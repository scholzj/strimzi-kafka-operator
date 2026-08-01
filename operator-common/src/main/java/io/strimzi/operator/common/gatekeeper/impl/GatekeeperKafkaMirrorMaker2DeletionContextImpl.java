/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.common.gatekeeper.impl;

import io.strimzi.plugin.gatekeeper.GatekeeperKafkaMirrorMaker2DeletionContext;

/**
 * Default implementation of the {@link GatekeeperKafkaMirrorMaker2DeletionContext} passed to the KafkaMirrorMaker2 plugins when a KafkaMirrorMaker2 is being
 * deleted. It currently carries no data and exists so that the operator has a concrete context instance to pass to the
 * plugins. Fields can be added later without breaking the plugins.
 */
public class GatekeeperKafkaMirrorMaker2DeletionContextImpl implements GatekeeperKafkaMirrorMaker2DeletionContext {
    /**
     * Creates the KafkaMirrorMaker2 deletion context.
     */
    public GatekeeperKafkaMirrorMaker2DeletionContextImpl() { }
}
