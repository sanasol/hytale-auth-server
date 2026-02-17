package ws.sanasol.dualauth.server;

import ws.sanasol.dualauth.agent.DualAuthConfig;
import ws.sanasol.dualauth.context.DualAuthContext;
import ws.sanasol.dualauth.context.DualAuthHelper;
import ws.sanasol.dualauth.embedded.EmbeddedJwkVerifier;
import com.nimbusds.jose.*;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jose.jwk.gen.OctetKeyPairGenerator;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.JWTClaimsSet;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Arrays;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Manages F2P server identity token fetching and self-signed token generation.
 */
public class DualServerIdentity {
    private static final Logger LOGGER = Logger.getLogger("DualAuthAgent");
    private static volatile OctetKeyPair selfSignedKeyPair = null;
    private static final int HTTP_TIMEOUT = 5000;
    
    // Unified cache for server tokens by issuer (TTL from config)
    private static final ConcurrentHashMap<String, CachedServerTokens> serverTokenCache = new ConcurrentHashMap<>();
    private static final long SERVER_TOKEN_TTL = DualAuthConfig.KEYS_CACHE_TTL_MS;
    
    private static class CachedServerTokens {
        final String identityToken;
        final String sessionToken;
        final String tokenType; // "federated" or "omni"
        final long timestamp;
        
        CachedServerTokens(String identityToken, String sessionToken, String tokenType) {
            this.identityToken = identityToken;
            this.sessionToken = sessionToken;
            this.tokenType = tokenType;
            this.timestamp = System.currentTimeMillis();
        }
        
        CachedServerTokens(String identityToken, String tokenType) {
            this(identityToken, null, tokenType);
        }
        
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > SERVER_TOKEN_TTL;
        }
        
        String getIdentityToken() {
            return identityToken;
        }
    }

    public static void refreshF2PTokens() {
        try {
            String endpoint = DualAuthConfig.F2P_SESSION_URL + "/server/auto-auth";
            LOGGER.info("Fetching F2P identity token from: " + endpoint);
            String response = fetchUrl(endpoint);
            if (response == null || response.isEmpty()) {
                generateFallbackTokens(); return;
            }
            String identityToken = extractJsonField(response, "identityToken");
            String sessionToken = extractJsonField(response, "sessionToken");
            if (identityToken == null) identityToken = extractJsonField(response, "token");
            if (identityToken != null) {
                DualServerTokenManager.setF2PTokens(sessionToken, identityToken);
                
                // Capture optional metadata from Sanasol backend
                String sUuid = extractJsonField(response, "serverUuid");
                if (sUuid != null) DualAuthHelper.setServerUuid(sUuid);
                
                String sId = extractJsonField(response, "serverId");
                if (sId != null) DualAuthHelper.setServerId(sId);

                LOGGER.info("F2P tokens fetched successfully (UUID: " + DualAuthHelper.getServerUuid() + ", ID: " + DualAuthHelper.getServerId() + ")");
            } else generateFallbackTokens();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to fetch F2P tokens: " + e.getMessage());
            generateFallbackTokens();
        }
    }

    public static void generateFallbackTokens() {
        try {
            OctetKeyPair keyPair = getOrCreateSelfSignedKeyPair();
            String issuer = DualAuthConfig.F2P_ISSUER;
            String serverUuid = DualAuthHelper.getServerUuid();
            Date now = new Date();
            Date exp = new Date(now.getTime() + 3600_000L); 
            
            JWTClaimsSet idClaims = new JWTClaimsSet.Builder().issuer(issuer).subject(serverUuid).audience("hytale:client").issueTime(now).expirationTime(exp).claim("scope", "hytale:server").build();
            JWTClaimsSet sessClaims = new JWTClaimsSet.Builder().issuer(issuer).subject(serverUuid).issueTime(now).expirationTime(exp).claim("scope", "hytale:server hytale:client").build();
            
            String head = Base64URL.encode("{\"alg\":\"EdDSA\",\"typ\":\"JWT\",\"jwk\":" + keyPair.toPublicJWK().toJSONString() + "}") + ".";
            String identityToken = signNative(head + Base64URL.encode(idClaims.toJSONObject().toString()), keyPair);
            String sessionToken = signNative(head + Base64URL.encode(sessClaims.toJSONObject().toString()), keyPair);
            
            DualServerTokenManager.setF2PTokens(sessionToken, identityToken);
            LOGGER.info("Generated self-signed fallback tokens (Native EdDSA)");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to generate fallback tokens: " + e.getMessage());
        }
    }

    private static String signNative(String input, OctetKeyPair kp) throws Exception {
        PrivateKey privKey = EmbeddedJwkVerifier.toNativePrivate(kp);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(privKey);
        signer.update(input.getBytes(StandardCharsets.UTF_8));
        return input + "." + Base64URL.encode(signer.sign());
    }

    private static synchronized OctetKeyPair getOrCreateSelfSignedKeyPair() throws Exception {
        if (selfSignedKeyPair == null) selfSignedKeyPair = new OctetKeyPairGenerator(Curve.Ed25519).keyID(UUID.randomUUID().toString()).generate();
        return selfSignedKeyPair;
    }


    public static String createDynamicIdentityToken(String issuer, String playerUuid) {
        if (issuer == null) issuer = DualAuthContext.getIssuer();
        if (issuer == null) issuer = DualAuthConfig.F2P_ISSUER;
        
        // FIX: Check for Omni-Auth first
        if (DualAuthContext.isOmni() || hasEmbeddedJwkFromContext(issuer)) {
            if (Boolean.getBoolean("dualauth.debug")) {
                 LOGGER.info("Creating Omni-Auth server identity token for: " + issuer);
                 LOGGER.info("DEBUG: Omni-Auth - DualAuthContext.isOmni(): " + DualAuthContext.isOmni());
                 LOGGER.info("DEBUG: Omni-Auth - hasEmbeddedJwkFromContext(" + issuer + "): " + hasEmbeddedJwkFromContext(issuer));
                 LOGGER.info("DEBUG: Omni-Auth - Current context issuer: " + DualAuthContext.getIssuer());
            }
            
            // Check Omni-Auth cache by player-uuid (NOT issuer)
            String playerUuidFromContext = DualAuthContext.getPlayerUuid();
            if (playerUuidFromContext != null) {
                String cacheKey = playerUuidFromContext + ":" + issuer;
                CachedServerTokens cached = serverTokenCache.get(cacheKey);
                if (cached != null && !cached.isExpired() && "omni".equals(cached.tokenType)) {
                    if (Boolean.getBoolean("dualauth.debug")) {
                        LOGGER.info("DEBUG: Using cached Omni-Auth token for player: " + playerUuidFromContext + " (issuer: " + issuer + ")");
                    }
                    return cached.getIdentityToken();
                }
            }
            
            // Generate new Omni-Auth token
            String token = EmbeddedJwkVerifier.createDynamicIdentityToken(issuer);
            
            // Cache the token by player-uuid
            if (token != null && playerUuidFromContext != null) {
                String cacheKey = playerUuidFromContext + ":" + issuer;
                serverTokenCache.put(cacheKey, new CachedServerTokens(token, "omni"));
                if (Boolean.getBoolean("dualauth.debug")) {
                    LOGGER.info("DEBUG: Cached Omni-Auth token for player: " + playerUuidFromContext + " (issuer: " + issuer + ")");
                }
            }
            
            return token;
        }
        
        // PATCHER STRATEGY: Only generate tokens for official issuers if needed (though usually captured)
        if (!DualAuthHelper.isOfficialIssuerStrict(issuer)) {
            if (Boolean.getBoolean("dualauth.debug")) {
                LOGGER.info("DEBUG: Non-official issuer, attempting federated token from: " + issuer);
            }
            
            // NEW: Try to fetch federated token from the issuer's /auto-auth endpoint
            DualServerTokenManager.FederatedIssuerTokens fedTokens = fetchFederatedTokensFromIssuer(issuer);
            if (fedTokens != null && fedTokens.getIdentityToken() != null) {
                if (Boolean.getBoolean("dualauth.debug")) {
                    LOGGER.info("DEBUG: Successfully fetched federated identity token from: " + issuer);
                }
                return fedTokens.getIdentityToken();
            }
            
            if (Boolean.getBoolean("dualauth.debug")) {
                LOGGER.info("DEBUG: Failed to fetch federated token from: " + issuer);
            }
            return null;
        }
        
        // For official issuers, typically tokens come from official flow.
        if (Boolean.getBoolean("dualauth.debug")) {
            LOGGER.info("Dynamic server identity generation not needed for official issuer: " + issuer);
        }
        return null; // Original patcher flow doesn't generate them dynamically for official either
    }

    private static boolean hasEmbeddedJwkFromContext(String issuer) {
        String jwk = DualAuthContext.getJwk();
        return jwk != null && !jwk.isEmpty() && issuer != null && (issuer.contains("127.0.0") || issuer.contains("localhost"));
    }

    public static DualServerTokenManager.FederatedIssuerTokens fetchFederatedTokensFromIssuer(String issuer) {
        // 1. Check unified cache first (fast path)
        CachedServerTokens cached = serverTokenCache.get(issuer);
        if (cached != null && !cached.isExpired() && "federated".equals(cached.tokenType)) {
            if (Boolean.getBoolean("dualauth.debug")) {
                LOGGER.info("DEBUG: Using cached federated tokens for issuer: " + issuer);
            }
            return new DualServerTokenManager.FederatedIssuerTokens(cached.identityToken, cached.sessionToken, SERVER_TOKEN_TTL);
        }
        
        // 2. Cache miss - initiate async fetch and wait for this specific request
        CompletableFuture<DualServerTokenManager.FederatedIssuerTokens> future = CompletableFuture.supplyAsync(() -> {
            try {
                // Perform the actual HTTP fetch operation
                if (Boolean.getBoolean("dualauth.debug")) {
                    LOGGER.info("DEBUG: Cache miss for issuer: " + issuer + " - fetching from /auto-auth");
                }
                
                // 1. Build issuer endpoint
                String baseUrl = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
                String autoAuthEndpoint = baseUrl + "/server/auto-auth";
                
                // 2. Prepare request with server data
                String serverUuid = DualAuthHelper.getServerUuid();
                String serverName = DualAuthHelper.getServerName();
                
                Map<String, Object> requestData = Map.of(
                    "serverUuid", serverUuid,
                    "serverName", serverName
                );
                
                String jsonBody = new com.google.gson.Gson().toJson(requestData);
                
                // 3. Execute HTTP request
                URL url = new URL(autoAuthEndpoint);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("User-Agent", "DualAuthAgent/1.0");
                conn.setConnectTimeout(HTTP_TIMEOUT);
                conn.setReadTimeout(HTTP_TIMEOUT);
                conn.setDoOutput(true);
                
                // 4. Send request
                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = jsonBody.getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }
                
                // 5. Read response
                int responseCode = conn.getResponseCode();
                String responseBody;
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String responseLine;
                    while ((responseLine = br.readLine()) != null) {
                        sb.append(responseLine).append(" ");
                    }
                    responseBody = sb.toString().trim();
                }
                
                if (responseCode != 200) {
                    if (Boolean.getBoolean("dualauth.debug")) {
                        LOGGER.info("DEBUG: Failed to fetch federated tokens, HTTP " + responseCode + " for: " + issuer);
                    }
                    return null;
                }
                
                // 6. Parse response
                @SuppressWarnings("unchecked")
                Map<String, Object> response = new com.google.gson.Gson().fromJson(responseBody, Map.class);
                
                String identityToken = (String) response.get("identityToken");
                String sessionToken = (String) response.get("sessionToken");
                
                if (identityToken == null) {
                    if (Boolean.getBoolean("dualauth.debug")) {
                        LOGGER.info("DEBUG: No identityToken in federated response for: " + issuer);
                    }
                    return null;
                }
                
                // 7. Cache the result
                CachedServerTokens newCached = new CachedServerTokens(identityToken, sessionToken, "federated");
                serverTokenCache.put(issuer, newCached);
                
                if (Boolean.getBoolean("dualauth.debug")) {
                    LOGGER.info("DEBUG: Successfully fetched and cached federated tokens for: " + issuer);
                }
                
                return new DualServerTokenManager.FederatedIssuerTokens(identityToken, sessionToken, SERVER_TOKEN_TTL);
                
            } catch (Exception e) {
                if (Boolean.getBoolean("dualauth.debug")) {
                    LOGGER.info("DEBUG: Exception fetching federated tokens for " + issuer + ": " + e.getMessage());
                }
                return null;
            }
        });
        
        // 3. Wait for the result (but this is only for THIS caller, others get served from cache)
        try {
            return future.get(8, TimeUnit.SECONDS); // Reasonable timeout for client connection
        } catch (Exception e) {
            if (Boolean.getBoolean("dualauth.debug")) {
                LOGGER.info("DEBUG: Timeout or error waiting for federated tokens for: " + issuer);
            }
            return null;
        }
    }
    
    public static void cleanupExpiredTokens() {
        int cleaned = 0;
        
        // Clean expired server tokens
        for (Map.Entry<String, CachedServerTokens> entry : serverTokenCache.entrySet()) {
            if (entry.getValue().isExpired()) {
                serverTokenCache.remove(entry.getKey());
                cleaned++;
            }
        }
        
        if (Boolean.getBoolean("dualauth.debug") && cleaned > 0) {
            LOGGER.info("Cleaned up " + cleaned + " expired server token entries");
        }
    }


    private static String fetchUrl(String urlString) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(HTTP_TIMEOUT);
            conn.setReadTimeout(HTTP_TIMEOUT);
            conn.setRequestProperty("Accept", "application/json");
            if (conn.getResponseCode() != 200) return null;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder(); String line;
                while ((line = r.readLine()) != null) sb.append(line);
                return sb.toString();
            }
        } catch (Exception e) { return null; } finally { if (conn != null) conn.disconnect(); }
    }

    private static String extractJsonField(String json, String fieldName) {
        if (json == null) return null;
        try {
            // More lenient parsing for whitespaces: "key" : "value"
            String pattern = "\"" + fieldName + "\"";
            int keyStart = json.indexOf(pattern);
            if (keyStart < 0) return null;
            
            int colonPos = json.indexOf(":", keyStart + pattern.length());
            if (colonPos < 0) return null;
            
            int quoteStart = json.indexOf("\"", colonPos + 1);
            if (quoteStart < 0) return null;
            
            int quoteEnd = json.indexOf("\"", quoteStart + 1);
            if (quoteEnd < 0) return null;
            
            return json.substring(quoteStart + 1, quoteEnd);
        } catch (Exception e) { return null; }
    }
}
