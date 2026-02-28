package ws.sanasol.dualauth.agent.transformers;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import ws.sanasol.dualauth.commands.DualAuthCommands;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * Transforms CommandManager to intercept DualAuth commands (/authinfo, /authlist)
 * before the game's normal command processing.
 *
 * Targets: CommandManager.handleCommand(CommandSender, String)
 * Skips:   CommandManager.handleCommand(PlayerRef, String) — that delegates to the CommandSender overload
 */
public class ChatCommandTransformer implements net.bytebuddy.agent.builder.AgentBuilder.Transformer {

    @Override
    public DynamicType.Builder<?> transform(DynamicType.Builder<?> builder, TypeDescription typeDescription,
            ClassLoader classLoader, net.bytebuddy.utility.JavaModule module, java.security.ProtectionDomain pd) {
        System.out.println("[DualAuthAgent] ChatCommandTransformer: Transforming " + typeDescription.getName());

        return builder
            .visit(Advice.to(CommandInterceptAdvice.class, ws.sanasol.dualauth.agent.DualAuthAgent.CLASS_FILE_LOCATOR).on(
                named("handleCommand")
                    .and(takesArguments(2))
                    .and(takesArgument(1, String.class))
                    // Exclude the PlayerRef overload — we only want the CommandSender overload
                    .and(not(takesArgument(0, named("com.hypixel.hytale.server.core.universe.PlayerRef"))))
            ));
    }

    /**
     * Intercepts CommandManager.handleCommand(CommandSender, String).
     * If the command is a DualAuth command, handles it and skips the original method.
     */
    public static class CommandInterceptAdvice {

        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static Object enter(
                @Advice.Argument(0) Object sender,
                @Advice.Argument(1) String commandString) {
            try {
                return DualAuthCommands.tryHandle(sender, commandString);
            } catch (Exception e) {
                System.err.println("[DualAuth] Command intercept error: " + e.getMessage());
                return null;
            }
        }

        @Advice.OnMethodExit
        public static void exit(
                @Advice.Return(readOnly = false, typing = net.bytebuddy.implementation.bytecode.assign.Assigner.Typing.DYNAMIC) Object returned,
                @Advice.Enter Object entered) {
            if (entered != null) {
                returned = entered;
            }
        }
    }
}
