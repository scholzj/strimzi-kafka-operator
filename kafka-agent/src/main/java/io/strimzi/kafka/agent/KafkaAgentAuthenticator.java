/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafka.agent;

import org.jose4j.http.Get;
import org.jose4j.jwk.HttpsJwks;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.MalformedClaimException;
import org.jose4j.jwt.consumer.InvalidJwtException;
import org.jose4j.jwt.consumer.JwtConsumer;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;
import org.jose4j.keys.resolvers.HttpsJwksVerificationKeyResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Validates Kubernetes Service Account tokens (JWTs) presented to the agent in
 * the {@code Authorization: Bearer ...} header. The token is verified against
 * the Kubernetes API server's JWKS endpoint, and the {@code sub} claim is
 * checked against an explicit allowlist of permitted subjects.
 */
public class KafkaAgentAuthenticator {
    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaAgentAuthenticator.class);
    private static final String BEARER_PREFIX = "Bearer ";
    private static final long JWKS_REFRESH_SECONDS = 300;

    private final JwtConsumer jwtConsumer;
    private final Set<String> allowedSubjects;

    /**
     * Production constructor — builds a {@link JwtConsumer} that fetches keys
     * from the supplied JWKS endpoint over HTTPS, trusting the certificates in
     * {@code jwksCaPath}.
     *
     * @param issuer            Expected {@code iss} claim value
     * @param audience          Expected {@code aud} claim value
     * @param jwksUri           HTTPS URI of the JWKS endpoint
     * @param jwksCaPath        Filesystem path of the PEM bundle to trust when calling the JWKS endpoint
     * @param allowedSubjects   Allowlist of {@code sub} claim values that are permitted
     */
    public KafkaAgentAuthenticator(String issuer, String audience, String jwksUri, String jwksCaPath, Collection<String> allowedSubjects) {
        this(buildJwtConsumer(issuer, audience, jwksUri, jwksCaPath), allowedSubjects);
    }

    /* test */ KafkaAgentAuthenticator(JwtConsumer jwtConsumer, Collection<String> allowedSubjects) {
        this.jwtConsumer = jwtConsumer;
        this.allowedSubjects = Collections.unmodifiableSet(new LinkedHashSet<>(allowedSubjects));
    }

    private static JwtConsumer buildJwtConsumer(String issuer, String audience, String jwksUri, String jwksCaPath) {
        HttpsJwks httpsJwks = new HttpsJwks(jwksUri);
        httpsJwks.setDefaultCacheDuration(JWKS_REFRESH_SECONDS);
        httpsJwks.setSimpleHttpGet(buildHttpGet(jwksCaPath));

        return new JwtConsumerBuilder()
                .setRequireExpirationTime()
                .setRequireSubject()
                .setExpectedIssuer(issuer)
                .setExpectedAudience(audience)
                .setVerificationKeyResolver(new HttpsJwksVerificationKeyResolver(httpsJwks))
                .build();
    }

    private static Get buildHttpGet(String jwksCaPath) {
        try {
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            int i = 0;
            try (InputStream is = Files.newInputStream(Paths.get(jwksCaPath))) {
                for (Certificate cert : cf.generateCertificates(is)) {
                    trustStore.setCertificateEntry("jwks-ca-" + i++, cert);
                }
            }

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, tmf.getTrustManagers(), null);

            Get get = new Get();
            get.setSslSocketFactory(sslContext.getSocketFactory());
            return get;
        } catch (GeneralSecurityException | IOException e) {
            throw new RuntimeException("Failed to configure HTTPS client for JWKS endpoint", e);
        }
    }

    /**
     * Validate the value of an HTTP {@code Authorization} header and return the
     * authenticated subject. Throws if the header is missing, malformed, the
     * token fails JWT validation, or the {@code sub} claim is not in the
     * allowlist.
     *
     * @param authorizationHeader   Raw value of the Authorization header (may be null)
     * @return                      Validated subject ({@code sub} claim)
     * @throws AuthenticationException  When authentication fails for any reason
     */
    public String authenticate(String authorizationHeader) throws AuthenticationException {
        if (authorizationHeader == null || authorizationHeader.isEmpty()) {
            throw new AuthenticationException("Missing Authorization header");
        }
        if (!authorizationHeader.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            throw new AuthenticationException("Authorization header is not a Bearer token");
        }

        String token = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            throw new AuthenticationException("Empty Bearer token");
        }

        JwtClaims claims;
        try {
            claims = jwtConsumer.processToClaims(token);
        } catch (InvalidJwtException e) {
            LOGGER.debug("Token validation failed", e);
            throw new AuthenticationException("Token validation failed");
        }

        String subject;
        try {
            subject = claims.getSubject();
        } catch (MalformedClaimException e) {
            throw new AuthenticationException("Token validation failed");
        }
        if (subject == null || !allowedSubjects.contains(subject)) {
            throw new AuthenticationException("Subject not allowed");
        }
        return subject;
    }

    /**
     * Thrown when authentication fails. The message is intentionally generic to
     * avoid leaking validation details to clients.
     */
    public static class AuthenticationException extends Exception {
        /**
         * Constructor
         *
         * @param message   Failure reason (logged only — not returned to the client verbatim)
         */
        public AuthenticationException(String message) {
            super(message);
        }
    }
}
