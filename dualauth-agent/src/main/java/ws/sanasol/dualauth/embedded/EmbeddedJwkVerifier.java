package ws.sanasol.dualauth.embedded;

import ws.sanasol.dualauth.context.DualAuthContext;
import ws.sanasol.dualauth.context.DualAuthHelper;
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
import java.util.HashMap;
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

            // Extract JWK from header map as a Map first to preserve raw data
            Map<String, Object> jwkMap = (Map<String, Object>) headerMap.get("jwk");
            
            // Parse to object for verification logic
            JWK jwk = JWK.parse(jwkMap);
            if (!(jwk instanceof OctetKeyPair)) return null;
            OctetKeyPair kp = (OctetKeyPair) jwk;

            // 2. Parse claims set early for trust check
            String payloadStr = new String(Base64URL.from(payloadPart).decode(), StandardCharsets.UTF_8);
            JWTClaimsSet claims = JWTClaimsSet.parse(payloadStr);
            String issuer = claims.getIssuer();

            if (!DualAuthHelper.isOmniIssuerTrusted(issuer)) {
                LOGGER.info("Issuer untrusted for Omni-Auth: " + issuer);
                return null;
            } else if (Boolean.getBoolean("dualauth.debug.omni")) {
                LOGGER.info("Omni-Auth issuer TRUSTED: " + issuer);
            }

            // 3. Verify signature using Native EdDSA (Bypasses all Nimbus verification logic)
            LOGGER.info("Verifying Omni-Auth token via Native JCE (Lenient)...");
            PublicKey publicKey = DualAuthHelper.toNativePublic(kp);
            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(publicKey);
            sig.update(token.substring(0, dot2).getBytes(StandardCharsets.UTF_8));

            if (!sig.verify(Base64URL.from(signaturePart).decode())) {
                LOGGER.info("Omni-Auth: Signature mismatch");
                return null;
            }

            // MISSION CRITICAL: Populate context
            DualAuthContext.setIssuer(issuer);
            DualAuthContext.setPlayerUuid(claims.getSubject());
            
            // FIX: Serialize the Map directly to JSON string.
            // This bypasses OctetKeyPair.toJSONString() which strips private keys,
            // and bypasses the brittle extractRawJwk string manipulation.
            String rawJwkJson = JSONObjectUtils.toJSONString(jwkMap);
            DualAuthContext.setJwk(rawJwkJson);
            
            DualAuthContext.setOmni(true);
            
            // Capture username from claims if present
            String name = (String) claims.getClaim("username");
            if (name == null) name = (String) claims.getClaim("nickname");
            if (name == null) name = (String) claims.getClaim("name");
            if (name != null) DualAuthContext.setUsername(name);
            
            return claims;
        } catch (Exception e) {
            // Only catch Exception, not Throwable, to avoid hiding critical errors
            LOGGER.info("Omni-Auth Verification Error: " + e.getMessage());
            if (Boolean.getBoolean("dualauth.debug")) {
                e.printStackTrace();
            }
            return null;
        }
    }

    // Overload for backward compatibility / identity tokens
    public static String createSignedToken(String issuer, String tokenType) {
        return createSignedToken(issuer, tokenType, null);
    }

    public static String createSignedToken(String issuer, String tokenType, String fingerprint) {
        if (Boolean.getBoolean("dualauth.debug")) {
            LOGGER.info("DEBUG: createSignedToken called with issuer: " + issuer);
        }
        
        try {
            String jwkJson = DualAuthContext.getJwk();
            if (jwkJson == null) return null;
            
            OctetKeyPair kp = OctetKeyPair.parse(jwkJson);
            
            if (!kp.isPrivate()) {
                LOGGER.info("Cannot sign Omni-Auth token: Private key 'd' missing in captured JWK");
                return null;
            }

            JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .issueTime(new Date())
                    .expirationTime(new Date(System.currentTimeMillis() + 3600_000L));
            
            // Subject = Server UUID, Audience = Player UUID
            String serverUuid = DualAuthHelper.getServerUuid();
            builder.subject(serverUuid);
            
            String playerUuid = DualAuthContext.getPlayerUuid();
            if (playerUuid != null && !playerUuid.isEmpty()) {
                builder.audience(playerUuid);
            }

            builder.claim("scope", "hytale:server hytale:client");

            // FIX 1: Certificate Fingerprint Handling
            // If provided (from handshake), use it. Otherwise use dummy.
            HashMap<String, String> cnf = new HashMap<>();
            String fingerprintValue = (fingerprint != null && !fingerprint.isEmpty()) 
                    ? fingerprint 
                    : "OMNI_AUTH_BYPASS_CERT_FINGERPRINT_00";
                    
            cnf.put("x5t#S256", fingerprintValue);
            builder.claim("cnf", cnf);

            if ("identity".equals(tokenType)) {
                String username = DualAuthContext.getUsername();
                if (username != null) {
                    builder.claim("username", username);
                }
            }

            // FIX 2: Explicitly set Key ID (kid) in Header
            // The client needs this to find the key in the JWKS (or embedded JWK)
            JWSHeader.Builder headerBuilder = new JWSHeader.Builder(JWSAlgorithm.EdDSA)
                    .jwk(kp.toPublicJWK());
            
            if (kp.getKeyID() != null) {
                headerBuilder.keyID(kp.getKeyID());
            }

            SignedJWT signedJWT = new SignedJWT(headerBuilder.build(), builder.build());
            signedJWT.sign(new Ed25519Signer(kp));
            
            String token = signedJWT.serialize();

            if (Boolean.getBoolean("dualauth.debug.omni")) {
                LOGGER.info("Generated Omni-Auth " + tokenType + " token to " + issuer + " (kid: " + kp.getKeyID() + ")");
            }
            
            return token;
        } catch (Exception e) {
            LOGGER.info("Token Sign Failure: " + e.getMessage());
            e.printStackTrace();
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

    public static String createDynamicIdentityToken(String issuer) { return createSignedToken(issuer, "identity", null); }
    
    // Updated to accept fingerprint
    public static String createDynamicSessionToken(String issuer, String fingerprint) { return createSignedToken(issuer, "session", fingerprint); }
    
    // Backward compatibility
    public static String createDynamicSessionToken(String issuer) { return createSignedToken(issuer, "session", null); }
}
