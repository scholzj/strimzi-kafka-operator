/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafka.metaauthorizer;

import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.security.auth.KafkaPrincipal;
import org.apache.kafka.common.security.auth.SecurityProtocol;
import org.apache.kafka.server.authorizer.AuthorizableRequestContext;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Simple request context used in tests and benchmarks
 *
 * @param listenerName  Name of the listener
 * @param principal     Principal of the request
 * @param clientAddress Address of the client
 * @param requestType   Type (API key) of the request
 */
public record TestRequestContext(String listenerName, KafkaPrincipal principal, InetAddress clientAddress, int requestType) implements AuthorizableRequestContext {
    private static final InetAddress LOCALHOST;

    static {
        try {
            LOCALHOST = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Creates a request context for a Fetch request from localhost
     *
     * @param listenerName  Name of the listener
     * @param principal     Principal in the {@code <type>:<name>} format
     *
     * @return  The request context
     */
    public static TestRequestContext of(String listenerName, String principal) {
        int separator = principal.indexOf(':');
        return new TestRequestContext(listenerName,
                new KafkaPrincipal(principal.substring(0, separator), principal.substring(separator + 1)),
                LOCALHOST,
                ApiKeys.FETCH.id);
    }

    @Override
    public SecurityProtocol securityProtocol() {
        return SecurityProtocol.SASL_SSL;
    }

    @Override
    public int requestVersion() {
        return 0;
    }

    @Override
    public String clientId() {
        return "test-client";
    }

    @Override
    public int correlationId() {
        return 1;
    }
}
