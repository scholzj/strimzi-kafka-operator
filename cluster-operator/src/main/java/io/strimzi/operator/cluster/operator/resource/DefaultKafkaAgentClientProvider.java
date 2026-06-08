/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.operator.resource;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.strimzi.operator.common.Reconciliation;
import io.strimzi.operator.common.auth.TlsPemIdentity;

/**
 * Class to provide the real KafkaAgentClient which connects to actual Kafka Agent
 */
public class DefaultKafkaAgentClientProvider implements KafkaAgentClientProvider {
    private final KubernetesClient kubernetesClient;

    /**
     * Constructor
     *
     * @param kubernetesClient  Kubernetes client used to mint Service Account tokens for authenticating to the Kafka Agent
     */
    public DefaultKafkaAgentClientProvider(KubernetesClient kubernetesClient) {
        this.kubernetesClient = kubernetesClient;
    }

    /**
     * No-argument constructor kept for tests that pass the provider as a placeholder without ever
     * calling {@link #createKafkaAgentClient}. Production code must use {@link #DefaultKafkaAgentClientProvider(KubernetesClient)}.
     */
    /* test */ public DefaultKafkaAgentClientProvider() {
        this(null);
    }

    @Override
    public KafkaAgentClient createKafkaAgentClient(Reconciliation reconciliation, TlsPemIdentity tlsPemIdentity) {
        return new KafkaAgentClient(reconciliation, reconciliation.name(), reconciliation.namespace(), tlsPemIdentity, kubernetesClient);
    }
}
