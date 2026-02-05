package com.hytale.dualauth.embedded;

import com.hytale.dualauth.context.DualAuthContext;
import com.hytale.dualauth.context.DualAuthHelper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.Ed25519Signer;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Omni-Auth embedded key verification and token generation.
 * Logic matched exactly to the original ASM patcher to ensure client compatibility.
 * Bypasses Nimbus strictness regarding private keys in 'jwk' header parameter.
 */
public class EmbeddedJwkVerifier {
    private static final Logger LOGGER = Logger.getLogger("DualAuthAgent");

    public static JWTClaimsSet verifyAndGetClaims(String token) {
        try {
            DualAuthContext.setOmni(false);
            int dot1 = token.indexOf('.');
            int dot2 = token.lastIndexOf('.');
            if (dot1 < 0 || dot2 <= dot1) return null;

            String headerPart = token.substring(0, dot1);
            String payloadPart = token.substring(dot1 + 1, dot2);
            String signaturePart = token.substring(dot2 + 1);

            // 1. Leniently parse header to avoid Nimbus strictness (Non-public key in jwk header parameter)
            String headerStr = new String(Base64URL.from(headerPart).decode(), StandardCharsets.UTF_8);
            Map<String, Object> headerMap = JSONObjectUtils.parse(headerStr);
            if (!headerMap.containsKey("jwk")) return null;

            // Extract JWK from header map
            JWK jwk = JWK.parse((Map<String, Object>) headerMap.get("jwk"));
            if (!(jwk instanceof OctetKeyPair)) return null;
            OctetKeyPair kp = (OctetKeyPair) jwk;

            // 2. Parse claims set early for trust check
            String payloadStr = new String(Base64URL.from(payloadPart).decode(), StandardCharsets.UTF_8);
            JWTClaimsSet claims = JWTClaimsSet.parse(payloadStr);
            String issuer = claims.getIssuer();

            if (!DualAuthHelper.isOmniIssuerTrusted(issuer)) {
                LOGGER.warning("[DualAuth] Issuer untrusted for Omni-Auth: " + issuer);
                return null;
            } else if (Boolean.getBoolean("dualauth.debug.omni")) {
                LOGGER.info("[DualAuth] Omni-Auth issuer TRUSTED: " + issuer);
            }

            // 3. Verify signature using Native EdDSA (Bypasses all Nimbus verification logic)
            LOGGER.info("[DualAuth] Verifying Omni-Auth token via Native JCE (Lenient)...");
            PublicKey publicKey = DualAuthHelper.toNativePublic(kp);
            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(publicKey);
            sig.update(token.substring(0, dot2).getBytes(StandardCharsets.UTF_8));

            if (!sig.verify(Base64URL.from(signaturePart).decode())) {
                LOGGER.warning("[DualAuth] Omni-Auth: Signature mismatch");
                return null;
            }

            // MISSION CRITICAL: Populate context
            DualAuthContext.setIssuer(issuer);
            DualAuthContext.setPlayerUuid(claims.getSubject());
            DualAuthContext.setJwk(kp.toJSONString());
            DualAuthContext.setOmni(true);
            
            // Capture username from claims if present
            String name = (String) claims.getClaim("username");
            if (name == null) name = (String) claims.getClaim("nickname");
            if (name == null) name = (String) claims.getClaim("name");
            if (name != null) DualAuthContext.setUsername(name);
            
            return claims;
        } catch (Throwable e) {
            LOGGER.log(Level.SEVERE, "[DualAuth] Omni-Auth Verification Error: " + e.getMessage());
            return null;
        }
    }

    public static String createSignedToken(String issuer, String tokenType) {
        if (Boolean.getBoolean("dualauth.debug")) {
            System.out.println("[DualAuth] DEBUG: createSignedToken called with issuer: " + issuer);
            System.out.println("[DualAuth] DEBUG: createSignedToken - Current DualAuthContext issuer: " + DualAuthContext.getIssuer());
        }
        
        try {
            String jwkJson = DualAuthContext.getJwk();
            if (jwkJson == null) return null;
            OctetKeyPair kp = OctetKeyPair.parse(jwkJson);
            
            if (!kp.isPrivate()) {
                LOGGER.warning("[DualAuth] Cannot sign Omni-Auth token: Private key 'd' missing in captured JWK");
                return null;
            }

            // Build Claims - CRITICAL FIX:
            // For server identity tokens sent TO clients:
            //   sub = SERVER UUID (who is signing/sending)
            //   aud = PLAYER UUID (who is receiving)
            JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .issueTime(new Date())
                    .expirationTime(new Date(System.currentTimeMillis() + 3600_000L));
            
            if (Boolean.getBoolean("dualauth.debug")) {
                System.out.println("[DualAuth] DEBUG: createSignedToken - Setting issuer in JWT claims: " + issuer);
            }

            // Subject = Server UUID
            String serverUuid = DualAuthHelper.getServerUuid();
            builder.subject(serverUuid);
            
            // Audience = Player UUID (who receives this token)
            String playerUuid = DualAuthContext.getPlayerUuid();
            if (playerUuid != null && !playerUuid.isEmpty()) {
                builder.audience(playerUuid);
            }

            builder.claim("scope", "hytale:server hytale:client");

            if ("identity".equals(tokenType)) {
                String username = DualAuthContext.getUsername();
                if (username != null) {
                    builder.claim("username", username);
                }
            }


            // Build Header (Matches OriginalDualAuthPatcher.java: includes public JWK)
            // Note: We use toPublicJWK() here to ensure we don't send our 'd' back to the client!
            JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.EdDSA)
                    .jwk(kp.toPublicJWK())
                    .build();

            // Sign and Serialize
            SignedJWT signedJWT = new SignedJWT(header, builder.build());
            signedJWT.sign(new Ed25519Signer(kp));
            
            String token = signedJWT.serialize();

            if (Boolean.getBoolean("dualauth.debug.omni")) {
                System.out.println("[DualAuth] Generated Omni-Auth " + tokenType + " token to " + issuer);
            }
            
            return token;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[DualAuth] Token Sign Failure: " + e.getMessage());
            return null;
        }
    }

    public static PrivateKey toNativePrivate(OctetKeyPair kp) throws Exception {
        byte[] d = kp.getD().decode();
        byte[] encoded = new byte[ED25519_PKCS8_HEADER.length + d.length];
        System.arraycopy(ED25519_PKCS8_HEADER, 0, encoded, 0, ED25519_PKCS8_HEADER.length);
        System.arraycopy(d, 0, encoded, ED25519_PKCS8_HEADER.length, d.length);
        return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(encoded));
    }

    private static final byte[] ED25519_PKCS8_HEADER = {0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20};

    public static String createDynamicIdentityToken(String issuer) { return createSignedToken(issuer, "identity"); }
    public static String createDynamicSessionToken(String issuer) { return createSignedToken(issuer, "session"); }
}
