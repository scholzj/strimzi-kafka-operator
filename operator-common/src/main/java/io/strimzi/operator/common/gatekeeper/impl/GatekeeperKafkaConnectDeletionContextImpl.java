/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.common.gatekeeper.impl;

import io.strimzi.plugin.gatekeeper.GatekeeperKafkaConnectDeletionContext;

/**
 * Default implementation of the {@link GatekeeperKafkaConnectDeletionContext} passed to the KafkaConnect plugins when a KafkaConnect is being
 * deleted. It currently carries no data and exists so that the operator has a concrete context instance to pass to the
 * plugins. Fields can be added later without breaking the plugins.
 */
public class GatekeeperKafkaConnectDeletionContextImpl implements GatekeeperKafkaConnectDeletionContext {
    /**
     * Creates the KafkaConnect deletion context.
     */
    public GatekeeperKafkaConnectDeletionContextImpl() { }
}
