package ws.sanasol.dualauth.plugin;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import net.bytebuddy.agent.ByteBuddyAgent;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * BRIDGE CLASS: This runs inside Hytale's "ThirdPartyPlugin" ClassLoader.
 *
 * CONFLICT PREVENTION STRATEGY:
 * 1. Checks 'dualauth.agent.active' System Property immediately.
 * 2. If true (Agent loaded via -javaagent), it logs "Passive Mode" and stops.
 * 3. If false, it performs the Dynamic Attach (Injection).
 * 4. If Dynamic Attach fails (no EnableDynamicAgentLoading), falls back to Reflection Mode.
 */
public class DualAuthBootstrap extends JavaPlugin {

    private volatile boolean reflectionMode = false;

    public DualAuthBootstrap(JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        // Check if the agent is already running (loaded at startup with -javaagent)
        if (System.getProperty("dualauth.agent.active") != null) {
            System.out.println("[DualAuth] Plugin Wrapper: Agent is ALREADY ACTIVE via -javaagent flag.");
            System.out.println("[DualAuth] Plugin Wrapper: Entering PASSIVE mode (no action taken).");
            return;
        }

        System.out.println("[DualAuth] Plugin Wrapper: Agent not detected. Initializing Dynamic Attach...");
        injectAgent();
    }

    @Override
    protected void start() {
        if (reflectionMode) {
            System.out.println("[DualAuth] Reflection Fallback: Initializing in start() phase...");
            initReflectionFallback();
        }
    }

    private void injectAgent() {
        try {
            // Double verification for safety
            if (System.getProperty("dualauth.agent.active") != null) return;

            // 1. Install ByteBuddy Agent to get Instrumentation
            Instrumentation inst = ByteBuddyAgent.install();

            // 2. Get the location of THIS jar file
            File currentJar = new File(getClass().getProtectionDomain().getCodeSource().getLocation().toURI());

            // 3. INJECT INTO BOOTSTRAP CLASSLOADER (not system!)
            boolean addedToBootstrap = false;
            try {
                File bootstrapJar = createBootstrapJar(currentJar);
                inst.appendToBootstrapClassLoaderSearch(new JarFile(bootstrapJar));
                addedToBootstrap = true;
            } catch (Exception e) {
                System.err.println("[DualAuth] Could not add agent to bootstrap classpath: " + e.getMessage());
            }

            if (addedToBootstrap) {
                inst.appendToSystemClassLoaderSearch(new JarFile(currentJar));
                Class<?> agentClass = Class.forName(
                    "ws.sanasol.dualauth.agent.DualAuthAgent", true, null);
                Method agentMainMethod = agentClass.getMethod("agentmain", String.class, Instrumentation.class);
                agentMainMethod.invoke(null, "plugin-mode", inst);
            } else {
                System.err.println("[DualAuth] WARNING: Falling back to system classloader. " +
                    "Transformed classes may not find agent helpers.");
                inst.appendToSystemClassLoaderSearch(new JarFile(currentJar));
                ClassLoader systemLoader = ClassLoader.getSystemClassLoader();
                Class<?> agentClass = systemLoader.loadClass("ws.sanasol.dualauth.agent.DualAuthAgent");
                Method agentMainMethod = agentClass.getMethod("agentmain", String.class, Instrumentation.class);
                agentMainMethod.invoke(null, "plugin-mode", inst);
            }

            // Mark success
            System.setProperty("dualauth.agent.active", "true");

        } catch (Throwable e) {
            System.err.println("[DualAuth] Dynamic agent attach failed: " + e.getMessage());
            System.err.println("[DualAuth] Switching to REFLECTION FALLBACK MODE...");
            reflectionMode = true;
        }
    }

    /**
     * Reflection Fallback: When dynamic agent attachment fails (no -XX:+EnableDynamicAgentLoading),
     * we create subclass-based replacements for JWTValidator and SessionServiceClient that route
     * tokens to the correct backend based on issuer.
     *
     * This does NOT require Instrumentation — just reflection to replace HandshakeHandler's
     * static volatile fields before the first player connects.
     */
    private void initReflectionFallback() {
        try {
            // Read F2P config from env vars (same logic as DualAuthConfig)
            String f2pDomain = System.getenv("HYTALE_AUTH_DOMAIN");
            if (f2pDomain == null || f2pDomain.isEmpty()) {
                f2pDomain = System.getenv("HYTALE_AUTH_SERVER");
            }
            if (f2pDomain == null || f2pDomain.isEmpty()) {
                f2pDomain = "auth.sanasol.ws";
            }

            boolean isLocalhost = f2pDomain.equals("localhost") || f2pDomain.startsWith("localhost:");
            String protocol = isLocalhost ? "http" : "https";
            String f2pSessionUrl = protocol + "://" + f2pDomain;
            String officialSessionUrl = "https://sessions.hytale.com";

            // Get server audience — must use the real value from AuthConfig
            String audience = null;
            try {
                // Try to get the real audience from AuthConfig.getServerAudience()
                // This calls ServerAuthManager.getInstance().getServerSessionId() internally
                ClassLoader pluginCL = getClass().getClassLoader();
                Class<?> authConfigClass = pluginCL.loadClass(
                        "com.hypixel.hytale.server.core.auth.AuthConfig");
                Method getAudience = authConfigClass.getMethod("getServerAudience");
                audience = (String) getAudience.invoke(null);
                System.out.println("[DualAuth-Reflection] Got real server audience: " + audience);
            } catch (Exception e) {
                System.err.println("[DualAuth-Reflection] Could not get audience from AuthConfig: " + e.getMessage());
            }
            if (audience == null || audience.isEmpty()) {
                audience = System.getenv("HYTALE_SERVER_AUDIENCE");
            }
            if (audience == null || audience.isEmpty()) {
                audience = "00000000-0000-0000-0000-000000000001";
            }

            System.out.println("[DualAuth] ╔══════════════════════════════════════════════════╗");
            System.out.println("[DualAuth] ║     REFLECTION FALLBACK MODE                     ║");
            System.out.println("[DualAuth] ║     No Instrumentation — Subclass-based auth     ║");
            System.out.println("[DualAuth] ╚══════════════════════════════════════════════════╝");
            System.out.println("[DualAuth] F2P Domain: " + f2pDomain);
            System.out.println("[DualAuth] F2P Session URL: " + f2pSessionUrl);
            System.out.println("[DualAuth] Server Audience: " + audience);

            // Find the server's classloader by locating the real JWTValidator class
            ClassLoader serverCL = findServerClassLoader();
            if (serverCL == null) {
                System.err.println("[DualAuth-Reflection] FATAL: Could not find server classloader!");
                return;
            }
            System.out.println("[DualAuth-Reflection] Server classloader: " + serverCL.getClass().getName());

            // Inject our subclasses into the server's classloader
            injectSubclasses(serverCL);

            // Load the real server auth classes from the server's classloader
            Class<?> jwtValidatorClass = serverCL.loadClass(
                    "com.hypixel.hytale.server.core.auth.JWTValidator");
            Class<?> sessionServiceClientClass = serverCL.loadClass(
                    "com.hypixel.hytale.server.core.auth.SessionServiceClient");
            Class<?> handshakeHandlerClass = serverCL.loadClass(
                    "com.hypixel.hytale.server.core.io.handlers.login.HandshakeHandler");

            // Load our injected subclasses from the server's classloader
            Class<?> dualValidatorClass = serverCL.loadClass(
                    "ws.sanasol.dualauth.plugin.DualJWTValidator");
            Class<?> dualClientClass = serverCL.loadClass(
                    "ws.sanasol.dualauth.plugin.DualSessionServiceClient");

            // Create F2P SessionServiceClient (standard server class, F2P URL)
            Constructor<?> sscCtor = sessionServiceClientClass.getConstructor(String.class);
            Object f2pSSC = sscCtor.newInstance(f2pSessionUrl);

            // Create F2P JWTValidator (standard server class, F2P issuer + F2P SSC)
            Constructor<?> jwtCtor = jwtValidatorClass.getConstructor(
                    sessionServiceClientClass, String.class, String.class);
            Object f2pValidator = jwtCtor.newInstance(f2pSSC, f2pSessionUrl, audience);

            // Create DualSessionServiceClient (our subclass, official URL as base)
            Constructor<?> dualClientCtor = dualClientClass.getConstructor(String.class);
            Object dualSSC = dualClientCtor.newInstance(officialSessionUrl);

            // Set the F2P client on DualSessionServiceClient
            Field f2pClientField = dualClientClass.getField("f2pClient");
            f2pClientField.set(dualSSC, f2pSSC);

            // Create DualJWTValidator (our subclass, official issuer as base, dual SSC)
            Constructor<?> dualValidatorCtor = dualValidatorClass.getConstructor(
                    sessionServiceClientClass, String.class, String.class);
            Object dualValidator = dualValidatorCtor.newInstance(dualSSC, officialSessionUrl, audience);

            // Set the F2P validator on DualJWTValidator
            Field f2pValidatorField = dualValidatorClass.getField("f2pValidator");
            f2pValidatorField.set(dualValidator, f2pValidator);

            // Replace HandshakeHandler's static volatile fields
            boolean replacedValidator = replaceStaticField(
                    handshakeHandlerClass, "jwtValidator", dualValidator);
            boolean replacedClient = replaceStaticField(
                    handshakeHandlerClass, "sessionServiceClient", dualSSC);

            if (replacedValidator && replacedClient) {
                System.out.println("[DualAuth-Reflection] Successfully replaced HandshakeHandler fields!");
                System.out.println("[DualAuth-Reflection] Official tokens → official JWKS (sessions.hytale.com)");
                System.out.println("[DualAuth-Reflection] F2P tokens → F2P JWKS (" + f2pDomain + ")");
                System.setProperty("dualauth.agent.active", "reflection");
            } else {
                System.err.println("[DualAuth-Reflection] WARNING: Partial replacement — " +
                        "validator=" + replacedValidator + ", client=" + replacedClient);
                if (replacedValidator || replacedClient) {
                    System.setProperty("dualauth.agent.active", "reflection-partial");
                }
            }

        } catch (Throwable e) {
            System.err.println("[DualAuth-Reflection] CRITICAL: Reflection fallback failed!");
            e.printStackTrace();
        }
    }

    /**
     * Find the server's classloader by attempting to load the real JWTValidator.
     * Tries several strategies since Hytale uses a complex classloader hierarchy.
     */
    private ClassLoader findServerClassLoader() {
        // Strategy 1: Walk up this plugin's classloader chain to find TransformingClassLoader
        ClassLoader cl = getClass().getClassLoader();
        while (cl != null) {
            try {
                // Check if this classloader can see the server auth classes
                cl.loadClass("com.hypixel.hytale.server.core.auth.JWTValidator");
                return cl;
            } catch (ClassNotFoundException e) {
                cl = cl.getParent();
            }
        }

        // Strategy 2: Try the context classloader
        try {
            ClassLoader contextCL = Thread.currentThread().getContextClassLoader();
            if (contextCL != null) {
                contextCL.loadClass("com.hypixel.hytale.server.core.auth.JWTValidator");
                return contextCL;
            }
        } catch (ClassNotFoundException ignored) {}

        // Strategy 3: Try system classloader
        try {
            ClassLoader systemCL = ClassLoader.getSystemClassLoader();
            systemCL.loadClass("com.hypixel.hytale.server.core.auth.JWTValidator");
            return systemCL;
        } catch (ClassNotFoundException ignored) {}

        return null;
    }

    /**
     * Inject DualJWTValidator and DualSessionServiceClient bytecode into the server's classloader.
     * Uses sun.misc.Unsafe.defineClass for maximum compatibility.
     */
    private void injectSubclasses(ClassLoader targetCL) throws Exception {
        String[] classNames = {
            "ws/sanasol/dualauth/plugin/DualJWTValidator.class",
            "ws/sanasol/dualauth/plugin/DualSessionServiceClient.class"
        };

        for (String className : classNames) {
            // Check if already loaded (e.g., if classloader can already see our classes)
            String dotName = className.replace('/', '.').replace(".class", "");
            try {
                targetCL.loadClass(dotName);
                System.out.println("[DualAuth-Reflection] Class already visible: " + dotName);
                continue;
            } catch (ClassNotFoundException expected) {
                // Need to inject
            }

            // Read class bytes from our JAR
            byte[] classBytes = readClassFromJar(className);
            if (classBytes == null) {
                throw new RuntimeException("Could not read " + className + " from agent JAR");
            }

            // Inject via Unsafe.defineClass
            defineClass(targetCL, dotName, classBytes);
            System.out.println("[DualAuth-Reflection] Injected: " + dotName);
        }
    }

    /**
     * Read class bytecode from the agent JAR file.
     */
    private byte[] readClassFromJar(String entryName) {
        try {
            // Try loading from our own classloader first (most reliable)
            try (InputStream is = getClass().getClassLoader().getResourceAsStream(entryName)) {
                if (is != null) {
                    return is.readAllBytes();
                }
            }

            // Fallback: read from the JAR file directly
            File jarFile = new File(getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
            try (JarFile jar = new JarFile(jarFile)) {
                JarEntry entry = jar.getJarEntry(entryName);
                if (entry != null) {
                    try (InputStream is = jar.getInputStream(entry)) {
                        return is.readAllBytes();
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[DualAuth-Reflection] Failed to read class bytes: " + entryName + " - " + e.getMessage());
        }
        return null;
    }

    /**
     * Define a class in the target classloader using Unsafe.defineClass.
     */
    private void defineClass(ClassLoader targetCL, String className, byte[] classBytes) throws Exception {
        // Get sun.misc.Unsafe instance
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);

        // Call Unsafe.defineClass(name, bytes, offset, length, classLoader, protectionDomain)
        Method defineClassMethod = unsafeClass.getMethod("defineClass",
                String.class, byte[].class, int.class, int.class,
                ClassLoader.class, java.security.ProtectionDomain.class);
        defineClassMethod.invoke(unsafe, className, classBytes, 0, classBytes.length,
                targetCL, null);
    }

    /**
     * Replace a private static volatile field on a class via reflection.
     */
    private boolean replaceStaticField(Class<?> clazz, String fieldName, Object newValue) {
        try {
            Field field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(null, newValue);
            System.out.println("[DualAuth-Reflection] Replaced " + clazz.getSimpleName() + "." + fieldName);
            return true;
        } catch (NoSuchFieldException e) {
            // Try finding the field in a broader search (field might have different name)
            System.err.println("[DualAuth-Reflection] Field not found: " + clazz.getSimpleName() + "." + fieldName);
            System.err.println("[DualAuth-Reflection] Available fields:");
            for (Field f : clazz.getDeclaredFields()) {
                System.err.println("[DualAuth-Reflection]   " + f.getType().getSimpleName() + " " + f.getName());
            }
            return false;
        } catch (Exception e) {
            System.err.println("[DualAuth-Reflection] Failed to replace " + fieldName + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Creates a filtered copy of the agent JAR for bootstrap classloader injection.
     * Excludes plugin classes (DualAuthBootstrap) and manifest.json to prevent the
     * Hytale PluginManager from discovering the bootstrap JAR as a plugin.
     */
    private static File createBootstrapJar(File sourceJar) throws Exception {
        File tempJar = File.createTempFile("dualauth-bootstrap-", ".jar");
        tempJar.deleteOnExit();
        try (JarFile source = new JarFile(sourceJar);
             JarOutputStream target = new JarOutputStream(new FileOutputStream(tempJar))) {
            Enumeration<JarEntry> entries = source.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                // Skip plugin classes and plugin manifest to avoid PluginManager conflicts
                if (name.startsWith("ws/sanasol/dualauth/plugin/") || name.equals("manifest.json")) {
                    continue;
                }
                target.putNextEntry(new JarEntry(name));
                if (!entry.isDirectory()) {
                    try (InputStream is = source.getInputStream(entry)) {
                        is.transferTo(target);
                    }
                }
                target.closeEntry();
            }
        }
        return tempJar;
    }
}
