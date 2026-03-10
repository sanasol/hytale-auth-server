package com.hypixel.hytale.plugin.early;

/**
 * COMPILE-TIME STUB ONLY — excluded from final JAR.
 * Matches the Hytale early plugin ClassTransformer interface.
 */
public interface ClassTransformer {
    default int priority() {
        return 0;
    }

    byte[] transform(String className, String internalName, byte[] classBytes);
}
