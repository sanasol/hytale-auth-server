package com.hytale.dualauth.server;

import com.hytale.dualauth.agent.DualAuthConfig;
import com.hytale.dualauth.context.DualAuthContext;
import com.hytale.dualauth.context.DualAuthHelper;
import com.hytale.dualauth.embedded.EmbeddedJwkVerifier;
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

/**
 * Manages F2P server identity token fetching and self-signed token generation.
 */
public class DualServerIdentity {
    private static final Logger LOGGER = Logger.getLogger("DualAuthAgent");
    private static volatile OctetKeyPair selfSignedKeyPair = null;
    private static final int HTTP_TIMEOUT = 5000;

    public static void refreshF2PTokens() {
        try {
            String endpoint = DualAuthConfig.F2P_SESSION_URL + "/server/auto-auth";
            LOGGER.info("[DualAuth] Fetching F2P identity token from: " + endpoint);
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

                LOGGER.info("[DualAuth] F2P tokens fetched successfully (UUID: " + DualAuthHelper.getServerUuid() + ", ID: " + DualAuthHelper.getServerId() + ")");
            } else generateFallbackTokens();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[DualAuth] Failed to fetch F2P tokens: " + e.getMessage());
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
            LOGGER.info("[DualAuth] Generated self-signed fallback tokens (Native EdDSA)");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[DualAuth] Failed to generate fallback tokens: " + e.getMessage());
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
                 System.out.println("[DualAuth] Creating Omni-Auth server identity token for: " + issuer);
            }
            return EmbeddedJwkVerifier.createDynamicIdentityToken(issuer);
        }
        
        // PATCHER STRATEGY: Only generate tokens for official issuers if needed (though usually captured)
        if (!DualAuthHelper.isOfficialIssuerStrict(issuer)) {
            if (Boolean.getBoolean("dualauth.debug")) {
                System.out.println("[DualAuth] Skipping dynamic server identity token for non-official issuer: " + issuer);
            }
            return null;
        }
        
        // For official issuers, typically tokens come from official flow.
        if (Boolean.getBoolean("dualauth.debug")) {
            System.out.println("[DualAuth] Dynamic server identity generation not needed for official issuer: " + issuer);
        }
        return null; // Original patcher flow doesn't generate them dynamically for official either
    }

    private static boolean hasEmbeddedJwkFromContext(String issuer) {
        String jwk = DualAuthContext.getJwk();
        return jwk != null && !jwk.isEmpty() && issuer != null && (issuer.contains("127.0.0") || issuer.contains("localhost"));
    }

    public static DualServerTokenManager.FederatedIssuerTokens fetchFederatedTokensFromIssuer(String issuer) {
        try {
            // 1. Build issuer endpoint
            String baseUrl = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
            String autoAuthEndpoint = baseUrl + "/server/auto-auth";
            
            // 2. Prepare request with server data
            String serverUuid = DualAuthHelper.getServerUuid();
            String serverName = DualAuthHelper.getServerName();
            
            String jsonBody = String.format(
                "{\"uuid\":\"%s\",\"name\":\"%s\"}", 
                serverUuid, serverName
            );
            
            // 3. Execute HTTP request
            URL url = new URL(autoAuthEndpoint);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "Hytale-Server/1.0");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setDoOutput(true);
            
            // 4. Send request
            try (java.io.OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }
            
            // 5. Process response
            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                String responseBody = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                
                // 6. Extract tokens
                String identityToken = extractJsonField(responseBody, "identityToken");
                String sessionToken = extractJsonField(responseBody, "sessionToken");
                
                if (identityToken != null && !identityToken.isEmpty()) {
                    if (Boolean.getBoolean("dualauth.debug")) {
                        System.out.println("[DualAuth] Successfully fetched federated tokens from: " + issuer);
                    }
                    
                    // TTL of 1 hour for federated tokens
                    long ttl = 3600000L; 
                    return new DualServerTokenManager.FederatedIssuerTokens(identityToken, sessionToken, ttl);
                }
            } else {
                if (Boolean.getBoolean("dualauth.debug")) {
                    System.out.println("[DualAuth] HTTP error from issuer: " + issuer + " - Code: " + responseCode);
                }
            }
            
        } catch (Exception e) {
            if (Boolean.getBoolean("dualauth.debug")) {
                System.out.println("[DualAuth] Exception fetching federated tokens: " + e.getMessage());
            }
        }
        
        return null;
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
