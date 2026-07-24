package com.jobseekercopilot.documentgenerationgateway;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

final class TestJwksServer implements AutoCloseable {

    static final String ISSUER = "job-seeker-copilot-authentication";
    static final String AUDIENCE = "job-seeker-copilot-services";
    private static final String ACTIVE_KEY_ID = "test-active-key";

    private final KeyPair active = generateKeyPair();
    private final KeyPair different = generateKeyPair();
    private HttpServer server;

    synchronized String jwkSetUri() {
        if (server == null) {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.createContext("/.well-known/jwks.json", exchange -> {
                    byte[] body = jwks().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.getResponseHeaders().set(
                            "Cache-Control",
                            "public, max-age=300, must-revalidate");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
                server.start();
            } catch (IOException exception) {
                throw new IllegalStateException("Could not start test JWKS server", exception);
            }
        }
        return "http://127.0.0.1:"
                + server.getAddress().getPort()
                + "/.well-known/jwks.json";
    }

    String validToken(String subject) {
        return token(
                active,
                ACTIVE_KEY_ID,
                JWSAlgorithm.RS256,
                ISSUER,
                AUDIENCE,
                subject,
                Instant.now().plusSeconds(300),
                "access");
    }

    String expiredToken(String subject) {
        return token(
                active,
                ACTIVE_KEY_ID,
                JWSAlgorithm.RS256,
                ISSUER,
                AUDIENCE,
                subject,
                Instant.now().minusSeconds(60),
                "access");
    }

    String forgedKnownKeyToken(String subject) {
        return token(
                different,
                ACTIVE_KEY_ID,
                JWSAlgorithm.RS256,
                ISSUER,
                AUDIENCE,
                subject,
                Instant.now().plusSeconds(300),
                "access");
    }

    String wrongIssuerToken(String subject) {
        return token(
                active,
                ACTIVE_KEY_ID,
                JWSAlgorithm.RS256,
                "wrong-issuer",
                AUDIENCE,
                subject,
                Instant.now().plusSeconds(300),
                "access");
    }

    String wrongAudienceToken(String subject) {
        return token(
                active,
                ACTIVE_KEY_ID,
                JWSAlgorithm.RS256,
                ISSUER,
                "wrong-audience",
                subject,
                Instant.now().plusSeconds(300),
                "access");
    }

    String refreshTokenType(String subject) {
        return token(
                active,
                ACTIVE_KEY_ID,
                JWSAlgorithm.RS256,
                ISSUER,
                AUDIENCE,
                subject,
                Instant.now().plusSeconds(300),
                "refresh");
    }

    String missingSubjectToken() {
        return token(
                active,
                ACTIVE_KEY_ID,
                JWSAlgorithm.RS256,
                ISSUER,
                AUDIENCE,
                null,
                Instant.now().plusSeconds(300),
                "access");
    }

    private String jwks() {
        RSAKey publicKey = new RSAKey.Builder((RSAPublicKey) active.getPublic())
                .keyID(ACTIVE_KEY_ID)
                .algorithm(JWSAlgorithm.RS256)
                .keyUse(KeyUse.SIGNATURE)
                .build();
        return "{\"keys\":[" + publicKey.toJSONString() + "]}";
    }

    private static String token(
            KeyPair pair,
            String keyId,
            JWSAlgorithm algorithm,
            String issuer,
            String audience,
            String subject,
            Instant expiresAt,
            String tokenType) {
        try {
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .audience(audience)
                    .subject(subject)
                    .jwtID(UUID.randomUUID().toString())
                    .issueTime(Date.from(now.minusSeconds(5)))
                    .expirationTime(Date.from(expiresAt))
                    .claim("sid", "test-session")
                    .claim("token_type", tokenType)
                    .build();
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(algorithm).keyID(keyId).build(),
                    claims);
            jwt.sign(new RSASSASigner((RSAPrivateKey) pair.getPrivate()));
            return jwt.serialize();
        } catch (JOSEException exception) {
            throw new IllegalStateException("Could not create test JWT", exception);
        }
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("RSA is unavailable", exception);
        }
    }

    @Override
    public synchronized void close() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }
}
