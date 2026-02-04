import org.objectweb.asm.*;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

/**
 * Hytale Server Dual Authentication Patcher v10.3
 *
 * TRUE DUAL AUTH + DECENTRALIZED ISSUERS
 *
 * Supports:
 * 1. Official hytale.com (Standard Flow)
 * 2. F2P sanasol.ws (Standard Flow)
 * 3. Self-Hosted/Decentralized Clients (Embedded JWK Header Flow - RFC 7515)
 */
public class DualAuthPatcher {

        private static final String F2P_DOMAIN = System.getenv("HYTALE_AUTH_DOMAIN") != null
                        ? System.getenv("HYTALE_AUTH_DOMAIN")
                        : "auth.sanasol.ws";

        private static final String OFFICIAL_SESSION_URL = "https://sessions.hytale.com";
        private static final String OFFICIAL_ISSUER = "https://sessions.hytale.com";

        // If we detect a local domain (127.x.x.x or localhost), we switch to HTTP
        // This allows Java (SSL) to talk to your Omni-Auth emulator in local worlds
        // without SSL clashing.
        private static final String PROTOCOL = (F2P_DOMAIN.startsWith("127.") || F2P_DOMAIN.startsWith("localhost"))
                        ? "http"
                        : "https";

        private static final String F2P_SESSION_URL = PROTOCOL + "://" + F2P_DOMAIN;
        private static final String F2P_ISSUER = F2P_SESSION_URL;

        // Base domain for backward compatibility (accepts sessions.sanasol.ws,
        // auth.sanasol.ws, etc.)
        // Extracts "sanasol.ws" from "auth.sanasol.ws"
        private static final String F2P_BASE_DOMAIN = extractBaseDomain(F2P_DOMAIN);

        private static final boolean TRUST_ALL_ISSUERS = getEnvBoolean("HYTALE_TRUST_ALL_ISSUERS", true);
        private static final boolean TRUST_OFFICIAL = getEnvBoolean("HYTALE_TRUST_OFFICIAL", true);
        private static final Set<String> TRUSTED_ISSUERS = parseTrustedIssuers(System.getenv("HYTALE_TRUSTED_ISSUERS"));
        private static final long JWKS_CACHE_TTL_MS = getEnvLongSeconds("HYTALE_JWKS_CACHE_TTL", 3600L) * 1000L;

        private static String extractBaseDomain(String domain) {
                // If it's an IP address (starts with digit), return as is to avoid subdomain
                // parsing errors
                if (domain != null && !domain.isEmpty() && Character.isDigit(domain.charAt(0))) {
                        return domain;
                }

                // auth.sanasol.ws -> sanasol.ws
                // sanasol.ws -> sanasol.ws
                int firstDot = domain.indexOf('.');
                if (firstDot > 0) {
                        String afterFirstDot = domain.substring(firstDot + 1);
                        // Check if there's another dot (meaning we have a subdomain)
                        if (afterFirstDot.indexOf('.') > 0) {
                                return afterFirstDot;
                        }
                }
                return domain;
        }

        private static boolean getEnvBoolean(String name, boolean defaultValue) {
                String raw = System.getenv(name);
                if (raw == null) {
                        return defaultValue;
                }
                String v = raw.trim();
                if (v.isEmpty()) {
                        return defaultValue;
                }
                return v.equalsIgnoreCase("true")
                                || v.equalsIgnoreCase("1")
                                || v.equalsIgnoreCase("yes")
                                || v.equalsIgnoreCase("y")
                                || v.equalsIgnoreCase("on");
        }

        private static long getEnvLongSeconds(String name, long defaultValueSeconds) {
                String raw = System.getenv(name);
                if (raw == null) {
                        return defaultValueSeconds;
                }
                String v = raw.trim();
                if (v.isEmpty()) {
                        return defaultValueSeconds;
                }
                try {
                        long parsed = Long.parseLong(v);
                        return parsed > 0 ? parsed : defaultValueSeconds;
                } catch (Exception ignored) {
                        return defaultValueSeconds;
                }
        }

        private static Set<String> parseTrustedIssuers(String raw) {
                Set<String> out = new HashSet<>();
                if (raw == null) {
                        return out;
                }
                String v = raw.trim();
                if (v.isEmpty()) {
                        return out;
                }
                String[] parts = v.split(",");
                for (String p : parts) {
                        if (p == null) {
                                continue;
                        }
                        String s = p.trim();
                        if (s.isEmpty()) {
                                continue;
                        }
                        if (s.endsWith("/")) {
                                s = s.substring(0, s.length() - 1);
                        }
                        out.add(s);
                }
                return out;
        }

        // Package for injected classes
        private static final String AUTH_PKG = "com/hypixel/hytale/server/core/auth/";
        private static final String JWKS_FETCHER_CLASS = AUTH_PKG + "DualJwksFetcher";
        private static final String CONTEXT_CLASS = AUTH_PKG + "DualAuthContext";
        private static final String HELPER_CLASS = AUTH_PKG + "DualAuthHelper";
        private static final String SERVER_IDENTITY_CLASS = AUTH_PKG + "DualServerIdentity";
        private static final String TOKEN_MANAGER_CLASS = AUTH_PKG + "DualServerTokenManager";
        // Class to verify embedded JWKs
        private static final String EMBEDDED_VERIFIER_CLASS = AUTH_PKG + "EmbeddedJwkVerifier";

        // Target classes to patch
        private static final String JWT_VALIDATOR_CLASS = "com/hypixel/hytale/server/core/auth/JWTValidator";
        private static final String SESSION_SERVICE_CLIENT_CLASS = "com/hypixel/hytale/server/core/auth/SessionServiceClient";
        private static final String AUTH_GRANT_CLASS = "com/hypixel/hytale/protocol/packets/auth/AuthGrant";
        private static final String SERVER_AUTH_MANAGER_CLASS = "com/hypixel/hytale/server/core/auth/ServerAuthManager";

        private static int patchCount = 0;
        private static boolean verbose = true;
        private static Set<String> patchedMethods = new HashSet<>();

        /**
         * Custom ClassWriter that handles unknown classes gracefully.
         * When COMPUTE_FRAMES is used, ASM needs to find common superclasses,
         * but we don't have access to the JAR's classes at patching time.
         * This implementation returns "java/lang/Object" for any unknown class.
         */
        private static class SafeClassWriter extends ClassWriter {
                public SafeClassWriter(int flags) {
                        super(flags);
                }

                public SafeClassWriter(ClassReader classReader, int flags) {
                        super(classReader, flags);
                }

                @Override
                protected String getCommonSuperClass(String type1, String type2) {
                        // For well-known Java classes, use the standard implementation
                        try {
                                return super.getCommonSuperClass(type1, type2);
                        } catch (RuntimeException e) {
                                // If either type is not found (TypeNotPresentException extends
                                // RuntimeException),
                                // return Object as the common superclass.
                                // This is safe because Object is the root of all class hierarchies.
                                return "java/lang/Object";
                        }
                }
        }

        public static void main(String[] args) throws Exception {
                if (args.length < 1) {
                        System.out.println("Usage: java DualAuthPatcher <HytaleServer.jar> [output.jar]");
                        System.exit(1);
                }

                String inputJar = args[0];
                String outputJar = args.length > 1 ? args[1] : inputJar;

                System.out.println("+---------------------------------------------------------------+");
                System.out.println("|     Hytale Server TRUE Dual Authentication Patcher v9.2       |");
                System.out.println("+---------------------------------------------------------------+");
                System.out.println();
                System.out.println("Input:  " + inputJar);
                System.out.println("Output: " + outputJar);
                System.out.println();
                System.out.println("Configuration:");
                System.out.println("  Official: " + OFFICIAL_SESSION_URL + " (issuer: " + OFFICIAL_ISSUER + ")");
                System.out.println("  F2P:      " + F2P_SESSION_URL + " (issuer: " + F2P_ISSUER + ")");
                System.out.println("  F2P Base: *." + F2P_BASE_DOMAIN + " (backward compatible issuers)");
                System.out.println("  Trust Official: " + TRUST_OFFICIAL);
                System.out.println("  Trust All Issuers: " + TRUST_ALL_ISSUERS);
                System.out.println("  Trusted Issuers: " + (TRUSTED_ISSUERS.isEmpty() ? "<none>" : TRUSTED_ISSUERS));
                System.out.println("  JWKS Cache TTL (ms): " + JWKS_CACHE_TTL_MS);
                System.out.println();

                patchJar(inputJar, outputJar);
        }

        private static void patchJar(String inputJar, String outputJar) throws Exception {
                Path tempOutput = Files.createTempFile("hytale-patched-", ".jar");

                Map<String, byte[]> patchedClasses = new LinkedHashMap<>();
                List<String> foundClasses = new ArrayList<>();

                System.out.println("--- Phase 1: Scanning JAR for auth classes ---");
                System.out.println();

                try (ZipFile zipIn = new ZipFile(inputJar)) {
                        Enumeration<? extends ZipEntry> entries = zipIn.entries();
                        while (entries.hasMoreElements()) {
                                ZipEntry entry = entries.nextElement();
                                String name = entry.getName();
                                if (!name.endsWith(".class"))
                                        continue;

                                byte[] classBytes = readAllBytes(zipIn.getInputStream(entry));

                                // Check for target classes
                                String className = name.replace(".class", "");

                                // Patch JWTValidator
                                if (className.equals(JWT_VALIDATOR_CLASS)) {
                                        System.out.println(
                                                        "Found JWTValidator - will patch JWKS fetching and issuer validation");
                                        foundClasses.add(name);
                                        byte[] patched = patchJWTValidator(classBytes);
                                        if (patched != null) {
                                                patchedClasses.put(name, patched);
                                        }
                                }
                                // Patch SessionServiceClient
                                else if (className.equals(SESSION_SERVICE_CLIENT_CLASS)) {
                                        System.out.println(
                                                        "Found SessionServiceClient - will patch authorization routing");
                                        foundClasses.add(name);
                                        byte[] patched = patchSessionServiceClient(classBytes);
                                        if (patched != null) {
                                                patchedClasses.put(name, patched);
                                        }
                                }
                                // Look for HandshakeHandler
                                else if (className.contains("HandshakeHandler")) {
                                        System.out.println(
                                                        "Found HandshakeHandler - will patch to use dual token manager");
                                        foundClasses.add(name);
                                        byte[] patched = patchHandshakeHandler(classBytes);
                                        if (patched != null) {
                                                patchedClasses.put(name, patched);
                                        }
                                }
                                // Patch AuthGrant packet to suppress serverIdentityToken for F2P clients
                                else if (className.equals(AUTH_GRANT_CLASS)) {
                                        System.out.println(
                                                        "Found AuthGrant - will patch to suppress server identity for F2P");
                                        foundClasses.add(name);
                                        byte[] patched = patchAuthGrant(classBytes);
                                        if (patched != null) {
                                                patchedClasses.put(name, patched);
                                        }
                                }
                                // Patch ServerAuthManager to auto-fetch F2P tokens and route via
                                // DualServerTokenManager
                                else if (className.equals(SERVER_AUTH_MANAGER_CLASS)) {
                                        System.out.println(
                                                        "Found ServerAuthManager - will patch for dual token management");
                                        foundClasses.add(name);
                                        byte[] patched = patchServerAuthManager(classBytes);
                                        if (patched != null) {
                                                patchedClasses.put(name, patched);
                                        }
                                }
                                // Also check other classes for issuer URL patterns
                                // IMPORTANT: Skip OAuth-related classes - /auth login MUST use official
                                // hytale.com
                                else if ((containsString(classBytes, OFFICIAL_SESSION_URL) ||
                                                containsString(classBytes, "sessions.hytale.com")) &&
                                                !isOAuthClass(className)) {
                                        foundClasses.add(name);
                                        byte[] patched = patchGenericClass(name, classBytes);
                                        if (patched != null) {
                                                patchedClasses.put(name, patched);
                                        }
                                }
                        }
                }

                System.out.println();
                System.out.println("--- Phase 2: Generating Dual Auth Classes ---");
                System.out.println();

                // Generate helper classes
                byte[] contextBytes = generateDualAuthContext();
                System.out.println("Generated: " + CONTEXT_CLASS + ".class (thread-local issuer tracking)");

                byte[] helperBytes = generateDualAuthHelper();
                System.out.println("Generated: " + HELPER_CLASS + ".class (issuer validation helpers)");

                byte[] fetcherBytes = generateDualJwksFetcher();
                System.out.println("Generated: " + JWKS_FETCHER_CLASS + ".class (dual JWKS fetching)");

                byte[] serverIdentityBytes = generateDualServerIdentity();
                System.out.println("Generated: " + SERVER_IDENTITY_CLASS + ".class (F2P server identity)");

                byte[] tokenManagerBytes = generateDualServerTokenManager();
                System.out.println("Generated: " + TOKEN_MANAGER_CLASS + ".class (dual token storage)");

                byte[] embeddedVerifierBytes = generateEmbeddedJwkVerifier();
                System.out.println("Generated: " + EMBEDDED_VERIFIER_CLASS + ".class (embedded JWK verifier)");

                System.out.println();
                System.out.println("--- Phase 3: Writing Patched JAR ---");
                System.out.println();

                try (ZipFile zipIn = new ZipFile(inputJar);
                                ZipOutputStream zipOut = new ZipOutputStream(
                                                new FileOutputStream(tempOutput.toFile()))) {

                        // Add generated classes first
                        addClassToJar(zipOut, CONTEXT_CLASS + ".class", contextBytes);
                        addClassToJar(zipOut, HELPER_CLASS + ".class", helperBytes);
                        addClassToJar(zipOut, JWKS_FETCHER_CLASS + ".class", fetcherBytes);
                        addClassToJar(zipOut, SERVER_IDENTITY_CLASS + ".class", serverIdentityBytes);
                        addClassToJar(zipOut, TOKEN_MANAGER_CLASS + ".class", tokenManagerBytes);
                        addClassToJar(zipOut, EMBEDDED_VERIFIER_CLASS + ".class", embeddedVerifierBytes);

                        // Set of generated class paths to skip when copying (in case JAR was already
                        // patched)
                        Set<String> generatedClasses = new HashSet<>();
                        generatedClasses.add(CONTEXT_CLASS + ".class");
                        generatedClasses.add(HELPER_CLASS + ".class");
                        generatedClasses.add(JWKS_FETCHER_CLASS + ".class");
                        generatedClasses.add(SERVER_IDENTITY_CLASS + ".class");
                        generatedClasses.add(TOKEN_MANAGER_CLASS + ".class");
                        generatedClasses.add(EMBEDDED_VERIFIER_CLASS + ".class");

                        // Copy all entries, replacing patched ones and skipping generated ones
                        Enumeration<? extends ZipEntry> entries = zipIn.entries();
                        while (entries.hasMoreElements()) {
                                ZipEntry entry = entries.nextElement();
                                String name = entry.getName();

                                // Skip if this is a generated class (we already added fresh versions)
                                if (generatedClasses.contains(name)) {
                                        System.out.println("[SKIP] Already added fresh version: " + name);
                                        continue;
                                }

                                ZipEntry newEntry = new ZipEntry(name);
                                zipOut.putNextEntry(newEntry);

                                if (patchedClasses.containsKey(name)) {
                                        zipOut.write(patchedClasses.get(name));
                                        System.out.println("[OK] Patched: " + name);
                                } else {
                                        copy(zipIn.getInputStream(entry), zipOut);
                                }

                                zipOut.closeEntry();
                        }
                }

                Files.move(tempOutput, Path.of(outputJar), StandardCopyOption.REPLACE_EXISTING);

                System.out.println();
                System.out.println("+---------------------------------------------------------------+");
                System.out.println("|     Patching Complete - TRUE DUAL AUTH + DECENTRALIZED        |");
                System.out.println("+---------------------------------------------------------------+");
                System.out.println();
                System.out.println("Statistics:");
                System.out.println("  Classes found with auth URLs: " + foundClasses.size());
                System.out.println("  Classes patched: " + patchedClasses.size());
                System.out.println("  Total patches applied: " + patchCount);
                System.out.println("  Methods modified: " + patchedMethods.size());
                System.out.println();
                System.out.println("The server now supports ALL authentication sources:");
                System.out.println("  [OK] JWKS loaded from BOTH " + OFFICIAL_SESSION_URL + " AND " + F2P_SESSION_URL);
                System.out.println("  [OK] Tokens accepted from both hytale.com and *." + F2P_BASE_DOMAIN + " issuers");
                System.out.println("       (backward compatible: sessions." + F2P_BASE_DOMAIN + ", " + F2P_DOMAIN
                                + ", etc.)");
                System.out.println("  [OK] Authorization requests routed based on token issuer");
                System.out.println("  [NEW] Self-hosted clients with embedded JWK headers (RFC 7515)");
                System.out.println("        - Supports Ed25519/EdDSA ephemeral keys");
                System.out.println("        - Local verification bypasses external key repositories");
        }

        /**
         * Generate DualAuthContext class - Thread-local + Global cache for Omni-Auth
         */
        private static byte[] generateDualAuthContext() {
                ClassWriter cw = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

                cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                                CONTEXT_CLASS, null, "java/lang/Object", null);

                // Fields
                cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                                "currentIssuer", "Ljava/lang/ThreadLocal;", null, null).visitEnd();

                cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                                "currentJwk", "Ljava/lang/ThreadLocal;", null, null).visitEnd();

                // NUEVO: Cache global para Omni-Auth
                cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                                "globalJwkCache", "Ljava/util/concurrent/ConcurrentHashMap;", null, null).visitEnd();

                cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                                "currentPlayerUuid", "Ljava/lang/ThreadLocal;", null, null).visitEnd();

                cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                                "globalPlayerJwkCache", "Ljava/util/concurrent/ConcurrentHashMap;", null, null)
                                .visitEnd();

                // Username propagation for Omni-Auth
                cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                                "currentUsername", "Ljava/lang/ThreadLocal;", null, null).visitEnd();

                // Omni-Auth state flag to distinguish between centralized and decentralized
                // tokens
                cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                                "isCurrentTokenOmni", "Ljava/lang/ThreadLocal;", null, null).visitEnd();

                // Static initializer
                MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
                mv.visitCode();
                mv.visitTypeInsn(Opcodes.NEW, "java/lang/ThreadLocal");
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/ThreadLocal", "<init>", "()V", false);
                mv.visitFieldInsn(Opcodes.PUTSTATIC, CONTEXT_CLASS, "currentIssuer", "Ljava/lang/ThreadLocal;");

                mv.visitTypeInsn(Opcodes.NEW, "java/lang/ThreadLocal");
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/ThreadLocal", "<init>", "()V", false);
                mv.visitFieldInsn(Opcodes.PUTSTATIC, CONTEXT_CLASS, "currentJwk", "Ljava/lang/ThreadLocal;");

                mv.visitTypeInsn(Opcodes.NEW, "java/util/concurrent/ConcurrentHashMap");
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/concurrent/ConcurrentHashMap", "<init>", "()V",
                                false);
                mv.visitFieldInsn(Opcodes.PUTSTATIC, CONTEXT_CLASS, "globalJwkCache",
                                "Ljava/util/concurrent/ConcurrentHashMap;");

                mv.visitTypeInsn(Opcodes.NEW, "java/lang/ThreadLocal");
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/ThreadLocal", "<init>", "()V", false);
                mv.visitFieldInsn(Opcodes.PUTSTATIC, CONTEXT_CLASS, "currentPlayerUuid", "Ljava/lang/ThreadLocal;");

                mv.visitTypeInsn(Opcodes.NEW, "java/util/concurrent/ConcurrentHashMap");
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/concurrent/ConcurrentHashMap", "<init>", "()V",
                                false);
                mv.visitFieldInsn(Opcodes.PUTSTATIC, CONTEXT_CLASS, "globalPlayerJwkCache",
                                "Ljava/util/concurrent/ConcurrentHashMap;");

                mv.visitTypeInsn(Opcodes.NEW, "java/lang/ThreadLocal");
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/ThreadLocal", "<init>", "()V", false);
                mv.visitFieldInsn(Opcodes.PUTSTATIC, CONTEXT_CLASS, "currentUsername", "Ljava/lang/ThreadLocal;");

                mv.visitTypeInsn(Opcodes.NEW, "java/lang/ThreadLocal");
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/ThreadLocal", "<init>", "()V", false);
                mv.visitFieldInsn(Opcodes.PUTSTATIC, CONTEXT_CLASS, "isCurrentTokenOmni", "Ljava/lang/ThreadLocal;");
                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(2, 0);
                mv.visitEnd();

                // Private constructor
                mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null);
                mv.visitCode();
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(1, 1);
                mv.visitEnd();

                // public static void setIssuer(String issuer)
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                                "setIssuer", "(Ljava/lang/String;)V", null, null);
                mv.visitCode();
                mv.visitFieldInsn(Opcodes.GETSTATIC, CONTEXT_CLASS, "currentIssuer", "Ljava/lang/ThreadLocal;");
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/ThreadLocal", "set", "(Ljava/lang/Object;)V",
                                false);
                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(2, 1);
                mv.visitEnd();

                // public static String getIssuer()
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                                "getIssuer", "()Ljava/lang/String;", null, null);
                mv.visitCode();
                mv.visitFieldInsn(Opcodes.GETSTATIC, CONTEXT_CLASS, "currentIssuer", "Ljava/lang/ThreadLocal;");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/ThreadLocal", "get", "()Ljava/lang/Object;",
                                false);
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String");
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitMaxs(1, 0);
                mv.visitEnd();

                // public static void setPlayerUuid(String uuid)
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                                "setPlayerUuid", "(Ljava/lang/String;)V", null, null);
                mv.visitCode();
                mv.visitFieldInsn(Opcodes.GETSTATIC, CONTEXT_CLASS, "currentPlayerUuid", "Ljava/lang/ThreadLocal;");
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/ThreadLocal", "set", "(Ljava/lang/Object;)V",
                                false);
                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(2, 1);
                mv.visitEnd();

                // public static String getPlayerUuid()
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                                "getPlayerUuid", "()Ljava/lang/String;", null, null);
                mv.visitCode();
                mv.visitFieldInsn(Opcodes.GETSTATIC, CONTEXT_CLASS, "currentPlayerUuid", "Ljava/lang/ThreadLocal;");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/ThreadLocal", "get", "()Ljava/lang/Object;",
                                false);
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String");
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitMaxs(1, 0);
                mv.visitEnd();

                // setJwk(String jwk) -> Also saves in the global cache using the current issuer
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                                "setJwk", "(Ljava/lang/String;)V", null, null);
                mv.visitCode();
                mv.visitFieldInsn(Opcodes.GETSTATIC, CONTEXT_CLASS, "currentJwk", "Ljava/lang/ThreadLocal;");
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/ThreadLocal", "set", "(Ljava/lang/Object;)V",
                                false);

                // If jwk == null, don't touch global caches (ConcurrentHashMap doesn't accept
                // null)
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                Label jwkNotNull = new Label();
                mv.visitJumpInsn(Opcodes.IFNONNULL, jwkNotNull);
                mv.visitInsn(Opcodes.RETURN);
                mv.visitLabel(jwkNotNull);

                // Save in global cache if there is an issuer
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, CONTEXT_CLASS, "getIssuer", "()Ljava/lang/String;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 1);
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                Label skipGlobal = new Label();
                mv.visitJumpInsn(Opcodes.IFNULL, skipGlobal);
                mv.visitFieldInsn(Opcodes.GETSTATIC, CONTEXT_CLASS, "globalJwkCache",
                                "Ljava/util/concurrent/ConcurrentHashMap;");
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/concurrent/ConcurrentHashMap", "put",
                                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
                mv.visitInsn(Opcodes.POP);
                mv.visitLabel(skipGlobal);

                // Save in global cache by UUID if exists
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, CONTEXT_CLASS, "getPlayerUuid", "()Ljava/lang/String;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 2);
                mv.visitVarInsn(Opcodes.ALOAD, 2);
                Label skipGlobalPlayer = new Label();
                mv.visitJumpInsn(Opcodes.IFNULL, skipGlobalPlayer);
                mv.visitFieldInsn(Opcodes.GETSTATIC, CONTEXT_CLASS, "globalPlayerJwkCache",
                                "Ljava/util/concurrent/ConcurrentHashMap;");
                mv.visitVarInsn(Opcodes.ALOAD, 2);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/concurrent/ConcurrentHashMap", "put",
                                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
                mv.visitInsn(Opcodes.POP);
                mv.visitLabel(skipGlobalPlayer);
                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(3, 3);
                mv.visitEnd();

                // getJwk() -> Tries ThreadLocal, if not, looks in Global Cache using the
                // current issuer
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                                "getJwk", "()Ljava/lang/String;", null, null);
                mv.visitCode();
                mv.visitFieldInsn(Opcodes.GETSTATIC, CONTEXT_CLASS, "currentJwk", "Ljava/lang/ThreadLocal;");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/ThreadLocal", "get", "()Ljava/lang/Object;",
                                false);
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String");
                mv.visitVarInsn(Opcodes.ASTORE, 0);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                Label returnLabel = new Label();
                mv.visitJumpInsn(Opcodes.IFNONNULL, returnLabel);

                // Fallback to global cache by UUID
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, CONTEXT_CLASS, "getPlayerUuid", "()Ljava/lang/String;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 1);
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                Label checkIssuerCache = new Label();
                mv.visitJumpInsn(Opcodes.IFNULL, checkIssuerCache);
                mv.visitFieldInsn(Opcodes.GETSTATIC, CONTEXT_CLASS, "globalPlayerJwkCache",
                                "Ljava/util/concurrent/ConcurrentHashMap;");
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/concurrent/ConcurrentHashMap", "get",
                                "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String");
                mv.visitInsn(Opcodes.ARETURN);

                // Fallback to global cache by issuer
                mv.visitLabel(checkIssuerCache);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, CONTEXT_CLASS, "getIssuer", "()Ljava/lang/String;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 1);
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitJumpInsn(Opcodes.IFNULL, returnLabel);
                mv.visitFieldInsn(Opcodes.GETSTATIC, CONTEXT_CLASS, "globalJwkCache",
                                "Ljava/util/concurrent/ConcurrentHashMap;");
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/concurrent/ConcurrentHashMap", "get",
                                "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String");
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitLabel(returnLabel);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitMaxs(2, 2);
                mv.visitEnd();

                // public static void clear()
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                                "clear", "()V", null, null);
                mv.visitCode();
                mv.visitFieldInsn(Opcodes.GETSTATIC, CONTEXT_CLASS, "currentIssuer", "Ljava/lang/ThreadLocal;");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/ThreadLocal", "remove", "()V", false);
                mv.visitFieldInsn(Opcodes.GETSTATIC, CONTEXT_CLASS, "currentJwk", "Ljava/lang/ThreadLocal;");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/ThreadLocal", "remove", "()V", false);
                mv.visitFieldInsn(Opcodes.GETSTATIC, CONTEXT_CLASS, "currentPlayerUuid", "Ljava/lang/ThreadLocal;");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/ThreadLocal", "remove", "()V", false);
                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(1, 0);
                mv.visitEnd();

                // public static void setUsername(String username)
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                                "setUsername", "(Ljava/lang/String;)V", null, null);
                mv.visitCode();
                mv.visitFieldInsn(Opcodes.GETSTATIC, CONTEXT_CLASS, "currentUsername", "Ljava/lang/ThreadLocal;");
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/ThreadLocal", "set", "(Ljava/lang/Object;)V",
                                false);
                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(2, 1);
                mv.visitEnd();

                // public static String getUsername()
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                                "getUsername", "()Ljava/lang/String;", null, null);
                mv.visitCode();
                mv.visitFieldInsn(Opcodes.GETSTATIC, CONTEXT_CLASS, "currentUsername", "Ljava/lang/ThreadLocal;");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/ThreadLocal", "get", "()Ljava/lang/Object;",
                                false);
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String");
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitMaxs(1, 0);
                mv.visitEnd();

                // public static void setOmni(boolean val)
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                                "setOmni", "(Z)V", null, null);
                mv.visitCode();
                mv.visitFieldInsn(Opcodes.GETSTATIC, CONTEXT_CLASS, "isCurrentTokenOmni", "Ljava/lang/ThreadLocal;");
                mv.visitVarInsn(Opcodes.ILOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;",
                                false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/ThreadLocal", "set", "(Ljava/lang/Object;)V",
                                false);
                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(2, 1);
                mv.visitEnd();

                // public static boolean isOmni()
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                                "isOmni", "()Z", null, null);
                mv.visitCode();
                mv.visitFieldInsn(Opcodes.GETSTATIC, CONTEXT_CLASS, "isCurrentTokenOmni", "Ljava/lang/ThreadLocal;");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/ThreadLocal", "get", "()Ljava/lang/Object;",
                                false);
                mv.visitInsn(Opcodes.DUP); // Crucial: duplicate to preserve the value after the jump
                Label omniNotNull = new Label();
                mv.visitJumpInsn(Opcodes.IFNONNULL, omniNotNull);
                mv.visitInsn(Opcodes.POP); // Clean up the null if not jumped
                mv.visitInsn(Opcodes.ICONST_0);
                mv.visitInsn(Opcodes.IRETURN);
                mv.visitLabel(omniNotNull);
                // Here the stack has the Boolean object thanks to DUP
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Boolean");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false);
                mv.visitInsn(Opcodes.IRETURN);
                mv.visitMaxs(2, 0);
                mv.visitEnd();

                // public static boolean isF2P()
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                                "isF2P", "()Z", null, null);
                mv.visitCode();
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, CONTEXT_CLASS, "getIssuer", "()Ljava/lang/String;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 0);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                Label notNull = new Label();
                mv.visitJumpInsn(Opcodes.IFNONNULL, notNull);
                mv.visitInsn(Opcodes.ICONST_0);
                mv.visitInsn(Opcodes.IRETURN);
                mv.visitLabel(notNull);
                // True only for Sanasol issuers
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitFieldInsn(Opcodes.GETSTATIC, HELPER_CLASS, "F2P_BASE_DOMAIN", "Ljava/lang/String;");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "contains",
                                "(Ljava/lang/CharSequence;)Z", false);
                Label returnFalse = new Label();
                mv.visitJumpInsn(Opcodes.IFEQ, returnFalse);
                mv.visitInsn(Opcodes.ICONST_1);
                mv.visitInsn(Opcodes.IRETURN);
                mv.visitLabel(returnFalse);
                mv.visitInsn(Opcodes.ICONST_0);
                mv.visitInsn(Opcodes.IRETURN);
                mv.visitMaxs(2, 1);
                mv.visitEnd();

                cw.visitEnd();
                return cw.toByteArray();
        }

        /**
         * Generate DualAuthHelper class - Issuer validation and URL routing
         */
        private static byte[] generateDualAuthHelper() {
                ClassWriter cw = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

                MethodVisitor mv;

                cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                                HELPER_CLASS, null, "java/lang/Object", null);

                // Dynamic Static Fields
                cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "OFFICIAL_URL", "Ljava/lang/String;", null, null)
                                .visitEnd();
                cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "F2P_URL", "Ljava/lang/String;", null, null)
                                .visitEnd();
                cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "OFFICIAL_ISSUER", "Ljava/lang/String;", null,
                                null).visitEnd();
                cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "F2P_ISSUER", "Ljava/lang/String;", null, null)
                                .visitEnd();
                cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "F2P_DOMAIN", "Ljava/lang/String;", null, null)
                                .visitEnd();
                cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "F2P_BASE_DOMAIN", "Ljava/lang/String;", null,
                                null).visitEnd();
                cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "OFFICIAL_DOMAIN", "Ljava/lang/String;", null,
                                null).visitEnd();

                // Static Initializer (<clinit>)
                mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
                mv.visitCode();

                // 1. Get HYTALE_AUTH_DOMAIN
                mv.visitLdcInsn("HYTALE_AUTH_DOMAIN");
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getenv", "(Ljava/lang/String;)Ljava/lang/String;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 0);

                mv.visitVarInsn(Opcodes.ALOAD, 0);
                Label hasEnv = new Label();
                mv.visitJumpInsn(Opcodes.IFNONNULL, hasEnv);
                mv.visitLdcInsn("auth.sanasol.ws");
                mv.visitVarInsn(Opcodes.ASTORE, 0);
                mv.visitLabel(hasEnv);

                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "trim", "()Ljava/lang/String;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 0);

                // Save F2P_DOMAIN
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitFieldInsn(Opcodes.PUTSTATIC, HELPER_CLASS, "F2P_DOMAIN", "Ljava/lang/String;");

                // 2. Extract F2P_BASE_DOMAIN (Improved IP handling)
                // if (Character.isDigit(domain.charAt(0))) base = domain; else ...
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitInsn(Opcodes.ICONST_0);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Character", "isDigit", "(C)Z", false);
                Label isIp = new Label();
                mv.visitJumpInsn(Opcodes.IFNE, isIp);

                // Domain parsing logic
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitIntInsn(Opcodes.BIPUSH, '.');
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "indexOf", "(I)I", false);
                mv.visitVarInsn(Opcodes.ISTORE, 1); // firstDot

                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitVarInsn(Opcodes.ASTORE, 2); // base

                mv.visitVarInsn(Opcodes.ILOAD, 1);
                Label doneParsing = new Label();
                mv.visitJumpInsn(Opcodes.IFLE, doneParsing);

                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitVarInsn(Opcodes.ILOAD, 1);
                mv.visitInsn(Opcodes.ICONST_1);
                mv.visitInsn(Opcodes.IADD);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring", "(I)Ljava/lang/String;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 3); // candidate

                mv.visitVarInsn(Opcodes.ALOAD, 3);
                mv.visitIntInsn(Opcodes.BIPUSH, '.');
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "indexOf", "(I)I", false);
                mv.visitJumpInsn(Opcodes.IFLE, doneParsing);
                mv.visitVarInsn(Opcodes.ALOAD, 3);
                mv.visitVarInsn(Opcodes.ASTORE, 2);
                mv.visitJumpInsn(Opcodes.GOTO, doneParsing);

                mv.visitLabel(isIp);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitVarInsn(Opcodes.ASTORE, 2);

                mv.visitLabel(doneParsing);
                mv.visitVarInsn(Opcodes.ALOAD, 2);
                mv.visitFieldInsn(Opcodes.PUTSTATIC, HELPER_CLASS, "F2P_BASE_DOMAIN", "Ljava/lang/String;");

                // 3. Protocol
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitLdcInsn("127.");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "startsWith", "(Ljava/lang/String;)Z",
                                false);
                Label isHttp = new Label();
                mv.visitJumpInsn(Opcodes.IFNE, isHttp);

                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitLdcInsn("localhost");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "contains", "(Ljava/lang/CharSequence;)Z", false);
                mv.visitJumpInsn(Opcodes.IFNE, isHttp);

                mv.visitLdcInsn("https");
                mv.visitVarInsn(Opcodes.ASTORE, 3);
                Label protoDone = new Label();
                mv.visitJumpInsn(Opcodes.GOTO, protoDone);

                mv.visitLabel(isHttp);
                mv.visitLdcInsn("http");
                mv.visitVarInsn(Opcodes.ASTORE, 3);

                mv.visitLabel(protoDone);

                // 4. Build F2P_URL
                mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
                mv.visitVarInsn(Opcodes.ALOAD, 3);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitLdcInsn("://");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
                mv.visitInsn(Opcodes.DUP);
                mv.visitFieldInsn(Opcodes.PUTSTATIC, HELPER_CLASS, "F2P_URL", "Ljava/lang/String;");
                mv.visitFieldInsn(Opcodes.PUTSTATIC, HELPER_CLASS, "F2P_ISSUER", "Ljava/lang/String;");

                // 5. Official Constants
                mv.visitLdcInsn("HYTALE_OFFICIAL_URL");
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getenv", "(Ljava/lang/String;)Ljava/lang/String;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 4);
                mv.visitVarInsn(Opcodes.ALOAD, 4);
                Label hasOff = new Label();
                mv.visitJumpInsn(Opcodes.IFNONNULL, hasOff);
                mv.visitLdcInsn("https://sessions.hytale.com");
                mv.visitVarInsn(Opcodes.ASTORE, 4);
                mv.visitLabel(hasOff);
                mv.visitVarInsn(Opcodes.ALOAD, 4);
                mv.visitFieldInsn(Opcodes.PUTSTATIC, HELPER_CLASS, "OFFICIAL_URL", "Ljava/lang/String;");
                mv.visitVarInsn(Opcodes.ALOAD, 4);
                mv.visitFieldInsn(Opcodes.PUTSTATIC, HELPER_CLASS, "OFFICIAL_ISSUER", "Ljava/lang/String;");

                mv.visitLdcInsn("HYTALE_OFFICIAL_DOMAIN");
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getenv", "(Ljava/lang/String;)Ljava/lang/String;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 5);
                mv.visitVarInsn(Opcodes.ALOAD, 5);
                Label hasOffD = new Label();
                mv.visitJumpInsn(Opcodes.IFNONNULL, hasOffD);
                mv.visitLdcInsn("hytale.com");
                mv.visitVarInsn(Opcodes.ASTORE, 5);
                mv.visitLabel(hasOffD);
                mv.visitVarInsn(Opcodes.ALOAD, 5);
                mv.visitFieldInsn(Opcodes.PUTSTATIC, HELPER_CLASS, "OFFICIAL_DOMAIN", "Ljava/lang/String;");

                // Log
                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn("[DualAuth] Config: F2P_URL=");
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
                mv.visitFieldInsn(Opcodes.GETSTATIC, HELPER_CLASS, "F2P_URL", "Ljava/lang/String;");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitLdcInsn(", BASE_DOMAIN=");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitFieldInsn(Opcodes.GETSTATIC, HELPER_CLASS, "F2P_BASE_DOMAIN", "Ljava/lang/String;");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(4, 6);
                mv.visitEnd();

                // Private constructor
                mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null);
                mv.visitCode();
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(1, 1);
                mv.visitEnd();

                // public static boolean isOfficialIssuer(String issuer)
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                                "isOfficialIssuer", "(Ljava/lang/String;)Z", null, null);
                mv.visitCode();
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                Label notNull = new Label();
                mv.visitJumpInsn(Opcodes.IFNONNULL, notNull);
                mv.visitInsn(Opcodes.ICONST_1);
                mv.visitInsn(Opcodes.IRETURN);
                mv.visitLabel(notNull);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitFieldInsn(Opcodes.GETSTATIC, HELPER_CLASS, "OFFICIAL_DOMAIN", "Ljava/lang/String;");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "contains",
                                "(Ljava/lang/CharSequence;)Z", false);
                mv.visitInsn(Opcodes.IRETURN);
                mv.visitMaxs(2, 1);
                mv.visitEnd();

                // public static boolean isValidIssuer(String issuer)
                // Returns true for BOTH official and F2P issuers
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                                "isValidIssuer", "(Ljava/lang/String;)Z", null, null);
                mv.visitCode();

                // if (issuer == null) return false;
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                Label checkNotNull = new Label();
                mv.visitJumpInsn(Opcodes.IFNONNULL, checkNotNull);
                mv.visitInsn(Opcodes.ICONST_0);
                mv.visitInsn(Opcodes.IRETURN);
                mv.visitLabel(checkNotNull);

                // Normalize: if (issuer.endsWith("/")) issuer = issuer.substring(0, len-1)
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitLdcInsn("/");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "endsWith", "(Ljava/lang/String;)Z", false);
                Label skipNormalize = new Label();
                mv.visitJumpInsn(Opcodes.IFEQ, skipNormalize);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitInsn(Opcodes.ICONST_0);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
                mv.visitInsn(Opcodes.ICONST_1);
                mv.visitInsn(Opcodes.ISUB);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring", "(II)Ljava/lang/String;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 0);
                mv.visitLabel(skipNormalize);

                // 1. Check HYTALE_TRUST_ALL_ISSUERS (Truly Agnostic Path)
                mv.visitLdcInsn("HYTALE_TRUST_ALL_ISSUERS");
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getenv", "(Ljava/lang/String;)Ljava/lang/String;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 1);
                
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                Label checkTrustValue = new Label();
                mv.visitJumpInsn(Opcodes.IFNONNULL, checkTrustValue);
                // null (unset) -> true (Agnostic by default)
                mv.visitInsn(Opcodes.ICONST_1);
                mv.visitInsn(Opcodes.IRETURN);

                mv.visitLabel(checkTrustValue);
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "parseBoolean", "(Ljava/lang/String;)Z", false);
                Label trustAllFalse = new Label();
                mv.visitJumpInsn(Opcodes.IFEQ, trustAllFalse);
                mv.visitInsn(Opcodes.ICONST_1);
                mv.visitInsn(Opcodes.IRETURN);

                mv.visitLabel(trustAllFalse);

                // 2. Fallback to Official
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_CLASS, "isOfficialIssuer", "(Ljava/lang/String;)Z", false);
                Label notOfficialLocal = new Label();
                mv.visitJumpInsn(Opcodes.IFEQ, notOfficialLocal);
                mv.visitInsn(Opcodes.ICONST_1);
                mv.visitInsn(Opcodes.IRETURN);
                mv.visitLabel(notOfficialLocal);

                // 3. Fallback to Primary F2P (via F2P_BASE_DOMAIN)
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitFieldInsn(Opcodes.GETSTATIC, HELPER_CLASS, "F2P_BASE_DOMAIN", "Ljava/lang/String;");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "contains", "(Ljava/lang/CharSequence;)Z", false);
                Label notF2PLocal = new Label();
                mv.visitJumpInsn(Opcodes.IFEQ, notF2PLocal);
                mv.visitInsn(Opcodes.ICONST_1);
                mv.visitInsn(Opcodes.IRETURN);
                mv.visitLabel(notF2PLocal);

                // 4. Fallback to Allowlist (TRUSTED_ISSUERS for Omni-Auth)
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_CLASS, "isOmniIssuerTrusted", "(Ljava/lang/String;)Z", false);
                mv.visitInsn(Opcodes.IRETURN);

                mv.visitMaxs(3, 2);
                mv.visitEnd();

                // public static String getSessionUrl()
                // Returns the session URL based on current context's issuer
                // For backward compatibility: returns the actual issuer (e.g.,
                // sessions.sanasol.ws)
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                                "getSessionUrl", "()Ljava/lang/String;", null, null);
                mv.visitCode();
                // String issuer = DualAuthContext.getIssuer();
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, CONTEXT_CLASS, "getIssuer", "()Ljava/lang/String;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 0);
                // if (issuer == null || isOfficialIssuer(issuer)) return OFFICIAL_SESSION_URL
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                Label getSessionUrlNotNull = new Label();
                mv.visitJumpInsn(Opcodes.IFNONNULL, getSessionUrlNotNull);
                mv.visitFieldInsn(Opcodes.GETSTATIC, HELPER_CLASS, "OFFICIAL_URL", "Ljava/lang/String;");
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitLabel(getSessionUrlNotNull);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_CLASS, "isOfficialIssuer",
                                "(Ljava/lang/String;)Z", false);
                Label getSessionUrlF2p = new Label();
                mv.visitJumpInsn(Opcodes.IFEQ, getSessionUrlF2p);
                mv.visitFieldInsn(Opcodes.GETSTATIC, HELPER_CLASS, "OFFICIAL_URL", "Ljava/lang/String;");
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitLabel(getSessionUrlF2p);
                // For F2P: return the issuer itself (it's the session URL)
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitMaxs(1, 1);
                mv.visitEnd();

                // public static String getSessionUrlForIssuer(String issuer)
                // For backward compatibility: return the actual issuer URL (e.g.,
                // sessions.sanasol.ws)
                // instead of hardcoded F2P URL, so requests go back to the original auth server
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                                "getSessionUrlForIssuer", "(Ljava/lang/String;)Ljava/lang/String;", null, null);
                mv.visitCode();
                // if (issuer == null) return OFFICIAL_SESSION_URL
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                Label issuerNotNull = new Label();
                mv.visitJumpInsn(Opcodes.IFNONNULL, issuerNotNull);
                mv.visitFieldInsn(Opcodes.GETSTATIC, HELPER_CLASS, "OFFICIAL_URL", "Ljava/lang/String;");
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitLabel(issuerNotNull);
                // if (isOfficialIssuer(issuer)) return OFFICIAL_SESSION_URL
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_CLASS, "isOfficialIssuer",
                                "(Ljava/lang/String;)Z", false);
                Label returnIssuer = new Label();
                mv.visitJumpInsn(Opcodes.IFEQ, returnIssuer);
                mv.visitFieldInsn(Opcodes.GETSTATIC, HELPER_CLASS, "OFFICIAL_URL", "Ljava/lang/String;");
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitLabel(returnIssuer);
                // For F2P: return the issuer itself (it's the session URL)
                // This preserves backward compatibility: sessions.sanasol.ws ->
                // sessions.sanasol.ws
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitMaxs(1, 1);
                mv.visitEnd();

                // public static String resolveUrl(String originalUrl)
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                                "resolveUrl", "(Ljava/lang/String;)Ljava/lang/String;", null, null);
                mv.visitCode();
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, CONTEXT_CLASS, "isF2P", "()Z", false);
                Label keepOriginal = new Label();
                mv.visitJumpInsn(Opcodes.IFEQ, keepOriginal);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                // F2P uses unified endpoint - replace sessions.hytale.com with just the F2P
                // domain
                mv.visitLdcInsn("sessions.hytale.com");
                mv.visitFieldInsn(Opcodes.GETSTATIC, HELPER_CLASS, "F2P_DOMAIN", "Ljava/lang/String;");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "replace",
                                "(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;", false);
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitLabel(keepOriginal);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitMaxs(3, 1);
                mv.visitEnd();

                // public static String extractIssuerFromToken(String token)
                // Wrapped with try-catch to handle non-JWT tokens gracefully
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                                "extractIssuerFromToken", "(Ljava/lang/String;)Ljava/lang/String;", null, null);
                mv.visitCode();

                // if (token == null) return null;
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                Label tokenNotNull = new Label();
                mv.visitJumpInsn(Opcodes.IFNONNULL, tokenNotNull);
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitInsn(Opcodes.ARETURN);

                mv.visitLabel(tokenNotNull);


                // try {
                Label tryStart = new Label();
                Label tryEnd = new Label();
                Label catchHandler = new Label();
                mv.visitTryCatchBlock(tryStart, tryEnd, catchHandler, "java/lang/Exception");

                mv.visitLabel(tryStart);

                // int firstDot = token.indexOf('.');
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitIntInsn(Opcodes.BIPUSH, '.');
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "indexOf", "(I)I", false);
                mv.visitVarInsn(Opcodes.ISTORE, 1);

                // if (firstDot < 0) return null;
                mv.visitVarInsn(Opcodes.ILOAD, 1);
                Label hasDot = new Label();
                mv.visitJumpInsn(Opcodes.IFGE, hasDot);
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitInsn(Opcodes.ARETURN);

                mv.visitLabel(hasDot);

                // int secondDot = token.indexOf('.', firstDot + 1);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitIntInsn(Opcodes.BIPUSH, '.');
                mv.visitVarInsn(Opcodes.ILOAD, 1);
                mv.visitInsn(Opcodes.ICONST_1);
                mv.visitInsn(Opcodes.IADD);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "indexOf", "(II)I", false);
                mv.visitVarInsn(Opcodes.ISTORE, 2);

                // if (secondDot < 0) secondDot = token.length();
                mv.visitVarInsn(Opcodes.ILOAD, 2);
                Label hasSecondDot = new Label();
                mv.visitJumpInsn(Opcodes.IFGE, hasSecondDot);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
                mv.visitVarInsn(Opcodes.ISTORE, 2);

                mv.visitLabel(hasSecondDot);

                // String payloadB64 = token.substring(firstDot + 1, secondDot);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitVarInsn(Opcodes.ILOAD, 1);
                mv.visitInsn(Opcodes.ICONST_1);
                mv.visitInsn(Opcodes.IADD);
                mv.visitVarInsn(Opcodes.ILOAD, 2);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring", "(II)Ljava/lang/String;",
                                false);
                mv.visitVarInsn(Opcodes.ASTORE, 3);

                // byte[] decoded = Base64.getUrlDecoder().decode(payloadB64);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Base64", "getUrlDecoder",
                                "()Ljava/util/Base64$Decoder;", false);
                mv.visitVarInsn(Opcodes.ALOAD, 3);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Base64$Decoder", "decode",
                                "(Ljava/lang/String;)[B", false);
                mv.visitVarInsn(Opcodes.ASTORE, 4);

                // String payload = new String(decoded, StandardCharsets.UTF_8);
                mv.visitTypeInsn(Opcodes.NEW, "java/lang/String");
                mv.visitInsn(Opcodes.DUP);
                mv.visitVarInsn(Opcodes.ALOAD, 4);
                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/nio/charset/StandardCharsets", "UTF_8",
                                "Ljava/nio/charset/Charset;");
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/String", "<init>",
                                "([BLjava/nio/charset/Charset;)V", false);
                mv.visitVarInsn(Opcodes.ASTORE, 5);

                // int idx = payload.indexOf("\"iss\":");
                mv.visitVarInsn(Opcodes.ALOAD, 5);
                mv.visitLdcInsn("\"iss\":");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "indexOf",
                                "(Ljava/lang/String;)I", false);
                mv.visitVarInsn(Opcodes.ISTORE, 6);

                // if (idx < 0) return null;
                mv.visitVarInsn(Opcodes.ILOAD, 6);
                Label foundIss = new Label();
                mv.visitJumpInsn(Opcodes.IFGE, foundIss);
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitInsn(Opcodes.ARETURN);

                mv.visitLabel(foundIss);

                // int start = payload.indexOf('"', idx + 6) + 1;
                mv.visitVarInsn(Opcodes.ALOAD, 5);
                mv.visitIntInsn(Opcodes.BIPUSH, '"');
                mv.visitVarInsn(Opcodes.ILOAD, 6);
                mv.visitIntInsn(Opcodes.BIPUSH, 6);
                mv.visitInsn(Opcodes.IADD);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "indexOf", "(II)I", false);
                mv.visitInsn(Opcodes.ICONST_1);
                mv.visitInsn(Opcodes.IADD);
                mv.visitVarInsn(Opcodes.ISTORE, 7);

                // int end = payload.indexOf('"', start);
                mv.visitVarInsn(Opcodes.ALOAD, 5);
                mv.visitIntInsn(Opcodes.BIPUSH, '"');
                mv.visitVarInsn(Opcodes.ILOAD, 7);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "indexOf", "(II)I", false);
                mv.visitVarInsn(Opcodes.ISTORE, 8);

                // if (end < 0) return null;
                mv.visitVarInsn(Opcodes.ILOAD, 8);
                Label foundEnd = new Label();
                mv.visitJumpInsn(Opcodes.IFGE, foundEnd);
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitInsn(Opcodes.ARETURN);

                mv.visitLabel(foundEnd);


                // return payload.substring(start, end);
                mv.visitVarInsn(Opcodes.ALOAD, 5);
                mv.visitVarInsn(Opcodes.ILOAD, 7);
                mv.visitVarInsn(Opcodes.ILOAD, 8);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring", "(II)Ljava/lang/String;",
                                false);
                mv.visitLabel(tryEnd);
                mv.visitInsn(Opcodes.ARETURN);

                // } catch (Exception e) { return null; }
                mv.visitLabel(catchHandler);

                mv.visitInsn(Opcodes.POP); // pop the exception
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitInsn(Opcodes.ARETURN);

                mv.visitMaxs(4, 9);
                mv.visitEnd();

                // public static String extractSubjectFromToken(String token)
                // Wrapped with try-catch to handle non-JWT tokens gracefully
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                                "extractSubjectFromToken", "(Ljava/lang/String;)Ljava/lang/String;", null, null);
                mv.visitCode();

                // if (token == null) return null;
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                Label subTokenNotNull = new Label();
                mv.visitJumpInsn(Opcodes.IFNONNULL, subTokenNotNull);
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitInsn(Opcodes.ARETURN);

                mv.visitLabel(subTokenNotNull);

                // try {
                Label subTryStart = new Label();
                Label subTryEnd = new Label();
                Label subCatchHandler = new Label();
                mv.visitTryCatchBlock(subTryStart, subTryEnd, subCatchHandler, "java/lang/Exception");

                mv.visitLabel(subTryStart);

                // int firstDot = token.indexOf('.');
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitIntInsn(Opcodes.BIPUSH, '.');
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "indexOf", "(I)I", false);
                mv.visitVarInsn(Opcodes.ISTORE, 1);

                // if (firstDot < 0) return null;
                mv.visitVarInsn(Opcodes.ILOAD, 1);
                Label subHasDot = new Label();
                mv.visitJumpInsn(Opcodes.IFGE, subHasDot);
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitInsn(Opcodes.ARETURN);

                mv.visitLabel(subHasDot);

                // int secondDot = token.indexOf('.', firstDot + 1);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitIntInsn(Opcodes.BIPUSH, '.');
                mv.visitVarInsn(Opcodes.ILOAD, 1);
                mv.visitInsn(Opcodes.ICONST_1);
                mv.visitInsn(Opcodes.IADD);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "indexOf", "(II)I", false);
                mv.visitVarInsn(Opcodes.ISTORE, 2);

                // if (secondDot < 0) secondDot = token.length();
                mv.visitVarInsn(Opcodes.ILOAD, 2);
                Label subHasSecondDot = new Label();
                mv.visitJumpInsn(Opcodes.IFGE, subHasSecondDot);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
                mv.visitVarInsn(Opcodes.ISTORE, 2);

                mv.visitLabel(subHasSecondDot);

                // String payloadB64 = token.substring(firstDot + 1, secondDot);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitVarInsn(Opcodes.ILOAD, 1);
                mv.visitInsn(Opcodes.ICONST_1);
                mv.visitInsn(Opcodes.IADD);
                mv.visitVarInsn(Opcodes.ILOAD, 2);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring", "(II)Ljava/lang/String;",
                                false);
                mv.visitVarInsn(Opcodes.ASTORE, 3);

                // byte[] decoded = Base64.getUrlDecoder().decode(payloadB64);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Base64", "getUrlDecoder",
                                "()Ljava/util/Base64$Decoder;", false);
                mv.visitVarInsn(Opcodes.ALOAD, 3);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Base64$Decoder", "decode",
                                "(Ljava/lang/String;)[B", false);
                mv.visitVarInsn(Opcodes.ASTORE, 4);

                // String payload = new String(decoded, StandardCharsets.UTF_8);
                mv.visitTypeInsn(Opcodes.NEW, "java/lang/String");
                mv.visitInsn(Opcodes.DUP);
                mv.visitVarInsn(Opcodes.ALOAD, 4);
                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/nio/charset/StandardCharsets", "UTF_8",
                                "Ljava/nio/charset/Charset;");
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/String", "<init>",
                                "([BLjava/nio/charset/Charset;)V", false);
                mv.visitVarInsn(Opcodes.ASTORE, 5);

                // int idx = payload.indexOf("\"sub\":");
                mv.visitVarInsn(Opcodes.ALOAD, 5);
                mv.visitLdcInsn("\"sub\":");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "indexOf",
                                "(Ljava/lang/String;)I", false);
                mv.visitVarInsn(Opcodes.ISTORE, 6);

                // if (idx < 0) return null;
                mv.visitVarInsn(Opcodes.ILOAD, 6);
                Label subFoundSub = new Label();
                mv.visitJumpInsn(Opcodes.IFGE, subFoundSub);
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitInsn(Opcodes.ARETURN);

                mv.visitLabel(subFoundSub);

                // int start = payload.indexOf('"', idx + 6) + 1;
                mv.visitVarInsn(Opcodes.ALOAD, 5);
                mv.visitIntInsn(Opcodes.BIPUSH, '"');
                mv.visitVarInsn(Opcodes.ILOAD, 6);
                mv.visitIntInsn(Opcodes.BIPUSH, 6);
                mv.visitInsn(Opcodes.IADD);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "indexOf", "(II)I", false);
                mv.visitInsn(Opcodes.ICONST_1);
                mv.visitInsn(Opcodes.IADD);
                mv.visitVarInsn(Opcodes.ISTORE, 7);

                // int end = payload.indexOf('"', start);
                mv.visitVarInsn(Opcodes.ALOAD, 5);
                mv.visitIntInsn(Opcodes.BIPUSH, '"');
                mv.visitVarInsn(Opcodes.ILOAD, 7);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "indexOf", "(II)I", false);
                mv.visitVarInsn(Opcodes.ISTORE, 8);

                // if (end < 0) return null;
                mv.visitVarInsn(Opcodes.ILOAD, 8);
                Label subFoundEnd = new Label();
                mv.visitJumpInsn(Opcodes.IFGE, subFoundEnd);
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitInsn(Opcodes.ARETURN);

                mv.visitLabel(subFoundEnd);

                // return payload.substring(start, end);
                mv.visitVarInsn(Opcodes.ALOAD, 5);
                mv.visitVarInsn(Opcodes.ILOAD, 7);
                mv.visitVarInsn(Opcodes.ILOAD, 8);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring", "(II)Ljava/lang/String;",
                                false);
                mv.visitLabel(subTryEnd);
                mv.visitInsn(Opcodes.ARETURN);

                // } catch (Exception e) { return null; }
                mv.visitLabel(subCatchHandler);
                mv.visitInsn(Opcodes.POP); // pop the exception
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitInsn(Opcodes.ARETURN);

                mv.visitMaxs(4, 9);
                mv.visitEnd();

                // public static void log(String msg)
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                                "log", "(Ljava/lang/String;)V", null, null);
                mv.visitCode();
                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn("[DualAuth] ");
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>",
                                "(Ljava/lang/String;)V", false);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
                                "()Ljava/lang/String;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println",
                                "(Ljava/lang/String;)V", false);
                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(4, 1);
                mv.visitEnd();

                // public static void logAuthGrant(String serverIdentityToken)
                // Logs AuthGrant.serialize() debug info - isF2P, issuer, and original token
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                                "logAuthGrant", "(Ljava/lang/String;)V", null, null);
                mv.visitCode();
                // System.out.println("[DualAuth] AuthGrant.serialize() - isF2P=" + isF2P() + ",
                // issuer=" + getIssuer());
                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn("[DualAuth] AuthGrant.serialize() - isF2P=");
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V",
                                false);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, CONTEXT_CLASS, "isF2P", "()Z", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                "(Z)Ljava/lang/StringBuilder;",
                                false);
                mv.visitLdcInsn(", issuer=");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, CONTEXT_CLASS, "getIssuer", "()Ljava/lang/String;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;",
                                false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V",
                                false);

                // System.out.println("[DualAuth] Original serverIdentityToken: " +
                // truncate(token));
                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn("[DualAuth] Original serverIdentityToken: ");
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V",
                                false);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_CLASS, "truncateToken",
                                "(Ljava/lang/String;)Ljava/lang/String;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;",
                                false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V",
                                false);

                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(4, 1);
                mv.visitEnd();

                // public static void logF2PIdentity(String f2pIdentity)
                // Logs the F2P identity token received from DualServerIdentity
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                                "logF2PIdentity", "(Ljava/lang/String;)V", null, null);
                mv.visitCode();
                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn("[DualAuth] F2P identity from DualServerIdentity: ");
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V",
                                false);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_CLASS, "truncateToken",
                                "(Ljava/lang/String;)Ljava/lang/String;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;",
                                false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V",
                                false);
                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(4, 1);
                mv.visitEnd();

                // public static String truncateToken(String token)
                // Helper to truncate tokens for logging (first 50 chars + "...")
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                                "truncateToken", "(Ljava/lang/String;)Ljava/lang/String;", null, null);
                mv.visitCode();
                // if (token == null) return "null";
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                Label tokenNotNullLabel = new Label();
                mv.visitJumpInsn(Opcodes.IFNONNULL, tokenNotNullLabel);
                mv.visitLdcInsn("null");
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitLabel(tokenNotNullLabel);

                // if (token.length() <= 50) return token + "...";
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
                mv.visitIntInsn(Opcodes.BIPUSH, 50);
                Label tooLong = new Label();
                mv.visitJumpInsn(Opcodes.IF_ICMPGT, tooLong);
                mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
                mv.visitInsn(Opcodes.DUP);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V",
                                false);
                mv.visitLdcInsn("...");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;",
                                false);
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitLabel(tooLong);

                // return token.substring(0, 50) + "...";
                mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
                mv.visitInsn(Opcodes.DUP);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitInsn(Opcodes.ICONST_0);
                mv.visitIntInsn(Opcodes.BIPUSH, 50);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring", "(II)Ljava/lang/String;",
                                false);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V",
                                false);
                mv.visitLdcInsn("...");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;",
                                false);
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitMaxs(4, 1);
                mv.visitEnd();

                // public static void maybeReplaceServerIdentity(AuthGrant packet)
                // Checks if authorizationGrant is from F2P and replaces serverIdentityToken if
                // needed
                // This works regardless of thread since we check the actual token content
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                                "maybeReplaceServerIdentity", "(L" + AUTH_GRANT_CLASS + ";)V", null, null);
                mv.visitCode();

                // String authGrant = packet.authorizationGrant;
                mv.visitVarInsn(Opcodes.ALOAD, 0); // packet
                mv.visitFieldInsn(Opcodes.GETFIELD, AUTH_GRANT_CLASS, "authorizationGrant", "Ljava/lang/String;");
                mv.visitVarInsn(Opcodes.ASTORE, 1); // authGrant

                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                mv.visitLdcInsn("[DualAuth] Analyzing client token for embedded private authority...");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V",
                                false);

                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, SERVER_IDENTITY_CLASS,
                                "createTokenFromClientKey", "(Ljava/lang/String;)Ljava/lang/String;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 2);

                mv.visitVarInsn(Opcodes.ALOAD, 2);
                Label fallback = new Label();
                mv.visitJumpInsn(Opcodes.IFNULL, fallback);

                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                mv.visitLdcInsn("[DualAuth] SUCCESS! Used client's embedded Private Key (Omni-Auth) to sign server identity.");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V",
                                false);

                // Omni-Auth: keep existing serverIdentityToken (mutual authentication requires
                // it)
                mv.visitInsn(Opcodes.RETURN);

                mv.visitLabel(fallback);


                // String issuer = extractIssuerFromToken(authGrant);
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_CLASS, "extractIssuerFromToken",
                                "(Ljava/lang/String;)Ljava/lang/String;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 3); // issuer

                // Log: "[DualAuth] AuthGrant.serialize() - authGrant issuer=" + issuer
                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn("[DualAuth] AuthGrant.serialize() - authGrant issuer=");
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V",
                                false);
                mv.visitVarInsn(Opcodes.ALOAD, 3);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitLdcInsn(", serverIdentityToken=");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitFieldInsn(Opcodes.GETFIELD, AUTH_GRANT_CLASS, "serverIdentityToken", "Ljava/lang/String;");
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_CLASS, "truncateToken",
                                "(Ljava/lang/String;)Ljava/lang/String;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;",
                                false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V",
                                false);

                // if (issuer == null) return; // Can't determine, keep as-is
                mv.visitVarInsn(Opcodes.ALOAD, 3);
                Label replaceIssuerNotNull = new Label();
                mv.visitJumpInsn(Opcodes.IFNONNULL, replaceIssuerNotNull);
                mv.visitInsn(Opcodes.RETURN);
                mv.visitLabel(replaceIssuerNotNull);


                mv.visitVarInsn(Opcodes.ALOAD, 3);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_CLASS, "isOfficialIssuer", "(Ljava/lang/String;)Z",
                                false);
                Label doReplace = new Label();
                mv.visitJumpInsn(Opcodes.IFEQ, doReplace);
                mv.visitInsn(Opcodes.RETURN);

                mv.visitLabel(doReplace);

                // Use the NEW dynamic logic from TokenManager
                mv.visitVarInsn(Opcodes.ALOAD, 3); // issuer
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, TOKEN_MANAGER_CLASS, "getIdentityTokenForIssuer",
                                "(Ljava/lang/String;)Ljava/lang/String;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 4); // identityToken

                mv.visitVarInsn(Opcodes.ALOAD, 4);
                Label haveToken = new Label();
                mv.visitJumpInsn(Opcodes.IFNULL, haveToken);

                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn("[DualAuth] Successfully resolved server identity for issuer: ");
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V",
                                false);
                mv.visitVarInsn(Opcodes.ALOAD, 3);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;",
                                false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V",
                                false);

                mv.visitVarInsn(Opcodes.ALOAD, 0); // packet
                mv.visitVarInsn(Opcodes.ALOAD, 4);
                mv.visitFieldInsn(Opcodes.PUTFIELD, AUTH_GRANT_CLASS, "serverIdentityToken", "Ljava/lang/String;");

                mv.visitLabel(haveToken);
                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(0, 0);
                mv.visitEnd();

                // public static String getSessionUrlForToken(String token)
                // Extracts issuer from token and returns the correct session URL
                // Used for token refresh to route to the correct backend
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                                "getSessionUrlForToken", "(Ljava/lang/String;)Ljava/lang/String;", null, null);
                mv.visitCode();

                // String issuer = extractIssuerFromToken(token);
                mv.visitVarInsn(Opcodes.ALOAD, 0); // token
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_CLASS, "extractIssuerFromToken",
                                "(Ljava/lang/String;)Ljava/lang/String;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 1); // issuer

                // Log the decision
                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn("[DualAuth] getSessionUrlForToken - issuer=");
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V",
                                false);
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;",
                                false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V",
                                false);

                // return getSessionUrlForIssuer(issuer);
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_CLASS, "getSessionUrlForIssuer",
                                "(Ljava/lang/String;)Ljava/lang/String;", false);
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitMaxs(0, 0);
                mv.visitEnd();

                mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                                "extractJwkFromToken", "(Ljava/lang/String;)Ljava/lang/String;", null, null);
                mv.visitCode();
                Label tStart = new Label();
                Label tCatch = new Label();
                mv.visitTryCatchBlock(tStart, tCatch, tCatch, "java/lang/Exception");
                mv.visitLabel(tStart);

                // Split manual "."
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitIntInsn(Opcodes.BIPUSH, '.');
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "indexOf", "(I)I", false);
                mv.visitVarInsn(Opcodes.ISTORE, 1);

                // String headerB64 = token.substring(0, dot1);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitInsn(Opcodes.ICONST_0);
                mv.visitVarInsn(Opcodes.ILOAD, 1);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring", "(II)Ljava/lang/String;",
                                false);
                mv.visitVarInsn(Opcodes.ASTORE, 2);

                // JWSHeader h = JWSHeader.parse(new Base64URL(headerB64));
                mv.visitTypeInsn(Opcodes.NEW, "com/nimbusds/jose/util/Base64URL");
                mv.visitInsn(Opcodes.DUP);
                mv.visitVarInsn(Opcodes.ALOAD, 2);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/nimbusds/jose/util/Base64URL", "<init>",
                                "(Ljava/lang/String;)V",
                                false);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/nimbusds/jose/JWSHeader", "parse",
                                "(Lcom/nimbusds/jose/util/Base64URL;)Lcom/nimbusds/jose/JWSHeader;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 3);

                // return h.getJWK().toJSONString();
                mv.visitVarInsn(Opcodes.ALOAD, 3);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jose/JWSHeader", "getJWK",
                                "()Lcom/nimbusds/jose/jwk/JWK;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jose/jwk/JWK", "toJSONString",
                                "()Ljava/lang/String;",
                                false);
                mv.visitInsn(Opcodes.ARETURN);

                mv.visitLabel(tCatch);
                mv.visitInsn(Opcodes.POP);
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitMaxs(0, 0);
                mv.visitEnd();

                // public static boolean isOmniIssuerTrusted(String issuer)
                // Checks if Omni-Auth issuer is in TRUSTED_ISSUERS list
                // This is used when TRUST_ALL_ISSUERS=false to still allow specific issuers
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                                "isOmniIssuerTrusted", "(Ljava/lang/String;)Z", null, null);
                mv.visitCode();

                // If issuer is null, return false
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                Label omniIssuerNotNull = new Label();
                mv.visitJumpInsn(Opcodes.IFNONNULL, omniIssuerNotNull);
                mv.visitInsn(Opcodes.ICONST_0);
                mv.visitInsn(Opcodes.IRETURN);

                mv.visitLabel(omniIssuerNotNull);

                // Get TRUSTED_ISSUERS env
                mv.visitLdcInsn("HYTALE_TRUSTED_ISSUERS");
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getenv",
                                "(Ljava/lang/String;)Ljava/lang/String;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 1); // trustedCsv

                // If null or empty, return false
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                Label trustedNotNull = new Label();
                mv.visitJumpInsn(Opcodes.IFNONNULL, trustedNotNull);
                mv.visitInsn(Opcodes.ICONST_0);
                mv.visitInsn(Opcodes.IRETURN);

                mv.visitLabel(trustedNotNull);
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "trim", "()Ljava/lang/String;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 1);
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "isEmpty", "()Z", false);
                Label trustedNotEmpty = new Label();
                mv.visitJumpInsn(Opcodes.IFEQ, trustedNotEmpty);
                mv.visitInsn(Opcodes.ICONST_0);
                mv.visitInsn(Opcodes.IRETURN);

                mv.visitLabel(trustedNotEmpty);

                // Extract host from issuer URL for matching
                // Try to get just the host part: http://127.0.0.1:12345 -> 127.0.0.1
                mv.visitVarInsn(Opcodes.ALOAD, 0); // issuer
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toLowerCase", "()Ljava/lang/String;",
                                false);
                mv.visitVarInsn(Opcodes.ASTORE, 2); // issuerLower

                // Split trusted issuers by comma and check each
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitLdcInsn(",");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "split",
                                "(Ljava/lang/String;)[Ljava/lang/String;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 3); // parts[]

                // Loop through parts
                mv.visitInsn(Opcodes.ICONST_0);
                mv.visitVarInsn(Opcodes.ISTORE, 4); // i = 0

                Label omniLoopCheck = new Label();
                Label omniLoopBody = new Label();
                Label omniReturnFalse = new Label();

                mv.visitJumpInsn(Opcodes.GOTO, omniLoopCheck);

                mv.visitLabel(omniLoopBody);
                // part = parts[i].trim().toLowerCase()
                mv.visitVarInsn(Opcodes.ALOAD, 3);
                mv.visitVarInsn(Opcodes.ILOAD, 4);
                mv.visitInsn(Opcodes.AALOAD);
                mv.visitVarInsn(Opcodes.ASTORE, 5); // part

                mv.visitVarInsn(Opcodes.ALOAD, 5);
                Label omniPartNotNull = new Label();
                mv.visitJumpInsn(Opcodes.IFNONNULL, omniPartNotNull);
                mv.visitIincInsn(4, 1); // i++ - skip null parts
                mv.visitJumpInsn(Opcodes.GOTO, omniLoopCheck);

                mv.visitLabel(omniPartNotNull);
                mv.visitVarInsn(Opcodes.ALOAD, 5);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "trim", "()Ljava/lang/String;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toLowerCase", "()Ljava/lang/String;",
                                false);
                mv.visitVarInsn(Opcodes.ASTORE, 5);

                mv.visitVarInsn(Opcodes.ALOAD, 5);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "isEmpty", "()Z", false);
                Label omniPartNotEmpty = new Label();
                mv.visitJumpInsn(Opcodes.IFEQ, omniPartNotEmpty);
                mv.visitIincInsn(4, 1);
                mv.visitJumpInsn(Opcodes.GOTO, omniLoopCheck);

                mv.visitLabel(omniPartNotEmpty);

                // Check if issuer contains the trusted part (host match)
                // e.g., "http://127.0.0.1:12345" contains "127.0.0.1"
                mv.visitVarInsn(Opcodes.ALOAD, 2); // issuerLower
                mv.visitVarInsn(Opcodes.ALOAD, 5); // part
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "contains",
                                "(Ljava/lang/CharSequence;)Z", false);
                Label omniNoMatch = new Label();
                mv.visitJumpInsn(Opcodes.IFEQ, omniNoMatch);

                // Match found! Log and return true
                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn("[DualAuth] Omni-Auth issuer trusted via TRUSTED_ISSUERS: ");
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V",
                                false);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitLdcInsn(" (matched: ");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitVarInsn(Opcodes.ALOAD, 5);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitLdcInsn(")");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;",
                                false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V",
                                false);
                mv.visitInsn(Opcodes.ICONST_1);
                mv.visitInsn(Opcodes.IRETURN);

                mv.visitLabel(omniNoMatch);
                mv.visitIincInsn(4, 1); // i++

                mv.visitLabel(omniLoopCheck);
                mv.visitVarInsn(Opcodes.ILOAD, 4); // i
                mv.visitVarInsn(Opcodes.ALOAD, 3); // parts
                mv.visitInsn(Opcodes.ARRAYLENGTH);
                mv.visitJumpInsn(Opcodes.IF_ICMPLT, omniLoopBody);

                // No match found
                mv.visitLabel(omniReturnFalse);
                mv.visitInsn(Opcodes.ICONST_0);
                mv.visitInsn(Opcodes.IRETURN);

                mv.visitMaxs(0, 0);
                mv.visitEnd();

                cw.visitEnd();
                return cw.toByteArray();
        }


        /**
         * Generate DualJwksFetcher class - Fetches and merges JWKS
         * 
         * v10.0 FIX: restore extractKeysContent internally and put it FIRST.
         */
        private static byte[] generateDualJwksFetcher() {
        ClassWriter cw = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        MethodVisitor mv;

        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, JWKS_FETCHER_CLASS, null,
                        "java/lang/Object", null);

        // Fields
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "OFFICIAL_JWKS_URL", "Ljava/lang/String;", null,
                        null).visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "F2P_JWKS_URL", "Ljava/lang/String;", null, null)
                        .visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE, "cachedBaseKeysContent",
                        "Ljava/lang/String;", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE, "lastBaseFetchMs", "J",
                        null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "dynamicIssuerCache",
                        "Ljava/util/concurrent/ConcurrentHashMap;", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE, "finalAggregatedJson",
                        "Ljava/lang/String;", null, null).visitEnd();

        // <clinit>
        mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, HELPER_CLASS, "OFFICIAL_URL", "Ljava/lang/String;");
        mv.visitLdcInsn("/.well-known/jwks.json");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat",
                        "(Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, JWKS_FETCHER_CLASS, "OFFICIAL_JWKS_URL", "Ljava/lang/String;");

        mv.visitFieldInsn(Opcodes.GETSTATIC, HELPER_CLASS, "F2P_URL", "Ljava/lang/String;");
        mv.visitLdcInsn("/.well-known/jwks.json");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat",
                        "(Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, JWKS_FETCHER_CLASS, "F2P_JWKS_URL", "Ljava/lang/String;");

        // Init cache map
        mv.visitTypeInsn(Opcodes.NEW, "java/util/concurrent/ConcurrentHashMap");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/concurrent/ConcurrentHashMap", "<init>", "()V",
                        false);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, JWKS_FETCHER_CLASS, "dynamicIssuerCache",
                        "Ljava/util/concurrent/ConcurrentHashMap;");

        // Init string field
        mv.visitLdcInsn("");
        mv.visitFieldInsn(Opcodes.PUTSTATIC, JWKS_FETCHER_CLASS, "cachedBaseKeysContent", "Ljava/lang/String;");
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 0);
        mv.visitEnd();

        // Constructor
        mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();

        // --- DEFINE extractKeysContent FIRST (before callers) ---
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                        "extractKeysContent", "(Ljava/lang/String;)Ljava/lang/String;", null, null);
        mv.visitCode();
        Label errLabel = new Label();

        // if (json == null) return "";
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        Label nullCheck = new Label();
        mv.visitJumpInsn(Opcodes.IFNONNULL, nullCheck);
        mv.visitLdcInsn("");
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(nullCheck);

        // int idx = json.indexOf("\"keys\":");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitLdcInsn("\"keys\":");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "indexOf",
                        "(Ljava/lang/String;)I", false);
        mv.visitVarInsn(Opcodes.ISTORE, 1);

        // int start = json.indexOf('[', idx);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitIntInsn(Opcodes.BIPUSH, '[');
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "indexOf", "(II)I", false);
        mv.visitVarInsn(Opcodes.ISTORE, 2);

        // int end = json.lastIndexOf(']');
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitIntInsn(Opcodes.BIPUSH, ']');
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "lastIndexOf", "(I)I", false);
        mv.visitVarInsn(Opcodes.ISTORE, 3);

        // Check if found and valid
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitInsn(Opcodes.ICONST_M1);
        mv.visitJumpInsn(Opcodes.IF_ICMPEQ, errLabel);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitInsn(Opcodes.ICONST_M1);
        mv.visitJumpInsn(Opcodes.IF_ICMPEQ, errLabel);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitJumpInsn(Opcodes.IF_ICMPLE, errLabel);

        // String keysContent = json.substring(start + 1, end).trim();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IADD);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring",
                        "(II)Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "trim", "()Ljava/lang/String;",
                        false);
        mv.visitInsn(Opcodes.ARETURN);

        mv.visitLabel(errLabel);

        mv.visitLdcInsn("");
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(4, 4);
        mv.visitEnd();

        // -------------------------------------------------------------
        // registerIssuer(String issuer)
        // -------------------------------------------------------------
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "registerIssuer", "(Ljava/lang/String;)V",
                        null, null);
        mv.visitCode();

        // 1. Basic Validation
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_CLASS, "isValidIssuer", "(Ljava/lang/String;)Z", false);
        Label doRegister = new Label();
        mv.visitJumpInsn(Opcodes.IFNE, doRegister);
        mv.visitInsn(Opcodes.RETURN);

        mv.visitLabel(doRegister);


        // 2. Skip Official (Optimized Path)
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_CLASS, "isOfficialIssuer", "(Ljava/lang/String;)Z", false);
        Label finish = new Label();
        mv.visitJumpInsn(Opcodes.IFNE, finish);

        // 3. Skip if already cached
        mv.visitFieldInsn(Opcodes.GETSTATIC, JWKS_FETCHER_CLASS, "dynamicIssuerCache", "Ljava/util/concurrent/ConcurrentHashMap;");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/concurrent/ConcurrentHashMap", "containsKey", "(Ljava/lang/Object;)Z", false);
        mv.visitJumpInsn(Opcodes.IFNE, finish);

        // Discovery: Fetch JWKS from the new domain
        // String jwksUrl = issuer + (issuer.endsWith("/") ? "" : "/") + ".well-known/jwks.json";
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitLdcInsn("/");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "endsWith", "(Ljava/lang/String;)Z", false);
        Label addSlash = new Label();
        mv.visitJumpInsn(Opcodes.IFNE, addSlash);
        mv.visitLdcInsn("/");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitLabel(addSlash);

        mv.visitLdcInsn(".well-known/jwks.json");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
        mv.visitVarInsn(Opcodes.ASTORE, 1); // jwksUrl

        // Fetch
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, JWKS_FETCHER_CLASS, "fetchJwksJson", "(Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitVarInsn(Opcodes.ASTORE, 2); // json

        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitJumpInsn(Opcodes.IFNULL, finish);

        // Discovery Success! Extract and cache
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, JWKS_FETCHER_CLASS, "extractKeysContent", "(Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitVarInsn(Opcodes.ASTORE, 3); // content

        mv.visitFieldInsn(Opcodes.GETSTATIC, JWKS_FETCHER_CLASS, "dynamicIssuerCache", "Ljava/util/concurrent/ConcurrentHashMap;");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/concurrent/ConcurrentHashMap", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
        mv.visitInsn(Opcodes.POP);

        // Invalidate aggregate cache
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, JWKS_FETCHER_CLASS, "finalAggregatedJson", "Ljava/lang/String;");

        // Log
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn("[DualAuth] Discovered new key source: ");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

        mv.visitLabel(finish);

        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(4, 4);
        mv.visitEnd();

        // -------------------------------------------------------------
        // fetchJwksJson(String url)
        // -------------------------------------------------------------
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "fetchJwksJson",
                        "(Ljava/lang/String;)Ljava/lang/String;", null, null);
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/net/http/HttpClient", "newBuilder",
                        "()Ljava/net/http/HttpClient$Builder;", false);
        mv.visitLdcInsn(5L);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/time/Duration", "ofSeconds", "(J)Ljava/time/Duration;",
                        false);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/net/http/HttpClient$Builder", "connectTimeout",
                        "(Ljava/time/Duration;)Ljava/net/http/HttpClient$Builder;", true);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/net/http/HttpClient$Builder", "build",
                        "()Ljava/net/http/HttpClient;", true);
        mv.visitVarInsn(Opcodes.ASTORE, 1);

        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/net/http/HttpRequest", "newBuilder",
                        "()Ljava/net/http/HttpRequest$Builder;", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/net/URI", "create", "(Ljava/lang/String;)Ljava/net/URI;",
                        false);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/net/http/HttpRequest$Builder", "uri",
                        "(Ljava/net/URI;)Ljava/net/http/HttpRequest$Builder;", true);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/net/http/HttpRequest$Builder", "GET",
                        "()Ljava/net/http/HttpRequest$Builder;", true);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/net/http/HttpRequest$Builder", "build",
                        "()Ljava/net/http/HttpRequest;", true);
        mv.visitVarInsn(Opcodes.ASTORE, 2);

        Label tryStart = new Label(), tryEnd = new Label(), catchHandler = new Label();
        mv.visitTryCatchBlock(tryStart, tryEnd, catchHandler, "java/lang/Exception");

        mv.visitLabel(tryStart);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/net/http/HttpResponse$BodyHandlers", "ofString",
                        "()Ljava/net/http/HttpResponse$BodyHandler;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/net/http/HttpClient", "send",
                        "(Ljava/net/http/HttpRequest;Ljava/net/http/HttpResponse$BodyHandler;)Ljava/net/http/HttpResponse;",
                        false);
        mv.visitVarInsn(Opcodes.ASTORE, 3);

        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/net/http/HttpResponse", "statusCode", "()I", true);
        mv.visitIntInsn(Opcodes.SIPUSH, 200);
        Label statusFail = new Label();
        mv.visitJumpInsn(Opcodes.IF_ICMPNE, statusFail);

        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/net/http/HttpResponse", "body", "()Ljava/lang/Object;",
                        true);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String");
        mv.visitLabel(tryEnd);
        mv.visitInsn(Opcodes.ARETURN);

        mv.visitLabel(statusFail);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);

        mv.visitLabel(catchHandler);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(3, 4);
        mv.visitEnd();

        // -------------------------------------------------------------
        // fetchMergedJwksJson()
        // -------------------------------------------------------------
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNCHRONIZED,
                        "fetchMergedJwksJson", "()Ljava/lang/String;", null, null);
        mv.visitCode();

        mv.visitFieldInsn(Opcodes.GETSTATIC, JWKS_FETCHER_CLASS, "finalAggregatedJson", "Ljava/lang/String;");
        mv.visitVarInsn(Opcodes.ASTORE, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        Label refresh = new Label();
        mv.visitJumpInsn(Opcodes.IFNULL, refresh);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ARETURN);

        mv.visitLabel(refresh);


        // Check base TTL
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "currentTimeMillis", "()J", false);
        mv.visitFieldInsn(Opcodes.GETSTATIC, JWKS_FETCHER_CLASS, "lastBaseFetchMs", "J");
        mv.visitInsn(Opcodes.LSUB);
        mv.visitLdcInsn(3600000L);
        mv.visitInsn(Opcodes.LCMP);
        Label skipBase = new Label();
        mv.visitJumpInsn(Opcodes.IFLE, skipBase);

        // Update Base
        mv.visitFieldInsn(Opcodes.GETSTATIC, JWKS_FETCHER_CLASS, "OFFICIAL_JWKS_URL", "Ljava/lang/String;");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, JWKS_FETCHER_CLASS, "fetchJwksJson",
                        "(Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitVarInsn(Opcodes.ASTORE, 1);

        mv.visitFieldInsn(Opcodes.GETSTATIC, JWKS_FETCHER_CLASS, "F2P_JWKS_URL", "Ljava/lang/String;");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, JWKS_FETCHER_CLASS, "fetchJwksJson",
                        "(Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitVarInsn(Opcodes.ASTORE, 2);

        mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
        mv.visitVarInsn(Opcodes.ASTORE, 3);

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        Label offNull = new Label();
        mv.visitJumpInsn(Opcodes.IFNULL, offNull);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ALOAD, 1);

        // Use SELF method
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, JWKS_FETCHER_CLASS, "extractKeysContent",
                        "(Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                        "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitLabel(offNull);


        mv.visitVarInsn(Opcodes.ALOAD, 2);
        Label f2pNull = new Label();
        mv.visitJumpInsn(Opcodes.IFNULL, f2pNull);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "length", "()I", false);
        Label f2pComma = new Label();
        mv.visitJumpInsn(Opcodes.IFLE, f2pComma);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitLdcInsn(",");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                        "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitLabel(f2pComma);

        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        
        // Use SELF method
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, JWKS_FETCHER_CLASS, "extractKeysContent",
                        "(Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                        "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitLabel(f2pNull);


        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;",
                        false);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, JWKS_FETCHER_CLASS, "cachedBaseKeysContent", "Ljava/lang/String;");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "currentTimeMillis", "()J", false);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, JWKS_FETCHER_CLASS, "lastBaseFetchMs", "J");

        mv.visitLabel(skipBase);


        // Build Aggregate
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn("{\"keys\":[");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V",
                        false);
        mv.visitVarInsn(Opcodes.ASTORE, 1); // sb

        // Append Base
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitFieldInsn(Opcodes.GETSTATIC, JWKS_FETCHER_CLASS, "cachedBaseKeysContent", "Ljava/lang/String;");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                        "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitInsn(Opcodes.POP);

        // Append Dynamic
        mv.visitFieldInsn(Opcodes.GETSTATIC, JWKS_FETCHER_CLASS, "dynamicIssuerCache",
                        "Ljava/util/concurrent/ConcurrentHashMap;");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/concurrent/ConcurrentHashMap", "values",
                        "()Ljava/util/Collection;", false);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Collection", "iterator", "()Ljava/util/Iterator;",
                        true);
        mv.visitVarInsn(Opcodes.ASTORE, 2);

        Label loop = new Label(), end = new Label();
        mv.visitLabel(loop);

        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
        mv.visitJumpInsn(Opcodes.IFEQ, end);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String");
        mv.visitVarInsn(Opcodes.ASTORE, 3);

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "length", "()I", false);
        mv.visitIntInsn(Opcodes.BIPUSH, 9);
        Label dynComma = new Label();
        mv.visitJumpInsn(Opcodes.IF_ICMPLE, dynComma);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitLdcInsn(",");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                        "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitLabel(dynComma);


        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                        "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitJumpInsn(Opcodes.GOTO, loop);

        mv.visitLabel(end);

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitLdcInsn("]}");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                        "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitInsn(Opcodes.POP);

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;",
                        false);
        mv.visitInsn(Opcodes.DUP);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, JWKS_FETCHER_CLASS, "finalAggregatedJson", "Ljava/lang/String;");
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(3, 4);
        mv.visitEnd();


        cw.visitEnd();
        return cw.toByteArray();
    }


        /**
         * Generate DualServerIdentity class - Fetches and caches F2P server identity
         * token
         *
         * This class allows the game server to have a separate identity for F2P
         * clients.
         * It fetches the server identity from the F2P auth server (sanasol.ws).
         */
        private static byte[] generateDualServerIdentity() {
                ClassWriter cw = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
                MethodVisitor mv;

                cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                                SERVER_IDENTITY_CLASS, null, "java/lang/Object", null);

                // Static fields
                cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE,
                                "f2pIdentityToken", "Ljava/lang/String;", null, null).visitEnd();
                cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE,
                                "f2pSessionToken", "Ljava/lang/String;", null, null).visitEnd();
                cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                                "lastFetchTime", "J", null, null).visitEnd();
                cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                                "CACHE_TTL_MS", "J", null, null).visitEnd(); // Configurable cache TTL

                // Constants
                cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                                "F2P_SESSION_URL", "Ljava/lang/String;", null, null).visitEnd();

                // Static Initializer (<clinit>)
                mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
                mv.visitCode();
                mv.visitFieldInsn(Opcodes.GETSTATIC, HELPER_CLASS, "F2P_URL", "Ljava/lang/String;");
                mv.visitFieldInsn(Opcodes.PUTSTATIC, SERVER_IDENTITY_CLASS, "F2P_SESSION_URL", "Ljava/lang/String;");

                // Initialize CACHE_TTL_MS from ENV
                mv.visitLdcInsn("HYTALE_IDENTITY_CACHE_TTL");
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getenv",
                                "(Ljava/lang/String;)Ljava/lang/String;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 0);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                Label hasTtl = new Label();
                mv.visitJumpInsn(Opcodes.IFNONNULL, hasTtl);
                mv.visitLdcInsn(300000L); // 5 min default
                mv.visitFieldInsn(Opcodes.PUTSTATIC, SERVER_IDENTITY_CLASS, "CACHE_TTL_MS", "J");
                Label ttlDone = new Label();
                mv.visitJumpInsn(Opcodes.GOTO, ttlDone);

                mv.visitLabel(hasTtl);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Long", "parseLong", "(Ljava/lang/String;)J", false);
                mv.visitLdcInsn(1000L); // Convert seconds to ms
                mv.visitInsn(Opcodes.LMUL);
                mv.visitFieldInsn(Opcodes.PUTSTATIC, SERVER_IDENTITY_CLASS, "CACHE_TTL_MS", "J");

                mv.visitLabel(ttlDone);
                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(1, 0);
                mv.visitEnd();

                // Private constructor
                mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null);
                mv.visitCode();
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(1, 1);
                mv.visitEnd();

                // public static String getF2PIdentityToken()
                // Returns cached token or fetches a new one if expired/missing
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNCHRONIZED,
                                "getF2PIdentityToken", "()Ljava/lang/String;", null, null);
                mv.visitCode();

                // if (f2pIdentityToken != null && (System.currentTimeMillis() - lastFetchTime)
                // < CACHE_TTL_MS) return f2pIdentityToken;
                mv.visitFieldInsn(Opcodes.GETSTATIC, SERVER_IDENTITY_CLASS, "f2pIdentityToken", "Ljava/lang/String;");
                Label fetchNew = new Label();
                mv.visitJumpInsn(Opcodes.IFNULL, fetchNew);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "currentTimeMillis", "()J", false);
                mv.visitFieldInsn(Opcodes.GETSTATIC, SERVER_IDENTITY_CLASS, "lastFetchTime", "J");
                mv.visitInsn(Opcodes.LSUB);
                mv.visitFieldInsn(Opcodes.GETSTATIC, SERVER_IDENTITY_CLASS, "CACHE_TTL_MS", "J");
                mv.visitInsn(Opcodes.LCMP);
                mv.visitJumpInsn(Opcodes.IFGE, fetchNew);
                mv.visitFieldInsn(Opcodes.GETSTATIC, SERVER_IDENTITY_CLASS, "f2pIdentityToken", "Ljava/lang/String;");
                mv.visitInsn(Opcodes.ARETURN);

                mv.visitLabel(fetchNew);

                // Try to fetch new identity
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, SERVER_IDENTITY_CLASS, "fetchF2PServerIdentity", "()V", false);

                // Return whatever we have (may still be null)
                mv.visitFieldInsn(Opcodes.GETSTATIC, SERVER_IDENTITY_CLASS, "f2pIdentityToken", "Ljava/lang/String;");
                mv.visitInsn(Opcodes.ARETURN);

                mv.visitMaxs(4, 0);
                mv.visitEnd();

                // public static String getIdentityTokenForUrl(String baseUrl)
                // Fetches identity token from a specific URL (for backward compatibility)
                // This does NOT use the global cache - it always fetches fresh from the given
                // URL
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                                "getIdentityTokenForUrl", "(Ljava/lang/String;)Ljava/lang/String;", null, null);
                mv.visitCode();

                // if (baseUrl == null) baseUrl = F2P_SESSION_URL;
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                Label urlNotNull = new Label();
                mv.visitJumpInsn(Opcodes.IFNONNULL, urlNotNull);
                mv.visitFieldInsn(Opcodes.GETSTATIC, HELPER_CLASS, "F2P_URL", "Ljava/lang/String;");
                mv.visitVarInsn(Opcodes.ASTORE, 0);
                mv.visitLabel(urlNotNull);

                // return fetchIdentityFromUrl(baseUrl);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, SERVER_IDENTITY_CLASS, "fetchIdentityFromUrl",
                                "(Ljava/lang/String;)Ljava/lang/String;", false);
                mv.visitInsn(Opcodes.ARETURN);

                mv.visitMaxs(1, 1);
                mv.visitEnd();

                // private static String fetchIdentityFromUrl(String baseUrl)
                // Fetches server identity from specified URL
                mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                                "fetchIdentityFromUrl", "(Ljava/lang/String;)Ljava/lang/String;", null, null);
                mv.visitCode();
                generateFetchIdentityFromUrlBody(mv);
                mv.visitEnd();

                // private static void fetchF2PServerIdentity()
                // Fetches server identity from F2P auth server with fallback to local
                // self-signed token
                mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                                "fetchF2PServerIdentity", "()V", null, null);
                mv.visitCode();

                // Try block for HTTP request
                Label tryStart = new Label();
                Label tryEnd = new Label();
                Label catchBlock = new Label();
                mv.visitTryCatchBlock(tryStart, tryEnd, catchBlock, "java/lang/Exception");
                mv.visitLabel(tryStart);

                // Log start
                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn("[DualAuth] Attempting fetch server identity from: ");
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V",
                                false);
                mv.visitFieldInsn(Opcodes.GETSTATIC, HELPER_CLASS, "F2P_URL", "Ljava/lang/String;");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;",
                                false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V",
                                false);

                // HttpClient client =
                // HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/net/http/HttpClient", "newBuilder",
                                "()Ljava/net/http/HttpClient$Builder;", false);
                mv.visitLdcInsn(5L);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/time/Duration", "ofSeconds",
                                "(J)Ljava/time/Duration;", false);
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/net/http/HttpClient$Builder", "connectTimeout",
                                "(Ljava/time/Duration;)Ljava/net/http/HttpClient$Builder;", true);
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/net/http/HttpClient$Builder", "build",
                                "()Ljava/net/http/HttpClient;", true);
                mv.visitVarInsn(Opcodes.ASTORE, 0); // client

                // String serverUuid = getServerUuid(); // Get server's UUID for the request
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, SERVER_IDENTITY_CLASS, "getServerUuid",
                                "()Ljava/lang/String;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 1); // serverUuid

                // String jsonBody = "{\"uuid\":\"" + serverUuid + "\"}";
                mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn("{\"uuid\":\"");
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V",
                                false);
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitLdcInsn("\"}");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;",
                                false);
                mv.visitVarInsn(Opcodes.ASTORE, 2); // jsonBody

                // HttpRequest request = HttpRequest.newBuilder()
                // .uri(URI.create(F2P_SESSION_URL + "/game-session/new"))
                // .header("Content-Type", "application/json")
                // .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                // .build();
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/net/http/HttpRequest", "newBuilder",
                                "()Ljava/net/http/HttpRequest$Builder;", false);
                mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
                mv.visitFieldInsn(Opcodes.GETSTATIC, HELPER_CLASS, "F2P_URL", "Ljava/lang/String;");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitLdcInsn("/game-session/new");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;",
                                false);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/net/URI", "create",
                                "(Ljava/lang/String;)Ljava/net/URI;", false);
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/net/http/HttpRequest$Builder", "uri",
                                "(Ljava/net/URI;)Ljava/net/http/HttpRequest$Builder;", true);
                mv.visitLdcInsn("Content-Type");
                mv.visitLdcInsn("application/json");
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/net/http/HttpRequest$Builder", "header",
                                "(Ljava/lang/String;Ljava/lang/String;)Ljava/net/http/HttpRequest$Builder;", true);
                mv.visitVarInsn(Opcodes.ALOAD, 2); // jsonBody
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/net/http/HttpRequest$BodyPublishers", "ofString",
                                "(Ljava/lang/String;)Ljava/net/http/HttpRequest$BodyPublisher;", false);
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/net/http/HttpRequest$Builder", "POST",
                                "(Ljava/net/http/HttpRequest$BodyPublisher;)Ljava/net/http/HttpRequest$Builder;", true);
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/net/http/HttpRequest$Builder", "build",
                                "()Ljava/net/http/HttpRequest;", true);
                mv.visitVarInsn(Opcodes.ASTORE, 3); // request

                // HttpResponse<String> response = client.send(request,
                // HttpResponse.BodyHandlers.ofString());
                mv.visitVarInsn(Opcodes.ALOAD, 0); // client
                mv.visitVarInsn(Opcodes.ALOAD, 3); // request
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/net/http/HttpResponse$BodyHandlers", "ofString",
                                "()Ljava/net/http/HttpResponse$BodyHandler;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/net/http/HttpClient", "send",
                                "(Ljava/net/http/HttpRequest;Ljava/net/http/HttpResponse$BodyHandler;)Ljava/net/http/HttpResponse;",
                                false);
                mv.visitVarInsn(Opcodes.ASTORE, 4); // response

                // if (response.statusCode() == 200) {
                mv.visitVarInsn(Opcodes.ALOAD, 4);
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/net/http/HttpResponse", "statusCode", "()I", true);
                mv.visitIntInsn(Opcodes.SIPUSH, 200);
                Label notOk = new Label();
                mv.visitJumpInsn(Opcodes.IF_ICMPNE, notOk);

                // Parse response to extract identityToken and sessionToken
                // String body = (String) response.body();
                mv.visitVarInsn(Opcodes.ALOAD, 4);
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/net/http/HttpResponse", "body",
                                "()Ljava/lang/Object;", true);
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String");
                mv.visitVarInsn(Opcodes.ASTORE, 5); // body

                // Extract identityToken using simple parsing
                // int idx = body.indexOf("\"identityToken\":\"");
                mv.visitVarInsn(Opcodes.ALOAD, 5);
                mv.visitLdcInsn("\"identityToken\":\"");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "indexOf", "(Ljava/lang/String;)I",
                                false);
                mv.visitVarInsn(Opcodes.ISTORE, 6); // idx

                // if (idx >= 0) {
                mv.visitVarInsn(Opcodes.ILOAD, 6);
                Label noIdentityToken = new Label();
                mv.visitJumpInsn(Opcodes.IFLT, noIdentityToken);

                // int start = idx + 17; // length of "identityToken\":\"
                mv.visitVarInsn(Opcodes.ILOAD, 6);
                mv.visitIntInsn(Opcodes.BIPUSH, 17);
                mv.visitInsn(Opcodes.IADD);
                mv.visitVarInsn(Opcodes.ISTORE, 7); // start

                // int end = body.indexOf("\"", start);
                mv.visitVarInsn(Opcodes.ALOAD, 5);
                mv.visitIntInsn(Opcodes.BIPUSH, '"');
                mv.visitVarInsn(Opcodes.ILOAD, 7);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "indexOf", "(II)I", false);
                mv.visitVarInsn(Opcodes.ISTORE, 8); // end

                // if (end > start) {
                mv.visitVarInsn(Opcodes.ILOAD, 8);
                mv.visitVarInsn(Opcodes.ILOAD, 7);
                mv.visitJumpInsn(Opcodes.IF_ICMPLE, noIdentityToken);

                // f2pIdentityToken = body.substring(start, end);
                mv.visitVarInsn(Opcodes.ALOAD, 5);
                mv.visitVarInsn(Opcodes.ILOAD, 7);
                mv.visitVarInsn(Opcodes.ILOAD, 8);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring", "(II)Ljava/lang/String;",
                                false);
                mv.visitFieldInsn(Opcodes.PUTSTATIC, SERVER_IDENTITY_CLASS, "f2pIdentityToken", "Ljava/lang/String;");

                // f2pSessionToken = f2pIdentityToken; // Use same token for both
                mv.visitFieldInsn(Opcodes.GETSTATIC, SERVER_IDENTITY_CLASS, "f2pIdentityToken", "Ljava/lang/String;");
                mv.visitFieldInsn(Opcodes.PUTSTATIC, SERVER_IDENTITY_CLASS, "f2pSessionToken", "Ljava/lang/String;");

                // lastFetchTime = System.currentTimeMillis();
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "currentTimeMillis", "()J", false);
                mv.visitFieldInsn(Opcodes.PUTSTATIC, SERVER_IDENTITY_CLASS, "lastFetchTime", "J");

                // Log success
                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                mv.visitLdcInsn("[DualAuth] Successfully obtained F2P server identity");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V",
                                false);

                // } // end if identityToken found
                mv.visitLabel(noIdentityToken);

                // } // end if status 200
                mv.visitLabel(notOk);

                mv.visitLabel(tryEnd);
                Label afterCatch = new Label();
                mv.visitJumpInsn(Opcodes.GOTO, afterCatch);

                // CATCH BLOCK: Fallback to local self-signed token generation
                mv.visitLabel(catchBlock);
                mv.visitVarInsn(Opcodes.ASTORE, 9); // exception

                // Log fallback
                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;");
                mv.visitLdcInsn(
                                "[DualAuth] Connection failed. FALLBACK: Generating Local Self-Signed Identity Token (Omni-Auth)");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V",
                                false);

                // Call the local token generator
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, SERVER_IDENTITY_CLASS, "generateLocalToken", "()V", false);

                mv.visitLabel(afterCatch);
                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(5, 10);
                mv.visitEnd();

                // private static String getServerUuid()
                // Returns the server's UUID (from AuthConfig or generates one)
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                                "getServerUuid", "()Ljava/lang/String;", null, null);
                mv.visitCode();

                // Try to get from environment variable first
                mv.visitLdcInsn("HYTALE_SERVER_AUDIENCE");
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getenv",
                                "(Ljava/lang/String;)Ljava/lang/String;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 0);

                mv.visitVarInsn(Opcodes.ALOAD, 0);
                Label noEnv = new Label();
                mv.visitJumpInsn(Opcodes.IFNULL, noEnv);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitInsn(Opcodes.ARETURN);

                mv.visitLabel(noEnv);

                // Generate a random UUID if not set
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/UUID", "randomUUID", "()Ljava/util/UUID;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/UUID", "toString", "()Ljava/lang/String;", false);
                mv.visitInsn(Opcodes.ARETURN);

                mv.visitMaxs(1, 1);
                mv.visitEnd();

                // private static void generateLocalToken()
                // Generates self-signed Ed25519 token with embedded JWK for Omni-Auth
                // compatibility
                mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                                "generateLocalToken", "()V", null, null);
                mv.visitCode();

                // Try block
                Label tStart = new Label();
                Label tEnd = new Label();
                Label tCatch = new Label();
                mv.visitTryCatchBlock(tStart, tEnd, tCatch, "java/lang/Exception");
                mv.visitLabel(tStart);

                // 1. Generate Ed25519 Key Pair
                // JWK jwk = new
                // OctetKeyPairGenerator(Curve.Ed25519).keyID(UUID.randomUUID().toString()).generate();
                mv.visitTypeInsn(Opcodes.NEW, "com/nimbusds/jose/jwk/gen/OctetKeyPairGenerator");
                mv.visitInsn(Opcodes.DUP);
                mv.visitFieldInsn(Opcodes.GETSTATIC, "com/nimbusds/jose/jwk/Curve", "Ed25519",
                                "Lcom/nimbusds/jose/jwk/Curve;");
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/nimbusds/jose/jwk/gen/OctetKeyPairGenerator", "<init>",
                                "(Lcom/nimbusds/jose/jwk/Curve;)V", false);

                // .keyID(randomUUID)
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/UUID", "randomUUID", "()Ljava/util/UUID;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/UUID", "toString", "()Ljava/lang/String;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jose/jwk/gen/OctetKeyPairGenerator", "keyID",
                                "(Ljava/lang/String;)Lcom/nimbusds/jose/jwk/gen/JWKGenerator;", false);

                // .generate() -> JWK
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jose/jwk/gen/JWKGenerator", "generate",
                                "()Lcom/nimbusds/jose/jwk/JWK;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 0); // jwk (Generic JWK)

                // Cast to OctetKeyPair
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitTypeInsn(Opcodes.CHECKCAST, "com/nimbusds/jose/jwk/OctetKeyPair");
                mv.visitVarInsn(Opcodes.ASTORE, 1); // kp

                // 2. Prepare Signer
                // JWSSigner signer = new Ed25519Signer(kp);
                mv.visitTypeInsn(Opcodes.NEW, "com/nimbusds/jose/crypto/Ed25519Signer");
                mv.visitInsn(Opcodes.DUP);
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/nimbusds/jose/crypto/Ed25519Signer", "<init>",
                                "(Lcom/nimbusds/jose/jwk/OctetKeyPair;)V", false);
                mv.visitVarInsn(Opcodes.ASTORE, 2); // signer

                // 3. Prepare Header with EMBEDDED JWK (CRUCIAL for Omni-Auth)
                // JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.EdDSA)
                // .type(JOSEObjectType.JWT)
                // .jwk(kp.toPublicJWK()) <--- CRUCIAL
                // .build();
                mv.visitTypeInsn(Opcodes.NEW, "com/nimbusds/jose/JWSHeader$Builder");
                mv.visitInsn(Opcodes.DUP);
                mv.visitFieldInsn(Opcodes.GETSTATIC, "com/nimbusds/jose/JWSAlgorithm", "EdDSA",
                                "Lcom/nimbusds/jose/JWSAlgorithm;");
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/nimbusds/jose/JWSHeader$Builder", "<init>",
                                "(Lcom/nimbusds/jose/JWSAlgorithm;)V", false);

                // .type(JWT)
                mv.visitFieldInsn(Opcodes.GETSTATIC, "com/nimbusds/jose/JOSEObjectType", "JWT",
                                "Lcom/nimbusds/jose/JOSEObjectType;");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jose/JWSHeader$Builder", "type",
                                "(Lcom/nimbusds/jose/JOSEObjectType;)Lcom/nimbusds/jose/JWSHeader$Builder;", false);

                // .jwk(kp.toPublicJWK()) - THE KEY PART FOR OMNI-AUTH
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jose/jwk/OctetKeyPair", "toPublicJWK",
                                "()Lcom/nimbusds/jose/jwk/OctetKeyPair;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jose/JWSHeader$Builder", "jwk",
                                "(Lcom/nimbusds/jose/jwk/JWK;)Lcom/nimbusds/jose/JWSHeader$Builder;", false);

                // .build()
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jose/JWSHeader$Builder", "build",
                                "()Lcom/nimbusds/jose/JWSHeader;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 3); // header

                // 4. Get Server UUID
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, SERVER_IDENTITY_CLASS, "getServerUuid", "()Ljava/lang/String;",
                                false);
                mv.visitVarInsn(Opcodes.ASTORE, 4); // serverUuid

                // 5. Prepare Payload
                // JWTClaimsSet claims = new JWTClaimsSet.Builder()
                // .issuer(F2P_SESSION_URL)
                // .subject(serverUuid)
                // .audience("hytale:server")
                // .claim("scope", "hytale:server")
                // .issueTime(new Date())
                // .expirationTime(new Date(System.currentTimeMillis() + 36000000))
                // .build();

                mv.visitTypeInsn(Opcodes.NEW, "com/nimbusds/jwt/JWTClaimsSet$Builder");
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/nimbusds/jwt/JWTClaimsSet$Builder", "<init>", "()V",
                                false);

                mv.visitFieldInsn(Opcodes.GETSTATIC, SERVER_IDENTITY_CLASS, "F2P_SESSION_URL", "Ljava/lang/String;");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jwt/JWTClaimsSet$Builder", "issuer",
                                "(Ljava/lang/String;)Lcom/nimbusds/jwt/JWTClaimsSet$Builder;", false);

                mv.visitVarInsn(Opcodes.ALOAD, 4); // serverUuid
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jwt/JWTClaimsSet$Builder", "subject",
                                "(Ljava/lang/String;)Lcom/nimbusds/jwt/JWTClaimsSet$Builder;", false);

                // Audience & Scope
                mv.visitLdcInsn("hytale:server");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jwt/JWTClaimsSet$Builder", "audience",
                                "(Ljava/lang/String;)Lcom/nimbusds/jwt/JWTClaimsSet$Builder;", false);

                mv.visitLdcInsn("scope");
                mv.visitLdcInsn("hytale:server");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jwt/JWTClaimsSet$Builder", "claim",
                                "(Ljava/lang/String;Ljava/lang/Object;)Lcom/nimbusds/jwt/JWTClaimsSet$Builder;", false);

                // Dates
                mv.visitTypeInsn(Opcodes.NEW, "java/util/Date");
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/Date", "<init>", "()V", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jwt/JWTClaimsSet$Builder", "issueTime",
                                "(Ljava/util/Date;)Lcom/nimbusds/jwt/JWTClaimsSet$Builder;", false);

                mv.visitTypeInsn(Opcodes.NEW, "java/util/Date");
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "currentTimeMillis", "()J", false);
                mv.visitLdcInsn(36000000L); // 10 hours
                mv.visitInsn(Opcodes.LADD);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/Date", "<init>", "(J)V", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jwt/JWTClaimsSet$Builder", "expirationTime",
                                "(Ljava/util/Date;)Lcom/nimbusds/jwt/JWTClaimsSet$Builder;", false);

                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jwt/JWTClaimsSet$Builder", "build",
                                "()Lcom/nimbusds/jwt/JWTClaimsSet;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 5); // claims

                // 6. Sign Object
                // SignedJWT signedJWT = new SignedJWT(header, claims);
                mv.visitTypeInsn(Opcodes.NEW, "com/nimbusds/jwt/SignedJWT");
                mv.visitInsn(Opcodes.DUP);
                mv.visitVarInsn(Opcodes.ALOAD, 3); // header
                mv.visitVarInsn(Opcodes.ALOAD, 5); // claims
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/nimbusds/jwt/SignedJWT", "<init>",
                                "(Lcom/nimbusds/jose/JWSHeader;Lcom/nimbusds/jwt/JWTClaimsSet;)V", false);
                mv.visitVarInsn(Opcodes.ASTORE, 6); // signedJWT

                // signedJWT.sign(signer);
                mv.visitVarInsn(Opcodes.ALOAD, 6);
                mv.visitVarInsn(Opcodes.ALOAD, 2); // signer
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jwt/SignedJWT", "sign",
                                "(Lcom/nimbusds/jose/JWSSigner;)V", false);

                // String token = signedJWT.serialize();
                mv.visitVarInsn(Opcodes.ALOAD, 6);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jwt/SignedJWT", "serialize",
                                "()Ljava/lang/String;",
                                false);
                mv.visitVarInsn(Opcodes.ASTORE, 7);

                // Store globally
                mv.visitVarInsn(Opcodes.ALOAD, 7);
                mv.visitFieldInsn(Opcodes.PUTSTATIC, SERVER_IDENTITY_CLASS, "f2pIdentityToken", "Ljava/lang/String;");
                mv.visitVarInsn(Opcodes.ALOAD, 7);
                mv.visitFieldInsn(Opcodes.PUTSTATIC, SERVER_IDENTITY_CLASS, "f2pSessionToken", "Ljava/lang/String;");

                // Update timestamp
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "currentTimeMillis", "()J", false);
                mv.visitFieldInsn(Opcodes.PUTSTATIC, SERVER_IDENTITY_CLASS, "lastFetchTime", "J");

                // Log success
                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                mv.visitLdcInsn("[DualAuth] Successfully generated self-signed identity token with embedded JWK");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V",
                                false);

                mv.visitLabel(tEnd);
                Label afterLocalCatch = new Label();
                mv.visitJumpInsn(Opcodes.GOTO, afterLocalCatch);

                mv.visitLabel(tCatch);
                mv.visitVarInsn(Opcodes.ASTORE, 8);
                // Log exception
                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;");
                mv.visitLdcInsn("[DualAuth] Failed to generate local self-signed token: ");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "print", "(Ljava/lang/String;)V",
                                false);
                mv.visitVarInsn(Opcodes.ALOAD, 8); // ex
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Exception", "printStackTrace", "()V", false);

                mv.visitLabel(afterLocalCatch);
                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(5, 9);
                mv.visitEnd();

                mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                                "generateLocalToken", "(Ljava/lang/String;)Ljava/lang/String;", null, null);
                mv.visitCode();

                Label genStart = new Label();
                Label genEnd = new Label();
                Label genCatch = new Label();
                mv.visitTryCatchBlock(genStart, genEnd, genCatch, "java/lang/Exception");
                mv.visitLabel(genStart);

                mv.visitTypeInsn(Opcodes.NEW, "com/nimbusds/jose/jwk/gen/OctetKeyPairGenerator");
                mv.visitInsn(Opcodes.DUP);
                mv.visitFieldInsn(Opcodes.GETSTATIC, "com/nimbusds/jose/jwk/Curve", "Ed25519",
                                "Lcom/nimbusds/jose/jwk/Curve;");
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/nimbusds/jose/jwk/gen/OctetKeyPairGenerator", "<init>",
                                "(Lcom/nimbusds/jose/jwk/Curve;)V", false);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/UUID", "randomUUID", "()Ljava/util/UUID;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/UUID", "toString", "()Ljava/lang/String;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jose/jwk/gen/OctetKeyPairGenerator", "keyID",
                                "(Ljava/lang/String;)Lcom/nimbusds/jose/jwk/gen/JWKGenerator;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jose/jwk/gen/JWKGenerator", "generate",
                                "()Lcom/nimbusds/jose/jwk/JWK;", false);
                mv.visitTypeInsn(Opcodes.CHECKCAST, "com/nimbusds/jose/jwk/OctetKeyPair");
                mv.visitVarInsn(Opcodes.ASTORE, 1);

                mv.visitTypeInsn(Opcodes.NEW, "com/nimbusds/jose/crypto/Ed25519Signer");
                mv.visitInsn(Opcodes.DUP);
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/nimbusds/jose/crypto/Ed25519Signer", "<init>",
                                "(Lcom/nimbusds/jose/jwk/OctetKeyPair;)V", false);
                mv.visitVarInsn(Opcodes.ASTORE, 2);

                mv.visitTypeInsn(Opcodes.NEW, "com/nimbusds/jose/JWSHeader$Builder");
                mv.visitInsn(Opcodes.DUP);
                mv.visitFieldInsn(Opcodes.GETSTATIC, "com/nimbusds/jose/JWSAlgorithm", "EdDSA",
                                "Lcom/nimbusds/jose/JWSAlgorithm;");
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/nimbusds/jose/JWSHeader$Builder", "<init>",
                                "(Lcom/nimbusds/jose/JWSAlgorithm;)V", false);
                mv.visitFieldInsn(Opcodes.GETSTATIC, "com/nimbusds/jose/JOSEObjectType", "JWT",
                                "Lcom/nimbusds/jose/JOSEObjectType;");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jose/JWSHeader$Builder", "type",
                                "(Lcom/nimbusds/jose/JOSEObjectType;)Lcom/nimbusds/jose/JWSHeader$Builder;", false);
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jose/jwk/OctetKeyPair", "toPublicJWK",
                                "()Lcom/nimbusds/jose/jwk/OctetKeyPair;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jose/JWSHeader$Builder", "jwk",
                                "(Lcom/nimbusds/jose/jwk/JWK;)Lcom/nimbusds/jose/JWSHeader$Builder;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jose/JWSHeader$Builder", "build",
                                "()Lcom/nimbusds/jose/JWSHeader;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 3);

                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/UUID", "randomUUID", "()Ljava/util/UUID;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/UUID", "toString", "()Ljava/lang/String;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 4);

                mv.visitTypeInsn(Opcodes.NEW, "com/nimbusds/jwt/JWTClaimsSet$Builder");
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/nimbusds/jwt/JWTClaimsSet$Builder", "<init>", "()V",
                                false);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jwt/JWTClaimsSet$Builder", "issuer",
                                "(Ljava/lang/String;)Lcom/nimbusds/jwt/JWTClaimsSet$Builder;", false);
                mv.visitVarInsn(Opcodes.ALOAD, 4);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jwt/JWTClaimsSet$Builder", "subject",
                                "(Ljava/lang/String;)Lcom/nimbusds/jwt/JWTClaimsSet$Builder;", false);
                mv.visitLdcInsn("hytale:server");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jwt/JWTClaimsSet$Builder", "audience",
                                "(Ljava/lang/String;)Lcom/nimbusds/jwt/JWTClaimsSet$Builder;", false);
                mv.visitLdcInsn("scope");
                mv.visitLdcInsn("hytale:server");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jwt/JWTClaimsSet$Builder", "claim",
                                "(Ljava/lang/String;Ljava/lang/Object;)Lcom/nimbusds/jwt/JWTClaimsSet$Builder;", false);
                mv.visitTypeInsn(Opcodes.NEW, "java/util/Date");
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/Date", "<init>", "()V", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jwt/JWTClaimsSet$Builder", "issueTime",
                                "(Ljava/util/Date;)Lcom/nimbusds/jwt/JWTClaimsSet$Builder;", false);
                mv.visitTypeInsn(Opcodes.NEW, "java/util/Date");
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "currentTimeMillis", "()J", false);
                mv.visitLdcInsn(36000000L);
                mv.visitInsn(Opcodes.LADD);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/Date", "<init>", "(J)V", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jwt/JWTClaimsSet$Builder", "expirationTime",
                                "(Ljava/util/Date;)Lcom/nimbusds/jwt/JWTClaimsSet$Builder;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jwt/JWTClaimsSet$Builder", "build",
                                "()Lcom/nimbusds/jwt/JWTClaimsSet;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 5);

                mv.visitTypeInsn(Opcodes.NEW, "com/nimbusds/jwt/SignedJWT");
                mv.visitInsn(Opcodes.DUP);
                mv.visitVarInsn(Opcodes.ALOAD, 3);
                mv.visitVarInsn(Opcodes.ALOAD, 5);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/nimbusds/jwt/SignedJWT", "<init>",
                                "(Lcom/nimbusds/jose/JWSHeader;Lcom/nimbusds/jwt/JWTClaimsSet;)V", false);
                mv.visitVarInsn(Opcodes.ASTORE, 6);

                mv.visitVarInsn(Opcodes.ALOAD, 6);
                mv.visitVarInsn(Opcodes.ALOAD, 2);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jwt/SignedJWT", "sign",
                                "(Lcom/nimbusds/jose/JWSSigner;)V", false);

                mv.visitVarInsn(Opcodes.ALOAD, 6);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jwt/SignedJWT", "serialize",
                                "()Ljava/lang/String;",
                                false);
                mv.visitLabel(genEnd);
                mv.visitInsn(Opcodes.ARETURN);

                mv.visitLabel(genCatch);
                mv.visitInsn(Opcodes.POP);
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitInsn(Opcodes.ARETURN);

                mv.visitMaxs(5, 7);
                mv.visitEnd();

                mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                                "createTokenFromClientKey", "(Ljava/lang/String;)Ljava/lang/String;", null, null);
                mv.visitCode();

                Label tryStartHijack = new Label();
                Label tryEndHijack = new Label();
                Label catchHijack = new Label();
                Label returnNull = new Label();
                mv.visitTryCatchBlock(tryStartHijack, tryEndHijack, catchHijack, "java/lang/Exception");
                mv.visitLabel(tryStartHijack);

                // if (token == null) return null;
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitJumpInsn(Opcodes.IFNULL, returnNull);

                // int dot1 = token.indexOf('.');
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitIntInsn(Opcodes.BIPUSH, '.');
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "indexOf", "(I)I", false);
                mv.visitVarInsn(Opcodes.ISTORE, 1);

                // int dot2 = token.lastIndexOf('.');
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitIntInsn(Opcodes.BIPUSH, '.');
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "lastIndexOf", "(I)I", false);
                mv.visitVarInsn(Opcodes.ISTORE, 2);

                // if (dot1 < 0 || dot2 <= dot1) return null;
                mv.visitVarInsn(Opcodes.ILOAD, 1);
                mv.visitJumpInsn(Opcodes.IFLT, returnNull);
                mv.visitVarInsn(Opcodes.ILOAD, 2);
                mv.visitVarInsn(Opcodes.ILOAD, 1);
                mv.visitJumpInsn(Opcodes.IF_ICMPLE, returnNull);

                // String headerStr = token.substring(0, dot1);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitInsn(Opcodes.ICONST_0);
                mv.visitVarInsn(Opcodes.ILOAD, 1);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring", "(II)Ljava/lang/String;",
                                false);
                mv.visitVarInsn(Opcodes.ASTORE, 3);

                // String headerJson = new Base64URL(headerStr).decodeToString();
                mv.visitTypeInsn(Opcodes.NEW, "com/nimbusds/jose/util/Base64URL");
                mv.visitInsn(Opcodes.DUP);
                mv.visitVarInsn(Opcodes.ALOAD, 3);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/nimbusds/jose/util/Base64URL", "<init>",
                                "(Ljava/lang/String;)V",
                                false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jose/util/Base64URL", "decodeToString",
                                "()Ljava/lang/String;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 4);

                // Map headerMap = JSONObjectUtils.parse(headerJson);
                mv.visitVarInsn(Opcodes.ALOAD, 4);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/nimbusds/jose/util/JSONObjectUtils", "parse",
                                "(Ljava/lang/String;)Ljava/util/Map;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 5);

                // String kid = (String) headerMap.get("kid");
                mv.visitVarInsn(Opcodes.ALOAD, 5);
                mv.visitLdcInsn("kid");
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map", "get",
                                "(Ljava/lang/Object;)Ljava/lang/Object;",
                                true);
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String");
                mv.visitVarInsn(Opcodes.ASTORE, 6);

                // JWK jwk = JWK.parse((Map)headerMap.get("jwk"));
                mv.visitVarInsn(Opcodes.ALOAD, 5);
                mv.visitLdcInsn("jwk");
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map", "get",
                                "(Ljava/lang/Object;)Ljava/lang/Object;",
                                true);
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/util/Map");
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/nimbusds/jose/jwk/JWK", "parse",
                                "(Ljava/util/Map;)Lcom/nimbusds/jose/jwk/JWK;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 7);

                mv.visitVarInsn(Opcodes.ALOAD, 7);
                mv.visitTypeInsn(Opcodes.INSTANCEOF, "com/nimbusds/jose/jwk/OctetKeyPair");
                mv.visitJumpInsn(Opcodes.IFEQ, returnNull);

                mv.visitVarInsn(Opcodes.ALOAD, 7);
                mv.visitTypeInsn(Opcodes.CHECKCAST, "com/nimbusds/jose/jwk/OctetKeyPair");
                mv.visitVarInsn(Opcodes.ASTORE, 8);

                mv.visitVarInsn(Opcodes.ALOAD, 8);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jose/jwk/OctetKeyPair", "isPrivate", "()Z",
                                false);
                mv.visitJumpInsn(Opcodes.IFEQ, returnNull);

                // Ed25519Signer signer = new Ed25519Signer(keyPair);
                mv.visitTypeInsn(Opcodes.NEW, "com/nimbusds/jose/crypto/Ed25519Signer");
                mv.visitInsn(Opcodes.DUP);
                mv.visitVarInsn(Opcodes.ALOAD, 8);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/nimbusds/jose/crypto/Ed25519Signer", "<init>",
                                "(Lcom/nimbusds/jose/jwk/OctetKeyPair;)V", false);
                mv.visitVarInsn(Opcodes.ASTORE, 9);

                // String payloadStr = token.substring(dot1 + 1, dot2);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitVarInsn(Opcodes.ILOAD, 1);
                mv.visitInsn(Opcodes.ICONST_1);
                mv.visitInsn(Opcodes.IADD);
                mv.visitVarInsn(Opcodes.ILOAD, 2);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring", "(II)Ljava/lang/String;",
                                false);
                mv.visitVarInsn(Opcodes.ASTORE, 10);

                // String payloadJson = new Base64URL(payloadStr).decodeToString();
                mv.visitTypeInsn(Opcodes.NEW, "com/nimbusds/jose/util/Base64URL");
                mv.visitInsn(Opcodes.DUP);
                mv.visitVarInsn(Opcodes.ALOAD, 10);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/nimbusds/jose/util/Base64URL", "<init>",
                                "(Ljava/lang/String;)V",
                                false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jose/util/Base64URL", "decodeToString",
                                "()Ljava/lang/String;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 11);

                // JWTClaimsSet clientClaims = JWTClaimsSet.parse(payloadJson);
                mv.visitVarInsn(Opcodes.ALOAD, 11);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/nimbusds/jwt/JWTClaimsSet", "parse",
                                "(Ljava/lang/String;)Lcom/nimbusds/jwt/JWTClaimsSet;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 12);

                // String issuer = clientClaims.getIssuer();
                mv.visitVarInsn(Opcodes.ALOAD, 12);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jwt/JWTClaimsSet", "getIssuer",
                                "()Ljava/lang/String;",
                                false);
                mv.visitVarInsn(Opcodes.ASTORE, 13);

                mv.visitVarInsn(Opcodes.ALOAD, 13);
                mv.visitJumpInsn(Opcodes.IFNULL, returnNull);

                // String serverUuid = getServerUuid();
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, SERVER_IDENTITY_CLASS, "getServerUuid", "()Ljava/lang/String;",
                                false);
                mv.visitVarInsn(Opcodes.ASTORE, 14);

                // Build claims
                mv.visitTypeInsn(Opcodes.NEW, "com/nimbusds/jwt/JWTClaimsSet$Builder");
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/nimbusds/jwt/JWTClaimsSet$Builder", "<init>", "()V",
                                false);
                mv.visitVarInsn(Opcodes.ALOAD, 13);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jwt/JWTClaimsSet$Builder", "issuer",
                                "(Ljava/lang/String;)Lcom/nimbusds/jwt/JWTClaimsSet$Builder;", false);
                mv.visitVarInsn(Opcodes.ALOAD, 14);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jwt/JWTClaimsSet$Builder", "subject",
                                "(Ljava/lang/String;)Lcom/nimbusds/jwt/JWTClaimsSet$Builder;", false);
                mv.visitVarInsn(Opcodes.ALOAD, 12); // clientClaims
                mv.visitLdcInsn("name");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jwt/JWTClaimsSet", "getStringClaim",
                                "(Ljava/lang/String;)Ljava/lang/String;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 16); // Reutilizar var para name temporal
                mv.visitVarInsn(Opcodes.ALOAD, 16);
                Label skipName = new Label();
                mv.visitJumpInsn(Opcodes.IFNULL, skipName);
                mv.visitLdcInsn("name");
                mv.visitVarInsn(Opcodes.ALOAD, 16);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jwt/JWTClaimsSet$Builder", "claim",
                                "(Ljava/lang/String;Ljava/lang/Object;)Lcom/nimbusds/jwt/JWTClaimsSet$Builder;", false);
                mv.visitLabel(skipName);

                // Copiar 'username' del cliente si existe
                mv.visitVarInsn(Opcodes.ALOAD, 12); // clientClaims
                mv.visitLdcInsn("username");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jwt/JWTClaimsSet", "getStringClaim",
                                "(Ljava/lang/String;)Ljava/lang/String;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 16);
                mv.visitVarInsn(Opcodes.ALOAD, 16);
                Label skipUser = new Label();
                mv.visitJumpInsn(Opcodes.IFNULL, skipUser);
                mv.visitLdcInsn("username");
                mv.visitVarInsn(Opcodes.ALOAD, 16);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jwt/JWTClaimsSet$Builder", "claim",
                                "(Ljava/lang/String;Ljava/lang/Object;)Lcom/nimbusds/jwt/JWTClaimsSet$Builder;", false);
                mv.visitLabel(skipUser);

                mv.visitLdcInsn("hytale:server");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jwt/JWTClaimsSet$Builder", "audience",
                                "(Ljava/lang/String;)Lcom/nimbusds/jwt/JWTClaimsSet$Builder;", false);
                mv.visitLdcInsn("scope");
                mv.visitLdcInsn("hytale:server");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jwt/JWTClaimsSet$Builder", "claim",
                                "(Ljava/lang/String;Ljava/lang/Object;)Lcom/nimbusds/jwt/JWTClaimsSet$Builder;", false);
                mv.visitTypeInsn(Opcodes.NEW, "java/util/Date");
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/Date", "<init>", "()V", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jwt/JWTClaimsSet$Builder", "issueTime",
                                "(Ljava/util/Date;)Lcom/nimbusds/jwt/JWTClaimsSet$Builder;", false);
                mv.visitTypeInsn(Opcodes.NEW, "java/util/Date");
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "currentTimeMillis", "()J", false);
                mv.visitLdcInsn(36000000L);
                mv.visitInsn(Opcodes.LADD);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/Date", "<init>", "(J)V", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jwt/JWTClaimsSet$Builder", "expirationTime",
                                "(Ljava/util/Date;)Lcom/nimbusds/jwt/JWTClaimsSet$Builder;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jwt/JWTClaimsSet$Builder", "build",
                                "()Lcom/nimbusds/jwt/JWTClaimsSet;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 15);

                // Build header (preserve kid)
                mv.visitTypeInsn(Opcodes.NEW, "com/nimbusds/jose/JWSHeader$Builder");
                mv.visitInsn(Opcodes.DUP);
                mv.visitFieldInsn(Opcodes.GETSTATIC, "com/nimbusds/jose/JWSAlgorithm", "EdDSA",
                                "Lcom/nimbusds/jose/JWSAlgorithm;");
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/nimbusds/jose/JWSHeader$Builder", "<init>",
                                "(Lcom/nimbusds/jose/JWSAlgorithm;)V", false);
                mv.visitVarInsn(Opcodes.ASTORE, 16);

                mv.visitVarInsn(Opcodes.ALOAD, 16);
                mv.visitFieldInsn(Opcodes.GETSTATIC, "com/nimbusds/jose/JOSEObjectType", "JWT",
                                "Lcom/nimbusds/jose/JOSEObjectType;");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jose/JWSHeader$Builder", "type",
                                "(Lcom/nimbusds/jose/JOSEObjectType;)Lcom/nimbusds/jose/JWSHeader$Builder;", false);
                mv.visitInsn(Opcodes.POP);

                mv.visitVarInsn(Opcodes.ALOAD, 6);
                Label noKid = new Label();
                mv.visitJumpInsn(Opcodes.IFNULL, noKid);
                mv.visitVarInsn(Opcodes.ALOAD, 16);
                mv.visitVarInsn(Opcodes.ALOAD, 6);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jose/JWSHeader$Builder", "keyID",
                                "(Ljava/lang/String;)Lcom/nimbusds/jose/JWSHeader$Builder;", false);
                mv.visitInsn(Opcodes.POP);
                mv.visitLabel(noKid);

                mv.visitVarInsn(Opcodes.ALOAD, 16);
                mv.visitVarInsn(Opcodes.ALOAD, 8);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jose/jwk/OctetKeyPair", "toPublicJWK",
                                "()Lcom/nimbusds/jose/jwk/OctetKeyPair;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jose/JWSHeader$Builder", "jwk",
                                "(Lcom/nimbusds/jose/jwk/JWK;)Lcom/nimbusds/jose/JWSHeader$Builder;", false);
                mv.visitInsn(Opcodes.POP);

                mv.visitVarInsn(Opcodes.ALOAD, 16);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jose/JWSHeader$Builder", "build",
                                "()Lcom/nimbusds/jose/JWSHeader;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 17);

                // Sign JWT
                mv.visitTypeInsn(Opcodes.NEW, "com/nimbusds/jwt/SignedJWT");
                mv.visitInsn(Opcodes.DUP);
                mv.visitVarInsn(Opcodes.ALOAD, 17);
                mv.visitVarInsn(Opcodes.ALOAD, 15);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/nimbusds/jwt/SignedJWT", "<init>",
                                "(Lcom/nimbusds/jose/JWSHeader;Lcom/nimbusds/jwt/JWTClaimsSet;)V", false);
                mv.visitVarInsn(Opcodes.ASTORE, 18);

                mv.visitVarInsn(Opcodes.ALOAD, 18);
                mv.visitVarInsn(Opcodes.ALOAD, 9);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jwt/SignedJWT", "sign",
                                "(Lcom/nimbusds/jose/JWSSigner;)V", false);

                mv.visitVarInsn(Opcodes.ALOAD, 18);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jwt/SignedJWT", "serialize",
                                "()Ljava/lang/String;",
                                false);
                mv.visitLabel(tryEndHijack);
                mv.visitInsn(Opcodes.ARETURN);

                mv.visitLabel(catchHijack);
                mv.visitInsn(Opcodes.POP);
                mv.visitLabel(returnNull);

                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitInsn(Opcodes.ARETURN);

                mv.visitMaxs(6, 19);
                mv.visitEnd();

                cw.visitEnd();
                return cw.toByteArray();
        }

        /**
         * Generate the body of fetchIdentityFromUrl(String baseUrl) method.
         * This fetches a server identity token from the given URL.
         */
        private static void generateFetchIdentityFromUrlBody(MethodVisitor mv) {
                // baseUrl is in local var 0

                // Log start
                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn("[DualAuth] Fetching identity from: ");
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V",
                                false);
                mv.visitVarInsn(Opcodes.ALOAD, 0); // baseUrl
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;",
                                false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V",
                                false);

                // try {
                Label tryStart = new Label();
                Label tryEnd = new Label();
                Label catchHandler = new Label();
                mv.visitTryCatchBlock(tryStart, tryEnd, catchHandler, "java/lang/Exception");
                mv.visitLabel(tryStart);

                // HttpClient client =
                // HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/net/http/HttpClient", "newBuilder",
                                "()Ljava/net/http/HttpClient$Builder;", false);
                mv.visitLdcInsn(5L);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/time/Duration", "ofSeconds",
                                "(J)Ljava/time/Duration;", false);
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/net/http/HttpClient$Builder", "connectTimeout",
                                "(Ljava/time/Duration;)Ljava/net/http/HttpClient$Builder;", true);
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/net/http/HttpClient$Builder", "build",
                                "()Ljava/net/http/HttpClient;", true);
                mv.visitVarInsn(Opcodes.ASTORE, 1); // client

                // String serverUuid = UUID.randomUUID().toString();
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/UUID", "randomUUID", "()Ljava/util/UUID;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/UUID", "toString", "()Ljava/lang/String;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 2); // serverUuid

                // String jsonBody = "{\"uuid\":\"" + serverUuid + "\"}";
                mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn("{\"uuid\":\"");
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V",
                                false);
                mv.visitVarInsn(Opcodes.ALOAD, 2);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitLdcInsn("\"}");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;",
                                false);
                mv.visitVarInsn(Opcodes.ASTORE, 3); // jsonBody

                // String url = baseUrl + "/game-session/new";
                mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
                mv.visitVarInsn(Opcodes.ALOAD, 0); // baseUrl
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitLdcInsn("/game-session/new");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;",
                                false);
                mv.visitVarInsn(Opcodes.ASTORE, 4); // url

                // HttpRequest request = HttpRequest.newBuilder()
                // .uri(URI.create(url))
                // .header("Content-Type", "application/json")
                // .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                // .build();
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/net/http/HttpRequest", "newBuilder",
                                "()Ljava/net/http/HttpRequest$Builder;", false);
                mv.visitVarInsn(Opcodes.ALOAD, 4); // url
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/net/URI", "create",
                                "(Ljava/lang/String;)Ljava/net/URI;", false);
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/net/http/HttpRequest$Builder", "uri",
                                "(Ljava/net/URI;)Ljava/net/http/HttpRequest$Builder;", true);
                mv.visitLdcInsn("Content-Type");
                mv.visitLdcInsn("application/json");
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/net/http/HttpRequest$Builder", "header",
                                "(Ljava/lang/String;Ljava/lang/String;)Ljava/net/http/HttpRequest$Builder;", true);
                mv.visitVarInsn(Opcodes.ALOAD, 3); // jsonBody
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/net/http/HttpRequest$BodyPublishers", "ofString",
                                "(Ljava/lang/String;)Ljava/net/http/HttpRequest$BodyPublisher;", false);
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/net/http/HttpRequest$Builder", "POST",
                                "(Ljava/net/http/HttpRequest$BodyPublisher;)Ljava/net/http/HttpRequest$Builder;", true);
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/net/http/HttpRequest$Builder", "build",
                                "()Ljava/net/http/HttpRequest;", true);
                mv.visitVarInsn(Opcodes.ASTORE, 5); // request

                // HttpResponse<String> response = client.send(request,
                // HttpResponse.BodyHandlers.ofString());
                mv.visitVarInsn(Opcodes.ALOAD, 1); // client
                mv.visitVarInsn(Opcodes.ALOAD, 5); // request
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/net/http/HttpResponse$BodyHandlers", "ofString",
                                "()Ljava/net/http/HttpResponse$BodyHandler;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/net/http/HttpClient", "send",
                                "(Ljava/net/http/HttpRequest;Ljava/net/http/HttpResponse$BodyHandler;)Ljava/net/http/HttpResponse;",
                                false);
                mv.visitVarInsn(Opcodes.ASTORE, 6); // response

                // if (response.statusCode() != 200) return null;
                mv.visitVarInsn(Opcodes.ALOAD, 6);
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/net/http/HttpResponse", "statusCode", "()I", true);
                mv.visitIntInsn(Opcodes.SIPUSH, 200);
                Label statusOk = new Label();
                mv.visitJumpInsn(Opcodes.IF_ICMPEQ, statusOk);
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitInsn(Opcodes.ARETURN);

                mv.visitLabel(statusOk);

                // String body = (String) response.body();
                mv.visitVarInsn(Opcodes.ALOAD, 6);
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/net/http/HttpResponse", "body",
                                "()Ljava/lang/Object;", true);
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String");
                mv.visitVarInsn(Opcodes.ASTORE, 7); // body

                // Extract identityToken from JSON response
                // int idx = body.indexOf("\"identityToken\":\"");
                mv.visitVarInsn(Opcodes.ALOAD, 7);
                mv.visitLdcInsn("\"identityToken\":\"");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "indexOf", "(Ljava/lang/String;)I",
                                false);
                mv.visitVarInsn(Opcodes.ISTORE, 8); // idx

                // if (idx < 0) return null;
                mv.visitVarInsn(Opcodes.ILOAD, 8);
                Label idxOk = new Label();
                mv.visitJumpInsn(Opcodes.IFGE, idxOk);
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitInsn(Opcodes.ARETURN);

                mv.visitLabel(idxOk);

                // int start = idx + 17; // length of "\"identityToken\":\""
                mv.visitVarInsn(Opcodes.ILOAD, 8);
                mv.visitIntInsn(Opcodes.BIPUSH, 17);
                mv.visitInsn(Opcodes.IADD);
                mv.visitVarInsn(Opcodes.ISTORE, 9); // start

                // int end = body.indexOf("\"", start);
                mv.visitVarInsn(Opcodes.ALOAD, 7);
                mv.visitIntInsn(Opcodes.BIPUSH, '"');
                mv.visitVarInsn(Opcodes.ILOAD, 9);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "indexOf", "(II)I", false);
                mv.visitVarInsn(Opcodes.ISTORE, 10); // end

                // if (end < 0) return null;
                mv.visitVarInsn(Opcodes.ILOAD, 10);
                Label endOk = new Label();
                mv.visitJumpInsn(Opcodes.IFGE, endOk);
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitInsn(Opcodes.ARETURN);

                mv.visitLabel(endOk);

                // String identityToken = body.substring(start, end);
                mv.visitVarInsn(Opcodes.ALOAD, 7);
                mv.visitVarInsn(Opcodes.ILOAD, 9);
                mv.visitVarInsn(Opcodes.ILOAD, 10);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring", "(II)Ljava/lang/String;",
                                false);
                mv.visitVarInsn(Opcodes.ASTORE, 11); // identityToken

                // Log success
                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                mv.visitLdcInsn("[DualAuth] Successfully obtained identity from URL");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V",
                                false);

                mv.visitLabel(tryEnd);
                // return identityToken;
                mv.visitVarInsn(Opcodes.ALOAD, 11);
                mv.visitInsn(Opcodes.ARETURN);

                // } catch (Exception e) {
                mv.visitLabel(catchHandler);

                mv.visitVarInsn(Opcodes.ASTORE, 1); // e

                // Log error
                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn("[DualAuth] Error fetching identity: ");
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V",
                                false);
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Exception", "getMessage", "()Ljava/lang/String;",
                                false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;",
                                false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V",
                                false);

                // return null;
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitInsn(Opcodes.ARETURN);

                mv.visitMaxs(4, 12);
        }

        /**
         * Generate DualServerTokenManager class - Stores both official and F2P server
         * tokens
         *
         * This class manages dual token sets:
         * - Official tokens: obtained via /auth login (from hytale.com)
         * - F2P tokens: auto-fetched from sanasol.ws on startup
         *
         * MODIFIED: Changed from background thread to synchronous fetch during static
         * init
         * to ensure tokens are available immediately at server startup.
         */
        private static byte[] generateDualServerTokenManager() {
                ClassWriter cw = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
                MethodVisitor mv;

                cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                                TOKEN_MANAGER_CLASS, null, "java/lang/Object", null);

                // Static fields for official tokens (from /auth login device flow)
                cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE,
                                "officialSessionToken", "Ljava/lang/String;", null, null).visitEnd();
                cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE,
                                "officialIdentityToken", "Ljava/lang/String;", null, null).visitEnd();

                // Static fields for F2P tokens (auto-fetched from sanasol.ws)
                cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE,
                                "f2pSessionToken", "Ljava/lang/String;", null, null).visitEnd();
                cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE,
                                "f2pIdentityToken", "Ljava/lang/String;", null, null).visitEnd();

                cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE,
                                "f2pIssuer", "Ljava/lang/String;", null, null).visitEnd();

                // Flag to track if F2P tokens have been fetched
                cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE,
                                "f2pTokensFetched", "Z", null, null).visitEnd();

                // Constants for URLs
                cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                                "F2P_AUTH_URL", "Ljava/lang/String;", null, null).visitEnd();
                cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                                "OFFICIAL_AUTH_URL", "Ljava/lang/String;", null, null).visitEnd();

                // Dynamic cache for unknown issuers: Map<String, String> (Issuer -> SessionToken)
                cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "issuerTokenCache",
                                "Ljava/util/concurrent/ConcurrentHashMap;", null, null).visitEnd();

                // Dynamic cache for identity tokens: Map<String, String> (Issuer -> IdentityToken)
                cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "issuerIdentityCache",
                                "Ljava/util/concurrent/ConcurrentHashMap;", null, null).visitEnd();

                // --- STATIC INITIALIZER (<clinit>) ---
                mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
                mv.visitCode();

                // 1. Initialize Constants
                mv.visitFieldInsn(Opcodes.GETSTATIC, HELPER_CLASS, "F2P_URL", "Ljava/lang/String;");
                mv.visitFieldInsn(Opcodes.PUTSTATIC, TOKEN_MANAGER_CLASS, "F2P_AUTH_URL", "Ljava/lang/String;");
                mv.visitFieldInsn(Opcodes.GETSTATIC, HELPER_CLASS, "OFFICIAL_URL", "Ljava/lang/String;");
                mv.visitFieldInsn(Opcodes.PUTSTATIC, TOKEN_MANAGER_CLASS, "OFFICIAL_AUTH_URL", "Ljava/lang/String;");

                // 2. Initialize Cache
                mv.visitTypeInsn(Opcodes.NEW, "java/util/concurrent/ConcurrentHashMap");
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/concurrent/ConcurrentHashMap", "<init>", "()V",
                                false);
                mv.visitFieldInsn(Opcodes.PUTSTATIC, TOKEN_MANAGER_CLASS, "issuerTokenCache",
                                "Ljava/util/concurrent/ConcurrentHashMap;");

                mv.visitTypeInsn(Opcodes.NEW, "java/util/concurrent/ConcurrentHashMap");
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/concurrent/ConcurrentHashMap", "<init>", "()V",
                                false);
                mv.visitFieldInsn(Opcodes.PUTSTATIC, TOKEN_MANAGER_CLASS, "issuerIdentityCache",
                                "Ljava/util/concurrent/ConcurrentHashMap;");

                // 3. WARM UP JWKS (Issue 1)
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, JWKS_FETCHER_CLASS, "fetchMergedJwksJson", "()Ljava/lang/String;", false);
                mv.visitInsn(Opcodes.POP);

                // 4. Perform Synchronous Fetch
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, TOKEN_MANAGER_CLASS, "ensureF2PTokens", "()V", false);

                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(2, 0);
                mv.visitEnd();
                // ---------------------------------------------

                // Private constructor
                mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null);
                mv.visitCode();
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(1, 1);
                mv.visitEnd();

                // public static void setOfficialTokens(String sessionToken, String
                // identityToken)
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNCHRONIZED,
                                "setOfficialTokens", "(Ljava/lang/String;Ljava/lang/String;)V", null, null);
                mv.visitCode();
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitFieldInsn(Opcodes.PUTSTATIC, TOKEN_MANAGER_CLASS, "officialSessionToken", "Ljava/lang/String;");
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitFieldInsn(Opcodes.PUTSTATIC, TOKEN_MANAGER_CLASS, "officialIdentityToken",
                                "Ljava/lang/String;");
                // Log
                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                mv.visitLdcInsn("[DualAuth] Official tokens set (from /auth login)");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V",
                                false);
                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(2, 2);
                mv.visitEnd();

                // public static void setF2PTokens(String sessionToken, String identityToken)
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNCHRONIZED,
                                "setF2PTokens", "(Ljava/lang/String;Ljava/lang/String;)V", null, null);
                mv.visitCode();
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitFieldInsn(Opcodes.PUTSTATIC, TOKEN_MANAGER_CLASS, "f2pSessionToken", "Ljava/lang/String;");
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitFieldInsn(Opcodes.PUTSTATIC, TOKEN_MANAGER_CLASS, "f2pIdentityToken", "Ljava/lang/String;");
                mv.visitInsn(Opcodes.ICONST_1);
                mv.visitFieldInsn(Opcodes.PUTSTATIC, TOKEN_MANAGER_CLASS, "f2pTokensFetched", "Z");

                // Extract issuer
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                Label nullId = new Label();
                mv.visitJumpInsn(Opcodes.IFNULL, nullId);
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_CLASS, "extractIssuerFromToken",
                                "(Ljava/lang/String;)Ljava/lang/String;", false);
                mv.visitFieldInsn(Opcodes.PUTSTATIC, TOKEN_MANAGER_CLASS, "f2pIssuer", "Ljava/lang/String;");
                mv.visitLabel(nullId);

                // Log
                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn("[DualAuth] F2P tokens set (auto-fetched from ");
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V",
                                false);
                mv.visitFieldInsn(Opcodes.GETSTATIC, HELPER_CLASS, "F2P_URL", "Ljava/lang/String;");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitLdcInsn(")");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;",
                                false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V",
                                false);
                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(2, 2);
                mv.visitEnd();

                // ------------------------------------------------------------------------------------------
                // NEW METHOD: fetchAndCacheServerTokens
                // ------------------------------------------------------------------------------------------
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "fetchAndCacheServerTokens",
                                "(Ljava/lang/String;)V", null, null);
                mv.visitCode();

                Label fStart = new Label(), fEnd = new Label(), fCatch = new Label();
                mv.visitTryCatchBlock(fStart, fEnd, fCatch, "java/lang/Exception");
                mv.visitLabel(fStart);

                // Build URL: issuer + "/server/auto-auth"
                mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
                mv.visitInsn(Opcodes.DUP);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V",
                                false);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitLdcInsn("/");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "endsWith", "(Ljava/lang/String;)Z",
                                false);
                Label addSlash = new Label();
                mv.visitJumpInsn(Opcodes.IFNE, addSlash);
                mv.visitLdcInsn("/");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitLabel(addSlash);
                mv.visitLdcInsn("server/auto-auth");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;",
                                false);
                mv.visitVarInsn(Opcodes.ASTORE, 1); // url

                // Build JSON
                mv.visitLdcInsn("HYTALE_SERVER_AUDIENCE");
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getenv",
                                "(Ljava/lang/String;)Ljava/lang/String;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 2);
                mv.visitVarInsn(Opcodes.ALOAD, 2);
                Label hasUuid = new Label();
                mv.visitJumpInsn(Opcodes.IFNONNULL, hasUuid);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/UUID", "randomUUID", "()Ljava/util/UUID;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/UUID", "toString", "()Ljava/lang/String;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 2);
                mv.visitLabel(hasUuid);

                mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn("{\"server_id\":\"");
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V",
                                false);
                mv.visitVarInsn(Opcodes.ALOAD, 2);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitLdcInsn("\"}");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;",
                                false);
                mv.visitVarInsn(Opcodes.ASTORE, 3); // body

                // Log
                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn("[DualAuth] Dynamic fetch for issuer: ");
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V",
                                false);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;",
                                false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V",
                                false);

                // HTTP Client
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/net/http/HttpClient", "newHttpClient",
                                "()Ljava/net/http/HttpClient;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 4);

                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/net/http/HttpRequest", "newBuilder",
                                "()Ljava/net/http/HttpRequest$Builder;", false);
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/net/URI", "create", "(Ljava/lang/String;)Ljava/net/URI;",
                                false);
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/net/http/HttpRequest$Builder", "uri",
                                "(Ljava/net/URI;)Ljava/net/http/HttpRequest$Builder;", true);
                mv.visitLdcInsn("Content-Type");
                mv.visitLdcInsn("application/json");
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/net/http/HttpRequest$Builder", "header",
                                "(Ljava/lang/String;Ljava/lang/String;)Ljava/net/http/HttpRequest$Builder;", true);
                mv.visitVarInsn(Opcodes.ALOAD, 3);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/net/http/HttpRequest$BodyPublishers", "ofString",
                                "(Ljava/lang/String;)Ljava/net/http/HttpRequest$BodyPublisher;", false);
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/net/http/HttpRequest$Builder", "POST",
                                "(Ljava/net/http/HttpRequest$BodyPublisher;)Ljava/net/http/HttpRequest$Builder;", true);
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/net/http/HttpRequest$Builder", "build",
                                "()Ljava/net/http/HttpRequest;", true);
                mv.visitVarInsn(Opcodes.ASTORE, 5);

                mv.visitVarInsn(Opcodes.ALOAD, 4);
                mv.visitVarInsn(Opcodes.ALOAD, 5);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/net/http/HttpResponse$BodyHandlers", "ofString",
                                "()Ljava/net/http/HttpResponse$BodyHandler;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/net/http/HttpClient", "send",
                                "(Ljava/net/http/HttpRequest;Ljava/net/http/HttpResponse$BodyHandler;)Ljava/net/http/HttpResponse;",
                                false);
                mv.visitVarInsn(Opcodes.ASTORE, 6);

                mv.visitVarInsn(Opcodes.ALOAD, 6);
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/net/http/HttpResponse", "statusCode", "()I", true);
                mv.visitIntInsn(Opcodes.SIPUSH, 200);
                Label statusFail = new Label();
                mv.visitJumpInsn(Opcodes.IF_ICMPNE, statusFail);

                mv.visitVarInsn(Opcodes.ALOAD, 6);
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/net/http/HttpResponse", "body", "()Ljava/lang/Object;",
                                true);
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String");
                mv.visitVarInsn(Opcodes.ASTORE, 7);

                // Extract and Cache
                mv.visitVarInsn(Opcodes.ALOAD, 7);
                mv.visitLdcInsn("sessionToken");
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, TOKEN_MANAGER_CLASS, "extractJsonField",
                                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 8);

                mv.visitVarInsn(Opcodes.ALOAD, 7);
                mv.visitLdcInsn("identityToken");
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, TOKEN_MANAGER_CLASS, "extractJsonField",
                                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 9);

                mv.visitVarInsn(Opcodes.ALOAD, 8);
                Label sNull = new Label();
                mv.visitJumpInsn(Opcodes.IFNULL, sNull);
                mv.visitFieldInsn(Opcodes.GETSTATIC, TOKEN_MANAGER_CLASS, "issuerTokenCache",
                                "Ljava/util/concurrent/ConcurrentHashMap;");
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitVarInsn(Opcodes.ALOAD, 8);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/concurrent/ConcurrentHashMap", "put",
                                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
                mv.visitInsn(Opcodes.POP);
                mv.visitLabel(sNull);

                mv.visitVarInsn(Opcodes.ALOAD, 9);
                Label iNull = new Label();
                mv.visitJumpInsn(Opcodes.IFNULL, iNull);
                mv.visitFieldInsn(Opcodes.GETSTATIC, TOKEN_MANAGER_CLASS, "issuerIdentityCache",
                                "Ljava/util/concurrent/ConcurrentHashMap;");
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitVarInsn(Opcodes.ALOAD, 9);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/concurrent/ConcurrentHashMap", "put",
                                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
                mv.visitInsn(Opcodes.POP);
                mv.visitLabel(iNull);

                mv.visitLabel(statusFail);
                mv.visitLabel(fEnd);
                Label fAfter = new Label();
                mv.visitJumpInsn(Opcodes.GOTO, fAfter);

                mv.visitLabel(fCatch);
                mv.visitInsn(Opcodes.POP);
                mv.visitLabel(fAfter);

                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(4, 10);
                mv.visitEnd();

                // ==========================================================================================
                // NEW HELPER: captureNativeSession
                // Called by ServerAuthManager patch when native auth succeeds
                // ==========================================================================================
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                                "captureNativeSession", "(Ljava/lang/Object;)V", null, null);
                mv.visitCode();
                // if (session == null) return;
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                Label sessionNull = new Label();
                mv.visitJumpInsn(Opcodes.IFNULL, sessionNull);

                // Cast to GameSessionResponse
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitTypeInsn(Opcodes.CHECKCAST,
                                "com/hypixel/hytale/server/core/auth/SessionServiceClient$GameSessionResponse");
                mv.visitVarInsn(Opcodes.ASTORE, 1);

                // Extract tokens
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitFieldInsn(Opcodes.GETFIELD,
                                "com/hypixel/hytale/server/core/auth/SessionServiceClient$GameSessionResponse",
                                "sessionToken", "Ljava/lang/String;");
                mv.visitVarInsn(Opcodes.ASTORE, 2); // sToken

                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitFieldInsn(Opcodes.GETFIELD,
                                "com/hypixel/hytale/server/core/auth/SessionServiceClient$GameSessionResponse",
                                "identityToken", "Ljava/lang/String;");
                mv.visitVarInsn(Opcodes.ASTORE, 3); // iToken

                // Extract Issuer to decide where to store
                mv.visitVarInsn(Opcodes.ALOAD, 3);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_CLASS, "extractIssuerFromToken",
                                "(Ljava/lang/String;)Ljava/lang/String;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 4); // issuer

                // Log
                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn("[DualAuth] Capturing native session for issuer: ");
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V",
                                false);
                mv.visitVarInsn(Opcodes.ALOAD, 4);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;",
                                false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V",
                                false);

                // if (isOfficial(issuer)) -> setOfficial
                mv.visitVarInsn(Opcodes.ALOAD, 4);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_CLASS, "isOfficialIssuer", "(Ljava/lang/String;)Z",
                                false);
                Label isF2PLabel = new Label();
                mv.visitJumpInsn(Opcodes.IFEQ, isF2PLabel);

                mv.visitVarInsn(Opcodes.ALOAD, 2);
                mv.visitVarInsn(Opcodes.ALOAD, 3);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, TOKEN_MANAGER_CLASS, "setOfficialTokens",
                                "(Ljava/lang/String;Ljava/lang/String;)V", false);
                mv.visitInsn(Opcodes.RETURN);

                mv.visitLabel(isF2PLabel);

                // Else -> setF2P
                mv.visitVarInsn(Opcodes.ALOAD, 2);
                mv.visitVarInsn(Opcodes.ALOAD, 3);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, TOKEN_MANAGER_CLASS, "setF2PTokens",
                                "(Ljava/lang/String;Ljava/lang/String;)V", false);

                mv.visitLabel(sessionNull);

                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(3, 5);
                mv.visitEnd();

                // === CRITICAL FIX: getSessionTokenForIssuer ===
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getSessionTokenForIssuer", "(Ljava/lang/String;)Ljava/lang/String;", null, null);
                mv.visitCode();

                // 1. Resolve Issuer
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                Label stHasIssuer = new Label();
                mv.visitJumpInsn(Opcodes.IFNONNULL, stHasIssuer);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, CONTEXT_CLASS, "getIssuer", "()Ljava/lang/String;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 0);
                mv.visitLabel(stHasIssuer);

                // 2. Safeguard (null check)
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                Label stCheckOff = new Label();
                mv.visitJumpInsn(Opcodes.IFNONNULL, stCheckOff);
                // If issuer is null, assume F2P fallback
                mv.visitFieldInsn(Opcodes.GETSTATIC, TOKEN_MANAGER_CLASS, "f2pSessionToken", "Ljava/lang/String;");
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitLabel(stCheckOff);

                // 3. Official Check
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_CLASS, "isOfficialIssuer", "(Ljava/lang/String;)Z", false);
                Label stNotOff = new Label();
                mv.visitJumpInsn(Opcodes.IFEQ, stNotOff);
                mv.visitFieldInsn(Opcodes.GETSTATIC, TOKEN_MANAGER_CLASS, "officialSessionToken", "Ljava/lang/String;");
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitLabel(stNotOff);

                // 4. F2P Check (PROMISCUOUS MODE)
                // If we have F2P tokens, and the user didn't ask for official, return F2P token.
                // This solves the localhost (server) vs sanasol.ws (client) mismatch.
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, TOKEN_MANAGER_CLASS, "hasF2PTokens", "()Z", false);
                Label stCheckOmni = new Label();
                mv.visitJumpInsn(Opcodes.IFEQ, stCheckOmni);
                
                // Log Promiscuous fallback
                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                mv.visitLdcInsn("[DualAuth] Returning F2P token for non-official issuer: ");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "print", "(Ljava/lang/String;)V", false);
                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

                mv.visitFieldInsn(Opcodes.GETSTATIC, TOKEN_MANAGER_CLASS, "f2pSessionToken", "Ljava/lang/String;");
                mv.visitInsn(Opcodes.ARETURN);

                mv.visitLabel(stCheckOmni);

                // 5. Omni Check (Modified: Try dynamic generation if not found in cache/fetch)
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, CONTEXT_CLASS, "getJwk", "()Ljava/lang/String;", false);
                Label stNoJwk = new Label();
                mv.visitJumpInsn(Opcodes.IFNULL, stNoJwk);

                // We have a JWK context (meaning a client is connected).
                // If we haven't returned yet, generate a dynamic token signed by client's key.
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, TOKEN_MANAGER_CLASS, "generateDynamicSessionToken",
                                "(Ljava/lang/String;)Ljava/lang/String;", false);
                mv.visitInsn(Opcodes.ARETURN);

                mv.visitLabel(stNoJwk);

                // Fallback (Return null to avoid incorrect issuer errors)
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitInsn(Opcodes.ARETURN);

                mv.visitMaxs(2, 1);
                mv.visitEnd();

                mv.visitMaxs(3, 3);
                mv.visitEnd();

        // fetchServerTokenFromIssuer (Old method -> redirect to new)
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "fetchServerTokenFromIssuer",
                        "(Ljava/lang/String;)Ljava/lang/String;", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, TOKEN_MANAGER_CLASS, "fetchAndCacheServerTokens",
                        "(Ljava/lang/String;)V", false);
        mv.visitFieldInsn(Opcodes.GETSTATIC, TOKEN_MANAGER_CLASS, "issuerTokenCache",
                        "Ljava/util/concurrent/ConcurrentHashMap;");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/concurrent/ConcurrentHashMap", "get",
                        "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String");
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(2, 1);
        mv.visitEnd();


                // === CRITICAL FIX: getIdentityTokenForIssuer (Same logic) ===
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getIdentityTokenForIssuer", "(Ljava/lang/String;)Ljava/lang/String;", null, null);
                mv.visitCode();

                mv.visitVarInsn(Opcodes.ALOAD, 0);
                Label idHasIssuer = new Label();
                mv.visitJumpInsn(Opcodes.IFNONNULL, idHasIssuer);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, CONTEXT_CLASS, "getIssuer", "()Ljava/lang/String;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 0);
                mv.visitLabel(idHasIssuer);

                mv.visitVarInsn(Opcodes.ALOAD, 0);
                Label idCheckOff = new Label();
                mv.visitJumpInsn(Opcodes.IFNONNULL, idCheckOff);
                mv.visitFieldInsn(Opcodes.GETSTATIC, TOKEN_MANAGER_CLASS, "f2pIdentityToken", "Ljava/lang/String;");
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitLabel(idCheckOff);

                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_CLASS, "isOfficialIssuer", "(Ljava/lang/String;)Z", false);
                Label idNotOff = new Label();
                mv.visitJumpInsn(Opcodes.IFEQ, idNotOff);
                mv.visitFieldInsn(Opcodes.GETSTATIC, TOKEN_MANAGER_CLASS, "officialIdentityToken", "Ljava/lang/String;");
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitLabel(idNotOff);

                // Promiscuous F2P Fallback
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, TOKEN_MANAGER_CLASS, "hasF2PTokens", "()Z", false);
                Label idCheckOmni = new Label();
                mv.visitJumpInsn(Opcodes.IFEQ, idCheckOmni);
                mv.visitFieldInsn(Opcodes.GETSTATIC, TOKEN_MANAGER_CLASS, "f2pIdentityToken", "Ljava/lang/String;");
                mv.visitInsn(Opcodes.ARETURN);

                mv.visitLabel(idCheckOmni);

                mv.visitMethodInsn(Opcodes.INVOKESTATIC, CONTEXT_CLASS, "getJwk", "()Ljava/lang/String;", false);
                Label idNoJwk = new Label();
                mv.visitJumpInsn(Opcodes.IFNULL, idNoJwk);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, TOKEN_MANAGER_CLASS, "generateDynamicIdentityToken", "(Ljava/lang/String;)Ljava/lang/String;", false);
                mv.visitInsn(Opcodes.ARETURN);
                
                mv.visitLabel(idNoJwk);
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitInsn(Opcodes.ARETURN);

                mv.visitMaxs(2, 1);
                mv.visitEnd();

                mv.visitMaxs(3, 3);
                mv.visitEnd();

                // public static boolean hasOfficialTokens()
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                                "hasOfficialTokens", "()Z", null, null);
                mv.visitCode();
                mv.visitFieldInsn(Opcodes.GETSTATIC, TOKEN_MANAGER_CLASS, "officialSessionToken", "Ljava/lang/String;");
                Label noOfficial = new Label();
                mv.visitJumpInsn(Opcodes.IFNULL, noOfficial);
                mv.visitInsn(Opcodes.ICONST_1);
                mv.visitInsn(Opcodes.IRETURN);
                mv.visitLabel(noOfficial);
                mv.visitInsn(Opcodes.ICONST_0);
                mv.visitInsn(Opcodes.IRETURN);
                mv.visitMaxs(1, 0);
                mv.visitEnd();

                // public static boolean hasF2PTokens()
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                                "hasF2PTokens", "()Z", null, null);
                mv.visitCode();
                mv.visitFieldInsn(Opcodes.GETSTATIC, TOKEN_MANAGER_CLASS, "f2pSessionToken", "Ljava/lang/String;");
                Label noF2P = new Label();
                mv.visitJumpInsn(Opcodes.IFNULL, noF2P);
                mv.visitInsn(Opcodes.ICONST_1);
                mv.visitInsn(Opcodes.IRETURN);
                mv.visitLabel(noF2P);
                mv.visitInsn(Opcodes.ICONST_0);
                mv.visitInsn(Opcodes.IRETURN);
                mv.visitMaxs(1, 0);
                mv.visitEnd();

                // public static void ensureF2PTokens()
                // Auto-fetches F2P tokens if not already fetched
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNCHRONIZED,
                                "ensureF2PTokens", "()V", null, null);
                mv.visitCode();
                // if (f2pTokensFetched) return;
                mv.visitFieldInsn(Opcodes.GETSTATIC, TOKEN_MANAGER_CLASS, "f2pTokensFetched", "Z");
                Label needFetch = new Label();
                mv.visitJumpInsn(Opcodes.IFEQ, needFetch);
                mv.visitInsn(Opcodes.RETURN);
                mv.visitLabel(needFetch);

                // fetchF2PTokens();
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, TOKEN_MANAGER_CLASS, "fetchF2PTokens", "()V", false);
                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(1, 0);
                mv.visitEnd();

                // public static void fetchF2PTokens()
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                                "fetchF2PTokens", "()V", null, null);
                mv.visitCode();

                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                mv.visitLdcInsn("[DualAuth] Auto-fetching F2P server tokens from " + F2P_SESSION_URL
                                + "/server/auto-auth...");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V",
                                false);

                Label tryStart = new Label();
                Label tryEnd = new Label();
                Label catchHandler = new Label();
                mv.visitTryCatchBlock(tryStart, tryEnd, catchHandler, "java/lang/Exception");
                mv.visitLabel(tryStart);

                // [UUID Generation Logic]
                mv.visitLdcInsn("HYTALE_SERVER_AUDIENCE");
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getenv",
                                "(Ljava/lang/String;)Ljava/lang/String;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 0);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                Label hasUuidF2P = new Label();
                mv.visitJumpInsn(Opcodes.IFNONNULL, hasUuidF2P);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/UUID", "randomUUID", "()Ljava/util/UUID;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/UUID", "toString", "()Ljava/lang/String;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 0);
                mv.visitLabel(hasUuidF2P);

                // [HttpClient Logic]
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/net/http/HttpClient", "newBuilder",
                                "()Ljava/net/http/HttpClient$Builder;", false);
                mv.visitLdcInsn(10L);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/time/Duration", "ofSeconds",
                                "(J)Ljava/time/Duration;", false);
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/net/http/HttpClient$Builder", "connectTimeout",
                                "(Ljava/time/Duration;)Ljava/net/http/HttpClient$Builder;", true);
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/net/http/HttpClient$Builder", "build",
                                "()Ljava/net/http/HttpClient;", true);
                mv.visitVarInsn(Opcodes.ASTORE, 1);

                // JSON Body
                mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn("{\"server_id\":\"");
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V",
                                false);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitLdcInsn("\"}");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;",
                                false);
                mv.visitVarInsn(Opcodes.ASTORE, 2);

                // Request
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/net/http/HttpRequest", "newBuilder",
                                "()Ljava/net/http/HttpRequest$Builder;", false);
                mv.visitLdcInsn(F2P_SESSION_URL + "/server/auto-auth");
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/net/URI", "create",
                                "(Ljava/lang/String;)Ljava/net/URI;", false);
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/net/http/HttpRequest$Builder", "uri",
                                "(Ljava/net/URI;)Ljava/net/http/HttpRequest$Builder;", true);
                mv.visitLdcInsn("Content-Type");
                mv.visitLdcInsn("application/json");
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/net/http/HttpRequest$Builder", "header",
                                "(Ljava/lang/String;Ljava/lang/String;)Ljava/net/http/HttpRequest$Builder;", true);
                mv.visitVarInsn(Opcodes.ALOAD, 2);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/net/http/HttpRequest$BodyPublishers", "ofString",
                                "(Ljava/lang/String;)Ljava/net/http/HttpRequest$BodyPublisher;", false);
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/net/http/HttpRequest$Builder", "POST",
                                "(Ljava/net/http/HttpRequest$BodyPublisher;)Ljava/net/http/HttpRequest$Builder;", true);
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/net/http/HttpRequest$Builder", "build",
                                "()Ljava/net/http/HttpRequest;", true);
                mv.visitVarInsn(Opcodes.ASTORE, 3);

                // Send
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitVarInsn(Opcodes.ALOAD, 3);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/net/http/HttpResponse$BodyHandlers", "ofString",
                                "()Ljava/net/http/HttpResponse$BodyHandler;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/net/http/HttpClient", "send",
                                "(Ljava/net/http/HttpRequest;Ljava/net/http/HttpResponse$BodyHandler;)Ljava/net/http/HttpResponse;",
                                false);
                mv.visitVarInsn(Opcodes.ASTORE, 4);

                // Check 200
                mv.visitVarInsn(Opcodes.ALOAD, 4);
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/net/http/HttpResponse", "statusCode", "()I", true);
                mv.visitIntInsn(Opcodes.SIPUSH, 200);
                Label notOk = new Label();
                mv.visitJumpInsn(Opcodes.IF_ICMPNE, notOk);

                // Get Body
                mv.visitVarInsn(Opcodes.ALOAD, 4);
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/net/http/HttpResponse", "body",
                                "()Ljava/lang/Object;", true);
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String");
                mv.visitVarInsn(Opcodes.ASTORE, 5);

                // --- LOG BODY ---
                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn("[DualAuth] Auto-auth response: ");
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
                mv.visitVarInsn(Opcodes.ALOAD, 5);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

                // Parse
                mv.visitVarInsn(Opcodes.ALOAD, 5);
                mv.visitLdcInsn("sessionToken");
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, TOKEN_MANAGER_CLASS, "extractJsonField",
                                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 6);

                mv.visitVarInsn(Opcodes.ALOAD, 5);
                mv.visitLdcInsn("identityToken");
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, TOKEN_MANAGER_CLASS, "extractJsonField",
                                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 7);

                // Set
                mv.visitVarInsn(Opcodes.ALOAD, 6);
                mv.visitVarInsn(Opcodes.ALOAD, 7);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, TOKEN_MANAGER_CLASS, "setF2PTokens",
                                "(Ljava/lang/String;Ljava/lang/String;)V", false);

                mv.visitLabel(notOk);
                mv.visitLabel(tryEnd);
                Label afterCatch = new Label();
                mv.visitJumpInsn(Opcodes.GOTO, afterCatch);

                mv.visitLabel(catchHandler);
                mv.visitVarInsn(Opcodes.ASTORE, 0);
                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;");
                mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn("[DualAuth] Failed to fetch F2P tokens: ");
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V",
                                false);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Exception", "getMessage", "()Ljava/lang/String;",
                                false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;",
                                false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V",
                                false);

                mv.visitLabel(afterCatch);
                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(5, 8);
                mv.visitEnd();

                // private static String extractJsonField(String json, String field)
                // ROBUST VERSION: Handles whitespace around colons and values
                mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                                "extractJsonField", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", null,
                                null);
                mv.visitCode();

                // 1. Find index of "field"
                // String key = "\"" + field + "\"";
                mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn("\"");
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitLdcInsn("\"");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 2); // key

                // int idx = json.indexOf(key);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitVarInsn(Opcodes.ALOAD, 2);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "indexOf", "(Ljava/lang/String;)I", false);
                mv.visitVarInsn(Opcodes.ISTORE, 3); // idx

                // if (idx < 0) return null;
                mv.visitVarInsn(Opcodes.ILOAD, 3);
                Label notFound = new Label();
                mv.visitJumpInsn(Opcodes.IFLT, notFound);

                // 2. Find next colon ':' after key
                // int colonIdx = json.indexOf(':', idx + key.length());
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitIntInsn(Opcodes.BIPUSH, ':');
                mv.visitVarInsn(Opcodes.ILOAD, 3);
                mv.visitVarInsn(Opcodes.ALOAD, 2);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
                mv.visitInsn(Opcodes.IADD);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "indexOf", "(II)I", false);
                mv.visitVarInsn(Opcodes.ISTORE, 4); // colonIdx

                // if (colonIdx < 0) return null;
                mv.visitVarInsn(Opcodes.ILOAD, 4);
                mv.visitJumpInsn(Opcodes.IFLT, notFound);

                // 3. Find start quote '"' after colon
                // int startQuote = json.indexOf('"', colonIdx);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitIntInsn(Opcodes.BIPUSH, '"');
                mv.visitVarInsn(Opcodes.ILOAD, 4);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "indexOf", "(II)I", false);
                mv.visitVarInsn(Opcodes.ISTORE, 5); // startQuote

                // if (startQuote < 0) return null;
                mv.visitVarInsn(Opcodes.ILOAD, 5);
                mv.visitJumpInsn(Opcodes.IFLT, notFound);

                // 4. Find end quote '"' after start quote
                // int endQuote = json.indexOf('"', startQuote + 1);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitIntInsn(Opcodes.BIPUSH, '"');
                mv.visitVarInsn(Opcodes.ILOAD, 5);
                mv.visitInsn(Opcodes.ICONST_1);
                mv.visitInsn(Opcodes.IADD);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "indexOf", "(II)I", false);
                mv.visitVarInsn(Opcodes.ISTORE, 6); // endQuote

                // if (endQuote < 0) return null;
                mv.visitVarInsn(Opcodes.ILOAD, 6);
                mv.visitJumpInsn(Opcodes.IFLT, notFound);

                // 5. Extract
                // return json.substring(startQuote + 1, endQuote);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitVarInsn(Opcodes.ILOAD, 5);
                mv.visitInsn(Opcodes.ICONST_1);
                mv.visitInsn(Opcodes.IADD);
                mv.visitVarInsn(Opcodes.ILOAD, 6);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring", "(II)Ljava/lang/String;", false);
                mv.visitInsn(Opcodes.ARETURN);

                mv.visitLabel(notFound);
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitMaxs(4, 7);
                mv.visitEnd();

                // public static void logStatus()
                // Debug method to log current token status
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                                "logStatus", "()V", null, null);
                mv.visitCode();
                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                mv.visitLdcInsn("[DualAuth] Token Status:");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V",
                                false);

                // Official tokens
                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn("  Official tokens: ");
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V",
                                false);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, TOKEN_MANAGER_CLASS, "hasOfficialTokens", "()Z", false);
                Label hasOfficialLabel = new Label();
                mv.visitJumpInsn(Opcodes.IFEQ, hasOfficialLabel);
                mv.visitLdcInsn("present");
                Label afterOfficialLabel = new Label();
                mv.visitJumpInsn(Opcodes.GOTO, afterOfficialLabel);
                mv.visitLabel(hasOfficialLabel);
                mv.visitLdcInsn("missing");
                mv.visitLabel(afterOfficialLabel);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;",
                                false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V",
                                false);

                // F2P tokens
                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn("  F2P tokens: ");
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V",
                                false);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, TOKEN_MANAGER_CLASS, "hasF2PTokens", "()Z", false);
                Label hasF2PLabel = new Label();
                mv.visitJumpInsn(Opcodes.IFEQ, hasF2PLabel);
                mv.visitLdcInsn("present");
                Label afterF2PLabel = new Label();
                mv.visitJumpInsn(Opcodes.GOTO, afterF2PLabel);
                mv.visitLabel(hasF2PLabel);
                mv.visitLdcInsn("missing");
                mv.visitLabel(afterF2PLabel);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;",
                                false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V",
                                false);

                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(4, 0);
                mv.visitEnd();

                // public static void logCurrentTokens()
                // Debug method to log current token acquisition status
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                                "logCurrentTokens", "()V", null, null);
                mv.visitCode();
                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn("[DualAuth] Current tokens - Official: ");
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V",
                                false);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, TOKEN_MANAGER_CLASS, "hasOfficialTokens", "()Z", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                "(Z)Ljava/lang/StringBuilder;",
                                false);
                mv.visitLdcInsn(", F2P: ");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, TOKEN_MANAGER_CLASS, "hasF2PTokens", "()Z", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                "(Z)Ljava/lang/StringBuilder;",
                                false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;",
                                false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V",
                                false);
                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(4, 0);
                mv.visitEnd();

                // [NOTE: Removed the second (duplicate) <clinit> block that was here]

                // public static String generateDynamicIdentityToken(String issuer)
                // Generate a dynamic identity token signed with the client's embedded JWK
                // This creates a valid server token that the client will accept
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                                "generateDynamicIdentityToken", "(Ljava/lang/String;)Ljava/lang/String;", null, null);
                mv.visitCode();

                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn("[DualAuth] Generating signed identity token for issuer: ");
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V",
                                false);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;",
                                false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V",
                                false);

                mv.visitMethodInsn(Opcodes.INVOKESTATIC, CONTEXT_CLASS, "getJwk", "()Ljava/lang/String;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 1); // jwkString

                // Check if we have a JWK
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                Label hasJwk = new Label();
                mv.visitJumpInsn(Opcodes.IFNONNULL, hasJwk);

                // No JWK available - fallback to unsigned token (shouldn't happen in normal
                // flow)
                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                mv.visitLdcInsn("[DualAuth] WARNING: No embedded JWK available, using unsigned token");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V",
                                false);
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitInsn(Opcodes.ARETURN);

                mv.visitLabel(hasJwk);

                // Create signed JWT using the embedded JWK as the signing key
                // Call EmbeddedJwkVerifier.createSignedToken(issuer, jwkString, "identity")
                mv.visitVarInsn(Opcodes.ALOAD, 0); // issuer
                mv.visitVarInsn(Opcodes.ALOAD, 1); // jwkString
                mv.visitLdcInsn("identity");
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/hypixel/hytale/server/core/auth/EmbeddedJwkVerifier",
                                "createSignedToken",
                                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                                false);

                mv.visitInsn(Opcodes.ARETURN);
                mv.visitMaxs(3, 2);
                mv.visitEnd();

                // public static String generateDynamicSessionToken(String issuer)
                // Generate a dynamic session token signed with the client's embedded JWK
                // This creates a valid server token that the client will accept
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                                "generateDynamicSessionToken", "(Ljava/lang/String;)Ljava/lang/String;", null, null);
                mv.visitCode();

                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn("[DualAuth] Generating signed session token for issuer: ");
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V",
                                false);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;",
                                false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V",
                                false);

                mv.visitMethodInsn(Opcodes.INVOKESTATIC, CONTEXT_CLASS, "getJwk", "()Ljava/lang/String;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 1); // jwkString

                // Check if we have a JWK
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                Label hasJwkSession = new Label();
                mv.visitJumpInsn(Opcodes.IFNONNULL, hasJwkSession);

                // No JWK available - fallback to unsigned token (shouldn't happen in normal
                // flow)
                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                mv.visitLdcInsn("[DualAuth] WARNING: No embedded JWK available, using unsigned token");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V",
                                false);
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitInsn(Opcodes.ARETURN);

                mv.visitLabel(hasJwkSession);

                // Create signed JWT using the embedded JWK as the signing key
                // Call EmbeddedJwkVerifier.createSignedToken(issuer, jwkString, "session")
                mv.visitVarInsn(Opcodes.ALOAD, 0); // issuer
                mv.visitVarInsn(Opcodes.ALOAD, 1); // jwkString
                mv.visitLdcInsn("session");
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/hypixel/hytale/server/core/auth/EmbeddedJwkVerifier",
                                "createSignedToken",
                                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                                false);

                mv.visitInsn(Opcodes.ARETURN);
                mv.visitMaxs(3, 2);
                mv.visitEnd();

                cw.visitEnd();
                return cw.toByteArray();
        }

        /**
         * Generate EmbeddedJwkVerifier class - OMNI-AUTH BYPASS EDITION
         * Handles tokens with embedded JWK private keys by manually validating
         * signatures
         * bypassing strict JWSHeader checks that reject 'd' (private) parameters.
         */
        private static byte[] generateEmbeddedJwkVerifier() {
                ClassWriter cw = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

                cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                                EMBEDDED_VERIFIER_CLASS, null, "java/lang/Object", null);

                // Static initializer
                MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
                mv.visitCode();
                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(0, 0);
                mv.visitEnd();

                // -------------------------------------------------------------
                // verifyAndGetClaims(String token) -> JWTClaimsSet (or null)
                // -------------------------------------------------------------
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                                "verifyAndGetClaims", "(Ljava/lang/String;)Lcom/nimbusds/jwt/JWTClaimsSet;", null,
                                null);
                mv.visitCode();

                // Reset Omni state at the beginning
                mv.visitInsn(Opcodes.ICONST_0);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, CONTEXT_CLASS, "setOmni", "(Z)V", false);

                Label tryStart = new Label();
                Label tryEnd = new Label();
                Label catchLabel = new Label();

                // 1. RAW STRING MANIPULATION (No parsing yet)
                // int dot1 = token.indexOf('.');
                mv.visitVarInsn(Opcodes.ALOAD, 0); // token
                mv.visitIntInsn(Opcodes.BIPUSH, '.');
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "indexOf", "(I)I", false);
                mv.visitVarInsn(Opcodes.ISTORE, 1); // dot1

                // int dot2 = token.lastIndexOf('.');
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitIntInsn(Opcodes.BIPUSH, '.');
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "lastIndexOf", "(I)I", false);
                mv.visitVarInsn(Opcodes.ISTORE, 2); // dot2

                // if (dot1 < 0 || dot2 <= dot1) throw new Exception("Invalid token format");
                mv.visitVarInsn(Opcodes.ILOAD, 1);
                Label invalidFormat = new Label();
                mv.visitJumpInsn(Opcodes.IFLT, invalidFormat);
                mv.visitVarInsn(Opcodes.ILOAD, 2);
                mv.visitVarInsn(Opcodes.ILOAD, 1);
                mv.visitJumpInsn(Opcodes.IF_ICMPLE, invalidFormat);

                // String headerStr = token.substring(0, dot1);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitInsn(Opcodes.ICONST_0);
                mv.visitVarInsn(Opcodes.ILOAD, 1);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring", "(II)Ljava/lang/String;",
                                false);
                mv.visitVarInsn(Opcodes.ASTORE, 3);

                // Decode Header: String headerJson = new Base64URL(headerStr).decodeToString();
                mv.visitTypeInsn(Opcodes.NEW, "com/nimbusds/jose/util/Base64URL");
                mv.visitInsn(Opcodes.DUP);
                mv.visitVarInsn(Opcodes.ALOAD, 3);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/nimbusds/jose/util/Base64URL", "<init>",
                                "(Ljava/lang/String;)V",
                                false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jose/util/Base64URL", "decodeToString",
                                "()Ljava/lang/String;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 4); // headerJson

                // Parse JSON map to inspect manually: Map headerMap =
                // JSONObjectUtils.parse(headerJson);
                mv.visitVarInsn(Opcodes.ALOAD, 4);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/nimbusds/jose/util/JSONObjectUtils", "parse",
                                "(Ljava/lang/String;)Ljava/util/Map;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 5); // headerMap

                // if (!headerMap.containsKey("jwk")) goto StandardParse;
                mv.visitVarInsn(Opcodes.ALOAD, 5);
                mv.visitLdcInsn("jwk");
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map", "containsKey", "(Ljava/lang/Object;)Z",
                                true);
                Label standardParse = new Label();
                mv.visitJumpInsn(Opcodes.IFEQ, standardParse);

                // JWK jwk = JWK.parse((Map)headerMap.get("jwk")); // JWK.parse allows private
                // keys!
                mv.visitVarInsn(Opcodes.ALOAD, 5);
                mv.visitLdcInsn("jwk");
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map", "get",
                                "(Ljava/lang/Object;)Ljava/lang/Object;",
                                true);
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/util/Map");
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/nimbusds/jose/jwk/JWK", "parse",
                                "(Ljava/util/Map;)Lcom/nimbusds/jose/jwk/JWK;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 6); // jwk

                // Check if PRIVATE
                // if (jwk instanceof OctetKeyPair && ((OctetKeyPair)jwk).isPrivate()) { ... }
                mv.visitVarInsn(Opcodes.ALOAD, 6);
                mv.visitTypeInsn(Opcodes.INSTANCEOF, "com/nimbusds/jose/jwk/OctetKeyPair");
                mv.visitJumpInsn(Opcodes.IFEQ, standardParse);

                mv.visitVarInsn(Opcodes.ALOAD, 6);
                mv.visitTypeInsn(Opcodes.CHECKCAST, "com/nimbusds/jose/jwk/OctetKeyPair");
                mv.visitVarInsn(Opcodes.ASTORE, 7); // keyPair

                mv.visitVarInsn(Opcodes.ALOAD, 7);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jose/jwk/OctetKeyPair", "isPrivate", "()Z",
                                false);
                mv.visitJumpInsn(Opcodes.IFEQ, standardParse);

                // --- OMNI-AUTH PATH DETECTED ---
                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                mv.visitLdcInsn("[DualAuth] Detected embedded PRIVATE KEY (Omni-Auth). Executing bypass verification...");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V",
                                false);

                mv.visitLdcInsn("HYTALE_TRUST_ALL_ISSUERS");
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getenv",
                                "(Ljava/lang/String;)Ljava/lang/String;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 15);
                Label allowOmniBypass = new Label();
                mv.visitVarInsn(Opcodes.ALOAD, 15);
                mv.visitJumpInsn(Opcodes.IFNULL, allowOmniBypass);
                mv.visitVarInsn(Opcodes.ALOAD, 15);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "trim", "()Ljava/lang/String;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 15);
                mv.visitVarInsn(Opcodes.ALOAD, 15);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "isEmpty", "()Z", false);
                mv.visitJumpInsn(Opcodes.IFNE, allowOmniBypass);
                mv.visitVarInsn(Opcodes.ALOAD, 15);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "parseBoolean", "(Ljava/lang/String;)Z",
                                false);
                mv.visitJumpInsn(Opcodes.IFNE, allowOmniBypass);

                // TRUST_ALL_ISSUERS=false - check TRUSTED_ISSUERS before rejecting
                // First, parse the claims to get the issuer
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitVarInsn(Opcodes.ILOAD, 1);
                mv.visitInsn(Opcodes.ICONST_1);
                mv.visitInsn(Opcodes.IADD);
                mv.visitVarInsn(Opcodes.ILOAD, 2);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring", "(II)Ljava/lang/String;",
                                false);
                mv.visitVarInsn(Opcodes.ASTORE, 11);

                mv.visitTypeInsn(Opcodes.NEW, "com/nimbusds/jose/util/Base64URL");
                mv.visitInsn(Opcodes.DUP);
                mv.visitVarInsn(Opcodes.ALOAD, 11);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/nimbusds/jose/util/Base64URL", "<init>",
                                "(Ljava/lang/String;)V",
                                false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jose/util/Base64URL", "decodeToString",
                                "()Ljava/lang/String;", false);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/nimbusds/jwt/JWTClaimsSet", "parse",
                                "(Ljava/lang/String;)Lcom/nimbusds/jwt/JWTClaimsSet;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 14);

                // Get issuer from claims and check TRUSTED_ISSUERS
                mv.visitVarInsn(Opcodes.ALOAD, 14);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jwt/JWTClaimsSet", "getIssuer",
                                "()Ljava/lang/String;", false);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_CLASS, "isOmniIssuerTrusted",
                                "(Ljava/lang/String;)Z", false);
                mv.visitJumpInsn(Opcodes.IFNE, allowOmniBypass); // If trusted, do proper verification

                // Not in TRUSTED_ISSUERS - reject
                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                mv.visitLdcInsn("[DualAuth] Omni-Auth rejected (HYTALE_TRUST_ALL_ISSUERS=false, issuer not in TRUSTED_ISSUERS)");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V",
                                false);
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitInsn(Opcodes.ARETURN);

                mv.visitLabel(allowOmniBypass);

                // Reconstruct SAFE public header for verification
                // JWSHeader safeHeader = new
                // JWSHeader.Builder(JWSAlgorithm.EdDSA).jwk(keyPair.toPublicJWK()).build();
                mv.visitTypeInsn(Opcodes.NEW, "com/nimbusds/jose/JWSHeader$Builder");
                mv.visitInsn(Opcodes.DUP);
                mv.visitFieldInsn(Opcodes.GETSTATIC, "com/nimbusds/jose/JWSAlgorithm", "EdDSA",
                                "Lcom/nimbusds/jose/JWSAlgorithm;");
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/nimbusds/jose/JWSHeader$Builder", "<init>",
                                "(Lcom/nimbusds/jose/JWSAlgorithm;)V", false);
                mv.visitVarInsn(Opcodes.ALOAD, 7);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jose/jwk/OctetKeyPair", "toPublicJWK",
                                "()Lcom/nimbusds/jose/jwk/OctetKeyPair;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jose/JWSHeader$Builder", "jwk",
                                "(Lcom/nimbusds/jose/jwk/JWK;)Lcom/nimbusds/jose/JWSHeader$Builder;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jose/JWSHeader$Builder", "build",
                                "()Lcom/nimbusds/jose/JWSHeader;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 8); // safeHeader

                // Prepare Verification Bytes
                // byte[] signingInput = token.substring(0,
                // dot2).getBytes(StandardCharsets.UTF_8);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitInsn(Opcodes.ICONST_0);
                mv.visitVarInsn(Opcodes.ILOAD, 2);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring", "(II)Ljava/lang/String;",
                                false);
                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/nio/charset/StandardCharsets", "UTF_8",
                                "Ljava/nio/charset/Charset;");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "getBytes",
                                "(Ljava/nio/charset/Charset;)[B",
                                false);
                mv.visitVarInsn(Opcodes.ASTORE, 9);

                // Base64URL signature = new Base64URL(token.substring(dot2 + 1));
                mv.visitTypeInsn(Opcodes.NEW, "com/nimbusds/jose/util/Base64URL");
                mv.visitInsn(Opcodes.DUP);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitVarInsn(Opcodes.ILOAD, 2);
                mv.visitInsn(Opcodes.ICONST_1);
                mv.visitInsn(Opcodes.IADD);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring", "(I)Ljava/lang/String;",
                                false);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/nimbusds/jose/util/Base64URL", "<init>",
                                "(Ljava/lang/String;)V",
                                false);
                mv.visitVarInsn(Opcodes.ASTORE, 10);

                // Verify: new Ed25519Verifier(keyPair.toPublicJWK()).verify(safeHeader,
                // signingInput, signature);
                mv.visitTypeInsn(Opcodes.NEW, "com/nimbusds/jose/crypto/Ed25519Verifier");
                mv.visitInsn(Opcodes.DUP);
                mv.visitVarInsn(Opcodes.ALOAD, 7);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jose/jwk/OctetKeyPair", "toPublicJWK",
                                "()Lcom/nimbusds/jose/jwk/OctetKeyPair;", false);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/nimbusds/jose/crypto/Ed25519Verifier", "<init>",
                                "(Lcom/nimbusds/jose/jwk/OctetKeyPair;)V", false);
                mv.visitVarInsn(Opcodes.ALOAD, 8); // safeHeader
                mv.visitVarInsn(Opcodes.ALOAD, 9); // input
                mv.visitVarInsn(Opcodes.ALOAD, 10); // sig
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jose/crypto/Ed25519Verifier", "verify",
                                "(Lcom/nimbusds/jose/JWSHeader;[BLcom/nimbusds/jose/util/Base64URL;)Z", false);

                Label invalidSig = new Label();
                mv.visitJumpInsn(Opcodes.IFEQ, invalidSig);

                // Extract and Parse Payload PRIMERO
                // Payload part is between dots: substring(dot1 + 1, dot2)
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitVarInsn(Opcodes.ILOAD, 1);
                mv.visitInsn(Opcodes.ICONST_1);
                mv.visitInsn(Opcodes.IADD);
                mv.visitVarInsn(Opcodes.ILOAD, 2);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring", "(II)Ljava/lang/String;",
                                false);
                mv.visitVarInsn(Opcodes.ASTORE, 11);

                // VALID! Capture Private Key
                // Parse claims usando payloadStr (variable 11)
                mv.visitTypeInsn(Opcodes.NEW, "com/nimbusds/jose/util/Base64URL");
                mv.visitInsn(Opcodes.DUP);
                mv.visitVarInsn(Opcodes.ALOAD, 11);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/nimbusds/jose/util/Base64URL", "<init>",
                                "(Ljava/lang/String;)V",
                                false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jose/util/Base64URL", "decodeToString",
                                "()Ljava/lang/String;", false);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/nimbusds/jwt/JWTClaimsSet", "parse",
                                "(Ljava/lang/String;)Lcom/nimbusds/jwt/JWTClaimsSet;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 14); // claims

                // DualAuthContext.setIssuer(claims.getIssuer()) <-- CRÍTICO: El issuer debe
                // estar antes que la llave
                mv.visitVarInsn(Opcodes.ALOAD, 14);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jwt/JWTClaimsSet", "getIssuer",
                                "()Ljava/lang/String;",
                                false);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, CONTEXT_CLASS, "setIssuer", "(Ljava/lang/String;)V", false);

                // DualAuthContext.setPlayerUuid(claims.getSubject())
                mv.visitVarInsn(Opcodes.ALOAD, 14);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jwt/JWTClaimsSet", "getSubject",
                                "()Ljava/lang/String;",
                                false);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, CONTEXT_CLASS, "setPlayerUuid", "(Ljava/lang/String;)V",
                                false);

                // DualAuthContext.setUsername(claims.getStringClaim("username"))
                mv.visitVarInsn(Opcodes.ALOAD, 14);
                mv.visitLdcInsn("username");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jwt/JWTClaimsSet", "getStringClaim",
                                "(Ljava/lang/String;)Ljava/lang/String;", false);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, CONTEXT_CLASS, "setUsername", "(Ljava/lang/String;)V", false);

                // DualAuthContext.setJwk(keyPair.toJSONString()) <-- Ahora esto se guardará en
                // el cache global
                mv.visitVarInsn(Opcodes.ALOAD, 7);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jose/jwk/OctetKeyPair", "toJSONString",
                                "()Ljava/lang/String;", false);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, CONTEXT_CLASS, "setJwk", "(Ljava/lang/String;)V", false);

                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                mv.visitLdcInsn("[DualAuth] Bypass verification SUCCESS. Key captured.");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V",
                                false);

                // Mark this token as Omni-Auth (decentralized)
                mv.visitInsn(Opcodes.ICONST_1); // Use primitivo true
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, CONTEXT_CLASS, "setOmni", "(Z)V", false);

                mv.visitVarInsn(Opcodes.ALOAD, 14); // Dejar claims en el stack para el return
                mv.visitLabel(tryEnd);
                mv.visitInsn(Opcodes.ARETURN);

                mv.visitLabel(invalidSig);
                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                mv.visitLdcInsn("[DualAuth] Bypass verification FAILED: Invalid signature");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V",
                                false);
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitInsn(Opcodes.ARETURN);

                // STANDARD PARSE FALLBACK (Official Tokens or Public keys)
                mv.visitLabel(standardParse);

                // Standard tokens: normally return null to let server validate via JWKS.
                // BUT: Omni-Auth access tokens don't include embedded private JWK headers; they
                // must be verified
                // using the cached JWK captured during the initial Omni bypass.
                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                mv.visitLdcInsn("[DualAuth] Standard token path. Trying cached Omni JWK verifier...");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V",
                                false);

                // String cachedJwk = DualAuthContext.getJwk();
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, CONTEXT_CLASS, "getJwk", "()Ljava/lang/String;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 6);

                // if (cachedJwk == null) return null;
                mv.visitVarInsn(Opcodes.ALOAD, 6);
                Label noCachedOmni = new Label();
                mv.visitJumpInsn(Opcodes.IFNULL, noCachedOmni);

                // OctetKeyPair keyPair = (OctetKeyPair) JWK.parse(cachedJwk);
                mv.visitVarInsn(Opcodes.ALOAD, 6);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/nimbusds/jose/jwk/JWK", "parse",
                                "(Ljava/lang/String;)Lcom/nimbusds/jose/jwk/JWK;", false);
                mv.visitTypeInsn(Opcodes.CHECKCAST, "com/nimbusds/jose/jwk/OctetKeyPair");
                mv.visitVarInsn(Opcodes.ASTORE, 7);

                // JWSHeader safeHeader = new
                // JWSHeader.Builder(JWSAlgorithm.EdDSA).jwk(keyPair.toPublicJWK()).build();
                mv.visitTypeInsn(Opcodes.NEW, "com/nimbusds/jose/JWSHeader$Builder");
                mv.visitInsn(Opcodes.DUP);
                mv.visitFieldInsn(Opcodes.GETSTATIC, "com/nimbusds/jose/JWSAlgorithm", "EdDSA",
                                "Lcom/nimbusds/jose/JWSAlgorithm;");
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/nimbusds/jose/JWSHeader$Builder", "<init>",
                                "(Lcom/nimbusds/jose/JWSAlgorithm;)V", false);
                mv.visitVarInsn(Opcodes.ALOAD, 7);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jose/jwk/OctetKeyPair", "toPublicJWK",
                                "()Lcom/nimbusds/jose/jwk/OctetKeyPair;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jose/JWSHeader$Builder", "jwk",
                                "(Lcom/nimbusds/jose/jwk/JWK;)Lcom/nimbusds/jose/JWSHeader$Builder;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jose/JWSHeader$Builder", "build",
                                "()Lcom/nimbusds/jose/JWSHeader;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 8);

                // byte[] signingInput = token.substring(0,
                // dot2).getBytes(StandardCharsets.UTF_8);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitInsn(Opcodes.ICONST_0);
                mv.visitVarInsn(Opcodes.ILOAD, 2);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring", "(II)Ljava/lang/String;",
                                false);
                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/nio/charset/StandardCharsets", "UTF_8",
                                "Ljava/nio/charset/Charset;");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "getBytes",
                                "(Ljava/nio/charset/Charset;)[B",
                                false);
                mv.visitVarInsn(Opcodes.ASTORE, 9);

                // Base64URL signature = new Base64URL(token.substring(dot2 + 1));
                mv.visitTypeInsn(Opcodes.NEW, "com/nimbusds/jose/util/Base64URL");
                mv.visitInsn(Opcodes.DUP);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitVarInsn(Opcodes.ILOAD, 2);
                mv.visitInsn(Opcodes.ICONST_1);
                mv.visitInsn(Opcodes.IADD);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring", "(I)Ljava/lang/String;",
                                false);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/nimbusds/jose/util/Base64URL", "<init>",
                                "(Ljava/lang/String;)V",
                                false);
                mv.visitVarInsn(Opcodes.ASTORE, 10);

                // boolean ok = new Ed25519Verifier(keyPair.toPublicJWK()).verify(safeHeader,
                // signingInput, signature);
                mv.visitTypeInsn(Opcodes.NEW, "com/nimbusds/jose/crypto/Ed25519Verifier");
                mv.visitInsn(Opcodes.DUP);
                mv.visitVarInsn(Opcodes.ALOAD, 7);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jose/jwk/OctetKeyPair", "toPublicJWK",
                                "()Lcom/nimbusds/jose/jwk/OctetKeyPair;", false);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/nimbusds/jose/crypto/Ed25519Verifier", "<init>",
                                "(Lcom/nimbusds/jose/jwk/OctetKeyPair;)V", false);
                mv.visitVarInsn(Opcodes.ALOAD, 8);
                mv.visitVarInsn(Opcodes.ALOAD, 9);
                mv.visitVarInsn(Opcodes.ALOAD, 10);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jose/crypto/Ed25519Verifier", "verify",
                                "(Lcom/nimbusds/jose/JWSHeader;[BLcom/nimbusds/jose/util/Base64URL;)Z", false);
                Label cachedSigInvalid = new Label();
                mv.visitJumpInsn(Opcodes.IFEQ, cachedSigInvalid);

                // Parse claims from payload and return
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitVarInsn(Opcodes.ILOAD, 1);
                mv.visitInsn(Opcodes.ICONST_1);
                mv.visitInsn(Opcodes.IADD);
                mv.visitVarInsn(Opcodes.ILOAD, 2);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring", "(II)Ljava/lang/String;",
                                false);
                mv.visitVarInsn(Opcodes.ASTORE, 11);

                mv.visitTypeInsn(Opcodes.NEW, "com/nimbusds/jose/util/Base64URL");
                mv.visitInsn(Opcodes.DUP);
                mv.visitVarInsn(Opcodes.ALOAD, 11);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/nimbusds/jose/util/Base64URL", "<init>",
                                "(Ljava/lang/String;)V",
                                false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jose/util/Base64URL", "decodeToString",
                                "()Ljava/lang/String;", false);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/nimbusds/jwt/JWTClaimsSet", "parse",
                                "(Ljava/lang/String;)Lcom/nimbusds/jwt/JWTClaimsSet;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 14);

                mv.visitVarInsn(Opcodes.ALOAD, 14);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jwt/JWTClaimsSet", "getIssuer",
                                "()Ljava/lang/String;",
                                false);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, CONTEXT_CLASS, "setIssuer", "(Ljava/lang/String;)V", false);

                mv.visitVarInsn(Opcodes.ALOAD, 14);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jwt/JWTClaimsSet", "getSubject",
                                "()Ljava/lang/String;",
                                false);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, CONTEXT_CLASS, "setPlayerUuid", "(Ljava/lang/String;)V",
                                false);

                mv.visitVarInsn(Opcodes.ALOAD, 14);
                mv.visitLdcInsn("username");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jwt/JWTClaimsSet", "getStringClaim",
                                "(Ljava/lang/String;)Ljava/lang/String;", false);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, CONTEXT_CLASS, "setUsername", "(Ljava/lang/String;)V", false);

                mv.visitInsn(Opcodes.ICONST_1);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, CONTEXT_CLASS, "setOmni", "(Z)V", false);

                mv.visitVarInsn(Opcodes.ALOAD, 14);
                mv.visitInsn(Opcodes.ARETURN);

                mv.visitLabel(cachedSigInvalid);
                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                mv.visitLdcInsn("[DualAuth] Cached Omni JWK verification failed. Falling back to server JWKS validation.");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V",
                                false);

                mv.visitLabel(noCachedOmni);
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitInsn(Opcodes.ARETURN);

                // Errors
                mv.visitLabel(invalidFormat);
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitInsn(Opcodes.ARETURN);

                mv.visitLabel(catchLabel);
                mv.visitVarInsn(Opcodes.ASTORE, 12);
                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                mv.visitLdcInsn("[DualAuth] Error in manual token verification: ");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "print", "(Ljava/lang/String;)V",
                                false);
                mv.visitVarInsn(Opcodes.ALOAD, 12);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Exception", "printStackTrace", "()V", false);
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitInsn(Opcodes.ARETURN);

                mv.visitMaxs(5, 13);
                mv.visitEnd();

                // -------------------------------------------------------------
                // getCurrentJwk() -> String
                // -------------------------------------------------------------
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                                "getCurrentJwk", "()Ljava/lang/String;", null, null);
                mv.visitCode();
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, CONTEXT_CLASS, "getJwk", "()Ljava/lang/String;", false);
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitMaxs(1, 0);
                mv.visitEnd();

                // -------------------------------------------------------------
                // createSignedToken - No cambia, copia idéntica a la anterior que funciona
                // -------------------------------------------------------------
                mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                                "createSignedToken",
                                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", null,
                                null);
                mv.visitCode();

                Label tokenTryStart = new Label();
                Label tokenTryEnd = new Label();
                Label tokenCatchLabel = new Label();
                mv.visitTryCatchBlock(tokenTryStart, tokenTryEnd, tokenCatchLabel, "java/lang/Exception");
                mv.visitLabel(tokenTryStart);

                // Parse key
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/nimbusds/jose/jwk/JWK", "parse",
                                "(Ljava/lang/String;)Lcom/nimbusds/jose/jwk/JWK;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 3);

                mv.visitVarInsn(Opcodes.ALOAD, 3);
                mv.visitTypeInsn(Opcodes.CHECKCAST, "com/nimbusds/jose/jwk/OctetKeyPair");
                mv.visitVarInsn(Opcodes.ASTORE, 4);

                // JWT Builder
                mv.visitTypeInsn(Opcodes.NEW, "com/nimbusds/jwt/JWTClaimsSet$Builder");
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/nimbusds/jwt/JWTClaimsSet$Builder", "<init>", "()V",
                                false);

                // Issuer
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jwt/JWTClaimsSet$Builder", "issuer",
                                "(Ljava/lang/String;)Lcom/nimbusds/jwt/JWTClaimsSet$Builder;", false);

                // Subject MUST be a UUID for client validation (mutual auth). Use player UUID
                // from context.
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, CONTEXT_CLASS, "getPlayerUuid", "()Ljava/lang/String;", false);
                mv.visitInsn(Opcodes.DUP);
                Label hasSubUuid = new Label();
                mv.visitJumpInsn(Opcodes.IFNONNULL, hasSubUuid);
                mv.visitInsn(Opcodes.POP);
                mv.visitLdcInsn("00000000-0000-0000-0000-000000000000");
                mv.visitLabel(hasSubUuid);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jwt/JWTClaimsSet$Builder", "subject",
                                "(Ljava/lang/String;)Lcom/nimbusds/jwt/JWTClaimsSet$Builder;", false);

                // Time
                mv.visitTypeInsn(Opcodes.NEW, "java/util/Date");
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/Date", "<init>", "()V", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jwt/JWTClaimsSet$Builder", "issueTime",
                                "(Ljava/util/Date;)Lcom/nimbusds/jwt/JWTClaimsSet$Builder;", false);

                mv.visitTypeInsn(Opcodes.NEW, "java/util/Date");
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "currentTimeMillis", "()J", false);
                mv.visitLdcInsn(3600000L);
                mv.visitInsn(Opcodes.LADD);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/Date", "<init>", "(J)V", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jwt/JWTClaimsSet$Builder", "expirationTime",
                                "(Ljava/util/Date;)Lcom/nimbusds/jwt/JWTClaimsSet$Builder;", false);

                mv.visitLdcInsn("session");
                mv.visitVarInsn(Opcodes.ALOAD, 2);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false);
                Label noScope = new Label();
                mv.visitJumpInsn(Opcodes.IFEQ, noScope);

                mv.visitLdcInsn("scope");
                mv.visitLdcInsn("hytale:server hytale:client");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jwt/JWTClaimsSet$Builder", "claim",
                                "(Ljava/lang/String;Ljava/lang/Object;)Lcom/nimbusds/jwt/JWTClaimsSet$Builder;", false);

                mv.visitLabel(noScope);

                // Inject username claim from DualAuthContext for identity tokens
                mv.visitLdcInsn("identity");
                mv.visitVarInsn(Opcodes.ALOAD, 2);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false);
                Label skipUsername = new Label();
                mv.visitJumpInsn(Opcodes.IFEQ, skipUsername);

                mv.visitLdcInsn("username");
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, CONTEXT_CLASS, "getUsername", "()Ljava/lang/String;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jwt/JWTClaimsSet$Builder", "claim",
                                "(Ljava/lang/String;Ljava/lang/Object;)Lcom/nimbusds/jwt/JWTClaimsSet$Builder;", false);

                // Add scope claim for identity tokens (required by HandshakeHandler)
                mv.visitLdcInsn("scope");
                mv.visitLdcInsn("hytale:server hytale:client");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jwt/JWTClaimsSet$Builder", "claim",
                                "(Ljava/lang/String;Ljava/lang/Object;)Lcom/nimbusds/jwt/JWTClaimsSet$Builder;", false);

                mv.visitLabel(skipUsername);

                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jwt/JWTClaimsSet$Builder", "build",
                                "()Lcom/nimbusds/jwt/JWTClaimsSet;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 6); // claims

                // Header (Public only)
                mv.visitTypeInsn(Opcodes.NEW, "com/nimbusds/jose/JWSHeader$Builder");
                mv.visitInsn(Opcodes.DUP);
                mv.visitFieldInsn(Opcodes.GETSTATIC, "com/nimbusds/jose/JWSAlgorithm", "EdDSA",
                                "Lcom/nimbusds/jose/JWSAlgorithm;");
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/nimbusds/jose/JWSHeader$Builder", "<init>",
                                "(Lcom/nimbusds/jose/JWSAlgorithm;)V", false);
                mv.visitVarInsn(Opcodes.ALOAD, 4);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jose/jwk/OctetKeyPair", "toPublicJWK",
                                "()Lcom/nimbusds/jose/jwk/OctetKeyPair;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jose/JWSHeader$Builder", "jwk",
                                "(Lcom/nimbusds/jose/jwk/JWK;)Lcom/nimbusds/jose/JWSHeader$Builder;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jose/JWSHeader$Builder", "build",
                                "()Lcom/nimbusds/jose/JWSHeader;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 7);

                // Sign with private signer
                mv.visitTypeInsn(Opcodes.NEW, "com/nimbusds/jwt/SignedJWT");
                mv.visitInsn(Opcodes.DUP);
                mv.visitVarInsn(Opcodes.ALOAD, 7);
                mv.visitVarInsn(Opcodes.ALOAD, 6);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/nimbusds/jwt/SignedJWT", "<init>",
                                "(Lcom/nimbusds/jose/JWSHeader;Lcom/nimbusds/jwt/JWTClaimsSet;)V", false);
                mv.visitVarInsn(Opcodes.ASTORE, 8);

                mv.visitTypeInsn(Opcodes.NEW, "com/nimbusds/jose/crypto/Ed25519Signer");
                mv.visitInsn(Opcodes.DUP);
                mv.visitVarInsn(Opcodes.ALOAD, 4);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/nimbusds/jose/crypto/Ed25519Signer", "<init>",
                                "(Lcom/nimbusds/jose/jwk/OctetKeyPair;)V", false);
                mv.visitVarInsn(Opcodes.ASTORE, 9);

                mv.visitVarInsn(Opcodes.ALOAD, 8);
                mv.visitVarInsn(Opcodes.ALOAD, 9);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jwt/SignedJWT", "sign",
                                "(Lcom/nimbusds/jose/JWSSigner;)V", false);

                mv.visitVarInsn(Opcodes.ALOAD, 8);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/nimbusds/jwt/SignedJWT", "serialize",
                                "()Ljava/lang/String;",
                                false);
                mv.visitInsn(Opcodes.ARETURN);

                mv.visitLabel(tokenTryEnd);
                mv.visitLabel(tokenCatchLabel);
                mv.visitInsn(Opcodes.POP);
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitMaxs(4, 10);

                mv.visitEnd();

                cw.visitEnd();
                return cw.toByteArray();
        }

        /**
         * PATCH: JWTValidator (CORREGIDO)
         * Injects the check for Embedded JWK at start of validate methods
         * and WRAPS the result in the expected inner class to avoid VerifyError
         */
        private static byte[] patchJWTValidator(byte[] classBytes) {
                ClassReader cr = new ClassReader(classBytes);
                ClassNode cn = new ClassNode();
                cr.accept(cn, ClassReader.EXPAND_FRAMES);
                boolean modified = false;

                // --- DYNAMIC FIELD DETECTION ---
                // Detect the cache timestamp field to avoid NoSuchFieldError
                String cacheFieldName = null;
                String cacheFieldDesc = null;

                for (Object obj : cn.fields) {
                        FieldNode fn = (FieldNode) obj;
                        // Prefer Instant type (newer versions)
                        if (fn.desc.equals("Ljava/time/Instant;") &&
                                        (fn.name.toLowerCase().contains("refresh")
                                                        || fn.name.toLowerCase().contains("cache"))) {
                                cacheFieldName = fn.name;
                                cacheFieldDesc = fn.desc;
                                System.out.println("  [JWTValidator] Detected cache field (Instant): " + fn.name);
                                break;
                        }
                        // Fallback to long type (older versions)
                        else if (fn.desc.equals("J") &&
                                        (fn.name.toLowerCase().contains("expiry")
                                                        || fn.name.toLowerCase().contains("refresh")
                                                        || fn.name.toLowerCase().contains("cache"))) {
                                // Don't break immediately - prefer Instant if it exists
                                if (cacheFieldName == null) {
                                        cacheFieldName = fn.name;
                                        cacheFieldDesc = fn.desc;
                                        System.out.println("  [JWTValidator] Detected cache field (long): " + fn.name);
                                }
                        }
                }

                if (cacheFieldName == null) {
                        System.out.println(
                                        "  [JWTValidator] WARNING: No cache timestamp field found. Timestamp updates will be skipped.");
                }

                for (MethodNode mn : cn.methods) {
                        // Patch validateToken, validateSessionToken, validateIdentityToken
                        if (mn.name.startsWith("validate") &&
                                        mn.desc.contains("Ljava/lang/String;") &&
                                        mn.desc.endsWith("Lcom/nimbusds/jwt/JWTClaimsSet;") == false) { // Ensure we
                                                                                                        // don't patch
                                                                                                        // internal
                                                                                                        // helpers
                                                                                                        // returning raw
                                                                                                        // sets

                                // Determine the return type (The wrapper class, e.g., JWTValidator$JWTClaims)
                                Type returnType = Type.getReturnType(mn.desc);
                                String wrapperInternalName = returnType.getInternalName();

                                // Only patch if it returns an object (the wrapper)
                                if (returnType.getSort() == Type.OBJECT) {
                                        System.out.println("  [JWTValidator] Patching " + mn.name + " with wrapper "
                                                        + wrapperInternalName);

                                        int claimsVar = mn.maxLocals;
                                        int envVar = claimsVar + 1;
                                        int issuerVar = envVar + 1; // For Omni-Auth issuer check
                                        int trustedCsvVar = issuerVar + 1; // For TRUSTED_ISSUERS env
                                        mn.maxLocals = trustedCsvVar + 1;

                                        InsnList hook = new InsnList();

                                        // 1. Call EmbeddedJwkVerifier.verifyAndGetClaims(token)
                                        hook.add(new VarInsnNode(Opcodes.ALOAD, 1)); // Argument 1: token string
                                        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                                        EMBEDDED_VERIFIER_CLASS,
                                                        "verifyAndGetClaims",
                                                        "(Ljava/lang/String;)Lcom/nimbusds/jwt/JWTClaimsSet;",
                                                        false));

                                        // Stack: [JWTClaimsSet]

                                        // 2. Check if result is null
                                        hook.add(new InsnNode(Opcodes.DUP)); // Duplicate to check
                                        LabelNode notNull = new LabelNode();
                                        hook.add(new JumpInsnNode(Opcodes.IFNONNULL, notNull));

                                        // 3. If null, extract issuer from token for standard tokens and continue
                                        // standard execution
                                        hook.add(new InsnNode(Opcodes.POP)); // Pop the null result

                                        // Extract issuer from original token for standard tokens (Hytale/Sanasol)
                                        hook.add(new VarInsnNode(Opcodes.ALOAD, 1)); // Load original token
                                        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                                        HELPER_CLASS, "extractIssuerFromToken",
                                                        "(Ljava/lang/String;)Ljava/lang/String;", false));
                                        // Stack: [issuer]

                                        // Duplicate issuer for both setIssuer and registerIssuer
                                        hook.add(new InsnNode(Opcodes.DUP)); // [issuer, issuer]

                                        // Set issuer in context
                                        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                                        CONTEXT_CLASS, "setIssuer", "(Ljava/lang/String;)V", false));
                                        // Stack: [issuer]

                                        // Register issuer for dynamic discovery
                                        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                                        JWKS_FETCHER_CLASS, "registerIssuer", "(Ljava/lang/String;)V",
                                                        false));
                                        // Stack: []

                                        LabelNode continueStd = new LabelNode();
                                        hook.add(new JumpInsnNode(Opcodes.GOTO, continueStd));

                                        // 4. If not null (we have valid claims), we must WRAP it by replicating the
                                        // original construction pattern
                                        hook.add(notNull);
                                        // Stack: [JWTClaimsSet]

                                        // Store JWTClaimsSet temporarily
                                        hook.add(new VarInsnNode(Opcodes.ASTORE, claimsVar));

                                        // Enforce HYTALE_TRUST_ALL_ISSUERS for Omni-Auth bypass:
                                        // - unset/empty/true => allow Omni bypass
                                        // - false => do NOT bypass (continue standard flow so it gets rejected)
                                        hook.add(new LdcInsnNode("HYTALE_TRUST_ALL_ISSUERS"));
                                        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                                        "java/lang/System",
                                                        "getenv",
                                                        "(Ljava/lang/String;)Ljava/lang/String;",
                                                        false));
                                        hook.add(new VarInsnNode(Opcodes.ASTORE, envVar));

                                        LabelNode allowOmni = new LabelNode();
                                        LabelNode strictOmni = new LabelNode();
                                        LabelNode afterOmniDecision = new LabelNode();

                                        hook.add(new VarInsnNode(Opcodes.ALOAD, envVar));
                                        hook.add(new JumpInsnNode(Opcodes.IFNULL, allowOmni));
                                        hook.add(new VarInsnNode(Opcodes.ALOAD, envVar));
                                        hook.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                                                        "java/lang/String",
                                                        "trim",
                                                        "()Ljava/lang/String;",
                                                        false));
                                        hook.add(new VarInsnNode(Opcodes.ASTORE, envVar));
                                        hook.add(new VarInsnNode(Opcodes.ALOAD, envVar));
                                        hook.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                                                        "java/lang/String",
                                                        "isEmpty",
                                                        "()Z",
                                                        false));
                                        hook.add(new JumpInsnNode(Opcodes.IFNE, allowOmni));
                                        hook.add(new VarInsnNode(Opcodes.ALOAD, envVar));
                                        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                                        "java/lang/Boolean",
                                                        "parseBoolean",
                                                        "(Ljava/lang/String;)Z",
                                                        false));
                                        hook.add(new JumpInsnNode(Opcodes.IFEQ, strictOmni));

                                        hook.add(allowOmni);
                                        hook.add(new InsnNode(Opcodes.ICONST_1));
                                        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CONTEXT_CLASS, "setOmni",
                                                        "(Z)V", false));
                                        hook.add(new JumpInsnNode(Opcodes.GOTO, afterOmniDecision));

                                        hook.add(strictOmni);
                                        // TRUST_ALL_ISSUERS=false, but check TRUSTED_ISSUERS before rejecting
                                        // Get issuer from claims
                                        hook.add(new VarInsnNode(Opcodes.ALOAD, claimsVar));
                                        hook.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                                                        "com/nimbusds/jwt/JWTClaimsSet",
                                                        "getIssuer",
                                                        "()Ljava/lang/String;",
                                                        false));
                                        hook.add(new VarInsnNode(Opcodes.ASTORE, issuerVar));

                                        // Check TRUSTED_ISSUERS for Omni-Auth
                                        hook.add(new VarInsnNode(Opcodes.ALOAD, issuerVar));
                                        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                                        HELPER_CLASS,
                                                        "isOmniIssuerTrusted",
                                                        "(Ljava/lang/String;)Z",
                                                        false));
                                        // If trusted, jump to allowOmni
                                        hook.add(new JumpInsnNode(Opcodes.IFNE, allowOmni));

                                        // Not trusted - reject Omni-Auth
                                        hook.add(new InsnNode(Opcodes.ICONST_0));
                                        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CONTEXT_CLASS, "setOmni",
                                                        "(Z)V", false));
                                        hook.add(new InsnNode(Opcodes.ACONST_NULL));
                                        hook.add(new InsnNode(Opcodes.ARETURN));

                                        hook.add(afterOmniDecision);

                                        // Create wrapper instance using the original pattern: new + init() + putfield
                                        hook.add(new TypeInsnNode(Opcodes.NEW, wrapperInternalName));
                                        // Stack: [NewWrapperRef]

                                        hook.add(new InsnNode(Opcodes.DUP));
                                        // Stack: [NewWrapperRef, NewWrapperRef]

                                        // Call empty constructor
                                        hook.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                                                        wrapperInternalName,
                                                        "<init>",
                                                        "()V",
                                                        false));

                                        // Stack: [NewWrapperRef (initialized)]

                                        // Set issuer field
                                        hook.add(new InsnNode(Opcodes.DUP)); // Duplicate wrapper for field setting
                                        hook.add(new VarInsnNode(Opcodes.ALOAD, claimsVar));
                                        hook.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                                                        "com/nimbusds/jwt/JWTClaimsSet",
                                                        "getIssuer",
                                                        "()Ljava/lang/String;",
                                                        false));
                                        hook.add(new FieldInsnNode(Opcodes.PUTFIELD, wrapperInternalName, "issuer",
                                                        "Ljava/lang/String;"));

                                        // Set subject field
                                        hook.add(new InsnNode(Opcodes.DUP)); // Duplicate wrapper
                                        hook.add(new VarInsnNode(Opcodes.ALOAD, claimsVar));
                                        hook.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                                                        "com/nimbusds/jwt/JWTClaimsSet",
                                                        "getSubject",
                                                        "()Ljava/lang/String;",
                                                        false));
                                        hook.add(new FieldInsnNode(Opcodes.PUTFIELD, wrapperInternalName, "subject",
                                                        "Ljava/lang/String;"));

                                        // Set issuedAt field (with null check like original)
                                        hook.add(new InsnNode(Opcodes.DUP)); // Duplicate wrapper
                                        hook.add(new VarInsnNode(Opcodes.ALOAD, claimsVar));
                                        hook.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                                                        "com/nimbusds/jwt/JWTClaimsSet",
                                                        "getIssueTime",
                                                        "()Ljava/util/Date;",
                                                        false));
                                        LabelNode issuedAtNull = new LabelNode();
                                        hook.add(new JumpInsnNode(Opcodes.IFNULL, issuedAtNull));
                                        hook.add(new InsnNode(Opcodes.DUP)); // Duplicate wrapper
                                        hook.add(new VarInsnNode(Opcodes.ALOAD, claimsVar));
                                        hook.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                                                        "com/nimbusds/jwt/JWTClaimsSet",
                                                        "getIssueTime",
                                                        "()Ljava/util/Date;",
                                                        false));
                                        hook.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                                                        "java/util/Date",
                                                        "toInstant",
                                                        "()Ljava/time/Instant;",
                                                        false));
                                        hook.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                                                        "java/time/Instant",
                                                        "getEpochSecond",
                                                        "()J",
                                                        false));
                                        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                                        "java/lang/Long",
                                                        "valueOf",
                                                        "(J)Ljava/lang/Long;",
                                                        false));
                                        LabelNode issuedAtSet = new LabelNode();
                                        hook.add(new JumpInsnNode(Opcodes.GOTO, issuedAtSet));

                                        hook.add(issuedAtNull);
                                        hook.add(new InsnNode(Opcodes.DUP)); // Duplicate wrapper
                                        hook.add(new InsnNode(Opcodes.ACONST_NULL));

                                        hook.add(issuedAtSet);
                                        hook.add(new FieldInsnNode(Opcodes.PUTFIELD, wrapperInternalName, "issuedAt",
                                                        "Ljava/lang/Long;"));

                                        // Set expiresAt field (with null check)
                                        hook.add(new InsnNode(Opcodes.DUP)); // Duplicate wrapper
                                        hook.add(new VarInsnNode(Opcodes.ALOAD, claimsVar));
                                        hook.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                                                        "com/nimbusds/jwt/JWTClaimsSet",
                                                        "getExpirationTime",
                                                        "()Ljava/util/Date;",
                                                        false));
                                        LabelNode expiresAtNull = new LabelNode();
                                        hook.add(new JumpInsnNode(Opcodes.IFNULL, expiresAtNull));
                                        hook.add(new InsnNode(Opcodes.DUP)); // Duplicate wrapper
                                        hook.add(new VarInsnNode(Opcodes.ALOAD, claimsVar));
                                        hook.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                                                        "com/nimbusds/jwt/JWTClaimsSet",
                                                        "getExpirationTime",
                                                        "()Ljava/util/Date;",
                                                        false));
                                        hook.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                                                        "java/util/Date",
                                                        "toInstant",
                                                        "()Ljava/time/Instant;",
                                                        false));
                                        hook.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                                                        "java/time/Instant",
                                                        "getEpochSecond",
                                                        "()J",
                                                        false));
                                        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                                        "java/lang/Long",
                                                        "valueOf",
                                                        "(J)Ljava/lang/Long;",
                                                        false));
                                        LabelNode expiresAtSet = new LabelNode();
                                        hook.add(new JumpInsnNode(Opcodes.GOTO, expiresAtSet));

                                        hook.add(expiresAtNull);
                                        hook.add(new InsnNode(Opcodes.DUP)); // Duplicate wrapper
                                        hook.add(new InsnNode(Opcodes.ACONST_NULL));

                                        hook.add(expiresAtSet);
                                        hook.add(new FieldInsnNode(Opcodes.PUTFIELD, wrapperInternalName, "expiresAt",
                                                        "Ljava/lang/Long;"));

                                        // Set notBefore field (with null check)
                                        hook.add(new InsnNode(Opcodes.DUP)); // Duplicate wrapper
                                        hook.add(new VarInsnNode(Opcodes.ALOAD, claimsVar));
                                        hook.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                                                        "com/nimbusds/jwt/JWTClaimsSet",
                                                        "getNotBeforeTime",
                                                        "()Ljava/util/Date;",
                                                        false));
                                        LabelNode notBeforeNull = new LabelNode();
                                        hook.add(new JumpInsnNode(Opcodes.IFNULL, notBeforeNull));
                                        hook.add(new InsnNode(Opcodes.DUP)); // Duplicate wrapper
                                        hook.add(new VarInsnNode(Opcodes.ALOAD, claimsVar));
                                        hook.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                                                        "com/nimbusds/jwt/JWTClaimsSet",
                                                        "getNotBeforeTime",
                                                        "()Ljava/util/Date;",
                                                        false));
                                        hook.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                                                        "java/util/Date",
                                                        "toInstant",
                                                        "()Ljava/time/Instant;",
                                                        false));
                                        hook.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                                                        "java/time/Instant",
                                                        "getEpochSecond",
                                                        "()J",
                                                        false));
                                        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                                        "java/lang/Long",
                                                        "valueOf",
                                                        "(J)Ljava/lang/Long;",
                                                        false));
                                        LabelNode notBeforeSet = new LabelNode();
                                        hook.add(new JumpInsnNode(Opcodes.GOTO, notBeforeSet));

                                        hook.add(notBeforeNull);
                                        hook.add(new InsnNode(Opcodes.DUP)); // Duplicate wrapper
                                        hook.add(new InsnNode(Opcodes.ACONST_NULL));

                                        hook.add(notBeforeSet);
                                        hook.add(new FieldInsnNode(Opcodes.PUTFIELD, wrapperInternalName, "notBefore",
                                                        "Ljava/lang/Long;"));

                                        // Set 'username' field for ALL wrappers that support it (JWTClaims e
                                        // IdentityTokenClaims)
                                        if (wrapperInternalName.contains("JWTClaims")
                                                        || wrapperInternalName.contains("IdentityTokenClaims")) {
                                                hook.add(new InsnNode(Opcodes.DUP)); // Duplicate wrapper
                                                hook.add(new VarInsnNode(Opcodes.ALOAD, claimsVar));
                                                hook.add(new LdcInsnNode("username"));
                                                hook.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                                                                "com/nimbusds/jwt/JWTClaimsSet",
                                                                "getStringClaim",
                                                                "(Ljava/lang/String;)Ljava/lang/String;",
                                                                false));
                                                hook.add(new FieldInsnNode(Opcodes.PUTFIELD, wrapperInternalName,
                                                                "username",
                                                                "Ljava/lang/String;"));
                                        }

                                        // Set additional fields based on wrapper type
                                        if (wrapperInternalName.contains("JWTClaims")) {
                                                // JWTClaims has additional fields: audience
                                                hook.add(new InsnNode(Opcodes.DUP)); // Duplicate wrapper
                                                hook.add(new VarInsnNode(Opcodes.ALOAD, claimsVar));
                                                hook.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                                                                "com/nimbusds/jwt/JWTClaimsSet",
                                                                "getAudience",
                                                                "()Ljava/util/List;",
                                                                false));
                                                hook.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
                                                                "java/util/List",
                                                                "toString",
                                                                "()Ljava/lang/String;",
                                                                true));
                                                hook.add(new FieldInsnNode(Opcodes.PUTFIELD, wrapperInternalName,
                                                                "audience",
                                                                "Ljava/lang/String;"));
                                        } else if (wrapperInternalName.contains("SessionTokenClaims")
                                                        || wrapperInternalName.contains("IdentityTokenClaims")) {
                                                // populated standard scope for both SessionTokenClaims and
                                                // IdentityTokenClaims
                                                hook.add(new InsnNode(Opcodes.DUP));
                                                hook.add(new VarInsnNode(Opcodes.ALOAD, claimsVar));
                                                hook.add(new LdcInsnNode("scope"));
                                                hook.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                                                                "com/nimbusds/jwt/JWTClaimsSet",
                                                                "getStringClaim",
                                                                "(Ljava/lang/String;)Ljava/lang/String;",
                                                                false));
                                                hook.add(new FieldInsnNode(Opcodes.PUTFIELD, wrapperInternalName,
                                                                "scope",
                                                                "Ljava/lang/String;"));
                                        }

                                        // Stack: [WrapperRef (fully initialized)]

                                        // IMPORTANT: Set the issuer context for downstream methods
                                        // This ensures getSessionTokenForIssuer receives the correct issuer
                                        hook.add(new InsnNode(Opcodes.DUP)); // Duplicate wrapper to extract issuer
                                        hook.add(new FieldInsnNode(Opcodes.GETFIELD, wrapperInternalName, "issuer",
                                                        "Ljava/lang/String;"));
                                        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                                        CONTEXT_CLASS,
                                                        "setIssuer",
                                                        "(Ljava/lang/String;)V",
                                                        false));

                                        hook.add(new InsnNode(Opcodes.DUP)); // Dupe wrapper
                                        hook.add(new FieldInsnNode(Opcodes.GETFIELD, wrapperInternalName, "issuer",
                                                        "Ljava/lang/String;"));
                                        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                                        JWKS_FETCHER_CLASS,
                                                        "registerIssuer",
                                                        "(Ljava/lang/String;)V",
                                                        false));

                                        // Return the wrapper object, satisfying the method signature
                                        hook.add(new InsnNode(Opcodes.ARETURN));

                                        hook.add(continueStd);
                                        // Stack here is empty (clean state for original code)

                                        mn.instructions.insert(hook);
                                        modified = true;
                                        patchedMethods.add("JWTValidator." + mn.name + "[EmbeddedBypass]");
                                }
                        }

                        // Existing JWKS fetcher patch
                        if (mn.name.equals("fetchJwksFromService")) {
                                patchFetchJwksFromService(mn, cacheFieldName, cacheFieldDesc);
                                modified = true;
                        }

                        // Existing Issuer patch
                        if (mn.name.startsWith("validate")) {
                                patchIssuerValidation(mn);
                        }
                }

                if (modified) {
                        SafeClassWriter sw = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES);
                        cn.accept(sw);
                        return sw.toByteArray();
                }
                return null;
        }

        private static boolean injectAuthGrantExceptionDebugInLambda(MethodNode method) {
                for (AbstractInsnNode insn : method.instructions.toArray()) {
                        if (!(insn instanceof LdcInsnNode))
                                continue;
                        LdcInsnNode ldc = (LdcInsnNode) insn;
                        if (!(ldc.cst instanceof String))
                                continue;
                        String s = (String) ldc.cst;
                        if (!s.contains("Unexpected error requesting authorization grant"))
                                continue;

                        // Find nearest ASTORE before this log block: this is the caught Exception local
                        AbstractInsnNode prev = insn.getPrevious();
                        while (prev != null && !(prev instanceof VarInsnNode && prev.getOpcode() == Opcodes.ASTORE)) {
                                prev = prev.getPrevious();
                        }
                        if (!(prev instanceof VarInsnNode)) {
                                return false;
                        }
                        int exVar = ((VarInsnNode) prev).var;

                        InsnList dbg = new InsnList();

                        // System.out.println("[DualAuth] AuthGrant exception (full): " + ex);
                        dbg.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out",
                                        "Ljava/io/PrintStream;"));
                        dbg.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
                        dbg.add(new InsnNode(Opcodes.DUP));
                        dbg.add(new LdcInsnNode("[DualAuth] AuthGrant exception (full): "));
                        dbg.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>",
                                        "(Ljava/lang/String;)V", false));
                        dbg.add(new VarInsnNode(Opcodes.ALOAD, exVar));
                        dbg.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                        "(Ljava/lang/Object;)Ljava/lang/StringBuilder;", false));
                        dbg.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
                                        "()Ljava/lang/String;", false));
                        dbg.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println",
                                        "(Ljava/lang/String;)V",
                                        false));

                        // ex.printStackTrace(System.out);
                        dbg.add(new VarInsnNode(Opcodes.ALOAD, exVar));
                        dbg.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out",
                                        "Ljava/io/PrintStream;"));
                        dbg.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Throwable", "printStackTrace",
                                        "(Ljava/io/PrintStream;)V", false));

                        method.instructions.insert(prev, dbg);
                        patchCount++;
                        return true;
                }

                return false;
        }

        /**
         * Patch issuer validation to accept both official and F2P issuers
         */
        private static boolean patchIssuerValidation(MethodNode method) {
                InsnList insns = method.instructions;
                boolean patched = false;

                for (AbstractInsnNode insn : insns.toArray()) {
                        // Look for: if (!this.expectedIssuer.equals(claims.issuer))
                        // Pattern: ALOAD, GETFIELD expectedIssuer, ALOAD, GETFIELD issuer,
                        // INVOKEVIRTUAL equals
                        if (insn.getOpcode() == Opcodes.INVOKEVIRTUAL) {
                                MethodInsnNode mi = (MethodInsnNode) insn;
                                if (mi.name.equals("equals") && mi.owner.equals("java/lang/String")) {
                                        // Check if this is likely an issuer comparison
                                        if (isIssuerComparison(insns, insns.indexOf(insn))) {
                                                // Replace String.equals with DualAuthHelper.isValidIssuer
                                                // Stack before equals: [expectedIssuer, actualIssuer]
                                                // We need: isValidIssuer(actualIssuer)
                                                InsnList replacement = new InsnList();
                                                replacement.add(new InsnNode(Opcodes.SWAP)); // [actualIssuer,
                                                                                             // expectedIssuer]
                                                replacement.add(new InsnNode(Opcodes.POP)); // [actualIssuer]
                                                // Persist issuer in context for downstream selection (HandshakeHandler
                                                // uses it
                                                // before async hop)
                                                replacement.add(new InsnNode(Opcodes.DUP)); // [actualIssuer,
                                                                                            // actualIssuer]
                                                replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                                                CONTEXT_CLASS, "setIssuer", "(Ljava/lang/String;)V",
                                                                false));
                                                replacement.add(new InsnNode(Opcodes.DUP)); // [actualIssuer,
                                                                                             // actualIssuer]
                                                replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                                                JWKS_FETCHER_CLASS, "registerIssuer", "(Ljava/lang/String;)V",
                                                                false));
                                                replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                                                HELPER_CLASS, "isValidIssuer", "(Ljava/lang/String;)Z",
                                                                false));
                                                insns.insert(insn, replacement);
                                                insns.remove(insn);
                                                patched = true;
                                                patchCount++;
                                        }
                                }
                        }
                }

                return patched;
        }

        /**
         * Check if a String.equals call is comparing issuers
         */
        private static boolean isIssuerComparison(InsnList insns, int idx) {
                // Look for expectedIssuer field access nearby
                int start = Math.max(0, idx - 10);
                for (int i = start; i < idx; i++) {
                        AbstractInsnNode insn = insns.get(i);
                        if (insn.getOpcode() == Opcodes.GETFIELD) {
                                FieldInsnNode fi = (FieldInsnNode) insn;
                                if (fi.name.equals("expectedIssuer") || fi.name.equals("issuer")) {
                                        return true;
                                }
                        }
                }
                return false;
        }

        /**
         * OFFLINE MODE: If we already captured an embedded JWK for this player UUID,
         * avoid contacting the player's issuer (e.g., localhost) and return a completed
         * future.
         */
        private static boolean injectOfflineBypassInRequestAuthorizationGrantAsync(MethodNode method) {
                InsnList injection = new InsnList();

                // Restore context from identityToken (arg index 1)
                injection.add(new VarInsnNode(Opcodes.ALOAD, 1));
                injection.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                HELPER_CLASS, "extractIssuerFromToken", "(Ljava/lang/String;)Ljava/lang/String;",
                                false));
                injection.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                CONTEXT_CLASS, "setIssuer", "(Ljava/lang/String;)V", false));

                injection.add(new VarInsnNode(Opcodes.ALOAD, 1));
                injection.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                HELPER_CLASS, "extractSubjectFromToken", "(Ljava/lang/String;)Ljava/lang/String;",
                                false));
                injection.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                CONTEXT_CLASS, "setPlayerUuid", "(Ljava/lang/String;)V", false));

                // Only bypass HTTP if this is an Omni-Auth token (has embedded private key)
                injection.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                CONTEXT_CLASS, "isOmni", "()Z", false));
                LabelNode continueHttp = new LabelNode();
                injection.add(new JumpInsnNode(Opcodes.IFEQ, continueHttp)); // If NOT Omni, continue with HTTP

                injection.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"));
                injection.add(new LdcInsnNode(
                                "[DualAuth] Omni-Auth active. Short-circuiting Session Service request (OFFLINE MODE)."));
                injection.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println",
                                "(Ljava/lang/String;)V", false));

                injection.add(new VarInsnNode(Opcodes.ALOAD, 1));
                injection.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                "java/util/concurrent/CompletableFuture", "completedFuture",
                                "(Ljava/lang/Object;)Ljava/util/concurrent/CompletableFuture;", false));
                injection.add(new InsnNode(Opcodes.ARETURN));

                injection.add(continueHttp);

                AbstractInsnNode firstInsn = method.instructions.getFirst();
                while (firstInsn != null && firstInsn.getOpcode() == -1) {
                        firstInsn = firstInsn.getNext();
                }

                if (firstInsn != null) {
                        method.instructions.insertBefore(firstInsn, injection);
                        patchCount++;
                        return true;
                }

                return false;
        }

        /**
         * Patch fetchJwksFromService to use DualJwksFetcher for merged JWKS
         *
         * Original method calls sessionServiceClient.getJwks() which fetches from one
         * URL.
         * We'll replace the entire method to call our dual fetcher.
         *
         * IMPORTANT: We replace the entire method body to avoid stack frame issues.
         * The method signature is: JWKSet fetchJwksFromService()
         *
         * New logic:
         * 1. Call DualJwksFetcher.fetchMergedJwksJson() to get merged JSON
         * 2. Parse into JwksResponse using existing codec
         * 3. Convert JwksResponse to JWKSet using existing conversion logic
         * 4. Return the JWKSet (or cached value on failure)
         * 
         * @param method         The method node to patch
         * @param cacheFieldName The name of the cache timestamp field (can be null)
         * @param cacheFieldDesc The descriptor of the cache timestamp field (can be
         *                       null)
         */
        private static boolean patchFetchJwksFromService(MethodNode method, String cacheFieldName,
                        String cacheFieldDesc) {
                // Clear existing instructions and replace with our implementation
                method.instructions.clear();

                // Also clear any exception handlers since we're replacing everything
                method.tryCatchBlocks.clear();

                // Create new method body
                InsnList code = new InsnList();

                // Log start
                code.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"));
                code.add(new LdcInsnNode("[DualAuth] fetchJwksFromService() called - using dual JWKS fetcher"));
                code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println",
                                "(Ljava/lang/String;)V",
                                false));

                // String mergedJson = DualJwksFetcher.fetchMergedJwksJson();
                code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                JWKS_FETCHER_CLASS, "fetchMergedJwksJson", "()Ljava/lang/String;", false));
                code.add(new VarInsnNode(Opcodes.ASTORE, 1)); // Store in local var 1

                // if (mergedJson == null) return this.cachedJwkSet;
                code.add(new VarInsnNode(Opcodes.ALOAD, 1));
                LabelNode notNull = new LabelNode();
                code.add(new JumpInsnNode(Opcodes.IFNONNULL, notNull));
                code.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"));
                code.add(new LdcInsnNode("[DualAuth] Merged JWKS is null, returning cached value"));
                code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println",
                                "(Ljava/lang/String;)V",
                                false));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0)); // this
                code.add(new FieldInsnNode(Opcodes.GETFIELD, JWT_VALIDATOR_CLASS, "cachedJwkSet",
                                "Lcom/nimbusds/jose/jwk/JWKSet;"));
                code.add(new InsnNode(Opcodes.ARETURN));

                code.add(notNull);
                code.add(new FrameNode(Opcodes.F_APPEND, 1, new Object[] { "java/lang/String" }, 0, null));

                // Parse JSON using JwksResponse.CODEC
                // JwksResponse jwks = JwksResponse.CODEC.decodeJson(new
                // RawJsonReader(mergedJson.toCharArray()), EmptyExtraInfo.EMPTY);
                code.add(new FieldInsnNode(Opcodes.GETSTATIC,
                                "com/hypixel/hytale/server/core/auth/SessionServiceClient$JwksResponse", "CODEC",
                                "Lcom/hypixel/hytale/codec/builder/BuilderCodec;"));
                code.add(new TypeInsnNode(Opcodes.NEW, "com/hypixel/hytale/codec/util/RawJsonReader"));
                code.add(new InsnNode(Opcodes.DUP));
                code.add(new VarInsnNode(Opcodes.ALOAD, 1));
                code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toCharArray", "()[C", false));
                code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "com/hypixel/hytale/codec/util/RawJsonReader",
                                "<init>",
                                "([C)V", false));
                code.add(new FieldInsnNode(Opcodes.GETSTATIC,
                                "com/hypixel/hytale/codec/EmptyExtraInfo", "EMPTY",
                                "Lcom/hypixel/hytale/codec/EmptyExtraInfo;"));
                code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                                "com/hypixel/hytale/codec/builder/BuilderCodec", "decodeJson",
                                "(Lcom/hypixel/hytale/codec/util/RawJsonReader;Lcom/hypixel/hytale/codec/ExtraInfo;)Ljava/lang/Object;",
                                false));
                code.add(new TypeInsnNode(Opcodes.CHECKCAST,
                                "com/hypixel/hytale/server/core/auth/SessionServiceClient$JwksResponse"));
                code.add(new VarInsnNode(Opcodes.ASTORE, 2)); // jwksResponse in var 2

                // if (jwksResponse == null || jwksResponse.keys == null ||
                // jwksResponse.keys.length == 0) return this.cachedJwkSet;
                code.add(new VarInsnNode(Opcodes.ALOAD, 2));
                LabelNode jwksNotNull = new LabelNode();
                code.add(new JumpInsnNode(Opcodes.IFNONNULL, jwksNotNull));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new FieldInsnNode(Opcodes.GETFIELD, JWT_VALIDATOR_CLASS, "cachedJwkSet",
                                "Lcom/nimbusds/jose/jwk/JWKSet;"));
                code.add(new InsnNode(Opcodes.ARETURN));

                code.add(jwksNotNull);
                code.add(new FrameNode(Opcodes.F_APPEND, 1,
                                new Object[] { "com/hypixel/hytale/server/core/auth/SessionServiceClient$JwksResponse" },
                                0, null));

                code.add(new VarInsnNode(Opcodes.ALOAD, 2));
                code.add(new FieldInsnNode(Opcodes.GETFIELD,
                                "com/hypixel/hytale/server/core/auth/SessionServiceClient$JwksResponse", "keys",
                                "[Lcom/hypixel/hytale/server/core/auth/SessionServiceClient$JwkKey;"));
                LabelNode keysNotNull = new LabelNode();
                code.add(new JumpInsnNode(Opcodes.IFNONNULL, keysNotNull));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new FieldInsnNode(Opcodes.GETFIELD, JWT_VALIDATOR_CLASS, "cachedJwkSet",
                                "Lcom/nimbusds/jose/jwk/JWKSet;"));
                code.add(new InsnNode(Opcodes.ARETURN));

                code.add(keysNotNull);
                code.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));

                code.add(new VarInsnNode(Opcodes.ALOAD, 2));
                code.add(new FieldInsnNode(Opcodes.GETFIELD,
                                "com/hypixel/hytale/server/core/auth/SessionServiceClient$JwksResponse", "keys",
                                "[Lcom/hypixel/hytale/server/core/auth/SessionServiceClient$JwkKey;"));
                code.add(new InsnNode(Opcodes.ARRAYLENGTH));
                LabelNode keysNotEmpty = new LabelNode();
                code.add(new JumpInsnNode(Opcodes.IFGT, keysNotEmpty));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new FieldInsnNode(Opcodes.GETFIELD, JWT_VALIDATOR_CLASS, "cachedJwkSet",
                                "Lcom/nimbusds/jose/jwk/JWKSet;"));
                code.add(new InsnNode(Opcodes.ARETURN));

                code.add(keysNotEmpty);
                code.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));

                // Log success
                code.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"));
                code.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
                code.add(new InsnNode(Opcodes.DUP));
                code.add(new LdcInsnNode("[DualAuth] Got merged JWKS with "));
                code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>",
                                "(Ljava/lang/String;)V",
                                false));
                code.add(new VarInsnNode(Opcodes.ALOAD, 2));
                code.add(new FieldInsnNode(Opcodes.GETFIELD,
                                "com/hypixel/hytale/server/core/auth/SessionServiceClient$JwksResponse", "keys",
                                "[Lcom/hypixel/hytale/server/core/auth/SessionServiceClient$JwkKey;"));
                code.add(new InsnNode(Opcodes.ARRAYLENGTH));
                code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                "(I)Ljava/lang/StringBuilder;", false));
                code.add(new LdcInsnNode(" keys"));
                code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
                code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
                                "()Ljava/lang/String;", false));
                code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println",
                                "(Ljava/lang/String;)V",
                                false));

                // Convert JwksResponse to JWKSet (ArrayList<JWK>)
                // ArrayList<JWK> jwkList = new ArrayList<>();
                code.add(new TypeInsnNode(Opcodes.NEW, "java/util/ArrayList"));
                code.add(new InsnNode(Opcodes.DUP));
                code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false));
                code.add(new VarInsnNode(Opcodes.ASTORE, 3)); // jwkList in var 3

                // for each key in jwksResponse.keys: call convertToJWK and add to list
                // JwkKey[] keys = jwksResponse.keys;
                code.add(new VarInsnNode(Opcodes.ALOAD, 2));
                code.add(new FieldInsnNode(Opcodes.GETFIELD,
                                "com/hypixel/hytale/server/core/auth/SessionServiceClient$JwksResponse", "keys",
                                "[Lcom/hypixel/hytale/server/core/auth/SessionServiceClient$JwkKey;"));
                code.add(new VarInsnNode(Opcodes.ASTORE, 4)); // keys array in var 4

                // int len = keys.length;
                code.add(new VarInsnNode(Opcodes.ALOAD, 4));
                code.add(new InsnNode(Opcodes.ARRAYLENGTH));
                code.add(new VarInsnNode(Opcodes.ISTORE, 5)); // len in var 5

                // int i = 0;
                code.add(new InsnNode(Opcodes.ICONST_0));
                code.add(new VarInsnNode(Opcodes.ISTORE, 6)); // i in var 6

                // loop start
                LabelNode loopStart = new LabelNode();
                LabelNode loopEnd = new LabelNode();
                code.add(loopStart);
                code.add(new FrameNode(Opcodes.F_APPEND, 4,
                                new Object[] { "java/util/ArrayList",
                                                "[Lcom/hypixel/hytale/server/core/auth/SessionServiceClient$JwkKey;",
                                                Opcodes.INTEGER,
                                                Opcodes.INTEGER },
                                0, null));

                // if (i >= len) break;
                code.add(new VarInsnNode(Opcodes.ILOAD, 6));
                code.add(new VarInsnNode(Opcodes.ILOAD, 5));
                code.add(new JumpInsnNode(Opcodes.IF_ICMPGE, loopEnd));

                // JwkKey key = keys[i];
                code.add(new VarInsnNode(Opcodes.ALOAD, 4));
                code.add(new VarInsnNode(Opcodes.ILOAD, 6));
                code.add(new InsnNode(Opcodes.AALOAD));
                code.add(new VarInsnNode(Opcodes.ASTORE, 7)); // key in var 7

                // JWK jwk = this.convertToJWK(key);
                code.add(new VarInsnNode(Opcodes.ALOAD, 0)); // this
                code.add(new VarInsnNode(Opcodes.ALOAD, 7)); // key
                code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, JWT_VALIDATOR_CLASS, "convertToJWK",
                                "(Lcom/hypixel/hytale/server/core/auth/SessionServiceClient$JwkKey;)Lcom/nimbusds/jose/jwk/JWK;",
                                false));
                code.add(new VarInsnNode(Opcodes.ASTORE, 8)); // jwk in var 8

                // if (jwk != null) jwkList.add(jwk);
                code.add(new VarInsnNode(Opcodes.ALOAD, 8));
                LabelNode skipAdd = new LabelNode();
                code.add(new JumpInsnNode(Opcodes.IFNULL, skipAdd));
                code.add(new VarInsnNode(Opcodes.ALOAD, 3)); // jwkList
                code.add(new VarInsnNode(Opcodes.ALOAD, 8)); // jwk
                code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "add",
                                "(Ljava/lang/Object;)Z",
                                false));
                code.add(new InsnNode(Opcodes.POP));

                code.add(skipAdd);
                code.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));

                // i++;
                code.add(new IincInsnNode(6, 1));
                code.add(new JumpInsnNode(Opcodes.GOTO, loopStart));

                code.add(loopEnd);
                code.add(new FrameNode(Opcodes.F_CHOP, 2, null, 0, null));

                // if (jwkList.isEmpty()) return this.cachedJwkSet;
                code.add(new VarInsnNode(Opcodes.ALOAD, 3));
                code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "isEmpty", "()Z", false));
                LabelNode listNotEmpty = new LabelNode();
                code.add(new JumpInsnNode(Opcodes.IFEQ, listNotEmpty));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new FieldInsnNode(Opcodes.GETFIELD, JWT_VALIDATOR_CLASS, "cachedJwkSet",
                                "Lcom/nimbusds/jose/jwk/JWKSet;"));
                code.add(new InsnNode(Opcodes.ARETURN));

                code.add(listNotEmpty);
                code.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));

                // JWKSet newSet = new JWKSet(jwkList);
                code.add(new TypeInsnNode(Opcodes.NEW, "com/nimbusds/jose/jwk/JWKSet"));
                code.add(new InsnNode(Opcodes.DUP));
                code.add(new VarInsnNode(Opcodes.ALOAD, 3)); // jwkList
                code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "com/nimbusds/jose/jwk/JWKSet", "<init>",
                                "(Ljava/util/List;)V", false));
                code.add(new VarInsnNode(Opcodes.ASTORE, 4)); // newSet in var 4 (reusing)

                // this.cachedJwkSet = newSet;
                code.add(new VarInsnNode(Opcodes.ALOAD, 0)); // this
                code.add(new VarInsnNode(Opcodes.ALOAD, 4)); // newSet
                code.add(new FieldInsnNode(Opcodes.PUTFIELD, JWT_VALIDATOR_CLASS, "cachedJwkSet",
                                "Lcom/nimbusds/jose/jwk/JWKSet;"));

                // --- DYNAMIC CACHE TIMESTAMP UPDATE (SAFE) ---
                // Update cache timestamp only if we detected a valid field
                if (cacheFieldName != null && cacheFieldDesc != null) {
                        code.add(new VarInsnNode(Opcodes.ALOAD, 0)); // this

                        if (cacheFieldDesc.equals("Ljava/time/Instant;")) {
                                // Newer versions: use Instant.now()
                                code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/time/Instant", "now",
                                                "()Ljava/time/Instant;", false));
                                code.add(new FieldInsnNode(Opcodes.PUTFIELD, JWT_VALIDATOR_CLASS, cacheFieldName,
                                                "Ljava/time/Instant;"));
                                System.out.println(
                                                "  [Patcher] Bytecode: Setting " + cacheFieldName + " = Instant.now()");
                        } else if (cacheFieldDesc.equals("J")) {
                                // Older versions: use System.currentTimeMillis()
                                code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/System",
                                                "currentTimeMillis", "()J", false));
                                code.add(new FieldInsnNode(Opcodes.PUTFIELD, JWT_VALIDATOR_CLASS, cacheFieldName, "J"));
                                System.out.println("  [Patcher] Bytecode: Setting " + cacheFieldName
                                                + " = System.currentTimeMillis()");
                        } else {
                                // Unexpected type - skip the timestamp update
                                code.add(new InsnNode(Opcodes.POP)); // Remove 'this' from stack
                                System.out.println("  [Patcher] Bytecode WARNING: Unknown field type '" + cacheFieldDesc
                                                + "' for " + cacheFieldName + ", timestamp not updated.");
                        }
                } else {
                        System.out.println(
                                        "  [Patcher] Bytecode WARNING: Cache timestamp field NOT FOUND. Skipping update.");
                }

                // Log final success
                code.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"));
                code.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
                code.add(new InsnNode(Opcodes.DUP));
                code.add(new LdcInsnNode("[DualAuth] JWKSet created with "));
                code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>",
                                "(Ljava/lang/String;)V",
                                false));
                code.add(new VarInsnNode(Opcodes.ALOAD, 3));
                code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "size", "()I", false));
                code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                "(I)Ljava/lang/StringBuilder;", false));
                code.add(new LdcInsnNode(" JWK keys from dual backends"));
                code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
                code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
                                "()Ljava/lang/String;", false));
                code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println",
                                "(Ljava/lang/String;)V",
                                false));

                // return newSet;
                code.add(new VarInsnNode(Opcodes.ALOAD, 4));
                code.add(new InsnNode(Opcodes.ARETURN));

                method.instructions = code;
                method.maxStack = 6;
                method.maxLocals = 9;

                patchCount++;
                return true;
        }

        /**
         * Patch HandshakeHandler to use DualServerTokenManager for server session
         * tokens
         */
        private static byte[] patchHandshakeHandler(byte[] classBytes) {
                try {
                        ClassReader reader = new ClassReader(classBytes);
                        ClassNode classNode = new ClassNode();
                        reader.accept(classNode, ClassReader.EXPAND_FRAMES);

                        boolean modified = false;

                        for (MethodNode method : classNode.methods) {
                                if (method.instructions == null)
                                        continue;

                                // Look for methods that might be getting server session token
                                // Common method names that might handle authentication
                                if (method.name.contains("Auth") ||
                                                method.name.contains("Token") ||
                                                method.name.contains("Session") ||
                                                method.name.contains("validate") ||
                                                method.name.contains("request")) {

                                        if (patchHandshakeHandlerMethod(method)) {
                                                modified = true;
                                                patchedMethods.add("HandshakeHandler." + method.name);
                                                System.out.println(
                                                                "  [HandshakeHandler] Patched " + method.name
                                                                                + "() to use dual token manager");
                                        }
                                }

                                // Also patch any URL references
                                if (patchUrlReferences(method, "HandshakeHandler")) {
                                        modified = true;
                                }
                        }

                        if (modified) {
                                ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES);
                                classNode.accept(writer);
                                return writer.toByteArray();
                        }

                } catch (Exception e) {
                        System.out.println("  Error patching HandshakeHandler: " + e.getMessage());
                        if (verbose)
                                e.printStackTrace();
                }

                return null;
        }

        /**
         * Patch individual HandshakeHandler method to use DualServerTokenManager
         */
        private static boolean patchHandshakeHandlerMethod(MethodNode method) {
                InsnList insns = method.instructions;
                boolean patched = false;
                int identityTokenLocalIdx = findIdentityTokenLocalInHandshakeMethod(method);

                // Look for patterns where server session token is accessed
                for (AbstractInsnNode insn : insns.toArray()) {
                        // Omni-Auth: Some access tokens may omit the username claim.
                        // HandshakeHandler disconnects on missing/empty username; we fall back to the
                        // handshake username.
                        if (insn.getOpcode() == Opcodes.GETFIELD) {
                                FieldInsnNode fi = (FieldInsnNode) insn;
                                if (fi.owner.equals(JWT_VALIDATOR_CLASS + "$JWTClaims") &&
                                                fi.name.equals("username") &&
                                                fi.desc.equals("Ljava/lang/String;")) {

                                        AbstractInsnNode next = insn.getNext();
                                        while (next != null && (next.getOpcode() == -1)) {
                                                next = next.getNext();
                                        }

                                        if (next instanceof VarInsnNode && next.getOpcode() == Opcodes.ASTORE) {
                                                int usernameLocal = ((VarInsnNode) next).var;

                                                InsnList fix = new InsnList();
                                                LabelNode haveUsername = new LabelNode();
                                                LabelNode haveNonEmptyUsername = new LabelNode();

                                                // if (username == null) username = this.username;
                                                fix.add(new VarInsnNode(Opcodes.ALOAD, usernameLocal));
                                                fix.add(new JumpInsnNode(Opcodes.IFNONNULL, haveUsername));
                                                fix.add(new VarInsnNode(Opcodes.ALOAD, 0));
                                                fix.add(new FieldInsnNode(Opcodes.GETFIELD,
                                                                "com/hypixel/hytale/server/core/io/handlers/login/HandshakeHandler",
                                                                "username", "Ljava/lang/String;"));
                                                fix.add(new VarInsnNode(Opcodes.ASTORE, usernameLocal));
                                                fix.add(haveUsername);

                                                // if (username.isEmpty()) username = this.username;
                                                fix.add(new VarInsnNode(Opcodes.ALOAD, usernameLocal));
                                                fix.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                                                                "java/lang/String", "isEmpty", "()Z", false));
                                                fix.add(new JumpInsnNode(Opcodes.IFEQ, haveNonEmptyUsername));
                                                fix.add(new VarInsnNode(Opcodes.ALOAD, 0));
                                                fix.add(new FieldInsnNode(Opcodes.GETFIELD,
                                                                "com/hypixel/hytale/server/core/io/handlers/login/HandshakeHandler",
                                                                "username", "Ljava/lang/String;"));
                                                fix.add(new VarInsnNode(Opcodes.ASTORE, usernameLocal));
                                                fix.add(haveNonEmptyUsername);

                                                insns.insert(next, fix);
                                                patched = true;
                                                patchCount++;
                                        }
                                }
                        }

                        // Look for field access to session tokens
                        if (insn.getOpcode() == Opcodes.GETFIELD) {
                                FieldInsnNode fi = (FieldInsnNode) insn;
                                if (fi.name.contains("Session") && fi.name.contains("Token")) {
                                        // Replace with call to DualServerTokenManager
                                        InsnList replacement = new InsnList();

                                        // Log
                                        replacement.add(
                                                        new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out",
                                                                        "Ljava/io/PrintStream;"));
                                        replacement.add(new LdcInsnNode(
                                                        "[DualAuth] HandshakeHandler - replacing session token access with DualServerTokenManager"));
                                        replacement.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream",
                                                        "println",
                                                        "(Ljava/lang/String;)V", false));

                                        if (identityTokenLocalIdx >= 0) {
                                                replacement.add(new VarInsnNode(Opcodes.ALOAD, identityTokenLocalIdx));
                                                replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                                                HELPER_CLASS, "extractIssuerFromToken",
                                                                "(Ljava/lang/String;)Ljava/lang/String;",
                                                                false));
                                                replacement.add(new InsnNode(Opcodes.DUP));
                                                replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                                                CONTEXT_CLASS, "setIssuer", "(Ljava/lang/String;)V",
                                                                false));

                                                // --- FIX: Explicitly clear Omni flag for new connection ---
                                                replacement.add(new InsnNode(Opcodes.ICONST_0));
                                                replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                                                CONTEXT_CLASS, "setOmni", "(Z)V", false));
                                        } else {
                                                replacement.add(new InsnNode(Opcodes.ACONST_NULL));
                                        }
                                        replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                                        TOKEN_MANAGER_CLASS, "getSessionTokenForIssuer",
                                                        "(Ljava/lang/String;)Ljava/lang/String;",
                                                        false));

                                        insns.insert(insn, replacement);
                                        insns.remove(insn);
                                        patched = true;
                                        patchCount++;
                                }
                        }

                        // Intercept INVOKEVIRTUAL to ServerAuthManager getters
                        else if (insn.getOpcode() == Opcodes.INVOKEVIRTUAL) {
                                MethodInsnNode mi = (MethodInsnNode) insn;
                                if (mi.owner.equals(SERVER_AUTH_MANAGER_CLASS)
                                                && mi.desc.endsWith("Ljava/lang/String;")) {
                                        // We don't know the exact name, but if it returns string from AuthManager in
                                        // handshake, it's likely the token
                                        // Wrap the result: check if null, if so use ours
                                        InsnList wrap = new InsnList();
                                        wrap.add(new InsnNode(Opcodes.DUP));
                                        LabelNode valid = new LabelNode();
                                        wrap.add(new JumpInsnNode(Opcodes.IFNONNULL, valid));
                                        wrap.add(new InsnNode(Opcodes.POP));
                                        if (identityTokenLocalIdx >= 0) {
                                                wrap.add(new VarInsnNode(Opcodes.ALOAD, identityTokenLocalIdx));
                                                wrap.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                                                HELPER_CLASS, "extractIssuerFromToken",
                                                                "(Ljava/lang/String;)Ljava/lang/String;",
                                                                false));
                                                wrap.add(new InsnNode(Opcodes.DUP));
                                                wrap.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                                                CONTEXT_CLASS, "setIssuer", "(Ljava/lang/String;)V",
                                                                false));

                                                // --- FIX: Explicitly clear Omni flag for new connection ---
                                                wrap.add(new InsnNode(Opcodes.ICONST_0));
                                                wrap.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                                                CONTEXT_CLASS, "setOmni", "(Z)V", false));
                                        } else {
                                                wrap.add(new InsnNode(Opcodes.ACONST_NULL));
                                        }
                                        wrap.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TOKEN_MANAGER_CLASS,
                                                        "getSessionTokenForIssuer",
                                                        "(Ljava/lang/String;)Ljava/lang/String;", false));
                                        wrap.add(valid);
                                        method.instructions.insert(insn, wrap);
                                        patched = true;
                                        patchCount++;
                                }
                        }

                        // Look for hardcoded session URLs
                        if (insn.getOpcode() == Opcodes.LDC) {
                                LdcInsnNode ldc = (LdcInsnNode) insn;
                                if (ldc.cst instanceof String) {
                                        String str = (String) ldc.cst;
                                        if (str.contains("sessions.hytale.com") || str.contains("hytale.com")) {
                                                // Replace with dynamic URL
                                                InsnList replacement = new InsnList();

                                                replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                                                HELPER_CLASS, "getSessionUrl", "()Ljava/lang/String;",
                                                                false));

                                                insns.insert(insn, replacement);
                                                insns.remove(insn);
                                                patched = true;
                                                patchCount++;
                                        }
                                }
                        }
                }

                return patched;
        }

        private static int findIdentityTokenLocalInHandshakeMethod(MethodNode method) {
                for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                        if (insn.getOpcode() == Opcodes.INVOKEVIRTUAL || insn.getOpcode() == Opcodes.INVOKESTATIC) {
                                if (!(insn instanceof MethodInsnNode))
                                        continue;
                                MethodInsnNode mi = (MethodInsnNode) insn;
                                if (mi.owner.equals(JWT_VALIDATOR_CLASS) && mi.name.equals("validateIdentityToken")
                                                && mi.desc.startsWith("(Ljava/lang/String;")) {
                                        AbstractInsnNode prev = insn.getPrevious();
                                        while (prev != null && prev.getOpcode() == -1) {
                                                prev = prev.getPrevious();
                                        }
                                        if (prev instanceof VarInsnNode && prev.getOpcode() == Opcodes.ALOAD) {
                                                return ((VarInsnNode) prev).var;
                                        }
                                }
                        }
                }
                return -1;
        }

        /**
         * Patch SessionServiceClient constructor to use dynamic URL instead of
         * hardcoded URL
         */
        private static boolean patchSessionServiceClientConstructor(MethodNode method) {
                InsnList insns = method.instructions;
                boolean patched = false;

                // Look for pattern: this.sessionServiceUrl = "https://sessions.hytale.com"
                for (AbstractInsnNode insn : insns.toArray()) {
                        if (insn.getOpcode() == Opcodes.LDC) {
                                LdcInsnNode ldc = (LdcInsnNode) insn;
                                if (ldc.cst instanceof String) {
                                        String str = (String) ldc.cst;
                                        if (str.equals(OFFICIAL_SESSION_URL)
                                                        || str.equals("https://sessions.hytale.com")) {
                                                // Replace with dynamic URL
                                                InsnList replacement = new InsnList();

                                                // Log that we're using dynamic URL
                                                replacement.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System",
                                                                "out",
                                                                "Ljava/io/PrintStream;"));
                                                replacement.add(new LdcInsnNode(
                                                                "[DualAuth] SessionServiceClient constructor - replacing hardcoded URL with dynamic URL"));
                                                replacement.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                                                                "java/io/PrintStream", "println",
                                                                "(Ljava/lang/String;)V", false));

                                                // Call DualAuthHelper.getSessionUrl() which will return the appropriate
                                                // URL
                                                replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                                                HELPER_CLASS, "getSessionUrl", "()Ljava/lang/String;",
                                                                false));

                                                // Insert the replacement before the PUTFIELD that sets
                                                // sessionServiceUrl
                                                // Find the PUTFIELD instruction that follows
                                                AbstractInsnNode putFieldInsn = findNextInstruction(insn,
                                                                Opcodes.PUTFIELD);
                                                if (putFieldInsn != null) {
                                                        FieldInsnNode fi = (FieldInsnNode) putFieldInsn;
                                                        if (fi.name.equals("sessionServiceUrl")) {
                                                                insns.insert(insn, replacement);
                                                                insns.remove(insn);
                                                                patched = true;
                                                                patchCount++;
                                                        }
                                                }
                                        }
                                }
                        }
                }

                return patched;
        }

        /**
         * Find the next instruction with the specified opcode
         */
        private static AbstractInsnNode findNextInstruction(AbstractInsnNode start, int opcode) {
                AbstractInsnNode insn = start.getNext();
                while (insn != null) {
                        if (insn.getOpcode() == opcode) {
                                return insn;
                        }
                        insn = insn.getNext();
                }
                return null;
        }

        /**
         * Patch SessionServiceClient to route authorization requests based on token
         * issuer
         */
        private static byte[] patchSessionServiceClient(byte[] classBytes) {
                try {
                        ClassReader reader = new ClassReader(classBytes);
                        ClassNode classNode = new ClassNode();
                        reader.accept(classNode, ClassReader.EXPAND_FRAMES);

                        boolean modified = false;

                        for (MethodNode method : classNode.methods) {
                                if (method.instructions == null)
                                        continue;

                                // Patch constructor to use dynamic URL
                                if (method.name.equals("<init>")) {
                                        if (patchSessionServiceClientConstructor(method)) {
                                                modified = true;
                                                patchedMethods.add("SessionServiceClient.<init>");
                                                System.out.println(
                                                                "  [SessionServiceClient] Patched constructor to use dynamic URL");
                                        }
                                }

                                // OFFLINE MODE: Short-circuit requestAuthorizationGrantAsync if we already have
                                // an embedded key
                                if (method.name.equals("requestAuthorizationGrantAsync")
                                                && method.desc.equals(
                                                                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/util/concurrent/CompletableFuture;")) {
                                        if (injectContextSetupInMainMethods(method)) {
                                                modified = true;
                                                patchedMethods.add(
                                                                "SessionServiceClient.requestAuthorizationGrantAsync");
                                                System.out.println(
                                                                "  [SessionServiceClient] Injected context setup in requestAuthorizationGrantAsync()");
                                        }
                                        if (injectOfflineBypassInRequestAuthorizationGrantAsync(method)) {
                                                modified = true;
                                                patchedMethods.add(
                                                                "SessionServiceClient.requestAuthorizationGrantAsync");
                                                System.out.println(
                                                                "  [SessionServiceClient] Injected offline bypass in requestAuthorizationGrantAsync()");
                                        }
                                }

                                // Patch lambda methods for requestAuthorizationGrantAsync
                                // Lambda methods are named like lambda$requestAuthorizationGrantAsync$0
                                // They capture identityToken and need to set context + use dynamic URL
                                if (method.name.startsWith("lambda$requestAuthorizationGrantAsync")) {
                                        System.out.println("  [SessionServiceClient] Found lambda: " + method.name
                                                        + method.desc);

                                        // Find identityToken parameter - it's captured in the lambda
                                        // The lambda signature is typically: (SessionServiceClient, String
                                        // identityToken, String, String) -> Object
                                        // or it might be a closure where identityToken is a captured variable
                                        int identityTokenIndex = findIdentityTokenParam(method);
                                        if (identityTokenIndex >= 0) {
                                                if (injectContextSetupInLambda(method, identityTokenIndex)) {
                                                        modified = true;
                                                        patchedMethods.add("SessionServiceClient." + method.name);
                                                        System.out.println(
                                                                        "  [SessionServiceClient] Injected context setup in "
                                                                                        + method.name
                                                                                        + "() (token at index "
                                                                                        + identityTokenIndex + ")");
                                                }
                                        }

                                        if (injectAuthGrantExceptionDebugInLambda(method)) {
                                                modified = true;
                                                patchedMethods.add("SessionServiceClient." + method.name
                                                                + "[ExceptionDebug]");
                                                System.out
                                                                .println("  [SessionServiceClient] Injected exception debug in "
                                                                                + method.name + "()");
                                        }
                                }

                                // Patch lambda methods for exchangeAuthGrantForTokenAsync
                                // This lambda also uses this.sessionServiceUrl and needs context from
                                // authorizationGrant token
                                if (method.name.startsWith("lambda$exchangeAuthGrantForTokenAsync")) {
                                        System.out.println("  [SessionServiceClient] Found lambda: " + method.name
                                                        + method.desc);

                                        // authorizationGrant is the first String param (after 'this')
                                        int authGrantIndex = findIdentityTokenParam(method);
                                        if (authGrantIndex >= 0) {
                                                if (injectContextSetupInLambda(method, authGrantIndex)) {
                                                        modified = true;
                                                        patchedMethods.add("SessionServiceClient." + method.name);
                                                        System.out.println(
                                                                        "  [SessionServiceClient] Injected context setup in "
                                                                                        + method.name
                                                                                        + "() (token at index "
                                                                                        + authGrantIndex + ")");
                                                }
                                        }
                                }

                                // Patch main method exchangeAuthGrantForTokenAsync
                                if (method.name.equals("exchangeAuthGrantForTokenAsync")
                                                && method.desc.contains(
                                                                "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;")) {
                                        if (injectContextSetupInMainMethods(method)) {
                                                modified = true;
                                                patchedMethods.add(
                                                                "SessionServiceClient.exchangeAuthGrantForTokenAsync");
                                                System.out.println(
                                                                "  [SessionServiceClient] Injected context setup in exchangeAuthGrantForTokenAsync()");
                                        }
                                }

                                // Patch lambda methods for refreshSessionAsync
                                // This lambda needs to use dynamic URL based on the sessionToken's issuer
                                // The sessionToken is passed as the first String parameter after 'this'
                                if (method.name.startsWith("lambda$refreshSessionAsync")) {
                                        System.out.println("  [SessionServiceClient] Found lambda: " + method.name
                                                        + method.desc);

                                        // sessionToken is the first String param (after 'this' if present)
                                        int sessionTokenIndex = findIdentityTokenParam(method);
                                        if (sessionTokenIndex >= 0) {
                                                if (injectRefreshContextSetup(method, sessionTokenIndex)) {
                                                        modified = true;
                                                        patchedMethods.add("SessionServiceClient." + method.name);
                                                        System.out.println(
                                                                        "  [SessionServiceClient] Injected refresh context setup in "
                                                                                        + method.name
                                                                                        + "() (token at index "
                                                                                        + sessionTokenIndex + ")");
                                                }
                                        }
                                }

                                // Patch main method refreshSessionAsync
                                if (method.name.equals("refreshSessionAsync")
                                                && method.desc.contains(
                                                                "Ljava/lang/String;)Lcom/hypixel/hytale/server/core/auth/SessionServiceClient$GameSessionResponse;")) {
                                        if (injectContextSetupInMainMethods(method)) {
                                                modified = true;
                                                patchedMethods.add("SessionServiceClient.refreshSessionAsync");
                                                System.out.println(
                                                                "  [SessionServiceClient] Injected context setup in refreshSessionAsync()");
                                        }
                                }

                                // Also patch URL references in ALL methods (including lambdas)
                                if (patchUrlReferences(method, "SessionServiceClient")) {
                                        modified = true;
                                }
                        }

                        if (modified) {
                                // Use COMPUTE_FRAMES to properly compute stack map frames for JVM verification
                                ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES);
                                classNode.accept(writer);
                                return writer.toByteArray();
                        }

                } catch (Exception e) {
                        System.out.println("  Error patching SessionServiceClient: " + e.getMessage());
                        if (verbose)
                                e.printStackTrace();
                }

                return null;
        }

        /**
         * Patch AuthGrant packet to suppress serverIdentityToken for F2P clients.
         *
         * When sending AuthGrant to an F2P client (detected via DualAuthContext),
         * we set serverIdentityToken to null because F2P clients can't verify
         * tokens signed by hytale.com.
         *
         * We patch the serialize() method to check context and nullify the field
         * before serialization for F2P clients.
         */
        private static byte[] patchAuthGrant(byte[] classBytes) {
                try {
                        ClassReader reader = new ClassReader(classBytes);
                        ClassNode classNode = new ClassNode();
                        reader.accept(classNode, ClassReader.EXPAND_FRAMES);

                        boolean modified = false;

                        for (MethodNode method : classNode.methods) {
                                if (method.instructions == null)
                                        continue;

                                // Patch serialize() method - inject code at start to nullify
                                // serverIdentityToken for F2P
                                if (method.name.equals("serialize")
                                                && method.desc.equals("(Lio/netty/buffer/ByteBuf;)V")) {
                                        if (injectServerIdentityNullifier(method)) {
                                                modified = true;
                                                patchedMethods.add("AuthGrant.serialize");
                                                System.out.println(
                                                                "  [AuthGrant] Patched serialize() to suppress serverIdentityToken for F2P");
                                        }
                                }
                        }

                        if (modified) {
                                ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES);
                                classNode.accept(writer);
                                return writer.toByteArray();
                        }

                } catch (Exception e) {
                        System.out.println("  Error patching AuthGrant: " + e.getMessage());
                        if (verbose)
                                e.printStackTrace();
                }

                return null;
        }

        /**
         * Inject context setup at the beginning of main SessionServiceClient methods
         * This ensures ThreadLocal context is preserved across async threads
         */
        private static boolean injectContextSetupInMainMethods(MethodNode method) {
                InsnList injection = new InsnList();

                // The first argument (index 1) in these methods is always a token
                // (identityToken, authGrant, or sessionToken)
                injection.add(new VarInsnNode(Opcodes.ALOAD, 1));
                injection.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER_CLASS,
                                "extractIssuerFromToken", "(Ljava/lang/String;)Ljava/lang/String;", false));
                injection.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CONTEXT_CLASS,
                                "setIssuer", "(Ljava/lang/String;)V", false));

                // Debug log for context re-hydration
                injection.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"));
                injection.add(new LdcInsnNode("[DualAuth] Context re-hydrated in SessionServiceClient." + method.name));
                injection.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println",
                                "(Ljava/lang/String;)V", false));

                // Find first non-label instruction to insert after
                AbstractInsnNode firstInsn = method.instructions.getFirst();
                while (firstInsn != null && firstInsn.getOpcode() == -1) {
                        firstInsn = firstInsn.getNext();
                }

                if (firstInsn != null) {
                        method.instructions.insertBefore(firstInsn, injection);
                        return true;
                }

                return false;
        }

        /**
         * Patch ServerAuthManager to integrate with DualServerTokenManager.
         *
         * Modifications:
         * 1. In initialize() - call DualServerTokenManager.ensureF2PTokens() to
         * auto-fetch F2P tokens
         * 2. When tokens are set via /auth login - also store in
         * DualServerTokenManager.setOfficialTokens()
         */
        private static byte[] patchServerAuthManager(byte[] classBytes) {
                try {
                        ClassReader reader = new ClassReader(classBytes);
                        ClassNode classNode = new ClassNode();
                        reader.accept(classNode, ClassReader.EXPAND_FRAMES);

                        boolean modified = false;

                        for (MethodNode method : classNode.methods) {
                                if (method.instructions == null)
                                        continue;

                                // Patch initialize() to log token status
                                if (method.name.equals("initialize") && method.desc.equals("()V")) {
                                        if (injectF2PTokenFetch(method)) {
                                                modified = true;
                                                patchedMethods.add("ServerAuthManager.initialize");
                                                System.out.println(
                                                                "  [ServerAuthManager] Patched initialize() to log token status");
                                        }
                                }

                                // Patch createGameSessionFromOAuth to store official tokens
                                // This is called after successful /auth login
                                if (method.name.equals("createGameSessionFromOAuth")) {
                                        if (injectOfficialTokenStorage(method)) {
                                                modified = true;
                                                patchedMethods.add("ServerAuthManager.createGameSessionFromOAuth");
                                                System.out.println(
                                                                "  [ServerAuthManager] Patched createGameSessionFromOAuth() to store official tokens");
                                        }
                                }

                                // Patch getter methods to fallback to F2P tokens if null
                                if (method.desc.endsWith("Ljava/lang/String;")) {
                                        if (patchGetterMethod(method)) {
                                                modified = true;
                                                System.out.println(
                                                                "  [ServerAuthManager] Patched getter: " + method.name);
                                        }
                                }

                                // Patch ANY method that sets gameSession (to capture native auto-auth)
                                if (injectNativeSessionCapture(method)) {
                                        modified = true;
                                        patchedMethods.add("ServerAuthManager." + method.name + "[NativeCapture]");
                                        System.out.println("  [ServerAuthManager] Instrumented " + method.name
                                                        + " to capture native session");
                                }
                        }

                        if (modified) {
                                ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES);
                                classNode.accept(writer);
                                return writer.toByteArray();
                        }

                } catch (Exception e) {
                        System.out.println("  Error patching ServerAuthManager: " + e.getMessage());
                        if (verbose)
                                e.printStackTrace();
                }

                return null;
        }

        /**
         * Inject token status logging at beginning of initialize()
         * MODIFIED: Removed F2P token fetch since it's now done in static initializer
         */
        private static boolean injectF2PTokenFetch(MethodNode method) {
                InsnList injection = new InsnList();

                // Log token status at server startup
                injection.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"));
                injection.add(new LdcInsnNode("[DualAuth] ServerAuthManager.initialize() - checking token status..."));
                injection.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println",
                                "(Ljava/lang/String;)V", false));

                // Log current token status
                injection.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                TOKEN_MANAGER_CLASS, "logCurrentTokens", "()V", false));

                // Find the first real instruction (skip labels and frames)
                AbstractInsnNode firstInsn = method.instructions.getFirst();
                while (firstInsn != null && firstInsn.getOpcode() == -1) {
                        firstInsn = firstInsn.getNext();
                }

                if (firstInsn != null) {
                        method.instructions.insertBefore(firstInsn, injection);
                        patchCount++;
                        return true;
                }

                return false;
        }

        /**
         * Inject call to store official tokens in DualServerTokenManager after OAuth
         * login
         */
        private static boolean injectOfficialTokenStorage(MethodNode method) {
                // This method sets this.gameSession which contains sessionToken and
                // identityToken
                // We need to extract these and store in DualServerTokenManager

                InsnList injection = new InsnList();

                // Log
                injection.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"));
                injection.add(new LdcInsnNode("[DualAuth] Storing official tokens from /auth login..."));
                injection.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println",
                                "(Ljava/lang/String;)V", false));

                // Get tokens from this.gameSession
                // GameSessionResponse session = this.gameSession.get();
                injection.add(new VarInsnNode(Opcodes.ALOAD, 0)); // this
                injection.add(new FieldInsnNode(Opcodes.GETFIELD, SERVER_AUTH_MANAGER_CLASS, "gameSession",
                                "Ljava/util/concurrent/atomic/AtomicReference;"));
                injection.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/concurrent/atomic/AtomicReference",
                                "get", "()Ljava/lang/Object;", false));
                injection.add(new TypeInsnNode(Opcodes.CHECKCAST,
                                "com/hypixel/hytale/server/core/auth/SessionServiceClient$GameSessionResponse"));
                injection.add(new VarInsnNode(Opcodes.ASTORE, 10)); // session (using high local var to avoid conflicts)

                // if (session != null) {
                injection.add(new VarInsnNode(Opcodes.ALOAD, 10));
                LabelNode skipStorage = new LabelNode();
                injection.add(new JumpInsnNode(Opcodes.IFNULL, skipStorage));

                // DualServerTokenManager.setOfficialTokens(session.sessionToken,
                // session.identityToken);
                injection.add(new VarInsnNode(Opcodes.ALOAD, 10));
                injection.add(new FieldInsnNode(Opcodes.GETFIELD,
                                "com/hypixel/hytale/server/core/auth/SessionServiceClient$GameSessionResponse",
                                "sessionToken", "Ljava/lang/String;"));
                injection.add(new VarInsnNode(Opcodes.ALOAD, 10));
                injection.add(new FieldInsnNode(Opcodes.GETFIELD,
                                "com/hypixel/hytale/server/core/auth/SessionServiceClient$GameSessionResponse",
                                "identityToken", "Ljava/lang/String;"));
                injection.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                TOKEN_MANAGER_CLASS, "setOfficialTokens", "(Ljava/lang/String;Ljava/lang/String;)V",
                                false));

                injection.add(skipStorage);
                injection.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));

                // Find first IRETURN or ARETURN and inject before it
                for (AbstractInsnNode insn : method.instructions.toArray()) {
                        if (insn.getOpcode() == Opcodes.IRETURN || insn.getOpcode() == Opcodes.ARETURN) {
                                method.instructions.insertBefore(insn, injection);
                                patchCount++;
                                return true;
                        }
                }

                return false;
        }

        /**
         * Inject logic to capture native session tokens when AtomicReference.set is
         * called.
         */
        private static boolean injectNativeSessionCapture(MethodNode method) {
                InsnList insns = method.instructions;
                boolean patched = false;

                for (AbstractInsnNode insn : insns.toArray()) {
                        // Look for: this.gameSession.set(...)
                        // Instruction pattern:
                        // ALOAD 0
                        // GETFIELD gameSession (AtomicReference)
                        // ... (loading argument) ...
                        // INVOKEVIRTUAL AtomicReference.set(Object)

                        if (insn.getOpcode() == Opcodes.INVOKEVIRTUAL) {
                                MethodInsnNode mi = (MethodInsnNode) insn;
                                if (mi.owner.equals("java/util/concurrent/atomic/AtomicReference") &&
                                                mi.name.equals("set")) {

                                        // Scan backwards for GETFIELD to ensure it's 'gameSession'
                                        AbstractInsnNode prev = insn.getPrevious();
                                        boolean targetFound = false;
                                        int steps = 0;
                                        while (prev != null && steps < 20) { // Limit scan
                                                if (prev.getOpcode() == Opcodes.GETFIELD) {
                                                        FieldInsnNode fi = (FieldInsnNode) prev;
                                                        if (fi.name.equals("gameSession")) {
                                                                targetFound = true;
                                                        }
                                                        break;
                                                }
                                                prev = prev.getPrevious();
                                                steps++;
                                        }

                                        if (targetFound) {
                                                InsnList hook = new InsnList();
                                                hook.add(new InsnNode(Opcodes.DUP));
                                                hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                                                TOKEN_MANAGER_CLASS, "captureNativeSession",
                                                                "(Ljava/lang/Object;)V", false));

                                                insns.insertBefore(insn, hook);
                                                patched = true;
                                                patchCount++;
                                        }
                                }
                        }
                }
                return patched;
        }

        /**
         * Inspects a method to see if it's a getter for session/identity token.
         * If so, injects fallback logic before return.
         */
        private static boolean patchGetterMethod(MethodNode method) {
                boolean isSessionGetter = false;
                boolean isIdentityGetter = false;

                for (AbstractInsnNode insn : method.instructions) {
                        if (insn.getOpcode() == Opcodes.GETFIELD) {
                                FieldInsnNode fn = (FieldInsnNode) insn;
                                if (fn.owner.equals(
                                                "com/hypixel/hytale/server/core/auth/SessionServiceClient$GameSessionResponse")) {
                                        if (fn.name.equals("sessionToken"))
                                                isSessionGetter = true;
                                        if (fn.name.equals("identityToken"))
                                                isIdentityGetter = true;
                                }
                        }
                }

                if (!isSessionGetter && !isIdentityGetter)
                        return false;

                // Found a getter. Inject fallback logic before ARETURN.
                // Logic: if (result == null) result = DualServerTokenManager.getToken(null);
                InsnList inject = new InsnList();
                inject.add(new InsnNode(Opcodes.DUP)); // Duplicate result on stack
                LabelNode notNull = new LabelNode();
                inject.add(new JumpInsnNode(Opcodes.IFNONNULL, notNull));

                inject.add(new InsnNode(Opcodes.POP)); // Pop null
                inject.add(new InsnNode(Opcodes.ACONST_NULL)); // Pass null issuer (will use Context)
                if (isSessionGetter) {
                        inject.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TOKEN_MANAGER_CLASS,
                                        "getSessionTokenForIssuer",
                                        "(Ljava/lang/String;)Ljava/lang/String;", false));
                } else {
                        inject.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TOKEN_MANAGER_CLASS,
                                        "getIdentityTokenForIssuer",
                                        "(Ljava/lang/String;)Ljava/lang/String;", false));
                }

                inject.add(notNull);

                // Find ARETURN
                AbstractInsnNode target = null;
                for (AbstractInsnNode insn : method.instructions) {
                        if (insn.getOpcode() == Opcodes.ARETURN) {
                                target = insn;
                                break;
                        }
                }

                if (target != null) {
                        method.instructions.insertBefore(target, inject);
                        return true;
                }
                return false;
        }

        /**
         * Inject code at start of serialize() to replace serverIdentityToken for F2P
         * context.
         *
         * Since ThreadLocal doesn't work across threads (AuthGrant.serialize() is
         * called
         * on a different thread than where we set the context), we determine F2P status
         * by examining the authorizationGrant JWT itself - if it came from F2P backend,
         * we need to use F2P server identity.
         *
         * Injected code:
         * DualAuthHelper.maybeReplaceServerIdentity(this);
         *
         * Note: Uses COMPUTE_FRAMES so we don't add manual FrameNode entries.
         */
        private static boolean injectServerIdentityNullifier(MethodNode method) {
                InsnList injection = new InsnList();

                // Call DualAuthHelper.maybeReplaceServerIdentity(this)
                // This method checks if authorizationGrant is from F2P and replaces
                // serverIdentityToken if needed
                injection.add(new VarInsnNode(Opcodes.ALOAD, 0)); // this (AuthGrant instance)
                injection.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                HELPER_CLASS, "maybeReplaceServerIdentity",
                                "(L" + AUTH_GRANT_CLASS + ";)V", false));

                // Find first real instruction
                AbstractInsnNode firstInsn = method.instructions.getFirst();
                while (firstInsn != null && firstInsn.getOpcode() == -1) {
                        firstInsn = firstInsn.getNext();
                }

                if (firstInsn != null) {
                        method.instructions.insertBefore(firstInsn, injection);
                        patchCount++;
                        return true;
                }

                return false;
        }

        /**
         * Find the index of identityToken parameter in a lambda method.
         * Lambda captures variables from outer scope.
         * For requestAuthorizationGrantAsync lambda, the captured vars are:
         * - this (index 0)
         * - identityToken (String, likely index 1)
         * - serverAudience (String)
         * - bearerToken (String)
         */
        private static int findIdentityTokenParam(MethodNode method) {
                // Parse method descriptor to find String parameters
                String desc = method.desc;

                // For instance method in lambda: first param is often 'this' of outer class
                // Then captured variables follow
                // Descriptor like:
                // (Lcom/hypixel/.../SessionServiceClient;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;

                // Count parameters - look for first String after 'this'
                int idx = 0;
                int paramIndex = 0;
                boolean inClass = false;

                if (desc.startsWith("(")) {
                        idx = 1;
                }

                while (idx < desc.length() && desc.charAt(idx) != ')') {
                        char c = desc.charAt(idx);
                        if (c == 'L') {
                                // Object type - find end
                                int end = desc.indexOf(';', idx);
                                String type = desc.substring(idx + 1, end);
                                if (type.equals("java/lang/String") && paramIndex > 0) {
                                        // First String after 'this' is identityToken
                                        return paramIndex;
                                }
                                idx = end + 1;
                                paramIndex++;
                        } else if (c == '[') {
                                // Array - skip to element type
                                idx++;
                        } else {
                                // Primitive
                                idx++;
                                paramIndex++;
                        }
                }

                // Fallback: assume identityToken is at index 1 (after 'this')
                return 1;
        }

        /**
         * Inject context setup at the START of a lambda method
         */
        private static boolean injectContextSetupInLambda(MethodNode method, int identityTokenIndex) {
                InsnList injection = new InsnList();

                // String issuer = DualAuthHelper.extractIssuerFromToken(identityToken);
                injection.add(new VarInsnNode(Opcodes.ALOAD, identityTokenIndex));
                injection.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                HELPER_CLASS, "extractIssuerFromToken", "(Ljava/lang/String;)Ljava/lang/String;",
                                false));

                // DualAuthContext.setIssuer(issuer);
                injection.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                CONTEXT_CLASS, "setIssuer", "(Ljava/lang/String;)V", false));

                injection.add(new VarInsnNode(Opcodes.ALOAD, identityTokenIndex));
                injection.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                HELPER_CLASS, "extractSubjectFromToken", "(Ljava/lang/String;)Ljava/lang/String;",
                                false));
                injection.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                CONTEXT_CLASS, "setPlayerUuid", "(Ljava/lang/String;)V", false));

                injection.add(new VarInsnNode(Opcodes.ALOAD, identityTokenIndex));
                injection.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                HELPER_CLASS, "extractJwkFromToken", "(Ljava/lang/String;)Ljava/lang/String;", false));

                injection.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                CONTEXT_CLASS, "setJwk", "(Ljava/lang/String;)V", false));

                // Log
                injection.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"));
                injection.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
                injection.add(new InsnNode(Opcodes.DUP));
                injection.add(new LdcInsnNode("[DualAuth] Lambda: issuer from token: "));
                injection.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>",
                                "(Ljava/lang/String;)V", false));
                injection.add(
                                new MethodInsnNode(Opcodes.INVOKESTATIC, CONTEXT_CLASS, "getIssuer",
                                                "()Ljava/lang/String;", false));
                injection.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
                injection.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
                                "()Ljava/lang/String;", false));
                injection.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println",
                                "(Ljava/lang/String;)V", false));

                // Find first real instruction
                AbstractInsnNode firstInsn = method.instructions.getFirst();
                while (firstInsn != null && firstInsn.getOpcode() == -1) {
                        firstInsn = firstInsn.getNext();
                }

                if (firstInsn != null) {
                        method.instructions.insertBefore(firstInsn, injection);
                        patchCount++;
                        return true;
                }

                return false;
        }

        /**
         * Inject context setup specifically for refreshSessionAsync lambda.
         * This stores the sessionToken index so we can use it for URL resolution.
         * The key difference from regular lambda patching is that refresh runs on a
         * different thread and needs to get the URL directly from the token, not from
         * thread-local context.
         */
        private static boolean injectRefreshContextSetup(MethodNode method, int sessionTokenIndex) {
                InsnList injection = new InsnList();

                // Log that we're in refresh
                injection.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"));
                injection.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
                injection.add(new InsnNode(Opcodes.DUP));
                injection.add(
                                new LdcInsnNode("[DualAuth] refreshSessionAsync lambda - sessionToken at index "
                                                + sessionTokenIndex));
                injection.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>",
                                "(Ljava/lang/String;)V", false));
                injection.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
                                "()Ljava/lang/String;", false));
                injection.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println",
                                "(Ljava/lang/String;)V", false));

                // Set context from sessionToken so URL resolution works
                // String issuer = DualAuthHelper.extractIssuerFromToken(sessionToken);
                injection.add(new VarInsnNode(Opcodes.ALOAD, sessionTokenIndex));
                injection.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                HELPER_CLASS, "extractIssuerFromToken", "(Ljava/lang/String;)Ljava/lang/String;",
                                false));

                // DualAuthContext.setIssuer(issuer);
                injection.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                CONTEXT_CLASS, "setIssuer", "(Ljava/lang/String;)V", false));

                injection.add(new VarInsnNode(Opcodes.ALOAD, sessionTokenIndex));
                injection.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                HELPER_CLASS, "extractSubjectFromToken", "(Ljava/lang/String;)Ljava/lang/String;",
                                false));

                injection.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                CONTEXT_CLASS, "setPlayerUuid", "(Ljava/lang/String;)V", false));

                injection.add(new VarInsnNode(Opcodes.ALOAD, sessionTokenIndex));
                injection.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                HELPER_CLASS, "extractJwkFromToken", "(Ljava/lang/String;)Ljava/lang/String;", false));

                injection.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                CONTEXT_CLASS, "setJwk", "(Ljava/lang/String;)V", false));

                // Log issuer
                injection.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"));
                injection.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
                injection.add(new InsnNode(Opcodes.DUP));
                injection.add(new LdcInsnNode("[DualAuth] Refresh token issuer: "));
                injection.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>",
                                "(Ljava/lang/String;)V", false));
                injection.add(
                                new MethodInsnNode(Opcodes.INVOKESTATIC, CONTEXT_CLASS, "getIssuer",
                                                "()Ljava/lang/String;", false));
                injection.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
                injection.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
                                "()Ljava/lang/String;", false));
                injection.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println",
                                "(Ljava/lang/String;)V", false));

                // Find first real instruction
                AbstractInsnNode firstInsn = method.instructions.getFirst();
                while (firstInsn != null && firstInsn.getOpcode() == -1) {
                        firstInsn = firstInsn.getNext();
                }

                if (firstInsn != null) {
                        method.instructions.insertBefore(firstInsn, injection);
                        patchCount++;
                        return true;
                }

                return false;
        }

        /**
         * Inject code to extract issuer from token and set context
         */
        private static boolean patchAuthGrantMethod(MethodNode method, int tokenArgIndex) {
                InsnList injection = new InsnList();

                // String issuer = DualAuthHelper.extractIssuerFromToken(identityToken);
                injection.add(new VarInsnNode(Opcodes.ALOAD, tokenArgIndex));
                injection.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                HELPER_CLASS, "extractIssuerFromToken", "(Ljava/lang/String;)Ljava/lang/String;",
                                false));

                // DualAuthContext.setIssuer(issuer);
                injection.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                CONTEXT_CLASS, "setIssuer", "(Ljava/lang/String;)V", false));

                // Log
                injection.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"));
                injection.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
                injection.add(new InsnNode(Opcodes.DUP));
                injection.add(new LdcInsnNode("[DualAuth] Auth request, issuer from token: "));
                injection.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>",
                                "(Ljava/lang/String;)V", false));
                injection.add(
                                new MethodInsnNode(Opcodes.INVOKESTATIC, CONTEXT_CLASS, "getIssuer",
                                                "()Ljava/lang/String;", false));
                injection.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
                injection.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
                                "()Ljava/lang/String;", false));
                injection.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println",
                                "(Ljava/lang/String;)V", false));

                // Find first real instruction
                AbstractInsnNode firstInsn = method.instructions.getFirst();
                while (firstInsn != null && firstInsn.getOpcode() == -1) {
                        firstInsn = firstInsn.getNext();
                }

                if (firstInsn != null) {
                        method.instructions.insertBefore(firstInsn, injection);
                        patchCount++;
                        return true;
                }

                return false;
        }

        /**
         * Patch URL string references in a method
         */
        private static boolean patchUrlReferences(MethodNode method, String className) {
                InsnList insns = method.instructions;
                boolean patched = false;

                for (AbstractInsnNode insn : insns.toArray()) {
                        // Patch GETFIELD sessionServiceUrl -> DualAuthHelper.getSessionUrl()
                        // This handles cases where the URL is built from this.sessionServiceUrl field
                        // e.g., URI.create(this.sessionServiceUrl + "/server-join/auth-grant")
                        if (insn.getOpcode() == Opcodes.GETFIELD) {
                                FieldInsnNode fi = (FieldInsnNode) insn;
                                if (fi.name.equals("sessionServiceUrl") && fi.desc.equals("Ljava/lang/String;")) {
                                        // We need to replace:
                                        // ALOAD 0 (this)
                                        // GETFIELD sessionServiceUrl
                                        // With:
                                        // INVOKESTATIC DualAuthHelper.getSessionUrl()

                                        // Find and remove the preceding ALOAD 0 (this)
                                        AbstractInsnNode prev = insn.getPrevious();
                                        while (prev != null && prev.getOpcode() == -1) {
                                                prev = prev.getPrevious(); // Skip labels/frames
                                        }

                                        // Replace GETFIELD with INVOKESTATIC and remove the ALOAD 0
                                        InsnList replacement = new InsnList();
                                        replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                                        HELPER_CLASS, "getSessionUrl", "()Ljava/lang/String;", false));

                                        insns.insert(insn, replacement);
                                        insns.remove(insn);

                                        // Remove the ALOAD 0 that loaded 'this' for the field access
                                        if (prev != null && prev.getOpcode() == Opcodes.ALOAD) {
                                                VarInsnNode aload = (VarInsnNode) prev;
                                                if (aload.var == 0) {
                                                        insns.remove(prev);
                                                }
                                        }

                                        patched = true;
                                        patchCount++;
                                        System.out.println("  [" + className
                                                        + "] Replaced GETFIELD sessionServiceUrl in " + method.name
                                                        + "() -> DualAuthHelper.getSessionUrl()");
                                }
                        } else if (insn.getOpcode() == Opcodes.LDC) {
                                LdcInsnNode ldc = (LdcInsnNode) insn;
                                if (ldc.cst instanceof String) {
                                        String str = (String) ldc.cst;

                                        // IMPORTANT: Never patch OAuth URLs - /auth login MUST use hytale.com
                                        if (str.contains("oauth.accounts") || str.contains("accounts.hytale.com") ||
                                                        str.contains("/consent/") || str.contains("/oauth2/")) {
                                                continue;
                                        }

                                        // Replace session URL with dynamic routing
                                        if (str.equals(OFFICIAL_SESSION_URL)) {
                                                InsnList replacement = new InsnList();
                                                replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                                                HELPER_CLASS, "getSessionUrl", "()Ljava/lang/String;",
                                                                false));
                                                insns.insert(insn, replacement);
                                                insns.remove(insn);
                                                patched = true;
                                                patchCount++;
                                                System.out.println("  [" + className + "] Replaced URL in "
                                                                + method.name
                                                                + "() -> DualAuthHelper.getSessionUrl()");
                                        }
                                        // Replace URL paths with dynamic routing
                                        else if (str.startsWith(OFFICIAL_SESSION_URL + "/")) {
                                                InsnList replacement = new InsnList();
                                                replacement.add(new LdcInsnNode(str));
                                                replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                                                HELPER_CLASS, "resolveUrl",
                                                                "(Ljava/lang/String;)Ljava/lang/String;", false));
                                                insns.insert(insn, replacement);
                                                insns.remove(insn);
                                                patched = true;
                                                patchCount++;
                                                String path = str.substring(OFFICIAL_SESSION_URL.length());
                                                System.out.println("  [" + className + "] Replaced URL" + path + " in "
                                                                + method.name
                                                                + "() -> DualAuthHelper.resolveUrl()");
                                        }
                                }
                        }
                }

                return patched;
        }

        /**
         * Patch generic class with session URL references
         */
        private static byte[] patchGenericClass(String name, byte[] classBytes) {
                try {
                        ClassReader reader = new ClassReader(classBytes);
                        ClassNode classNode = new ClassNode();
                        reader.accept(classNode, ClassReader.EXPAND_FRAMES);

                        boolean modified = false;
                        String shortName = name.substring(name.lastIndexOf('/') + 1).replace(".class", "");

                        for (MethodNode method : classNode.methods) {
                                if (method.instructions == null)
                                        continue;

                                if (patchUrlReferences(method, shortName)) {
                                        modified = true;
                                        patchedMethods.add(shortName + "." + method.name);
                                }
                        }

                        if (modified) {
                                // Use COMPUTE_FRAMES to properly compute stack map frames for JVM verification
                                ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES);
                                classNode.accept(writer);
                                return writer.toByteArray();
                        }

                } catch (Exception e) {
                        System.out.println("  Error patching " + name + ": " + e.getMessage());
                        if (verbose)
                                e.printStackTrace();
                }

                return null;
        }

        /**
         * Check if a class is OAuth-related and should NOT be patched.
         * /auth login device flow MUST use official hytale.com URLs.
         */
        private static boolean isOAuthClass(String className) {
                // OAuth client and related classes - these handle /auth login
                if (className.contains("/oauth/") ||
                                className.contains("OAuth") ||
                                className.contains("AuthConfig") ||
                                className.contains("AuthCredentialStore")) {
                        System.out.println("  [SKIP] OAuth class (keeping hytale.com URLs): " + className);
                        return true;
                }
                return false;
        }

        /**
         * Check if class bytes contain a specific string
         */
        private static boolean containsString(byte[] classBytes, String search) {
                try {
                        ClassReader reader = new ClassReader(classBytes);
                        ClassNode classNode = new ClassNode();
                        reader.accept(classNode, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

                        for (FieldNode field : classNode.fields) {
                                if (field.value instanceof String) {
                                        if (((String) field.value).contains(search)) {
                                                return true;
                                        }
                                }
                        }

                        for (MethodNode method : classNode.methods) {
                                if (method.instructions == null)
                                        continue;
                                for (AbstractInsnNode insn : method.instructions) {
                                        if (insn.getOpcode() == Opcodes.LDC) {
                                                LdcInsnNode ldc = (LdcInsnNode) insn;
                                                if (ldc.cst instanceof String) {
                                                        if (((String) ldc.cst).contains(search)) {
                                                                return true;
                                                        }
                                                }
                                        }
                                }
                        }

                        return false;
                } catch (Exception e) {
                        return false;
                }
        }

        private static void addClassToJar(ZipOutputStream zipOut, String name, byte[] bytes) throws IOException {
                ZipEntry entry = new ZipEntry(name);
                zipOut.putNextEntry(entry);
                zipOut.write(bytes);
                zipOut.closeEntry();
                System.out.println("[OK] Added: " + name);
        }

        private static byte[] readAllBytes(InputStream is) throws IOException {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) != -1) {
                        baos.write(buf, 0, len);
                }
                return baos.toByteArray();
        }

        private static void copy(InputStream is, OutputStream os) throws IOException {
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) != -1) {
                        os.write(buf, 0, len);
                }
        }
}
