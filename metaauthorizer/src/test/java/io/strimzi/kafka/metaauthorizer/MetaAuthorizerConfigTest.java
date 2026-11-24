/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafka.metaauthorizer;

import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.security.auth.KafkaPrincipal;
import org.apache.kafka.metadata.authorizer.StandardAuthorizer;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MetaAuthorizerConfigTest {
    @Test
    public void testFullConfiguration() {
        Map<String, Object> configs = new HashMap<>();
        configs.put("strimzi.metaauthorizer.listeners", "controlplane-9090, REPLICATION-9091");
        configs.put("strimzi.metaauthorizer.authorizer.class.name", StandardAuthorizer.class.getName());
        configs.put("strimzi.metaauthorizer.kafka.user", "User:CN=my-cluster-kafka,O=io.strimzi");
        configs.put("strimzi.metaauthorizer.clusteroperator.user", "User:CN=cluster-operator,O=io.strimzi");
        configs.put("strimzi.metaauthorizer.topicoperator.user", "User:system:serviceaccount:myproject:my-cluster-entity-operator");
        configs.put("strimzi.metaauthorizer.useroperator.user", "User:system:serviceaccount:myproject:my-cluster-entity-operator");
        configs.put("strimzi.metaauthorizer.cruisecontrol.user", "User:CN=my-cluster-cruise-control,O=io.strimzi");
        configs.put("strimzi.metaauthorizer.kafkaexporter.user", "User:CN=my-cluster-kafka-exporter,O=io.strimzi");
        configs.put("strimzi.metaauthorizer.cruisecontrol.topics", "a,b");
        // Unrelated options are ignored
        configs.put("super.users", "User:admin");
        configs.put("node.id", 0);

        MetaAuthorizerConfig config = new MetaAuthorizerConfig(configs);

        assertEquals(Set.of("CONTROLPLANE-9090", "REPLICATION-9091"), config.internalListeners());
        assertEquals(StandardAuthorizer.class.getName(), config.authorizerClassName());
        assertEquals(Set.of("a", "b"), config.cruiseControlTopics());
        assertEquals(6, config.users().size());
        assertEquals(new KafkaPrincipal("User", "CN=my-cluster-kafka,O=io.strimzi"), config.users().get(Component.KAFKA));
        assertEquals(new KafkaPrincipal("User", "CN=cluster-operator,O=io.strimzi"), config.users().get(Component.CLUSTER_OPERATOR));
        assertEquals(new KafkaPrincipal("User", "system:serviceaccount:myproject:my-cluster-entity-operator"), config.users().get(Component.TOPIC_OPERATOR));
        assertEquals(new KafkaPrincipal("User", "system:serviceaccount:myproject:my-cluster-entity-operator"), config.users().get(Component.USER_OPERATOR));
        assertEquals(new KafkaPrincipal("User", "CN=my-cluster-cruise-control,O=io.strimzi"), config.users().get(Component.CRUISE_CONTROL));
        assertEquals(new KafkaPrincipal("User", "CN=my-cluster-kafka-exporter,O=io.strimzi"), config.users().get(Component.KAFKA_EXPORTER));
    }

    @Test
    public void testMinimalConfiguration() {
        MetaAuthorizerConfig config = new MetaAuthorizerConfig(Map.of("strimzi.metaauthorizer.listeners", List.of("REPLICATION-9091")));

        assertEquals(Set.of("REPLICATION-9091"), config.internalListeners());
        assertNull(config.authorizerClassName());
        assertTrue(config.users().isEmpty());
        assertEquals(Set.of("strimzi.cruisecontrol.metrics", "strimzi.cruisecontrol.modeltrainingsamples", "strimzi.cruisecontrol.partitionmetricsamples"),
                config.cruiseControlTopics());
    }

    @Test
    public void testBlankValuesAreIgnored() {
        MetaAuthorizerConfig config = new MetaAuthorizerConfig(Map.of(
                "strimzi.metaauthorizer.listeners", "REPLICATION-9091",
                "strimzi.metaauthorizer.authorizer.class.name", " ",
                "strimzi.metaauthorizer.kafka.user", ""
        ));

        assertNull(config.authorizerClassName());
        assertTrue(config.users().isEmpty());
    }

    @Test
    public void testMissingListeners() {
        assertThrows(ConfigException.class, () -> new MetaAuthorizerConfig(Map.of()));
        assertThrows(ConfigException.class, () -> new MetaAuthorizerConfig(Map.of("strimzi.metaauthorizer.listeners", "")));
    }

    @Test
    public void testInvalidPrincipal() {
        for (String principal : List.of("my-user", ":my-user", "User:")) {
            ConfigException e = assertThrows(ConfigException.class, () -> new MetaAuthorizerConfig(Map.of(
                    "strimzi.metaauthorizer.listeners", "REPLICATION-9091",
                    "strimzi.metaauthorizer.topicoperator.user", principal
            )));

            assertTrue(e.getMessage().contains("strimzi.metaauthorizer.topicoperator.user"));
        }
    }

    @Test
    public void testPrincipalNameWithColons() {
        assertEquals(new KafkaPrincipal("User", "system:serviceaccount:ns:sa"),
                MetaAuthorizerConfig.parsePrincipal("key", "User:system:serviceaccount:ns:sa"));
    }

    @Test
    public void testSelfDelegationIsRejected() {
        assertThrows(ConfigException.class, () -> new MetaAuthorizerConfig(Map.of(
                "strimzi.metaauthorizer.listeners", "REPLICATION-9091",
                "strimzi.metaauthorizer.authorizer.class.name", MetaAuthorizer.class.getName()
        )));
    }
}
