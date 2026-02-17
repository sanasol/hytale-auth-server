package com.hypixel.hytale.server.core.plugin;

/**
 * Stub class for Hytale JavaPlugin API.
 * Used only for compilation. The real class is provided by Hytale server at runtime.
 */
public abstract class JavaPlugin {
    public JavaPlugin(JavaPluginInit init) {
    }

    protected void onEnable() {}
    protected void onDisable() {}
    
    // Called by Hytale server
    protected void setup() {}
    protected void start() {}
    protected void shutdown() {}
}
