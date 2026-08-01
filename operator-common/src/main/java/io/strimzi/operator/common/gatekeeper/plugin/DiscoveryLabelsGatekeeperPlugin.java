/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.common.gatekeeper.plugin;

import io.fabric8.kubernetes.client.utils.Serialization;
import io.strimzi.api.kafka.model.bridge.KafkaBridge;
import io.strimzi.api.kafka.model.bridge.KafkaBridgeHttpConfig;
import io.strimzi.api.kafka.model.bridge.KafkaBridgeSpec;
import io.strimzi.api.kafka.model.bridge.KafkaBridgeTemplate;
import io.strimzi.api.kafka.model.common.template.InternalServiceTemplate;
import io.strimzi.api.kafka.model.common.template.MetadataTemplate;
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.api.kafka.model.kafka.KafkaClusterSpec;
import io.strimzi.api.kafka.model.kafka.KafkaClusterTemplate;
import io.strimzi.api.kafka.model.kafka.listener.GenericKafkaListener;
import io.strimzi.api.kafka.model.nodepool.KafkaNodePool;
import io.strimzi.operator.common.model.Labels;
import io.strimzi.plugin.gatekeeper.GatekeeperKafkaBridgeEntryContext;
import io.strimzi.plugin.gatekeeper.GatekeeperKafkaBridgeMutatingPlugin;
import io.strimzi.plugin.gatekeeper.GatekeeperKafkaEntryContext;
import io.strimzi.plugin.gatekeeper.GatekeeperKafkaMutatingPlugin;
import io.strimzi.plugin.gatekeeper.GatekeeperPluginConfigurationContext;
import io.strimzi.plugin.gatekeeper.KafkaAndKafkaNodePools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Gatekeeper plugin which adds the Strimzi discovery label and annotation to the custom resources it receives. It is a
 * <em>mutating</em> plugin which runs in the entry phase and modifies the service template metadata so that the
 * generated bootstrap (Kafka) or REST API (Kafka Bridge) service carries the discovery label and annotation.
 * <p>
 * The discovery label {@code strimzi.io/discovery: "true"} marks the service as discoverable, and the discovery
 * annotation with the same key holds a JSON description of the discoverable ports (their port number, whether TLS is
 * used, the authentication type and the protocol). Only the Kafka and KafkaBridge resources expose such a discoverable
 * service.
 * <p>
 * The label and annotation are added to the service template metadata only if they are not already set there, so that
 * values set explicitly by the user are left unchanged. The operator adds the discovery label and annotation to the
 * service on its own as well; this plugin does not replace that logic, it performs the same configuration by mutating
 * the custom resource.
 */
public class DiscoveryLabelsGatekeeperPlugin implements GatekeeperKafkaMutatingPlugin, GatekeeperKafkaBridgeMutatingPlugin {
    // Mirrors KafkaBridgeCluster.REST_API_MANAGEMENT_PORT (in the cluster-operator module), duplicated here so that the
    // plugin does not depend on that module.
    private static final int BRIDGE_MANAGEMENT_PORT = 8081;

    /**
     * Creates the discovery labels plugin.
     */
    public DiscoveryLabelsGatekeeperPlugin() { }

    @Override
    public void configure(GatekeeperPluginConfigurationContext context) {
        // This plugin does not need any configuration
    }

    @Override
    public CompletionStage<KafkaAndKafkaNodePools> kafkaEntry(GatekeeperKafkaEntryContext context, Kafka kafka, List<KafkaNodePool> kafkaNodePools) {
        if (kafka.getSpec() != null && kafka.getSpec().getKafka() != null) {
            InternalServiceTemplate service = ensureKafkaBootstrapServiceTemplate(kafka.getSpec().getKafka());
            applyDiscovery(service, kafkaDiscoveryAnnotation(kafka.getSpec().getKafka().getListeners()));
        }

        return CompletableFuture.completedFuture(new KafkaAndKafkaNodePools(kafka, kafkaNodePools));
    }

    @Override
    public CompletionStage<KafkaBridge> kafkaBridgeEntry(GatekeeperKafkaBridgeEntryContext context, KafkaBridge kafkaBridge) {
        if (kafkaBridge.getSpec() != null) {
            InternalServiceTemplate service = ensureBridgeApiServiceTemplate(kafkaBridge.getSpec());
            applyDiscovery(service, bridgeDiscoveryAnnotation(kafkaBridge.getSpec().getHttp()));
        }

        return CompletableFuture.completedFuture(kafkaBridge);
    }

    private InternalServiceTemplate ensureKafkaBootstrapServiceTemplate(KafkaClusterSpec kafkaSpec) {
        KafkaClusterTemplate template = kafkaSpec.getTemplate();
        if (template == null) {
            template = new KafkaClusterTemplate();
            kafkaSpec.setTemplate(template);
        }

        InternalServiceTemplate service = template.getBootstrapService();
        if (service == null) {
            service = new InternalServiceTemplate();
            template.setBootstrapService(service);
        }

        return service;
    }

    private InternalServiceTemplate ensureBridgeApiServiceTemplate(KafkaBridgeSpec spec) {
        KafkaBridgeTemplate template = spec.getTemplate();
        if (template == null) {
            template = new KafkaBridgeTemplate();
            spec.setTemplate(template);
        }

        InternalServiceTemplate service = template.getApiService();
        if (service == null) {
            service = new InternalServiceTemplate();
            template.setApiService(service);
        }

        return service;
    }

    /**
     * Adds the discovery label and annotation to the metadata of the given service template. The label and annotation
     * share the same key ({@code strimzi.io/discovery}); the label value is {@code "true"} and the annotation value is
     * the JSON description of the discoverable ports. Neither is overwritten if already present.
     *
     * @param service               The service template on which to set the discovery metadata
     * @param discoveryAnnotation   The JSON discovery annotation value
     */
    private void applyDiscovery(InternalServiceTemplate service, String discoveryAnnotation) {
        MetadataTemplate metadata = service.getMetadata();
        if (metadata == null) {
            metadata = new MetadataTemplate();
            service.setMetadata(metadata);
        }

        Map<String, String> labels = new LinkedHashMap<>();
        if (metadata.getLabels() != null) {
            labels.putAll(metadata.getLabels());
        }
        labels.putIfAbsent(Labels.STRIMZI_DISCOVERY_LABEL, "true");
        metadata.setLabels(labels);

        Map<String, String> annotations = new LinkedHashMap<>();
        if (metadata.getAnnotations() != null) {
            annotations.putAll(metadata.getAnnotations());
        }
        annotations.putIfAbsent(Labels.STRIMZI_DISCOVERY_LABEL, discoveryAnnotation);
        metadata.setAnnotations(annotations);
    }

    /**
     * Builds the discovery annotation for a Kafka bootstrap service - one entry per configured listener.
     */
    private static String kafkaDiscoveryAnnotation(List<GenericKafkaListener> listeners) {
        List<Map<String, Object>> discovery = new ArrayList<>();

        if (listeners != null) {
            for (GenericKafkaListener listener : listeners) {
                String auth = listener.getAuth() != null ? listener.getAuth().getType() : "none";
                discovery.add(discoveryEntry(listener.getPort(), listener.isTls(), auth, "kafka"));
            }
        }

        return Serialization.asJson(discovery);
    }

    /**
     * Builds the discovery annotation for a Kafka Bridge REST API service - the REST API port and the management port.
     */
    private static String bridgeDiscoveryAnnotation(KafkaBridgeHttpConfig http) {
        int port = http != null ? http.getPort() : KafkaBridgeHttpConfig.HTTP_DEFAULT_PORT;
        boolean tls = http != null && http.getTls() != null;

        List<Map<String, Object>> discovery = new ArrayList<>();
        discovery.add(discoveryEntry(port, tls, "none", tls ? "https" : "http"));
        discovery.add(discoveryEntry(BRIDGE_MANAGEMENT_PORT, false, "none", "http"));

        return Serialization.asJson(discovery);
    }

    private static Map<String, Object> discoveryEntry(int port, boolean tls, String auth, String protocol) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("port", port);
        entry.put("tls", tls);
        entry.put("auth", auth);
        entry.put("protocol", protocol);
        return entry;
    }
}
