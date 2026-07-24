package com.jobseekercopilot.documentgenerationgateway.config;

import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                writeAuthenticationError(response))
                        .accessDeniedHandler((request, response, exception) ->
                                writeAccessDeniedError(response)))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/api/v1/document-generation/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> {
                        })
                        .authenticationEntryPoint((request, response, exception) ->
                                writeAuthenticationError(response)))
                .build();
    }

    @Bean
    JwtDecoder documentGenerationJwtDecoder(
            @Value("${document-generation.security.jwk-set-uri}") String jwkSetUri,
            @Value("${document-generation.security.issuer}") String issuer,
            @Value("${document-generation.security.audience}") String audience) {
        validateConfiguration(jwkSetUri, issuer, audience);
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                requiredAudience(audience),
                requiredAccessToken()));
        return decoder;
    }

    private static OAuth2TokenValidator<Jwt> requiredAudience(String audience) {
        return token -> token.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : invalidToken();
    }

    private static OAuth2TokenValidator<Jwt> requiredAccessToken() {
        return token -> token.getSubject() != null
                        && !token.getSubject().isBlank()
                        && "access".equals(token.getClaimAsString("token_type"))
                ? OAuth2TokenValidatorResult.success()
                : invalidToken();
    }

    private static OAuth2TokenValidatorResult invalidToken() {
        return OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", "Access token validation failed.", null));
    }

    static void validateConfiguration(String jwkSetUri, String issuer, String audience) {
        try {
            URI uri = URI.create(jwkSetUri);
            if (!List.of("http", "https").contains(uri.getScheme())
                    || !uri.isAbsolute()
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getFragment() != null
                    || issuer == null
                    || issuer.isBlank()
                    || audience == null
                    || audience.isBlank()) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "Document Generation JWT verification configuration is invalid");
        }
    }

    private static void writeAuthenticationError(HttpServletResponse response)
            throws java.io.IOException {
        writeError(
                response,
                HttpServletResponse.SC_UNAUTHORIZED,
                "AUTHENTICATION_REQUIRED",
                "Valid authentication is required.");
    }

    private static void writeAccessDeniedError(HttpServletResponse response)
            throws java.io.IOException {
        writeError(
                response,
                HttpServletResponse.SC_FORBIDDEN,
                "ACCESS_DENIED",
                "Access is denied.");
    }

    private static void writeError(
            HttpServletResponse response,
            int status,
            String code,
            String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
                "{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
