package ws.sanasol.dualauth.agent.transformers;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import static net.bytebuddy.matcher.ElementMatchers.named;

/** Standalone regression check: exercises inlined Advice, without a game process. */
public final class LoggingTransformerSelfCheck {
    public static class NativeFormatter extends Formatter {
        @Override public String format(LogRecord record) {
            String message = record.getParameters() == null ? record.getMessage()
                : String.format(record.getMessage(), record.getParameters());
            StringWriter stack = new StringWriter();
            if (record.getThrown() != null) record.getThrown().printStackTrace(new PrintWriter(stack));
            return record.getLevel().getName() + ": " + message + "\n" + stack;
        }
    }

    public static void main(String[] args) throws Exception {
        String previous = System.getProperty("dualauth.logging.enabled");
        try {
            System.setProperty("dualauth.logging.enabled", "true");
            Formatter transformed = new ByteBuddy().redefine(NativeFormatter.class)
                .visit(Advice.to(LoggingTransformer.LoggingAdvice.class).on(named("format")))
                .make().load(LoggingTransformerSelfCheck.class.getClassLoader(), ClassLoadingStrategy.Default.CHILD_FIRST)
                .getLoaded().getConstructor().newInstance();
            LogRecord severe = record(Level.SEVERE, "Failed to run task!");
            severe.setThrown(new IllegalStateException("actual task failure", new IllegalArgumentException("root cause")));
            preserved(transformed, severe);
            preserved(transformed, record(Level.WARNING, "important warning"));
            LogRecord parameters = record(Level.INFO, "Task %s took %d ns");
            parameters.setParameters(new Object[]{"actual-runnable", 42});
            preserved(transformed, parameters);
            LogRecord infoCause = record(Level.INFO, "exception at INFO");
            infoCause.setThrown(new IllegalArgumentException("info cause"));
            preserved(transformed, infoCause);
            preserved(transformed, record(Level.SEVERE, "severity without exception"));
            String shortened = transformed.format(record(Level.INFO, "ordinary info"));
            if (!shortened.contains("World|default | ordinary info") || shortened.startsWith("INFO:"))
                throw new AssertionError("Simple INFO no longer uses short format: " + shortened);
            System.setProperty("dualauth.logging.enabled", "false");
            preserved(transformed, record(Level.INFO, "disabled transformer"));
            System.out.println("Logging Advice regression: throwable/cause, parameters, WARNING/SEVERE, INFO and disabled mode preserved.");
        } finally {
            if (previous == null) System.clearProperty("dualauth.logging.enabled");
            else System.setProperty("dualauth.logging.enabled", previous);
        }
    }

    private static LogRecord record(Level level, String message) {
        LogRecord record = new LogRecord(level, message);
        record.setLoggerName("World|default");
        return record;
    }

    private static void preserved(Formatter transformed, LogRecord record) {
        String expected = new NativeFormatter().format(record);
        String actual = transformed.format(record);
        if (!expected.equals(actual)) throw new AssertionError("Native diagnostic lost: " + actual);
    }
}
