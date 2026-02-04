package com.hytale.dualauth.server;

import com.hytale.dualauth.agent.DualAuthConfig;
import com.hytale.dualauth.context.DualAuthContext;
import com.hytale.dualauth.context.DualAuthHelper;
import com.hytale.dualauth.embedded.EmbeddedJwkVerifier;

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

    public static synchronized void setOfficialTokens(String sessionToken, String identityToken) {
        officialSessionToken = sessionToken;
        officialIdentityToken = identityToken;
        LOGGER.info("[DualAuth] Official tokens captured and stored");
    }

    public static synchronized void setF2PTokens(String sessionToken, String identityToken) {
        f2pSessionToken = sessionToken;
        f2pIdentityToken = identityToken;
        LOGGER.info("[DualAuth] F2P tokens stored in manager");
    }

    /**
     * Intercepts GameSessionResponse to capture tokens dynamically.
     */
    public static void captureNativeSession(Object response) {
        if (response == null) return;
        try {
            // Use reflection to find token fields (GameSessionResponse has sessionToken and identityToken)
            String sToken = extractField(response, "sessionToken");
            String iToken = extractField(response, "identityToken");
            
            if (sToken == null && iToken == null) {
                LOGGER.warning("[DualAuth] captureNativeSession: No tokens found in response");
                return;
            }
            
            String issuer = iToken != null ? DualAuthHelper.extractIssuerFromToken(iToken) : null;
            LOGGER.info("[DualAuth] Capturing native session for issuer: " + issuer);
            
            if (DualAuthHelper.isOfficialIssuer(issuer)) {
                setOfficialTokens(sToken, iToken);
            } else {
                // Store as F2P (covers sanasol.ws and similar)
                setF2PTokens(sToken, iToken);
                
                // Also cache by issuer for federated lookup
                if (issuer != null) {
                    if (sToken != null) issuerSessionCache.put(issuer, sToken);
                    if (iToken != null) issuerIdentityCache.put(issuer, iToken);
                }
            }
        } catch (Exception e) {
            LOGGER.warning("[DualAuth] Failed to capture native session tokens: " + e.getMessage());
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
     * Attempts to capture tokens from a ServerAuthManager instance by inspecting its fields.
     */
    public static void captureFromInstance(Object serverAuthManager) {
        if (serverAuthManager == null) return;
        try {
            // ServerAuthManager usually stores the session in an AtomicReference named 'gameSession'
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
            } catch (Exception ignored) {}
        } catch (Exception ignored) {
            // Field might not exist or be different in this version
        }
    }

    /**
     * Gets the session token for the given issuer.
     * Prioritizes: Official → Omni-Auth Dynamic → F2P → Federated Cache
     */
    public static String getSessionTokenForIssuer(String issuer) {
        if (issuer == null) issuer = DualAuthContext.getIssuer();
        
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
                LOGGER.info("[DualAuth] Generated dynamic session token for Omni-Auth issuer: " + issuer);
                return dynamicToken;
            }
        }
        
        // 3. F2P Promiscuous Fallback
        // If we have F2P tokens and it's not official, use F2P tokens.
        // This solves the localhost vs sanasol.ws development mismatch.
        if (f2pSessionToken != null) {
            LOGGER.fine("[DualAuth] Returning F2P session token for non-official issuer: " + issuer);
            return f2pSessionToken;
        }
        
        // 4. Dynamic Cache (Federated)
        return issuerSessionCache.get(issuer);
    }

    /**
     * Gets the identity token for the given issuer.
     * Prioritizes: Official → Omni-Auth Dynamic → F2P → Federated Cache
     */
    public static String getIdentityTokenForIssuer(String issuer, String playerUuid) {
        if (issuer == null) issuer = DualAuthContext.getIssuer();
        if (playerUuid == null) playerUuid = DualAuthContext.getPlayerUuid();
        
        // Safeguard: Use F2P or Official if no issuer context
        if (issuer == null) {
            return f2pIdentityToken != null ? f2pIdentityToken : officialIdentityToken;
        }

        // 1. Official Check
        if (DualAuthHelper.isOfficialIssuer(issuer)) {
            return officialIdentityToken;
        }

        // 2. Omni-Auth or Federated Dynamic Generation (PRIORITY)
        // We ALWAYS generate a dynamic token for non-official issuers to ensure 
        // the 'audience' claim matches the player's UUID. This is required by Hytale client.
        String dynamicToken = DualServerIdentity.createDynamicIdentityToken(issuer, playerUuid);
        if (dynamicToken != null) {
            return dynamicToken;
        }

        // 3. F2P Fallback (last resort, might cause 'invalid payload' if audience mismatch)
        if (f2pIdentityToken != null) {
            return f2pIdentityToken;
        }

        // 4. Dynamic Cache
        return issuerIdentityCache.get(issuer);
    }

    /**
     * Ensures F2P tokens are available. Called during warmup phase.
     */
    public static void ensureF2PTokens() {
        if (f2pSessionToken == null) {
            LOGGER.info("[DualAuth] F2P tokens not available, triggering refresh...");
            DualServerIdentity.refreshF2PTokens();
        }
    }

    /**
     * Check if tokens are available for a given issuer.
     */
    public static boolean hasTokensForIssuer(String issuer) {
        if (issuer == null) return false;
        
        if (DualAuthHelper.isOfficialIssuer(issuer)) {
            return officialIdentityToken != null;
        }
        
        if (DualAuthContext.isOmni() && DualAuthContext.getJwk() != null) {
            return true; // Can generate dynamically
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
        LOGGER.info("[DualAuth] All token caches cleared");
    }
}
