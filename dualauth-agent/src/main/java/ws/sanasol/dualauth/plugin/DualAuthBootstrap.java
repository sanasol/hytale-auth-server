package ws.sanasol.dualauth.plugin;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import net.bytebuddy.agent.ByteBuddyAgent;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
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
 */
public class DualAuthBootstrap extends JavaPlugin {

    public DualAuthBootstrap(JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        // Check if the agent is already running (loaded at startup with -javaagent)
        // This is not being triggered because HytaleServer detects the duplicated plugins before this even can prevent it
        if (System.getProperty("dualauth.agent.active") != null) {
            System.out.println("[DualAuth] Plugin Wrapper: Agent is ALREADY ACTIVE via -javaagent flag.");
            System.out.println("[DualAuth] Plugin Wrapper: Entering PASSIVE mode (no action taken).");
            return; // SUCCESSFUL, but does nothing.
        }

        System.out.println("[DualAuth] Plugin Wrapper: Agent not detected. Initializing Dynamic Attach...");
        injectAgent();
    }

    @Override
    protected void start() {
        // Nothing to do here. Logic resides entirely in the Agent (System ClassLoader).
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
            // Hytale's TransformingClassLoader has platform CL as parent (NOT system CL).
            // Agent classes on system CL are invisible to transformed server classes.
            // Bootstrap CL is the root of all classloaders, so classes there are visible everywhere.
            // This mirrors the premain() strategy for consistent classloader behavior.
            boolean addedToBootstrap = false;
            try {
                File bootstrapJar = createBootstrapJar(currentJar);
                inst.appendToBootstrapClassLoaderSearch(new JarFile(bootstrapJar));
                addedToBootstrap = true;
            } catch (Exception e) {
                System.err.println("[DualAuth] Could not add agent to bootstrap classpath: " + e.getMessage());
            }

            if (addedToBootstrap) {
                // 4. REFLECTION TRAMPOLINE via BOOTSTRAP classloader (null = bootstrap CL)
                // Load DualAuthAgent from bootstrap so ALL agent classes (DualAuthHelper,
                // ByteBuddy, DualAuthContext, etc.) load from a single classloader.
                // This ensures advice-inlined references resolve from TransformingClassLoader.
                Class<?> agentClass = Class.forName(
                    "ws.sanasol.dualauth.agent.DualAuthAgent", true, null);
                Method agentMainMethod = agentClass.getMethod("agentmain", String.class, Instrumentation.class);
                agentMainMethod.invoke(null, "plugin-mode", inst);
            } else {
                // Fallback: system classloader (may not work with all server classloaders)
                System.err.println("[DualAuth] WARNING: Falling back to system classloader. " +
                    "Transformed classes may not find agent helpers.");
                inst.appendToSystemClassLoaderSearch(new JarFile(currentJar));
                ClassLoader systemLoader = ClassLoader.getSystemClassLoader();
                Class<?> agentClass = systemLoader.loadClass("ws.sanasol.dualauth.agent.DualAuthAgent");
                Method agentMainMethod = agentClass.getMethod("agentmain", String.class, Instrumentation.class);
                agentMainMethod.invoke(null, "plugin-mode", inst);
            }

            // 5. Mark success
            System.setProperty("dualauth.agent.active", "true");

        } catch (Throwable e) {
            System.err.println("[DualAuth] CRITICAL: Failed to attach agent dynamically!");
            System.err.println("[DualAuth] Please try adding the flag: -XX:+EnableDynamicAgentLoading");
            e.printStackTrace();
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
