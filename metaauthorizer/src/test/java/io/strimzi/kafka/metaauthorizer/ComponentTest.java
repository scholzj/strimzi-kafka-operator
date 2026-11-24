/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafka.metaauthorizer;

import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.resource.ResourceType;
import org.apache.kafka.server.authorizer.AuthorizationResult;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.apache.kafka.common.acl.AclOperation.ALTER;
import static org.apache.kafka.common.acl.AclOperation.ALTER_CONFIGS;
import static org.apache.kafka.common.acl.AclOperation.CREATE;
import static org.apache.kafka.common.acl.AclOperation.DELETE;
import static org.apache.kafka.common.acl.AclOperation.DESCRIBE;
import static org.apache.kafka.common.acl.AclOperation.DESCRIBE_CONFIGS;
import static org.apache.kafka.common.acl.AclOperation.READ;
import static org.apache.kafka.common.acl.AclOperation.WRITE;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests the hardcoded rules of the individual components. For each component, it checks all combinations of the
 * operations and resource types and verifies that exactly the expected operations are allowed and everything else is
 * denied.
 */
public class ComponentTest {
    private static final String CLUSTER = "kafka-cluster";
    private static final String TOPIC = "my-topic";
    private static final String GROUP = "my-group";
    private static final String CC_TOPIC = "strimzi.cruisecontrol.metrics";
    private static final String CUSTOM_CC_TOPIC = "my-cc-metrics";

    private static final List<AclOperation> OPERATIONS = Arrays.stream(AclOperation.values())
            .filter(op -> op != AclOperation.ANY && op != AclOperation.UNKNOWN && op != AclOperation.ALL)
            .toList();
    private static final List<ResourceType> RESOURCE_TYPES = Arrays.stream(ResourceType.values())
            .filter(type -> type != ResourceType.ANY && type != ResourceType.UNKNOWN)
            .toList();
    private static final List<String> RESOURCE_NAMES = List.of(CLUSTER, TOPIC, GROUP, CC_TOPIC, CUSTOM_CC_TOPIC, "my-transactional-id");

    private static final Set<String> DEFAULT_CC_TOPICS = Set.of(MetaAuthorizerConfig.DEFAULT_CRUISE_CONTROL_TOPICS.split(","));

    /**
     * Describes the expected allowed operations for given resource type and name
     */
    @FunctionalInterface
    interface Expected {
        Set<AclOperation> allowed(ResourceType type, String name);
    }

    private static void assertRules(Component component, Set<String> ccTopics, Expected expected) {
        InternalUser user = new InternalUser("User:test", Set.of(component), ccTopics);

        for (ResourceType type : RESOURCE_TYPES) {
            for (String name : RESOURCE_NAMES) {
                Set<AclOperation> allowed = expected.allowed(type, name);

                for (AclOperation op : OPERATIONS) {
                    AuthorizationResult expectedResult = allowed.contains(op) ? AuthorizationResult.ALLOWED : AuthorizationResult.DENIED;
                    assertEquals(expectedResult, user.authorize(op, type, name),
                            component + ": unexpected result for " + op + " on " + type + ":" + name);
                }
            }
        }
    }

    private static Set<AclOperation> ops(AclOperation... ops) {
        return ops.length == 0 ? EnumSet.noneOf(AclOperation.class) : EnumSet.copyOf(Arrays.asList(ops));
    }

    @Test
    public void testKafkaIsAllowedEverything() {
        assertRules(Component.KAFKA, DEFAULT_CC_TOPICS, (type, name) -> EnumSet.copyOf(OPERATIONS));
    }

    @Test
    public void testClusterOperator() {
        assertRules(Component.CLUSTER_OPERATOR, DEFAULT_CC_TOPICS, (type, name) -> switch (type) {
            case CLUSTER -> ops(DESCRIBE, DESCRIBE_CONFIGS, ALTER, ALTER_CONFIGS);
            case TOPIC -> ops(DESCRIBE, DESCRIBE_CONFIGS);
            default -> ops();
        });
    }

    @Test
    public void testTopicOperator() {
        assertRules(Component.TOPIC_OPERATOR, DEFAULT_CC_TOPICS, (type, name) -> switch (type) {
            case CLUSTER -> ops(DESCRIBE, DESCRIBE_CONFIGS);
            case TOPIC -> ops(CREATE, DELETE, ALTER, DESCRIBE, DESCRIBE_CONFIGS, ALTER_CONFIGS);
            default -> ops();
        });
    }

    @Test
    public void testUserOperator() {
        assertRules(Component.USER_OPERATOR, DEFAULT_CC_TOPICS, (type, name) -> switch (type) {
            case CLUSTER -> ops(DESCRIBE, DESCRIBE_CONFIGS, ALTER, ALTER_CONFIGS);
            default -> ops();
        });
    }

    @Test
    public void testCruiseControlWithDefaultTopics() {
        assertRules(Component.CRUISE_CONTROL, DEFAULT_CC_TOPICS, (type, name) -> switch (type) {
            case CLUSTER -> ops(DESCRIBE, DESCRIBE_CONFIGS, ALTER, ALTER_CONFIGS);
            case TOPIC -> CC_TOPIC.equals(name)
                    ? ops(CREATE, READ, WRITE, ALTER, DESCRIBE, DESCRIBE_CONFIGS, ALTER_CONFIGS)
                    : ops(DESCRIBE, DESCRIBE_CONFIGS, ALTER_CONFIGS);
            default -> ops();
        });
    }

    @Test
    public void testCruiseControlWithCustomTopics() {
        assertRules(Component.CRUISE_CONTROL, Set.of(CUSTOM_CC_TOPIC), (type, name) -> switch (type) {
            case CLUSTER -> ops(DESCRIBE, DESCRIBE_CONFIGS, ALTER, ALTER_CONFIGS);
            case TOPIC -> CUSTOM_CC_TOPIC.equals(name)
                    ? ops(CREATE, READ, WRITE, ALTER, DESCRIBE, DESCRIBE_CONFIGS, ALTER_CONFIGS)
                    : ops(DESCRIBE, DESCRIBE_CONFIGS, ALTER_CONFIGS);
            default -> ops();
        });
    }

    @Test
    public void testKafkaExporter() {
        assertRules(Component.KAFKA_EXPORTER, DEFAULT_CC_TOPICS, (type, name) -> switch (type) {
            case CLUSTER, TOPIC, GROUP -> ops(DESCRIBE);
            default -> ops();
        });
    }

    @Test
    public void testSharedPrincipalGetsUnionOfRules() {
        // The Topic and User Operators share the Entity Operator principal in the Service Account authentication mode
        InternalUser user = new InternalUser("User:entity-operator", Set.of(Component.TOPIC_OPERATOR, Component.USER_OPERATOR), DEFAULT_CC_TOPICS);

        // From the Topic Operator
        assertEquals(AuthorizationResult.ALLOWED, user.authorize(CREATE, ResourceType.TOPIC, TOPIC));
        // From the User Operator
        assertEquals(AuthorizationResult.ALLOWED, user.authorize(ALTER, ResourceType.CLUSTER, CLUSTER));
        assertEquals(AuthorizationResult.ALLOWED, user.authorize(ALTER_CONFIGS, ResourceType.CLUSTER, CLUSTER));
        // Neither
        assertEquals(AuthorizationResult.DENIED, user.authorize(READ, ResourceType.TOPIC, TOPIC));
        assertEquals(AuthorizationResult.DENIED, user.authorize(READ, ResourceType.GROUP, GROUP));
    }

    @Test
    public void testSharedPrincipalWithKafkaIsAllowedEverything() {
        InternalUser user = new InternalUser("User:shared", Set.of(Component.KAFKA_EXPORTER, Component.KAFKA), DEFAULT_CC_TOPICS);

        assertEquals(AuthorizationResult.ALLOWED, user.authorize(WRITE, ResourceType.TOPIC, TOPIC));
        assertEquals(AuthorizationResult.ALLOWED, user.authorizeByResourceType(WRITE, ResourceType.TRANSACTIONAL_ID));
    }

    @Test
    public void testAuthorizeByResourceType() {
        Map<Component, Map<ResourceType, Set<AclOperation>>> expected = Map.of(
                Component.CRUISE_CONTROL, Map.of(ResourceType.TOPIC, ops(WRITE, READ, CREATE, ALTER, DESCRIBE, DESCRIBE_CONFIGS, ALTER_CONFIGS)),
                Component.TOPIC_OPERATOR, Map.of(ResourceType.TOPIC, ops(CREATE, DELETE, ALTER, DESCRIBE, DESCRIBE_CONFIGS, ALTER_CONFIGS)),
                Component.KAFKA_EXPORTER, Map.of(ResourceType.TOPIC, ops(DESCRIBE), ResourceType.GROUP, ops(DESCRIBE))
        );

        expected.forEach((component, byType) -> {
            InternalUser user = new InternalUser("User:test", Set.of(component), DEFAULT_CC_TOPICS);

            for (ResourceType type : List.of(ResourceType.TOPIC, ResourceType.GROUP, ResourceType.TRANSACTIONAL_ID)) {
                Set<AclOperation> allowed = byType.getOrDefault(type, ops());

                for (AclOperation op : OPERATIONS) {
                    AuthorizationResult expectedResult = allowed.contains(op) ? AuthorizationResult.ALLOWED : AuthorizationResult.DENIED;
                    assertEquals(expectedResult, user.authorizeByResourceType(op, type),
                            component + ": unexpected result for " + op + " on " + type);
                }
            }
        });
    }

    @Test
    public void testUserConfigKeys() {
        assertEquals("strimzi.metaauthorizer.kafka.user", Component.KAFKA.userConfigKey());
        assertEquals("strimzi.metaauthorizer.clusteroperator.user", Component.CLUSTER_OPERATOR.userConfigKey());
        assertEquals("strimzi.metaauthorizer.topicoperator.user", Component.TOPIC_OPERATOR.userConfigKey());
        assertEquals("strimzi.metaauthorizer.useroperator.user", Component.USER_OPERATOR.userConfigKey());
        assertEquals("strimzi.metaauthorizer.cruisecontrol.user", Component.CRUISE_CONTROL.userConfigKey());
        assertEquals("strimzi.metaauthorizer.kafkaexporter.user", Component.KAFKA_EXPORTER.userConfigKey());
    }
}
