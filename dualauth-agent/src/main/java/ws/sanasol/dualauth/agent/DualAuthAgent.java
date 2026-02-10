package ws.sanasol.dualauth.agent;

import ws.sanasol.dualauth.agent.transformers.*;
import ws.sanasol.dualauth.agent.transformers.LoggingTransformer;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * PRODUCTION-READY DUALAUTH AGENT (JDK 25)
 * 
 * This agent intercepts Hytale Server auth classes to enable:
 * 1. Dual JWKS merging (Official + F2P + Dynamic)
 * 2. Omni-Auth bypass (embedded JWK verification)
 * 3. URL routing based on issuer
 * 4. Token management for F2P/Omni clients
 * 
 * HYBRID MODE: Works as both -javaagent and Hytale Plugin
 */
public class DualAuthAgent {
    public static final String VERSION = loadVersion();
    
    // Flag to prevent double initialization (-javaagent + Plugin mode)
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    /**
     * Check if the agent has already been installed.
     * Used by the plugin wrapper to avoid double initialization.
     */
    public static boolean isInstalled() {
        return INSTALLED.get();
    }

    private static String loadVersion() {
        try (InputStream is = DualAuthAgent.class.getResourceAsStream("/version.properties")) {
            Properties prop = new Properties();
            if (is != null) {
                prop.load(is);
                return prop.getProperty("version", "1.0.0-UNKNOWN");
            }
        } catch (Exception e) {
            // Fallback
        }
        return "1.0.0-DEV";
    }

    public static void main(String[] args) {
        if (args.length > 0 && (args[0].equals("--version") || args[0].equals("-v"))) {
            // Use System.out for version to avoid logger initialization issues
            System.out.println("DualAuth Agent version: " + VERSION);
        } else {
            System.out.println("DualAuth Agent v" + VERSION);
            System.out.println("Usage: java -javaagent:dualauth-agent.jar -jar HytaleServer.jar");
            System.out.println("   or: java -jar dualauth-agent.jar --version");
        }
    }

    /**
     * Entry point for -javaagent startup (before main())
     */
    public static void premain(String args, Instrumentation inst) {
        install(args, inst);
    }

    /**
     * Entry point for Dynamic Attach (Plugin mode - after JVM startup)
     */
    public static void agentmain(String args, Instrumentation inst) {
        install(args, inst);
    }

    private static void install(String args, Instrumentation inst) {
        // Prevent double initialization
        if (INSTALLED.getAndSet(true)) {
            System.out.println("[DualAuth] Agent install requested but already active. Skipping.");
            return;
        }

        // Mark globally that we're active (for the plugin bootstrap check)
        System.setProperty("dualauth.agent.active", "true");

        // Handle --version in agent arguments
        if (args != null && (args.contains("version") || args.contains("-v"))) {
            System.out.println("DualAuth Agent version: " + VERSION);
            if (args.equals("version") || args.equals("--version") || args.equals("-v")) {
                return;
            }
        }

        // Enable experimental mode for newer Java versions
        System.setProperty("net.bytebuddy.experimental", "true");
        
        // Startup banner
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║            DualAuth ByteBuddy Agent v" + padRight(VERSION, 24) + "║");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.println("║ Mode: " + padRight((args != null && args.contains("plugin-mode") ? "DYNAMIC (Plugin Attach)" : "STATIC (-javaagent flag)"), 55) + "║");
        System.out.println("║ Configuration:                                               ║");
        System.out.println("║   F2P Domain: " + padRight(DualAuthConfig.F2P_DOMAIN, 47) + "║");
        System.out.println("║   F2P Session URL: " + padRight(DualAuthConfig.F2P_SESSION_URL, 42) + "║");
        System.out.println("║   Official URL: " + padRight(DualAuthConfig.OFFICIAL_SESSION_URL, 45) + "║");
        System.out.println("║   Trust All Issuers: " + padRight(String.valueOf(DualAuthConfig.TRUST_ALL_ISSUERS), 40) + "║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");

        // Start async Warmup
        DualAuthWarmup.start();

        new AgentBuilder.Default()
            .disableClassFormatChanges()
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
            .with(new AgentBuilder.Listener.Adapter() {
                @Override
                public void onTransformation(TypeDescription typeDescription, ClassLoader classLoader,
                        JavaModule module, boolean loaded, DynamicType dynamicType) {
                    System.out.println("✓ TRANSFORMED: " + typeDescription.getName());
                }

                @Override
                public void onError(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded, Throwable throwable) {
                    System.err.println("✗ ERROR transforming " + typeName + ": " + throwable.getMessage());
                    throwable.printStackTrace();
                }
            })
            .ignore(ElementMatchers.nameStartsWith("net.bytebuddy.")
               .or(ElementMatchers.nameStartsWith("jdk."))
               .or(ElementMatchers.nameStartsWith("java."))
               .or(ElementMatchers.nameStartsWith("sun."))
               .or(ElementMatchers.nameStartsWith("ws.sanasol.dualauth.")))
            
            // 1. JWT Logic
            .type(named("com.hypixel.hytale.server.core.auth.JWTValidator"))
            .transform(new JWTValidatorTransformer())

            // 2. Session Logic
            .type(named("com.hypixel.hytale.server.core.auth.SessionServiceClient"))
            .transform(new SessionServiceClientTransformer())

            // 3. Network Packet Logic
            .type(named("com.hypixel.hytale.protocol.packets.auth.AuthGrant"))
            .transform(new AuthGrantTransformer())

            // 4. Handshake Logic
            .type(nameContains("HandshakeHandler"))
            .transform(new HandshakeHandlerTransformer())

            // 5. Manager Logic
            .type(named("com.hypixel.hytale.server.core.auth.ServerAuthManager"))
            .transform(new ServerAuthManagerTransformer())
            
            // 6. Logging Enhancement
            .type(named("com.hypixel.hytale.logger.backend.HytaleLogFormatter"))
            .transform(new LoggingTransformer())
            
            .installOn(inst);
            
        System.out.println("DualAuth Hybrid Agent installed successfully.");
        System.out.println("Waiting for Hytale Server classes to load...");

        // 3. THE "HAMMER": Force retransformation if we are in Plugin mode
        // If the server already started, classes are already loaded and ByteBuddy sometimes ignores them.
        // Here we search for them and force the JVM to re-process them.
        boolean isPluginMode = args != null && args.contains("plugin-mode");
        if (isPluginMode) {
            System.out.println("[DualAuth] Dynamic Mode: Scanning for loaded classes to retransform...");
            long count = 0;
            
            // List of critical classes we know are already loaded
            String[] criticalClasses = {
                "com.hypixel.hytale.server.core.auth.JWTValidator",
                "com.hypixel.hytale.server.core.auth.SessionServiceClient",
                "com.hypixel.hytale.server.core.auth.ServerAuthManager",
                "com.hypixel.hytale.logger.backend.HytaleLogFormatter",
                "com.hypixel.hytale.server.core.io.handlers.login.HandshakeHandler"
            };

            for (Class<?> loadedClass : inst.getAllLoadedClasses()) {
                String name = loadedClass.getName();
                
                // Check if it's one of our target classes
                boolean target = false;
                for (String critical : criticalClasses) {
                    if (name.equals(critical) || (critical.contains("HandshakeHandler") && name.contains("HandshakeHandler"))) {
                        target = true;
                        break;
                    }
                }

                if (target) {
                    try {
                        // This triggers the transformer registered above
                        inst.retransformClasses(loadedClass);
                        count++;
                    } catch (Throwable e) {
                        System.err.println("[DualAuth] Failed to force retransform " + name + ": " + e.getMessage());
                    }
                }
            }
            System.out.println("[DualAuth] Force-retransformation complete. Processed " + count + " critical classes.");
        }
    }

    private static String padRight(String s, int length) {
        if (s == null) s = "null";
        if (s.length() >= length) return s.substring(0, length);
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < length) {
            sb.append(' ');
        }
        return sb.toString();
    }
}
