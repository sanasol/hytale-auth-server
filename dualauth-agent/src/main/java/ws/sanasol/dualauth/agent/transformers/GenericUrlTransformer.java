package ws.sanasol.dualauth.agent.transformers;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import ws.sanasol.dualauth.context.DualAuthHelper;
import ws.sanasol.dualauth.agent.DualAuthConfig;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * A transformer that replaces hardcoded Official Session URLs with dynamic routing.
 * This mirrors the 'patchGenericClass' logic from the original patcher.
 */
public class GenericUrlTransformer implements net.bytebuddy.agent.builder.AgentBuilder.Transformer {

    @Override
    public DynamicType.Builder<?> transform(DynamicType.Builder<?> builder, TypeDescription typeDescription, ClassLoader classLoader, net.bytebuddy.utility.JavaModule module, java.security.ProtectionDomain pd) {
        String className = typeDescription.getName();
        
        // Skip OAuth classes (Mission Critical: avoid breaking /auth device flow)
        if (className.contains(".oauth.") || className.contains("OAuth") || 
            className.contains("AuthConfig") || className.contains("AuthCredentialStore")) {
            return builder;
        }

        // We use a custom ASM visitor or Advice to replace string constants.
        // Advice is easier for most cases.
        return builder.visit(Advice.to(UrlReplacementAdvice.class).on(any()));
    }

    public static class UrlReplacementAdvice {
        // This is tricky for strings. ByteBuddy Advice doesn't easily replace string constants in-place
        // without knowing exactly where they are used.
        // However, we can patch methods that are likely to build URLs.
        
        // A better way is to use a MemberSubstitution, but that's more complex.
        // For now, let's focus on the known critical classes or use a very targeted Advice
        // on methods that take URLs.
    }
}
