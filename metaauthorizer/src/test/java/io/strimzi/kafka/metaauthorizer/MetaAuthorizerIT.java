/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafka.metaauthorizer;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.acl.AccessControlEntry;
import org.apache.kafka.common.acl.AclBinding;
import org.apache.kafka.common.acl.AclBindingFilter;
import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.acl.AclPermissionType;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.errors.ClusterAuthorizationException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.apache.kafka.common.network.ListenerName;
import org.apache.kafka.common.quota.ClientQuotaAlteration;
import org.apache.kafka.common.quota.ClientQuotaEntity;
import org.apache.kafka.common.resource.PatternType;
import org.apache.kafka.common.resource.ResourcePattern;
import org.apache.kafka.common.resource.ResourceType;
import org.apache.kafka.common.security.auth.SecurityProtocol;
import org.apache.kafka.common.test.KafkaClusterTestKit;
import org.apache.kafka.common.test.TestKitNodes;
import org.apache.kafka.metadata.authorizer.StandardAuthorizer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test running an in-JVM KRaft cluster (one broker and one separate controller) with the Metaauthorizer
 * delegating to the Kafka StandardAuthorizer. It verifies:
 * <ul>
 *     <li>The internal users are authorized using the hardcoded rules on the internal listeners</li>
 *     <li>The ACLs stored in the KRaft metadata are passed to the delegate authorizer</li>
 *     <li>Requests forwarded from the broker to the controller are authorized by the delegate</li>
 * </ul>
 *
 * The cluster uses the SASL PLAIN authentication on all listeners. The EXTERNAL listener is the user listener. The
 * REPLICATION and CONTROLLER listeners are the internal listeners.
 */
public class MetaAuthorizerIT {
    private static final String EXTERNAL = "EXTERNAL";
    private static final String REPLICATION = "REPLICATION";
    private static final String CONTROLLER = "CONTROLLER";
    private static final int BROKER_ID = 0;

    private static final String KAFKA_USER = "kafka";
    private static final String TOPIC_OPERATOR_USER = "topicoperator";
    private static final String USER_OPERATOR_USER = "useroperator";
    private static final String REGULAR_USER = "alice";

    private static final String JAAS_CONFIG = "org.apache.kafka.common.security.plain.PlainLoginModule required "
            + "username=\"" + KAFKA_USER + "\" password=\"" + password(KAFKA_USER) + "\" "
            + "user_" + KAFKA_USER + "=\"" + password(KAFKA_USER) + "\" "
            + "user_" + TOPIC_OPERATOR_USER + "=\"" + password(TOPIC_OPERATOR_USER) + "\" "
            + "user_" + USER_OPERATOR_USER + "=\"" + password(USER_OPERATOR_USER) + "\" "
            + "user_" + REGULAR_USER + "=\"" + password(REGULAR_USER) + "\";";

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static KafkaClusterTestKit cluster;

    private static String password(String user) {
        return user + "-secret";
    }

    @BeforeAll
    public static void startCluster() throws Exception {
        TestKitNodes nodes = new TestKitNodes.Builder()
                .setCombined(false)
                .setNumBrokerNodes(1)
                .setNumControllerNodes(1)
                .setBrokerListenerName(new ListenerName(EXTERNAL))
                .setBrokerSecurityProtocol(SecurityProtocol.SASL_PLAINTEXT)
                .setControllerListenerName(new ListenerName(CONTROLLER))
                .setControllerSecurityProtocol(SecurityProtocol.SASL_PLAINTEXT)
                .setPerServerProperties(Map.of(BROKER_ID, Map.of(
                        "listeners", EXTERNAL + "://localhost:0," + REPLICATION + "://localhost:0",
                        "listener.security.protocol.map", EXTERNAL + ":SASL_PLAINTEXT," + REPLICATION + ":SASL_PLAINTEXT," + CONTROLLER + ":SASL_PLAINTEXT",
                        "inter.broker.listener.name", REPLICATION
                )))
                .build();

        cluster = new KafkaClusterTestKit.Builder(nodes)
                .setConfigProp("authorizer.class.name", MetaAuthorizer.class.getName())
                .setConfigProp("strimzi.metaauthorizer.authorizer.class.name", StandardAuthorizer.class.getName())
                .setConfigProp("strimzi.metaauthorizer.listeners", REPLICATION + "," + CONTROLLER)
                .setConfigProp("strimzi.metaauthorizer.kafka.user", "User:" + KAFKA_USER)
                .setConfigProp("strimzi.metaauthorizer.topicoperator.user", "User:" + TOPIC_OPERATOR_USER)
                .setConfigProp("strimzi.metaauthorizer.useroperator.user", "User:" + USER_OPERATOR_USER)
                // No super-users => the internal users rely only on the Metaauthorizer rules
                .setConfigProp("super.users", "")
                .setConfigProp("allow.everyone.if.no.acl.found", "false")
                .setConfigProp("sasl.enabled.mechanisms", "PLAIN")
                .setConfigProp("sasl.mechanism.inter.broker.protocol", "PLAIN")
                .setConfigProp("sasl.mechanism.controller.protocol", "PLAIN")
                .setConfigProp("listener.name.external.plain.sasl.jaas.config", JAAS_CONFIG)
                .setConfigProp("listener.name.replication.plain.sasl.jaas.config", JAAS_CONFIG)
                .setConfigProp("listener.name.controller.plain.sasl.jaas.config", JAAS_CONFIG)
                .build();

        cluster.format();
        cluster.startup();
        cluster.waitForReadyBrokers();
    }

    @AfterAll
    public static void stopCluster() throws Exception {
        if (cluster != null) {
            cluster.close();
        }
    }

    private static Admin admin(String listener, String user) {
        int port = cluster.brokers().get(BROKER_ID).boundPort(ListenerName.normalised(listener));

        return Admin.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:" + port,
                AdminClientConfig.SECURITY_PROTOCOL_CONFIG, SecurityProtocol.SASL_PLAINTEXT.name,
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000,
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 20_000,
                SaslConfigs.SASL_MECHANISM, "PLAIN",
                SaslConfigs.SASL_JAAS_CONFIG, "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"" + user + "\" password=\"" + password(user) + "\";"
        ));
    }

    private static void createTopic(Admin admin, String name) throws ExecutionException, InterruptedException {
        admin.createTopics(List.of(new NewTopic(name, Optional.of(1), Optional.empty()))).all().get();
    }

    private static Throwable cause(ThrowingRunnable runnable) {
        ExecutionException e = assertThrows(ExecutionException.class, runnable::run);
        return e.getCause();
    }

    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Test
    public void testTopicOperatorCanManageTopicsOnInternalListener() throws Exception {
        try (Admin admin = admin(REPLICATION, TOPIC_OPERATOR_USER)) {
            // CreateTopics is forwarded to the controller where it arrives on the CONTROLLER listener
            createTopic(admin, "to-managed-topic");
            waitUntilTopicIsVisible(admin, "to-managed-topic");

            ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, "to-managed-topic");
            admin.incrementalAlterConfigs(Map.of(resource, List.of(new AlterConfigOp(new ConfigEntry("retention.ms", "3600000"), AlterConfigOp.OpType.SET)))).all().get();
            admin.describeConfigs(List.of(resource)).all().get();

            admin.listPartitionReassignments().reassignments().get();
            admin.deleteTopics(List.of("to-managed-topic")).all().get();
        }
    }

    @Test
    public void testTopicOperatorIsDeniedOutsideOfItsRules() {
        try (Admin admin = admin(REPLICATION, TOPIC_OPERATOR_USER)) {
            AclBinding binding = new AclBinding(new ResourcePattern(ResourceType.TOPIC, "x", PatternType.LITERAL),
                    new AccessControlEntry("User:" + REGULAR_USER, "*", AclOperation.READ, AclPermissionType.ALLOW));

            // CreateAcls needs ALTER on the cluster (checked on the controller)
            assertInstanceOf(ClusterAuthorizationException.class, cause(() -> admin.createAcls(List.of(binding)).all().get()));

            // AlterClientQuotas needs ALTER_CONFIGS on the cluster (checked on the controller)
            ClientQuotaAlteration quota = new ClientQuotaAlteration(new ClientQuotaEntity(Map.of(ClientQuotaEntity.USER, REGULAR_USER)),
                    List.of(new ClientQuotaAlteration.Op("producer_byte_rate", 1024.0)));
            assertInstanceOf(ClusterAuthorizationException.class, cause(() -> admin.alterClientQuotas(List.of(quota)).all().get()));
        }
    }

    @Test
    public void testRegularUserIsAuthorizedByDelegateIncludingForwardedRequests() throws Exception {
        AclBinding createAcl = new AclBinding(new ResourcePattern(ResourceType.TOPIC, "alice-", PatternType.PREFIXED),
                new AccessControlEntry("User:" + REGULAR_USER, "*", AclOperation.CREATE, AclPermissionType.ALLOW));
        AclBinding describeAcl = new AclBinding(new ResourcePattern(ResourceType.TOPIC, "alice-", PatternType.PREFIXED),
                new AccessControlEntry("User:" + REGULAR_USER, "*", AclOperation.DESCRIBE, AclPermissionType.ALLOW));

        try (Admin alice = admin(EXTERNAL, REGULAR_USER); Admin userOperator = admin(REPLICATION, USER_OPERATOR_USER)) {
            // Without ACLs, the regular user is denied
            assertInstanceOf(TopicAuthorizationException.class, cause(() -> createTopic(alice, "alice-denied")));

            // The User Operator creates the ACLs. They are stored in the KRaft metadata and passed to the delegate.
            userOperator.createAcls(List.of(createAcl, describeAcl)).all().get();

            try {
                // CreateTopics is forwarded to the controller. The controller authorizes it with alice's principal
                // on the CONTROLLER listener and has to delegate it to the StandardAuthorizer with the ACLs.
                waitUntilSucceeds(() -> createTopic(alice, "alice-topic"));
                // The broker authorizes the Metadata request locally on the user listener
                waitUntilTopicIsVisible(alice, "alice-topic");

                // Topics without ACLs are still denied
                assertInstanceOf(TopicAuthorizationException.class, cause(() -> createTopic(alice, "bob-topic")));
            } finally {
                userOperator.deleteAcls(List.of(AclBindingFilter.ANY)).all().get();
            }

            // The broker serves DescribeAcls from its local copy of the ACLs which is updated asynchronously
            waitUntilSucceeds(() -> {
                Collection<AclBinding> remaining = userOperator.describeAcls(AclBindingFilter.ANY).values().get();
                assertTrue(remaining.isEmpty(), "Some ACLs still exist: " + remaining);
            });
        }
    }

    @Test
    public void testInternalPrincipalIsNotPrivilegedOnUserListener() throws Exception {
        try (Admin internal = admin(REPLICATION, TOPIC_OPERATOR_USER); Admin external = admin(EXTERNAL, TOPIC_OPERATOR_USER)) {
            createTopic(internal, "to-visible-topic");
            waitUntilTopicIsVisible(internal, "to-visible-topic");

            // Requests handled directly by the broker are authorized by the delegate on the user listener
            Set<String> topics = external.listTopics().names().get();
            assertFalse(topics.contains("to-visible-topic"));

            ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, "to-visible-topic");
            assertInstanceOf(TopicAuthorizationException.class, cause(() -> external.describeConfigs(List.of(resource)).all().get()));

            internal.deleteTopics(List.of("to-visible-topic")).all().get();
        }
    }

    /**
     * Documents the known limitation: Requests forwarded by the broker to the controller without being authorized on
     * the broker arrive at the controller with the original principal on the control plane listener. The controller
     * cannot distinguish them from requests sent directly to the internal listener. A client using the same principal
     * as an internal component on a user listener therefore gets the internal rules for the forwarded requests (this
     * is the same as with the internal users configured as super-users). This applies for example to CreateTopics
     * and to IncrementalAlterConfigs for topics (the broker pre-authorizes only the BROKER and BROKER_LOGGER resources).
     */
    @Test
    public void testKnownLimitationForwardedRequestFromInternalPrincipalOnUserListener() throws Exception {
        try (Admin external = admin(EXTERNAL, TOPIC_OPERATOR_USER); Admin internal = admin(REPLICATION, TOPIC_OPERATOR_USER)) {
            createTopic(external, "to-forwarded-topic");
            waitUntilTopicIsVisible(internal, "to-forwarded-topic");

            ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, "to-forwarded-topic");
            external.incrementalAlterConfigs(Map.of(resource, List.of(new AlterConfigOp(new ConfigEntry("retention.ms", "3600000"), AlterConfigOp.OpType.SET)))).all().get();

            internal.deleteTopics(List.of("to-forwarded-topic")).all().get();
        }
    }

    private static void waitUntilTopicIsVisible(Admin admin, String topic) throws Exception {
        waitUntilSucceeds(() -> assertTrue(admin.listTopics().names().get().contains(topic), "Topic " + topic + " is not visible"));
    }

    private static void waitUntilSucceeds(ThrowingRunnable runnable) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();

        while (true) {
            try {
                runnable.run();
                return;
            } catch (Exception | AssertionError e) {
                if (System.nanoTime() > deadline) {
                    throw e;
                }

                Thread.sleep(200);
            }
        }
    }
}
