/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.common.gatekeeper.plugin;

import io.strimzi.api.kafka.model.common.ConditionBuilder;
import io.strimzi.api.kafka.model.user.KafkaUser;
import io.strimzi.api.kafka.model.user.KafkaUserBuilder;
import io.strimzi.api.kafka.model.user.KafkaUserStatus;
import io.strimzi.api.kafka.model.user.KafkaUserStatusBuilder;
import io.strimzi.plugin.gatekeeper.GatekeeperKafkaUserEntryContext;
import io.strimzi.plugin.gatekeeper.GatekeeperKafkaUserExitContext;
import io.strimzi.plugin.gatekeeper.GatekeeperKafkaUserMutatingPlugin;

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
public class SampleMutatingGatekeeperPlugin implements GatekeeperKafkaUserMutatingPlugin {
    /**
     * Creates the sample mutating plugin
     */
    public SampleMutatingGatekeeperPlugin() { }

    @Override
    public CompletionStage<KafkaUser> kafkaUserEntry(GatekeeperKafkaUserEntryContext context, KafkaUser kafkaUser) {
        KafkaUser mutatedUser = new KafkaUserBuilder(kafkaUser)
            .editSpec()
                .editOrNewTemplate()
                    .editOrNewSecret()
                        .editOrNewMetadata()
                            .addToAnnotations("sample.gatekeeper.strimzi.io/checked-by", this.getClass().getCanonicalName())
                        .endMetadata()
                    .endSecret()
                .endTemplate()
            .endSpec()
            .build();

        return CompletableFuture.completedFuture(mutatedUser);
    }

    @Override
    public CompletionStage<KafkaUserStatus> kafkaUserExit(GatekeeperKafkaUserExitContext context, KafkaUser kafkaUser, KafkaUserStatus newKafkaUserStatus) {
        KafkaUserStatus mutatedStatus = new KafkaUserStatusBuilder(newKafkaUserStatus)
            .addToConditions(new ConditionBuilder()
                .withType("SampleMutatingGatekeeperPlugin")
                .withStatus("True")
                .withReason("Checked")
                .withMessage("The SampleMutatingGatekeeperPlugin has checked this KafkaUser.")
                .build())
            .build();

        return CompletableFuture.completedFuture(mutatedStatus);
    }
}
