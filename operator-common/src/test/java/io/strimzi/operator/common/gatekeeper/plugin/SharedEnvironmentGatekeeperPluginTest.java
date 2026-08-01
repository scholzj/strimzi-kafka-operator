/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.common.gatekeeper.plugin;

import io.strimzi.api.kafka.model.bridge.KafkaBridge;
import io.strimzi.api.kafka.model.bridge.KafkaBridgeBuilder;
import io.strimzi.api.kafka.model.common.template.ContainerEnvVar;
import io.strimzi.api.kafka.model.common.template.ContainerEnvVarBuilder;
import io.strimzi.api.kafka.model.common.template.ContainerTemplate;
import io.strimzi.api.kafka.model.connect.KafkaConnect;
import io.strimzi.api.kafka.model.connect.KafkaConnectBuilder;
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.api.kafka.model.kafka.KafkaBuilder;
import io.strimzi.api.kafka.model.mirrormaker2.KafkaMirrorMaker2;
import io.strimzi.api.kafka.model.mirrormaker2.KafkaMirrorMaker2Builder;
import io.strimzi.api.kafka.model.nodepool.KafkaNodePool;
import io.strimzi.api.kafka.model.nodepool.KafkaNodePoolBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

public class SharedEnvironmentGatekeeperPluginTest {
    private static final String NAMESPACE = "my-namespace";
    private static final String NAME = "my-resource";
    private static final List<ContainerEnvVar> SHARED = List.of(
            new ContainerEnvVarBuilder().withName("HTTP_PROXY").withValue("http://proxy:3128").build(),
            new ContainerEnvVarBuilder().withName("FIPS_MODE").withValue("disabled").build());

    private static SharedEnvironmentGatekeeperPlugin plugin() {
        SharedEnvironmentGatekeeperPlugin plugin = new SharedEnvironmentGatekeeperPlugin();
        plugin.setSharedEnvironmentVariables(SHARED);
        return plugin;
    }

    private static Map<String, String> env(ContainerTemplate container) {
        return container.getEnv().stream().collect(Collectors.toMap(ContainerEnvVar::getName, ContainerEnvVar::getValue));
    }

    private static void assertHasSharedVariables(ContainerTemplate container) {
        assertThat(env(container), is(Map.of("HTTP_PROXY", "http://proxy:3128", "FIPS_MODE", "disabled")));
    }

    // Not configured

    @Test
    public void testUnconfiguredPluginDoesNotMutate() {
        // A plugin whose environment variables were not set must not modify the resource
        SharedEnvironmentGatekeeperPlugin plugin = new SharedEnvironmentGatekeeperPlugin();
        Kafka kafka = kafka();

        plugin.kafkaEntry(null, kafka, List.of(nodePool(false))).toCompletableFuture().join();

        assertThat(kafka.getSpec().getKafka().getTemplate(), is(nullValue()));
    }

    // Kafka nodes

    @Test
    public void testKafkaLevelContainerTemplatesGetTheVariables() {
        // The Kafka resource has both container templates and the node pool has none => the variables go on the
        // Kafka-level templates and the node pool inherits them, so it is left untouched
        Kafka kafka = new KafkaBuilder(kafka())
                .editSpec()
                    .editKafka()
                        .withNewTemplate()
                            .withNewKafkaContainer().endKafkaContainer()
                            .withNewInitContainer().endInitContainer()
                        .endTemplate()
                    .endKafka()
                .endSpec()
                .build();
        KafkaNodePool nodePool = nodePool(false);

        plugin().kafkaEntry(null, kafka, List.of(nodePool)).toCompletableFuture().join();

        assertHasSharedVariables(kafka.getSpec().getKafka().getTemplate().getKafkaContainer());
        assertHasSharedVariables(kafka.getSpec().getKafka().getTemplate().getInitContainer());
        assertThat(nodePool.getSpec().getTemplate(), is(nullValue()));
    }

    @Test
    public void testNodePoolContainerTemplatesGetTheVariables() {
        // The node pool has its own container templates (which replace the Kafka-level ones) => the variables go there
        Kafka kafka = kafka();
        KafkaNodePool nodePool = nodePool(true);

        plugin().kafkaEntry(null, kafka, List.of(nodePool)).toCompletableFuture().join();

        assertHasSharedVariables(nodePool.getSpec().getTemplate().getKafkaContainer());
        assertHasSharedVariables(nodePool.getSpec().getTemplate().getInitContainer());
    }

    @Test
    public void testNodePoolContainerTemplatesAreCreatedWhenThereAreNone() {
        // Neither the Kafka resource nor the node pool has container templates => the plugin creates them on the node pool
        Kafka kafka = kafka();
        KafkaNodePool nodePool = nodePool(false);

        plugin().kafkaEntry(null, kafka, List.of(nodePool)).toCompletableFuture().join();

        assertHasSharedVariables(nodePool.getSpec().getTemplate().getKafkaContainer());
        assertHasSharedVariables(nodePool.getSpec().getTemplate().getInitContainer());
        assertThat(kafka.getSpec().getKafka().getTemplate(), is(nullValue()));
    }

    @Test
    public void testExistingEnvironmentVariablesArePreserved() {
        // An environment variable defined explicitly on the container template takes precedence and must be left unchanged
        Kafka kafka = new KafkaBuilder(kafka())
                .editSpec()
                    .editKafka()
                        .withNewTemplate()
                            .withNewKafkaContainer()
                                .withEnv(new ContainerEnvVarBuilder().withName("HTTP_PROXY").withValue("http://user-proxy:8080").build())
                            .endKafkaContainer()
                            .withNewInitContainer().endInitContainer()
                        .endTemplate()
                    .endKafka()
                .endSpec()
                .build();

        plugin().kafkaEntry(null, kafka, List.of(nodePool(false))).toCompletableFuture().join();

        // The user's HTTP_PROXY is kept, and FIPS_MODE is added
        assertThat(env(kafka.getSpec().getKafka().getTemplate().getKafkaContainer()),
                is(Map.of("HTTP_PROXY", "http://user-proxy:8080", "FIPS_MODE", "disabled")));
    }

    // Kafka additional components

    @Test
    public void testConfiguredKafkaComponentContainersGetTheVariables() {
        Kafka kafka = new KafkaBuilder(kafka())
                .editSpec()
                    .withNewEntityOperator()
                    .endEntityOperator()
                    .withNewCruiseControl()
                    .endCruiseControl()
                    .withNewKafkaExporter()
                    .endKafkaExporter()
                .endSpec()
                .build();

        plugin().kafkaEntry(null, kafka, List.of(nodePool(false))).toCompletableFuture().join();

        assertHasSharedVariables(kafka.getSpec().getEntityOperator().getTemplate().getTopicOperatorContainer());
        assertHasSharedVariables(kafka.getSpec().getEntityOperator().getTemplate().getUserOperatorContainer());
        assertHasSharedVariables(kafka.getSpec().getCruiseControl().getTemplate().getCruiseControlContainer());
        assertHasSharedVariables(kafka.getSpec().getKafkaExporter().getTemplate().getContainer());
    }

    @Test
    public void testUnconfiguredKafkaComponentsAreNotEnabled() {
        Kafka kafka = kafka();

        plugin().kafkaEntry(null, kafka, List.of(nodePool(false))).toCompletableFuture().join();

        assertThat(kafka.getSpec().getEntityOperator(), is(nullValue()));
        assertThat(kafka.getSpec().getCruiseControl(), is(nullValue()));
        assertThat(kafka.getSpec().getKafkaExporter(), is(nullValue()));
    }

    // KafkaConnect

    @Test
    public void testKafkaConnectContainersGetTheVariablesButBuildContainerOnlyWithABuild() {
        KafkaConnect connect = new KafkaConnectBuilder()
                .withNewMetadata().withNamespace(NAMESPACE).withName(NAME).endMetadata()
                .withNewSpec().endSpec()
                .build();

        plugin().kafkaConnectEntry(null, connect, List.of()).toCompletableFuture().join();

        assertHasSharedVariables(connect.getSpec().getTemplate().getConnectContainer());
        assertHasSharedVariables(connect.getSpec().getTemplate().getInitContainer());
        assertThat(connect.getSpec().getTemplate().getBuildContainer(), is(nullValue()));
    }

    @Test
    public void testKafkaConnectBuildContainerGetsTheVariablesWhenABuildIsConfigured() {
        KafkaConnect connect = new KafkaConnectBuilder()
                .withNewMetadata().withNamespace(NAMESPACE).withName(NAME).endMetadata()
                .withNewSpec()
                    .withNewBuild()
                    .endBuild()
                .endSpec()
                .build();

        plugin().kafkaConnectEntry(null, connect, List.of()).toCompletableFuture().join();

        assertHasSharedVariables(connect.getSpec().getTemplate().getBuildContainer());
    }

    // KafkaMirrorMaker2

    @Test
    public void testKafkaMirrorMaker2ContainersGetTheVariables() {
        KafkaMirrorMaker2 mirrorMaker2 = new KafkaMirrorMaker2Builder()
                .withNewMetadata().withNamespace(NAMESPACE).withName(NAME).endMetadata()
                .withNewSpec().endSpec()
                .build();

        plugin().kafkaMirrorMaker2Entry(null, mirrorMaker2).toCompletableFuture().join();

        assertHasSharedVariables(mirrorMaker2.getSpec().getTemplate().getConnectContainer());
        assertHasSharedVariables(mirrorMaker2.getSpec().getTemplate().getInitContainer());
        // KafkaMirrorMaker2 has no build container
        assertThat(mirrorMaker2.getSpec().getTemplate().getBuildContainer(), is(nullValue()));
    }

    // KafkaBridge

    @Test
    public void testKafkaBridgeContainersGetTheVariables() {
        KafkaBridge bridge = new KafkaBridgeBuilder()
                .withNewMetadata().withNamespace(NAMESPACE).withName(NAME).endMetadata()
                .withNewSpec()
                    .withBootstrapServers("my-kafka:9092")
                .endSpec()
                .build();

        plugin().kafkaBridgeEntry(null, bridge).toCompletableFuture().join();

        assertHasSharedVariables(bridge.getSpec().getTemplate().getBridgeContainer());
        assertHasSharedVariables(bridge.getSpec().getTemplate().getInitContainer());
    }

    private static Kafka kafka() {
        return new KafkaBuilder()
                .withNewMetadata().withNamespace(NAMESPACE).withName(NAME).endMetadata()
                .withNewSpec()
                    .withNewKafka()
                    .endKafka()
                .endSpec()
                .build();
    }

    private static KafkaNodePool nodePool(boolean withContainerTemplates) {
        KafkaNodePoolBuilder builder = new KafkaNodePoolBuilder()
                .withNewMetadata().withNamespace(NAMESPACE).withName("pool").endMetadata()
                .withNewSpec()
                .endSpec();

        if (withContainerTemplates) {
            builder.editSpec()
                    .withNewTemplate()
                        .withNewKafkaContainer().endKafkaContainer()
                        .withNewInitContainer().endInitContainer()
                    .endTemplate()
                .endSpec();
        }

        return builder.build();
    }
}
