package com.jobseekercopilot.documentgenerationgateway.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class CurrentAccessTokenSupplierTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void suppliesOnlyTheValidatedRequestAccessToken() {
        Jwt jwt = new Jwt(
                "validated-token",
                Instant.now(),
                Instant.now().plusSeconds(300),
                Map.of("alg", "RS256"),
                Map.of("sub", "alice", "token_type", "access"));
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, java.util.List.of(), "alice"));

        assertEquals("validated-token", new CurrentAccessTokenSupplier().get());
    }

    @Test
    void failsClosedOutsideAnAuthenticatedJwtRequest() {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new CurrentAccessTokenSupplier().get());

        assertEquals("Validated access token is required.", exception.getMessage());
    }
}
