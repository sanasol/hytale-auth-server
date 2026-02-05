package com.hytale.dualauth.agent.transformers;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import com.hytale.dualauth.context.DualAuthContext;
import com.hytale.dualauth.context.DualAuthHelper;
import com.hytale.dualauth.fetcher.DualJwksFetcher;
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

        System.out.println("[DualAuth] SessionServiceClientTransformer: Transforming " + name);

        return builder
            .visit(Advice.to(JwksFetchAdvice.class).on(
                named("fetchJwks").or(named("fetchJwksFromService")).or(named("loadJwks")).or(nameContains("fetchJwks"))
            ))
            .visit(Advice.to(ConstructorUrlPatch.class).on(isConstructor()))
            .visit(Advice.to(UrlRoutingAdvice.class).on(
                named("requestAuthorizationGrantAsync").or(named("refreshSessionAsync")).or(named("validateSessionAsync"))
            ))
            .visit(Advice.to(OfflineBypassAdvice.class).on(named("requestAuthorizationGrantAsync")))
            .visit(Advice.to(LambdaContextAdvice.class).on(
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
                        System.out.println("[DualAuth] JwksFetchAdvice: Using original JWKS flow for official issuer: " + currentIssuer);
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
                    // System.out.println("[DualAuth] SessionServiceClient: " + currentUrl);
                }
            } catch (Exception ignored) {}
        }
    }

    public static class UrlRoutingAdvice {
        @Advice.OnMethodEnter
        public static void enter(@Advice.This Object thiz) {
            try {
                String issuer = DualAuthContext.getIssuer();
                if (issuer != null && !DualAuthHelper.isOfficialIssuer(issuer)) {
                    String issuerUrl = DualAuthHelper.getSessionUrlForIssuer(issuer);
                    Field urlField = DualAuthHelper.findUrlField(thiz.getClass());
                    if (urlField != null) {
                        urlField.setAccessible(true);
                        if (!issuerUrl.equals(urlField.get(thiz))) {
                            urlField.set(thiz, issuerUrl);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    public static class OfflineBypassAdvice {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static CompletableFuture<String> enter(@Advice.Argument(0) String identityToken) {
            try {
                if (DualAuthContext.isOmni() || DualAuthHelper.hasEmbeddedJwk(identityToken)) {
                    return CompletableFuture.completedFuture(identityToken);
                }
            } catch (Exception ignored) {}
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
