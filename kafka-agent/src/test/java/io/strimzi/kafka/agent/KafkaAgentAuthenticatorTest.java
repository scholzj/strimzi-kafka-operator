/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafka.agent;

import org.jose4j.jwk.RsaJsonWebKey;
import org.jose4j.jwk.RsaJwkGenerator;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.NumericDate;
import org.jose4j.jwt.consumer.JwtConsumer;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;
import org.jose4j.lang.JoseException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class KafkaAgentAuthenticatorTest {
    private static final String ISSUER = "https://kubernetes.default.svc.cluster.local";
    private static final String AUDIENCE = "strimzi.io";
    private static final String ALLOWED_SUBJECT = "system:serviceaccount:myproject:strimzi-cluster-operator";
    private static final String DISALLOWED_SUBJECT = "system:serviceaccount:myproject:somebody-else";

    private static RsaJsonWebKey signingKey;
    private static JwtConsumer jwtConsumer;

    @BeforeAll
    public static void setUp() throws JoseException {
        signingKey = RsaJwkGenerator.generateJwk(2048);
        signingKey.setKeyId("test-key");

        jwtConsumer = new JwtConsumerBuilder()
                .setRequireExpirationTime()
                .setRequireSubject()
                .setExpectedIssuer(ISSUER)
                .setExpectedAudience(AUDIENCE)
                .setVerificationKey(signingKey.getPublicKey())
                .build();
    }

    private KafkaAgentAuthenticator authenticator() {
        return new KafkaAgentAuthenticator(jwtConsumer, List.of(ALLOWED_SUBJECT));
    }

    private String issueToken(String issuer, String audience, String subject, NumericDate expiration) throws JoseException {
        JwtClaims claims = new JwtClaims();
        claims.setIssuer(issuer);
        claims.setAudience(audience);
        claims.setSubject(subject);
        claims.setExpirationTime(expiration);
        claims.setIssuedAtToNow();
        claims.setGeneratedJwtId();

        JsonWebSignature jws = new JsonWebSignature();
        jws.setPayload(claims.toJson());
        jws.setKey(signingKey.getPrivateKey());
        jws.setKeyIdHeaderValue(signingKey.getKeyId());
        jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.RSA_USING_SHA256);
        return jws.getCompactSerialization();
    }

    private String validToken() throws JoseException {
        return issueToken(ISSUER, AUDIENCE, ALLOWED_SUBJECT, NumericDate.fromSeconds(NumericDate.now().getValue() + 600));
    }

    @Test
    public void validTokenReturnsSubject() throws Exception {
        String subject = authenticator().authenticate("Bearer " + validToken());
        assertThat(subject, is(ALLOWED_SUBJECT));
    }

    @Test
    public void bearerPrefixIsCaseInsensitive() throws Exception {
        String subject = authenticator().authenticate("bearer " + validToken());
        assertThat(subject, is(ALLOWED_SUBJECT));
    }

    @Test
    public void nullHeaderFails() {
        assertThrows(KafkaAgentAuthenticator.AuthenticationException.class,
                () -> authenticator().authenticate(null));
    }

    @Test
    public void emptyHeaderFails() {
        assertThrows(KafkaAgentAuthenticator.AuthenticationException.class,
                () -> authenticator().authenticate(""));
    }

    @Test
    public void nonBearerSchemeFails() {
        assertThrows(KafkaAgentAuthenticator.AuthenticationException.class,
                () -> authenticator().authenticate("Basic dXNlcjpwYXNz"));
    }

    @Test
    public void emptyBearerValueFails() {
        assertThrows(KafkaAgentAuthenticator.AuthenticationException.class,
                () -> authenticator().authenticate("Bearer    "));
    }

    @Test
    public void disallowedSubjectFails() throws Exception {
        String token = issueToken(ISSUER, AUDIENCE, DISALLOWED_SUBJECT, NumericDate.fromSeconds(NumericDate.now().getValue() + 600));
        assertThrows(KafkaAgentAuthenticator.AuthenticationException.class,
                () -> authenticator().authenticate("Bearer " + token));
    }

    @Test
    public void wrongIssuerFails() throws Exception {
        String token = issueToken("https://attacker.example.com", AUDIENCE, ALLOWED_SUBJECT, NumericDate.fromSeconds(NumericDate.now().getValue() + 600));
        assertThrows(KafkaAgentAuthenticator.AuthenticationException.class,
                () -> authenticator().authenticate("Bearer " + token));
    }

    @Test
    public void wrongAudienceFails() throws Exception {
        String token = issueToken(ISSUER, "some-other-audience", ALLOWED_SUBJECT, NumericDate.fromSeconds(NumericDate.now().getValue() + 600));
        assertThrows(KafkaAgentAuthenticator.AuthenticationException.class,
                () -> authenticator().authenticate("Bearer " + token));
    }

    @Test
    public void expiredTokenFails() throws Exception {
        String token = issueToken(ISSUER, AUDIENCE, ALLOWED_SUBJECT, NumericDate.fromSeconds(NumericDate.now().getValue() - 60));
        assertThrows(KafkaAgentAuthenticator.AuthenticationException.class,
                () -> authenticator().authenticate("Bearer " + token));
    }

    @Test
    public void garbageTokenFails() {
        assertThrows(KafkaAgentAuthenticator.AuthenticationException.class,
                () -> authenticator().authenticate("Bearer not-a-jwt"));
    }

    @Test
    public void tokenSignedByDifferentKeyFails() throws Exception {
        RsaJsonWebKey otherKey = RsaJwkGenerator.generateJwk(2048);
        otherKey.setKeyId("other-key");

        JwtClaims claims = new JwtClaims();
        claims.setIssuer(ISSUER);
        claims.setAudience(AUDIENCE);
        claims.setSubject(ALLOWED_SUBJECT);
        claims.setExpirationTime(NumericDate.fromSeconds(NumericDate.now().getValue() + 600));
        claims.setIssuedAtToNow();

        JsonWebSignature jws = new JsonWebSignature();
        jws.setPayload(claims.toJson());
        jws.setKey(otherKey.getPrivateKey());
        jws.setKeyIdHeaderValue(otherKey.getKeyId());
        jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.RSA_USING_SHA256);
        String token = jws.getCompactSerialization();

        assertThrows(KafkaAgentAuthenticator.AuthenticationException.class,
                () -> authenticator().authenticate("Bearer " + token));
    }
}
