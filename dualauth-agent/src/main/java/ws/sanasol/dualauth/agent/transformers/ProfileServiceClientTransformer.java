package ws.sanasol.dualauth.agent.transformers;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import ws.sanasol.dualauth.context.DualAuthContext;
import ws.sanasol.dualauth.context.DualAuthHelper;
import java.lang.reflect.Field;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * Transforms ProfileServiceClient for URL routing based on issuer context.
 *
 * ProfileServiceClient uses a `profileServiceUrl` field (default: https://account-data.hytale.com)
 * for profile lookups. This transformer swaps that URL to route requests to the correct
 * backend based on the current issuer (F2P → F2P auth server, official → account-data.hytale.com).
 *
 * Methods intercepted:
 * - getProfileByUuid(UUID, String) — sync profile lookup by UUID
 * - getProfileByUsername(String, String) — sync profile lookup by username
 * - getProfileByUuidAsync(UUID, String) — async wrapper (delegates to sync)
 * - getProfileByUsernameAsync(String, String) — async wrapper (delegates to sync)
 */
public class ProfileServiceClientTransformer implements net.bytebuddy.agent.builder.AgentBuilder.Transformer {

    @Override
    public DynamicType.Builder<?> transform(DynamicType.Builder<?> builder, TypeDescription typeDescription, ClassLoader classLoader, net.bytebuddy.utility.JavaModule module, java.security.ProtectionDomain pd) {
        System.out.println("[DualAuthAgent] ProfileServiceClientTransformer: Transforming " + typeDescription.getName());

        return builder
            .visit(Advice.to(ConstructorUrlPatch.class, ws.sanasol.dualauth.agent.DualAuthAgent.CLASS_FILE_LOCATOR).on(isConstructor()))
            .visit(Advice.to(ProfileUrlRoutingAdvice.class, ws.sanasol.dualauth.agent.DualAuthAgent.CLASS_FILE_LOCATOR).on(
                named("getProfileByUuid")
                .or(named("getProfileByUsername"))
                .or(named("getProfileByUuidAsync"))
                .or(named("getProfileByUsernameAsync"))
            ));
    }

    /**
     * Captures the original profileServiceUrl on construction for later restoration.
     */
    public static class ConstructorUrlPatch {
        @Advice.OnMethodExit
        public static void exit(@Advice.This Object thiz) {
            try {
                Field urlField = findProfileUrlField(thiz.getClass());
                if (urlField != null) {
                    urlField.setAccessible(true);
                    String currentUrl = (String) urlField.get(thiz);
                    DualAuthContext.saveOriginalProfileUrl(currentUrl);
                    if (Boolean.getBoolean("dualauth.debug")) {
                        System.out.println("[DualAuthAgent] ProfileServiceClient initialized with URL: " + currentUrl);
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    /**
     * Swaps profileServiceUrl before each method call based on issuer context.
     * Same pattern as SessionServiceClientTransformer.UrlRoutingAdvice but uses
     * profile-specific URL resolution and original URL storage.
     */
    public static class ProfileUrlRoutingAdvice {
        @Advice.OnMethodEnter
        public static void enter(@Advice.This Object thiz, @Advice.AllArguments Object[] args) {
            try {
                Field urlField = findProfileUrlField(thiz.getClass());
                if (urlField == null) return;
                urlField.setAccessible(true);
                String currentUrl = (String) urlField.get(thiz);

                // Save original URL on first encounter
                DualAuthContext.saveOriginalProfileUrl(currentUrl);

                String issuer = DualAuthContext.getIssuer();

                // Fallback: extract issuer from bearerToken argument if ThreadLocal is null.
                // Profile methods: getProfileByUuid(UUID, String bearerToken),
                //                  getProfileByUsername(String, String bearerToken)
                // The bearer token is the server's session JWT — contains iss claim.
                if (issuer == null && args != null) {
                    for (Object arg : args) {
                        if (arg instanceof String) {
                            String s = (String) arg;
                            if (s.length() > 30 && DualAuthHelper.countDots(s) >= 2) {
                                String extracted = DualAuthHelper.extractIssuerFromToken(s);
                                if (extracted != null) {
                                    issuer = extracted;
                                    if (Boolean.getBoolean("dualauth.debug")) {
                                        System.out.println("[DualAuthAgent] ProfileUrlRouting: Extracted issuer from token arg: " + issuer);
                                    }
                                    break;
                                }
                            }
                        }
                    }
                }

                if (issuer != null && !DualAuthHelper.isOfficialIssuer(issuer)) {
                    // F2P: route to F2P profile URL
                    String issuerUrl = DualAuthHelper.getProfileUrlForIssuer(issuer);
                    if (!issuerUrl.equals(currentUrl)) {
                        urlField.set(thiz, issuerUrl);
                        if (Boolean.getBoolean("dualauth.debug")) {
                            System.out.println("[DualAuthAgent] ProfileUrlRouting: Routed to " + issuerUrl);
                        }
                    }
                } else {
                    String originalUrl = DualAuthContext.getOriginalProfileUrl();
                    if (originalUrl != null && !originalUrl.equals(currentUrl)) {
                        // Official or no issuer: restore to original URL
                        urlField.set(thiz, originalUrl);
                        if (Boolean.getBoolean("dualauth.debug")) {
                            System.out.println("[DualAuthAgent] ProfileUrlRouting: Restored to " + originalUrl);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    /**
     * Finds the URL field on ProfileServiceClient.
     * Looks for fields containing "profileserviceurl" or "serviceurl" or generic "url".
     */
    private static Field findProfileUrlField(Class<?> clazz) {
        while (clazz != null && clazz != Object.class) {
            for (Field f : clazz.getDeclaredFields()) {
                String name = f.getName().toLowerCase();
                if (name.contains("profileserviceurl") || name.contains("serviceurl")
                        || (f.getType() == String.class && name.contains("url")))
                    return f;
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }
}
