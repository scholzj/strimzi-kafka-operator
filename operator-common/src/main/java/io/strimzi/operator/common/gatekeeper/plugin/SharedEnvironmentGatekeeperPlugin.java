/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.common.gatekeeper.plugin;

import io.strimzi.api.kafka.model.bridge.KafkaBridge;
import io.strimzi.api.kafka.model.bridge.KafkaBridgeSpec;
import io.strimzi.api.kafka.model.bridge.KafkaBridgeTemplate;
import io.strimzi.api.kafka.model.common.template.ContainerEnvVar;
import io.strimzi.api.kafka.model.common.template.ContainerEnvVarBuilder;
import io.strimzi.api.kafka.model.common.template.ContainerTemplate;
import io.strimzi.api.kafka.model.connect.KafkaConnect;
import io.strimzi.api.kafka.model.connect.KafkaConnectSpec;
import io.strimzi.api.kafka.model.connect.KafkaConnectTemplate;
import io.strimzi.api.kafka.model.connector.KafkaConnector;
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.api.kafka.model.kafka.KafkaClusterTemplate;
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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Gatekeeper plugin which injects the shared environment variables configured on the Cluster Operator into the custom
 * resources it receives. It is a <em>mutating</em> plugin which runs in the entry phase and modifies the resource's
 * container template sections so that the resulting containers are created with those environment variables.
 * <p>
 * The environment variables are read once, at initialization, from the operator's own environment. The names below
 * mirror the shared environment variables which the operator propagates to all containers (the HTTP(S) proxy settings
 * and the FIPS mode); they are duplicated here on purpose so that the plugin is self-contained. Only the variables which
 * are actually set are injected. When none of them is set, the plugin does nothing.
 * <p>
 * The plugin sets the environment variables on every container template of the supported resources - Kafka (together
 * with its KafkaNodePools), KafkaConnect, KafkaMirrorMaker2 and KafkaBridge - including the containers of the additional
 * components of a Kafka cluster (Entity Operator, Cruise Control and Kafka Exporter) when they are configured, and the
 * build container of KafkaConnect when a build is configured. It never enables a component which is not already
 * configured. An environment variable is added to a container template only if the template does not already define an
 * environment variable with the same name, so that variables set explicitly by the user are left unchanged.
 * <p>
 * For the Kafka nodes, the container templates can be set on the Kafka resource ({@code spec.kafka.template}) or on a
 * KafkaNodePool ({@code spec.template}); a KafkaNodePool container template replaces the Kafka-level one for that pool.
 * The plugin therefore sets the variables on whichever container templates already exist and, for a node pool which has
 * neither its own container template nor a Kafka-level one to inherit, creates the container template on the
 * KafkaNodePool.
 * <p>
 * The operator propagates these shared environment variables to the containers on its own as well; this plugin does not
 * replace that logic, it performs the same configuration by mutating the custom resource.
 */
@SuppressWarnings("checkstyle:ClassFanOutComplexity") // This class intentionally references the types of all the operands, which raises its fan-out above the limit
public class SharedEnvironmentGatekeeperPlugin implements
        GatekeeperKafkaMutatingPlugin,
        GatekeeperKafkaConnectMutatingPlugin,
        GatekeeperKafkaMirrorMaker2MutatingPlugin,
        GatekeeperKafkaBridgeMutatingPlugin {
    // The names of the shared environment variables propagated to all containers. These mirror the Cluster Operator's
    // SharedEnvironmentProvider and are duplicated here so that the plugin does not depend on the cluster-operator module.
    private static final List<String> SHARED_ENVIRONMENT_VARIABLE_NAMES = List.of("HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "FIPS_MODE");

    private List<ContainerEnvVar> sharedEnvironmentVariables = List.of();

    /**
     * Creates the shared environment plugin.
     */
    public SharedEnvironmentGatekeeperPlugin() { }

    @Override
    public void configure(GatekeeperPluginConfigurationContext context) {
        List<ContainerEnvVar> variables = new ArrayList<>();

        for (String name : SHARED_ENVIRONMENT_VARIABLE_NAMES) {
            String value = System.getenv(name);
            if (value != null) {
                variables.add(new ContainerEnvVarBuilder().withName(name).withValue(value).build());
            }
        }

        this.sharedEnvironmentVariables = variables;
    }

    @Override
    public CompletionStage<KafkaAndKafkaNodePools> kafkaEntry(GatekeeperKafkaEntryContext context, Kafka kafka, List<KafkaNodePool> kafkaNodePools) {
        if (isConfigured() && kafka.getSpec() != null) {
            applyToKafkaNodeContainer(kafka, kafkaNodePools, KafkaClusterTemplate::getKafkaContainer, KafkaNodePoolTemplate::getKafkaContainer, KafkaNodePoolTemplate::setKafkaContainer);
            applyToKafkaNodeContainer(kafka, kafkaNodePools, KafkaClusterTemplate::getInitContainer, KafkaNodePoolTemplate::getInitContainer, KafkaNodePoolTemplate::setInitContainer);
            applyToKafkaComponents(kafka.getSpec());
        }

        return CompletableFuture.completedFuture(new KafkaAndKafkaNodePools(kafka, kafkaNodePools));
    }

    @Override
    public CompletionStage<KafkaConnectAndKafkaConnectors> kafkaConnectEntry(GatekeeperKafkaConnectEntryContext context, KafkaConnect kafkaConnect, List<KafkaConnector> kafkaConnectors) {
        if (isConfigured() && kafkaConnect.getSpec() != null) {
            KafkaConnectSpec spec = kafkaConnect.getSpec();
            KafkaConnectTemplate template = ensureTemplate(spec.getTemplate(), KafkaConnectTemplate::new, spec::setTemplate);

            applyToContainer(template.getConnectContainer(), template::setConnectContainer);
            applyToContainer(template.getInitContainer(), template::setInitContainer);

            // The build container exists only when a build is configured
            if (spec.getBuild() != null) {
                applyToContainer(template.getBuildContainer(), template::setBuildContainer);
            }
        }

        return CompletableFuture.completedFuture(new KafkaConnectAndKafkaConnectors(kafkaConnect, kafkaConnectors));
    }

    @Override
    public CompletionStage<KafkaMirrorMaker2> kafkaMirrorMaker2Entry(GatekeeperKafkaMirrorMaker2EntryContext context, KafkaMirrorMaker2 kafkaMirrorMaker2) {
        if (isConfigured() && kafkaMirrorMaker2.getSpec() != null) {
            KafkaMirrorMaker2Spec spec = kafkaMirrorMaker2.getSpec();
            KafkaConnectTemplate template = ensureTemplate(spec.getTemplate(), KafkaConnectTemplate::new, spec::setTemplate);

            // KafkaMirrorMaker2 does not support builds, so it has no build container
            applyToContainer(template.getConnectContainer(), template::setConnectContainer);
            applyToContainer(template.getInitContainer(), template::setInitContainer);
        }

        return CompletableFuture.completedFuture(kafkaMirrorMaker2);
    }

    @Override
    public CompletionStage<KafkaBridge> kafkaBridgeEntry(GatekeeperKafkaBridgeEntryContext context, KafkaBridge kafkaBridge) {
        if (isConfigured() && kafkaBridge.getSpec() != null) {
            KafkaBridgeSpec spec = kafkaBridge.getSpec();
            KafkaBridgeTemplate template = ensureTemplate(spec.getTemplate(), KafkaBridgeTemplate::new, spec::setTemplate);

            applyToContainer(template.getBridgeContainer(), template::setBridgeContainer);
            applyToContainer(template.getInitContainer(), template::setInitContainer);
        }

        return CompletableFuture.completedFuture(kafkaBridge);
    }

    /**
     * Injects the shared environment variables into one of the container templates of the Kafka nodes (either the main
     * Kafka container or the init container). The container template can be set on the Kafka resource or on a
     * KafkaNodePool (which replaces the Kafka-level one for that pool). The variables are set on whichever container
     * templates already exist; a node pool which has neither its own container template nor a Kafka-level one to inherit
     * gets a new container template.
     *
     * @param kafka                     The Kafka resource
     * @param kafkaNodePools            The node pools belonging to the Kafka
     * @param clusterContainerGetter    Getter for the container template on the Kafka-level template
     * @param nodePoolContainerGetter   Getter for the container template on a node pool template
     * @param nodePoolContainerSetter   Setter for the container template on a node pool template
     */
    private void applyToKafkaNodeContainer(Kafka kafka,
                                           List<KafkaNodePool> kafkaNodePools,
                                           Function<KafkaClusterTemplate, ContainerTemplate> clusterContainerGetter,
                                           Function<KafkaNodePoolTemplate, ContainerTemplate> nodePoolContainerGetter,
                                           BiConsumer<KafkaNodePoolTemplate, ContainerTemplate> nodePoolContainerSetter) {
        KafkaClusterTemplate clusterTemplate = kafka.getSpec().getKafka() != null ? kafka.getSpec().getKafka().getTemplate() : null;
        ContainerTemplate kafkaLevelContainer = clusterTemplate != null ? clusterContainerGetter.apply(clusterTemplate) : null;

        if (kafkaLevelContainer != null) {
            addMissingEnvironmentVariables(kafkaLevelContainer);
        }

        if (kafkaNodePools != null) {
            for (KafkaNodePool nodePool : kafkaNodePools) {
                if (nodePool.getSpec() == null) {
                    continue;
                }

                KafkaNodePoolTemplate nodePoolTemplate = nodePool.getSpec().getTemplate();
                ContainerTemplate nodePoolContainer = nodePoolTemplate != null ? nodePoolContainerGetter.apply(nodePoolTemplate) : null;

                if (nodePoolContainer != null) {
                    // The node pool has its own container template (it replaces the Kafka-level one) => set the variables there
                    addMissingEnvironmentVariables(nodePoolContainer);
                } else if (kafkaLevelContainer == null) {
                    // Neither the Kafka resource nor this node pool has this container template => create it on the node pool
                    if (nodePoolTemplate == null) {
                        nodePoolTemplate = new KafkaNodePoolTemplate();
                        nodePool.getSpec().setTemplate(nodePoolTemplate);
                    }
                    ContainerTemplate created = new ContainerTemplate();
                    nodePoolContainerSetter.accept(nodePoolTemplate, created);
                    addMissingEnvironmentVariables(created);
                }
                // else: the node pool has no container template but the Kafka resource does => the node pool inherits it
            }
        }
    }

    /**
     * Injects the shared environment variables into the container templates of the additional components of a Kafka
     * cluster - Entity Operator, Cruise Control and Kafka Exporter - but only for the components which are configured, so
     * that a component which is not configured is never enabled by the plugin.
     */
    private void applyToKafkaComponents(KafkaSpec spec) {
        if (spec.getEntityOperator() != null) {
            EntityOperatorSpec entityOperator = spec.getEntityOperator();
            EntityOperatorTemplate template = ensureTemplate(entityOperator.getTemplate(), EntityOperatorTemplate::new, entityOperator::setTemplate);
            applyToContainer(template.getTopicOperatorContainer(), template::setTopicOperatorContainer);
            applyToContainer(template.getUserOperatorContainer(), template::setUserOperatorContainer);
        }

        if (spec.getCruiseControl() != null) {
            CruiseControlSpec cruiseControl = spec.getCruiseControl();
            CruiseControlTemplate template = ensureTemplate(cruiseControl.getTemplate(), CruiseControlTemplate::new, cruiseControl::setTemplate);
            applyToContainer(template.getCruiseControlContainer(), template::setCruiseControlContainer);
        }

        if (spec.getKafkaExporter() != null) {
            KafkaExporterSpec kafkaExporter = spec.getKafkaExporter();
            KafkaExporterTemplate template = ensureTemplate(kafkaExporter.getTemplate(), KafkaExporterTemplate::new, kafkaExporter::setTemplate);
            applyToContainer(template.getContainer(), template::setContainer);
        }
    }

    /**
     * Sets the shared environment variables on the given container template, creating it and storing it using the given
     * setter if it does not exist yet.
     *
     * @param existingContainer     The existing container template, or {@code null} if there is none
     * @param setter                Setter used to store a newly created container template
     */
    private void applyToContainer(ContainerTemplate existingContainer, Consumer<ContainerTemplate> setter) {
        ContainerTemplate container = existingContainer;

        if (container == null) {
            container = new ContainerTemplate();
            setter.accept(container);
        }

        addMissingEnvironmentVariables(container);
    }

    /**
     * Adds the shared environment variables to the given container template, skipping any whose name is already defined
     * on the template so that environment variables set explicitly by the user are left unchanged.
     *
     * @param container     The container template on which to set the environment variables
     */
    private void addMissingEnvironmentVariables(ContainerTemplate container) {
        List<ContainerEnvVar> env = container.getEnv();

        if (env == null) {
            env = new ArrayList<>();
            container.setEnv(env);
        }

        Set<String> existingNames = env.stream().map(ContainerEnvVar::getName).collect(Collectors.toSet());

        for (ContainerEnvVar variable : sharedEnvironmentVariables) {
            if (!existingNames.contains(variable.getName())) {
                env.add(new ContainerEnvVarBuilder().withName(variable.getName()).withValue(variable.getValue()).build());
            }
        }
    }

    /**
     * Returns the given template if it is not {@code null}, otherwise creates a new one using the given factory, stores
     * it using the given setter and returns it.
     */
    private static <T> T ensureTemplate(T existing, Supplier<T> factory, Consumer<T> setter) {
        if (existing != null) {
            return existing;
        }

        T created = factory.get();
        setter.accept(created);
        return created;
    }

    private boolean isConfigured() {
        return !sharedEnvironmentVariables.isEmpty();
    }

    /**
     * Sets the shared environment variables used by the plugin. Used by tests to avoid reading the operator environment.
     *
     * @param sharedEnvironmentVariables    The environment variables to inject
     */
    /* test */ void setSharedEnvironmentVariables(List<ContainerEnvVar> sharedEnvironmentVariables) {
        this.sharedEnvironmentVariables = sharedEnvironmentVariables;
    }
}
