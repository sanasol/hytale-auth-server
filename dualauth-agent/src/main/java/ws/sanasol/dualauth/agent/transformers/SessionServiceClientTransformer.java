package ws.sanasol.dualauth.agent.transformers;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import ws.sanasol.dualauth.context.DualAuthContext;
import ws.sanasol.dualauth.context.DualAuthHelper;
import ws.sanasol.dualauth.fetcher.DualJwksFetcher;
import ws.sanasol.dualauth.embedded.EmbeddedJwkVerifier;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * Transforms SessionServiceClient for URL routing, JWKS merging, and async propagation.
 */
public class SessionServiceClientTransformer implements net.bytebuddy.agent.builder.AgentBuilder.Transformer {

    @Override
    public DynamicType.Builder<?> transform(DynamicType.Builder<?> builder, TypeDescription typeDescription, ClassLoader classLoader, net.bytebuddy.utility.JavaModule module, java.security.ProtectionDomain pd) {
        String name = typeDescription.getName();
        if (name.contains("oauth") || name.contains("OAuth") || name.contains("/auth")) {
            return builder;
        }

        System.out.println("[DualAuthAgent] SessionServiceClientTransformer: Transforming " + name);

        return builder
            .visit(Advice.to(JwksFetchAdvice.class, ws.sanasol.dualauth.agent.DualAuthAgent.CLASS_FILE_LOCATOR).on(
                named("fetchJwks").or(named("fetchJwksFromService")).or(named("loadJwks")).or(nameContains("fetchJwks"))
            ))
            .visit(Advice.to(ConstructorUrlPatch.class, ws.sanasol.dualauth.agent.DualAuthAgent.CLASS_FILE_LOCATOR).on(isConstructor()))
            .visit(Advice.to(UrlRoutingAdvice.class, ws.sanasol.dualauth.agent.DualAuthAgent.CLASS_FILE_LOCATOR).on(
                named("requestAuthorizationGrantAsync")
                .or(named("refreshSessionAsync"))
                .or(named("validateSessionAsync"))
                .or(named("exchangeAuthGrantForTokenAsync")) // Added for F2P/Omni
            ))
            .visit(Advice.to(OfflineBypassAdvice.class, ws.sanasol.dualauth.agent.DualAuthAgent.CLASS_FILE_LOCATOR).on(
                named("requestAuthorizationGrantAsync")
                .or(named("exchangeAuthGrantForTokenAsync")) // Added for Omni Bypass
            ))
            .visit(Advice.to(LambdaContextAdvice.class, ws.sanasol.dualauth.agent.DualAuthAgent.CLASS_FILE_LOCATOR).on(
                nameContains("lambda$").and(takesArguments(String.class).or(takesArguments(String.class, String.class)))
            ));
    }

    public static class JwksFetchAdvice {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static Object enter(@Advice.This Object thiz) {
            try {
                // CRITICAL FIX: Skip JWKS merging for official issuers to prevent lag
                String currentIssuer = DualAuthContext.getIssuer();
                if (currentIssuer != null && DualAuthHelper.isOfficialIssuer(currentIssuer)) {
                    if (Boolean.getBoolean("dualauth.debug")) {
                        System.out.println("[DualAuthAgent] JwksFetchAdvice: Using original JWKS flow for official issuer: " + currentIssuer);
                    }
                    return null; // Let original flow handle official issuers
                }
                
                String fullJson = DualJwksFetcher.fetchMergedJwksJson();
                if (fullJson == null) return null;

                ClassLoader cl = thiz.getClass().getClassLoader();
                Class<?> jwksResponseClass = cl.loadClass("com.hypixel.hytale.server.core.auth.SessionServiceClient$JwksResponse");
                Field codecField = jwksResponseClass.getDeclaredField("CODEC");
                codecField.setAccessible(true);
                Object codec = codecField.get(null);

                Class<?> rawJsonReaderClass = cl.loadClass("com.hypixel.hytale.codec.util.RawJsonReader");
                Object reader = rawJsonReaderClass.getDeclaredConstructor(char[].class).newInstance((Object) fullJson.toCharArray());
                
                Class<?> extraInfoClass = cl.loadClass("com.hypixel.hytale.codec.ExtraInfo");
                Class<?> emptyExtraInfoClass = cl.loadClass("com.hypixel.hytale.codec.EmptyExtraInfo");
                Object emptyInfo = emptyExtraInfoClass.getDeclaredField("EMPTY").get(null);

                Method decodeMethod = codec.getClass().getMethod("decodeJson", rawJsonReaderClass, extraInfoClass);
                Object jwksResponse = decodeMethod.invoke(codec, reader, emptyInfo);

                return jwksResponse;
            } catch (Exception ignored) {}
            return null;
        }

        @Advice.OnMethodExit
        public static void exit(@Advice.Return(readOnly = false, typing = net.bytebuddy.implementation.bytecode.assign.Assigner.Typing.DYNAMIC) Object returned, @Advice.Enter Object entered) {
            if (entered != null) returned = entered;
        }
    }

    public static class ConstructorUrlPatch {
        @Advice.OnMethodExit
        public static void exit(@Advice.This Object thiz) {
            try {
                Field urlField = DualAuthHelper.findUrlField(thiz.getClass());
                if (urlField != null) {
                    urlField.setAccessible(true);
                    String currentUrl = (String) urlField.get(thiz);
                    // System.out.println("SessionServiceClient: " + currentUrl);
                }
            } catch (Exception ignored) {}
        }
    }

    public static class UrlRoutingAdvice {
        @Advice.OnMethodEnter
        public static void enter(@Advice.This Object thiz) {
            try {
                Field urlField = DualAuthHelper.findUrlField(thiz.getClass());
                if (urlField == null) return;
                urlField.setAccessible(true);
                String currentUrl = (String) urlField.get(thiz);

                // Save original URL on first encounter (stored in DualAuthContext on bootstrap CL)
                DualAuthContext.saveOriginalSessionUrl(currentUrl);

                String issuer = DualAuthContext.getIssuer();
                if (issuer != null && !DualAuthHelper.isOfficialIssuer(issuer)) {
                    // F2P: route to F2P session URL
                    String issuerUrl = DualAuthHelper.getSessionUrlForIssuer(issuer);
                    if (!issuerUrl.equals(currentUrl)) {
                        urlField.set(thiz, issuerUrl);
                    }
                } else {
                    String originalUrl = DualAuthContext.getOriginalSessionUrl();
                    if (originalUrl != null && !originalUrl.equals(currentUrl)) {
                        // Official or no issuer: restore to original URL
                        urlField.set(thiz, originalUrl);
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    public static class OfflineBypassAdvice {
        /**
         * Intercepts calls to Session Service.
         * For exchangeAuthGrantForTokenAsync, we capture the client-provided fingerprint (Argument 1)
         * to generate a token that matches the client's TLS expectations.
         */
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static CompletableFuture<String> enter(
                @Advice.Argument(0) String tokenArg, 
                // We capture arguments as Object array because different methods have different signatures
                // requestAuthorizationGrantAsync(identityToken, audience, bearer)
                // exchangeAuthGrantForTokenAsync(grant, fingerprint, bearer)
                @Advice.AllArguments Object[] args,
                @Advice.Origin("#m") String methodName) {
            try {
                if (DualAuthContext.isOmni() || DualAuthHelper.hasEmbeddedJwk(tokenArg)) {
                    if (Boolean.getBoolean("dualauth.debug")) {
                        System.out.println("[DualAuthAgent] OfflineBypass: Bypassing session call for Omni/Embedded token.");
                    }
                    
                    if (methodName.contains("exchangeAuthGrantForTokenAsync")) {
                        String issuer = DualAuthContext.getIssuer();
                        
                        // FIX: Capture the fingerprint from arguments (index 1)
                        String clientFingerprint = null;
                        if (args.length > 1 && args[1] instanceof String) {
                            clientFingerprint = (String) args[1];
                        }
                        
                        if (Boolean.getBoolean("dualauth.debug")) {
                            System.out.println("[DualAuthAgent] OfflineBypass: Using client provided fingerprint: " + clientFingerprint);
                        }
                        
                        // Pass fingerprint to generator so the token matches client's calculated hash
                        String freshToken = EmbeddedJwkVerifier.createDynamicSessionToken(issuer, clientFingerprint);
                        
                        if (freshToken != null) {
                            if (Boolean.getBoolean("dualauth.debug")) {
                                System.out.println("[DualAuthAgent] OfflineBypass: Generated fresh Server Session Token with matched CNF claim.");
                            }
                            return CompletableFuture.completedFuture(freshToken);
                        }
                    }
                    
                    return CompletableFuture.completedFuture(tokenArg);
                }
            } catch (Exception ignored) {
                if (Boolean.getBoolean("dualauth.debug")) {
                    System.out.println("[DualAuthAgent] OfflineBypass Exception: " + ignored.getMessage());
                }
            }
            return null;
        }

        @Advice.OnMethodExit
        public static void exit(@Advice.Return(readOnly = false) CompletableFuture<String> returned, @Advice.Enter CompletableFuture<String> entered) {
            if (entered != null) returned = entered;
        }
    }

    public static class LambdaContextAdvice {
        @Advice.OnMethodEnter
        public static void enter(@Advice.AllArguments Object[] args) {
            try {
                DualAuthContext.resetForNewConnection();
                if (args == null) return;
                for (Object arg : args) {
                    if (arg instanceof String) {
                        String st = (String) arg;
                        if (st.length() > 30 && DualAuthHelper.countDots(st) >= 2) {
                            String iss = DualAuthHelper.extractIssuerFromToken(st);
                            if (iss != null) {
                                DualAuthContext.setIssuer(iss);
                                DualAuthContext.setPlayerUuid(DualAuthHelper.extractSubjectFromToken(st));
                                String jwk = DualAuthHelper.extractJwkFromToken(st);
                                DualAuthContext.setJwk(jwk);
                                DualAuthContext.setOmni(jwk != null);
                                return;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
    }
}
