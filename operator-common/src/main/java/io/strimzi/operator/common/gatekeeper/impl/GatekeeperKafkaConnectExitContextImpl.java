/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.common.gatekeeper.impl;

import io.strimzi.plugin.gatekeeper.GatekeeperKafkaConnectExitContext;

/**
 * Default implementation of the {@link GatekeeperKafkaConnectExitContext} passed to the KafkaConnect plugins at the end
 * of a KafkaConnect reconciliation, both for the KafkaConnect status and for the per-connector statuses. It currently
 * carries no data and exists so that the operator has a concrete context instance to pass to the plugins. Fields can be
 * added later without breaking the plugins.
 */
public class GatekeeperKafkaConnectExitContextImpl implements GatekeeperKafkaConnectExitContext {
    /**
     * Creates the KafkaConnect exit context.
     */
    public GatekeeperKafkaConnectExitContextImpl() { }
}
