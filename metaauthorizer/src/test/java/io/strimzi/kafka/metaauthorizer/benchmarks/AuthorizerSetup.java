/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafka.metaauthorizer.benchmarks;

import io.strimzi.kafka.metaauthorizer.MetaAuthorizer;
import io.strimzi.kafka.metaauthorizer.TestRequestContext;
import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.acl.AclPermissionType;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.metrics.Monitorable;
import org.apache.kafka.common.metrics.internals.PluginMetricsImpl;
import org.apache.kafka.common.resource.PatternType;
import org.apache.kafka.common.resource.ResourcePattern;
import org.apache.kafka.common.resource.ResourceType;
import org.apache.kafka.metadata.authorizer.ClusterMetadataAuthorizer;
import org.apache.kafka.metadata.authorizer.StandardAcl;
import org.apache.kafka.metadata.authorizer.StandardAuthorizer;
import org.apache.kafka.server.authorizer.Action;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Creates the authorizers compared in the benchmarks:
 * <ul>
 *     <li>baseline: Kafka StandardAuthorizer with the internal users configured as super-users (as Strimzi does today)</li>
 *     <li>metaauthorizer: Strimzi Metaauthorizer delegating to the StandardAuthorizer with the internal users
 *         configured in the Metaauthorizer and no super-users</li>
 * </ul>
 * Both get the same ACLs, loaded through the ClusterMetadataAuthorizer interface in the same way as in a Kafka node.
 */
final class AuthorizerSetup {
    static final String BASELINE = "baseline";
    static final String METAAUTHORIZER = "metaauthorizer";

    static final String USER_LISTENER = "TLS-9093";
    static final String CONTROLPLANE_LISTENER = "CONTROLPLANE-9090";
    static final String REPLICATION_LISTENER = "REPLICATION-9091";

    static final String KAFKA_USER = "User:CN=my-cluster-kafka,O=io.strimzi";
    static final String CLUSTER_OPERATOR_USER = "User:CN=cluster-operator,O=io.strimzi";
    static final String TOPIC_OPERATOR_USER = "User:CN=my-cluster-entity-topic-operator,O=io.strimzi";
    static final String USER_OPERATOR_USER = "User:CN=my-cluster-entity-user-operator,O=io.strimzi";
    static final String CRUISE_CONTROL_USER = "User:CN=my-cluster-cruise-control,O=io.strimzi";
    static final String KAFKA_EXPORTER_USER = "User:CN=my-cluster-kafka-exporter,O=io.strimzi";

    /**
     * Regular user with ACLs allowing READ and WRITE on the topics with the alice- prefix
     */
    static final String REGULAR_USER = "User:CN=alice";

    private AuthorizerSetup() {
    }

    /**
     * Creates and initializes the authorizer
     *
     * @param type      Type of the authorizer (baseline or metaauthorizer)
     * @param aclCount  Number of additional ACLs for other users
     * @param metrics   Metrics instance used for the plugin metrics
     *
     * @return  Initialized authorizer
     */
    static ClusterMetadataAuthorizer create(String type, int aclCount, Metrics metrics) {
        Map<String, Object> config = new HashMap<>();
        config.put("node.id", "0");
        config.put("allow.everyone.if.no.acl.found", "false");

        ClusterMetadataAuthorizer authorizer;

        switch (type) {
            case BASELINE -> {
                config.put("super.users", String.join(";", KAFKA_USER, CLUSTER_OPERATOR_USER, TOPIC_OPERATOR_USER,
                        USER_OPERATOR_USER, CRUISE_CONTROL_USER, KAFKA_EXPORTER_USER));
                authorizer = new StandardAuthorizer();
            }
            case METAAUTHORIZER -> {
                config.put("strimzi.metaauthorizer.listeners", CONTROLPLANE_LISTENER + "," + REPLICATION_LISTENER);
                config.put("strimzi.metaauthorizer.authorizer.class.name", StandardAuthorizer.class.getName());
                config.put("strimzi.metaauthorizer.kafka.user", KAFKA_USER);
                config.put("strimzi.metaauthorizer.clusteroperator.user", CLUSTER_OPERATOR_USER);
                config.put("strimzi.metaauthorizer.topicoperator.user", TOPIC_OPERATOR_USER);
                config.put("strimzi.metaauthorizer.useroperator.user", USER_OPERATOR_USER);
                config.put("strimzi.metaauthorizer.cruisecontrol.user", CRUISE_CONTROL_USER);
                config.put("strimzi.metaauthorizer.kafkaexporter.user", KAFKA_EXPORTER_USER);
                authorizer = new MetaAuthorizer();
            }
            default -> throw new IllegalArgumentException("Unknown authorizer type " + type);
        }

        // Same order as in Kafka: configure, plugin metrics, ACL snapshot, initial load completion
        authorizer.configure(config);
        ((Monitorable) authorizer).withPluginMetrics(new PluginMetricsImpl(metrics, Map.of()));
        authorizer.loadSnapshot(acls(aclCount));
        authorizer.completeInitialLoad();

        return authorizer;
    }

    /**
     * Generates the ACLs. The regular user used in the benchmarks gets ALLOW ACLs for READ and WRITE on topics with
     * the alice- prefix. In addition, the given number of ACLs for 500 other users is generated. They mix literal and
     * prefixed patterns, topics and groups, different operations, and ALLOW and DENY rules.
     *
     * @param aclCount  Number of additional ACLs
     *
     * @return  Map with the ACLs
     */
    static Map<Uuid, StandardAcl> acls(int aclCount) {
        Map<Uuid, StandardAcl> acls = new HashMap<>();
        acls.put(Uuid.randomUuid(), new StandardAcl(ResourceType.TOPIC, "alice-", PatternType.PREFIXED, REGULAR_USER, "*", AclOperation.READ, AclPermissionType.ALLOW));
        acls.put(Uuid.randomUuid(), new StandardAcl(ResourceType.TOPIC, "alice-", PatternType.PREFIXED, REGULAR_USER, "*", AclOperation.WRITE, AclPermissionType.ALLOW));

        AclOperation[] operations = {AclOperation.READ, AclOperation.WRITE, AclOperation.DESCRIBE};

        for (int i = 0; i < aclCount; i++) {
            String principal = "User:CN=user-" + (i % 500);
            ResourceType resourceType = i % 5 == 4 ? ResourceType.GROUP : ResourceType.TOPIC;
            PatternType patternType = i % 2 == 0 ? PatternType.LITERAL : PatternType.PREFIXED;
            String name = patternType == PatternType.LITERAL ? "resource-" + i : "prefix-" + i + "-";
            AclPermissionType permission = i % 10 == 9 ? AclPermissionType.DENY : AclPermissionType.ALLOW;

            acls.put(Uuid.randomUuid(), new StandardAcl(resourceType, name, patternType, principal, "*", operations[i % operations.length], permission));
        }

        return acls;
    }

    /**
     * Creates a list of actions on different topics
     *
     * @param operation     Operation
     * @param topicPrefix   Prefix of the topic names
     * @param count         Number of actions
     *
     * @return  List of actions
     */
    static List<Action> topicActions(AclOperation operation, String topicPrefix, int count) {
        List<Action> actions = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            actions.add(new Action(operation, new ResourcePattern(ResourceType.TOPIC, topicPrefix + i, PatternType.LITERAL), 1, true, true));
        }

        return List.copyOf(actions);
    }

    /**
     * Creates the request context
     *
     * @param listener      Listener name
     * @param principal     Principal
     *
     * @return  Request context
     */
    static TestRequestContext context(String listener, String principal) {
        return TestRequestContext.of(listener, principal);
    }
}
