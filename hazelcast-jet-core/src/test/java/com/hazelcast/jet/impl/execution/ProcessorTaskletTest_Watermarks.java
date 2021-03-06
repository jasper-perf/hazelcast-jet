/*
 * Copyright (c) 2008-2017, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.impl.execution;

import com.hazelcast.jet.core.Inbox;
import com.hazelcast.jet.core.Outbox;
import com.hazelcast.jet.core.Processor;
import com.hazelcast.jet.core.Watermark;
import com.hazelcast.jet.core.test.TestOutbox.MockSerializationService;
import com.hazelcast.jet.impl.execution.init.Contexts.ProcCtx;
import com.hazelcast.jet.impl.util.ProgressState;
import com.hazelcast.logging.ILogger;
import com.hazelcast.test.HazelcastParallelClassRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

import static com.hazelcast.jet.config.ProcessingGuarantee.EXACTLY_ONCE;
import static com.hazelcast.jet.impl.util.ProgressState.MADE_PROGRESS;
import static com.hazelcast.jet.impl.util.ProgressState.NO_PROGRESS;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

@RunWith(HazelcastParallelClassRunner.class)
public class ProcessorTaskletTest_Watermarks {

    private static final int CALL_COUNT_LIMIT = 10;

    private List<MockInboundStream> instreams;
    private List<OutboundEdgeStream> outstreams;
    private ProcessorWithWatermarks processor;
    private ProcCtx context;
    private MockOutboundCollector snapshotCollector;

    @Before
    public void setUp() {
        this.processor = new ProcessorWithWatermarks();
        this.context = new ProcCtx(null, new MockSerializationService(), null, null, 0,
                EXACTLY_ONCE);
        this.instreams = new ArrayList<>();
        this.outstreams = new ArrayList<>();
        this.snapshotCollector = new MockOutboundCollector(0);
    }

    @Test
    public void when_isCooperative_then_true() {
        assertTrue(createTasklet(-1).isCooperative());
    }

    @Test
    public void when_singleInbound_then_watermarkForwardedImmediately() {
        // Given
        List<Object> input = new ArrayList<>(asList(0, 1));
        input.add(wm(123));
        MockInboundStream instream1 = new MockInboundStream(0, input, input.size());
        MockOutboundStream outstream1 = new MockOutboundStream(0);

        instreams.add(instream1);
        outstreams.add(outstream1);

        ProcessorTasklet tasklet = createTasklet(-1);

        // When
        callUntil(400, tasklet, NO_PROGRESS);

        // Then
        assertEquals(asList(0, 1, "wm(123)-0", wm(123)), outstream1.getBuffer());
    }

    @Test
    public void when_multipleInboundAndUnlimitedRetention_then_waitForWm() {
        // Given
        List<Object> input1 = asList(0, 1, wm(100), 2, 3);
        List<Object> input2 = new ArrayList<>();

        MockInboundStream instream1 = new MockInboundStream(0, input1, 1024);
        MockInboundStream instream2 = new MockInboundStream(0, input2, 1024);
        MockOutboundStream outstream1 = new MockOutboundStream(0);

        instreams.add(instream1);
        instreams.add(instream2);
        outstreams.add(outstream1);

        ProcessorTasklet tasklet = createTasklet(-1);

        // When
        callUntil(400, tasklet, NO_PROGRESS);

        // Then
        assertEquals(asList(0, 1, 2, 3), outstream1.getBuffer());
        outstream1.flush();

        // 100 ms later still no progress - we are waiting for the WM
        callUntil(500, tasklet, NO_PROGRESS);
        assertEquals(emptyList(), outstream1.getBuffer());

        // When watermark in the other queue
        instream2.push(wm(99));
        callUntil(500, tasklet, NO_PROGRESS);
        assertEquals(asList("wm(99)-0", wm(99)), outstream1.getBuffer());
    }

    @Test
    public void when_processWatermarkReturnsFalse_then_calledAgain() {
        // Given
        MockInboundStream instream1 = new MockInboundStream(0, singletonList(wm(100)), 1000);
        MockOutboundStream outstream1 = new MockOutboundStream(0, 128);
        instreams.add(instream1);
        outstreams.add(outstream1);
        ProcessorTasklet tasklet = createTasklet(-1);
        processor.processWatermarkCallCountdown = 3;

        // When
        callUntil(400, tasklet, NO_PROGRESS);

        // Then
        assertEquals(asList("wm(100)-3", "wm(100)-2", "wm(100)-1", wm(100)), outstream1.getBuffer());
    }

    @Test
    public void when_multipleWms_then_processed() {
        // Given
        MockInboundStream instream1 = new MockInboundStream(0, asList(wm(100), wm(101)), 1000);
        MockOutboundStream outstream1 = new MockOutboundStream(0, 128);
        instreams.add(instream1);
        outstreams.add(outstream1);
        ProcessorTasklet tasklet = createTasklet(-1);

        // When
        callUntil(400, tasklet, NO_PROGRESS);

        // Then
        assertEquals(asList("wm(100)-0", wm(100), "wm(101)-0", wm(101)), outstream1.getBuffer());
    }

    @Test
    public void when_noWmOnOneQueue_then_processedAfterMaxRetainTime() {
        // Given
        MockInboundStream instream1 = new MockInboundStream(0, emptyList(), 1000);
        MockInboundStream instream2 = new MockInboundStream(0, singletonList(wm(100)), 1000);
        MockOutboundStream outstream1 = new MockOutboundStream(0, 128);
        instreams.add(instream1);
        instreams.add(instream2);
        outstreams.add(outstream1);
        ProcessorTasklet tasklet = createTasklet(16);

        // When
        callUntil(400, tasklet, NO_PROGRESS);
        callUntil(416, tasklet, NO_PROGRESS);
        // Then
        assertEquals(asList("wm(100)-0", wm(100)), outstream1.getBuffer());
    }

    private ProcessorTasklet createTasklet(int maxWatermarkRetainMillis) {
        for (int i = 0; i < instreams.size(); i++) {
            instreams.get(i).setOrdinal(i);
        }
        SnapshotContext snapshotContext = new SnapshotContext(mock(ILogger.class), 0, 0, -1, EXACTLY_ONCE);
        snapshotContext.initTaskletCount(1, 0);
        final ProcessorTasklet t = new ProcessorTasklet(context, processor, instreams, outstreams,
                snapshotContext, snapshotCollector, maxWatermarkRetainMillis);
        t.init();
        return t;
    }

    private static void callUntil(long now, ProcessorTasklet tasklet, ProgressState expectedState) {
        int iterCount = 0;
        for (ProgressState r; (r = tasklet.call(MILLISECONDS.toNanos(now))) != expectedState; ) {
            assertEquals("Failed to make progress", MADE_PROGRESS, r);
            assertTrue(String.format(
                    "tasklet.call() invoked %d times without reaching %s. Last state was %s",
                    CALL_COUNT_LIMIT, expectedState, r),
                    ++iterCount < CALL_COUNT_LIMIT);
        }
    }

    private Watermark wm(long timestamp) {
        return new Watermark(timestamp);
    }

    private static class ProcessorWithWatermarks implements Processor {

        int nullaryProcessCallCountdown;
        int processWatermarkCallCountdown;
        private Outbox outbox;

        @Override
        public void init(@Nonnull Outbox outbox, @Nonnull Context context) {
            this.outbox = outbox;
        }

        @Override
        public void process(int ordinal, @Nonnull Inbox inbox) {
            for (Object item; (item = inbox.peek()) != null; ) {
                if (outbox.offer(item)) {
                    inbox.remove();
                }
            }
        }

        @Override
        public boolean complete() {
            return true;
        }

        @Override
        public boolean tryProcessWatermark(@Nonnull Watermark watermark) {
            if (outbox.offer("wm(" + watermark.timestamp() + ")-" + processWatermarkCallCountdown)) {
                if (processWatermarkCallCountdown > 0) {
                    processWatermarkCallCountdown--;
                }
                return processWatermarkCallCountdown <= 0;
            }
            return false;
        }

        @Override
        public boolean tryProcess() {
            return nullaryProcessCallCountdown-- <= 0;
        }
    }
}
