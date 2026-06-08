/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.operator.resource;

import io.fabric8.kubernetes.api.model.authentication.TokenRequest;
import io.fabric8.kubernetes.api.model.authentication.TokenRequestBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.ServiceAccountResource;
import org.apache.kafka.common.security.oauthbearer.OAuthBearerToken;
import org.apache.kafka.common.security.oauthbearer.OAuthBearerTokenCallback;
import org.junit.jupiter.api.Test;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.login.AppConfigurationEntry;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class KubernetesServiceAccountTokenLoginCallbackHandlerTest {
    private static final String LOGIN_MODULE = "org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule";

    private static List<AppConfigurationEntry> jaas(Map<String, Object> options) {
        return List.of(new AppConfigurationEntry(LOGIN_MODULE, AppConfigurationEntry.LoginModuleControlFlag.REQUIRED, options));
    }

    private static Map<String, Object> fullOptions() {
        return Map.of(
                KubernetesServiceAccountTokenLoginCallbackHandler.NAMESPACE_OPTION, "my-ns",
                KubernetesServiceAccountTokenLoginCallbackHandler.SERVICE_ACCOUNT_OPTION, "my-cluster-cluster-operator",
                KubernetesServiceAccountTokenLoginCallbackHandler.AUDIENCE_OPTION, "strimzi.io",
                KubernetesServiceAccountTokenLoginCallbackHandler.EXPIRATION_SECONDS_OPTION, "1800"
        );
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ServiceAccountResource mockServiceAccountResource(KubernetesClient client, String namespace, String saName) {
        MixedOperation saOps = mock(MixedOperation.class);
        when(client.serviceAccounts()).thenReturn(saOps);
        MixedOperation namespaced = mock(MixedOperation.class);
        when(saOps.inNamespace(namespace)).thenReturn(namespaced);
        ServiceAccountResource resource = mock(ServiceAccountResource.class);
        when(namespaced.withName(saName)).thenReturn(resource);
        return resource;
    }

    private static TokenRequest tokenRequestWith(String token, Instant expirationTimestamp) {
        return new TokenRequestBuilder()
                .withNewStatus()
                    .withToken(token)
                    .withExpirationTimestamp(expirationTimestamp.toString())
                .endStatus()
                .build();
    }

    private static KubernetesServiceAccountTokenLoginCallbackHandler handlerWith(KubernetesClient client) {
        return new KubernetesServiceAccountTokenLoginCallbackHandler() {
            @Override
            KubernetesClient buildKubernetesClient() {
                return client;
            }
        };
    }

    @Test
    public void configureRequiresNamespace() {
        Map<String, Object> options = Map.of(
                KubernetesServiceAccountTokenLoginCallbackHandler.SERVICE_ACCOUNT_OPTION, "sa-name"
        );
        KubernetesServiceAccountTokenLoginCallbackHandler handler = handlerWith(mock(KubernetesClient.class));
        assertThrows(IllegalArgumentException.class, () -> handler.configure(Map.of(), "OAUTHBEARER", jaas(options)));
    }

    @Test
    public void configureRequiresServiceAccountName() {
        Map<String, Object> options = Map.of(
                KubernetesServiceAccountTokenLoginCallbackHandler.NAMESPACE_OPTION, "ns"
        );
        KubernetesServiceAccountTokenLoginCallbackHandler handler = handlerWith(mock(KubernetesClient.class));
        assertThrows(IllegalArgumentException.class, () -> handler.configure(Map.of(), "OAUTHBEARER", jaas(options)));
    }

    @Test
    public void configureRejectsMissingJaasEntries() {
        KubernetesServiceAccountTokenLoginCallbackHandler handler = handlerWith(mock(KubernetesClient.class));
        assertThrows(IllegalArgumentException.class, () -> handler.configure(Map.of(), "OAUTHBEARER", List.of()));
    }

    @Test
    public void handleMintsTokenForRequestedServiceAccount() throws Exception {
        KubernetesClient client = mock(KubernetesClient.class);
        ServiceAccountResource saResource = mockServiceAccountResource(client, "my-ns", "my-cluster-cluster-operator");
        Instant expiresAt = Instant.now().plusSeconds(1800);
        when(saResource.tokenRequest(any(TokenRequest.class))).thenReturn(tokenRequestWith("jwt-value", expiresAt));

        KubernetesServiceAccountTokenLoginCallbackHandler handler = handlerWith(client);
        handler.configure(Map.of(), "OAUTHBEARER", jaas(fullOptions()));

        OAuthBearerTokenCallback callback = new OAuthBearerTokenCallback();
        handler.handle(new Callback[] {callback});

        OAuthBearerToken token = callback.token();
        assertThat(token, notNullValue());
        assertThat(token.value(), is("jwt-value"));
        assertThat(token.principalName(), is("system:serviceaccount:my-ns:my-cluster-cluster-operator"));
        assertThat(token.lifetimeMs(), is(expiresAt.toEpochMilli()));
        assertThat(token.scope().isEmpty(), is(true));
    }

    @Test
    public void handleUsesDefaultsForOptionalJaasOptions() throws Exception {
        KubernetesClient client = mock(KubernetesClient.class);
        ServiceAccountResource saResource = mockServiceAccountResource(client, "ns", "sa-name");
        when(saResource.tokenRequest(any(TokenRequest.class))).thenReturn(tokenRequestWith("tok", Instant.now().plusSeconds(3600)));

        KubernetesServiceAccountTokenLoginCallbackHandler handler = handlerWith(client);
        handler.configure(Map.of(), "OAUTHBEARER", jaas(Map.of(
                KubernetesServiceAccountTokenLoginCallbackHandler.NAMESPACE_OPTION, "ns",
                KubernetesServiceAccountTokenLoginCallbackHandler.SERVICE_ACCOUNT_OPTION, "sa-name"
        )));

        OAuthBearerTokenCallback callback = new OAuthBearerTokenCallback();
        handler.handle(new Callback[] {callback});

        assertNotNull(callback.token());
    }

    @Test
    public void handleSetsErrorOnTokenCallbackWhenTokenRequestFails() throws Exception {
        KubernetesClient client = mock(KubernetesClient.class);
        ServiceAccountResource saResource = mockServiceAccountResource(client, "my-ns", "my-cluster-cluster-operator");
        when(saResource.tokenRequest(any(TokenRequest.class))).thenThrow(new RuntimeException("API server boom"));

        KubernetesServiceAccountTokenLoginCallbackHandler handler = handlerWith(client);
        handler.configure(Map.of(), "OAUTHBEARER", jaas(fullOptions()));

        OAuthBearerTokenCallback callback = new OAuthBearerTokenCallback();
        handler.handle(new Callback[] {callback});

        assertThat(callback.token(), is((OAuthBearerToken) null));
        assertThat(callback.errorCode(), is("invalid_token"));
        assertTrue(callback.errorDescription().contains("API server boom"));
    }

    @Test
    public void handleRejectsUnsupportedCallbacks() throws Exception {
        KubernetesServiceAccountTokenLoginCallbackHandler handler = handlerWith(mock(KubernetesClient.class));
        handler.configure(Map.of(), "OAUTHBEARER", jaas(fullOptions()));

        assertThrows(UnsupportedCallbackException.class,
                () -> handler.handle(new Callback[] {new NameCallback("test")}));
    }
}
