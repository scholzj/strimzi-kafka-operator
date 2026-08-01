/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.common.gatekeeper.plugin;

import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.strimzi.api.kafka.model.bridge.KafkaBridge;
import io.strimzi.api.kafka.model.bridge.KafkaBridgeSpec;
import io.strimzi.api.kafka.model.bridge.KafkaBridgeTemplate;
import io.strimzi.api.kafka.model.common.template.PodTemplate;
import io.strimzi.api.kafka.model.common.template.PodTemplateBuilder;
import io.strimzi.api.kafka.model.connect.KafkaConnect;
import io.strimzi.api.kafka.model.connect.KafkaConnectSpec;
import io.strimzi.api.kafka.model.connect.KafkaConnectTemplate;
import io.strimzi.api.kafka.model.connector.KafkaConnector;
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.api.kafka.model.kafka.KafkaSpec;
import io.strimzi.api.kafka.model.kafka.cruisecontrol.CruiseControlSpec;
import io.strimzi.api.kafka.model.kafka.cruisecontrol.CruiseControlTemplate;
import io.strimzi.api.kafka.model.kafka.entityoperator.EntityOperatorSpec;
import io.strimzi.api.kafka.model.kafka.entityoperator.EntityOperatorTemplate;
import io.strimzi.api.kafka.model.kafka.exporter.KafkaExporterSpec;
import io.strimzi.api.kafka.model.kafka.exporter.KafkaExporterTemplate;
import io.strimzi.api.kafka.model.mirrormaker2.KafkaMirrorMaker2;
import io.strimzi.api.kafka.model.mirrormaker2.KafkaMirrorMaker2Spec;
import io.strimzi.api.kafka.model.nodepool.KafkaNodePool;
import io.strimzi.api.kafka.model.nodepool.KafkaNodePoolTemplate;
import io.strimzi.operator.common.config.ConfigParameterParser;
import io.strimzi.plugin.gatekeeper.GatekeeperKafkaBridgeEntryContext;
import io.strimzi.plugin.gatekeeper.GatekeeperKafkaBridgeMutatingPlugin;
import io.strimzi.plugin.gatekeeper.GatekeeperKafkaConnectEntryContext;
import io.strimzi.plugin.gatekeeper.GatekeeperKafkaConnectMutatingPlugin;
import io.strimzi.plugin.gatekeeper.GatekeeperKafkaEntryContext;
import io.strimzi.plugin.gatekeeper.GatekeeperKafkaMirrorMaker2EntryContext;
import io.strimzi.plugin.gatekeeper.GatekeeperKafkaMirrorMaker2MutatingPlugin;
import io.strimzi.plugin.gatekeeper.GatekeeperKafkaMutatingPlugin;
import io.strimzi.plugin.gatekeeper.GatekeeperPluginConfigurationContext;
import io.strimzi.plugin.gatekeeper.KafkaAndKafkaNodePools;
import io.strimzi.plugin.gatekeeper.KafkaConnectAndKafkaConnectors;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * Gatekeeper plugin which injects the image pull secrets configured on the Cluster Operator into the custom resources it
 * receives. It is a <em>mutating</em> plugin which runs in the entry phase and modifies the resource's Pod template
 * sections so that the resulting Pods are created with those image pull secrets.
 * <p>
 * The image pull secrets are read once, at initialization, from the {@code STRIMZI_IMAGE_PULL_SECRETS} environment
 * variable (a comma-separated list of Secret names). When the environment variable is not set, the plugin does nothing.
 * <p>
 * The plugin sets the image pull secrets on every Pod template of the supported resources - Kafka (together with its
 * KafkaNodePools), KafkaConnect, KafkaMirrorMaker2 and KafkaBridge - including the additional components of a Kafka
 * cluster (Entity Operator, Cruise Control and Kafka Exporter) when they are configured, and the build Pod of
 * KafkaConnect when a build is configured. It never enables a component which is not already configured. If a Pod
 * template already has image pull secrets set explicitly, they are left unchanged - this matches the operator's own
 * behaviour, where the image pull secrets set on a Pod template take precedence over {@code STRIMZI_IMAGE_PULL_SECRETS}.
 * <p>
 * For the Kafka nodes, the Pod template can be set on the Kafka resource ({@code spec.kafka.template.pod}) or on a
 * KafkaNodePool ({@code spec.template.pod}); a KafkaNodePool template replaces the Kafka-level one for that pool. The
 * plugin therefore sets the secrets on whichever Pod templates already exist and, for a node pool which has neither its
 * own Pod template nor a Kafka-level one to inherit, creates the Pod template on the KafkaNodePool.
 * <p>
 * The operator applies {@code STRIMZI_IMAGE_PULL_SECRETS} to the Pods on its own as well; this plugin does not replace
 * that logic, it performs the same configuration by mutating the custom resource.
 */
@SuppressWarnings("checkstyle:ClassFanOutComplexity") // This class intentionally references the types of all the operands, which raises its fan-out above the limit
public class ImagePullSecretsGatekeeperPlugin implements
        GatekeeperKafkaMutatingPlugin,
        GatekeeperKafkaConnectMutatingPlugin,
        GatekeeperKafkaMirrorMaker2MutatingPlugin,
        GatekeeperKafkaBridgeMutatingPlugin {
    private static final String STRIMZI_IMAGE_PULL_SECRETS = "STRIMZI_IMAGE_PULL_SECRETS";

    private List<LocalObjectReference> imagePullSecrets;

    /**
     * Creates the image pull secrets plugin.
     */
    public ImagePullSecretsGatekeeperPlugin() { }

    @Override
    public void configure(GatekeeperPluginConfigurationContext context) {
        // The image pull secrets are read once at startup from the environment variable. An invalid value throws and
        // fails the operator startup, matching how the operator parses the same variable.
        this.imagePullSecrets = parseImagePullSecrets(System.getenv(STRIMZI_IMAGE_PULL_SECRETS));
    }

    @Override
    public CompletionStage<KafkaAndKafkaNodePools> kafkaEntry(GatekeeperKafkaEntryContext context, Kafka kafka, List<KafkaNodePool> kafkaNodePools) {
        if (isConfigured() && kafka.getSpec() != null) {
            applyToKafkaNodes(kafka, kafkaNodePools);
            applyToKafkaComponents(kafka.getSpec());
        }

        return CompletableFuture.completedFuture(new KafkaAndKafkaNodePools(kafka, kafkaNodePools));
    }

    @Override
    public CompletionStage<KafkaConnectAndKafkaConnectors> kafkaConnectEntry(GatekeeperKafkaConnectEntryContext context, KafkaConnect kafkaConnect, List<KafkaConnector> kafkaConnectors) {
        if (isConfigured() && kafkaConnect.getSpec() != null) {
            KafkaConnectSpec spec = kafkaConnect.getSpec();
            KafkaConnectTemplate template = spec.getTemplate();
            if (template == null) {
                template = new KafkaConnectTemplate();
                spec.setTemplate(template);
            }

            applyToPodTemplate(template.getPod(), template::setPod);

            // The build Pod exists only when a build is configured
            if (spec.getBuild() != null) {
                applyToPodTemplate(template.getBuildPod(), template::setBuildPod);
            }
        }

        return CompletableFuture.completedFuture(new KafkaConnectAndKafkaConnectors(kafkaConnect, kafkaConnectors));
    }

    @Override
    public CompletionStage<KafkaMirrorMaker2> kafkaMirrorMaker2Entry(GatekeeperKafkaMirrorMaker2EntryContext context, KafkaMirrorMaker2 kafkaMirrorMaker2) {
        if (isConfigured() && kafkaMirrorMaker2.getSpec() != null) {
            KafkaMirrorMaker2Spec spec = kafkaMirrorMaker2.getSpec();
            KafkaConnectTemplate template = spec.getTemplate();
            if (template == null) {
                template = new KafkaConnectTemplate();
                spec.setTemplate(template);
            }

            // KafkaMirrorMaker2 does not support builds, so it has no build Pod
            applyToPodTemplate(template.getPod(), template::setPod);
        }

        return CompletableFuture.completedFuture(kafkaMirrorMaker2);
    }

    @Override
    public CompletionStage<KafkaBridge> kafkaBridgeEntry(GatekeeperKafkaBridgeEntryContext context, KafkaBridge kafkaBridge) {
        if (isConfigured() && kafkaBridge.getSpec() != null) {
            KafkaBridgeSpec spec = kafkaBridge.getSpec();
            KafkaBridgeTemplate template = spec.getTemplate();
            if (template == null) {
                template = new KafkaBridgeTemplate();
                spec.setTemplate(template);
            }

            applyToPodTemplate(template.getPod(), template::setPod);
        }

        return CompletableFuture.completedFuture(kafkaBridge);
    }

    /**
     * Injects the image pull secrets for the Kafka nodes. The Pod template for the Kafka nodes can be set on the Kafka
     * resource or on a KafkaNodePool (which replaces the Kafka-level one for that pool). The secrets are set on whichever
     * Pod templates already exist; a node pool which has neither its own Pod template nor a Kafka-level one to inherit
     * gets a new Pod template.
     */
    private void applyToKafkaNodes(Kafka kafka, List<KafkaNodePool> kafkaNodePools) {
        PodTemplate kafkaLevelPod = kafka.getSpec().getKafka() != null && kafka.getSpec().getKafka().getTemplate() != null
                ? kafka.getSpec().getKafka().getTemplate().getPod() : null;

        if (kafkaLevelPod != null) {
            setImagePullSecretsIfAbsent(kafkaLevelPod);
        }

        if (kafkaNodePools != null) {
            for (KafkaNodePool nodePool : kafkaNodePools) {
                if (nodePool.getSpec() == null) {
                    continue;
                }

                KafkaNodePoolTemplate template = nodePool.getSpec().getTemplate();
                PodTemplate nodePoolPod = template != null ? template.getPod() : null;

                if (nodePoolPod != null) {
                    // The node pool has its own Pod template (it replaces the Kafka-level one) => set the secrets there
                    setImagePullSecretsIfAbsent(nodePoolPod);
                } else if (kafkaLevelPod == null) {
                    // Neither the Kafka resource nor this node pool has a Pod template => create it on the node pool
                    if (template == null) {
                        template = new KafkaNodePoolTemplate();
                        nodePool.getSpec().setTemplate(template);
                    }
                    template.setPod(newPodTemplateWithImagePullSecrets());
                }
                // else: the node pool has no Pod template but the Kafka resource does => the node pool inherits it
            }
        }
    }

    /**
     * Injects the image pull secrets into the Pod templates of the additional components of a Kafka cluster - Entity
     * Operator, Cruise Control and Kafka Exporter - but only for the components which are configured, so that a component
     * which is not configured is never enabled by the plugin.
     */
    private void applyToKafkaComponents(KafkaSpec spec) {
        if (spec.getEntityOperator() != null) {
            EntityOperatorSpec entityOperator = spec.getEntityOperator();
            if (entityOperator.getTemplate() == null) {
                entityOperator.setTemplate(new EntityOperatorTemplate());
            }
            applyToPodTemplate(entityOperator.getTemplate().getPod(), entityOperator.getTemplate()::setPod);
        }

        if (spec.getCruiseControl() != null) {
            CruiseControlSpec cruiseControl = spec.getCruiseControl();
            if (cruiseControl.getTemplate() == null) {
                cruiseControl.setTemplate(new CruiseControlTemplate());
            }
            applyToPodTemplate(cruiseControl.getTemplate().getPod(), cruiseControl.getTemplate()::setPod);
        }

        if (spec.getKafkaExporter() != null) {
            KafkaExporterSpec kafkaExporter = spec.getKafkaExporter();
            if (kafkaExporter.getTemplate() == null) {
                kafkaExporter.setTemplate(new KafkaExporterTemplate());
            }
            applyToPodTemplate(kafkaExporter.getTemplate().getPod(), kafkaExporter.getTemplate()::setPod);
        }
    }

    /**
     * Sets the image pull secrets on the given Pod template if it exists, or creates a new Pod template with the image
     * pull secrets and stores it using the given setter if it does not.
     *
     * @param existingPod   The existing Pod template, or {@code null} if there is none
     * @param setter        Setter used to store a newly created Pod template
     */
    private void applyToPodTemplate(PodTemplate existingPod, Consumer<PodTemplate> setter) {
        if (existingPod == null) {
            setter.accept(newPodTemplateWithImagePullSecrets());
        } else {
            setImagePullSecretsIfAbsent(existingPod);
        }
    }

    /**
     * Sets the image pull secrets on the given Pod template, but only if they are not already set. Image pull secrets
     * which are set explicitly on the Pod template take precedence and are left unchanged.
     *
     * @param pod   The Pod template on which to set the image pull secrets
     */
    private void setImagePullSecretsIfAbsent(PodTemplate pod) {
        if (pod.getImagePullSecrets() == null) {
            pod.setImagePullSecrets(new ArrayList<>(imagePullSecrets));
        }
    }

    private PodTemplate newPodTemplateWithImagePullSecrets() {
        return new PodTemplateBuilder().withImagePullSecrets(new ArrayList<>(imagePullSecrets)).build();
    }

    private boolean isConfigured() {
        return imagePullSecrets != null && !imagePullSecrets.isEmpty();
    }

    /**
     * Parses the value of the {@code STRIMZI_IMAGE_PULL_SECRETS} environment variable (a comma-separated list of Secret
     * names) into a list of {@link LocalObjectReference}s, reusing the same parser as the operator configuration.
     *
     * @param envValue  The value of the environment variable, or {@code null} if it is not set
     *
     * @return  The list of image pull secrets, or {@code null} when the value is not set
     */
    static List<LocalObjectReference> parseImagePullSecrets(String envValue) {
        return ConfigParameterParser.LOCAL_OBJECT_REFERENCE_LIST.parse(envValue);
    }

    /**
     * Sets the image pull secrets used by the plugin. Used by tests to avoid reading the environment variable.
     *
     * @param imagePullSecrets  The image pull secrets to inject
     */
    /* test */ void setImagePullSecrets(List<LocalObjectReference> imagePullSecrets) {
        this.imagePullSecrets = imagePullSecrets;
    }
}
