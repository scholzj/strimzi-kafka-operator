/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.common.gatekeeper.impl;

import io.strimzi.plugin.gatekeeper.GatekeeperKafkaMirrorMaker2EntryContext;

/**
 * Default implementation of the {@link GatekeeperKafkaMirrorMaker2EntryContext} passed to the KafkaMirrorMaker2 plugins
 * at the start of a KafkaMirrorMaker2 reconciliation. It currently carries no data and exists so that the operator has a
 * concrete context instance to pass to the plugins. Fields can be added later without breaking the plugins.
 */
public class GatekeeperKafkaMirrorMaker2EntryContextImpl implements GatekeeperKafkaMirrorMaker2EntryContext {
    /**
     * Creates the KafkaMirrorMaker2 entry context.
     */
    public GatekeeperKafkaMirrorMaker2EntryContextImpl() { }
}
