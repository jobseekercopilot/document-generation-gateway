package com.jobseekercopilot.documentgenerationgateway.security;

import java.util.function.Supplier;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public class CurrentAccessTokenSupplier implements Supplier<String> {

    @Override
    public String get() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication
                && authentication.isAuthenticated()) {
            return jwtAuthentication.getToken().getTokenValue();
        }
        throw new IllegalStateException("Validated access token is required.");
    }
}
