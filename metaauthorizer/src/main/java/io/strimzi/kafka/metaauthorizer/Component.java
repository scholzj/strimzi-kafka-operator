/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafka.metaauthorizer;

import org.apache.kafka.common.resource.ResourceType;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.apache.kafka.common.acl.AclOperation.ALTER;
import static org.apache.kafka.common.acl.AclOperation.ALTER_CONFIGS;
import static org.apache.kafka.common.acl.AclOperation.CREATE;
import static org.apache.kafka.common.acl.AclOperation.DELETE;
import static org.apache.kafka.common.acl.AclOperation.DESCRIBE;
import static org.apache.kafka.common.acl.AclOperation.DESCRIBE_CONFIGS;
import static org.apache.kafka.common.acl.AclOperation.READ;
import static org.apache.kafka.common.acl.AclOperation.WRITE;

/**
 * The internal Strimzi components connecting to the internal listeners and their hardcoded authorization rules. The
 * rules are tailored to what the component actually does with the Kafka cluster.
 */
public enum Component {
    /**
     * Kafka nodes (brokers and controllers). They are allowed everything. They use the internal listeners for the
     * replication, for the KRaft controller communication, for forwarding requests from the brokers to the
     * controllers, and for the broker plugins (Cruise Control Metrics Reporter, Strimzi Quotas plugin, Tiered Storage
     * Remote Log Metadata Manager etc.). Enumerating all of this would be fragile and the nodes have access to all the
     * data anyway.
     */
    KAFKA("kafka.user") {
        @Override
        boolean allowAll() {
            return true;
        }

        @Override
        List<Rule> rules(Set<String> cruiseControlTopics) {
            return List.of();
        }
    },

    /**
     * Strimzi Cluster Operator. It uses the Admin API to:
     * <ul>
     *     <li>Check the cluster state and the KRaft quorum (DescribeCluster, DescribeMetadataQuorum)</li>
     *     <li>Check the topic availability before rolling the brokers (ListTopics, DescribeTopics, topic DescribeConfigs)</li>
     *     <li>Manage the broker configurations and loggers (broker DescribeConfigs, IncrementalAlterConfigs)</li>
     *     <li>Upgrade the metadata version (UpdateFeatures)</li>
     *     <li>Unregister removed nodes (UnregisterBroker, UnregisterController)</li>
     *     <li>Manage the client quotas for the Strimzi Quotas plugin (DescribeClientQuotas, AlterClientQuotas)</li>
     * </ul>
     */
    CLUSTER_OPERATOR("clusteroperator.user") {
        @Override
        List<Rule> rules(Set<String> cruiseControlTopics) {
            return List.of(
                    Rule.all(ResourceType.CLUSTER, DESCRIBE, DESCRIBE_CONFIGS, ALTER, ALTER_CONFIGS),
                    Rule.all(ResourceType.TOPIC, DESCRIBE, DESCRIBE_CONFIGS)
            );
        }
    },

    /**
     * Strimzi Topic Operator. It uses the Admin API to:
     * <ul>
     *     <li>Manage topics (CreateTopics, DeleteTopics, CreatePartitions, DescribeTopics)</li>
     *     <li>Manage the topic configurations (DescribeConfigs, IncrementalAlterConfigs)</li>
     *     <li>Read the broker configurations (broker DescribeConfigs)</li>
     *     <li>Check the ongoing reassignments (ListPartitionReassignments)</li>
     *     <li>Read the cluster information (DescribeCluster)</li>
     * </ul>
     */
    TOPIC_OPERATOR("topicoperator.user") {
        @Override
        List<Rule> rules(Set<String> cruiseControlTopics) {
            return List.of(
                    Rule.all(ResourceType.CLUSTER, DESCRIBE, DESCRIBE_CONFIGS),
                    Rule.all(ResourceType.TOPIC, CREATE, DELETE, ALTER, DESCRIBE, DESCRIBE_CONFIGS, ALTER_CONFIGS)
            );
        }
    },

    /**
     * Strimzi User Operator. It uses the Admin API to:
     * <ul>
     *     <li>Manage ACLs (DescribeAcls, CreateAcls, DeleteAcls)</li>
     *     <li>Manage SCRAM-SHA credentials (DescribeUserScramCredentials, AlterUserScramCredentials)</li>
     *     <li>Manage user quotas (DescribeClientQuotas, AlterClientQuotas)</li>
     *     <li>Read the cluster information (DescribeCluster)</li>
     * </ul>
     */
    USER_OPERATOR("useroperator.user") {
        @Override
        List<Rule> rules(Set<String> cruiseControlTopics) {
            return List.of(
                    Rule.all(ResourceType.CLUSTER, DESCRIBE, ALTER, DESCRIBE_CONFIGS, ALTER_CONFIGS)
            );
        }
    },

    /**
     * Cruise Control. It uses:
     * <ul>
     *     <li>The Admin API to read the cluster, topic and log directory information (DescribeCluster, ListTopics,
     *         DescribeTopics, DescribeConfigs, DescribeLogDirs, DescribeReplicaLogDirs)</li>
     *     <li>The Admin API to rebalance the cluster (AlterPartitionReassignments, ListPartitionReassignments,
     *         ElectLeaders, AlterReplicaLogDirs)</li>
     *     <li>The Admin API to configure the replication throttles on brokers and topics (IncrementalAlterConfigs)</li>
     *     <li>The Admin API to create and maintain its own topics (CreateTopics, CreatePartitions, IncrementalAlterConfigs)</li>
     *     <li>Producers and consumers to read the metrics topic and read / write the sample store topics. The
     *         consumers use manual partition assignment without a consumer group.</li>
     * </ul>
     */
    CRUISE_CONTROL("cruisecontrol.user") {
        @Override
        List<Rule> rules(Set<String> cruiseControlTopics) {
            List<Rule> rules = new ArrayList<>();
            rules.add(Rule.all(ResourceType.CLUSTER, DESCRIBE, DESCRIBE_CONFIGS, ALTER, ALTER_CONFIGS));
            rules.add(Rule.all(ResourceType.TOPIC, DESCRIBE, DESCRIBE_CONFIGS, ALTER_CONFIGS));

            for (String topic : cruiseControlTopics) {
                rules.add(Rule.literal(ResourceType.TOPIC, topic, CREATE, READ, WRITE, ALTER));
            }

            return List.copyOf(rules);
        }
    },

    /**
     * Kafka Exporter. It reads the cluster and topic metadata (Metadata, ListOffsets) and the consumer group
     * information (ListGroups, DescribeGroups, OffsetFetch).
     */
    KAFKA_EXPORTER("kafkaexporter.user") {
        @Override
        List<Rule> rules(Set<String> cruiseControlTopics) {
            return List.of(
                    Rule.all(ResourceType.CLUSTER, DESCRIBE),
                    Rule.all(ResourceType.TOPIC, DESCRIBE),
                    Rule.all(ResourceType.GROUP, DESCRIBE)
            );
        }
    };

    private final String userConfigKey;

    Component(String userConfigSuffix) {
        this.userConfigKey = MetaAuthorizerConfig.PREFIX + userConfigSuffix;
    }

    /**
     * Returns the name of the configuration option with the principal of this component
     *
     * @return  The name of the configuration option with the principal of this component
     */
    public String userConfigKey() {
        return userConfigKey;
    }

    /**
     * Returns true if this component is allowed to do everything. False otherwise.
     *
     * @return  True if this component is allowed to do everything. False otherwise.
     */
    boolean allowAll() {
        return false;
    }

    /**
     * Returns the rules for this component
     *
     * @param cruiseControlTopics   Names of the topics used by Cruise Control
     *
     * @return  List of the rules for this component
     */
    abstract List<Rule> rules(Set<String> cruiseControlTopics);
}
