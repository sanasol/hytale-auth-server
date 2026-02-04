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
import java.util.Date;
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
        
        // If we have an embedded JWK in context (Omni-Auth), use that
        if (DualAuthContext.isOmni()) {
            String token = EmbeddedJwkVerifier.createDynamicIdentityToken(issuer);
            if (token != null) return token;
        }

        // Use provided player UUID or fallback to context
        String aud = playerUuid;
        if (aud == null) aud = DualAuthContext.getPlayerUuid();
        if (aud == null || aud.isEmpty()) aud = UUID.randomUUID().toString();
        
        String sub = DualAuthHelper.getServerUuid();

        // STRATEGY: Generate a self-signed token with embedded JWK
        // Since we cannot modify the backend to sign tokens for specific players,
        // we must sign them ourselves and include the public key in the header.
        // The Hytale client (and Omni-Auth) supports this via the 'jwk' header parameter.
        try {
            OctetKeyPair kp = getOrCreateSelfSignedKeyPair();

            // DEBUG: Log all claims for troubleshooting
            if (Boolean.getBoolean("dualauth.debug")) {
                System.out.println("[DualAuth] Creating self-signed server identity token (embedded JWK):");
                System.out.println("[DualAuth]   iss (issuer): " + issuer);
                System.out.println("[DualAuth]   sub (server UUID): " + sub);
                System.out.println("[DualAuth]   aud (player UUID): " + aud);
                System.out.println("[DualAuth]   kid (key ID): " + kp.getKeyID());
            }

            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(sub)
                .audience(aud) // CRITICAL: Audience must be the Player's UUID
                .issueTime(new Date())
                .expirationTime(new Date(System.currentTimeMillis() + 3600000L))
                .claim("scope", "hytale:server")
                .build();

            String header = Base64URL.encode("{\"alg\":\"EdDSA\",\"typ\":\"JWT\",\"kid\":\"" + kp.getKeyID() + "\",\"jwk\":" + kp.toPublicJWK().toJSONString() + "}").toString();
            String payload = Base64URL.encode(claims.toJSONObject().toString()).toString();
            
            return signNative(header + "." + payload, kp);
        } catch (Exception e) { 
            LOGGER.log(Level.WARNING, "[DualAuth] Failed to create dynamic identity token: " + e.getMessage());
            return null; 
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
