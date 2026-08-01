/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.common.gatekeeper.impl;

import io.strimzi.plugin.gatekeeper.GatekeeperKafkaBridgeEntryContext;

/**
 * Default implementation of the {@link GatekeeperKafkaBridgeEntryContext} passed to the KafkaBridge plugins at the start
 * of a KafkaBridge reconciliation. It currently carries no data and exists so that the operator has a concrete context
 * instance to pass to the plugins. Fields can be added later without breaking the plugins.
 */
public class GatekeeperKafkaBridgeEntryContextImpl implements GatekeeperKafkaBridgeEntryContext {
    /**
     * Creates the KafkaBridge entry context.
     */
    public GatekeeperKafkaBridgeEntryContextImpl() { }
}
