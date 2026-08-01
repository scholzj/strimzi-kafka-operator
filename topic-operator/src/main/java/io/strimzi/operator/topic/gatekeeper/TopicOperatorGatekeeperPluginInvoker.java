/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.topic.gatekeeper;

import io.strimzi.api.kafka.model.topic.KafkaTopic;
import io.strimzi.api.kafka.model.topic.KafkaTopicBuilder;
import io.strimzi.api.kafka.model.topic.KafkaTopicStatus;
import io.strimzi.api.kafka.model.topic.KafkaTopicStatusBuilder;
import io.strimzi.operator.common.gatekeeper.AbstractGatekeeperPluginInvoker;
import io.strimzi.operator.common.gatekeeper.GatekeeperPluginFactory;
import io.strimzi.plugin.gatekeeper.GatekeeperKafkaTopicDeletionContext;
import io.strimzi.plugin.gatekeeper.GatekeeperKafkaTopicEntryContext;
import io.strimzi.plugin.gatekeeper.GatekeeperKafkaTopicExitContext;
import io.strimzi.plugin.gatekeeper.GatekeeperKafkaTopicMutatingPlugin;
import io.strimzi.plugin.gatekeeper.GatekeeperKafkaTopicValidatingPlugin;

import java.util.concurrent.CompletionStage;

/**
 * Invokes the Strimzi Gatekeeper plugins for KafkaTopic reconciliations. It provides entry, exit, and deletion methods
 * which invoke all the KafkaTopic plugins - both mutating and validating - as one ordered chain. The plugins (and their
 * order) are provided by the {@link GatekeeperPluginFactory}.
 */
public class TopicOperatorGatekeeperPluginInvoker extends AbstractGatekeeperPluginInvoker {
    private TopicOperatorGatekeeperPluginInvoker() { }

    /**
     * Invokes the entry of all the KafkaTopic plugins (mutating and validating) as a single ordered chain. Mutating plugins
     * can modify the resource; validating plugins receive a copy of it and let the original pass through unchanged.
     *
     * @param context   The entry context passed to the plugins
     * @param kafkaTopic   The KafkaTopic custom resource being reconciled
     *
     * @return  A completion stage with the KafkaTopic resource after it passed through all the plugins
     */
    public static CompletionStage<KafkaTopic> kafkaTopicEntry(GatekeeperKafkaTopicEntryContext context, KafkaTopic kafkaTopic) {
        return chain(
                GatekeeperKafkaTopicMutatingPlugin.class,
                GatekeeperKafkaTopicValidatingPlugin.class,
                Phase.ENTRY,
                kafkaTopic,
                (plugin, current) -> plugin.kafkaTopicEntry(context, current),
                (plugin, current) -> plugin.kafkaTopicEntry(context, copy(current, item -> new KafkaTopicBuilder(item).build())));
    }

    /**
     * Invokes the exit of all the KafkaTopic plugins (mutating and validating) as a single ordered chain, in the reverse of
     * the configured order. Mutating plugins can modify the status; validating plugins receive copies of the resource and
     * the status and let the original status pass through unchanged.
     *
     * @param context   The exit context passed to the plugins
     * @param kafkaTopic   The KafkaTopic custom resource being reconciled
     * @param status    The status computed for the KafkaTopic resource
     *
     * @return  A completion stage with the KafkaTopic status after it passed through all the plugins
     */
    public static CompletionStage<KafkaTopicStatus> kafkaTopicExit(GatekeeperKafkaTopicExitContext context, KafkaTopic kafkaTopic, KafkaTopicStatus status) {
        return chain(
                GatekeeperKafkaTopicMutatingPlugin.class,
                GatekeeperKafkaTopicValidatingPlugin.class,
                Phase.EXIT,
                status,
                (plugin, current) -> plugin.kafkaTopicExit(context, kafkaTopic, current),
                (plugin, current) -> plugin.kafkaTopicExit(context, copy(kafkaTopic, item -> new KafkaTopicBuilder(item).build()), copy(current, item -> new KafkaTopicStatusBuilder(item).build())));
    }



    /**
     * Invokes the deletion hook of all the KafkaTopic plugins as a single ordered chain. There is no resource to mutate
     * during a deletion, so the hooks only react to the deletion; any of them can reject it by completing exceptionally,
     * which stops the chain.
     *
     * @param context   The deletion context passed to the plugins
     * @param namespace The namespace of the KafkaTopic being deleted
     * @param name      The name of the KafkaTopic being deleted
     *
     * @return  A completion stage which completes when all the deletion hooks completed
     */
    public static CompletionStage<Void> kafkaTopicDeletion(GatekeeperKafkaTopicDeletionContext context, String namespace, String name) {
        return deletion(
                GatekeeperKafkaTopicMutatingPlugin.class,
                GatekeeperKafkaTopicValidatingPlugin.class,
                plugin -> plugin instanceof GatekeeperKafkaTopicMutatingPlugin mutating
                        ? mutating.kafkaTopicDeletion(context, namespace, name)
                        : ((GatekeeperKafkaTopicValidatingPlugin) plugin).kafkaTopicDeletion(context, namespace, name));
    }
}
