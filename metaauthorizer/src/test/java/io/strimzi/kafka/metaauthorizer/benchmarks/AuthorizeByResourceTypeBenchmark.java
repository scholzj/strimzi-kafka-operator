/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafka.metaauthorizer.benchmarks;

import io.strimzi.kafka.metaauthorizer.TestRequestContext;
import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.resource.ResourceType;
import org.apache.kafka.metadata.authorizer.ClusterMetadataAuthorizer;
import org.apache.kafka.server.authorizer.AuthorizationResult;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.util.concurrent.TimeUnit;

/**
 * Benchmarks of the Authorizer#authorizeByResourceType method (used for example when initializing the idempotent
 * producers) comparing the StandardAuthorizer with super-users (baseline) with the Metaauthorizer delegating to the
 * StandardAuthorizer.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class AuthorizeByResourceTypeBenchmark {
    @Param({AuthorizerSetup.BASELINE, AuthorizerSetup.METAAUTHORIZER})
    private String authorizer;

    @Param({"0", "1000", "10000", "50000"})
    private int aclCount;

    private Metrics metrics;
    private ClusterMetadataAuthorizer auth;

    private TestRequestContext userContext;
    private TestRequestContext cruiseControlContext;

    /**
     * Creates the authorizer and the requests
     */
    @Setup(Level.Trial)
    public void setup() {
        metrics = new Metrics();
        auth = AuthorizerSetup.create(authorizer, aclCount, metrics);

        userContext = AuthorizerSetup.context(AuthorizerSetup.USER_LISTENER, AuthorizerSetup.REGULAR_USER);
        cruiseControlContext = AuthorizerSetup.context(AuthorizerSetup.REPLICATION_LISTENER, AuthorizerSetup.CRUISE_CONTROL_USER);
    }

    /**
     * Closes the authorizer
     *
     * @throws Exception    When closing fails
     */
    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
        auth.close();
        metrics.close();
    }

    /**
     * Regular user with prefixed WRITE ACL (idempotent producer check)
     *
     * @return  Authorization result
     */
    @Benchmark
    public AuthorizationResult userAllowed() {
        return auth.authorizeByResourceType(userContext, AclOperation.WRITE, ResourceType.TOPIC);
    }

    /**
     * Regular user without matching ACL
     *
     * @return  Authorization result
     */
    @Benchmark
    public AuthorizationResult userDenied() {
        return auth.authorizeByResourceType(userContext, AclOperation.WRITE, ResourceType.TRANSACTIONAL_ID);
    }

    /**
     * Cruise Control on the replication listener (idempotent producer check)
     *
     * @return  Authorization result
     */
    @Benchmark
    public AuthorizationResult internalUser() {
        return auth.authorizeByResourceType(cruiseControlContext, AclOperation.WRITE, ResourceType.TOPIC);
    }
}
