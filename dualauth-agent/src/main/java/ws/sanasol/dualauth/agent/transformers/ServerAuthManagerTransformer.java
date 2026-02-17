package ws.sanasol.dualauth.agent.transformers;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import ws.sanasol.dualauth.server.DualServerTokenManager;
import ws.sanasol.dualauth.context.DualAuthContext;
import ws.sanasol.dualauth.context.DualAuthHelper;
import java.lang.reflect.Method;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * Transforms ServerAuthManager to:
 * 1. Capture tokens from initialize() and session creation methods
 * 2. Provide fallback tokens from DualServerTokenManager for getters
 * 
 * This ensures that even if the original server token is null (e.g., for F2P clients),
 * we can provide a valid token from our dual cache.
 */
public class ServerAuthManagerTransformer implements net.bytebuddy.agent.builder.AgentBuilder.Transformer {

    @Override
    public DynamicType.Builder<?> transform(DynamicType.Builder<?> builder, TypeDescription typeDescription, ClassLoader classLoader, net.bytebuddy.utility.JavaModule module, java.security.ProtectionDomain pd) {
        System.out.println("[DualAuthAgent] ServerAuthManagerTransformer: Transforming " + typeDescription.getName());
        
        return builder
            // 1. Void Capture: Capture from instance field after initialize()
            .visit(Advice.to(InstanceCaptureAdvice.class).on(
                named("initialize")
            ))
            // 2. Return Capture: Capture from return value (session creation methods)
            .visit(Advice.to(ReturnCaptureAdvice.class).on(
                named("createGameSessionFromOAuth")
                .or(named("onSessionRefreshed"))
                .or(named("handleSessionResponse"))
                .or(named("setGameSession"))
            ))
            // 3. Getter Fallback: Provide custom tokens if official ones are missing
            .visit(Advice.to(IdentityTokenGetterAdvice.class).on(
                named("getIdentityToken").or(named("getPlatformIdentityToken"))
            ))
            .visit(Advice.to(SessionTokenGetterAdvice.class).on(
                named("getSessionToken")
            ));
    }

    /**
     * Captures tokens from the instance's gameSession field after initialize().
     */
    public static class InstanceCaptureAdvice {
        @Advice.OnMethodExit
        public static void exit(@Advice.This Object thiz) {
            try {
                DualServerTokenManager.captureFromInstance(thiz);
            } catch (Exception e) {}
        }
    }

    /**
     * Captures tokens from session response objects.
     */
    public static class ReturnCaptureAdvice {
        @Advice.OnMethodExit
        public static void exit(@Advice.Return(readOnly = true, typing = net.bytebuddy.implementation.bytecode.assign.Assigner.Typing.DYNAMIC) Object response) {
            try {
                if (response != null) {
                    DualServerTokenManager.captureNativeSession(response);
                }
            } catch (Exception e) {}
        }
    }

    /**
     * Provides fallback identity token if the original returns null/empty.
     */
    public static class IdentityTokenGetterAdvice {
        @Advice.OnMethodExit
        public static void exit(
                @Advice.Return(readOnly = false) String returnedValue) {
            try {
                if (returnedValue == null || returnedValue.isEmpty()) {
                    String issuer = DualAuthContext.getIssuer();
                    String fallback = DualServerTokenManager.getIdentityTokenForIssuer(issuer, DualAuthContext.getPlayerUuid());
                    if (fallback != null && !fallback.isEmpty()) {
                        returnedValue = fallback;
                    }
                }
            } catch (Exception e) {}
        }
    }

    /**
     * Provides fallback session token if the original returns null/empty.
     */
    public static class SessionTokenGetterAdvice {
        @Advice.OnMethodExit
        public static void exit(
                @Advice.Return(readOnly = false) String returnedValue) {
            try {
                if (returnedValue == null || returnedValue.isEmpty()) {
                    String issuer = DualAuthContext.getIssuer();
                    String fallback = DualServerTokenManager.getSessionTokenForIssuer(issuer);
                    if (fallback != null && !fallback.isEmpty()) {
                        returnedValue = fallback;
                    }
                }
            } catch (Exception e) {}
        }
    }
}
