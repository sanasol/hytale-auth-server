package ws.sanasol.dualauth.agent.transformers;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import ws.sanasol.dualauth.embedded.EmbeddedJwkVerifier;
import ws.sanasol.dualauth.fetcher.DualJwksFetcher;
import ws.sanasol.dualauth.context.DualAuthContext;
import ws.sanasol.dualauth.context.DualAuthHelper;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * Transforms JWTValidator to support:
 * 1. Omni-Auth bypass (embedded JWK verification)
 * 2. Dual JWKS fetching (merges Official + F2P + Dynamic sources)
 * 3. Context propagation for issuer-based routing
 */
public class JWTValidatorTransformer implements net.bytebuddy.agent.builder.AgentBuilder.Transformer {

    @Override
    public DynamicType.Builder<?> transform(DynamicType.Builder<?> builder, TypeDescription typeDescription, ClassLoader classLoader, net.bytebuddy.utility.JavaModule module, java.security.ProtectionDomain pd) {
        System.out.println("[DualAuthAgent] JWTValidatorTransformer: Transforming " + typeDescription.getName());
        
        return builder
            .visit(Advice.to(ValidateAdvice.class).on(
                nameStartsWith("validate")
                .and(takesArgument(0, String.class))
                .and(not(isStatic()))
                .and(not(nameContains("Raw"))) 
            ))
            .visit(Advice.to(FetchJwksAdvice.class).on(
                named("fetchJwksFromService")
                .or(named("fetchJwks"))
                .or(named("loadJwks"))
                .or(named("getJwkSet"))
                .or(named("refreshJwks"))
            ))
            .visit(Advice.to(CacheProtectionAdvice.class).on(
                named("invalidateJwksCache")
                .or(named("clearCache"))
                .or(named("invalidateCache"))
            ));
    }

    /**
     * Advice for validate* methods.
     * Delegates complex logic to DualAuthHelper to avoid IllegalAccessError from inlining.
     */
    public static class ValidateAdvice {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static Object enter(@Advice.This Object thiz, @Advice.Argument(0) String token, @Advice.Origin("#m") String methodName, @Advice.Origin("#d") String methodDescriptor) {
            try {

                // Extract issuer first to determine processing path
                String issuer = DualAuthHelper.extractIssuerFromToken(token);
                
                // CRITICAL FIX: For official issuers, ensure clean context processing
                if (issuer != null && DualAuthHelper.isOfficialIssuer(issuer)) {
                    // Clear any residual DualAuth context that might interfere
                    DualAuthContext.softClear();
                    
                    if (Boolean.getBoolean("dualauth.debug")) {
                        System.out.println("[DualAuthAgent] ValidateAdvice: Processing official issuer with clean context: " + issuer);
                    }
                    
                    // Continue to original validation for official issuers
                    // This ensures official tokens are validated by the original system
                    return null;
                }

                // 1. Omni-Auth Check (only for non-official issuers)
                com.nimbusds.jwt.JWTClaimsSet claims = EmbeddedJwkVerifier.verifyAndGetClaims(token);
                if (claims != null) {
                    return DualAuthHelper.createJWTClaimsWrapper(thiz.getClass().getClassLoader(), claims, methodName, methodDescriptor);
                }

                // 2. F2P / Trusted Check
                if (issuer != null) {
                    DualAuthContext.setIssuer(issuer);
                    DualJwksFetcher.registerIssuer(issuer);

                    // MISSION CRITICAL: manual validation for ALL valid issuers
                    if (DualAuthHelper.isValidIssuer(issuer)) {
                         // Proactively update expected issuer
                         DualAuthHelper.updateExpectedIssuer(thiz, issuer);

                         Object wrapper = DualAuthHelper.verifyTrustedToken(thiz, token, methodName);
                         if (wrapper != null) {
                             System.out.println("[DualAuthAgent] Manual validation SUCCESS for issuer: " + issuer);
                             return wrapper;
                         } else {
                             System.out.println("[DualAuthAgent] Manual validation FAILED for issuer: " + issuer + " (falling back)");
                         }
                    }
                    
                    // Set subject for context propagation
                    String subject = DualAuthHelper.extractSubjectFromToken(token);
                    if (subject != null) {
                        DualAuthContext.setPlayerUuid(subject);
                    }
                }
            } catch (Exception ignored) {}
            return null; // Continue to original method if not bypassed
        }

        @Advice.OnMethodExit
        public static void exit(@Advice.Return(readOnly = false, typing = net.bytebuddy.implementation.bytecode.assign.Assigner.Typing.DYNAMIC) Object returned, @Advice.Enter Object entered) {
            if (entered != null) {
                returned = entered;
            }
        }
    }

    /**
     * Advice for fetchJwks* methods.
     * Returns merged JWKS for non-official issuers, lets original handle officials.
     */
    public static class FetchJwksAdvice {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static Object enter(@Advice.This Object thiz) {
            try {
                // CRITICAL FIX: Skip JWKS merging for official issuers to prevent lag
                String currentIssuer = DualAuthContext.getIssuer();
                if (currentIssuer != null && DualAuthHelper.isOfficialIssuer(currentIssuer)) {
                    if (Boolean.getBoolean("dualauth.debug")) {
                        System.out.println("[DualAuthAgent] FetchJwksAdvice: Using original JWKS flow for official issuer: " + currentIssuer);
                    }
                    return null; // Let original flow handle official issuers
                }
                
                String mergedJson = DualJwksFetcher.fetchMergedJwksJson();
                if (mergedJson == null || mergedJson.isEmpty()) return null;

                ClassLoader cl = thiz.getClass().getClassLoader();
                
                // Reflection to decode JSON into JwksResponse
                Class<?> jwksResponseClass = cl.loadClass("com.hypixel.hytale.server.core.auth.SessionServiceClient$JwksResponse");
                Field codecField = jwksResponseClass.getDeclaredField("CODEC");
                codecField.setAccessible(true);
                Object codec = codecField.get(null);

                Class<?> rawJsonReaderClass = cl.loadClass("com.hypixel.hytale.codec.util.RawJsonReader");
                Object reader = rawJsonReaderClass.getDeclaredConstructor(char[].class).newInstance((Object) mergedJson.toCharArray());
                
                Class<?> extraInfoClass = cl.loadClass("com.hypixel.hytale.codec.ExtraInfo");
                Class<?> emptyExtraInfoClass = cl.loadClass("com.hypixel.hytale.codec.EmptyExtraInfo");
                Object emptyInfo = emptyExtraInfoClass.getDeclaredField("EMPTY").get(null);

                Method decodeMethod = codec.getClass().getMethod("decodeJson", rawJsonReaderClass, extraInfoClass);
                Object jwksResponse = decodeMethod.invoke(codec, reader, emptyInfo);

                if (jwksResponse == null) return null;

                Field keysField = jwksResponseClass.getDeclaredField("keys");
                keysField.setAccessible(true);
                Object[] keys = (Object[]) keysField.get(jwksResponse);

                java.util.List<com.nimbusds.jose.jwk.JWK> jwkList = new java.util.ArrayList<>();
                Method convertMethod = thiz.getClass().getDeclaredMethod("convertToJWK", cl.loadClass("com.hypixel.hytale.server.core.auth.SessionServiceClient$JwkKey"));
                convertMethod.setAccessible(true);

                for (Object key : keys) {
                    com.nimbusds.jose.jwk.JWK jwk = (com.nimbusds.jose.jwk.JWK) convertMethod.invoke(thiz, key);
                    if (jwk != null) jwkList.add(jwk);
                }

                com.nimbusds.jose.jwk.JWKSet jwkSet = new com.nimbusds.jose.jwk.JWKSet(jwkList);
                
                // Update Cache
                Field cacheField = thiz.getClass().getDeclaredField("cachedJwkSet");
                cacheField.setAccessible(true);
                cacheField.set(thiz, jwkSet);

                // Use public helper to update timestamp
                DualAuthHelper.updateCacheTimestamp(thiz);

                System.out.println("[DualAuthAgent] fetchJwksFromService: Successfully loaded " + jwkList.size() + " merged keys.");
                return jwkSet;
            } catch (Exception e) {
                System.err.println("[DualAuthAgent] fetchJwksFromService failed: " + e.getMessage());
                return null;
            }
        }

        @Advice.OnMethodExit
        public static void exit(@Advice.Return(readOnly = false, typing = net.bytebuddy.implementation.bytecode.assign.Assigner.Typing.DYNAMIC) Object returned, @Advice.Enter Object entered) {
            if (entered != null) {
                returned = entered;
            }
        }
    }

    /**
     * Cache Protection Advice - Selective cache invalidation for official issuers
     * Only blocks cache invalidation for official issuers if it's a background refresh
     * Allows manual/forced invalidation to prevent stale cache issues
     */
    public static class CacheProtectionAdvice {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean enter(@Advice.This Object thiz, @Advice.Origin("#m") String methodName) {
            try {
                String currentIssuer = DualAuthContext.getIssuer();
                if (currentIssuer != null && DualAuthHelper.isOfficialIssuer(currentIssuer)) {
                    // Allow cache invalidation for official issuers in these cases:
                    // 1. Manual invalidation methods (contains "invalidate", "clear")
                    // 2. Forced refresh (contains "refresh")
                    // 3. Background methods that might indicate stale cache
                    boolean isManualInvalidation = methodName.toLowerCase().contains("invalidate") || 
                                               methodName.toLowerCase().contains("clear");
                    boolean isForcedRefresh = methodName.toLowerCase().contains("refresh");
                    
                    if (isManualInvalidation || isForcedRefresh) {
                        if (Boolean.getBoolean("dualauth.debug")) {
                            System.out.println("[DualAuthAgent] CacheProtection: Allowing " + methodName + " for official issuer: " + currentIssuer);
                        }
                        return false; // Allow manual/forced invalidation
                    }
                    
                    // Only block automatic background refreshes for official issuers
                    if (Boolean.getBoolean("dualauth.debug")) {
                        System.out.println("[DualAuthAgent] CacheProtection: Blocking automatic cache invalidation for official issuer: " + currentIssuer + " (method: " + methodName + ")");
                    }
                    return true; // Skip automatic cache invalidation for officials
                }
            } catch (Exception ignored) {}
            return false; // Allow normal cache invalidation for non-officials
        }
    }
}
