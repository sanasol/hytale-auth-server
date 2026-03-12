package ws.sanasol.dualauth.context;

import ws.sanasol.dualauth.agent.DualAuthConfig;
import ws.sanasol.dualauth.server.DualServerTokenManager;
import ws.sanasol.dualauth.embedded.EmbeddedJwkVerifier;
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
import com.nimbusds.jose.util.Base64URL;

/**
 * Global helper for authentication, reflection, and context processing.
 */
public class DualAuthHelper {
    private static final Logger LOGGER = Logger.getLogger("DualAuthAgent");

    // --- Ed25519 NATIVE UTILS ---
    private static final byte[] ED25519_X509_HEADER = { 0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03,
            0x21, 0x00 };

    public static PublicKey toNativePublic(OctetKeyPair okp) throws Exception {
        byte[] x = okp.getX().decode();
        byte[] encoded = new byte[ED25519_X509_HEADER.length + x.length];
        System.arraycopy(ED25519_X509_HEADER, 0, encoded, 0, ED25519_X509_HEADER.length);
        System.arraycopy(x, 0, encoded, ED25519_X509_HEADER.length, x.length);
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
    }

    // --- ISSUER HELPERS ---

    public static boolean isOfficialIssuer(String issuer) {
        if (issuer == null)
            return true;
        return issuer.contains(DualAuthConfig.OFFICIAL_DOMAIN);
    }

    public static boolean isOfficialIssuerStrict(String issuer) {
        // Patchers: only hytale.com is considered official for server identity purposes
        if (issuer == null)
            return false;
        return issuer.contains(DualAuthConfig.OFFICIAL_DOMAIN);
    }

    public static boolean isValidIssuer(String issuer) {
        if (issuer == null)
            return false;
        String norm = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
        if (DualAuthConfig.TRUST_ALL_ISSUERS)
            return true;
        if (isOfficialIssuer(norm))
            return true;
        if (norm.contains(DualAuthConfig.F2P_BASE_DOMAIN))
            return true;
        for (String trusted : DualAuthConfig.TRUSTED_ISSUERS) {
            if (norm.contains(trusted.trim()))
                return true;
        }
        return false;
    }

    public static boolean isPublicIssuer(String issuer) {
        if (issuer == null)
            return false;

        // 1. Check Blacklist
        if (DualAuthConfig.ISSUER_BLACKLIST.contains(issuer)) {
            if (Boolean.getBoolean("dualauth.debug")) {
                LOGGER.info("Issuer is blacklisted: " + issuer);
            }
            return false;
        }

        // 2. Omni-Auth: Check if CURRENT TOKEN has embedded JWK (not issuer-based)
        String currentTokenJwk = DualAuthContext.getJwk();
        if (currentTokenJwk != null && !currentTokenJwk.isEmpty()) {
            if (Boolean.getBoolean("dualauth.debug")) {
                System.out
                        .println("Current token has embedded JWK: not treating issuer as public: " + issuer);
            }
            return false; // This specific token is Omni-Auth, don't treat issuer as public
        }

        // 3. TRUSTED_ISSUERS: Treat as public (no detection needed)
        if (DualAuthConfig.TRUSTED_ISSUERS.contains(issuer)) {
            if (Boolean.getBoolean("dualauth.debug")) {
                LOGGER.info("Trusted issuer: treating as public (no detection): " + issuer);
            }
            return true;
        }

        // 4. Official issuers: detection only if forced
        if (isOfficialIssuer(issuer) && !DualAuthConfig.FORCE_DETECTION_FOR_ALL) {
            return false;
        }

        // 5. Check Cache (FAST PATH - no blocking)
        DualServerTokenManager.IssuerDetectionResult cached = DualServerTokenManager.getIssuerDetectionCache()
                .get(issuer);
        if (cached != null && !cached.isExpired()) {
            if (Boolean.getBoolean("dualauth.debug")) {
                LOGGER.info(
                        "Using cached detection for issuer: " + issuer + " -> public: " + cached.isPublic());
            }
            return cached.isPublic();
        }

        // 6. NEW: Background Detection (NON-BLOCKING for server)
        // Start detection in background and return conservative default
        startBackgroundDetection(issuer);

        if (Boolean.getBoolean("dualauth.debug")) {
            LOGGER.info("Starting background detection for issuer: " + issuer
                    + " (returning conservative default)");
        }

        // Conservative default: assume not public until detection completes
        return false;
    }

    /**
     * Starts issuer detection in background without blocking the current thread.
     * Results will be cached for future requests.
     */
    private static void startBackgroundDetection(String issuer) {
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                if (Boolean.getBoolean("dualauth.debug")) {
                    LOGGER.info("Background detection started for: " + issuer);
                }

                boolean isPublic = performJwksDetection(issuer);

                // Cache the result for future requests
                cacheDetectionResult(issuer, isPublic, null);

                if (Boolean.getBoolean("dualauth.debug")) {
                    LOGGER.info(
                            "Background detection completed for: " + issuer + " -> public: " + isPublic);
                }
            } catch (Exception e) {
                // Cache failure result
                cacheDetectionResult(issuer, false, e);
                if (Boolean.getBoolean("dualauth.debug")) {
                    System.out
                            .println("Background detection failed for: " + issuer + " -> " + e.getMessage());
                }
            }
        });
    }

    private static void cacheDetectionResult(String issuer, boolean isPublic, Exception error) {
        String jwksUrl = isPublic ? buildJwksUrl(issuer) : null;
        DualServerTokenManager.IssuerDetectionResult result = error != null
                ? new DualServerTokenManager.IssuerDetectionResult(error, DualAuthConfig.ISSUER_DETECTION_CACHE_TTL)
                : new DualServerTokenManager.IssuerDetectionResult(isPublic, jwksUrl,
                        DualAuthConfig.ISSUER_DETECTION_CACHE_TTL);

        DualServerTokenManager.getIssuerDetectionCache().put(issuer, result);
    }

    private static String buildJwksUrl(String issuer) {
        String baseUrl = issuer;
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        // Standard path
        return baseUrl + "/.well-known/jwks.json";
    }

    private static boolean performJwksDetection(String issuer) {
        try {
            String jwksUrl = buildJwksUrl(issuer);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(jwksUrl).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Hytale-Server/1.0");
            conn.setConnectTimeout(5000); // Fixed 5-second timeout
            conn.setReadTimeout(5000); // Fixed 5-second timeout
            conn.setInstanceFollowRedirects(true);

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                String responseBody = new String(conn.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
                if (isValidJwksResponse(responseBody)) {
                    if (Boolean.getBoolean("dualauth.debug")) {
                        System.out.println("Valid JWKS found at: " + jwksUrl);
                    }
                    return true;
                } else {
                    if (Boolean.getBoolean("dualauth.debug")) {
                        System.out.println("Invalid JWKS format at: " + jwksUrl);
                    }
                }
            } else {
                if (Boolean.getBoolean("dualauth.debug")) {
                    System.out.println("JWKS endpoint returned: " + responseCode + " for: " + jwksUrl);
                }
            }
        } catch (Exception e) {
            if (Boolean.getBoolean("dualauth.debug")) {
                System.out.println("JWKS detection failed for " + issuer + ": " + e.getMessage());
            }
        }
        return false;
    }

    private static boolean isValidJwksResponse(String responseBody) {
        try {
            if (responseBody == null || responseBody.trim().isEmpty())
                return false;
            if (!responseBody.contains("\"keys\""))
                return false;

            int keysIndex = responseBody.indexOf("\"keys\"");
            if (keysIndex == -1)
                return false;

            int arrayStart = responseBody.indexOf("[", keysIndex);
            int arrayEnd = responseBody.indexOf("]", arrayStart);

            return arrayStart != -1 && arrayEnd != -1 && arrayEnd > arrayStart + 1;
        } catch (Exception e) {
            return false;
        }
    }

    public static String getSessionUrlForIssuer(String issuer) {
        if (issuer == null || isOfficialIssuer(issuer))
            return DualAuthConfig.OFFICIAL_SESSION_URL;
        return issuer;
    }

    public static String getProfileUrlForIssuer(String issuer) {
        if (issuer == null || isOfficialIssuer(issuer))
            return "https://account-data.hytale.com";
        return issuer; // F2P: same server handles all endpoints
    }

    // --- JWT VALIDATION HELPERS ---

    private static Object verifyWithKeys(java.util.List<JWK> keys, SignedJWT signedJWT, ClassLoader cl, String methodName) {
        for (JWK key : keys) {
            if (key == null)
                continue;
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
                    if (key instanceof RSAKey)
                        pubKey = ((RSAKey) key).toPublicKey();
                    else if (key instanceof ECKey)
                        pubKey = ((ECKey) key).toPublicKey();
                    if (pubKey != null) {
                        com.nimbusds.jose.JWSVerifier verifier = factory.createJWSVerifier(signedJWT.getHeader(),
                                pubKey);
                        verified = signedJWT.verify(verifier);
                    }
                }
                if (verified)
                    return createJWTClaimsWrapper(cl, signedJWT.getJWTClaimsSet(), methodName, null);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    public static Object verifyTrustedToken(Object validatorInstance, String token, String methodName) {
        try {
            // OPTIMIZATION: If it has an embedded JWK, it's Omni-Auth and already failed
            // verifyAndGetClaims
            // if we are here. Skip to avoid Nimbus ParseException on private keys in
            // headers.
            if (hasEmbeddedJwk(token))
                return null;

            ClassLoader cl = validatorInstance.getClass().getClassLoader();

            Field cacheField = null;
            for (Field f : validatorInstance.getClass().getDeclaredFields()) {
                if (f.getType().getName().equals("com.nimbusds.jose.jwk.JWKSet")) {
                    cacheField = f;
                    break;
                }
            }
            if (cacheField == null)
                return null;
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

            if (jwkSet == null)
                return null;

            SignedJWT signedJWT = null;
            try {
                signedJWT = SignedJWT.parse(token);
            } catch (Exception e) {
                // Return null to fall back, but don't crash
                return null;
            }

            String kid = signedJWT.getHeader().getKeyID();
            java.util.List<JWK> keys = jwkSet.getKeys();

            // Try kid-matched keys first (there may be multiple with same kid from different issuers)
            if (kid != null) {
                java.util.List<JWK> kidMatches = new java.util.ArrayList<>();
                for (JWK k : keys) {
                    if (kid.equals(k.getKeyID())) kidMatches.add(k);
                }
                if (!kidMatches.isEmpty()) {
                    Object result = verifyWithKeys(kidMatches, signedJWT, cl, methodName);
                    if (result != null) return result;
                }
            }

            // Kid match failed or no kid — try all keys
            Object result = verifyWithKeys(keys, signedJWT, cl, methodName);
            if (result != null)
                return result;

            // Keys existed but none verified the token — likely pre-seeded/stale keys.
            // Force JWKS refresh and retry with merged keys.
            for (Method m : validatorInstance.getClass().getDeclaredMethods()) {
                if (m.getName().equals("fetchJwksFromService") || m.getName().equals("getJwkSet")) {
                    m.setAccessible(true);
                    if (m.getParameterCount() == 0) {
                        com.nimbusds.jose.jwk.JWKSet refreshedSet = (com.nimbusds.jose.jwk.JWKSet) m.invoke(validatorInstance);
                        if (refreshedSet != null && refreshedSet != jwkSet) {
                            java.util.List<JWK> refreshedKeys = refreshedSet.getKeys();
                            if (kid != null) {
                                java.util.List<JWK> kidMatches = new java.util.ArrayList<>();
                                for (JWK k : refreshedKeys) {
                                    if (kid.equals(k.getKeyID())) kidMatches.add(k);
                                }
                                if (!kidMatches.isEmpty()) {
                                    result = verifyWithKeys(kidMatches, signedJWT, cl, methodName);
                                    if (result != null) return result;
                                }
                            }
                            result = verifyWithKeys(refreshedKeys, signedJWT, cl, methodName);
                            if (result != null)
                                return result;
                        }
                        break;
                    }
                }
            }
        } catch (Exception e) {
        }
        return null;
    }

    public static Object createJWTClaimsWrapper(ClassLoader cl, com.nimbusds.jwt.JWTClaimsSet claims, String methodName,
            String descriptor) {
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
                if (lower.contains("identity") || lower.contains("offline")) {
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
                        System.out.println("Could not load preferred class: " + targetClassName);
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
                    try {
                        clazz = cl.loadClass(cn);
                        break;
                    } catch (Exception ignored) {
                    }
                }
            }

            if (clazz == null)
                return null;

            Object wrapper = clazz.getDeclaredConstructor().newInstance();

            // 4. Populate Fields via Reflection
            setF(wrapper, "issuer", claims.getIssuer());
            setF(wrapper, "subject", claims.getSubject());

            // Handle timestamps (Convert Date to Long seconds)
            if (claims.getIssueTime() != null)
                setF(wrapper, "issuedAt", claims.getIssueTime().getTime() / 1000L);
            if (claims.getExpirationTime() != null)
                setF(wrapper, "expiresAt", claims.getExpirationTime().getTime() / 1000L);
            if (claims.getNotBeforeTime() != null)
                setF(wrapper, "notBefore", claims.getNotBeforeTime().getTime() / 1000L);

            // Username field (present in JWTClaims and IdentityTokenClaims)
            try {
                String user = claims.getStringClaim("username");
                if (user == null)
                    user = claims.getStringClaim("name");
                if (user != null)
                    setF(wrapper, "username", user);
            } catch (Exception ignored) {
            }

            // Audience (JWTClaims)
            try {
                java.util.List<String> aud = claims.getAudience();
                if (aud != null && !aud.isEmpty()) {
                    setF(wrapper, "audience", aud.get(0));
                }
            } catch (Exception ignored) {
            }

            // Scope (Identity/Session)
            try {
                String scope = claims.getStringClaim("scope");
                if (scope != null) {
                    setF(wrapper, "scope", scope);
                } else {
                    // Default to client scope to ensure handshake proceeds
                    setF(wrapper, "scope", "hytale:client");
                }
            } catch (Exception ignored) {
            }

            return wrapper;
        } catch (Exception e) {
            System.err.println("Failed to wrap claims: " + e.getMessage());
            return null;
        }
    }

    // Stores the original expectedIssuer value so it can be restored after fallback
    private static volatile String originalExpectedIssuer = null;

    /**
     * Temporarily sets expectedIssuer on the validator for F2P fallback validation,
     * saving the original value for restoration. Also reads and caches the audience.
     *
     * The original method checks expectedIssuer against the token's issuer. For F2P tokens
     * falling back to the original method, we must temporarily match the issuer. But we
     * MUST restore the original value afterwards, or official clients will break.
     */
    public static void updateExpectedIssuer(Object validator, String issuer) {
        if (validator == null)
            return;
        try {
            Class<?> clazz = validator.getClass();
            while (clazz != null && clazz != Object.class) {
                for (Field f : clazz.getDeclaredFields()) {
                    String name = f.getName().toLowerCase();
                    // Issuer: temporarily set for F2P fallback, but save original for restore
                    if (issuer != null && (name.contains("expectedissuer") || name.equals("issuer"))) {
                        f.setAccessible(true);
                        String currentIssuer = (String) f.get(validator);

                        // Save original on first encounter
                        if (originalExpectedIssuer == null && currentIssuer != null && !currentIssuer.isEmpty()) {
                            originalExpectedIssuer = currentIssuer;
                            if (Boolean.getBoolean("dualauth.debug")) {
                                System.out.println("Saved original expectedIssuer: " + currentIssuer);
                            }
                        }

                        // Temporarily set to F2P issuer for fallback validation
                        String serverBaseDomain = DualAuthConfig.F2P_BASE_DOMAIN;
                        String issuerBaseDomain = extractBaseDomain(issuer);
                        String finalIssuer = issuer;
                        if (issuerBaseDomain != null && issuerBaseDomain.equals(serverBaseDomain) &&
                                !isIpAddress(issuer) && !isIpAddress(DualAuthConfig.F2P_ISSUER)) {
                            finalIssuer = DualAuthConfig.F2P_ISSUER;
                        }
                        f.set(validator, finalIssuer);
                        if (Boolean.getBoolean("dualauth.debug")) {
                            System.out.println("Temporarily set expectedIssuer to: " + finalIssuer
                                    + " (original: " + originalExpectedIssuer + ")");
                        }
                    }
                    // Audience: READ from validator and cache (never overwrite!)
                    // The server sets the correct audience in the JWTValidator constructor
                    // (from AuthConfig.getServerAudience() → ServerAuthManager.getServerSessionId()).
                    // Overwriting it with getServerId() corrupts it with a dummy UUID,
                    // causing "Invalid audience" when manual validation falls back to the
                    // original method (e.g., when early plugins like FixtaleEarly are present).
                    if (name.contains("expectedaudience") || name.equals("audience")) {
                        f.setAccessible(true);
                        Object currentAudience = f.get(validator);
                        if (currentAudience instanceof String && !((String) currentAudience).isEmpty()) {
                            String realAudience = (String) currentAudience;
                            // Cache the real server audience for federated token requests
                            if (cachedServerUuid == null
                                    || cachedServerUuid.equals("00000000-0000-0000-0000-000000000001")) {
                                setServerUuid(realAudience);
                                if (Boolean.getBoolean("dualauth.debug")) {
                                    System.out.println("Captured real server audience: " + realAudience
                                            + " from " + clazz.getSimpleName());
                                }
                            }
                        }
                    }
                }
                clazz = clazz.getSuperclass();
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Restores expectedIssuer to its original value after F2P validation completes.
     * This ensures official clients are not rejected by stale F2P issuer values.
     */
    public static void restoreExpectedIssuer(Object validator) {
        if (validator == null || originalExpectedIssuer == null)
            return;
        try {
            Class<?> clazz = validator.getClass();
            while (clazz != null && clazz != Object.class) {
                for (Field f : clazz.getDeclaredFields()) {
                    String name = f.getName().toLowerCase();
                    if (name.contains("expectedissuer") || name.equals("issuer")) {
                        f.setAccessible(true);
                        f.set(validator, originalExpectedIssuer);
                        if (Boolean.getBoolean("dualauth.debug")) {
                            System.out.println("Restored expectedIssuer to: " + originalExpectedIssuer);
                        }
                        return;
                    }
                }
                clazz = clazz.getSuperclass();
            }
        } catch (Exception ignored) {
        }
    }

    // --- REFLECTION UTILS ---

    public static void setF(Object obj, String name, Object val) {
        if (val == null)
            return;
        try {
            Field f = null;
            try {
                f = obj.getClass().getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                for (Field field : obj.getClass().getDeclaredFields()) {
                    if (field.getName().equalsIgnoreCase(name)) {
                        f = field;
                        break;
                    }
                }
            }
            if (f != null) {
                f.setAccessible(true);
                Class<?> type = f.getType();
                if (type == long.class || type == Long.class) {
                    if (val instanceof Number)
                        f.set(obj, ((Number) val).longValue());
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
        } catch (Exception ignored) {
        }
    }

    public static Object getF(Object obj, String name) {
        if (obj == null)
            return null;
        try {
            Field f = null;
            try {
                f = obj.getClass().getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                for (Field field : obj.getClass().getDeclaredFields()) {
                    if (field.getName().equalsIgnoreCase(name)) {
                        f = field;
                        break;
                    }
                }
            }
            if (f != null) {
                f.setAccessible(true);
                Object value = f.get(obj);
                return value;
            }
        } catch (Exception e) {
            if (Boolean.getBoolean("dualauth.debug")) {
                System.err.println("getF error for field " + name + ": " + e.getMessage());
            }
        }
        return null;
    }

    public static Field findUrlField(Class<?> clazz) {
        while (clazz != null && clazz != Object.class) {
            for (Field f : clazz.getDeclaredFields()) {
                String name = f.getName().toLowerCase();
                if (name.contains("sessionserviceurl") || name.contains("baseurl") || name.contains("serviceurl")
                        || (f.getType() == String.class && name.contains("url")))
                    return f;
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    public static void updateCacheTimestamp(Object thiz) {
        try {
            for (Field f : thiz.getClass().getDeclaredFields()) {
                String name = f.getName().toLowerCase();
                if (name.contains("refresh") || name.contains("cache") || name.contains("expiry")
                        || name.contains("updated") || name.contains("last")) {
                    f.setAccessible(true);
                    if (f.getType().equals(long.class))
                        f.setLong(thiz, System.currentTimeMillis());
                    else if (f.getType().getName().equals("java.time.Instant"))
                        f.set(thiz, java.time.Instant.now());
                }
            }
        } catch (Exception ignored) {
        }
    }

    public static void maybeReplaceServerIdentity(Object authGrant) {
        try {
            Field idField = authGrant.getClass().getDeclaredField("serverIdentityToken");
            idField.setAccessible(true);
            String currentToken = (String) idField.get(authGrant);

            // Determine client issuer. Multiple strategies because AuthGrant
            // serialization happens on an async callback thread where ThreadLocal is empty.
            String clientIssuer = null;

            // Strategy 1: Player registry (most reliable — set during token validation)
            // Extract player UUID from the auth grant's subject claim
            try {
                Field grantField = authGrant.getClass().getDeclaredField("authorizationGrant");
                grantField.setAccessible(true);
                String grantToken = (String) grantField.get(authGrant);
                if (grantToken != null) {
                    String playerUuid = extractClaimFromToken(grantToken, "sub");
                    if (playerUuid != null) {
                        DualAuthContext.PlayerAuthInfo info = DualAuthContext.getPlayerInfo(playerUuid);
                        if (info != null && info.issuer != null) {
                            clientIssuer = info.issuer;
                        }
                    }
                    // Fallback: auth grant JWT issuer (only correct for F2P, not third-party)
                    if (clientIssuer == null) {
                        clientIssuer = extractIssuerFromToken(grantToken);
                    }
                }
            } catch (Exception ignored) {}

            // Strategy 2: ThreadLocal (may be correct if on same thread)
            if (clientIssuer == null) {
                clientIssuer = DualAuthContext.getIssuer();
            }
            // Strategy 3: extract from current server identity token
            if (clientIssuer == null && currentToken != null) {
                clientIssuer = extractIssuerFromToken(currentToken);
            }

            if (Boolean.getBoolean("dualauth.debug")) {
                System.out.println("AuthGrant: clientIssuer=" + clientIssuer
                        + " (ThreadLocal=" + DualAuthContext.getIssuer() + ")");
            }

            // Omni-Auth: replace with self-signed token
            if (clientIssuer != null && DualAuthContext.isOmni()) {
                String omniToken = EmbeddedJwkVerifier.createDynamicIdentityToken(clientIssuer);
                if (omniToken != null) {
                    idField.set(authGrant, omniToken);
                    if (Boolean.getBoolean("dualauth.debug")) {
                        LOGGER.info("Replaced with Omni-Auth server identity token");
                    }
                    return;
                }
            }

            // Official client: keep the native server identity token unchanged
            if (clientIssuer == null || isOfficialIssuerStrict(clientIssuer)) {
                if (Boolean.getBoolean("dualauth.debug")) {
                    System.out.println("AuthGrant: Keeping native serverIdentityToken for official client"
                            + " (issuer: " + clientIssuer + ")");
                }
                return;
            }

            // Non-official client: replace server identity token with one matching client's issuer
            if (Boolean.getBoolean("dualauth.debug")) {
                System.out.println("AuthGrant: Non-official client detected, getting token for issuer: " + clientIssuer);
            }

            String correctedToken = DualServerTokenManager.getIdentityTokenForIssuer(clientIssuer,
                    DualAuthContext.getPlayerUuid());
            if (correctedToken != null) {
                idField.set(authGrant, correctedToken);
                if (Boolean.getBoolean("dualauth.debug")) {
                    String finalIssuer = extractIssuerFromToken(correctedToken);
                    System.out.println("AuthGrant: Replaced serverIdentityToken -> " + finalIssuer
                            + " (len=" + correctedToken.length() + ")");
                }
            } else if (isThirdPartyIssuer(clientIssuer)) {
                // Third-party issuer (e.g. Butter): no identity token available,
                // strip server identity so client doesn't try to validate official token
                idField.set(authGrant, "");
                System.out.println("[DualAuthAgent] AuthGrant: Stripped server identity for third-party issuer: " + clientIssuer);
            } else {
                if (Boolean.getBoolean("dualauth.debug")) {
                    System.out.println("AuthGrant: No F2P token found for issuer " + clientIssuer
                            + " - keeping native token");
                }
            }

        } catch (Exception e) {
            if (Boolean.getBoolean("dualauth.debug")) {
                System.out.println("AuthGrant: Error in maybeReplaceServerIdentity: " + e.getMessage());
            }
        }
    }

    private static String cachedServerUuid = null;

    public static String getServerUuid() {
        if (cachedServerUuid != null)
            return cachedServerUuid;
        String env = System.getenv("HYTALE_SERVER_AUDIENCE");
        if (env == null || env.isEmpty())
            env = System.getenv("HYTALE_SERVER_ID");
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
        if (cachedServerId != null)
            return cachedServerId;
        return getServerUuid(); // Fallback to UUID
    }

    public static void setServerId(String id) {
        if (id != null && !id.isEmpty()) {
            cachedServerId = id;
        }
    }

    private static String cachedServerName = null;

    public static String getServerName() {
        if (cachedServerName != null)
            return cachedServerName;
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
        String[] possibleNames = { "username", "playerName", "name", "requestedName" };
        Class<?> clazz = handler.getClass();
        while (clazz != null && clazz != Object.class) {
            for (String fieldName : possibleNames) {
                try {
                    Field f = clazz.getDeclaredField(fieldName);
                    f.setAccessible(true);
                    Object value = f.get(handler);
                    if (value instanceof String && !((String) value).isEmpty())
                        return (String) value;
                } catch (Exception ignored) {
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    public static String extractUsernameFromArgs(Object[] args) {
        if (args == null)
            return null;
        for (Object arg : args) {
            if (arg instanceof String) {
                String s = (String) arg;
                if (s.length() >= 3 && s.length() <= 16 && s.matches("^[a-zA-Z0-9_]+$"))
                    return s;
            }
        }
        return null;
    }

    public static String extractIssuerFromToken(String token) {
        if (token == null || !token.contains("."))
            return null;
        try {
            String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
            return extractJsonField(payload, "iss");
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractJsonField(String json, String fieldName) {
        if (json == null)
            return null;
        try {
            // More lenient parsing for whitespaces: "key" : "value"
            String pattern = "\"" + fieldName + "\"";
            int keyStart = json.indexOf(pattern);
            if (keyStart < 0)
                return null;

            int colonPos = json.indexOf(":", keyStart + pattern.length());
            if (colonPos < 0)
                return null;

            int quoteStart = json.indexOf("\"", colonPos + 1);
            if (quoteStart < 0)
                return null;

            int quoteEnd = json.indexOf("\"", quoteStart + 1);
            if (quoteEnd < 0)
                return null;

            return json.substring(quoteStart + 1, quoteEnd);
        } catch (Exception e) {
            return null;
        }
    }

    public static String extractSubjectFromToken(String token) {
        return extractClaimFromToken(token, "sub");
    }

    /**
     * Extract any string claim from a JWT token payload without full parsing.
     * Uses simple JSON string search — works for string-valued claims.
     */
    public static String extractClaimFromToken(String token, String claimName) {
        if (token == null || !token.contains(".") || claimName == null)
            return null;
        try {
            String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
            String key = "\"" + claimName + "\":";
            int idx = payload.indexOf(key);
            if (idx < 0)
                return null;
            int start = payload.indexOf('"', idx + key.length()) + 1;
            int end = payload.indexOf('"', start);
            if (start <= 0 || end < start)
                return null;
            return payload.substring(start, end);
        } catch (Exception e) {
            return null;
        }
    }

    public static String extractJwkFromToken(String token) {
        if (token == null || !token.contains("."))
            return null;
        try {
            // Use Base64URL from Nimbus to handle unpadded input safely
            String header = new String(Base64URL.from(token.split("\\.")[0]).decode());
            
            // Relaxed check (allow spaces before colon)
            int idx = header.indexOf("\"jwk\"");
            if (idx < 0)
                return null;
            
            int start = header.indexOf('{', idx);
            if (start < 0)
                return null;
                
            int depth = 0;
            for (int i = start; i < header.length(); i++) {
                if (header.charAt(i) == '{')
                    depth++;
                else if (header.charAt(i) == '}') {
                    depth--;
                    if (depth == 0)
                        return header.substring(start, i + 1);
                }
            }
        } catch (Exception e) {
        }
        return null;
    }

    public static boolean hasEmbeddedJwk(String token) {
        return extractJwkFromToken(token) != null;
    }

    public static boolean hasEmbeddedJwkForIssuer(String issuer) {
        // Check if current context has embedded JWK for this issuer
        String currentIssuer = DualAuthContext.getIssuer();
        String currentJwk = DualAuthContext.getJwk();
        return currentJwk != null && !currentJwk.isEmpty() &&
                issuer != null && issuer.equals(currentIssuer);
    }

    /**
     * Extracts the base domain from an issuer URL (e.g., "https://auth.sanasol.ws"
     * -> "sanasol.ws")
     * This is used for flexible domain matching. NOT APPLIED to IP addresses.
     */
    public static String extractBaseDomain(String domain) {
        if (domain == null || domain.isEmpty()) {
            return domain;
        }

        // Don't extract base domain from IP addresses
        if (isIpAddress(domain)) {
            return domain;
        }

        // Handle URLs: extract hostname from "https://host:port/path"
        String hostname = domain;
        if (domain.startsWith("http://") || domain.startsWith("https://")) {
            hostname = domain.substring(domain.indexOf("://") + 3);
            int slashIndex = hostname.indexOf('/');
            if (slashIndex > 0) {
                hostname = hostname.substring(0, slashIndex);
            }
            int colonIndex = hostname.indexOf(':');
            if (colonIndex > 0) {
                hostname = hostname.substring(0, colonIndex);
            }
        }

        // Extract base domain from hostname
        int firstDot = hostname.indexOf('.');
        if (firstDot > 0 && !Character.isDigit(hostname.charAt(0))) {
            String afterFirstDot = hostname.substring(firstDot + 1);
            if (afterFirstDot.indexOf('.') > 0) {
                return afterFirstDot;
            }
        }
        return hostname;
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

    public static boolean isOmniIssuerTrusted(String issuer) {
        if (DualAuthConfig.TRUST_ALL_ISSUERS)
            return true;
        if (issuer == null)
            return false;
        String norm = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
        for (String trusted : DualAuthConfig.TRUSTED_ISSUERS) {
            String t = trusted.trim();
            if (t.endsWith("/"))
                t = t.substring(0, t.length() - 1);
            if (norm.contains(t) || t.contains(norm))
                return true;
        }
        return norm.contains(DualAuthConfig.F2P_BASE_DOMAIN);
    }

    public static int countDots(String s) {
        if (s == null)
            return 0;
        int c = 0;
        for (int i = 0; i < s.length(); i++)
            if (s.charAt(i) == '.')
                c++;
        return c;
    }

    // --- THIRD-PARTY TOKEN ACCEPTANCE (bypass signature verification) ---

    /**
     * Checks if issuer is a third-party (not official, not F2P, not Omni-Auth).
     * Used to accept tokens from launchers like Butter whose JWKS doesn't match
     * their signing key.
     */
    public static boolean isThirdPartyIssuer(String issuer) {
        if (issuer == null) return false;
        if (isOfficialIssuer(issuer)) return false;
        if (DualAuthConfig.F2P_BASE_DOMAIN != null && issuer.contains(DualAuthConfig.F2P_BASE_DOMAIN)) return false;
        return true;
    }

    /**
     * Accept a token from a third-party issuer without signature verification.
     * Parses the JWT payload to extract claims and creates a wrapper that the
     * server accepts. Only called when:
     * - Issuer is valid (trusted or TRUST_ALL_ISSUERS=true)
     * - Issuer is third-party (not official, not F2P)
     * - Normal signature verification already failed
     *
     * This enables launchers like Butter whose published JWKS doesn't match
     * their signing key to still connect to DualAuth servers.
     */
    public static Object acceptThirdPartyToken(Object validatorInstance, String token, String methodName) {
        try {
            String issuer = DualAuthContext.getIssuer();
            if (!isThirdPartyIssuer(issuer)) return null;

            // Parse claims without verification
            com.nimbusds.jwt.SignedJWT signedJWT = com.nimbusds.jwt.SignedJWT.parse(token);
            com.nimbusds.jwt.JWTClaimsSet claims = signedJWT.getJWTClaimsSet();
            if (claims == null || claims.getSubject() == null) return null;

            // Check token expiration (we still enforce TTL even without sig check)
            java.util.Date exp = claims.getExpirationTime();
            if (exp != null && exp.before(new java.util.Date())) {
                System.out.println("[DualAuthAgent] Third-party token expired for issuer: " + issuer);
                return null;
            }

            System.out.println("[DualAuthAgent] ACCEPTING third-party token without signature verification" +
                " (issuer: " + issuer + ", sub: " + claims.getSubject() + ")");

            // Set context so shouldBypassMutualAuth() works downstream
            DualAuthContext.setPlayerUuid(claims.getSubject());
            String name = (String) claims.getClaim("name");
            if (name == null) name = (String) claims.getClaim("username");
            if (name == null) name = (String) claims.getClaim("nickname");
            if (name != null) DualAuthContext.setUsername(name);

            ClassLoader cl = validatorInstance.getClass().getClassLoader();
            return createJWTClaimsWrapper(cl, claims, methodName, null);
        } catch (Exception e) {
            System.out.println("[DualAuthAgent] Third-party token acceptance failed: " + e.getMessage());
            return null;
        }
    }

    // --- HANDSHAKE BYPASS (Development Flow for third-party issuers) ---

    /**
     * Determines if the current connection should bypass mutual authentication
     * and fall back to development flow. This is needed for third-party issuers
     * (like Butter) that don't run their own session service and can't provide
     * server identity tokens for the auth-grant exchange.
     *
     * Returns true if:
     * - Issuer is non-official (not hytale.com)
     * - Token is NOT Omni-Auth (Omni handles its own bypass via OfflineBypassAdvice)
     * - Issuer is NOT F2P (F2P has its own session service)
     */
    public static boolean shouldBypassMutualAuth() {
        String issuer = DualAuthContext.getIssuer();
        if (issuer == null) return false;

        // Official issuer: normal flow
        if (isOfficialIssuer(issuer)) return false;

        // Omni-Auth: handled by OfflineBypassAdvice already
        if (DualAuthContext.isOmni()) return false;

        // F2P issuer (our own): has session service, normal flow
        if (DualAuthContext.isF2P()) return false;

        // Third-party issuers (e.g. Butter): use normal AuthGrant flow
        // but with stripped server identity (handled by maybeReplaceServerIdentity).
        // ConnectAccept bypass doesn't work — clients don't support dev mode.
        if (isThirdPartyIssuer(issuer)) return false;

        // Third-party issuer (e.g. Butter): bypass mutual auth
        if (Boolean.getBoolean("dualauth.debug")) {
            LOGGER.info("HandshakeBypass: Third-party issuer detected, bypassing mutual auth: " + issuer);
        }
        return true;
    }

    /**
     * Bypasses mutual authentication by mimicking InitialPacketHandler's development flow.
     * Sends ConnectAccept(null) to the client and installs PasswordPacketHandler.
     *
     * @param handshakeHandler the HandshakeHandler instance (AuthenticationPacketHandler)
     * @return true if bypass succeeded, false if it failed (should fall back to normal flow)
     */
    public static boolean bypassToDevFlow(Object handshakeHandler) {
        try {
            String issuer = DualAuthContext.getIssuer();
            String username = DualAuthContext.getUsername();
            String uuid = null;
            Object playerUuid = getF(handshakeHandler, "playerUuid");
            if (playerUuid != null) uuid = playerUuid.toString();

            System.out.println("[DualAuthAgent] HandshakeBypass: Bypassing mutual auth for " +
                (username != null ? username : "unknown") + " (issuer: " + issuer + ")");

            // 1. Get the channel via getChannel() method (channel is in channels[0] array in PacketHandler)
            Object channel = null;
            try {
                Method getChannel = findMethodInHierarchy(handshakeHandler.getClass(), "getChannel");
                if (getChannel != null) {
                    getChannel.setAccessible(true);
                    channel = getChannel.invoke(handshakeHandler);
                }
            } catch (Exception e) {
                System.err.println("[DualAuthAgent] HandshakeBypass: getChannel() failed: " + e.getMessage());
            }
            // Fallback: try 'channels' array field (PacketHandler stores channel in channels[0])
            if (channel == null) {
                Object channels = getFieldFromHierarchy(handshakeHandler, "channels");
                if (channels != null && channels.getClass().isArray()) {
                    Object[] arr = (Object[]) channels;
                    if (arr.length > 0) channel = arr[0];
                }
            }
            if (channel == null) {
                System.err.println("[DualAuthAgent] HandshakeBypass: Could not find channel");
                return false;
            }

            // 2. Get ProtocolVersion and other fields needed for PasswordPacketHandler
            Object protocolVersion = getFieldFromHierarchy(handshakeHandler, "protocolVersion");
            Object language = getFieldFromHierarchy(handshakeHandler, "language");
            Object referralData = getFieldFromHierarchy(handshakeHandler, "referralData");
            Object referralSource = getFieldFromHierarchy(handshakeHandler, "referralSource");

            // 3. Create ConnectAccept packet with null password challenge
            //    ConnectAccept is in com.hypixel.hytale.protocol.packets.auth
            Class<?> connectAcceptClass = findClass(handshakeHandler.getClass().getClassLoader(),
                "com.hypixel.hytale.protocol.packets.auth.ConnectAccept");
            if (connectAcceptClass == null) {
                System.err.println("[DualAuthAgent] HandshakeBypass: Could not find ConnectAccept class");
                return false;
            }

            // ConnectAccept(byte[] passwordChallenge)
            Object connectAccept = null;
            for (Constructor<?> c : connectAcceptClass.getDeclaredConstructors()) {
                Class<?>[] params = c.getParameterTypes();
                if (params.length == 1 && params[0] == byte[].class) {
                    c.setAccessible(true);
                    connectAccept = c.newInstance((Object) null);
                    break;
                }
            }
            if (connectAccept == null) {
                // Try no-arg constructor + set field
                try {
                    Constructor<?> c = connectAcceptClass.getDeclaredConstructor();
                    c.setAccessible(true);
                    connectAccept = c.newInstance();
                } catch (Exception e) {
                    System.err.println("[DualAuthAgent] HandshakeBypass: Could not create ConnectAccept: " + e.getMessage());
                    return false;
                }
            }

            // 4. Send ConnectAccept via channel.writeAndFlush()
            Method writeAndFlush = channel.getClass().getMethod("writeAndFlush", Object.class);
            writeAndFlush.invoke(channel, connectAccept);
            System.out.println("[DualAuthAgent] HandshakeBypass: Sent ConnectAccept (no password challenge)");

            // 5. Create PasswordPacketHandler
            //    PasswordPacketHandler(Channel, ProtocolVersion, String language, UUID, String username,
            //                          byte[] referralData, HostAddress referralSource,
            //                          byte[] passwordChallenge, SetupHandlerSupplier)
            Class<?> passwordHandlerClass = findClass(handshakeHandler.getClass().getClassLoader(),
                "com.hypixel.hytale.server.core.io.handlers.login.PasswordPacketHandler");
            if (passwordHandlerClass == null) {
                System.err.println("[DualAuthAgent] HandshakeBypass: Could not find PasswordPacketHandler class");
                return false;
            }

            // Find the SetupHandlerSupplier from AuthenticationPacketHandler.authHandlerSupplier
            // or from InitialPacketHandler
            Object setupHandlerSupplier = findSetupHandlerSupplier(handshakeHandler);

            Object passwordHandler = null;
            for (Constructor<?> c : passwordHandlerClass.getDeclaredConstructors()) {
                Class<?>[] params = c.getParameterTypes();
                if (params.length >= 8) {
                    c.setAccessible(true);
                    try {
                        passwordHandler = c.newInstance(
                            channel, protocolVersion, language,
                            playerUuid, username,
                            referralData, referralSource,
                            null,  // passwordChallenge = null (no password)
                            setupHandlerSupplier
                        );
                        break;
                    } catch (Exception e) {
                        if (Boolean.getBoolean("dualauth.debug")) {
                            System.out.println("[DualAuthAgent] HandshakeBypass: Constructor attempt failed: " + e.getMessage());
                        }
                    }
                }
            }

            if (passwordHandler == null) {
                System.err.println("[DualAuthAgent] HandshakeBypass: Could not create PasswordPacketHandler");
                return false;
            }

            // 6. Install via NettyUtil.setChannelHandler(channel, passwordHandler)
            Class<?> nettyUtilClass = findClass(handshakeHandler.getClass().getClassLoader(),
                "com.hypixel.hytale.server.core.io.netty.NettyUtil");
            if (nettyUtilClass == null) {
                System.err.println("[DualAuthAgent] HandshakeBypass: Could not find NettyUtil class");
                return false;
            }

            Method setChannelHandler = null;
            for (Method m : nettyUtilClass.getDeclaredMethods()) {
                if (m.getName().equals("setChannelHandler") && m.getParameterCount() == 2) {
                    setChannelHandler = m;
                    break;
                }
            }
            if (setChannelHandler == null) {
                System.err.println("[DualAuthAgent] HandshakeBypass: Could not find setChannelHandler method");
                return false;
            }

            setChannelHandler.setAccessible(true);
            setChannelHandler.invoke(null, channel, passwordHandler);
            System.out.println("[DualAuthAgent] HandshakeBypass: Installed PasswordPacketHandler - development flow active");

            return true;
        } catch (Exception e) {
            System.err.println("[DualAuthAgent] HandshakeBypass: Error: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Gets a field value by searching the full class hierarchy.
     */
    private static Object getFieldFromHierarchy(Object obj, String fieldName) {
        Class<?> clazz = obj.getClass();
        while (clazz != null && clazz != Object.class) {
            try {
                Field f = clazz.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private static Method findMethodInHierarchy(Class<?> clazz, String methodName) {
        while (clazz != null && clazz != Object.class) {
            try {
                return clazz.getDeclaredMethod(methodName);
            } catch (NoSuchMethodException e) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }

    /**
     * Finds the SetupHandlerSupplier needed by PasswordPacketHandler.
     * AuthenticationPacketHandler has an authHandlerSupplier field that implements this.
     */
    private static Object findSetupHandlerSupplier(Object handler) {
        // Try authHandlerSupplier on AuthenticationPacketHandler
        Object supplier = getFieldFromHierarchy(handler, "authHandlerSupplier");
        if (supplier != null) return supplier;

        // Try setupHandlerSupplier directly
        supplier = getFieldFromHierarchy(handler, "setupHandlerSupplier");
        if (supplier != null) return supplier;

        // Fallback: search for any field implementing a supplier-like interface
        Class<?> clazz = handler.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field f : clazz.getDeclaredFields()) {
                String name = f.getName().toLowerCase();
                if (name.contains("supplier") || name.contains("handler")) {
                    try {
                        f.setAccessible(true);
                        Object val = f.get(handler);
                        if (val != null && !val.getClass().getName().startsWith("io.netty"))
                            return val;
                    } catch (Exception ignored) {}
                }
            }
            clazz = clazz.getSuperclass();
        }

        if (Boolean.getBoolean("dualauth.debug")) {
            LOGGER.warning("HandshakeBypass: Could not find SetupHandlerSupplier");
        }
        return null;
    }

    /**
     * Finds a class by name from the given classloader.
     */
    private static Class<?> findClass(ClassLoader cl, String className) {
        try {
            return Class.forName(className, false, cl);
        } catch (ClassNotFoundException e) {
            // Try with Thread context classloader
            try {
                return Class.forName(className, false, Thread.currentThread().getContextClassLoader());
            } catch (ClassNotFoundException e2) {
                return null;
            }
        }
    }
}
