/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.common.gatekeeper.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.strimzi.api.kafka.model.bridge.KafkaBridge;
import io.strimzi.api.kafka.model.bridge.KafkaBridgeBuilder;
import io.strimzi.api.kafka.model.common.template.MetadataTemplate;
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.api.kafka.model.kafka.KafkaBuilder;
import io.strimzi.api.kafka.model.kafka.listener.GenericKafkaListenerBuilder;
import io.strimzi.api.kafka.model.kafka.listener.KafkaListenerAuthenticationTls;
import io.strimzi.api.kafka.model.kafka.listener.KafkaListenerType;
import io.strimzi.operator.common.model.Labels;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

public class DiscoveryLabelsGatekeeperPluginTest {
    private static final String NAMESPACE = "my-namespace";
    private static final String NAME = "my-resource";

    private final DiscoveryLabelsGatekeeperPlugin plugin = new DiscoveryLabelsGatekeeperPlugin();

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parse(String json) {
        try {
            return new ObjectMapper().readValue(json, List.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // Kafka

    @Test
    public void testKafkaGetsDiscoveryLabelAndAnnotation() {
        Kafka kafka = new KafkaBuilder()
                .withNewMetadata().withNamespace(NAMESPACE).withName(NAME).endMetadata()
                .withNewSpec()
                    .withNewKafka()
                        .withListeners(
                                new GenericKafkaListenerBuilder().withName("plain").withPort(9092).withType(KafkaListenerType.INTERNAL).withTls(false).build(),
                                new GenericKafkaListenerBuilder().withName("tls").withPort(9093).withType(KafkaListenerType.INTERNAL).withTls(true).withAuth(new KafkaListenerAuthenticationTls()).build())
                    .endKafka()
                .endSpec()
                .build();

        plugin.kafkaEntry(null, kafka, List.of()).toCompletableFuture().join();

        MetadataTemplate metadata = kafka.getSpec().getKafka().getTemplate().getBootstrapService().getMetadata();
        assertThat(metadata.getLabels().get(Labels.STRIMZI_DISCOVERY_LABEL), is("true"));

        List<Map<String, Object>> discovery = parse(metadata.getAnnotations().get(Labels.STRIMZI_DISCOVERY_LABEL));
        assertThat(discovery, hasSize(2));
        assertThat(discovery.get(0), is(Map.of("port", 9092, "tls", false, "auth", "none", "protocol", "kafka")));
        assertThat(discovery.get(1), is(Map.of("port", 9093, "tls", true, "auth", "tls", "protocol", "kafka")));
    }

    @Test
    public void testKafkaRespectsExistingDiscoveryMetadata() {
        Kafka kafka = new KafkaBuilder()
                .withNewMetadata().withNamespace(NAMESPACE).withName(NAME).endMetadata()
                .withNewSpec()
                    .withNewKafka()
                        .withNewTemplate()
                            .withNewBootstrapService()
                                .withNewMetadata()
                                    .withLabels(Map.of(Labels.STRIMZI_DISCOVERY_LABEL, "false", "user-label", "keep"))
                                    .withAnnotations(Map.of(Labels.STRIMZI_DISCOVERY_LABEL, "user-annotation"))
                                .endMetadata()
                            .endBootstrapService()
                        .endTemplate()
                    .endKafka()
                .endSpec()
                .build();

        plugin.kafkaEntry(null, kafka, List.of()).toCompletableFuture().join();

        MetadataTemplate metadata = kafka.getSpec().getKafka().getTemplate().getBootstrapService().getMetadata();
        // The user's discovery label and annotation are left unchanged, other labels are kept
        assertThat(metadata.getLabels().get(Labels.STRIMZI_DISCOVERY_LABEL), is("false"));
        assertThat(metadata.getLabels().get("user-label"), is("keep"));
        assertThat(metadata.getAnnotations().get(Labels.STRIMZI_DISCOVERY_LABEL), is("user-annotation"));
    }

    // KafkaBridge

    @Test
    public void testBridgeGetsDiscoveryLabelAndAnnotation() {
        KafkaBridge bridge = new KafkaBridgeBuilder()
                .withNewMetadata().withNamespace(NAMESPACE).withName(NAME).endMetadata()
                .withNewSpec()
                    .withBootstrapServers("my-kafka:9092")
                .endSpec()
                .build();

        plugin.kafkaBridgeEntry(null, bridge).toCompletableFuture().join();

        MetadataTemplate metadata = bridge.getSpec().getTemplate().getApiService().getMetadata();
        assertThat(metadata.getLabels().get(Labels.STRIMZI_DISCOVERY_LABEL), is("true"));

        List<Map<String, Object>> discovery = parse(metadata.getAnnotations().get(Labels.STRIMZI_DISCOVERY_LABEL));
        assertThat(discovery, hasSize(2));
        // No HTTP config => default REST API port 8080, no TLS
        assertThat(discovery.get(0), is(Map.of("port", 8080, "tls", false, "auth", "none", "protocol", "http")));
        // Management port
        assertThat(discovery.get(1), is(Map.of("port", 8081, "tls", false, "auth", "none", "protocol", "http")));
    }

    @Test
    public void testBridgeWithCustomHttpPortAndTls() {
        KafkaBridge bridge = new KafkaBridgeBuilder()
                .withNewMetadata().withNamespace(NAMESPACE).withName(NAME).endMetadata()
                .withNewSpec()
                    .withBootstrapServers("my-kafka:9092")
                    .withNewHttp()
                        .withPort(8443)
                        .withNewTls()
                        .endTls()
                    .endHttp()
                .endSpec()
                .build();

        plugin.kafkaBridgeEntry(null, bridge).toCompletableFuture().join();

        MetadataTemplate metadata = bridge.getSpec().getTemplate().getApiService().getMetadata();
        List<Map<String, Object>> discovery = parse(metadata.getAnnotations().get(Labels.STRIMZI_DISCOVERY_LABEL));
        assertThat(discovery.get(0), is(Map.of("port", 8443, "tls", true, "auth", "none", "protocol", "https")));
        assertThat(discovery.get(1), is(Map.of("port", 8081, "tls", false, "auth", "none", "protocol", "http")));
    }
}
