/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.topic;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.strimzi.api.kafka.model.topic.KafkaTopic;
import io.strimzi.api.kafka.model.topic.KafkaTopicBuilder;
import io.strimzi.api.kafka.model.topic.KafkaTopicStatus;
import io.strimzi.operator.common.Reconciliation;
import io.strimzi.operator.common.gatekeeper.GatekeeperPluginFactory;
import io.strimzi.operator.topic.cruisecontrol.CruiseControlHandler;
import io.strimzi.operator.topic.metrics.TopicOperatorMetricsHolder;
import io.strimzi.operator.topic.metrics.TopicOperatorMetricsProvider;
import io.strimzi.operator.topic.model.Either;
import io.strimzi.operator.topic.model.Pair;
import io.strimzi.operator.topic.model.PartitionedByError;
import io.strimzi.operator.topic.model.ReconcilableTopic;
import io.strimzi.operator.topic.model.TopicOperatorException;
import io.strimzi.operator.topic.model.TopicState;
import io.strimzi.plugin.gatekeeper.GatekeeperKafkaTopicDeletionContext;
import io.strimzi.plugin.gatekeeper.GatekeeperKafkaTopicEntryContext;
import io.strimzi.plugin.gatekeeper.GatekeeperKafkaTopicExitContext;
import io.strimzi.plugin.gatekeeper.GatekeeperKafkaTopicMutatingPlugin;
import io.strimzi.plugin.gatekeeper.GatekeeperPluginConfigurationContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused unit test verifying that the Topic Operator's batching controller invokes the Gatekeeper plugins. The
 * controller methods are called directly on the test thread with mocked handlers which complete synchronously, so the
 * thread-local test plugins set through {@link GatekeeperPluginFactory#initializeForTests(List)} are visible to them.
 */
@SuppressWarnings("unchecked")
public class BatchingTopicControllerGatekeeperTest {
    private static final String NAMESPACE = "my-namespace";
    private static final String NAME = "my-topic";

    private KafkaHandler kafkaHandler;
    private KubernetesHandler kubernetesHandler;

    @AfterEach
    public void cleanup() {
        GatekeeperPluginFactory.clearTestPlugins();
    }

    private BatchingTopicController controller() {
        var config = TopicOperatorConfig.buildFromMap(Map.of(
            TopicOperatorConfig.BOOTSTRAP_SERVERS.key(), "localhost:1234",
            TopicOperatorConfig.WATCHED_NAMESPACE.key(), NAMESPACE,
            TopicOperatorConfig.SKIP_CLUSTER_CONFIG_REVIEW.key(), "true",
            TopicOperatorConfig.USE_FINALIZERS.key(), "false"
        ));

        var metricsHolder = new TopicOperatorMetricsHolder(KafkaTopic.RESOURCE_KIND, null, new TopicOperatorMetricsProvider(new SimpleMeterRegistry()));
        kafkaHandler = mock(KafkaHandler.class);
        kubernetesHandler = mock(KubernetesHandler.class);
        var cruiseControlHandler = mock(CruiseControlHandler.class);

        // The reconciliation of a managed topic always ends up describing it. We make the describe fail so that the
        // topic ends up as a failure and reaches updateStatusForException without needing to fully mock a successful
        // reconciliation. The remaining Kafka operations are stubbed to no-ops.
        when(kafkaHandler.describeTopics(any())).thenAnswer(invocation -> {
            List<ReconcilableTopic> input = invocation.getArgument(0);
            return new PartitionedByError<>(List.of(), input.stream()
                .map(rt -> new Pair<>(rt, Either.<TopicOperatorException, TopicState>ofLeft(new TopicOperatorException.InternalError(new RuntimeException("describe failed")))))
                .collect(Collectors.toList()));
        });
        when(kafkaHandler.createTopics(any())).thenReturn(new PartitionedByError<>(List.of(), List.of()));
        when(kafkaHandler.alterConfigs(any())).thenReturn(new PartitionedByError<>(List.of(), List.of()));
        when(kafkaHandler.createPartitions(any())).thenReturn(new PartitionedByError<>(List.of(), List.of()));
        when(kafkaHandler.deleteTopics(any(), any())).thenReturn(new PartitionedByError<>(List.of(), List.of()));
        when(kubernetesHandler.removeFinalizer(any())).thenAnswer(invocation -> invocation.getArgument(0, ReconcilableTopic.class).kt());
        when(kubernetesHandler.updateStatus(any())).thenAnswer(invocation -> invocation.getArgument(0, ReconcilableTopic.class).kt());

        return new BatchingTopicController(config, Map.of("key", "VALUE"), kubernetesHandler, kafkaHandler, metricsHolder, cruiseControlHandler);
    }

    private static KafkaTopic managedTopic() {
        return new KafkaTopicBuilder()
                .withNewMetadata()
                    .withNamespace(NAMESPACE)
                    .withName(NAME)
                    .withLabels(Map.of("key", "VALUE"))
                    .withCreationTimestamp("2024-01-01T00:00:00Z")
                .endMetadata()
                .withNewSpec()
                    .withPartitions(1)
                    .withReplicas(1)
                .endSpec()
                .build();
    }

    private static ReconcilableTopic reconcilableTopic(KafkaTopic kt) {
        return new ReconcilableTopic(new Reconciliation("test", "KafkaTopic", NAMESPACE, NAME), kt, TopicOperatorUtil.topicName(kt));
    }

    @Test
    public void testEntryPluginMutationIsUsedInTheReconciliation() throws InterruptedException {
        BatchingTopicController controller = controller();
        GatekeeperPluginFactory.initializeForTests(List.of(new AnnotationAddingEntryPlugin()));

        // Capture the topics passed to describeTopics: these are the ones which are actually reconciled
        ArgumentCaptor<List<ReconcilableTopic>> captor = ArgumentCaptor.forClass(List.class);

        controller.onUpdate(List.of(reconcilableTopic(managedTopic())));

        verify(kafkaHandler).describeTopics(captor.capture());
        assertThat(captor.getValue(), hasSize(1));
        assertThat(captor.getValue().get(0).kt().getMetadata().getAnnotations().get("gatekeeper.strimzi.io/entry"), is("applied"));
    }

    @Test
    public void testExitPluginMutatesTheStatusBeforeItIsPersisted() throws InterruptedException {
        BatchingTopicController controller = controller();
        GatekeeperPluginFactory.initializeForTests(List.of(new ObservedGenerationSettingExitPlugin()));

        ArgumentCaptor<ReconcilableTopic> captor = ArgumentCaptor.forClass(ReconcilableTopic.class);

        controller.onUpdate(List.of(reconcilableTopic(managedTopic())));

        verify(kubernetesHandler).updateStatus(captor.capture());
        assertThat(captor.getValue().kt().getStatus().getObservedGeneration(), is(4242L));
    }

    @Test
    public void testDeletionInvokesTheDeletionHook() throws InterruptedException {
        BatchingTopicController controller = controller();
        RecordingDeletionPlugin plugin = new RecordingDeletionPlugin();
        GatekeeperPluginFactory.initializeForTests(List.of(plugin));

        // An unmanaged topic keeps the deletion path light (just finalizer removal), but the deletion hooks still run
        KafkaTopic unmanagedTopic = new KafkaTopicBuilder(managedTopic())
                .editMetadata()
                    .addToAnnotations("strimzi.io/managed", "false")
                .endMetadata()
                .build();

        controller.onDelete(List.of(reconcilableTopic(unmanagedTopic)));

        assertThat("deletion hook was invoked", plugin.deletionInvoked, is(true));
        assertThat("deletion hook received the namespace", plugin.deletionNamespace, is(NAMESPACE));
        assertThat("deletion hook received the name", plugin.deletionName, is(NAME));
    }

    /**
     * Mutating plugin which adds an annotation to the KafkaTopic during the entry phase.
     */
    private static final class AnnotationAddingEntryPlugin implements GatekeeperKafkaTopicMutatingPlugin {
        @Override
        public void configure(GatekeeperPluginConfigurationContext context) { }

        @Override
        public CompletionStage<KafkaTopic> kafkaTopicEntry(GatekeeperKafkaTopicEntryContext context, KafkaTopic kafkaTopic) {
            return CompletableFuture.completedFuture(new KafkaTopicBuilder(kafkaTopic)
                    .editMetadata()
                        .addToAnnotations("gatekeeper.strimzi.io/entry", "applied")
                    .endMetadata()
                    .build());
        }
    }

    /**
     * Mutating plugin which sets the observed generation on the status during the exit phase.
     */
    private static final class ObservedGenerationSettingExitPlugin implements GatekeeperKafkaTopicMutatingPlugin {
        @Override
        public void configure(GatekeeperPluginConfigurationContext context) { }

        @Override
        public CompletionStage<KafkaTopicStatus> kafkaTopicExit(GatekeeperKafkaTopicExitContext context, KafkaTopic kafkaTopic, KafkaTopicStatus newKafkaTopicStatus) {
            newKafkaTopicStatus.setObservedGeneration(4242L);
            return CompletableFuture.completedFuture(newKafkaTopicStatus);
        }
    }

    /**
     * Mutating plugin which records whether the deletion hook was invoked and with which namespace and name.
     */
    private static final class RecordingDeletionPlugin implements GatekeeperKafkaTopicMutatingPlugin {
        private boolean deletionInvoked;
        private String deletionNamespace;
        private String deletionName;

        @Override
        public void configure(GatekeeperPluginConfigurationContext context) { }

        @Override
        public CompletionStage<Void> kafkaTopicDeletion(GatekeeperKafkaTopicDeletionContext context, String namespace, String name) {
            deletionInvoked = true;
            deletionNamespace = namespace;
            deletionName = name;
            return CompletableFuture.completedFuture(null);
        }
    }
}
