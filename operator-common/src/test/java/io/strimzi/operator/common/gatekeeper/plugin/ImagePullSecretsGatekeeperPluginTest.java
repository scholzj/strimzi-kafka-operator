/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.common.gatekeeper.plugin;

import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.LocalObjectReferenceBuilder;
import io.strimzi.api.kafka.model.bridge.KafkaBridge;
import io.strimzi.api.kafka.model.bridge.KafkaBridgeBuilder;
import io.strimzi.api.kafka.model.common.template.PodTemplate;
import io.strimzi.api.kafka.model.connect.KafkaConnect;
import io.strimzi.api.kafka.model.connect.KafkaConnectBuilder;
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.api.kafka.model.kafka.KafkaBuilder;
import io.strimzi.api.kafka.model.mirrormaker2.KafkaMirrorMaker2;
import io.strimzi.api.kafka.model.mirrormaker2.KafkaMirrorMaker2Builder;
import io.strimzi.api.kafka.model.nodepool.KafkaNodePool;
import io.strimzi.api.kafka.model.nodepool.KafkaNodePoolBuilder;
import io.strimzi.operator.common.InvalidConfigurationException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ImagePullSecretsGatekeeperPluginTest {
    private static final String NAMESPACE = "my-namespace";
    private static final String NAME = "my-resource";
    private static final List<LocalObjectReference> SECRETS = List.of(
            new LocalObjectReferenceBuilder().withName("secret-1").build(),
            new LocalObjectReferenceBuilder().withName("secret-2").build());

    private static ImagePullSecretsGatekeeperPlugin plugin() {
        ImagePullSecretsGatekeeperPlugin plugin = new ImagePullSecretsGatekeeperPlugin();
        plugin.setImagePullSecrets(SECRETS);
        return plugin;
    }

    private static List<String> secretNames(PodTemplate pod) {
        return pod.getImagePullSecrets().stream().map(LocalObjectReference::getName).toList();
    }

    // Environment variable parsing

    @Test
    public void testParseImagePullSecrets() {
        assertThat(ImagePullSecretsGatekeeperPlugin.parseImagePullSecrets("secret-1, secret-2").stream().map(LocalObjectReference::getName).toList(),
                contains("secret-1", "secret-2"));
        assertThat(ImagePullSecretsGatekeeperPlugin.parseImagePullSecrets(null), is(nullValue()));
        assertThat(ImagePullSecretsGatekeeperPlugin.parseImagePullSecrets(""), is(nullValue()));
    }

    @Test
    public void testParseImagePullSecretsRejectsInvalidValue() {
        assertThrows(InvalidConfigurationException.class, () -> ImagePullSecretsGatekeeperPlugin.parseImagePullSecrets("Not A Valid Secret!"));
    }

    // Not configured

    @Test
    public void testUnconfiguredPluginDoesNotMutate() {
        // A plugin whose environment variable was not set has no image pull secrets and must not modify the resource
        ImagePullSecretsGatekeeperPlugin plugin = new ImagePullSecretsGatekeeperPlugin();
        Kafka kafka = kafka();

        plugin.kafkaEntry(null, kafka, List.of(nodePool(false))).toCompletableFuture().join();

        assertThat(kafka.getSpec().getKafka().getTemplate(), is(nullValue()));
    }

    // Kafka nodes

    @Test
    public void testKafkaLevelPodTemplateGetsTheSecrets() {
        // The Kafka resource has a Pod template, and the node pool has none => the secrets go on the Kafka-level template
        // and the node pool inherits it, so it is left untouched
        Kafka kafka = new KafkaBuilder(kafka())
                .editSpec()
                    .editKafka()
                        .withNewTemplate()
                            .withNewPod()
                            .endPod()
                        .endTemplate()
                    .endKafka()
                .endSpec()
                .build();
        KafkaNodePool nodePool = nodePool(false);

        plugin().kafkaEntry(null, kafka, List.of(nodePool)).toCompletableFuture().join();

        assertThat(secretNames(kafka.getSpec().getKafka().getTemplate().getPod()), contains("secret-1", "secret-2"));
        assertThat(nodePool.getSpec().getTemplate(), is(nullValue()));
    }

    @Test
    public void testNodePoolPodTemplateGetsTheSecrets() {
        // The node pool has its own Pod template (which replaces the Kafka-level one) => the secrets go there
        Kafka kafka = kafka();
        KafkaNodePool nodePool = nodePool(true);

        plugin().kafkaEntry(null, kafka, List.of(nodePool)).toCompletableFuture().join();

        assertThat(secretNames(nodePool.getSpec().getTemplate().getPod()), contains("secret-1", "secret-2"));
    }

    @Test
    public void testNodePoolPodTemplateIsCreatedWhenThereIsNone() {
        // Neither the Kafka resource nor the node pool has a Pod template => the plugin creates it on the node pool
        Kafka kafka = kafka();
        KafkaNodePool nodePool = nodePool(false);

        plugin().kafkaEntry(null, kafka, List.of(nodePool)).toCompletableFuture().join();

        assertThat(secretNames(nodePool.getSpec().getTemplate().getPod()), contains("secret-1", "secret-2"));
        assertThat(kafka.getSpec().getKafka().getTemplate(), is(nullValue()));
    }

    @Test
    public void testExistingImagePullSecretsArePreserved() {
        // Image pull secrets set explicitly on a Pod template take precedence and must be left unchanged
        Kafka kafka = new KafkaBuilder(kafka())
                .editSpec()
                    .editKafka()
                        .withNewTemplate()
                            .withNewPod()
                                .withImagePullSecrets(new LocalObjectReferenceBuilder().withName("user-secret").build())
                            .endPod()
                        .endTemplate()
                    .endKafka()
                .endSpec()
                .build();

        plugin().kafkaEntry(null, kafka, List.of(nodePool(false))).toCompletableFuture().join();

        assertThat(secretNames(kafka.getSpec().getKafka().getTemplate().getPod()), contains("user-secret"));
    }

    // Kafka additional components

    @Test
    public void testConfiguredKafkaComponentsGetTheSecrets() {
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

        assertThat(secretNames(kafka.getSpec().getEntityOperator().getTemplate().getPod()), contains("secret-1", "secret-2"));
        assertThat(secretNames(kafka.getSpec().getCruiseControl().getTemplate().getPod()), contains("secret-1", "secret-2"));
        assertThat(secretNames(kafka.getSpec().getKafkaExporter().getTemplate().getPod()), contains("secret-1", "secret-2"));
    }

    @Test
    public void testUnconfiguredKafkaComponentsAreNotEnabled() {
        // The plugin must not create the additional components which are not configured
        Kafka kafka = kafka();

        plugin().kafkaEntry(null, kafka, List.of(nodePool(false))).toCompletableFuture().join();

        assertThat(kafka.getSpec().getEntityOperator(), is(nullValue()));
        assertThat(kafka.getSpec().getCruiseControl(), is(nullValue()));
        assertThat(kafka.getSpec().getKafkaExporter(), is(nullValue()));
    }

    // KafkaConnect

    @Test
    public void testKafkaConnectPodGetsTheSecretsButBuildPodOnlyWithABuild() {
        KafkaConnect connect = new KafkaConnectBuilder()
                .withNewMetadata().withNamespace(NAMESPACE).withName(NAME).endMetadata()
                .withNewSpec().endSpec()
                .build();

        plugin().kafkaConnectEntry(null, connect, List.of()).toCompletableFuture().join();

        assertThat(secretNames(connect.getSpec().getTemplate().getPod()), contains("secret-1", "secret-2"));
        // Without a build there is no build Pod
        assertThat(connect.getSpec().getTemplate().getBuildPod(), is(nullValue()));
    }

    @Test
    public void testKafkaConnectBuildPodGetsTheSecretsWhenABuildIsConfigured() {
        KafkaConnect connect = new KafkaConnectBuilder()
                .withNewMetadata().withNamespace(NAMESPACE).withName(NAME).endMetadata()
                .withNewSpec()
                    .withNewBuild()
                    .endBuild()
                .endSpec()
                .build();

        plugin().kafkaConnectEntry(null, connect, List.of()).toCompletableFuture().join();

        assertThat(secretNames(connect.getSpec().getTemplate().getPod()), contains("secret-1", "secret-2"));
        assertThat(secretNames(connect.getSpec().getTemplate().getBuildPod()), contains("secret-1", "secret-2"));
    }

    // KafkaMirrorMaker2

    @Test
    public void testKafkaMirrorMaker2PodGetsTheSecrets() {
        KafkaMirrorMaker2 mirrorMaker2 = new KafkaMirrorMaker2Builder()
                .withNewMetadata().withNamespace(NAMESPACE).withName(NAME).endMetadata()
                .withNewSpec().endSpec()
                .build();

        plugin().kafkaMirrorMaker2Entry(null, mirrorMaker2).toCompletableFuture().join();

        assertThat(secretNames(mirrorMaker2.getSpec().getTemplate().getPod()), contains("secret-1", "secret-2"));
        // KafkaMirrorMaker2 has no build Pod
        assertThat(mirrorMaker2.getSpec().getTemplate().getBuildPod(), is(nullValue()));
    }

    // KafkaBridge

    @Test
    public void testKafkaBridgePodGetsTheSecrets() {
        KafkaBridge bridge = new KafkaBridgeBuilder()
                .withNewMetadata().withNamespace(NAMESPACE).withName(NAME).endMetadata()
                .withNewSpec()
                    .withBootstrapServers("my-kafka:9092")
                .endSpec()
                .build();

        plugin().kafkaBridgeEntry(null, bridge).toCompletableFuture().join();

        assertThat(secretNames(bridge.getSpec().getTemplate().getPod()), contains("secret-1", "secret-2"));
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

    private static KafkaNodePool nodePool(boolean withPodTemplate) {
        KafkaNodePoolBuilder builder = new KafkaNodePoolBuilder()
                .withNewMetadata().withNamespace(NAMESPACE).withName("pool").endMetadata()
                .withNewSpec()
                .endSpec();

        if (withPodTemplate) {
            builder.editSpec()
                    .withNewTemplate()
                        .withNewPod()
                        .endPod()
                    .endTemplate()
                .endSpec();
        }

        return builder.build();
    }
}
