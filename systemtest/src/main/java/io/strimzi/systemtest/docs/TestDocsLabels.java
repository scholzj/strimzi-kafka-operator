/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest.docs;

/**
 * Provides documentation labels used in {@link io.skodjob.annotations.SuiteDoc#labels()} or
 * {@link io.skodjob.annotations.TestDoc#labels()}.
 */
public interface TestDocsLabels {

    String BRIDGE = "bridge";
    String CONNECT = "connect";
    String CRUISE_CONTROL = "cruise-control";
}