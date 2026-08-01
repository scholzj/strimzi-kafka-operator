/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.common.gatekeeper.impl;

import io.strimzi.plugin.gatekeeper.GatekeeperKafkaBridgeDeletionContext;

/**
 * Default implementation of the {@link GatekeeperKafkaBridgeDeletionContext} passed to the KafkaBridge plugins when a KafkaBridge is being
 * deleted. It currently carries no data and exists so that the operator has a concrete context instance to pass to the
 * plugins. Fields can be added later without breaking the plugins.
 */
public class GatekeeperKafkaBridgeDeletionContextImpl implements GatekeeperKafkaBridgeDeletionContext {
    /**
     * Creates the KafkaBridge deletion context.
     */
    public GatekeeperKafkaBridgeDeletionContextImpl() { }
}
