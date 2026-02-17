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

    private static boolean detectIssuerPublicAsync(String issuer) {
        java.util.concurrent.CompletableFuture<Boolean> detectionFuture = java.util.concurrent.CompletableFuture
                .supplyAsync(() -> performJwksDetection(issuer));

        try {
            // Fixed timeout for JWKS detection (5 seconds)
            boolean isPublic = detectionFuture.get(5000, java.util.concurrent.TimeUnit.MILLISECONDS);

            // Cache result from future
            cacheDetectionResult(issuer, isPublic, null);

            if (Boolean.getBoolean("dualauth.debug")) {
                LOGGER.info("Detected issuer: " + issuer + " -> public: " + isPublic);
            }
            return isPublic;
        } catch (java.util.concurrent.TimeoutException e) {
            cacheDetectionResult(issuer, false, e);
            if (Boolean.getBoolean("dualauth.debug")) {
                LOGGER.info("Detection timeout for issuer: " + issuer + " -> assuming not public");
            }
            return false;
        } catch (Exception e) {
            cacheDetectionResult(issuer, false, e);
            if (Boolean.getBoolean("dualauth.debug")) {
                System.out.println("Detection error for issuer: " + issuer + " -> " + e.getMessage());
            }
            return false;
        }
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

    // --- JWT VALIDATION HELPERS ---

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

            if (kid != null) {
                JWK match = jwkSet.getKeyByKeyId(kid);
                if (match != null)
                    keys = java.util.Collections.singletonList(match);
            }

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

    public static void updateExpectedIssuer(Object validator, String issuer) {
        if (validator == null)
            return;
        try {
            Class<?> clazz = validator.getClass();
            while (clazz != null && clazz != Object.class) {
                for (Field f : clazz.getDeclaredFields()) {
                    String name = f.getName().toLowerCase();
                    // Update Issuer - use base domain matching for flexibility
                    if (issuer != null && (name.contains("expectedissuer") || name.equals("issuer"))) {
                        f.setAccessible(true);

                        // Check if we should use base domain matching
                        String serverBaseDomain = DualAuthConfig.F2P_BASE_DOMAIN;
                        String issuerBaseDomain = extractBaseDomain(issuer);

                        String finalIssuer = issuer;
                        // Only apply base domain matching if neither is an IP address
                        if (issuerBaseDomain != null && issuerBaseDomain.equals(serverBaseDomain) &&
                                !isIpAddress(issuer) && !isIpAddress(DualAuthConfig.F2P_ISSUER)) {
                            // Use the server's expected issuer format for compatibility
                            finalIssuer = DualAuthConfig.F2P_ISSUER;
                            if (Boolean.getBoolean("dualauth.debug")) {
                                System.out.println("Using base domain matching: " + issuer + " -> "
                                        + finalIssuer + " (base domain: " + issuerBaseDomain + ")");
                            }
                        }

                        f.set(validator, finalIssuer);
                        if (Boolean.getBoolean("dualauth.debug")) {
                            System.out.println("Updated expectedIssuer to: " + finalIssuer + " in "
                                    + clazz.getSimpleName());
                        }
                    }
                    // Update Audience
                    String sId = getServerId();
                    if (sId != null && (name.contains("expectedaudience") || name.equals("audience"))) {
                        f.setAccessible(true);
                        f.set(validator, sId);
                        if (Boolean.getBoolean("dualauth.debug")) {
                            System.out.println(
                                    "Updated expectedAudience to: " + sId + " in " + clazz.getSimpleName());
                        }
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
            // 1. Get the current token first
            Field idField = authGrant.getClass().getDeclaredField("serverIdentityToken");
            idField.setAccessible(true);
            String currentToken = (String) idField.get(authGrant);

            // 2. Determine Issuer (Context > Token)
            String issuer = DualAuthContext.getIssuer();
            if (issuer == null && currentToken != null) {
                issuer = extractIssuerFromToken(currentToken);
            }

            // Omni-Auth replace with self-signed token
            if (issuer != null && DualAuthContext.isOmni()) {
                String omniToken = EmbeddedJwkVerifier.createDynamicIdentityToken(issuer);
                if (omniToken != null) {
                    idField.set(authGrant, omniToken);
                    if (Boolean.getBoolean("dualauth.debug")) {
                    LOGGER.info("Replaced with Omni-Auth server identity token");
                }
                    return;
                }
            }

            // 3. PATCHER STRATEGY: For non-official issuers, we MUST provide the F2P
            // identity token
            // so the client can validate mutual auth against the F2P JWKS.
            // Sending null causes "Server did not provide identity token" error on client.
            if (issuer != null && !isOfficialIssuerStrict(issuer)) {
                // CRITICAL FIX: Use the exact issuer from the client's token for compatibility
                // The client expects the server identity token to have the same issuer as their
                // user token
                String clientIssuer = issuer; // Keep the exact issuer the client used

                if (Boolean.getBoolean("dualauth.debug")) {
                    System.out.println("Server Identity: Attempting to get token for exact client issuer: "
                            + clientIssuer);
                }

                // Get token with client's exact issuer from TokenManager (this will attempt
                // federated fetch)
                String correctedToken = DualServerTokenManager.getIdentityTokenForIssuer(clientIssuer,
                        DualAuthContext.getPlayerUuid());
                if (correctedToken != null) {
                    // Extract the actual issuer from the retrieved token
                    String actualTokenIssuer = extractIssuerFromToken(correctedToken);

                    if (Boolean.getBoolean("dualauth.debug")) {
                        System.out.println("Server Identity: Got token with actual issuer: "
                                + actualTokenIssuer + " (expected: " + clientIssuer + ")");
                    }

                    // IMPORTANT: Do not modify the token if the issuer doesn't match, as this
                    // breaks the signature
                    // Instead, we should ensure the token was obtained with the correct issuer from
                    // the start
                    if (actualTokenIssuer != null && !actualTokenIssuer.equals(clientIssuer)) {
                        if (Boolean.getBoolean("dualauth.debug")) {
                            System.out.println(
                                    "Server Identity: Token issuer mismatch - expected " + clientIssuer +
                                            " but got " + actualTokenIssuer
                                            + " - This may cause signature verification issues if we modify it");
                        }

                        // For now, we'll proceed with the token we got, but in the future we should
                        // ensure
                        // the token is obtained from the correct endpoint to begin with
                        idField.set(authGrant, correctedToken);
                    } else {
                        idField.set(authGrant, correctedToken);
                    }

                    if (Boolean.getBoolean("dualauth.debug")) {
                        String finalTokenIssuer = extractIssuerFromToken(correctedToken);
                        System.out.println("AuthGrant: Replaced serverIdentityToken for issuer: " + issuer
                                + " -> " + finalTokenIssuer + " (len=" + correctedToken.length() + ")");

                        // DEBUG: Verify the token was actually set
                        try {
                            String verifyToken = (String) idField.get(authGrant);
                            String verifyIssuer = extractIssuerFromToken(verifyToken);
                            System.out.println("DEBUG: Final token in AuthGrant: " + verifyIssuer);
                        } catch (Exception e) {
                            System.out.println("DEBUG: Error verifying final token: " + e.getMessage());
                        }
                    }
                } else {
                    // No token found for exact issuer - skip base domain fallback to prevent
                    // signature issues
                    // Each issuer should get its own properly signed token from its own endpoint
                    if (Boolean.getBoolean("dualauth.debug")) {
                        System.out.println("Server Identity: No token found for exact issuer " + clientIssuer
                                + " - skipping base domain fallback to preserve signature integrity");
                    }

                    // Only suppress if we truly have no token to give (fallback behavior)
                    idField.set(authGrant, null);
                    if (Boolean.getBoolean("dualauth.debug")) {
                        System.out.println(
                                "AuthGrant: Suppressed serverIdentityToken (no replacement found) for issuer: "
                                        + issuer);
                    }
                }
                return;
            }

            // 4. For official issuers, keep existing token
            if (issuer != null && isOfficialIssuerStrict(issuer)) {
                if (Boolean.getBoolean("dualauth.debug")) {
                    System.out.println(
                            "AuthGrant: Keeping serverIdentityToken for official issuer: " + issuer);
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
        if (token == null || !token.contains("."))
            return null;
        try {
            String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
            int subIdx = payload.indexOf("\"sub\":");
            if (subIdx < 0)
                return null;
            int start = payload.indexOf('"', subIdx + 6) + 1;
            int end = payload.indexOf('"', start);
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
}
