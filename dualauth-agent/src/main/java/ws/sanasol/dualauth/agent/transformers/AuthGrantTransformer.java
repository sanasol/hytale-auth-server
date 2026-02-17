package ws.sanasol.dualauth.agent.transformers;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import ws.sanasol.dualauth.context.DualAuthContext;
import ws.sanasol.dualauth.context.DualAuthHelper;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * Transforms AuthGrant to replace serverIdentityToken for non-official clients.
 */
public class AuthGrantTransformer implements net.bytebuddy.agent.builder.AgentBuilder.Transformer {

    @Override
    public DynamicType.Builder<?> transform(DynamicType.Builder<?> builder, TypeDescription typeDescription, ClassLoader classLoader, net.bytebuddy.utility.JavaModule module, java.security.ProtectionDomain pd) {
        return builder
            .visit(Advice.to(SerializeAdvice.class).on(
                named("serialize").or(named("write")).or(named("encode"))
            ))
            .visit(Advice.to(ConstructorAdvice.class).on(isConstructor()));
    }

    public static class SerializeAdvice {
        @Advice.OnMethodEnter
        public static void enter(@Advice.This Object thiz) {
            DualAuthHelper.maybeReplaceServerIdentity(thiz);
        }
    }

    public static class ConstructorAdvice {
        @Advice.OnMethodExit
        public static void exit(@Advice.This Object thiz) {
            // Backup replacement at construction time
            DualAuthHelper.maybeReplaceServerIdentity(thiz);
        }
    }
}
