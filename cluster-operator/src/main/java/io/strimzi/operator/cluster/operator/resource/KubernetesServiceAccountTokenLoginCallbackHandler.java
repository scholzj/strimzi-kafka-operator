/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.operator.resource;

import io.fabric8.kubernetes.api.model.authentication.TokenRequest;
import io.fabric8.kubernetes.api.model.authentication.TokenRequestBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.apache.kafka.common.security.auth.AuthenticateCallbackHandler;
import org.apache.kafka.common.security.oauthbearer.OAuthBearerToken;
import org.apache.kafka.common.security.oauthbearer.OAuthBearerTokenCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.login.AppConfigurationEntry;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Kafka SASL/OAUTHBEARER login callback handler that mints a fresh token via the Kubernetes
 * {@code TokenRequest} API on each invocation. The token is bound to a configured Service
 * Account in a configured namespace, with a configured audience.
 *
 * <p>The handler caches nothing internally — Kafka's
 * {@link org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule} infrastructure
 * uses the {@link OAuthBearerToken#lifetimeMs()} value returned here to schedule the next
 * refresh, so a new TokenRequest is only made when the prior token is close to expiring.</p>
 *
 * <p>Configured via JAAS options on the {@code OAuthBearerLoginModule} entry:</p>
 * <pre>{@code
 * sasl.login.callback.handler.class=io.strimzi.operator.cluster.operator.resource.KubernetesServiceAccountTokenLoginCallbackHandler
 * sasl.jaas.config=org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required \
 *     strimzi.k8s.token.namespace="my-kafka-ns" \
 *     strimzi.k8s.token.serviceAccountName="my-cluster-cluster-operator" \
 *     strimzi.k8s.token.audience="strimzi.io" \
 *     strimzi.k8s.token.expirationSeconds="3600";
 * }</pre>
 */
public class KubernetesServiceAccountTokenLoginCallbackHandler implements AuthenticateCallbackHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(KubernetesServiceAccountTokenLoginCallbackHandler.class);

    /** JAAS option: namespace containing the Service Account to mint tokens for. */
    public static final String NAMESPACE_OPTION = "strimzi.k8s.token.namespace";
    /** JAAS option: name of the Service Account to mint tokens for. */
    public static final String SERVICE_ACCOUNT_OPTION = "strimzi.k8s.token.serviceAccountName";
    /** JAAS option: audience claim to request on the token. */
    public static final String AUDIENCE_OPTION = "strimzi.k8s.token.audience";
    /** JAAS option: requested token lifetime in seconds. */
    public static final String EXPIRATION_SECONDS_OPTION = "strimzi.k8s.token.expirationSeconds";

    private static final String DEFAULT_AUDIENCE = "strimzi.io";
    private static final long DEFAULT_EXPIRATION_SECONDS = 3600L;

    private String namespace;
    private String serviceAccountName;
    private String audience;
    private long expirationSeconds;
    private String principalName;
    private KubernetesClient client;

    /**
     * Default constructor — required because Kafka loads the handler via reflection.
     */
    public KubernetesServiceAccountTokenLoginCallbackHandler() {
    }

    @Override
    public void configure(Map<String, ?> configs, String saslMechanism, List<AppConfigurationEntry> jaasConfigEntries) {
        if (jaasConfigEntries == null || jaasConfigEntries.isEmpty()) {
            throw new IllegalArgumentException("No JAAS configuration entry found for " + getClass().getName());
        }
        Map<String, ?> options = jaasConfigEntries.get(0).getOptions();

        namespace = requiredOption(options, NAMESPACE_OPTION);
        serviceAccountName = requiredOption(options, SERVICE_ACCOUNT_OPTION);
        audience = optionalOption(options, AUDIENCE_OPTION, DEFAULT_AUDIENCE);
        expirationSeconds = Long.parseLong(optionalOption(options, EXPIRATION_SECONDS_OPTION, Long.toString(DEFAULT_EXPIRATION_SECONDS)));
        principalName = "system:serviceaccount:" + namespace + ":" + serviceAccountName;

        client = buildKubernetesClient();
        LOGGER.info("Configured Kubernetes Service Account token login for {} (audience={})", principalName, audience);
    }

    /**
     * Factory for the Kubernetes client. Overridable so tests can inject a mock.
     *
     * @return  Kubernetes client used to call the TokenRequest API
     */
    /* test */ KubernetesClient buildKubernetesClient() {
        return new KubernetesClientBuilder().build();
    }

    @Override
    public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
        for (Callback callback : callbacks) {
            if (callback instanceof OAuthBearerTokenCallback tokenCallback) {
                try {
                    tokenCallback.token(mintToken());
                } catch (RuntimeException e) {
                    LOGGER.error("Failed to mint Service Account token for {}", principalName, e);
                    tokenCallback.error("invalid_token", e.getMessage(), null);
                }
            } else {
                throw new UnsupportedCallbackException(callback);
            }
        }
    }

    private OAuthBearerToken mintToken() {
        TokenRequest request = new TokenRequestBuilder()
                .withNewSpec()
                    .withAudiences(audience)
                    .withExpirationSeconds(expirationSeconds)
                .endSpec()
                .build();
        TokenRequest response = client.serviceAccounts()
                .inNamespace(namespace)
                .withName(serviceAccountName)
                .tokenRequest(request);

        if (response == null || response.getStatus() == null || response.getStatus().getToken() == null) {
            throw new IllegalStateException("Kubernetes API did not return a token for ServiceAccount " + namespace + "/" + serviceAccountName);
        }

        String tokenValue = response.getStatus().getToken();
        long lifetimeMs = Instant.parse(response.getStatus().getExpirationTimestamp()).toEpochMilli();
        long startTimeMs = System.currentTimeMillis();
        return new ServiceAccountToken(tokenValue, principalName, lifetimeMs, startTimeMs);
    }

    @Override
    public void close() {
        if (client != null) {
            client.close();
        }
    }

    private static String requiredOption(Map<String, ?> options, String key) {
        Object value = options.get(key);
        if (value == null || value.toString().isEmpty()) {
            throw new IllegalArgumentException("Required JAAS option '" + key + "' is missing or empty");
        }
        return value.toString();
    }

    private static String optionalOption(Map<String, ?> options, String key, String defaultValue) {
        Object value = options.get(key);
        if (value == null || value.toString().isEmpty()) {
            return defaultValue;
        }
        return value.toString();
    }

    private static final class ServiceAccountToken implements OAuthBearerToken {
        private final String token;
        private final String principalName;
        private final long lifetimeMs;
        private final long startTimeMs;

        ServiceAccountToken(String token, String principalName, long lifetimeMs, long startTimeMs) {
            this.token = token;
            this.principalName = principalName;
            this.lifetimeMs = lifetimeMs;
            this.startTimeMs = startTimeMs;
        }

        @Override
        public String value() {
            return token;
        }

        @Override
        public Set<String> scope() {
            return Collections.emptySet();
        }

        @Override
        public long lifetimeMs() {
            return lifetimeMs;
        }

        @Override
        public String principalName() {
            return principalName;
        }

        @Override
        public Long startTimeMs() {
            return startTimeMs;
        }
    }
}
