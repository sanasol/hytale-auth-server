package ws.sanasol.dualauth.plugin;

import com.hypixel.hytale.plugin.early.ClassTransformer;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.pool.TypePool;
import ws.sanasol.dualauth.agent.DualAuthAgent;
import ws.sanasol.dualauth.agent.DualAuthConfig;
import ws.sanasol.dualauth.agent.transformers.*;

import net.bytebuddy.dynamic.loading.ClassInjector;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Early plugin entry point for DualAuth.
 * Provides full bytecode transformation (like -javaagent) without needing Instrumentation.
 *
 * The early plugin system calls transform() for every class loaded by the TransformingClassLoader.
 * We use ByteBuddy's redefine API (no Instrumentation) to apply the same Advice transformations
 * that the agent uses.
 *
 * Loading modes (same JAR, different entry points):
 * 1. -javaagent: premain → DualAuthAgent.install() with Instrumentation
 * 2. mods/: DualAuthBootstrap → dynamic attach or reflection fallback
 * 3. earlyplugins/: DualAuthEarlyPlugin → bytecode transform at class load time (this class)
 */
public class DualAuthEarlyPlugin implements ClassTransformer {

    private static final String AGENT_ACTIVE_PROPERTY = "dualauth.agent.active";

    private static final Set<String> TARGET_CLASSES = Set.of(
        "com.hypixel.hytale.server.core.auth.JWTValidator",
        "com.hypixel.hytale.server.core.auth.SessionServiceClient",
        "com.hypixel.hytale.server.core.auth.ProfileServiceClient",
        "com.hypixel.hytale.protocol.packets.auth.AuthGrant",
        "com.hypixel.hytale.server.core.auth.ServerAuthManager",
        "com.hypixel.hytale.logger.backend.HytaleLogFormatter",
        "com.hypixel.hytale.server.core.command.system.CommandManager"
    );

    // HandshakeHandler uses nameContains in the agent, so we check separately
    private static final String HANDSHAKE_HANDLER = "HandshakeHandler";

    private volatile boolean initialized = false;
    private Object cachedUnsafe;

    // Transformers (same as agent uses)
    private final JWTValidatorTransformer jwtValidatorTransformer = new JWTValidatorTransformer();
    private final SessionServiceClientTransformer sessionServiceClientTransformer = new SessionServiceClientTransformer();
    private final ProfileServiceClientTransformer profileServiceClientTransformer = new ProfileServiceClientTransformer();
    private final AuthGrantTransformer authGrantTransformer = new AuthGrantTransformer();
    private final HandshakeHandlerTransformer handshakeHandlerTransformer = new HandshakeHandlerTransformer();
    private final ServerAuthManagerTransformer serverAuthManagerTransformer = new ServerAuthManagerTransformer();
    private final LoggingTransformer loggingTransformer = new LoggingTransformer();
    private final ChatCommandTransformer chatCommandTransformer = new ChatCommandTransformer();

    @Override
    public int priority() {
        // High priority to run before other early plugins
        return 100;
    }

    @Override
    public byte[] transform(String className, String internalName, byte[] classBytes) {
        if (!initialized) {
            initialize();
        }

        // If javaagent/bootstrap install already claimed ownership, skip entirely.
        // ServiceLoader may load this transformer in a different classloader, so relying
        // only on DualAuthAgent.isInstalled() is insufficient.
        if (isAnotherModeAlreadyActive()) {
            return null;
        }

        // Check if this is a target class
        if (!isTargetClass(className)) {
            return null; // null = no transformation
        }

        try {
            return applyTransformation(className, classBytes);
        } catch (Exception e) {
            System.err.println("[DualAuth-Early] Failed to transform " + className + ": " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private boolean isTargetClass(String className) {
        if (TARGET_CLASSES.contains(className)) return true;
        // Match HandshakeHandler but NOT inner classes like HandshakeHandler$AuthState
        return className.contains(HANDSHAKE_HANDLER) && !className.contains("$");
    }

    private synchronized void initialize() {
        if (initialized) return;

        // Skip if javaagent/bootstrap install already installed. This must use a global
        // System property because the early plugin can be loaded in a different classloader
        // from the bootstrap-loaded DualAuthAgent class.
        if (isAnotherModeAlreadyActive()) {
            System.out.println("[DualAuth-Early] Agent already active via -javaagent. Skipping early plugin initialization.");
            initialized = true;
            return;
        }

        claimEarlyPluginMode();
        initialized = true;

        System.out.println("[DualAuth-Early] Initializing DualAuth Early Plugin...");

        // 1. Set experimental mode for newer Java
        System.setProperty("net.bytebuddy.experimental", "true");

        // 2. Mark as active was already done before initialization to avoid races.

        // 3. Initialize ClassFileLocator from our JAR
        try {
            File ourJar = new File(
                getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
            DualAuthAgent.CLASS_FILE_LOCATOR = ClassFileLocator.ForJarFile.of(ourJar);
        } catch (Exception e) {
            DualAuthAgent.CLASS_FILE_LOCATOR = ClassFileLocator.ForClassLoader.of(getClass().getClassLoader());
        }

        // 4. Initialize logging system property
        LoggingTransformer.initSystemProperty();

        // 5. Inject our JAR into the TransformingClassLoader so DualAuth runtime
        //    classes are visible to transformed server classes
        injectIntoTransformingClassLoader();

        // 6. Startup banner
        String version = DualAuthAgent.VERSION;
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║            DualAuth Early Plugin v" + padRight(version, 28) + "║");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.println("║ Mode: EARLY PLUGIN (ClassTransformer)                        ║");
        System.out.println("║ Configuration:                                               ║");
        System.out.println("║   F2P Domain: " + padRight(DualAuthConfig.F2P_DOMAIN, 47) + "║");
        System.out.println("║   F2P Session URL: " + padRight(DualAuthConfig.F2P_SESSION_URL, 42) + "║");
        System.out.println("║   Official URL: " + padRight(DualAuthConfig.OFFICIAL_SESSION_URL, 45) + "║");
        System.out.println("║   Trust All Issuers: " + padRight(String.valueOf(DualAuthConfig.TRUST_ALL_ISSUERS), 40) + "║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
    }

    private boolean isAnotherModeAlreadyActive() {
        String active = System.getProperty(AGENT_ACTIVE_PROPERTY);
        if (active != null && !active.isEmpty() && !"early-plugin".equals(active)) {
            return true;
        }
        return DualAuthAgent.isInstalled();
    }

    private void claimEarlyPluginMode() {
        String existing = System.getProperty(AGENT_ACTIVE_PROPERTY);
        if (existing == null || existing.isEmpty()) {
            System.setProperty(AGENT_ACTIVE_PROPERTY, "early-plugin");
        }
    }

    /**
     * Make DualAuth runtime classes visible to transformed server classes.
     *
     * Java 25 removed ClassLoader.parent field and Unsafe.defineClass, so we try multiple strategies:
     * 1. Reparent via Unsafe (works on older JVMs)
     * 2. URLClassLoader.addURL() with Unsafe-forced accessibility (adds entire JAR)
     * 3. ClassLoader.defineClass() with Unsafe-forced accessibility (inject individual classes)
     * 4. ByteBuddy ClassInjector (handles Java version quirks internally)
     */
    private void injectIntoTransformingClassLoader() {
        ClassLoader contextCL = Thread.currentThread().getContextClassLoader();
        ClassLoader pluginCL = getClass().getClassLoader();

        if (contextCL == null || pluginCL == null) {
            System.err.println("[DualAuth-Early] WARNING: Missing classloader reference");
            return;
        }

        // Log classloader info for debugging
        System.out.println("[DualAuth-Early] TransformingCL: " + contextCL.getClass().getName());
        System.out.println("[DualAuth-Early] PluginCL: " + pluginCL.getClass().getName());

        boolean injected = false;

        // Strategy 1: Try reparenting ClassLoader.parent
        if (!injected) injected = tryReparentClassLoader(contextCL, pluginCL);

        // Strategy 2: Try adding our JAR URL to TransformingClassLoader
        if (!injected) injected = tryAddJarUrl(contextCL);

        // Pre-collect runtime classes for strategies 3 and 4
        Map<String, byte[]> runtimeClasses = null;
        if (!injected) {
            try {
                File ourJar = new File(
                    getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
                runtimeClasses = collectRuntimeClasses(ourJar);
                System.out.println("[DualAuth-Early] Collected " + runtimeClasses.size() + " runtime classes for injection");
            } catch (Exception e) {
                System.err.println("[DualAuth-Early] Failed to collect runtime classes: " + e.getMessage());
            }
        }

        // Strategy 3: Inject individual classes via ClassLoader.defineClass + Unsafe accessibility
        if (!injected && runtimeClasses != null) injected = tryInjectViaDefineClass(contextCL, runtimeClasses);

        // Strategy 4: ByteBuddy ClassInjector
        if (!injected && runtimeClasses != null) injected = tryInjectViaBytebuddy(contextCL, runtimeClasses);

        if (!injected) {
            System.err.println("[DualAuth-Early] CRITICAL: All injection strategies failed!");
            System.err.println("[DualAuth-Early] DualAuth runtime classes will NOT be visible to transformed classes!");
            return;
        }

        // After successful injection, trigger warmup via TransformingCL's classes.
        // This is critical: DualAuthWarmup.start() in plugin CL stores F2P tokens
        // in the plugin CL's DualServerTokenManager, but advice code runs in TransformingCL.
        // We must also trigger warmup in TransformingCL so F2P tokens are available there.
        if (!verifyInjectedRuntimeClasses(contextCL)) {
            System.err.println("[DualAuth-Early] WARNING: Skipping warmup because injected runtime classes are not fully available in TransformingCL");
            System.err.println("[DualAuth-Early] F2P tokens will be fetched lazily on first real use");
            return;
        }
        triggerWarmupInTargetClassLoader(contextCL);
    }

    /**
     * Trigger F2P token warmup using the TransformingCL's copy of the classes.
     * Without this, F2P server identity tokens would only exist in the plugin CL's
     * DualServerTokenManager (unreachable by advice code in TransformingCL).
     */
    private void triggerWarmupInTargetClassLoader(ClassLoader targetCL) {
        try {
            Class<?> warmupClass = targetCL.loadClass("ws.sanasol.dualauth.agent.DualAuthWarmup");
            warmupClass.getMethod("start").invoke(null);
            System.out.println("[DualAuth-Early] Warmup triggered in TransformingClassLoader context");
        } catch (Exception e) {
            System.err.println("[DualAuth-Early] WARNING: Failed to trigger warmup in TransformingCL: " + e.getMessage());
            System.err.println("[DualAuth-Early] F2P server identity tokens may not be available");
        }
    }

    private boolean verifyInjectedRuntimeClasses(ClassLoader targetCL) {
        String[] requiredClasses = new String[] {
            "ws.sanasol.dualauth.agent.DualAuthWarmup",
            "ws.sanasol.dualauth.server.DualServerTokenManager",
            "ws.sanasol.dualauth.server.DualServerIdentity",
            "ws.sanasol.dualauth.context.DualAuthHelper",
            "ws.sanasol.dualauth.libs.google.gson.Gson"
        };

        for (String className : requiredClasses) {
            try {
                Class<?> cls = targetCL.loadClass(className);
                System.out.println("[DualAuth-Early] Verified runtime class: " + cls.getName());
            } catch (Throwable t) {
                System.err.println("[DualAuth-Early] Missing runtime class in TransformingCL: " + className);
                System.err.println("[DualAuth-Early] Verification failure: " + t);
                return false;
            }
        }

        return true;
    }

    private boolean tryReparentClassLoader(ClassLoader target, ClassLoader newParent) {
        try {
            Object unsafe = getUnsafe();
            if (unsafe == null) return false;

            // Search for 'parent' field in ClassLoader hierarchy
            Field parentField = null;
            for (Class<?> c = ClassLoader.class; c != null; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getName().equals("parent") && ClassLoader.class.isAssignableFrom(f.getType())) {
                        parentField = f;
                        break;
                    }
                }
                if (parentField != null) break;
            }

            if (parentField == null) {
                System.out.println("[DualAuth-Early] Strategy 1: ClassLoader.parent field not found");
                return false;
            }

            Class<?> unsafeClass = unsafe.getClass();
            long parentOffset = (long) unsafeClass.getMethod("objectFieldOffset", Field.class)
                .invoke(unsafe, parentField);
            Object oldParent = unsafeClass.getMethod("getObject", Object.class, long.class)
                .invoke(unsafe, target, parentOffset);
            unsafeClass.getMethod("putObject", Object.class, long.class, Object.class)
                .invoke(unsafe, target, parentOffset, newParent);

            System.out.println("[DualAuth-Early] Strategy 1: Reparented TransformingClassLoader");
            System.out.println("[DualAuth-Early]   Old parent: " + (oldParent != null ? oldParent.getClass().getName() : "null"));
            System.out.println("[DualAuth-Early]   New parent: " + newParent.getClass().getName());
            return true;
        } catch (Exception e) {
            System.out.println("[DualAuth-Early] Strategy 1 failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Add our JAR URL to TransformingClassLoader's classpath via URLClassLoader.addURL().
     * Uses Unsafe to bypass Java module system restrictions on setAccessible().
     */
    private boolean tryAddJarUrl(ClassLoader targetCL) {
        try {
            if (!(targetCL instanceof URLClassLoader)) {
                System.out.println("[DualAuth-Early] Strategy 2: Not a URLClassLoader");
                return false;
            }

            URL jarUrl = getClass().getProtectionDomain().getCodeSource().getLocation();
            Method addUrlMethod = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);

            // Force accessibility via Unsafe (bypasses module system)
            forceAccessible(addUrlMethod);

            addUrlMethod.invoke(targetCL, jarUrl);
            System.out.println("[DualAuth-Early] Strategy 2: Added JAR URL to TransformingClassLoader");
            return true;
        } catch (Exception e) {
            System.out.println("[DualAuth-Early] Strategy 2 failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Inject individual class bytecode via ClassLoader.defineClass() with forced accessibility.
     */
    private boolean tryInjectViaDefineClass(ClassLoader targetCL, Map<String, byte[]> classMap) {
        try {
            Method defineClassMethod = ClassLoader.class.getDeclaredMethod("defineClass",
                String.class, byte[].class, int.class, int.class);
            forceAccessible(defineClassMethod);

            int injected = 0;
            int skipped = 0;
            for (Map.Entry<String, byte[]> entry : classMap.entrySet()) {
                try {
                    defineClassMethod.invoke(targetCL, entry.getKey(), entry.getValue(), 0, entry.getValue().length);
                    injected++;
                } catch (Exception e) {
                    skipped++;
                }
            }

            System.out.println("[DualAuth-Early] Strategy 3: Injected " + injected + " classes"
                + (skipped > 0 ? " (" + skipped + " skipped)" : ""));
            return injected > 0;
        } catch (Exception e) {
            System.out.println("[DualAuth-Early] Strategy 3 failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Use ByteBuddy's ClassInjector which handles Java version differences internally.
     */
    private boolean tryInjectViaBytebuddy(ClassLoader targetCL, Map<String, byte[]> classMap) {
        try {
            // Try UsingReflection first
            try {
                new ClassInjector.UsingReflection(targetCL).injectRaw(classMap);
                System.out.println("[DualAuth-Early] Strategy 4: Injected via ClassInjector.UsingReflection");
                return true;
            } catch (Exception e) {
                System.out.println("[DualAuth-Early] Strategy 4a (UsingReflection) failed: " + e.getMessage());
            }

            // Try UsingUnsafe
            try {
                new ClassInjector.UsingUnsafe(targetCL).injectRaw(classMap);
                System.out.println("[DualAuth-Early] Strategy 4: Injected via ClassInjector.UsingUnsafe");
                return true;
            } catch (Exception e) {
                System.out.println("[DualAuth-Early] Strategy 4b (UsingUnsafe) failed: " + e.getMessage());
            }

            return false;
        } catch (Exception e) {
            System.out.println("[DualAuth-Early] Strategy 4 failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Collect DualAuth runtime class bytes from the agent JAR.
     * Only includes classes referenced by advice code at runtime (see isRuntimeClass).
     * Includes shaded runtime deps needed by injected classes in early-plugin mode.
     */
    private Map<String, byte[]> collectRuntimeClasses(File jarFile) throws Exception {
        Map<String, byte[]> classMap = new HashMap<>();
        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();

                if (!name.endsWith(".class")) continue;
                if (!name.startsWith("ws/sanasol/dualauth/")) continue;
                // Only inject runtime classes referenced by advice code at runtime.
                // Also include shaded runtime libs needed by those classes.
                if (!isRuntimeClass(name)) continue;

                String className = name.substring(0, name.length() - 6).replace('/', '.');
                try (InputStream is = jar.getInputStream(entry)) {
                    classMap.put(className, is.readAllBytes());
                }
            }
        }
        return classMap;
    }

    /**
     * Check if a JAR entry is a runtime class that should be injected into TransformingCL.
     * Only includes classes actually referenced by Advice code at runtime.
     * Excludes: plugin/ (already loaded) and ByteBuddy-heavy agent infrastructure.
     */
    private static boolean isRuntimeClass(String entryName) {
        // Whitelist: packages containing classes referenced by advice code
        if (entryName.startsWith("ws/sanasol/dualauth/context/")) return true;      // DualAuthHelper, DualAuthContext
        if (entryName.startsWith("ws/sanasol/dualauth/fetcher/")) return true;      // DualJwksFetcher
        if (entryName.startsWith("ws/sanasol/dualauth/server/")) return true;       // DualServerIdentity, DualServerTokenManager
        if (entryName.startsWith("ws/sanasol/dualauth/commands/")) return true;     // DualAuthCommands
        if (entryName.startsWith("ws/sanasol/dualauth/protection/")) return true;   // IdentityProtectionChecker
        if (entryName.startsWith("ws/sanasol/dualauth/embedded/")) return true;     // EmbeddedJwkVerifier
        // DualAuthConfig + DualAuthWarmup from agent package (no ByteBuddy refs)
        if (entryName.startsWith("ws/sanasol/dualauth/agent/DualAuthConfig")) return true;
        if (entryName.startsWith("ws/sanasol/dualauth/agent/DualAuthWarmup")) return true;
        // Shaded runtime libraries used by injected classes
        if (entryName.startsWith("ws/sanasol/dualauth/libs/google/gson/")) return true;
        return false;
    }

    /**
     * Force a Method/Field to be accessible by directly setting the AccessibleObject.override
     * field via Unsafe. This bypasses Java module system restrictions that block setAccessible().
     */
    private void forceAccessible(AccessibleObject obj) throws Exception {
        try {
            obj.setAccessible(true);
            return; // Worked without Unsafe
        } catch (Exception ignored) {}

        Object unsafe = getUnsafe();
        if (unsafe == null) throw new RuntimeException("Unsafe not available");
        Class<?> unsafeClass = unsafe.getClass();

        // AccessibleObject.override is the internal boolean that setAccessible() normally sets
        Field overrideField = AccessibleObject.class.getDeclaredField("override");
        long overrideOffset = (long) unsafeClass.getMethod("objectFieldOffset", Field.class)
            .invoke(unsafe, overrideField);
        unsafeClass.getMethod("putBoolean", Object.class, long.class, boolean.class)
            .invoke(unsafe, obj, overrideOffset, true);
    }

    private Object getUnsafe() {
        if (cachedUnsafe != null) return cachedUnsafe;
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            cachedUnsafe = unsafeField.get(null);
            return cachedUnsafe;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Apply ByteBuddy transformation to a target class using the same Advice classes
     * as the agent mode. Uses ByteBuddy's redefine API (no Instrumentation needed).
     */
    private byte[] applyTransformation(String className, byte[] classBytes) throws Exception {
        // Create a compound locator:
        // 1. Target class bytes (the class being transformed)
        // 2. TransformingClassLoader (for resolving server class dependencies - parent classes, field types)
        // 3. Our advice classes (from the agent JAR)
        ClassFileLocator targetLocator = ClassFileLocator.Simple.of(className, classBytes);
        ClassFileLocator serverLocator = ClassFileLocator.ForClassLoader.of(
            Thread.currentThread().getContextClassLoader());
        ClassFileLocator adviceLocator = DualAuthAgent.CLASS_FILE_LOCATOR;
        ClassFileLocator compound = new ClassFileLocator.Compound(
            targetLocator, serverLocator, adviceLocator);

        TypePool typePool = TypePool.Default.of(compound);
        TypeDescription typeDesc = typePool.describe(className).resolve();

        // Start with ByteBuddy redefine (no Instrumentation)
        DynamicType.Builder<?> builder = new ByteBuddy()
            .redefine(typeDesc, compound);

        // Apply the appropriate transformer based on class name
        if (className.equals("com.hypixel.hytale.server.core.auth.JWTValidator")) {
            builder = jwtValidatorTransformer.transform(builder, typeDesc, null, null, null);
            System.out.println("[DualAuth-Early] ✓ Transformed: JWTValidator");
        } else if (className.equals("com.hypixel.hytale.server.core.auth.SessionServiceClient")) {
            builder = sessionServiceClientTransformer.transform(builder, typeDesc, null, null, null);
            System.out.println("[DualAuth-Early] ✓ Transformed: SessionServiceClient");
        } else if (className.equals("com.hypixel.hytale.server.core.auth.ProfileServiceClient")) {
            builder = profileServiceClientTransformer.transform(builder, typeDesc, null, null, null);
            System.out.println("[DualAuth-Early] ✓ Transformed: ProfileServiceClient");
        } else if (className.equals("com.hypixel.hytale.protocol.packets.auth.AuthGrant")) {
            builder = authGrantTransformer.transform(builder, typeDesc, null, null, null);
            System.out.println("[DualAuth-Early] ✓ Transformed: AuthGrant");
        } else if (className.contains(HANDSHAKE_HANDLER)) {
            builder = handshakeHandlerTransformer.transform(builder, typeDesc, null, null, null);
            System.out.println("[DualAuth-Early] ✓ Transformed: " + className);
        } else if (className.equals("com.hypixel.hytale.server.core.auth.ServerAuthManager")) {
            builder = serverAuthManagerTransformer.transform(builder, typeDesc, null, null, null);
            System.out.println("[DualAuth-Early] ✓ Transformed: ServerAuthManager");
        } else if (className.equals("com.hypixel.hytale.logger.backend.HytaleLogFormatter")) {
            builder = loggingTransformer.transform(builder, typeDesc, null, null, null);
            System.out.println("[DualAuth-Early] ✓ Transformed: HytaleLogFormatter");
        } else if (className.equals("com.hypixel.hytale.server.core.command.system.CommandManager")) {
            builder = chatCommandTransformer.transform(builder, typeDesc, null, null, null);
            System.out.println("[DualAuth-Early] ✓ Transformed: CommandManager");
        } else {
            return null;
        }

        return builder.make().getBytes();
    }

    private static String padRight(String s, int n) {
        if (s.length() >= n) return s.substring(0, n);
        return s + " ".repeat(n - s.length());
    }
}
