package ws.sanasol.dualauth.server;

import ws.sanasol.dualauth.agent.DualAuthConfig;
import ws.sanasol.dualauth.context.DualAuthContext;
import ws.sanasol.dualauth.context.DualAuthHelper;
import ws.sanasol.dualauth.embedded.EmbeddedJwkVerifier;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Manages dual token sets for multi-issuer authentication:
 * 
 * 1. **Official tokens**: Captured from /auth login (hytale.com)
 * 2. **F2P tokens**: Auto-fetched from F2P endpoint (sanasol.ws)
 * 3. **Omni-Auth tokens**: Dynamically generated using embedded client keys
 * 4. **Federated tokens**: Cached for discovered third-party issuers
 * 
 * Token Selection Priority (for non-official issuers):
 * 1. Omni-Auth dynamic generation (if context has embedded JWK)
 * 2. F2P cached tokens (promiscuous fallback for development)
 * 3. Federated cache (for explicitly registered issuers)
 */
public class DualServerTokenManager {
    private static final Logger LOGGER = Logger.getLogger("DualAuthAgent");

    // Official slots (captured from /auth login)
    private static volatile String officialSessionToken = null;
    private static volatile String officialIdentityToken = null;

    // F2P slots (auto-fetched from endpoint)
    private static volatile String f2pSessionToken = null;
    private static volatile String f2pIdentityToken = null;

    // Dynamic caches for federated issuers
    private static final ConcurrentHashMap<String, String> issuerSessionCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> issuerIdentityCache = new ConcurrentHashMap<>();

    // New federated cache for public issuers (by issuer)
    private static final ConcurrentHashMap<String, FederatedIssuerTokens> federatedIssuerCache = new ConcurrentHashMap<>();

    public static class FederatedIssuerTokens {
        private final String identityToken;
        private final String sessionToken;
        private final long timestamp;
        private final long ttl;

        public FederatedIssuerTokens(String identityToken, String sessionToken, long ttl) {
            this.identityToken = identityToken;
            this.sessionToken = sessionToken;
            this.timestamp = System.currentTimeMillis();
            this.ttl = ttl;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() - timestamp > ttl;
        }

        public String getIdentityToken() {
            return identityToken;
        }

        public String getSessionToken() {
            return sessionToken;
        }
    }

    // Cache for issuer detection results
    public static class IssuerDetectionResult {
        private final boolean isPublic;
        private final String jwksUrl;
        private final long timestamp;
        private final long ttl;
        private final Exception lastError;

        public IssuerDetectionResult(boolean isPublic, String jwksUrl, long ttl) {
            this.isPublic = isPublic;
            this.jwksUrl = jwksUrl;
            this.timestamp = System.currentTimeMillis();
            this.ttl = ttl;
            this.lastError = null;
        }

        public IssuerDetectionResult(Exception error, long ttl) {
            this.isPublic = false;
            this.jwksUrl = null;
            this.timestamp = System.currentTimeMillis();
            this.ttl = ttl;
            this.lastError = error;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() - timestamp > ttl;
        }

        public boolean isPublic() {
            return isPublic;
        }

        public String getJwksUrl() {
            return jwksUrl;
        }

        public Exception getLastError() {
            return lastError;
        }
    }

    private static final ConcurrentHashMap<String, IssuerDetectionResult> issuerDetectionCache = new ConcurrentHashMap<>();

    public static ConcurrentHashMap<String, IssuerDetectionResult> getIssuerDetectionCache() {
        return issuerDetectionCache;
    }

    public static ConcurrentHashMap<String, FederatedIssuerTokens> getFederatedIssuerCache() {
        return federatedIssuerCache;
    }

    public static synchronized void setOfficialTokens(String sessionToken, String identityToken) {
        officialSessionToken = sessionToken;
        officialIdentityToken = identityToken;
        LOGGER.info("Official tokens captured and stored");
    }

    public static synchronized void setF2PTokens(String sessionToken, String identityToken) {
        f2pSessionToken = sessionToken;
        f2pIdentityToken = identityToken;
        LOGGER.info("F2P tokens stored in manager");
    }

    /**
     * Intercepts GameSessionResponse to capture tokens dynamically.
     */
    public static void captureNativeSession(Object response) {
        if (response == null)
            return;
        try {
            // Use reflection to find token fields (GameSessionResponse has sessionToken and
            // identityToken)
            String sToken = extractField(response, "sessionToken");
            String iToken = extractField(response, "identityToken");

            if (sToken == null && iToken == null) {
                LOGGER.warning("captureNativeSession: No tokens found in response");
                return;
            }

            String issuer = iToken != null ? DualAuthHelper.extractIssuerFromToken(iToken) : null;
            LOGGER.info("Capturing native session for issuer: " + issuer);

            if (DualAuthHelper.isOfficialIssuer(issuer)) {
                setOfficialTokens(sToken, iToken);
            } else {
                // Store as F2P (covers sanasol.ws and similar)
                setF2PTokens(sToken, iToken);

                // Also cache by issuer for federated lookup
                if (issuer != null) {
                    if (sToken != null)
                        issuerSessionCache.put(issuer, sToken);
                    if (iToken != null)
                        issuerIdentityCache.put(issuer, iToken);
                }
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to capture native session tokens: " + e.getMessage());
        }
    }

    private static String extractField(Object obj, String fieldName) {
        try {
            Field f = obj.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            return (String) f.get(obj);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Attempts to capture tokens from a ServerAuthManager instance by inspecting
     * its fields.
     */
    public static void captureFromInstance(Object serverAuthManager) {
        if (serverAuthManager == null)
            return;
        try {
            // ServerAuthManager usually stores the session in an AtomicReference named
            // 'gameSession'
            Field f = serverAuthManager.getClass().getDeclaredField("gameSession");
            f.setAccessible(true);
            Object atomicRef = f.get(serverAuthManager);
            if (atomicRef instanceof java.util.concurrent.atomic.AtomicReference) {
                Object response = ((java.util.concurrent.atomic.AtomicReference<?>) atomicRef).get();
                if (response != null) {
                    captureNativeSession(response);
                }
            }
        } catch (NoSuchFieldException e) {
            // Try alternative field names
            try {
                for (Field f : serverAuthManager.getClass().getDeclaredFields()) {
                    if (f.getType().getName().contains("AtomicReference")) {
                        f.setAccessible(true);
                        Object atomicRef = f.get(serverAuthManager);
                        if (atomicRef instanceof java.util.concurrent.atomic.AtomicReference) {
                            Object response = ((java.util.concurrent.atomic.AtomicReference<?>) atomicRef).get();
                            if (response != null) {
                                captureNativeSession(response);
                                return;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        } catch (Exception ignored) {
            // Field might not exist or be different in this version
        }
    }

    /**
     * Gets the session token for the given issuer.
     * Prioritizes: Official → Omni-Auth Dynamic → F2P → Federated Cache
     */
    public static String getSessionTokenForIssuer(String issuer) {
        if (issuer == null)
            issuer = DualAuthContext.getIssuer();

        // Safeguard: Use F2P or Official if no issuer context
        if (issuer == null) {
            return f2pSessionToken != null ? f2pSessionToken : officialSessionToken;
        }

        // 1. Official Check
        if (DualAuthHelper.isOfficialIssuer(issuer)) {
            return officialSessionToken;
        }

        // 2. Omni-Auth Dynamic Generation
        // If we have an embedded JWK, generate a signed token on-the-fly
        if (DualAuthContext.isOmni()) {
            String dynamicToken = EmbeddedJwkVerifier.createDynamicSessionToken(issuer);
            if (dynamicToken != null) {
                LOGGER.info("Generated dynamic session token for Omni-Auth issuer: " + issuer);
                return dynamicToken;
            }
        }

        // 3. F2P Promiscuous Fallback
        // If we have F2P tokens and it's not official, use F2P tokens.
        // This solves the localhost vs sanasol.ws development mismatch.
        if (f2pSessionToken != null) {
            LOGGER.fine("Returning F2P session token for non-official issuer: " + issuer);
            return f2pSessionToken;
        }

        // 4. Dynamic Cache (Federated)
        return issuerSessionCache.get(issuer);
    }


    /**
     * Starts federated token fetch in background without blocking the current
     * thread.
     * Results will be cached for future requests.
     */
    private static void startBackgroundFederatedFetch(String issuer) {
        // Check if a fetch is already in progress (placeholder with null tokens)
        FederatedIssuerTokens existing = federatedIssuerCache.get(issuer);
        if (existing != null) {
            // If it's a valid cached token (not a placeholder), don't initiate fetch
            if (existing.getIdentityToken() != null || existing.getSessionToken() != null) {
                return; // Valid tokens are already cached
            }
            // Otherwise, it's a placeholder indicating fetch in progress, so we don't start another
            return;
        }

        // Mark as being fetched to avoid duplicates
        federatedIssuerCache.put(issuer, new FederatedIssuerTokens(null, null, 30000)); // 30 second placeholder TTL

        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                if (Boolean.getBoolean("dualauth.debug")) {
                    LOGGER.info("Background federated fetch started for: " + issuer);
                }

                // Avoid blocking critical threads blindly, though this is likely called from
                // serialize() which is sync.
                // In refined future versions, this should be pre-fetched or async.
                FederatedIssuerTokens freshTokens = DualServerIdentity.fetchFederatedTokensFromIssuer(issuer);
                if (freshTokens != null) {
                    // 3. Cache it
                    federatedIssuerCache.put(issuer, freshTokens);
                    if (Boolean.getBoolean("dualauth.debug")) {
                        LOGGER.info("Cached federated tokens for issuer: " + issuer);
                    }
                } else {
                    // Remove the placeholder if fetch failed
                    FederatedIssuerTokens current = federatedIssuerCache.get(issuer);
                    if (current != null && current.getIdentityToken() == null && current.getSessionToken() == null) {
                        federatedIssuerCache.remove(issuer); // Remove placeholder only if still a placeholder
                    }
                    if (Boolean.getBoolean("dualauth.debug")) {
                        LOGGER.warning("Failed to fetch federated tokens for issuer: " + issuer);
                    }
                }
            } catch (Exception e) {
                // Remove the placeholder if fetch failed
                FederatedIssuerTokens current = federatedIssuerCache.get(issuer);
                if (current != null && current.getIdentityToken() == null && current.getSessionToken() == null) {
                    federatedIssuerCache.remove(issuer); // Remove placeholder only if still a placeholder
                }
                if (Boolean.getBoolean("dualauth.debug")) {
                    LOGGER.warning("Exception in background federated fetch for issuer: " + issuer + " -> " + e.getMessage());
                }
            }
        });
    }

    public static void cleanupExpiredFederatedCache() {
        java.util.Iterator<java.util.Map.Entry<String, FederatedIssuerTokens>> iterator = federatedIssuerCache
                .entrySet().iterator();
        while (iterator.hasNext()) {
            java.util.Map.Entry<String, FederatedIssuerTokens> entry = iterator.next();
            if (entry.getValue().isExpired()) {
                iterator.remove();
                if (Boolean.getBoolean("dualauth.debug")) {
                    LOGGER.info("Cleaned up expired cache for issuer: " + entry.getKey());
                }
            }
        }
    }

    public static void cleanupExpiredDetectionCache() {
        java.util.Iterator<java.util.Map.Entry<String, IssuerDetectionResult>> iterator = issuerDetectionCache
                .entrySet().iterator();

        int cleaned = 0;
        while (iterator.hasNext()) {
            java.util.Map.Entry<String, IssuerDetectionResult> entry = iterator.next();
            if (entry.getValue().isExpired()) {
                iterator.remove();
                cleaned++;
            }
        }

        if (Boolean.getBoolean("dualauth.debug") && cleaned > 0) {
            LOGGER.info("Cleaned up " + cleaned + " expired issuer detection entries");
        }
    }

    public static void logDetectionStats() {
        int total = issuerDetectionCache.size();
        int publicCount = 0;
        int errorCount = 0;

        for (IssuerDetectionResult result : issuerDetectionCache.values()) {
            if (result.isPublic())
                publicCount++;
            if (result.getLastError() != null)
                errorCount++;
        }

        LOGGER.info("Issuer Detection Stats:");
        LOGGER.info("  Total cached: " + total);
        LOGGER.info("  Public issuers: " + publicCount);
        LOGGER.info("  Error entries: " + errorCount);
    }

    /**
     * Checks if the given string is an IP address (IPv4 or IPv6).
     */
    private static boolean isIpAddress(String address) {
        if (address == null || address.isEmpty())
            return false;

        // Remove protocol if present
        String host = address;
        if (address.startsWith("http://") || address.startsWith("https://")) {
            host = address.substring(address.indexOf("://") + 3);
            int slashIndex = host.indexOf('/');
            if (slashIndex > 0) {
                host = host.substring(0, slashIndex);
            }
            int colonIndex = host.indexOf(':');
            if (colonIndex > 0) {
                host = host.substring(0, colonIndex);
            }
        }

        // IPv4 check
        String[] ipv4Parts = host.split("\\.");
        if (ipv4Parts.length == 4) {
            try {
                for (String part : ipv4Parts) {
                    int num = Integer.parseInt(part);
                    if (num < 0 || num > 255)
                        return false;
                }
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        // IPv6 check (basic)
        return host.contains(":");
    }

    private static final java.util.concurrent.ScheduledExecutorService cacheCleanupExecutor = java.util.concurrent.Executors
            .newSingleThreadScheduledExecutor();

    static {
        // Clean cache every 30 minutes
        cacheCleanupExecutor.scheduleAtFixedRate(() -> {
            cleanupExpiredFederatedCache();
            cleanupExpiredDetectionCache();
            if (Boolean.getBoolean("dualauth.debug"))
                logDetectionStats();
        }, 30, 30, java.util.concurrent.TimeUnit.MINUTES);
    }

    /**
     * Gets the identity token for the given issuer.
     * Prioritizes: Official → Omni-Auth Dynamic → Federated Cache → F2P Fallback
     */
    public static String getIdentityTokenForIssuer(String issuer, String playerUuid) {
        return getIdentityTokenForIssuer(issuer, playerUuid, null);
    }

    /**
     * Gets the identity token for the given issuer with optional custom issuer
     * override.
     * Prioritizes: Official → Omni-Auth Dynamic → Federated Exact Match → Custom
     * Generation → F2P Fallback
     * If customIssuer is provided, it will be used instead of the original issuer
     * for token generation.
     */
    public static String getIdentityTokenForIssuer(String issuer, String playerUuid, String customIssuer) {
        String effectiveIssuer = (customIssuer != null) ? customIssuer : issuer;

        if (effectiveIssuer == null)
            effectiveIssuer = DualAuthContext.getIssuer();
        if (playerUuid == null)
            playerUuid = DualAuthContext.getPlayerUuid();

        // Safeguard: Use F2P or Official if no issuer context
        if (effectiveIssuer == null) {
            return f2pIdentityToken != null ? f2pIdentityToken : officialIdentityToken;
        }

        // 1. Official issuers use official tokens
        if (DualAuthHelper.isOfficialIssuerStrict(effectiveIssuer)) {
            return officialIdentityToken;
        }

        // 2. Omni-Auth uses per-player cache (or dynamic generation)
        if (DualAuthContext.isOmni()) {
            return DualServerIdentity.createDynamicIdentityToken(effectiveIssuer, playerUuid);
        }

        // 3. CRITICAL: FIRST try to get federated token for the EXACT issuer from the
        // client
        // This handles new domains like sessions.sanasol.ws/server/auto-auth
        DualServerTokenManager.FederatedIssuerTokens fedTokens = DualServerIdentity.fetchFederatedTokensFromIssuer(effectiveIssuer);
        if (fedTokens != null && fedTokens.getIdentityToken() != null) {
            if (Boolean.getBoolean("dualauth.debug")) {
                LOGGER.info("Got federated identity token for exact issuer: " + effectiveIssuer);
            }
            return fedTokens.getIdentityToken();
        }

        // 5. Custom issuer generation (NEW: Force generation for customIssuer)
        if (customIssuer != null) {
            // Force generation with the custom issuer from the client
            if (Boolean.getBoolean("dualauth.debug")) {
                LOGGER.info("DEBUG: Forcing token generation with custom issuer: " + customIssuer
                        + " (original: " + issuer + ")");
            }
            return DualServerIdentity.createDynamicIdentityToken(customIssuer, playerUuid);
        }

        // 6. Public issuers fallback (for cases where other checks didn't trigger)
        if (DualAuthHelper.isPublicIssuer(effectiveIssuer)) {
            DualServerTokenManager.FederatedIssuerTokens publicFedTokens = DualServerIdentity.fetchFederatedTokensFromIssuer(effectiveIssuer);
            if (publicFedTokens != null && publicFedTokens.getIdentityToken() != null)
                return publicFedTokens.getIdentityToken();
        }

        // 7. No base domain fallback - we avoid adapting tokens between subdomains to
        // prevent signature issues
        // Each issuer should get its own properly signed token from its own endpoint

        // Only return null if we honestly don't have ANY token to give
        if (Boolean.getBoolean("dualauth.debug")) {
            LOGGER.warning("partial failure: requested identity for " + effectiveIssuer
                    + " but no F2P token available");
        }
        return null;
    }

    /**
     * Ensures F2P tokens are available. Called during warmup phase.
     */
    public static void ensureF2PTokens() {
        if (f2pSessionToken == null) {
            LOGGER.info("F2P tokens not available, triggering refresh...");
            DualServerIdentity.refreshF2PTokens();
        }
    }

    /**
     * Check if tokens are available for a given issuer.
     */
    public static boolean hasTokensForIssuer(String issuer) {
        if (issuer == null)
            return false;

        if (DualAuthHelper.isOfficialIssuer(issuer)) {
            return officialIdentityToken != null;
        }

        if (DualAuthContext.isOmni()) {
            return true;
        }

        if (federatedIssuerCache.containsKey(issuer)) {
            return true;
        }

        if (f2pIdentityToken != null) {
            return true; // F2P fallback available
        }

        return issuerIdentityCache.containsKey(issuer);
    }

    /**
     * Clears all cached tokens. Used for testing/debugging.
     */
    public static synchronized void clearAll() {
        officialSessionToken = null;
        officialIdentityToken = null;
        f2pSessionToken = null;
        f2pIdentityToken = null;
        issuerSessionCache.clear();
        issuerIdentityCache.clear();
        LOGGER.info("All token caches cleared");
    }
}
