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
import io.strimzi.operator.common.Reconciliation;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class KafkaAgentClientTest {
    private static final Reconciliation RECONCILIATION = new Reconciliation("test", "kafka", "namespace", "my-cluster");

    @Test
    public void testBrokerInRecoveryState() {
        KafkaAgentClient kafkaAgentClient = spy(new KafkaAgentClient(RECONCILIATION, "my-cluster", "namespace"));
        doAnswer(invocation -> "{\"brokerState\":2,\"recoveryState\":{\"remainingLogsToRecover\":10,\"remainingSegmentsToRecover\":100}}").when(kafkaAgentClient).doGet(any());
        BrokerState actual = kafkaAgentClient.getBrokerState("mypod");
        assertTrue(actual.isBrokerInRecovery(), "broker is not in log recovery as expected");
        assertEquals(10, actual.remainingLogsToRecover());
        assertEquals(100, actual.remainingSegmentsToRecover());
    }

    @Test
    public void testBrokerInRunningState() {
        KafkaAgentClient kafkaAgentClient = spy(new KafkaAgentClient(RECONCILIATION, "my-cluster", "namespace"));
        doAnswer(invocation -> "{\"brokerState\":3}").when(kafkaAgentClient).doGet(any());

        BrokerState actual = kafkaAgentClient.getBrokerState("mypod");
        assertEquals(3, actual.code());
        assertEquals(0, actual.remainingLogsToRecover());
        assertEquals(0, actual.remainingSegmentsToRecover());
    }

    @Test
    public void testInvalidJsonResponse() {
        KafkaAgentClient kafkaAgentClient = spy(new KafkaAgentClient(RECONCILIATION, "my-cluster", "namespace"));
        doAnswer(invocation -> "&\"brokerState\":3&").when(kafkaAgentClient).doGet(any());

        BrokerState actual = kafkaAgentClient.getBrokerState("mypod");
        assertEquals(-1, actual.code());
        assertEquals(0, actual.remainingLogsToRecover());
        assertEquals(0, actual.remainingSegmentsToRecover());
    }

    @Test
    public void testErrorResponse() {
        KafkaAgentClient kafkaAgentClient = spy(new KafkaAgentClient(RECONCILIATION, "my-cluster", "namespace"));
        doAnswer(invocation -> {
            throw new RuntimeException("Test failure");
        }).when(kafkaAgentClient).doGet(any());

        BrokerState actual = kafkaAgentClient.getBrokerState("mypod");
        assertEquals(-1, actual.code());
        assertEquals(0, actual.remainingLogsToRecover());
        assertEquals(0, actual.remainingSegmentsToRecover());
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

    @Test
    public void testCurrentTokenMintsFromTokenRequestApi() {
        KubernetesClient client = mock(KubernetesClient.class);
        ServiceAccountResource saResource = mockServiceAccountResource(client, "namespace", "my-cluster-cluster-operator");
        when(saResource.tokenRequest(any(TokenRequest.class))).thenReturn(tokenRequestWith("jwt-token", Instant.now().plusSeconds(3600)));

        KafkaAgentClient kafkaAgentClient = new KafkaAgentClient(RECONCILIATION, "my-cluster", "namespace", client);

        assertThat(kafkaAgentClient.currentToken(), is("jwt-token"));
        verify(saResource, times(1)).tokenRequest(any(TokenRequest.class));
    }

    @Test
    public void testCurrentTokenReusesCachedTokenUntilNearExpiration() {
        KubernetesClient client = mock(KubernetesClient.class);
        ServiceAccountResource saResource = mockServiceAccountResource(client, "namespace", "my-cluster-cluster-operator");
        when(saResource.tokenRequest(any(TokenRequest.class)))
                .thenReturn(tokenRequestWith("first-token", Instant.now().plusSeconds(3600)))
                .thenReturn(tokenRequestWith("second-token", Instant.now().plusSeconds(7200)));

        KafkaAgentClient kafkaAgentClient = new KafkaAgentClient(RECONCILIATION, "my-cluster", "namespace", client);

        assertThat(kafkaAgentClient.currentToken(), is("first-token"));
        assertThat(kafkaAgentClient.currentToken(), is("first-token"));
        verify(saResource, times(1)).tokenRequest(any(TokenRequest.class));
    }

    @Test
    public void testCurrentTokenRefreshesWhenAlreadyExpired() {
        KubernetesClient client = mock(KubernetesClient.class);
        ServiceAccountResource saResource = mockServiceAccountResource(client, "namespace", "my-cluster-cluster-operator");
        // First response: already-expired token. Second response: a fresh one.
        when(saResource.tokenRequest(any(TokenRequest.class)))
                .thenReturn(tokenRequestWith("expired-token", Instant.now().minusSeconds(10)))
                .thenReturn(tokenRequestWith("fresh-token", Instant.now().plusSeconds(3600)));

        KafkaAgentClient kafkaAgentClient = new KafkaAgentClient(RECONCILIATION, "my-cluster", "namespace", client);

        assertThat(kafkaAgentClient.currentToken(), is("expired-token"));
        // Second call sees that the cached token is past its refresh threshold and re-mints.
        assertThat(kafkaAgentClient.currentToken(), is("fresh-token"));
        verify(saResource, times(2)).tokenRequest(any(TokenRequest.class));
    }

    @Test
    public void testCurrentTokenFailsWhenApiReturnsNoToken() {
        KubernetesClient client = mock(KubernetesClient.class);
        ServiceAccountResource saResource = mockServiceAccountResource(client, "namespace", "my-cluster-cluster-operator");
        when(saResource.tokenRequest(any(TokenRequest.class))).thenReturn(new TokenRequestBuilder().withNewStatus().endStatus().build());

        KafkaAgentClient kafkaAgentClient = new KafkaAgentClient(RECONCILIATION, "my-cluster", "namespace", client);

        RuntimeException e = assertThrows(RuntimeException.class, kafkaAgentClient::currentToken);
        assertTrue(e.getMessage().contains("my-cluster-cluster-operator"));
    }
}
