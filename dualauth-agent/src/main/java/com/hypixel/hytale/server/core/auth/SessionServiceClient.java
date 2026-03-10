package com.hypixel.hytale.server.core.auth;

import java.util.concurrent.CompletableFuture;

/**
 * COMPILE-TIME STUB ONLY — excluded from final JAR.
 * Matches decompiled SessionServiceClient signatures for subclass compilation.
 */
public class SessionServiceClient {

    public SessionServiceClient(String sessionServiceUrl) {
        throw new UnsupportedOperationException("Stub");
    }

    public CompletableFuture<String> requestAuthorizationGrantAsync(
            String identityToken,
            String serverAudience,
            String bearerToken) {
        throw new UnsupportedOperationException("Stub");
    }

    public CompletableFuture<String> exchangeAuthGrantForTokenAsync(
            String authorizationGrant,
            String x509Fingerprint,
            String bearerToken) {
        throw new UnsupportedOperationException("Stub");
    }
}
