package ws.sanasol.dualauth.agent.transformers;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;

import java.util.logging.LogRecord;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * Transformer for replacing completely the logging system of Hytale.
 * Intercepts the format method of HytaleLogFormatter to provide a new logging
 * system. This approach completely replaces the original formatter.
 *
 * IMPORTANT: The LoggingAdvice inner class must be completely SELF-CONTAINED.
 * ByteBuddy Advice inlines the advice bytecode into the target class. Any
 * references to agent classes (getstatic, invokestatic) in the inlined code
 * require those classes to be loadable from the target's classloader at runtime.
 * When other Java agents or early plugins (e.g. FixtaleEarly) are present,
 * they can interfere with classloader resolution, causing NoClassDefFoundError
 * in the logging system which cascades to crash every thread.
 *
 * Solution: The advice code uses ONLY java.* classes. The enabled flag is
 * communicated via a system property set during agent install.
 */
public class LoggingTransformer implements net.bytebuddy.agent.builder.AgentBuilder.Transformer {

    /** System property key used to communicate enabled state to inlined advice code. */
    public static final String LOGGING_ENABLED_PROPERTY = "dualauth.logging.enabled";

    public static final boolean LOGGING_ENABLED = !"false".equalsIgnoreCase(System.getenv("DUALAUTH_LOGGING_ENABLED"));

    /**
     * Must be called once during agent install (before any class transformation).
     * Sets a system property so the inlined advice can check enabled state
     * without referencing any agent classes.
     */
    public static void initSystemProperty() {
        System.setProperty(LOGGING_ENABLED_PROPERTY, String.valueOf(LOGGING_ENABLED));
    }

    @Override
    public DynamicType.Builder<?> transform(DynamicType.Builder<?> builder, TypeDescription typeDescription,
            ClassLoader classLoader, net.bytebuddy.utility.JavaModule module, java.security.ProtectionDomain pd) {
        if (!LOGGING_ENABLED) {
            System.out.println(
                    "LoggingTransformer: Disabled by environment variable DUALAUTH_LOGGING_ENABLED=false");
            return builder; // Don't apply transformation if disabled
        }

        System.out.println("LoggingTransformer: Transforming " + typeDescription.getName());

        return builder
                .visit(Advice.to(LoggingAdvice.class, ws.sanasol.dualauth.agent.DualAuthAgent.CLASS_FILE_LOCATOR).on(
                        named("format")
                                .and(takesArguments(LogRecord.class))
                                .and(returns(String.class))
                ));
    }

    /**
     * SELF-CONTAINED advice class.
     *
     * This class MUST NOT reference any agent classes (LoggingTransformer,
     * DualAuthContext, etc.) because its bytecode is inlined into
     * HytaleLogFormatter.format() by ByteBuddy. At runtime, the inlined code
     * runs in HytaleLogFormatter's classloader context, which may not have
     * visibility to agent classes when other transformers/agents are active.
     *
     * All logic uses exclusively java.* classes and system properties.
     */
    public static class LoggingAdvice {

        @Advice.OnMethodEnter
        @SuppressWarnings("unused")
        public static void enter(@Advice.Argument(0) LogRecord record,
                @Advice.Local("formattedOutput") String formattedOutput) {
            try {
                // Check enabled via system property — NO reference to agent classes
                if (!"true".equals(System.getProperty("dualauth.logging.enabled"))) {
                    return;
                }

                // Get log record information
                String originalMessage = record.getMessage();
                String level = record.getLevel().getName();
                String loggerName = record.getLoggerName() != null ? record.getLoggerName() : "Unknown";

                // Format time inline — NO reference to LoggingTransformer.TIME_FORMATTER
                java.time.LocalTime now = java.time.LocalTime.now();
                String time = String.format("%02d:%02d", now.getHour(), now.getMinute());

                // Auto-detect if output is to file vs terminal
                boolean isFileHandler = false;
                StackTraceElement[] stack = Thread.currentThread().getStackTrace();
                for (StackTraceElement element : stack) {
                    String className = element.getClassName();
                    if (className.contains("FileHandler") || className.contains("FileLogHandler") ||
                        className.contains("FileAppender") || className.contains("RollingFileHandler")) {
                        isFileHandler = true;
                        break;
                    }
                }

                // Use colors only for terminal output (not file handlers)
                boolean useColors = !isFileHandler && System.console() != null;

                // Inline color code logic — NO reference to getColorCodeForLevel()
                String colorCode = "";
                if (useColors && level != null) {
                    String upperLevel = level.toUpperCase();
                    if ("SEVERE".equals(upperLevel) || "ERROR".equals(upperLevel)) {
                        colorCode = "\033[91m";
                    } else if ("WARNING".equals(upperLevel) || "WARN".equals(upperLevel)) {
                        colorCode = "\033[93m";
                    } else if ("INFO".equals(upperLevel)) {
                        colorCode = "\033[92m";
                    } else if ("DEBUG".equals(upperLevel) || "FINE".equals(upperLevel)
                            || "FINER".equals(upperLevel) || "FINEST".equals(upperLevel)) {
                        colorCode = "\033[96m";
                    } else if ("CONFIG".equals(upperLevel)) {
                        colorCode = "\033[95m";
                    } else {
                        colorCode = "\033[97m";
                    }
                }
                String resetCode = useColors ? "\033[m" : "";

                // Format the message
                formattedOutput = String.format("%s[%s] %s%s | %s",
                        colorCode, time, loggerName, resetCode,
                        originalMessage != null ? originalMessage : "");

            } catch (Throwable t) {
                // Catch Throwable (not Exception) to handle NoClassDefFoundError etc.
                // Silently fail — original format method return value will be used
            }
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        @SuppressWarnings("unused")
        public static void exit(@Advice.Return(readOnly = false) String returnedValue,
                @Advice.Local("formattedOutput") String formattedOutput) {
            try {
                // Check enabled via system property — NO reference to agent classes
                if (!"true".equals(System.getProperty("dualauth.logging.enabled"))) {
                    return;
                }

                if (formattedOutput != null) {
                    returnedValue = formattedOutput + "\n";
                }
            } catch (Throwable t) {
                // Silently fail — keep original return value
            }
        }
    }
}