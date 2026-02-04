package com.hytale.dualauth.context;

import com.hytale.dualauth.agent.DualAuthConfig;
import com.hytale.dualauth.server.DualServerTokenManager;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Base64;
import java.util.logging.Logger;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.ECKey;

/**
 * Global helper for authentication, reflection, and context processing.
 */
public class DualAuthHelper {
    private static final Logger LOGGER = Logger.getLogger("DualAuthAgent");

    // --- Ed25519 NATIVE UTILS ---
    private static final byte[] ED25519_X509_HEADER = {0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00};

    public static PublicKey toNativePublic(OctetKeyPair okp) throws Exception {
        byte[] x = okp.getX().decode();
        byte[] encoded = new byte[ED25519_X509_HEADER.length + x.length];
        System.arraycopy(ED25519_X509_HEADER, 0, encoded, 0, ED25519_X509_HEADER.length);
        System.arraycopy(x, 0, encoded, ED25519_X509_HEADER.length, x.length);
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
    }

    // --- ISSUER HELPERS ---

    public static boolean isOfficialIssuer(String issuer) {
        if (issuer == null) return true;
        return issuer.contains(DualAuthConfig.OFFICIAL_DOMAIN);
    }

    public static boolean isValidIssuer(String issuer) {
        if (issuer == null) return false;
        String norm = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
        if (DualAuthConfig.TRUST_ALL_ISSUERS) return true;
        if (isOfficialIssuer(norm)) return true;
        if (norm.contains(DualAuthConfig.F2P_BASE_DOMAIN)) return true;
        for (String trusted : DualAuthConfig.TRUSTED_ISSUERS) {
            if (norm.contains(trusted.trim())) return true;
        }
        return false;
    }

    public static String getSessionUrlForIssuer(String issuer) {
        if (issuer == null || isOfficialIssuer(issuer)) return DualAuthConfig.OFFICIAL_SESSION_URL;
        return issuer; 
    }

    // --- JWT VALIDATION HELPERS ---

    public static Object verifyTrustedToken(Object validatorInstance, String token, String methodName) {
        try {
            // OPTIMIZATION: If it has an embedded JWK, it's Omni-Auth and already failed verifyAndGetClaims
            // if we are here. Skip to avoid Nimbus ParseException on private keys in headers.
            if (hasEmbeddedJwk(token)) return null;

            ClassLoader cl = validatorInstance.getClass().getClassLoader();
            
            Field cacheField = null;
            for (Field f : validatorInstance.getClass().getDeclaredFields()) {
                if (f.getType().getName().equals("com.nimbusds.jose.jwk.JWKSet")) {
                    cacheField = f; break;
                }
            }
            if (cacheField == null) return null;
            cacheField.setAccessible(true);
            
            com.nimbusds.jose.jwk.JWKSet jwkSet = (com.nimbusds.jose.jwk.JWKSet) cacheField.get(validatorInstance);
            
            if (jwkSet == null) {
                for (Method m : validatorInstance.getClass().getDeclaredMethods()) {
                    if (m.getName().equals("fetchJwksFromService") || m.getName().equals("getJwkSet")) {
                        m.setAccessible(true);
                        if (m.getParameterCount() == 0) {
                            jwkSet = (com.nimbusds.jose.jwk.JWKSet) m.invoke(validatorInstance);
                            break;
                        }
                    }
                }
            }

            if (jwkSet == null) return null;

            SignedJWT signedJWT = null;
            try {
                signedJWT = SignedJWT.parse(token);
            } catch (Exception e) {
                // Return null to fall back, but don't crash
                return null;
            }
            
            String kid = signedJWT.getHeader().getKeyID();
            java.util.List<JWK> keys = jwkSet.getKeys();
            
            if (kid != null) {
                JWK match = jwkSet.getKeyByKeyId(kid);
                if (match != null) keys = java.util.Collections.singletonList(match);
            }

            for (JWK key : keys) {
                if (key == null) continue;
                try {
                    boolean verified = false;
                    if (key instanceof OctetKeyPair) {
                        PublicKey pub = toNativePublic((OctetKeyPair) key);
                        Signature sig = Signature.getInstance("Ed25519");
                        sig.initVerify(pub);
                        sig.update(signedJWT.getSigningInput());
                        verified = sig.verify(signedJWT.getSignature().decode());
                    } else {
                        com.nimbusds.jose.proc.JWSVerifierFactory factory = new com.nimbusds.jose.crypto.factories.DefaultJWSVerifierFactory();
                        java.security.Key pubKey = null;
                        if (key instanceof RSAKey) pubKey = ((RSAKey) key).toPublicKey();
                        else if (key instanceof ECKey) pubKey = ((ECKey) key).toPublicKey();
                        if (pubKey != null) {
                            com.nimbusds.jose.JWSVerifier verifier = factory.createJWSVerifier(signedJWT.getHeader(), pubKey);
                            verified = signedJWT.verify(verifier);
                        }
                    }
                    
                    if (verified) return createJWTClaimsWrapper(cl, signedJWT.getJWTClaimsSet(), methodName, null);
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {}
        return null;
    }

    public static Object createJWTClaimsWrapper(ClassLoader cl, com.nimbusds.jwt.JWTClaimsSet claims, String methodName, String descriptor) {
        try {
            String targetClassName = null;
            
            // 1. Precise match via descriptor (robust)
            if (descriptor != null && descriptor.contains(")L")) {
                int start = descriptor.indexOf(")L") + 2;
                int end = descriptor.lastIndexOf(';');
                if (end > start) {
                    targetClassName = descriptor.substring(start, end).replace('/', '.');
                }
            }
            
            // 2. Fallback via method name if descriptor missing/invalid
            if (targetClassName == null && methodName != null) {
                String lower = methodName.toLowerCase();
                if (lower.contains("identity")) {
                    targetClassName = "com.hypixel.hytale.server.core.auth.JWTValidator$IdentityTokenClaims";
                } else if (lower.contains("session")) {
                    targetClassName = "com.hypixel.hytale.server.core.auth.JWTValidator$SessionTokenClaims";
                } else if (lower.contains("access") || lower.equals("validatetoken")) {
                    targetClassName = "com.hypixel.hytale.server.core.auth.JWTValidator$JWTClaims";
                }
            }

            Class<?> clazz = null;
            if (targetClassName != null) {
                try {
                    clazz = cl.loadClass(targetClassName);
                } catch (Exception e) {
                    if (Boolean.getBoolean("dualauth.debug")) {
                        System.out.println("[DualAuth] Could not load preferred class: " + targetClassName);
                    }
                }
            }

            // 3. Last resort fallbacks
            if (clazz == null) {
                String[] fallbacks = {
                    "com.hypixel.hytale.server.core.auth.JWTValidator$JWTClaims",
                    "com.hypixel.hytale.server.core.auth.JWTValidator$IdentityTokenClaims",
                    "com.hypixel.hytale.server.core.auth.JWTValidator$SessionTokenClaims"
                };
                for (String cn : fallbacks) {
                    try { clazz = cl.loadClass(cn); break; } catch (Exception ignored) {}
                }
            }

            if (clazz == null) return null;

            Object wrapper = clazz.getDeclaredConstructor().newInstance();
            
            // 4. Populate Fields via Reflection
            setF(wrapper, "issuer", claims.getIssuer());
            setF(wrapper, "subject", claims.getSubject());
            
            // Handle timestamps (Convert Date to Long seconds)
            if (claims.getIssueTime() != null) setF(wrapper, "issuedAt", claims.getIssueTime().getTime() / 1000L);
            if (claims.getExpirationTime() != null) setF(wrapper, "expiresAt", claims.getExpirationTime().getTime() / 1000L);
            if (claims.getNotBeforeTime() != null) setF(wrapper, "notBefore", claims.getNotBeforeTime().getTime() / 1000L);
            
            // Username field (present in JWTClaims and IdentityTokenClaims)
            try {
                String user = claims.getStringClaim("username");
                if (user == null) user = claims.getStringClaim("name");
                if (user != null) setF(wrapper, "username", user);
            } catch (Exception ignored) {}

            // Audience (JWTClaims)
            try {
                java.util.List<String> aud = claims.getAudience();
                if (aud != null && !aud.isEmpty()) {
                    setF(wrapper, "audience", aud.get(0));
                }
            } catch (Exception ignored) {}

            // Scope (Identity/Session)
            try {
                String scope = claims.getStringClaim("scope");
                if (scope != null) {
                    setF(wrapper, "scope", scope);
                } else {
                    // Default to client scope to ensure handshake proceeds
                    setF(wrapper, "scope", "hytale:client");
                }
            } catch (Exception ignored) {}

            return wrapper;
        } catch (Exception e) {
            System.err.println("[DualAuth] Failed to wrap claims: " + e.getMessage());
            return null;
        }
    }

    public static void updateExpectedIssuer(Object validator, String issuer) {
        if (validator == null) return;
        try {
            Class<?> clazz = validator.getClass();
            while (clazz != null && clazz != Object.class) {
                for (Field f : clazz.getDeclaredFields()) {
                    String name = f.getName().toLowerCase();
                    // Update Issuer
                    if (issuer != null && (name.contains("expectedissuer") || name.equals("issuer"))) {
                        f.setAccessible(true);
                        f.set(validator, issuer);
                        if (Boolean.getBoolean("dualauth.debug")) {
                            System.out.println("[DualAuth] Updated expectedIssuer to: " + issuer + " in " + clazz.getSimpleName());
                        }
                    }
                    // Update Audience
                    String sId = getServerId();
                    if (sId != null && (name.contains("expectedaudience") || name.equals("audience"))) {
                        f.setAccessible(true);
                        f.set(validator, sId);
                        if (Boolean.getBoolean("dualauth.debug")) {
                            System.out.println("[DualAuth] Updated expectedAudience to: " + sId + " in " + clazz.getSimpleName());
                        }
                    }
                }
                clazz = clazz.getSuperclass();
            }
        } catch (Exception ignored) {}
    }

    // --- REFLECTION UTILS ---

    public static void setF(Object obj, String name, Object val) {
        if (val == null) return;
        try {
            Field f = null;
            try { f = obj.getClass().getDeclaredField(name); } 
            catch (NoSuchFieldException e) {
                for (Field field : obj.getClass().getDeclaredFields()) {
                    if (field.getName().equalsIgnoreCase(name)) { f = field; break; }
                }
            }
            if (f != null) {
                f.setAccessible(true);
                Class<?> type = f.getType();
                if (type == long.class || type == Long.class) {
                    if (val instanceof Number) f.set(obj, ((Number) val).longValue());
                } else if (type.getName().equals("java.time.Instant") && val instanceof Long) {
                    f.set(obj, java.time.Instant.ofEpochSecond((Long) val));
                } else if (type == String[].class && val instanceof String[]) {
                    f.set(obj, val);
                } else if (type == String.class) {
                    f.set(obj, String.valueOf(val));
                } else {
                    f.set(obj, val);
                }
            }
        } catch (Exception ignored) {}
    }

    public static Object getF(Object obj, String name) {
        if (obj == null) return null;
        try {
            Field f = null;
            try { f = obj.getClass().getDeclaredField(name); } 
            catch (NoSuchFieldException e) {
                for (Field field : obj.getClass().getDeclaredFields()) {
                    if (field.getName().equalsIgnoreCase(name)) { f = field; break; }
                }
            }
            if (f != null) {
                f.setAccessible(true);
                return f.get(obj);
            }
        } catch (Exception ignored) {}
        return null;
    }

    public static Field findUrlField(Class<?> clazz) {
        while (clazz != null && clazz != Object.class) {
            for (Field f : clazz.getDeclaredFields()) {
                String name = f.getName().toLowerCase();
                if (name.contains("sessionserviceurl") || name.contains("baseurl") || name.contains("serviceurl") || (f.getType() == String.class && name.contains("url"))) return f;
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    public static void updateCacheTimestamp(Object thiz) {
        try {
            for (Field f : thiz.getClass().getDeclaredFields()) {
                String name = f.getName().toLowerCase();
                if (name.contains("refresh") || name.contains("cache") || name.contains("expiry") || name.contains("updated") || name.contains("last")) {
                    f.setAccessible(true);
                    if (f.getType().equals(long.class)) f.setLong(thiz, System.currentTimeMillis());
                    else if (f.getType().getName().equals("java.time.Instant")) f.set(thiz, java.time.Instant.now());
                }
            }
        } catch (Exception ignored) {}
    }

    public static void maybeReplaceServerIdentity(Object authGrant) {
        try {
            // 1. Get the current token first
            Field idField = authGrant.getClass().getDeclaredField("serverIdentityToken");
            idField.setAccessible(true);
            String currentToken = (String) idField.get(authGrant);
            
            // 2. Determine Issuer (Context > Token)
            String issuer = DualAuthContext.getIssuer();
            if (issuer == null && currentToken != null) {
                issuer = extractIssuerFromToken(currentToken);
                if (Boolean.getBoolean("dualauth.debug")) {
                    System.out.println("[DualAuth] AuthGrant: Recovered issuer from token: " + issuer);
                }
            }

            if (issuer != null && !isOfficialIssuer(issuer)) {
                // Try to get player UUID from context
                String playerUuid = DualAuthContext.getPlayerUuid();
                
                String rep = DualServerTokenManager.getIdentityTokenForIssuer(issuer, playerUuid);
                if (rep != null) {
                    idField.set(authGrant, rep);
                    if (Boolean.getBoolean("dualauth.debug")) {
                        System.out.println("[DualAuth] AuthGrant: Replaced server identity token for " + issuer + (playerUuid != null ? " (Player: " + playerUuid + ")" : ""));
                    }
                } else {
                     if (Boolean.getBoolean("dualauth.debug")) {
                        System.out.println("[DualAuth] AuthGrant: No replacement token found for issuer: " + issuer);
                    }
                }
            } else {
                 if (Boolean.getBoolean("dualauth.debug")) {
                     // Only log if we have a token but decided not to replace (official or unknown)
                     if (currentToken != null) {
                        System.out.println("[DualAuth] AuthGrant: Skipping replacement. Issuer: " + issuer + ", IsOfficial: " + isOfficialIssuer(issuer));
                     }
                }
            }
        } catch (Exception e) {
             if (Boolean.getBoolean("dualauth.debug")) {
                System.out.println("[DualAuth] AuthGrant: Error in maybeReplaceServerIdentity: " + e.getMessage());
            }
        }
    }


    private static String cachedServerUuid = null;
    public static String getServerUuid() {
        if (cachedServerUuid != null) return cachedServerUuid;
        String env = System.getenv("HYTALE_SERVER_AUDIENCE");
        if (env == null || env.isEmpty()) env = System.getenv("HYTALE_SERVER_ID");
        cachedServerUuid = (env != null && !env.isEmpty()) ? env : "00000000-0000-0000-0000-000000000001";
        return cachedServerUuid;
    }

    public static void setServerUuid(String uuid) {
        if (uuid != null && !uuid.isEmpty()) {
            cachedServerUuid = uuid;
        }
    }

    private static String cachedServerId = null;
    public static String getServerId() {
        if (cachedServerId != null) return cachedServerId;
        return getServerUuid(); // Fallback to UUID
    }

    public static void setServerId(String id) {
        if (id != null && !id.isEmpty()) {
            cachedServerId = id;
        }
    }

    private static String cachedServerName = null;
    public static String getServerName() {
        if (cachedServerName != null) return cachedServerName;
        // Try to get from environment first
        String envName = System.getenv("HYTALE_SERVER_NAME");
        if (envName != null && !envName.isEmpty()) {
            cachedServerName = envName;
            return cachedServerName;
        }
        // Fallback to serverId with prefix
        String serverId = getServerId();
        if (serverId != null && !serverId.isEmpty()) {
            cachedServerName = "Server-" + serverId.substring(0, 8);
            return cachedServerName;
        }
        // Ultimate fallback
        return "Multi-Issuer-Server";
    }

    public static void setServerName(String name) {
        if (name != null && !name.isEmpty()) {
            cachedServerName = name;
        }
    }

    public static String extractUsername(Object handler) {
        String[] possibleNames = {"username", "playerName", "name", "requestedName"};
        Class<?> clazz = handler.getClass();
        while (clazz != null && clazz != Object.class) {
            for (String fieldName : possibleNames) {
                try {
                    Field f = clazz.getDeclaredField(fieldName);
                    f.setAccessible(true);
                    Object value = f.get(handler);
                    if (value instanceof String && !((String) value).isEmpty()) return (String) value;
                } catch (Exception ignored) {}
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    public static String extractUsernameFromArgs(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg instanceof String) {
                String s = (String) arg;
                if (s.length() >= 3 && s.length() <= 16 && s.matches("^[a-zA-Z0-9_]+$")) return s;
            }
        }
        return null;
    }

    public static String extractIssuerFromToken(String token) {
        if (token == null || !token.contains(".")) return null;
        try {
            String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
            return extractJsonField(payload, "iss");
        } catch (Exception e) { return null; }
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

    public static String extractSubjectFromToken(String token) {
        if (token == null || !token.contains(".")) return null;
        try {
            String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
            int subIdx = payload.indexOf("\"sub\":");
            if (subIdx < 0) return null;
            int start = payload.indexOf('"', subIdx + 6) + 1;
            int end = payload.indexOf('"', start);
            return payload.substring(start, end);
        } catch (Exception e) { return null; }
    }

    public static String extractJwkFromToken(String token) {
        if (token == null || !token.contains(".")) return null;
        try {
            String header = new String(Base64.getUrlDecoder().decode(token.split("\\.")[0]));
            int idx = header.indexOf("\"jwk\":");
            if (idx < 0) return null;
            int start = header.indexOf('{', idx);
            int depth = 0;
            for (int i = start; i < header.length(); i++) {
                if (header.charAt(i) == '{') depth++;
                else if (header.charAt(i) == '}') {
                    depth--; if (depth == 0) return header.substring(start, i + 1);
                }
            }
        } catch (Exception e) {}
        return null;
    }

    public static boolean hasEmbeddedJwk(String token) {
        return extractJwkFromToken(token) != null;
    }

    public static boolean isOmniIssuerTrusted(String issuer) {
        if (DualAuthConfig.TRUST_ALL_ISSUERS) return true;
        if (issuer == null) return false;
        String norm = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
        for (String trusted : DualAuthConfig.TRUSTED_ISSUERS) {
            String t = trusted.trim();
            if (t.endsWith("/")) t = t.substring(0, t.length() - 1);
            if (norm.contains(t) || t.contains(norm)) return true;
        }
        return norm.contains(DualAuthConfig.F2P_BASE_DOMAIN);
    }
    public static int countDots(String s) {
        if (s == null) return 0;
        int c = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '.') c++;
        return c;
    }
}
