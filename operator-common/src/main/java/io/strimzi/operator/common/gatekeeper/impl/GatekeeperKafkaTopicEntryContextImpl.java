/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.common.gatekeeper.impl;

import io.strimzi.plugin.gatekeeper.GatekeeperKafkaTopicEntryContext;

/**
 * Default implementation of the {@link GatekeeperKafkaTopicEntryContext} passed to the KafkaTopic plugins at the start
 * of a KafkaTopic reconciliation. It currently carries no data and exists so that the operator has a concrete context
 * instance to pass to the plugins. Fields can be added later without breaking the plugins.
 */
public class GatekeeperKafkaTopicEntryContextImpl implements GatekeeperKafkaTopicEntryContext {
    /**
     * Creates the KafkaTopic entry context.
     */
    public GatekeeperKafkaTopicEntryContextImpl() { }
}
