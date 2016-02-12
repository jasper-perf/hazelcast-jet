/*
 * Copyright (c) 2008-2016, Hazelcast, Inc. All Rights Reserved.
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

package com.hazelcast.jet.api;

import java.util.Collection;

import com.hazelcast.nio.Address;
import com.hazelcast.jet.spi.config.JetApplicationConfig;
import com.hazelcast.jet.api.application.ApplicationContext;
import com.hazelcast.jet.api.executor.SharedApplicationExecutor;

public interface JetApplicationManager {
    Address getLocalJetAddress();

    void destroyApplication(String name);

    SharedApplicationExecutor getNetworkExecutor();

    SharedApplicationExecutor getProcessingExecutor();

    SharedApplicationExecutor getAcceptorExecutor();

    ApplicationContext getApplicationContext(String name);

    Collection<ApplicationContext> getApplicationContexts();

    ApplicationContext createOrGetApplicationContext(String name, JetApplicationConfig config);
}