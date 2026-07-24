package com.jobseekercopilot.documentgenerationgateway.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SecurityConfigTest {

    @Test
    void jwtVerificationConfigurationFailsClosed() {
        SecurityConfig.validateConfiguration(
                "https://auth.example.test/.well-known/jwks.json",
                "issuer",
                "audience");

        assertThatThrownBy(() ->
                SecurityConfig.validateConfiguration(
                        "file:///tmp/jwks.json",
                        "issuer",
                        "audience"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Document Generation JWT verification configuration is invalid");
        assertThatThrownBy(() ->
                SecurityConfig.validateConfiguration(
                        "https://user@example.test/jwks",
                        "issuer",
                        "audience"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() ->
                SecurityConfig.validateConfiguration(
                        "https://auth.example.test/jwks",
                        " ",
                        "audience"))
                .isInstanceOf(IllegalStateException.class);
    }
}
