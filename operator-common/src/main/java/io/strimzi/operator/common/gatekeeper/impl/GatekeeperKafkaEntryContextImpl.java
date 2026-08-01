/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.common.gatekeeper.impl;

import io.strimzi.plugin.gatekeeper.GatekeeperKafkaEntryContext;

/**
 * Default implementation of the {@link GatekeeperKafkaEntryContext} passed to the Kafka plugins at the start of a Kafka
 * reconciliation. It currently carries no data and exists so that the operator has a concrete context instance to pass
 * to the plugins. Fields can be added later without breaking the plugins.
 */
public class GatekeeperKafkaEntryContextImpl implements GatekeeperKafkaEntryContext {
    /**
     * Creates the Kafka entry context.
     */
    public GatekeeperKafkaEntryContextImpl() { }
}
