/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.common.gatekeeper;

import io.strimzi.plugin.gatekeeper.GatekeeperKafkaMutatingPlugin;
import io.strimzi.plugin.gatekeeper.GatekeeperPluginConfigurationContext;

/**
 * Test Gatekeeper plugin which implements a type-specific plugin interface. It is used to verify that the factory can
 * return the plugins filtered by their type while keeping the ordering.
 */
public class TestGatekeeperKafkaMutatingPlugin implements GatekeeperKafkaMutatingPlugin {
    private int configureCount = 0;

    @Override
    public void configure(GatekeeperPluginConfigurationContext context) {
        this.configureCount++;
    }

    public int configureCount() {
        return configureCount;
    }
}
