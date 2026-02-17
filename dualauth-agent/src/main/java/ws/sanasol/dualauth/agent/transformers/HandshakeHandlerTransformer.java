package ws.sanasol.dualauth.agent.transformers;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import ws.sanasol.dualauth.context.DualAuthContext;
import ws.sanasol.dualauth.context.DualAuthHelper;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * Transforms HandshakeHandler to:
 * 1. Capture/Fallback username from the handshake if missing in JWT (Omni-Auth)
 * 2. Ensure context is routed correctly
 */
public class HandshakeHandlerTransformer implements net.bytebuddy.agent.builder.AgentBuilder.Transformer {
    
    @Override
    public DynamicType.Builder<?> transform(DynamicType.Builder<?> builder, TypeDescription typeDescription, ClassLoader classLoader, net.bytebuddy.utility.JavaModule module, java.security.ProtectionDomain pd) {
        System.out.println("[DualAuthAgent] HandshakeHandlerTransformer: Transforming " + typeDescription.getName());
        
        return builder
            .visit(Advice.to(HandshakeEntryAdvice.class).on(
                named("channelRead0")
                .or(named("handleHandshake"))
                .or(nameContains("handleLogin"))
            ))
            .visit(Advice.to(UsernameFallbackAdvice.class).on(
                named("requestAuthGrant")
                .or(named("exchangeServerAuthGrant"))
                .or(nameContains("completeAuthentication"))
            ));
    }

    /**
     * Resets context at connection boundary and captures initial username.
     */
    public static class HandshakeEntryAdvice {
        @Advice.OnMethodEnter
        public static void enter(@Advice.This Object thiz) {
            try {
                String authUser = (String) DualAuthHelper.getF(thiz, "authenticatedUsername");
                if (authUser == null) {
                    DualAuthContext.resetForNewConnection();
                    String username = DualAuthHelper.extractUsername(thiz);
                    if (username != null && !username.trim().isEmpty()) {
                        DualAuthContext.setUsername(username.trim());
                    }
                }
            } catch (Exception e) {
                // Log error but don't crash the handshake
                System.out.println("[DualAuthAgent] HandshakeEntryAdvice error: " + e.getMessage());
                // Ensure context is reset even on error
                try {
                    DualAuthContext.resetForNewConnection();
                } catch (Exception resetError) {
                    System.out.println("[DualAuthAgent] Failed to reset context after error: " + resetError.getMessage());
                }
            }
        }
    }

    /**
     * Fallback for missing username in JWT tokens.
     */
    public static class UsernameFallbackAdvice {
        @Advice.OnMethodEnter
        public static void enter(@Advice.This Object thiz) {
            try {
                String authUser = (String) DualAuthHelper.getF(thiz, "authenticatedUsername");
                if (authUser == null || authUser.trim().isEmpty()) {
                    String handshakeUser = (String) DualAuthHelper.getF(thiz, "username");
                    if (handshakeUser != null && !handshakeUser.trim().isEmpty()) {
                        String cleanUsername = handshakeUser.trim();
                        DualAuthHelper.setF(thiz, "authenticatedUsername", cleanUsername);
                        System.out.println("[DualAuthAgent] HandshakeHandler: Fallback to handshake username: " + cleanUsername);
                    }
                }
            } catch (Exception e) {
                // Log error but don't crash the authentication
                System.out.println("[DualAuthAgent] UsernameFallbackAdvice error: " + e.getMessage());
            }
        }
    }
}
