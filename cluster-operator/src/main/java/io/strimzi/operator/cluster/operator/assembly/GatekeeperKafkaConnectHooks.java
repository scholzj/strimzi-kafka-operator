/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.operator.assembly;

import io.strimzi.api.kafka.model.connect.KafkaConnect;
import io.strimzi.api.kafka.model.connect.KafkaConnectStatus;
import io.strimzi.api.kafka.model.connector.KafkaConnector;
import io.strimzi.api.kafka.model.connector.KafkaConnectorStatus;
import io.strimzi.operator.cluster.gatekeeper.ClusterOperatorGatekeeperPluginInvoker;
import io.strimzi.operator.cluster.operator.VertxUtil;
import io.strimzi.operator.common.gatekeeper.impl.GatekeeperKafkaConnectDeletionContextImpl;
import io.strimzi.operator.common.gatekeeper.impl.GatekeeperKafkaConnectEntryContextImpl;
import io.strimzi.operator.common.gatekeeper.impl.GatekeeperKafkaConnectExitContextImpl;
import io.strimzi.plugin.gatekeeper.KafkaConnectAndKafkaConnectors;
import io.vertx.core.Future;

import java.util.List;

/**
 * Thin adapter which invokes the Gatekeeper KafkaConnect plugins and adapts their {@code CompletionStage} results to
 * Vert.x {@code Future}s. It exists so that {@link KafkaConnectAssemblyOperator} depends on a single type for the
 * Gatekeeper wiring rather than on the invoker and each of the context implementations directly. It creates a fresh
 * context instance for every call; the contexts currently carry no data.
 */
class GatekeeperKafkaConnectHooks {
    private GatekeeperKafkaConnectHooks() { }

    /**
     * Invokes the Gatekeeper KafkaConnect entry phase.
     *
     * @param kafkaConnect      The KafkaConnect resource being reconciled
     * @param kafkaConnectors   The connectors belonging to the KafkaConnect
     *
     * @return  Future with the (possibly mutated) KafkaConnect and connectors
     */
    static Future<KafkaConnectAndKafkaConnectors> entry(KafkaConnect kafkaConnect, List<KafkaConnector> kafkaConnectors) {
        return VertxUtil.toFuture(ClusterOperatorGatekeeperPluginInvoker.kafkaConnectEntry(new GatekeeperKafkaConnectEntryContextImpl(), kafkaConnect, kafkaConnectors));
    }

    /**
     * Invokes the Gatekeeper KafkaConnect exit phase for the KafkaConnect status.
     *
     * @param kafkaConnect      The KafkaConnect resource being reconciled
     * @param kafkaConnectors   The connectors belonging to the KafkaConnect
     * @param status            The computed KafkaConnect status
     *
     * @return  Future with the (possibly mutated) KafkaConnect status
     */
    static Future<KafkaConnectStatus> exit(KafkaConnect kafkaConnect, List<KafkaConnector> kafkaConnectors, KafkaConnectStatus status) {
        return VertxUtil.toFuture(ClusterOperatorGatekeeperPluginInvoker.kafkaConnectExit(new GatekeeperKafkaConnectExitContextImpl(), kafkaConnect, kafkaConnectors, status));
    }

    /**
     * Invokes the Gatekeeper KafkaConnect exit phase for a single connector status.
     *
     * @param kafkaConnect      The KafkaConnect resource being reconciled (may be {@code null} on a cluster deletion)
     * @param kafkaConnector    The connector whose status is being processed
     * @param status            The computed connector status
     *
     * @return  Future with the (possibly mutated) connector status
     */
    static Future<KafkaConnectorStatus> connectorExit(KafkaConnect kafkaConnect, KafkaConnector kafkaConnector, KafkaConnectorStatus status) {
        return VertxUtil.toFuture(ClusterOperatorGatekeeperPluginInvoker.kafkaConnectorExit(new GatekeeperKafkaConnectExitContextImpl(), kafkaConnect, kafkaConnector, status));
    }

    /**
     * Invokes the Gatekeeper KafkaConnect deletion hooks.
     *
     * @param namespace     Namespace of the deleted KafkaConnect
     * @param name          Name of the deleted KafkaConnect
     *
     * @return  Future which completes when all deletion hooks have run
     */
    static Future<Void> deletion(String namespace, String name) {
        return VertxUtil.toFuture(ClusterOperatorGatekeeperPluginInvoker.kafkaConnectDeletion(new GatekeeperKafkaConnectDeletionContextImpl(), namespace, name));
    }
}
