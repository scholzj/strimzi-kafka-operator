/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.common.gatekeeper.impl;

import io.strimzi.plugin.gatekeeper.GatekeeperKafkaExitContext;

/**
 * Default implementation of the {@link GatekeeperKafkaExitContext} passed to the Kafka plugins at the end of a Kafka
 * reconciliation, both for the Kafka status and for the per-node-pool statuses. It currently carries no data and exists
 * so that the operator has a concrete context instance to pass to the plugins. Fields can be added later without
 * breaking the plugins.
 */
public class GatekeeperKafkaExitContextImpl implements GatekeeperKafkaExitContext {
    /**
     * Creates the Kafka exit context.
     */
    public GatekeeperKafkaExitContextImpl() { }
}
