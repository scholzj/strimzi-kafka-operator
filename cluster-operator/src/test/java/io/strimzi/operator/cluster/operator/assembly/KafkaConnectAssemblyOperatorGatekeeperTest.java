/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.operator.assembly;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.strimzi.api.ResourceAnnotations;
import io.strimzi.api.kafka.model.connect.KafkaConnect;
import io.strimzi.api.kafka.model.connector.KafkaConnector;
import io.strimzi.api.kafka.model.connector.KafkaConnectorBuilder;
import io.strimzi.api.kafka.model.connector.KafkaConnectorList;
import io.strimzi.api.kafka.model.connector.KafkaConnectorStatus;
import io.strimzi.operator.cluster.KafkaVersionTestUtils;
import io.strimzi.operator.cluster.PlatformFeaturesAvailability;
import io.strimzi.operator.cluster.ResourceUtils;
import io.strimzi.operator.cluster.model.KafkaVersion;
import io.strimzi.operator.cluster.operator.resource.ResourceOperatorSupplier;
import io.strimzi.operator.cluster.operator.resource.kubernetes.ClusterRoleBindingOperator;
import io.strimzi.operator.common.Reconciliation;
import io.strimzi.operator.common.gatekeeper.GatekeeperPluginFactory;
import io.strimzi.operator.common.model.Labels;
import io.strimzi.operator.common.operator.resource.ReconcileResult;
import io.strimzi.operator.common.operator.resource.kubernetes.CrdOperator;
import io.strimzi.platform.KubernetesVersion;
import io.strimzi.plugin.gatekeeper.GatekeeperKafkaConnectDeletionContext;
import io.strimzi.plugin.gatekeeper.GatekeeperKafkaConnectExitContext;
import io.strimzi.plugin.gatekeeper.GatekeeperKafkaConnectMutatingPlugin;
import io.strimzi.plugin.gatekeeper.GatekeeperPluginConfigurationContext;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Focused unit test verifying that the Kafka Connect operator invokes the Gatekeeper plugins. The methods under test are
 * called directly on the test thread with mocks which complete synchronously, so the thread-local test plugins set
 * through {@link GatekeeperPluginFactory#initializeForTests(List)} are visible to them.
 */
@SuppressWarnings("unchecked")
public class KafkaConnectAssemblyOperatorGatekeeperTest {
    private static final KafkaVersion.Lookup VERSIONS = KafkaVersionTestUtils.getKafkaVersionLookup();
    private static final PlatformFeaturesAvailability PFA = new PlatformFeaturesAvailability(true, KubernetesVersion.MINIMAL_SUPPORTED_VERSION);
    private static final String NAMESPACE = "my-namespace";
    private static final String NAME = "my-connect";
    private static final String CONNECTOR_NAME = "my-connector";

    private static Vertx vertx;

    @BeforeAll
    public static void before() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    public static void after() {
        vertx.close();
    }

    @AfterEach
    public void cleanup() {
        GatekeeperPluginFactory.clearTestPlugins();
    }

    private static KafkaConnectAssemblyOperator operator(ResourceOperatorSupplier supplier) {
        return new KafkaConnectAssemblyOperator(vertx, PFA, supplier, ResourceUtils.dummyClusterOperatorConfig(VERSIONS), connect -> mock(KafkaConnectApi.class));
    }

    @Test
    public void testConnectorExitPluginMutatesTheConnectorStatusBeforeItIsPersisted() {
        ResourceOperatorSupplier supplier = ResourceUtils.supplierWithMocks(false);
        CrdOperator<KubernetesClient, KafkaConnector, KafkaConnectorList> mockConnectorOps = supplier.kafkaConnectorOperator;

        // A paused connector so that reconcileConnectorAndHandleResult goes straight to the status update (which runs the
        // Gatekeeper connector exit phase) without needing the Connect REST API
        KafkaConnector connector = new KafkaConnectorBuilder()
                .withNewMetadata()
                    .withNamespace(NAMESPACE)
                    .withName(CONNECTOR_NAME)
                    .withAnnotations(Map.of(ResourceAnnotations.ANNO_STRIMZI_IO_PAUSE_RECONCILIATION, "true"))
                .endMetadata()
                .withNewSpec()
                .endSpec()
                .build();

        when(mockConnectorOps.getAsync(NAMESPACE, CONNECTOR_NAME)).thenReturn(CompletableFuture.completedFuture(connector));
        ArgumentCaptor<KafkaConnector> captor = ArgumentCaptor.forClass(KafkaConnector.class);
        when(mockConnectorOps.updateStatusAsync(any(), captor.capture())).thenReturn(CompletableFuture.completedFuture(connector));

        GatekeeperPluginFactory.initializeForTests(List.of(new TasksMaxSettingConnectorExitPlugin()));

        Future<Void> result = operator(supplier).reconcileConnectorAndHandleResult(
                new Reconciliation("test", "KafkaConnect", NAMESPACE, NAME),
                "host",
                mock(KafkaConnectApi.class),
                true,
                CONNECTOR_NAME,
                connector);

        assertThat(result.succeeded(), is(true));
        // The connector status which was persisted must carry the value set by the connector exit plugin
        assertThat(captor.getValue().getStatus().getTasksMax(), is(99));
    }

    @Test
    public void testDeletionInvokesTheDeletionHook() {
        ResourceOperatorSupplier supplier = ResourceUtils.supplierWithMocks(false);
        CrdOperator<KubernetesClient, KafkaConnector, KafkaConnectorList> mockConnectorOps = supplier.kafkaConnectorOperator;
        ClusterRoleBindingOperator mockCrbOps = supplier.clusterRoleBindingOperator;

        when(mockConnectorOps.listAsync(anyString(), any(Labels.class))).thenReturn(CompletableFuture.completedFuture(List.of()));
        when(mockCrbOps.reconcile(any(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(ReconcileResult.noop(null)));

        RecordingDeletionPlugin plugin = new RecordingDeletionPlugin();
        GatekeeperPluginFactory.initializeForTests(List.of(plugin));

        Future<Boolean> result = operator(supplier).delete(new Reconciliation("test", "KafkaConnect", NAMESPACE, NAME));

        assertThat(result.succeeded(), is(true));
        assertThat("deletion hook was invoked", plugin.deletionInvoked, is(true));
        assertThat("deletion hook received the namespace", plugin.deletionNamespace, is(NAMESPACE));
        assertThat("deletion hook received the name", plugin.deletionName, is(NAME));
    }

    /**
     * Mutating plugin which sets tasksMax on the connector status during the connector exit phase.
     */
    private static final class TasksMaxSettingConnectorExitPlugin implements GatekeeperKafkaConnectMutatingPlugin {
        @Override
        public void configure(GatekeeperPluginConfigurationContext context) { }

        @Override
        public CompletionStage<KafkaConnectorStatus> kafkaConnectorExit(GatekeeperKafkaConnectExitContext context, KafkaConnect kafkaConnect, KafkaConnector kafkaConnector, KafkaConnectorStatus newKafkaConnectorStatus) {
            newKafkaConnectorStatus.setTasksMax(99);
            return CompletableFuture.completedFuture(newKafkaConnectorStatus);
        }
    }

    /**
     * Mutating plugin which records whether the deletion hook was invoked and with which namespace and name.
     */
    private static final class RecordingDeletionPlugin implements GatekeeperKafkaConnectMutatingPlugin {
        private boolean deletionInvoked;
        private String deletionNamespace;
        private String deletionName;

        @Override
        public void configure(GatekeeperPluginConfigurationContext context) { }

        @Override
        public CompletionStage<Void> kafkaConnectDeletion(GatekeeperKafkaConnectDeletionContext context, String namespace, String name) {
            deletionInvoked = true;
            deletionNamespace = namespace;
            deletionName = name;
            return CompletableFuture.completedFuture(null);
        }
    }
}
