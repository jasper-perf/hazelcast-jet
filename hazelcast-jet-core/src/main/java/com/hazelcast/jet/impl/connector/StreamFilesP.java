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

package com.hazelcast.jet.impl.connector;

import com.hazelcast.jet.JetException;
import com.hazelcast.jet.core.AbstractProcessor;
import com.hazelcast.jet.core.CloseableProcessorSupplier;
import com.hazelcast.jet.core.ProcessorMetaSupplier;
import com.hazelcast.jet.impl.util.ReflectionUtils;
import com.hazelcast.logging.ILogger;

import javax.annotation.Nonnull;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.stream.IntStream;

import static com.hazelcast.jet.impl.util.ExceptionUtil.sneakyThrow;
import static com.hazelcast.jet.impl.util.LoggingUtil.logFine;
import static com.hazelcast.jet.impl.util.LoggingUtil.logFinest;
import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toList;

/**
 * Private API. Access via {@link
 * com.hazelcast.jet.core.processor.SourceProcessors#streamFilesP(String, Charset, String).
 * <p>
 * Since the work of this vertex is file IO-intensive, its {@link
 * com.hazelcast.jet.core.Vertex#localParallelism(int) local parallelism}
 * should be set according to the performance characteristics of the
 * underlying storage system. Modern high-end devices peak with 4-8 reading
 * threads, so if running a single Jet job with a single file-reading
 * vertex, the optimal value would be in the range of 4-8. Note that any
 * one file is only read by one thread, so extra parallelism won't improve
 * performance if there aren't enough files to read.
 */
public class StreamFilesP extends AbstractProcessor implements Closeable {

    /**
     * The amount of data read from one file at once must be limited
     * in order to prevent a possible {@link java.nio.file.StandardWatchEventKinds#OVERFLOW
     * OVERFLOW} if too many Watcher events accumulate in the queue. This
     * constant specifies the number of lines to read at once, before going
     * back to polling the event queue.
     */
    private static final int LINES_IN_ONE_BATCH = 64;
    private static final String SENSITIVITY_MODIFIER_CLASSNAME = "com.sun.nio.file.SensitivityWatchEventModifier";
    private static final WatchEvent.Kind[] WATCH_EVENT_KINDS = {ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE};
    private static final WatchEvent.Modifier[] WATCH_EVENT_MODIFIERS = getHighSensitivityModifiers();

    // exposed for testing
    final Map<Path, Long> fileOffsets = new HashMap<>();

    private final Path watchedDirectory;
    private final Charset charset;
    private final PathMatcher glob;
    private final int parallelism;

    private final int id;
    private final Queue<Path> eventQueue = new ArrayDeque<>();

    private WatchService watcher;
    private StringBuilder lineBuilder = new StringBuilder();
    private Path currentFile;
    private FileInputStream currentInputStream;
    private Reader currentReader;

    StreamFilesP(@Nonnull String watchedDirectory, @Nonnull Charset charset, @Nonnull String glob,
                 int parallelism, int id
    ) {
        this.watchedDirectory = Paths.get(watchedDirectory);
        this.charset = charset;
        this.glob = FileSystems.getDefault().getPathMatcher("glob:" + glob);
        this.parallelism = parallelism;
        this.id = id;
        setCooperative(false);
    }

    @Override
    protected void init(@Nonnull Context context) throws Exception {
        for (Path file : Files.newDirectoryStream(watchedDirectory)) {
            if (Files.isRegularFile(file)) {
                // Negative offset means "initial offset", needed to skip the first line
                fileOffsets.put(file, -Files.size(file));
            }
        }
        watcher = FileSystems.getDefault().newWatchService();
        watchedDirectory.register(watcher, WATCH_EVENT_KINDS, WATCH_EVENT_MODIFIERS);
        getLogger().info("Started to watch directory: " + watchedDirectory);
    }

    @Override
    public void close() {
        try {
            closeCurrentFile();
            if (isClosed()) {
                return;
            }
            getLogger().info("Closing StreamFilesP. Any pending watch events will be processed.");
            watcher.close();
        } catch (IOException e) {
            getLogger().severe("Failed to close StreamFilesP", e);
        } finally {
            watcher = null;
        }
    }

    @Override
    public boolean complete() {
        try {
            if (!isClosed()) {
                drainWatcherEvents();
            } else if (eventQueue.isEmpty()) {
                return true;
            }
            if (currentFile == null) {
                currentFile = eventQueue.poll();
            }
            if (currentFile != null) {
                processFile();
            }
            return false;
        } catch (InterruptedException e) {
            close();
            return true;
        }
    }

    private void drainWatcherEvents() throws InterruptedException {
        final ILogger logger = getLogger();
        // poll with blocking only when there is no other work to do
        final WatchKey key = (currentFile == null && eventQueue.isEmpty())
                ? watcher.poll(1, SECONDS)
                : watcher.poll();
        if (key == null) {
            if (!Files.exists(watchedDirectory)) {
                logger.info("Directory " + watchedDirectory + " does not exist, stopped watching");
                close();
            }
            return;
        }
        for (WatchEvent<?> event : key.pollEvents()) {
            final WatchEvent.Kind<?> kind = event.kind();
            final Path fileName = ((WatchEvent<Path>) event).context();
            final Path filePath = watchedDirectory.resolve(fileName);
            if (kind == ENTRY_CREATE || kind == ENTRY_MODIFY) {
                if (glob.matches(fileName) && belongsToThisProcessor(fileName) && !Files.isDirectory(filePath)) {
                    logFine(logger, "Will open file to read new content: %s", filePath);
                    eventQueue.add(filePath);
                }
            } else if (kind == ENTRY_DELETE) {
                logFinest(logger, "File was deleted: %s", filePath);
                fileOffsets.remove(filePath);
            } else if (kind == OVERFLOW) {
                logger.warning("Detected OVERFLOW in " + watchedDirectory);
            } else {
                throw new JetException("Unknown kind of WatchEvent: " + kind);
            }
        }
        if (!key.reset()) {
            logger.info("Watch key is invalid. Stopping watcher.");
            close();
        }
    }

    private boolean belongsToThisProcessor(Path path) {
        return ((path.hashCode() & Integer.MAX_VALUE) % parallelism) == id;
    }

    private void processFile() {
        try {
            if (!ensureFileOpen()) {
                return;
            }
            for (int i = 0; i < LINES_IN_ONE_BATCH; i++) {
                String line = readCompleteLine(currentReader);
                if (line == null) {
                    fileOffsets.put(currentFile, currentInputStream.getChannel().position());
                    closeCurrentFile();
                    break;
                }
                if (!tryEmit(line)) {
                    break;
                }
            }
        } catch (IOException e) {
            close();
            throw sneakyThrow(e);
        }
    }

    private boolean ensureFileOpen() throws IOException {
        if (currentReader != null) {
            return true;
        }
        long offset = fileOffsets.getOrDefault(currentFile, 0L);
        logFinest(getLogger(), "Processing file %s, previous offset: %,d", currentFile, offset);
        try {
            FileInputStream fis = new FileInputStream(currentFile.toFile());
            // Negative offset means we're reading the file for the first time.
            // We recover the actual offset by negating, then we subtract one
            // so as not to miss a preceding newline.
            fis.getChannel().position(offset >= 0 ? offset : -offset - 1);
            BufferedReader r = new BufferedReader(new InputStreamReader(fis, charset));
            if (offset < 0 && !findNextLine(r, offset)) {
                closeCurrentFile();
                return false;
            }
            currentReader = r;
            currentInputStream = fis;
            return true;
        } catch (FileNotFoundException ignored) {
            // This could be caused by ENTRY_MODIFY emitted on file deletion
            // just before ENTRY_DELETE
            closeCurrentFile();
            return false;
        }
    }

    private boolean findNextLine(Reader in, long offset) throws IOException {
        while (true) {
            int ch = in.read();
            if (ch < 0) {
                // we've hit EOF before finding the end of current line
                fileOffsets.put(currentFile, offset);
                return false;
            }
            if (ch == '\n' || ch == '\r') {
                maybeSkipLF(in, ch);
                return true;
            }
        }
    }

    /**
     * Reads a line from the input only if it is terminated by CR or LF or
     * CRLF. If it detects EOF before the newline character, returns
     * {@code null}.
     *
     * @return The line (possibly zero-length) or null on EOF.
     */
    // package-visible for testing
    String readCompleteLine(Reader reader) throws IOException {
        int ch;
        while ((ch = reader.read()) >= 0) {
            if (ch < 0) {
                break;
            }
            if (ch == '\r' || ch == '\n') {
                maybeSkipLF(reader, ch);
                try {
                    return lineBuilder.toString();
                } finally {
                    lineBuilder.setLength(0);
                }
            } else {
                lineBuilder.append((char) ch);
            }
        }
        // EOF
        return null;
    }

    private static void maybeSkipLF(Reader reader, int ch) throws IOException {
        // look ahead for possible '\n' after '\r' (windows end-line style)
        if (ch == '\r') {
            reader.mark(1);
            int ch2 = reader.read();
            if (ch2 != '\n') {
                reader.reset();
            }
        }
    }

    private void closeCurrentFile() {
        if (currentReader != null) {
            try {
                currentReader.close();
            } catch (IOException e) {
                throw sneakyThrow(e);
            }
        }
        currentFile = null;
        currentReader = null;
        currentInputStream = null;
    }

    private boolean isClosed() {
        return watcher == null;
    }

    /**
     * Private API. Use {@link
     * com.hazelcast.jet.core.processor.SourceProcessors#streamFilesP(String, Charset, String)}
     * instead.
     */
    @Nonnull
    public static ProcessorMetaSupplier metaSupplier(
            @Nonnull String watchedDirectory, @Nonnull String charset, @Nonnull String glob
    ) {
        return ProcessorMetaSupplier.of(new CloseableProcessorSupplier<>(
                count -> IntStream.range(0, count)
                        .mapToObj(i -> new StreamFilesP(watchedDirectory, Charset.forName(charset), glob, count, i))
                        .collect(toList())),
                2);
    }

    private static WatchEvent.Modifier[] getHighSensitivityModifiers() {
        // Modifiers for file watch service to achieve the highest possible sensitivity.
        // Background: Java 7 SE defines no standard modifiers for a watch service. However some JDKs use internal
        // modifiers to increase sensitivity. This field contains modifiers to be used for highest possible sensitivity.
        // It's JVM-specific and hence it's just a best-effort.
        // I believe this is useful on platforms without native watch service (or where Java does not use it) e.g. MacOSX
        Object modifier = ReflectionUtils.readStaticFieldOrNull(SENSITIVITY_MODIFIER_CLASSNAME, "HIGH");
        if (modifier instanceof WatchEvent.Modifier) {
            return new WatchEvent.Modifier[]{(WatchEvent.Modifier) modifier};
        }
        //bad luck, we did not find the modifier
        return new WatchEvent.Modifier[0];
    }
}
