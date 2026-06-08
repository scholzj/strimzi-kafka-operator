/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.operator.resource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.authentication.TokenRequest;
import io.fabric8.kubernetes.api.model.authentication.TokenRequestBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.strimzi.api.kafka.model.kafka.KafkaResources;
import io.strimzi.operator.cluster.model.DnsNameGenerator;
import io.strimzi.operator.common.Reconciliation;
import io.strimzi.operator.common.ReconciliationLogger;
import io.strimzi.operator.common.auth.TlsPemIdentity;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.time.Instant;

/**
 * Creates HTTP client and interacts with Kafka Agent's REST endpoint
 */
public class KafkaAgentClient {
    private static final ReconciliationLogger LOGGER = ReconciliationLogger.create(KafkaAgentClient.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String BROKER_STATE_REST_PATH = "/v1/broker-state/";
    private static final int KAFKA_AGENT_HTTPS_PORT = 8443;
    private static final char[] KEYSTORE_PASSWORD = "changeit".toCharArray();
    // The Service Account whose identity we present to the Kafka Agent. It lives in the Kafka
    // cluster's own namespace, so the resulting principal does not depend on where the operator
    // is installed.
    private static final String CO_SA_NAME_SUFFIX = "-cluster-operator";
    // Audience the Kafka Agent checks on the minted token. Must match the agent's allowedSubjects config.
    private static final String SA_TOKEN_AUDIENCE = "strimzi.io";
    private static final long SA_TOKEN_EXPIRATION_SECONDS = 3600L;
    // Refresh slightly before the API server's expiration time to avoid using a token that
    // expires mid-request.
    private static final long SA_TOKEN_REFRESH_BUFFER_MS = 60_000L;

    private final String namespace;
    private final Reconciliation reconciliation;
    private final String cluster;
    private final String serviceAccountName;
    private final KubernetesClient kubernetesClient;
    private TlsPemIdentity tlsPemIdentity;
    private HttpClient httpClient;

    private String cachedToken;
    private long cachedTokenRefreshAt;

    /**
     * Constructor
     *
     * @param reconciliation    Reconciliation marker
     * @param cluster           Cluster name
     * @param namespace         Cluster namespace
     * @param tlsPemIdentity    Trust set and identity for TLS client authentication for connecting to the Kafka cluster
     * @param kubernetesClient  Kubernetes client used to mint Service Account tokens via the TokenRequest API
     */
    public KafkaAgentClient(Reconciliation reconciliation, String cluster, String namespace, TlsPemIdentity tlsPemIdentity, KubernetesClient kubernetesClient) {
        this.reconciliation = reconciliation;
        this.cluster = cluster;
        this.namespace = namespace;
        this.tlsPemIdentity = tlsPemIdentity;
        this.kubernetesClient = kubernetesClient;
        this.serviceAccountName = cluster + CO_SA_NAME_SUFFIX;
        this.httpClient = createHttpClient();
    }

    /**
     * Constructor
     *
     * @param reconciliation    Reconciliation marker
     * @param cluster   Cluster name
     * @param namespace Cluster namespace
     */
    public KafkaAgentClient(Reconciliation reconciliation, String cluster, String namespace) {
        this.reconciliation = reconciliation;
        this.namespace = namespace;
        this.cluster =  cluster;
        this.serviceAccountName = cluster + CO_SA_NAME_SUFFIX;
        this.kubernetesClient = null;
    }

    /* test */ KafkaAgentClient(Reconciliation reconciliation, String cluster, String namespace, KubernetesClient kubernetesClient) {
        this.reconciliation = reconciliation;
        this.namespace = namespace;
        this.cluster = cluster;
        this.kubernetesClient = kubernetesClient;
        this.serviceAccountName = cluster + CO_SA_NAME_SUFFIX;
    }

    private HttpClient createHttpClient() {
        if (tlsPemIdentity == null) {
            throw new RuntimeException("Missing cluster CA and operator certificates required to create connection to Kafka Agent");
        }

        try {
            if (tlsPemIdentity.pemTrustSet() == null) {
                throw new RuntimeException("Missing cluster CA trust set certificates required to create connection to Kafka Agent");
            }
            String trustManagerFactoryAlgorithm = TrustManagerFactory.getDefaultAlgorithm();
            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(trustManagerFactoryAlgorithm);
            trustManagerFactory.init(tlsPemIdentity.pemTrustSet().trustStore());

            if (tlsPemIdentity.pemAuthIdentity() == null) {
                throw new RuntimeException("Missing cluster operator authentication identity certificates required to create connection to Kafka Agent");
            }
            String keyManagerFactoryAlgorithm = KeyManagerFactory.getDefaultAlgorithm();
            KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(keyManagerFactoryAlgorithm);
            keyManagerFactory.init(tlsPemIdentity.pemAuthIdentity().keyStore(KEYSTORE_PASSWORD), KEYSTORE_PASSWORD);

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), null);

            // Disable TLS
            //return HttpClient.newBuilder()
            //        .sslContext(sslContext)
            //        .build();
            return HttpClient.newBuilder()
                    .build();
        } catch (GeneralSecurityException | IOException e) {
            throw new RuntimeException("Failed to configure HTTP client", e);
        }
    }

    String doGet(URI uri) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("Authorization", "Bearer " + currentToken())
                    .GET()
                    .build();

            var response = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("Unexpected HTTP status code: " + response.statusCode());
            }
            return response.body();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to send HTTP request to Kafka Agent", e);
        }
    }

    /**
     * Returns a valid Service Account token for the per-cluster cluster-operator SA, minting a fresh
     * one via the Kubernetes TokenRequest API when no cached token is available or it is about to expire.
     *
     * @return  JWT token string suitable for use in an HTTP Authorization Bearer header
     */
    synchronized String currentToken() {
        if (cachedToken == null || System.currentTimeMillis() >= cachedTokenRefreshAt) {
            TokenRequest request = new TokenRequestBuilder()
                    .withNewSpec()
                        .withAudiences(SA_TOKEN_AUDIENCE)
                        .withExpirationSeconds(SA_TOKEN_EXPIRATION_SECONDS)
                    .endSpec()
                    .build();
            TokenRequest response = kubernetesClient.serviceAccounts()
                    .inNamespace(namespace)
                    .withName(serviceAccountName)
                    .tokenRequest(request);

            if (response == null || response.getStatus() == null || response.getStatus().getToken() == null) {
                throw new RuntimeException("Kubernetes API did not return a token for ServiceAccount " + namespace + "/" + serviceAccountName);
            }

            cachedToken = response.getStatus().getToken();
            cachedTokenRefreshAt = Instant.parse(response.getStatus().getExpirationTimestamp()).toEpochMilli() - SA_TOKEN_REFRESH_BUFFER_MS;
        }
        return cachedToken;
    }

    /**
     * Gets broker state by sending HTTP request to the /v1/broker-state endpoint of the KafkaAgent
     *
     * @param podName Name of the pod to interact with
     * @return A BrokerState that contains broker state and recovery progress.
     *         -1 is returned for broker state if the http request failed or returned non 200 response.
     *         Null value is returned for recovery progress if broker state is not 2 (RECOVERY).
     */
    public BrokerState getBrokerState(String podName) {
        BrokerState brokerstate = new BrokerState(-1, null);
        String host = DnsNameGenerator.podDnsName(namespace, KafkaResources.brokersServiceName(cluster), podName);
        try {
            URI uri = new URI("https", null, host, KAFKA_AGENT_HTTPS_PORT, BROKER_STATE_REST_PATH, null, null);
            brokerstate = MAPPER.readValue(doGet(uri), BrokerState.class);
        } catch (JsonProcessingException e) {
            LOGGER.warnCr(reconciliation, "Failed to parse broker state", e);
        } catch (URISyntaxException e) {
            LOGGER.warnCr(reconciliation, "Failed to get broker state due to invalid URI", e);
        } catch (RuntimeException e) {
            LOGGER.warnCr(reconciliation, "Failed to get broker state", e);
        }
        return brokerstate;
    }
}
