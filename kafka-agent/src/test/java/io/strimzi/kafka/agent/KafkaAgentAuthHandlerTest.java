/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafka.agent;

import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class KafkaAgentAuthHandlerTest {
    private static final String VALID_TOKEN = "valid.bearer.token";
    private static final String SUBJECT = "system:serviceaccount:myproject:strimzi-cluster-operator";

    private Server server;
    private ServerConnector authenticatedConnector;
    private ServerConnector openConnector;
    private KafkaAgentAuthenticator authenticator;
    private HttpClient httpClient;

    @BeforeEach
    public void setUp() {
        server = new Server();

        authenticatedConnector = new ServerConnector(server);
        authenticatedConnector.setHost("localhost");
        authenticatedConnector.setPort(0);

        openConnector = new ServerConnector(server);
        openConnector.setHost("localhost");
        openConnector.setPort(0);

        server.setConnectors(new Connector[] {authenticatedConnector, openConnector});

        authenticator = mock(KafkaAgentAuthenticator.class);

        httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (server != null) {
            server.stop();
        }
    }

    private void startServerWithAuthHandler(Handler wrapped) throws Exception {
        KafkaAgent.AuthHandler authHandler = new KafkaAgent.AuthHandler(authenticator, authenticatedConnector);
        authHandler.setHandler(wrapped);
        server.setHandler(authHandler);
        server.start();
    }

    private Handler okHandler() {
        return new Handler.Abstract() {
            @Override
            public boolean handle(Request request, Response response, Callback callback) {
                response.setStatus(HttpServletResponse.SC_OK);
                response.write(true, null, callback);
                return true;
            }
        };
    }

    private HttpResponse<String> send(int port, String authorization) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(new URI("http://localhost:" + port + "/v1/broker-state"))
                .GET();
        if (authorization != null) {
            builder.header(HttpHeader.AUTHORIZATION.asString(), authorization);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    public void openConnectorBypassesAuthEvenWithoutHeader() throws Exception {
        startServerWithAuthHandler(okHandler());

        HttpResponse<String> response = send(openConnector.getLocalPort(), null);

        assertThat(response.statusCode(), is(HttpServletResponse.SC_OK));
    }

    @Test
    public void authenticatedConnectorRejectsMissingHeader() throws Exception {
        doThrow(new KafkaAgentAuthenticator.AuthenticationException("Missing Authorization header"))
                .when(authenticator).authenticate(null);
        startServerWithAuthHandler(okHandler());

        HttpResponse<String> response = send(authenticatedConnector.getLocalPort(), null);

        assertThat(response.statusCode(), is(HttpServletResponse.SC_UNAUTHORIZED));
        assertThat(response.headers().firstValue(HttpHeader.WWW_AUTHENTICATE.asString()).orElse(""),
                is("Bearer realm=\"kafka-agent\""));
    }

    @Test
    public void authenticatedConnectorRejectsInvalidToken() throws Exception {
        doThrow(new KafkaAgentAuthenticator.AuthenticationException("Token validation failed"))
                .when(authenticator).authenticate("Bearer bogus");
        startServerWithAuthHandler(okHandler());

        HttpResponse<String> response = send(authenticatedConnector.getLocalPort(), "Bearer bogus");

        assertThat(response.statusCode(), is(HttpServletResponse.SC_UNAUTHORIZED));
    }

    @Test
    public void authenticatedConnectorAcceptsValidToken() throws Exception {
        when(authenticator.authenticate(eq("Bearer " + VALID_TOKEN))).thenReturn(SUBJECT);
        startServerWithAuthHandler(okHandler());

        HttpResponse<String> response = send(authenticatedConnector.getLocalPort(), "Bearer " + VALID_TOKEN);

        assertThat(response.statusCode(), is(HttpServletResponse.SC_OK));
    }

    @Test
    public void missingAuthenticatorOnAuthenticatedConnectorReturnsInternalError() throws Exception {
        KafkaAgent.AuthHandler authHandler = new KafkaAgent.AuthHandler(null, authenticatedConnector);
        authHandler.setHandler(okHandler());
        server.setHandler(authHandler);
        server.start();

        HttpResponse<String> response = send(authenticatedConnector.getLocalPort(), null);

        assertThat(response.statusCode(), is(HttpServletResponse.SC_INTERNAL_SERVER_ERROR));
    }
}
