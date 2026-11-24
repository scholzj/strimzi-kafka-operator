/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafka.metaauthorizer.benchmarks;

import io.strimzi.kafka.metaauthorizer.TestRequestContext;
import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.metadata.authorizer.ClusterMetadataAuthorizer;
import org.apache.kafka.server.authorizer.Action;
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

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Benchmarks of the Authorizer#authorize method comparing the StandardAuthorizer with super-users (baseline) with the
 * Metaauthorizer delegating to the StandardAuthorizer. Each benchmark method represents one scenario. Each call
 * authorizes the given number of actions on different topics.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class AuthorizeBenchmark {
    @Param({AuthorizerSetup.BASELINE, AuthorizerSetup.METAAUTHORIZER})
    private String authorizer;

    @Param({"0", "1000", "10000", "50000"})
    private int aclCount;

    @Param({"1", "10", "50"})
    private int actions;

    private Metrics metrics;
    private ClusterMetadataAuthorizer auth;

    private TestRequestContext userContext;
    private TestRequestContext userForwardedContext;
    private TestRequestContext kafkaContext;
    private TestRequestContext topicOperatorContext;

    private List<Action> allowedReads;
    private List<Action> deniedReads;
    private List<Action> describeConfigs;

    /**
     * Creates the authorizer and the requests
     */
    @Setup(Level.Trial)
    public void setup() {
        metrics = new Metrics();
        auth = AuthorizerSetup.create(authorizer, aclCount, metrics);

        userContext = AuthorizerSetup.context(AuthorizerSetup.USER_LISTENER, AuthorizerSetup.REGULAR_USER);
        userForwardedContext = AuthorizerSetup.context(AuthorizerSetup.CONTROLPLANE_LISTENER, AuthorizerSetup.REGULAR_USER);
        kafkaContext = AuthorizerSetup.context(AuthorizerSetup.REPLICATION_LISTENER, AuthorizerSetup.KAFKA_USER);
        topicOperatorContext = AuthorizerSetup.context(AuthorizerSetup.REPLICATION_LISTENER, AuthorizerSetup.TOPIC_OPERATOR_USER);

        allowedReads = AuthorizerSetup.topicActions(AclOperation.READ, "alice-topic-", actions);
        deniedReads = AuthorizerSetup.topicActions(AclOperation.READ, "other-topic-", actions);
        describeConfigs = AuthorizerSetup.topicActions(AclOperation.DESCRIBE_CONFIGS, "alice-topic-", actions);
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
     * Regular user on a user listener allowed by an ACL (produce / fetch hot path)
     *
     * @return  Authorization results
     */
    @Benchmark
    public List<AuthorizationResult> userAllowed() {
        return auth.authorize(userContext, allowedReads);
    }

    /**
     * Regular user on a user listener without matching ACL
     *
     * @return  Authorization results
     */
    @Benchmark
    public List<AuthorizationResult> userDenied() {
        return auth.authorize(userContext, deniedReads);
    }

    /**
     * Regular user on the control plane listener (request forwarded from a broker to the controller)
     *
     * @return  Authorization results
     */
    @Benchmark
    public List<AuthorizationResult> userForwarded() {
        return auth.authorize(userForwardedContext, allowedReads);
    }

    /**
     * Kafka node on the replication listener (super-user in the baseline, allow-all rule in the Metaauthorizer)
     *
     * @return  Authorization results
     */
    @Benchmark
    public List<AuthorizationResult> kafkaNode() {
        return auth.authorize(kafkaContext, allowedReads);
    }

    /**
     * Topic Operator on the replication listener doing an operation allowed by its rules
     *
     * @return  Authorization results
     */
    @Benchmark
    public List<AuthorizationResult> operatorAllowed() {
        return auth.authorize(topicOperatorContext, describeConfigs);
    }

    /**
     * Topic Operator on the replication listener doing an operation outside of its rules (allowed as super-user in
     * the baseline, strict deny in the Metaauthorizer)
     *
     * @return  Authorization results
     */
    @Benchmark
    public List<AuthorizationResult> operatorDenied() {
        return auth.authorize(topicOperatorContext, allowedReads);
    }
}
