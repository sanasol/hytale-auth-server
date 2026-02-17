package ws.sanasol.dualauth.agent.transformers;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.LogRecord;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * Transformer for replacing completely the logging system of Hytale
 * Intercepts the format method of HytaleLogFormatter to provide a new logging
 * system
 * This approach completely replaces the original formatter
 */
public class LoggingTransformer implements net.bytebuddy.agent.builder.AgentBuilder.Transformer {

    public static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    public static final boolean LOGGING_ENABLED = !"false".equalsIgnoreCase(System.getenv("DUALAUTH_LOGGING_ENABLED"));

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
                .visit(Advice.to(LoggingAdvice.class).on(
                        named("format")
                                .and(takesArguments(LogRecord.class))
                                .and(returns(String.class))
                ));
    }

    public static class LoggingAdvice {
        @Advice.OnMethodEnter
        public static void enter(@Advice.Argument(0) LogRecord record,
                @Advice.Local("originalMessage") String originalMessage,
                @Advice.Local("level") String level,
                @Advice.Local("loggerName") String loggerName,
                @Advice.Local("formattedOutput") String formattedOutput) {
            if (!LoggingTransformer.LOGGING_ENABLED) {
                return; // Don't do anything if disabled
            }

            try {
                // Get the log record information
                originalMessage = record.getMessage();
                level = record.getLevel().getName();
                loggerName = record.getLoggerName() != null ? record.getLoggerName() : "Unknown";

                // Get the current time in HH:mm format
                String time = LocalTime.now().format(TIME_FORMATTER);

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
                
                // Use color codes only when appropriate
                String colorCode = useColors ? getColorCodeForLevel(level, false) : "";
                String resetCode = useColors ? "\033[m" : "";

                // Format the message (with colors only for terminal)
                formattedOutput = String.format("%s[%s] %s%s | %s",
                        colorCode,
                        time,
                        loggerName,
                        resetCode,
                        originalMessage != null ? originalMessage : "");

            } catch (Exception e) {
                System.err.println("Error in LoggingAdvice enter: " + e.getMessage());
            }
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Return(readOnly = false) String returnedValue,
                @Advice.Local("formattedOutput") String formattedOutput) {
            if (!LoggingTransformer.LOGGING_ENABLED) {
                return; // Don't do anything if disabled
            }

            try {
                // Override the return value of the original method with our format
                // This completely replaces the original formatter result
                if (formattedOutput != null) {
                    returnedValue = formattedOutput + "\n"; // Add newline like the original
                } else {
                    // If something fails, return an empty string to avoid the original format
                    returnedValue = "\n"; // Return at least a newline to maintain format
                }
            } catch (Exception e) {
                System.err.println("Error in LoggingAdvice exit: " + e.getMessage());
                returnedValue = "";
            }
        }

        public static String getColorCodeForLevel(String level, boolean isDualAuthMessage) {
            if (level == null) {
                return "\033[97m"; // Default to white
            }

            switch (level.toUpperCase()) {
                case "SEVERE":
                case "ERROR":
                    return "\033[91m"; // Red
                case "WARNING":
                case "WARN":
                    return "\033[93m"; // Yellow
                case "INFO":
                    return "\033[92m"; // Green
                case "DEBUG":
                case "FINE":
                case "FINER":
                case "FINEST":
                    return "\033[96m"; // Cyan
                case "CONFIG":
                    return "\033[95m"; // Magenta
                default:
                    return "\033[97m"; // White
            }
        }
    }
}