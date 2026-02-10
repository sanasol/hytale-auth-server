package ws.sanasol.dualauth.plugin;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import net.bytebuddy.agent.ByteBuddyAgent;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.jar.JarFile;

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

            // 3. INJECT INTO SYSTEM CLASSLOADER
            // This is vital. We move the JAR from "PluginClassLoader" to "SystemClassLoader".
            inst.appendToSystemClassLoaderSearch(new JarFile(currentJar));

            // 4. REFLECTION TRAMPOLINE
            // We use the SystemClassLoader to load the Agent class.
            // If we used DualAuthAgent.class directly, we would use the PluginClassLoader, causing LinkageError.
            ClassLoader systemLoader = ClassLoader.getSystemClassLoader();
            
            Class<?> agentClass = systemLoader.loadClass("ws.sanasol.dualauth.agent.DualAuthAgent");
            Method agentMainMethod = agentClass.getMethod("agentmain", String.class, Instrumentation.class);
            
            // 5. Execute the agent in the System context
            agentMainMethod.invoke(null, "plugin-mode", inst);
            
            // 6. Mark success
            System.setProperty("dualauth.agent.active", "true");

        } catch (Throwable e) {
            System.err.println("[DualAuth] CRITICAL: Failed to attach agent dynamically!");
            System.err.println("[DualAuth] Please try adding the flag: -XX:+EnableDynamicAgentLoading");
            e.printStackTrace();
        }
    }
}
