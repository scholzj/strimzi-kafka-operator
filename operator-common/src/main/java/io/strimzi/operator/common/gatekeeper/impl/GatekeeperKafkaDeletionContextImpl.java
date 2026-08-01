/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.common.gatekeeper.impl;

import io.strimzi.plugin.gatekeeper.GatekeeperKafkaDeletionContext;

/**
 * Default implementation of the {@link GatekeeperKafkaDeletionContext} passed to the Kafka plugins when a Kafka is being
 * deleted. It currently carries no data and exists so that the operator has a concrete context instance to pass to the
 * plugins. Fields can be added later without breaking the plugins.
 */
public class GatekeeperKafkaDeletionContextImpl implements GatekeeperKafkaDeletionContext {
    /**
     * Creates the Kafka deletion context.
     */
    public GatekeeperKafkaDeletionContextImpl() { }
}
