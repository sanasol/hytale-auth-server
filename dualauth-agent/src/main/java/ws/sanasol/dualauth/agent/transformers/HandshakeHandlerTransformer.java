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
 * 3. Bypass mutual auth for third-party issuers that can't provide server tokens
 */
public class HandshakeHandlerTransformer implements net.bytebuddy.agent.builder.AgentBuilder.Transformer {

    @Override
    public DynamicType.Builder<?> transform(DynamicType.Builder<?> builder, TypeDescription typeDescription, ClassLoader classLoader, net.bytebuddy.utility.JavaModule module, java.security.ProtectionDomain pd) {
        System.out.println("[DualAuthAgent] HandshakeHandlerTransformer: Transforming " + typeDescription.getName());

        return builder
            .visit(Advice.to(HandshakeEntryAdvice.class, ws.sanasol.dualauth.agent.DualAuthAgent.CLASS_FILE_LOCATOR).on(
                named("channelRead0")
                .or(named("handleHandshake"))
                .or(nameContains("handleLogin"))
            ))
            .visit(Advice.to(UsernameFallbackAdvice.class, ws.sanasol.dualauth.agent.DualAuthAgent.CLASS_FILE_LOCATOR).on(
                named("exchangeServerAuthGrant")
                .or(nameContains("completeAuthentication"))
            ))
            .visit(Advice.to(AuthGrantBypassAdvice.class, ws.sanasol.dualauth.agent.DualAuthAgent.CLASS_FILE_LOCATOR).on(
                named("requestAuthGrant")
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
                    // Unregister previous player from registry before resetting context
                    String previousUuid = DualAuthContext.getPlayerUuid();
                    if (previousUuid != null) {
                        DualAuthContext.unregisterPlayer(previousUuid);
                    }
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
                    String previousUuid = DualAuthContext.getPlayerUuid();
                    if (previousUuid != null) {
                        DualAuthContext.unregisterPlayer(previousUuid);
                    }
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

    /**
     * Bypasses mutual authentication for third-party issuers (e.g. Butter)
     * that can't provide server identity tokens.
     *
     * When a non-official, non-F2P, non-Omni issuer is detected, this advice
     * intercepts requestAuthGrant() and instead mimics the server's development
     * flow: sends ConnectAccept and installs PasswordPacketHandler directly.
     *
     * Uses skipOn pattern: return non-default (true) to skip original method.
     */
    public static class AuthGrantBypassAdvice {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean enter(@Advice.This Object thiz) {
            try {
                // First do username fallback (was previously on requestAuthGrant via UsernameFallbackAdvice)
                String authUser = (String) DualAuthHelper.getF(thiz, "authenticatedUsername");
                if (authUser == null || authUser.trim().isEmpty()) {
                    String handshakeUser = (String) DualAuthHelper.getF(thiz, "username");
                    if (handshakeUser != null && !handshakeUser.trim().isEmpty()) {
                        String cleanUsername = handshakeUser.trim();
                        DualAuthHelper.setF(thiz, "authenticatedUsername", cleanUsername);
                        System.out.println("[DualAuthAgent] AuthGrantBypass: Fallback to handshake username: " + cleanUsername);
                    }
                }

                // Check if we should bypass mutual auth
                if (!DualAuthHelper.shouldBypassMutualAuth()) {
                    return false; // default value = don't skip, run original method
                }

                // Attempt bypass to development flow
                boolean success = DualAuthHelper.bypassToDevFlow(thiz);
                if (success) {
                    return true; // non-default = skip original requestAuthGrant()
                }

                // Bypass failed, fall through to original method
                System.out.println("[DualAuthAgent] AuthGrantBypass: Bypass failed, falling back to normal flow");
                return false;
            } catch (Exception e) {
                System.out.println("[DualAuthAgent] AuthGrantBypass error: " + e.getMessage());
                return false; // don't skip on error
            }
        }
    }
}
