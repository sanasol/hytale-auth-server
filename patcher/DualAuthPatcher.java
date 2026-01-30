import org.objectweb.asm.*;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

/**
 * Hytale Server Dual Authentication Patcher v9.1 (Fixed)
 *
 * TRUE DUAL AUTH: Supports BOTH official hytale.com AND F2P tokens
 *
 * This patcher:
 * 1. Injects DualJwksFetcher class that fetches JWKS from BOTH backends and merges keys
 * 2. Patches JWTValidator.fetchJwksFromService() to use DualJwksFetcher
 * 3. Patches issuer validation to accept BOTH hytale.com AND F2P issuers
 * 4. Injects DualAuthContext for per-request routing of authorization requests
 * 5. Patches SessionServiceClient to route auth requests based on token issuer
 * 6. Patches ServerAuthManager getters to fallback to F2P tokens if official ones are missing
 * 7. Updates DualServerTokenManager to check ThreadLocal context for correct token selection
 * 8. Enhanced HandshakeHandler to intercept INVOKEVIRTUAL calls in addition to GETFIELD
 *
 * Flow:
 *   Token arrives -> Signature verified against merged JWKS (both backends' keys)
 *   Issuer check -> Accepts both hytale.com and F2P issuer
 *   Handshake -> ServerAuthManager returns appropriate Server Session Token (Official or F2P)
 *   Auth Grant -> Signed with the Session Token corresponding to the client's issuer
 */
public class DualAuthPatcher {

    private static final String F2P_DOMAIN = System.getenv("HYTALE_AUTH_DOMAIN") != null
        ? System.getenv("HYTALE_AUTH_DOMAIN") : "auth.sanasol.ws";

    private static final String OFFICIAL_SESSION_URL = "https://sessions.hytale.com";
    private static final String OFFICIAL_ISSUER = "https://sessions.hytale.com";

    // F2P always uses single endpoint without subdomains (all traffic routes to one domain)
    // Official hytale.com keeps original subdomain behavior (sessions., account-data., etc.)
    private static final String F2P_SESSION_URL = "https://" + F2P_DOMAIN;
    private static final String F2P_ISSUER = F2P_SESSION_URL;

    // Base domain for backward compatibility (accepts sessions.sanasol.ws, auth.sanasol.ws, etc.)
    // Extracts "sanasol.ws" from "auth.sanasol.ws"
    private static final String F2P_BASE_DOMAIN = extractBaseDomain(F2P_DOMAIN);

    private static String extractBaseDomain(String domain) {
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

    // Package for injected classes
    private static final String AUTH_PKG = "com/hypixel/hytale/server/core/auth/";
    private static final String JWKS_FETCHER_CLASS = AUTH_PKG + "DualJwksFetcher";
    private static final String CONTEXT_CLASS = AUTH_PKG + "DualAuthContext";
    private static final String HELPER_CLASS = AUTH_PKG + "DualAuthHelper";
    private static final String SERVER_IDENTITY_CLASS = AUTH_PKG + "DualServerIdentity";
    private static final String TOKEN_MANAGER_CLASS = AUTH_PKG + "DualServerTokenManager";

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
                // If either type is not found (TypeNotPresentException extends RuntimeException),
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
        System.out.println("|     Hytale Server TRUE Dual Authentication Patcher v9.1       |");
        System.out.println("+---------------------------------------------------------------+");
        System.out.println();
        System.out.println("Input:  " + inputJar);
        System.out.println("Output: " + outputJar);
        System.out.println();
        System.out.println("Configuration:");
        System.out.println("  Official: " + OFFICIAL_SESSION_URL + " (issuer: " + OFFICIAL_ISSUER + ")");
        System.out.println("  F2P:      " + F2P_SESSION_URL + " (issuer: " + F2P_ISSUER + ")");
        System.out.println("  F2P Base: *." + F2P_BASE_DOMAIN + " (backward compatible issuers)");
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
                if (!name.endsWith(".class")) continue;

                byte[] classBytes = readAllBytes(zipIn.getInputStream(entry));

                // Check for target classes
                String className = name.replace(".class", "");

                // Patch JWTValidator
                if (className.equals(JWT_VALIDATOR_CLASS)) {
                    System.out.println("Found JWTValidator - will patch JWKS fetching and issuer validation");
                    foundClasses.add(name);
                    byte[] patched = patchJWTValidator(classBytes);
                    if (patched != null) {
                        patchedClasses.put(name, patched);
                    }
                }
                // Patch SessionServiceClient
                else if (className.equals(SESSION_SERVICE_CLIENT_CLASS)) {
                    System.out.println("Found SessionServiceClient - will patch authorization routing");
                    foundClasses.add(name);
                    byte[] patched = patchSessionServiceClient(classBytes);
                    if (patched != null) {
                        patchedClasses.put(name, patched);
                    }
                }
                // Look for HandshakeHandler
                else if (className.contains("HandshakeHandler")) {
                    System.out.println("Found HandshakeHandler - will patch to use dual token manager");
                    foundClasses.add(name);
                    byte[] patched = patchHandshakeHandler(classBytes);
                    if (patched != null) {
                        patchedClasses.put(name, patched);
                    }
                }
                // Patch AuthGrant packet to suppress serverIdentityToken for F2P clients
                else if (className.equals(AUTH_GRANT_CLASS)) {
                    System.out.println("Found AuthGrant - will patch to suppress server identity for F2P");
                    foundClasses.add(name);
                    byte[] patched = patchAuthGrant(classBytes);
                    if (patched != null) {
                        patchedClasses.put(name, patched);
                    }
                }
                // Patch ServerAuthManager to auto-fetch F2P tokens and route via DualServerTokenManager
                else if (className.equals(SERVER_AUTH_MANAGER_CLASS)) {
                    System.out.println("Found ServerAuthManager - will patch for dual token management");
                    foundClasses.add(name);
                    byte[] patched = patchServerAuthManager(classBytes);
                    if (patched != null) {
                        patchedClasses.put(name, patched);
                    }
                }
                // Also check other classes for issuer URL patterns
                // IMPORTANT: Skip OAuth-related classes - /auth login MUST use official hytale.com
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

        System.out.println();
        System.out.println("--- Phase 3: Writing Patched JAR ---");
        System.out.println();

        try (ZipFile zipIn = new ZipFile(inputJar);
             ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(tempOutput.toFile()))) {

            // Add generated classes first
            addClassToJar(zipOut, CONTEXT_CLASS + ".class", contextBytes);
            addClassToJar(zipOut, HELPER_CLASS + ".class", helperBytes);
            addClassToJar(zipOut, JWKS_FETCHER_CLASS + ".class", fetcherBytes);
            addClassToJar(zipOut, SERVER_IDENTITY_CLASS + ".class", serverIdentityBytes);
            addClassToJar(zipOut, TOKEN_MANAGER_CLASS + ".class", tokenManagerBytes);

            // Set of generated class paths to skip when copying (in case JAR was already patched)
            Set<String> generatedClasses = new HashSet<>();
            generatedClasses.add(CONTEXT_CLASS + ".class");
            generatedClasses.add(HELPER_CLASS + ".class");
            generatedClasses.add(JWKS_FETCHER_CLASS + ".class");
            generatedClasses.add(SERVER_IDENTITY_CLASS + ".class");
            generatedClasses.add(TOKEN_MANAGER_CLASS + ".class");

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
        System.out.println("|     Patching Complete - TRUE DUAL AUTH ENABLED                |");
        System.out.println("+---------------------------------------------------------------+");
        System.out.println();
        System.out.println("Statistics:");
        System.out.println("  Classes found with auth URLs: " + foundClasses.size());
        System.out.println("  Classes patched: " + patchedClasses.size());
        System.out.println("  Total patches applied: " + patchCount);
        System.out.println("  Methods modified: " + patchedMethods.size());
        System.out.println();
        System.out.println("Found classes:");
        for (String c : foundClasses) {
            System.out.println("  - " + c);
        }
        System.out.println();
        System.out.println("The server now supports BOTH authentication sources:");
        System.out.println("  [OK] JWKS loaded from BOTH " + OFFICIAL_SESSION_URL + " AND " + F2P_SESSION_URL);
        System.out.println("  [OK] Tokens accepted from both hytale.com and *." + F2P_BASE_DOMAIN + " issuers");
        System.out.println("       (backward compatible: sessions." + F2P_BASE_DOMAIN + ", " + F2P_DOMAIN + ", etc.)");
        System.out.println("  [OK] Authorization requests routed based on token issuer");
    }

    /**
     * Generate DualAuthContext class - Thread-local storage for current issuer
     */
    private static byte[] generateDualAuthContext() {
        ClassWriter cw = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
            CONTEXT_CLASS, null, "java/lang/Object", null);

        // private static final ThreadLocal<String> currentIssuer = new ThreadLocal<>();
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
            "currentIssuer", "Ljava/lang/ThreadLocal;", null, null).visitEnd();

        // Static initializer
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/ThreadLocal");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/ThreadLocal", "<init>", "()V", false);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, CONTEXT_CLASS, "currentIssuer", "Ljava/lang/ThreadLocal;");
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
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/ThreadLocal", "set", "(Ljava/lang/Object;)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 1);
        mv.visitEnd();

        // public static String getIssuer()
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "getIssuer", "()Ljava/lang/String;", null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, CONTEXT_CLASS, "currentIssuer", "Ljava/lang/ThreadLocal;");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/ThreadLocal", "get", "()Ljava/lang/Object;", false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String");
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(1, 0);
        mv.visitEnd();

        // public static void clear()
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "clear", "()V", null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, CONTEXT_CLASS, "currentIssuer", "Ljava/lang/ThreadLocal;");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/ThreadLocal", "remove", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 0);
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
        mv.visitFrame(Opcodes.F_APPEND, 1, new Object[]{"java/lang/String"}, 0, null);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitLdcInsn("hytale.com");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "contains",
            "(Ljava/lang/CharSequence;)Z", false);
        Label returnFalse = new Label();
        mv.visitJumpInsn(Opcodes.IFNE, returnFalse);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitLabel(returnFalse);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
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

        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
            HELPER_CLASS, null, "java/lang/Object", null);

        // Constants
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
            "OFFICIAL_URL", "Ljava/lang/String;", null, OFFICIAL_SESSION_URL).visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
            "F2P_URL", "Ljava/lang/String;", null, F2P_SESSION_URL).visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
            "OFFICIAL_ISSUER", "Ljava/lang/String;", null, OFFICIAL_ISSUER).visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
            "F2P_ISSUER", "Ljava/lang/String;", null, F2P_ISSUER).visitEnd();

        // Private constructor
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null);
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
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitLdcInsn("hytale.com");
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

        // Debug: Print the issuer being validated and expected domains
        // System.out.println("[DualAuth] Validating issuer: " + issuer + " (accepts: hytale.com or *." + F2P_BASE_DOMAIN + ")");
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn("[DualAuth] Validating issuer: ");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0); // issuer parameter
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitLdcInsn(" (accepts: hytale.com or *." + F2P_BASE_DOMAIN + ")");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

        mv.visitVarInsn(Opcodes.ALOAD, 0);
        Label checkNotNull = new Label();
        mv.visitJumpInsn(Opcodes.IFNONNULL, checkNotNull);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitLabel(checkNotNull);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        // Check if contains hytale.com
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitLdcInsn("hytale.com");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "contains",
            "(Ljava/lang/CharSequence;)Z", false);
        Label notOfficial = new Label();
        mv.visitJumpInsn(Opcodes.IFEQ, notOfficial);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitLabel(notOfficial);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        // Check if contains F2P base domain (sanasol.ws) for backward compatibility
        // This accepts both auth.sanasol.ws (new) and sessions.sanasol.ws (old)
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitLdcInsn(F2P_BASE_DOMAIN);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "contains",
            "(Ljava/lang/CharSequence;)Z", false);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(2, 1);
        mv.visitEnd();

        // public static String getSessionUrl()
        // Returns the session URL based on current context's issuer
        // For backward compatibility: returns the actual issuer (e.g., sessions.sanasol.ws)
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
        mv.visitLdcInsn(OFFICIAL_SESSION_URL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(getSessionUrlNotNull);
        mv.visitFrame(Opcodes.F_APPEND, 1, new Object[]{"java/lang/String"}, 0, null);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_CLASS, "isOfficialIssuer",
            "(Ljava/lang/String;)Z", false);
        Label getSessionUrlF2p = new Label();
        mv.visitJumpInsn(Opcodes.IFEQ, getSessionUrlF2p);
        mv.visitLdcInsn(OFFICIAL_SESSION_URL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(getSessionUrlF2p);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        // For F2P: return the issuer itself (it's the session URL)
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();

        // public static String getSessionUrlForIssuer(String issuer)
        // For backward compatibility: return the actual issuer URL (e.g., sessions.sanasol.ws)
        // instead of hardcoded F2P URL, so requests go back to the original auth server
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "getSessionUrlForIssuer", "(Ljava/lang/String;)Ljava/lang/String;", null, null);
        mv.visitCode();
        // if (issuer == null) return OFFICIAL_SESSION_URL
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        Label issuerNotNull = new Label();
        mv.visitJumpInsn(Opcodes.IFNONNULL, issuerNotNull);
        mv.visitLdcInsn(OFFICIAL_SESSION_URL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(issuerNotNull);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        // if (isOfficialIssuer(issuer)) return OFFICIAL_SESSION_URL
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_CLASS, "isOfficialIssuer",
            "(Ljava/lang/String;)Z", false);
        Label returnIssuer = new Label();
        mv.visitJumpInsn(Opcodes.IFEQ, returnIssuer);
        mv.visitLdcInsn(OFFICIAL_SESSION_URL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(returnIssuer);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        // For F2P: return the issuer itself (it's the session URL)
        // This preserves backward compatibility: sessions.sanasol.ws -> sessions.sanasol.ws
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
        // F2P uses unified endpoint - replace sessions.hytale.com with just the F2P domain
        mv.visitLdcInsn("sessions.hytale.com");
        mv.visitLdcInsn(F2P_DOMAIN);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "replace",
            "(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(keepOriginal);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
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
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);

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
        mv.visitFrame(Opcodes.F_APPEND, 1, new Object[]{Opcodes.INTEGER}, 0, null);

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
        mv.visitFrame(Opcodes.F_APPEND, 1, new Object[]{Opcodes.INTEGER}, 0, null);

        // String payloadB64 = token.substring(firstDot + 1, secondDot);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IADD);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring", "(II)Ljava/lang/String;", false);
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
        mv.visitFrame(Opcodes.F_FULL, 7,
            new Object[]{"java/lang/String", Opcodes.INTEGER, Opcodes.INTEGER, "java/lang/String", "[B", "java/lang/String", Opcodes.INTEGER},
            0, new Object[]{});

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
        mv.visitFrame(Opcodes.F_APPEND, 2, new Object[]{Opcodes.INTEGER, Opcodes.INTEGER}, 0, null);

        // return payload.substring(start, end);
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitVarInsn(Opcodes.ILOAD, 7);
        mv.visitVarInsn(Opcodes.ILOAD, 8);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring", "(II)Ljava/lang/String;", false);
        mv.visitLabel(tryEnd);
        mv.visitInsn(Opcodes.ARETURN);

        // } catch (Exception e) { return null; }
        mv.visitLabel(catchHandler);
        mv.visitFrame(Opcodes.F_FULL, 1, new Object[]{"java/lang/String"}, 1, new Object[]{"java/lang/Exception"});
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
        // System.out.println("[DualAuth] AuthGrant.serialize() - isF2P=" + isF2P() + ", issuer=" + getIssuer());
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn("[DualAuth] AuthGrant.serialize() - isF2P=");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, CONTEXT_CLASS, "isF2P", "()Z", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Z)Ljava/lang/StringBuilder;", false);
        mv.visitLdcInsn(", issuer=");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, CONTEXT_CLASS, "getIssuer", "()Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

        // System.out.println("[DualAuth] Original serverIdentityToken: " + truncate(token));
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn("[DualAuth] Original serverIdentityToken: ");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_CLASS, "truncateToken", "(Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

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
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_CLASS, "truncateToken", "(Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
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
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        // if (token.length() <= 50) return token + "...";
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
        mv.visitIntInsn(Opcodes.BIPUSH, 50);
        Label tooLong = new Label();
        mv.visitJumpInsn(Opcodes.IF_ICMPGT, tooLong);
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
        mv.visitLdcInsn("...");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(tooLong);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        // return token.substring(0, 50) + "...";
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitIntInsn(Opcodes.BIPUSH, 50);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring", "(II)Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
        mv.visitLdcInsn("...");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(4, 1);
        mv.visitEnd();

        // public static void maybeReplaceServerIdentity(AuthGrant packet)
        // Checks if authorizationGrant is from F2P and replaces serverIdentityToken if needed
        // This works regardless of thread since we check the actual token content
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "maybeReplaceServerIdentity", "(L" + AUTH_GRANT_CLASS + ";)V", null, null);
        mv.visitCode();

        // String authGrant = packet.authorizationGrant;
        mv.visitVarInsn(Opcodes.ALOAD, 0); // packet
        mv.visitFieldInsn(Opcodes.GETFIELD, AUTH_GRANT_CLASS, "authorizationGrant", "Ljava/lang/String;");
        mv.visitVarInsn(Opcodes.ASTORE, 1); // authGrant

        // String issuer = extractIssuerFromToken(authGrant);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_CLASS, "extractIssuerFromToken",
            "(Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitVarInsn(Opcodes.ASTORE, 2); // issuer

        // Log: "[DualAuth] AuthGrant.serialize() - authGrant issuer=" + issuer
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn("[DualAuth] AuthGrant.serialize() - authGrant issuer=");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitLdcInsn(", serverIdentityToken=");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, AUTH_GRANT_CLASS, "serverIdentityToken", "Ljava/lang/String;");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_CLASS, "truncateToken", "(Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

        // if (issuer == null) return; // Can't determine, keep as-is
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        Label replaceIssuerNotNull = new Label();
        mv.visitJumpInsn(Opcodes.IFNONNULL, replaceIssuerNotNull);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitLabel(replaceIssuerNotNull);
        mv.visitFrame(Opcodes.F_APPEND, 2, new Object[]{"java/lang/String", "java/lang/String"}, 0, null);

        // if (!issuer.contains(F2P_BASE_DOMAIN)) return; // Not F2P, keep hytale.com identity
        // Use F2P_BASE_DOMAIN (sanasol.ws) for backward compatibility with sessions.sanasol.ws
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitLdcInsn(F2P_BASE_DOMAIN);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "contains",
            "(Ljava/lang/CharSequence;)Z", false);
        Label isF2P = new Label();
        mv.visitJumpInsn(Opcodes.IFNE, isF2P);
        // Log: Not F2P, keeping original
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        mv.visitLdcInsn("[DualAuth] Not F2P issuer (no " + F2P_BASE_DOMAIN + "), keeping original serverIdentityToken");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        mv.visitInsn(Opcodes.RETURN);

        mv.visitLabel(isF2P);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);

        // Log: F2P detected, fetching F2P identity from the authGrant's issuer
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn("[DualAuth] F2P issuer detected, fetching server identity from: ");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 2); // issuer
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

        // String f2pIdentity = DualServerIdentity.getIdentityTokenForUrl(issuer);
        // Pass the issuer URL so we fetch from the correct backend (backward compat)
        mv.visitVarInsn(Opcodes.ALOAD, 2); // issuer
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, SERVER_IDENTITY_CLASS, "getIdentityTokenForUrl",
            "(Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitVarInsn(Opcodes.ASTORE, 3); // f2pIdentity

        // Log what we got
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn("[DualAuth] F2P identity from server: ");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_CLASS, "truncateToken", "(Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

        // if (f2pIdentity == null) return;
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        Label f2pNotNull = new Label();
        mv.visitJumpInsn(Opcodes.IFNONNULL, f2pNotNull);
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        mv.visitLdcInsn("[DualAuth] WARNING: Failed to get F2P identity, keeping original");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        mv.visitInsn(Opcodes.RETURN);

        mv.visitLabel(f2pNotNull);
        mv.visitFrame(Opcodes.F_APPEND, 1, new Object[]{"java/lang/String"}, 0, null);

        // Log: Replacing serverIdentityToken
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        mv.visitLdcInsn("[DualAuth] Replacing serverIdentityToken with F2P identity");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

        // packet.serverIdentityToken = f2pIdentity;
        mv.visitVarInsn(Opcodes.ALOAD, 0); // packet
        mv.visitVarInsn(Opcodes.ALOAD, 3); // f2pIdentity
        mv.visitFieldInsn(Opcodes.PUTFIELD, AUTH_GRANT_CLASS, "serverIdentityToken", "Ljava/lang/String;");

        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(4, 4);
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
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

        // return getSessionUrlForIssuer(issuer);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_CLASS, "getSessionUrlForIssuer",
            "(Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(4, 2);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Generate DualJwksFetcher class - Fetches JWKS from BOTH backends and merges keys
     *
     * This is the KEY class that enables dual auth - it fetches keys from both
     * hytale.com and sanasol.ws, merges them, and returns the combined set.
     */
    private static byte[] generateDualJwksFetcher() {
        ClassWriter cw = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
            JWKS_FETCHER_CLASS, null, "java/lang/Object", null);

        // Static fields for URLs
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
            "OFFICIAL_JWKS_URL", "Ljava/lang/String;", null, OFFICIAL_SESSION_URL + "/.well-known/jwks.json").visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
            "F2P_JWKS_URL", "Ljava/lang/String;", null, F2P_SESSION_URL + "/.well-known/jwks.json").visitEnd();

        // Private constructor
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();

        // public static String fetchJwksJson(String url)
        // Fetches JWKS JSON from a single URL
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "fetchJwksJson", "(Ljava/lang/String;)Ljava/lang/String;", null, null);
        mv.visitCode();

        // Debug: Print the URL being fetched
        // System.out.println("[DualAuth] Fetching JWKS from: " + url);
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn("[DualAuth] Fetching JWKS from: ");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0); // url parameter
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

        // HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        // Note: HttpClient.newBuilder() is a static method on an abstract class, so itf=false
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/net/http/HttpClient", "newBuilder",
            "()Ljava/net/http/HttpClient$Builder;", false);
        mv.visitLdcInsn(5L);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/time/Duration", "ofSeconds",
            "(J)Ljava/time/Duration;", false);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/net/http/HttpClient$Builder", "connectTimeout",
            "(Ljava/time/Duration;)Ljava/net/http/HttpClient$Builder;", true);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/net/http/HttpClient$Builder", "build",
            "()Ljava/net/http/HttpClient;", true);
        mv.visitVarInsn(Opcodes.ASTORE, 1);

        // HttpRequest request = HttpRequest.newBuilder()
        //     .uri(URI.create(url))
        //     .header("Accept", "application/json")
        //     .timeout(Duration.ofSeconds(5))
        //     .GET()
        //     .build();
        // Note: HttpRequest.newBuilder() is a static method on an abstract class, so itf=false
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/net/http/HttpRequest", "newBuilder",
            "()Ljava/net/http/HttpRequest$Builder;", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/net/URI", "create",
            "(Ljava/lang/String;)Ljava/net/URI;", false);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/net/http/HttpRequest$Builder", "uri",
            "(Ljava/net/URI;)Ljava/net/http/HttpRequest$Builder;", true);
        mv.visitLdcInsn("Accept");
        mv.visitLdcInsn("application/json");
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/net/http/HttpRequest$Builder", "header",
            "(Ljava/lang/String;Ljava/lang/String;)Ljava/net/http/HttpRequest$Builder;", true);
        mv.visitLdcInsn(5L);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/time/Duration", "ofSeconds",
            "(J)Ljava/time/Duration;", false);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/net/http/HttpRequest$Builder", "timeout",
            "(Ljava/time/Duration;)Ljava/net/http/HttpRequest$Builder;", true);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/net/http/HttpRequest$Builder", "GET",
            "()Ljava/net/http/HttpRequest$Builder;", true);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/net/http/HttpRequest$Builder", "build",
            "()Ljava/net/http/HttpRequest;", true);
        mv.visitVarInsn(Opcodes.ASTORE, 2);

        // HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        // Note: BodyHandlers.ofString() is a static method on a class, so itf=false
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/net/http/HttpResponse$BodyHandlers", "ofString",
            "()Ljava/net/http/HttpResponse$BodyHandler;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/net/http/HttpClient", "send",
            "(Ljava/net/http/HttpRequest;Ljava/net/http/HttpResponse$BodyHandler;)Ljava/net/http/HttpResponse;", false);
        mv.visitVarInsn(Opcodes.ASTORE, 3);

        // if (response.statusCode() != 200) return null;
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/net/http/HttpResponse", "statusCode", "()I", true);
        mv.visitIntInsn(Opcodes.SIPUSH, 200);
        Label statusOk = new Label();
        mv.visitJumpInsn(Opcodes.IF_ICMPEQ, statusOk);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);

        mv.visitLabel(statusOk);
        mv.visitFrame(Opcodes.F_APPEND, 3, new Object[]{"java/net/http/HttpClient", "java/net/http/HttpRequest", "java/net/http/HttpResponse"}, 0, null);

        // return (String) response.body();
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/net/http/HttpResponse", "body", "()Ljava/lang/Object;", true);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String");
        mv.visitInsn(Opcodes.ARETURN);

        mv.visitMaxs(4, 4);
        mv.visitEnd();

        // public static String fetchMergedJwksJson()
        // Fetches JWKS from both backends and merges them
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "fetchMergedJwksJson", "()Ljava/lang/String;", null, null);
        mv.visitCode();

        // Print detailed log message with configured URLs
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        mv.visitLdcInsn("[DualAuth] Fetching JWKS from both backends...");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

        // Print Official JWKS URL
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn("[DualAuth] Official JWKS URL: ");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
        mv.visitFieldInsn(Opcodes.GETSTATIC, JWKS_FETCHER_CLASS, "OFFICIAL_JWKS_URL", "Ljava/lang/String;");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

        // Print F2P JWKS URL
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn("[DualAuth] F2P JWKS URL: ");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
        mv.visitFieldInsn(Opcodes.GETSTATIC, JWKS_FETCHER_CLASS, "F2P_JWKS_URL", "Ljava/lang/String;");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

        // String officialJson = fetchJwksJson(OFFICIAL_JWKS_URL);
        mv.visitFieldInsn(Opcodes.GETSTATIC, JWKS_FETCHER_CLASS, "OFFICIAL_JWKS_URL", "Ljava/lang/String;");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, JWKS_FETCHER_CLASS, "fetchJwksJson",
            "(Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitVarInsn(Opcodes.ASTORE, 0);

        // String f2pJson = fetchJwksJson(F2P_JWKS_URL);
        mv.visitFieldInsn(Opcodes.GETSTATIC, JWKS_FETCHER_CLASS, "F2P_JWKS_URL", "Ljava/lang/String;");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, JWKS_FETCHER_CLASS, "fetchJwksJson",
            "(Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitVarInsn(Opcodes.ASTORE, 1);

        // Log results
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn("[DualAuth] Official JWKS: ");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        Label officialNotNull = new Label();
        mv.visitJumpInsn(Opcodes.IFNONNULL, officialNotNull);
        mv.visitLdcInsn("FAILED");
        Label afterOfficialCheck = new Label();
        mv.visitJumpInsn(Opcodes.GOTO, afterOfficialCheck);
        mv.visitLabel(officialNotNull);
        mv.visitFrame(Opcodes.F_FULL, 2, new Object[]{"java/lang/String", "java/lang/String"}, 3,
            new Object[]{"java/io/PrintStream", "java/lang/StringBuilder", "java/lang/StringBuilder"});
        mv.visitLdcInsn("OK");
        mv.visitLabel(afterOfficialCheck);
        mv.visitFrame(Opcodes.F_FULL, 2, new Object[]{"java/lang/String", "java/lang/String"}, 4,
            new Object[]{"java/io/PrintStream", "java/lang/StringBuilder", "java/lang/StringBuilder", "java/lang/String"});
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
            "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn("[DualAuth] F2P JWKS: ");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        Label f2pNotNull = new Label();
        mv.visitJumpInsn(Opcodes.IFNONNULL, f2pNotNull);
        mv.visitLdcInsn("FAILED");
        Label afterF2pCheck = new Label();
        mv.visitJumpInsn(Opcodes.GOTO, afterF2pCheck);
        mv.visitLabel(f2pNotNull);
        mv.visitFrame(Opcodes.F_FULL, 2, new Object[]{"java/lang/String", "java/lang/String"}, 3,
            new Object[]{"java/io/PrintStream", "java/lang/StringBuilder", "java/lang/StringBuilder"});
        mv.visitLdcInsn("OK");
        mv.visitLabel(afterF2pCheck);
        mv.visitFrame(Opcodes.F_FULL, 2, new Object[]{"java/lang/String", "java/lang/String"}, 4,
            new Object[]{"java/io/PrintStream", "java/lang/StringBuilder", "java/lang/StringBuilder", "java/lang/String"});
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
            "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

        // Merge the keys
        // return mergeJwks(officialJson, f2pJson);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, JWKS_FETCHER_CLASS, "mergeJwks",
            "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitInsn(Opcodes.ARETURN);

        mv.visitMaxs(5, 2);
        mv.visitEnd();

        // public static String mergeJwks(String json1, String json2)
        // Merges two JWKS JSON strings into one
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "mergeJwks", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", null, null);
        mv.visitCode();

        // Handle null cases
        // if (json1 == null && json2 == null) return null;
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        Label json1NotNull = new Label();
        mv.visitJumpInsn(Opcodes.IFNONNULL, json1NotNull);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        Label json2NotNullAlone = new Label();
        mv.visitJumpInsn(Opcodes.IFNONNULL, json2NotNullAlone);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);

        // if (json1 == null) return json2;
        mv.visitLabel(json2NotNullAlone);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.ARETURN);

        mv.visitLabel(json1NotNull);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);

        // if (json2 == null) return json1;
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        Label bothNotNull = new Label();
        mv.visitJumpInsn(Opcodes.IFNONNULL, bothNotNull);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ARETURN);

        mv.visitLabel(bothNotNull);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);

        // Extract keys arrays from both JSONs
        // We'll do simple string manipulation to merge them:
        // Find "keys":[...] in each and combine

        // int idx1 = json1.indexOf("\"keys\":");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitLdcInsn("\"keys\":");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "indexOf", "(Ljava/lang/String;)I", false);
        mv.visitVarInsn(Opcodes.ISTORE, 2);

        // int idx2 = json2.indexOf("\"keys\":");
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitLdcInsn("\"keys\":");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "indexOf", "(Ljava/lang/String;)I", false);
        mv.visitVarInsn(Opcodes.ISTORE, 3);

        // if (idx1 < 0) return json2;
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        Label idx1Ok = new Label();
        mv.visitJumpInsn(Opcodes.IFGE, idx1Ok);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.ARETURN);

        mv.visitLabel(idx1Ok);
        mv.visitFrame(Opcodes.F_APPEND, 2, new Object[]{Opcodes.INTEGER, Opcodes.INTEGER}, 0, null);

        // if (idx2 < 0) return json1;
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        Label idx2Ok = new Label();
        mv.visitJumpInsn(Opcodes.IFGE, idx2Ok);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ARETURN);

        mv.visitLabel(idx2Ok);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);

        // Find the array contents
        // int start1 = json1.indexOf('[', idx1) + 1;
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitIntInsn(Opcodes.BIPUSH, '[');
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "indexOf", "(II)I", false);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IADD);
        mv.visitVarInsn(Opcodes.ISTORE, 4);

        // int end1 = json1.lastIndexOf(']');
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitIntInsn(Opcodes.BIPUSH, ']');
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "lastIndexOf", "(I)I", false);
        mv.visitVarInsn(Opcodes.ISTORE, 5);

        // int start2 = json2.indexOf('[', idx2) + 1;
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitIntInsn(Opcodes.BIPUSH, '[');
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "indexOf", "(II)I", false);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IADD);
        mv.visitVarInsn(Opcodes.ISTORE, 6);

        // int end2 = json2.lastIndexOf(']');
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitIntInsn(Opcodes.BIPUSH, ']');
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "lastIndexOf", "(I)I", false);
        mv.visitVarInsn(Opcodes.ISTORE, 7);

        // String keys1 = json1.substring(start1, end1).trim();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitVarInsn(Opcodes.ILOAD, 5);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring", "(II)Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "trim", "()Ljava/lang/String;", false);
        mv.visitVarInsn(Opcodes.ASTORE, 8);

        // String keys2 = json2.substring(start2, end2).trim();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 6);
        mv.visitVarInsn(Opcodes.ILOAD, 7);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring", "(II)Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "trim", "()Ljava/lang/String;", false);
        mv.visitVarInsn(Opcodes.ASTORE, 9);

        // Build merged JSON
        // StringBuilder sb = new StringBuilder();
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
        mv.visitVarInsn(Opcodes.ASTORE, 10);

        // sb.append("{\"keys\":[");
        mv.visitVarInsn(Opcodes.ALOAD, 10);
        mv.visitLdcInsn("{\"keys\":[");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
            "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitInsn(Opcodes.POP);

        // if (!keys1.isEmpty()) sb.append(keys1);
        mv.visitVarInsn(Opcodes.ALOAD, 8);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "isEmpty", "()Z", false);
        Label keys1Empty = new Label();
        mv.visitJumpInsn(Opcodes.IFNE, keys1Empty);
        mv.visitVarInsn(Opcodes.ALOAD, 10);
        mv.visitVarInsn(Opcodes.ALOAD, 8);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
            "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitInsn(Opcodes.POP);

        mv.visitLabel(keys1Empty);
        mv.visitFrame(Opcodes.F_FULL, 11,
            new Object[]{"java/lang/String", "java/lang/String", Opcodes.INTEGER, Opcodes.INTEGER,
                Opcodes.INTEGER, Opcodes.INTEGER, Opcodes.INTEGER, Opcodes.INTEGER,
                "java/lang/String", "java/lang/String", "java/lang/StringBuilder"},
            0, new Object[]{});

        // if (!keys1.isEmpty() && !keys2.isEmpty()) sb.append(",");
        mv.visitVarInsn(Opcodes.ALOAD, 8);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "isEmpty", "()Z", false);
        Label skipComma = new Label();
        mv.visitJumpInsn(Opcodes.IFNE, skipComma);
        mv.visitVarInsn(Opcodes.ALOAD, 9);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "isEmpty", "()Z", false);
        mv.visitJumpInsn(Opcodes.IFNE, skipComma);
        mv.visitVarInsn(Opcodes.ALOAD, 10);
        mv.visitLdcInsn(",");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
            "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitInsn(Opcodes.POP);

        mv.visitLabel(skipComma);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);

        // if (!keys2.isEmpty()) sb.append(keys2);
        mv.visitVarInsn(Opcodes.ALOAD, 9);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "isEmpty", "()Z", false);
        Label keys2Empty = new Label();
        mv.visitJumpInsn(Opcodes.IFNE, keys2Empty);
        mv.visitVarInsn(Opcodes.ALOAD, 10);
        mv.visitVarInsn(Opcodes.ALOAD, 9);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
            "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitInsn(Opcodes.POP);

        mv.visitLabel(keys2Empty);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);

        // sb.append("]}");
        mv.visitVarInsn(Opcodes.ALOAD, 10);
        mv.visitLdcInsn("]}");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
            "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitInsn(Opcodes.POP);

        // String result = sb.toString();
        mv.visitVarInsn(Opcodes.ALOAD, 10);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
        mv.visitVarInsn(Opcodes.ASTORE, 11);

        // Log result
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        mv.visitLdcInsn("[DualAuth] Merged JWKS with keys from both backends");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

        // return result;
        mv.visitVarInsn(Opcodes.ALOAD, 11);
        mv.visitInsn(Opcodes.ARETURN);

        mv.visitMaxs(4, 12);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Generate DualServerIdentity class - Fetches and caches F2P server identity token
     *
     * This class allows the game server to have a separate identity for F2P clients.
     * It fetches the server identity from the F2P auth server (sanasol.ws).
     */
    private static byte[] generateDualServerIdentity() {
        ClassWriter cw = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
            SERVER_IDENTITY_CLASS, null, "java/lang/Object", null);

        // Static fields
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE,
            "f2pIdentityToken", "Ljava/lang/String;", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE,
            "f2pSessionToken", "Ljava/lang/String;", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
            "lastFetchTime", "J", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
            "CACHE_TTL_MS", "J", null, 300000L).visitEnd(); // 5 minutes

        // Constants
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
            "F2P_SESSION_URL", "Ljava/lang/String;", null, F2P_SESSION_URL).visitEnd();

        // Private constructor
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null);
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

        // if (f2pIdentityToken != null && (System.currentTimeMillis() - lastFetchTime) < CACHE_TTL_MS) return f2pIdentityToken;
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
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);

        // Try to fetch new identity
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, SERVER_IDENTITY_CLASS, "fetchF2PServerIdentity", "()V", false);

        // Return whatever we have (may still be null)
        mv.visitFieldInsn(Opcodes.GETSTATIC, SERVER_IDENTITY_CLASS, "f2pIdentityToken", "Ljava/lang/String;");
        mv.visitInsn(Opcodes.ARETURN);

        mv.visitMaxs(4, 0);
        mv.visitEnd();

        // public static String getIdentityTokenForUrl(String baseUrl)
        // Fetches identity token from a specific URL (for backward compatibility)
        // This does NOT use the global cache - it always fetches fresh from the given URL
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "getIdentityTokenForUrl", "(Ljava/lang/String;)Ljava/lang/String;", null, null);
        mv.visitCode();

        // if (baseUrl == null) baseUrl = F2P_SESSION_URL;
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        Label urlNotNull = new Label();
        mv.visitJumpInsn(Opcodes.IFNONNULL, urlNotNull);
        mv.visitLdcInsn(F2P_SESSION_URL);
        mv.visitVarInsn(Opcodes.ASTORE, 0);
        mv.visitLabel(urlNotNull);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);

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
        // Fetches server identity from F2P auth server (legacy, uses hardcoded URL)
        mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
            "fetchF2PServerIdentity", "()V", null, null);
        mv.visitCode();

        // Log start
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        mv.visitLdcInsn("[DualAuth] Fetching F2P server identity from " + F2P_SESSION_URL + "...");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

        // HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
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
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
            "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitLdcInsn("\"}");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
            "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
        mv.visitVarInsn(Opcodes.ASTORE, 2); // jsonBody

        // HttpRequest request = HttpRequest.newBuilder()
        //     .uri(URI.create(F2P_SESSION_URL + "/game-session/new"))
        //     .header("Content-Type", "application/json")
        //     .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
        //     .build();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/net/http/HttpRequest", "newBuilder",
            "()Ljava/net/http/HttpRequest$Builder;", false);
        mv.visitLdcInsn(F2P_SESSION_URL + "/game-session/new");
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

        // HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        mv.visitVarInsn(Opcodes.ALOAD, 0); // client
        mv.visitVarInsn(Opcodes.ALOAD, 3); // request
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/net/http/HttpResponse$BodyHandlers", "ofString",
            "()Ljava/net/http/HttpResponse$BodyHandler;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/net/http/HttpClient", "send",
            "(Ljava/net/http/HttpRequest;Ljava/net/http/HttpResponse$BodyHandler;)Ljava/net/http/HttpResponse;", false);
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
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/net/http/HttpResponse", "body", "()Ljava/lang/Object;", true);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String");
        mv.visitVarInsn(Opcodes.ASTORE, 5); // body

        // Extract identityToken using simple parsing
        // int idx = body.indexOf("\"identityToken\":\"");
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitLdcInsn("\"identityToken\":\"");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "indexOf", "(Ljava/lang/String;)I", false);
        mv.visitVarInsn(Opcodes.ISTORE, 6); // idx

        // if (idx >= 0) {
        mv.visitVarInsn(Opcodes.ILOAD, 6);
        Label noIdentityToken = new Label();
        mv.visitJumpInsn(Opcodes.IFLT, noIdentityToken);

        // int start = idx + 17; // length of "identityToken":"
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
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring", "(II)Ljava/lang/String;", false);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, SERVER_IDENTITY_CLASS, "f2pIdentityToken", "Ljava/lang/String;");

        // lastFetchTime = System.currentTimeMillis();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "currentTimeMillis", "()J", false);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, SERVER_IDENTITY_CLASS, "lastFetchTime", "J");

        // Log success
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        mv.visitLdcInsn("[DualAuth] Successfully obtained F2P server identity");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

        // } // end if identityToken found
        mv.visitLabel(noIdentityToken);
        mv.visitFrame(Opcodes.F_APPEND, 3, new Object[]{"java/net/http/HttpClient", "java/lang/String", "java/lang/String"}, 0, null);

        // } // end if status 200
        mv.visitLabel(notOk);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);

        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(5, 9);
        mv.visitEnd();

        // private static String getServerUuid()
        // Returns the server's UUID (from AuthConfig or generates one)
        mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
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
        mv.visitFrame(Opcodes.F_APPEND, 1, new Object[]{"java/lang/String"}, 0, null);

        // Generate a random UUID if not set
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/UUID", "randomUUID", "()Ljava/util/UUID;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/UUID", "toString", "()Ljava/lang/String;", false);
        mv.visitInsn(Opcodes.ARETURN);

        mv.visitMaxs(1, 1);
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
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0); // baseUrl
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

        // try {
        Label tryStart = new Label();
        Label tryEnd = new Label();
        Label catchHandler = new Label();
        mv.visitTryCatchBlock(tryStart, tryEnd, catchHandler, "java/lang/Exception");
        mv.visitLabel(tryStart);

        // HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
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
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
            "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitLdcInsn("\"}");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
            "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
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
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
        mv.visitVarInsn(Opcodes.ASTORE, 4); // url

        // HttpRequest request = HttpRequest.newBuilder()
        //     .uri(URI.create(url))
        //     .header("Content-Type", "application/json")
        //     .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
        //     .build();
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

        // HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        mv.visitVarInsn(Opcodes.ALOAD, 1); // client
        mv.visitVarInsn(Opcodes.ALOAD, 5); // request
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/net/http/HttpResponse$BodyHandlers", "ofString",
            "()Ljava/net/http/HttpResponse$BodyHandler;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/net/http/HttpClient", "send",
            "(Ljava/net/http/HttpRequest;Ljava/net/http/HttpResponse$BodyHandler;)Ljava/net/http/HttpResponse;", false);
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
        mv.visitFrame(Opcodes.F_FULL, 7,
            new Object[]{"java/lang/String", "java/net/http/HttpClient", "java/lang/String",
                "java/lang/String", "java/lang/String", "java/net/http/HttpRequest", "java/net/http/HttpResponse"},
            0, null);

        // String body = (String) response.body();
        mv.visitVarInsn(Opcodes.ALOAD, 6);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/net/http/HttpResponse", "body", "()Ljava/lang/Object;", true);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String");
        mv.visitVarInsn(Opcodes.ASTORE, 7); // body

        // Extract identityToken from JSON response
        // int idx = body.indexOf("\"identityToken\":\"");
        mv.visitVarInsn(Opcodes.ALOAD, 7);
        mv.visitLdcInsn("\"identityToken\":\"");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "indexOf", "(Ljava/lang/String;)I", false);
        mv.visitVarInsn(Opcodes.ISTORE, 8); // idx

        // if (idx < 0) return null;
        mv.visitVarInsn(Opcodes.ILOAD, 8);
        Label idxOk = new Label();
        mv.visitJumpInsn(Opcodes.IFGE, idxOk);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);

        mv.visitLabel(idxOk);
        mv.visitFrame(Opcodes.F_FULL, 9,
            new Object[]{"java/lang/String", "java/net/http/HttpClient", "java/lang/String",
                "java/lang/String", "java/lang/String", "java/net/http/HttpRequest",
                "java/net/http/HttpResponse", "java/lang/String", Opcodes.INTEGER},
            0, null);

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
        mv.visitFrame(Opcodes.F_FULL, 11,
            new Object[]{"java/lang/String", "java/net/http/HttpClient", "java/lang/String",
                "java/lang/String", "java/lang/String", "java/net/http/HttpRequest",
                "java/net/http/HttpResponse", "java/lang/String", Opcodes.INTEGER,
                Opcodes.INTEGER, Opcodes.INTEGER},
            0, null);

        // String identityToken = body.substring(start, end);
        mv.visitVarInsn(Opcodes.ALOAD, 7);
        mv.visitVarInsn(Opcodes.ILOAD, 9);
        mv.visitVarInsn(Opcodes.ILOAD, 10);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring", "(II)Ljava/lang/String;", false);
        mv.visitVarInsn(Opcodes.ASTORE, 11); // identityToken

        // Log success
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        mv.visitLdcInsn("[DualAuth] Successfully obtained identity from URL");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

        mv.visitLabel(tryEnd);
        // return identityToken;
        mv.visitVarInsn(Opcodes.ALOAD, 11);
        mv.visitInsn(Opcodes.ARETURN);

        // } catch (Exception e) {
        mv.visitLabel(catchHandler);
        mv.visitFrame(Opcodes.F_FULL, 1, new Object[]{"java/lang/String"}, 1, new Object[]{"java/lang/Exception"});
        mv.visitVarInsn(Opcodes.ASTORE, 1); // e

        // Log error
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn("[DualAuth] Error fetching identity: ");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Exception", "getMessage", "()Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

        // return null;
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);

        mv.visitMaxs(4, 12);
    }

    /**
     * Generate DualServerTokenManager class - Stores both official and F2P server tokens
     *
     * This class manages dual token sets:
     * - Official tokens: obtained via /auth login (from hytale.com)
     * - F2P tokens: auto-fetched from sanasol.ws on startup
     *
     * MODIFIED: Changed from background thread to synchronous fetch during static init
     * to ensure tokens are available immediately at server startup.
     */
    private static byte[] generateDualServerTokenManager() {
        ClassWriter cw = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

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

        // Flag to track if F2P tokens have been fetched
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE,
            "f2pTokensFetched", "Z", null, null).visitEnd();

        // Constants for URLs
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
            "F2P_AUTH_URL", "Ljava/lang/String;", null, F2P_SESSION_URL).visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
            "OFFICIAL_AUTH_URL", "Ljava/lang/String;", null, OFFICIAL_SESSION_URL).visitEnd();

        // Private constructor
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();

        // public static void setOfficialTokens(String sessionToken, String identityToken)
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNCHRONIZED,
            "setOfficialTokens", "(Ljava/lang/String;Ljava/lang/String;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, TOKEN_MANAGER_CLASS, "officialSessionToken", "Ljava/lang/String;");
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, TOKEN_MANAGER_CLASS, "officialIdentityToken", "Ljava/lang/String;");
        // Log
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        mv.visitLdcInsn("[DualAuth] Official tokens set (from /auth login)");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
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
        // Log
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        mv.visitLdcInsn("[DualAuth] F2P tokens set (auto-fetched from " + F2P_SESSION_URL + ")");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();

        // public static String getSessionTokenForIssuer(String issuer)
        // Returns the appropriate session token based on issuer
        // MODIFIED: Enhanced fallback logic to ensure tokens are always available
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "getSessionTokenForIssuer", "(Ljava/lang/String;)Ljava/lang/String;", null, null);
        mv.visitCode();

        // if (issuer == null) issuer = DualAuthContext.getIssuer();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        Label issuerNotNull = new Label();
        mv.visitJumpInsn(Opcodes.IFNONNULL, issuerNotNull);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, CONTEXT_CLASS, "getIssuer", "()Ljava/lang/String;", false);
        mv.visitVarInsn(Opcodes.ASTORE, 0);
        mv.visitLabel(issuerNotNull);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);

        // Log the call for debugging
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn("[DualAuth] getSessionTokenForIssuer called with issuer: ");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

        // if (issuer == null) {
        //   // No issuer info, try official first, fallback to F2P
        //   if (officialSessionToken != null) return officialSessionToken;
        //   return f2pSessionToken;
        // }
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        Label issuerNotNull2 = new Label();
        mv.visitJumpInsn(Opcodes.IFNONNULL, issuerNotNull2);

        // issuer is null - try official first, then F2P
        mv.visitFieldInsn(Opcodes.GETSTATIC, TOKEN_MANAGER_CLASS, "officialSessionToken", "Ljava/lang/String;");
        Label returnOfficialIfAvailable = new Label();
        mv.visitJumpInsn(Opcodes.IFNONNULL, returnOfficialIfAvailable);

        // No official token, use F2P
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        mv.visitLdcInsn("[DualAuth] No issuer provided and no official token, using F2P token");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        mv.visitFieldInsn(Opcodes.GETSTATIC, TOKEN_MANAGER_CLASS, "f2pSessionToken", "Ljava/lang/String;");
        mv.visitInsn(Opcodes.ARETURN);

        mv.visitLabel(returnOfficialIfAvailable);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        mv.visitLdcInsn("[DualAuth] No issuer provided, using official token");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        mv.visitFieldInsn(Opcodes.GETSTATIC, TOKEN_MANAGER_CLASS, "officialSessionToken", "Ljava/lang/String;");
        mv.visitInsn(Opcodes.ARETURN);

        mv.visitLabel(issuerNotNull2);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);

        // Check if issuer is from F2P (contains sanasol.ws)
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitLdcInsn("sanasol.ws");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "contains",
            "(Ljava/lang/CharSequence;)Z", false);
        Label issuerIsF2P = new Label();
        mv.visitJumpInsn(Opcodes.IFEQ, issuerIsF2P);

        // Issuer is F2P - return F2P token
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        mv.visitLdcInsn("[DualAuth] Issuer is F2P, using F2P token");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        mv.visitFieldInsn(Opcodes.GETSTATIC, TOKEN_MANAGER_CLASS, "f2pSessionToken", "Ljava/lang/String;");
        mv.visitInsn(Opcodes.ARETURN);

        mv.visitLabel(issuerIsF2P);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);

        // Issuer is not F2P (or doesn't contain sanasol.ws) - check if it's hytale.com
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitLdcInsn("hytale.com");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "contains",
            "(Ljava/lang/CharSequence;)Z", false);
        Label issuerIsOfficial = new Label();
        mv.visitJumpInsn(Opcodes.IFEQ, issuerIsOfficial);

        // Issuer is official hytale.com - check if we have official token
        mv.visitFieldInsn(Opcodes.GETSTATIC, TOKEN_MANAGER_CLASS, "officialSessionToken", "Ljava/lang/String;");
        Label hasOfficialToken = new Label();
        mv.visitJumpInsn(Opcodes.IFNONNULL, hasOfficialToken);

        // No official token for hytale.com issuer - log warning and fallback to F2P
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn("[DualAuth] WARNING: Official token requested for hytale.com issuer but not available, falling back to F2P token. Use /auth login to get official tokens.");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        mv.visitFieldInsn(Opcodes.GETSTATIC, TOKEN_MANAGER_CLASS, "f2pSessionToken", "Ljava/lang/String;");
        mv.visitInsn(Opcodes.ARETURN);

        mv.visitLabel(hasOfficialToken);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        mv.visitLdcInsn("[DualAuth] Issuer is official hytale.com, using official token");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        mv.visitFieldInsn(Opcodes.GETSTATIC, TOKEN_MANAGER_CLASS, "officialSessionToken", "Ljava/lang/String;");
        mv.visitInsn(Opcodes.ARETURN);

        mv.visitLabel(issuerIsOfficial);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);

        // Unknown issuer - fallback to F2P (most compatible)
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn("[DualAuth] Unknown issuer '");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitLdcInsn("', falling back to F2P token");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        mv.visitFieldInsn(Opcodes.GETSTATIC, TOKEN_MANAGER_CLASS, "f2pSessionToken", "Ljava/lang/String;");
        mv.visitInsn(Opcodes.ARETURN);

        mv.visitMaxs(4, 1);
        mv.visitEnd();

        // public static String getIdentityTokenForIssuer(String issuer)
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "getIdentityTokenForIssuer", "(Ljava/lang/String;)Ljava/lang/String;", null, null);
        mv.visitCode();
        
        // if (issuer == null) issuer = DualAuthContext.getIssuer();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        Label idIssuerNotNull = new Label();
        mv.visitJumpInsn(Opcodes.IFNONNULL, idIssuerNotNull);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, CONTEXT_CLASS, "getIssuer", "()Ljava/lang/String;", false);
        mv.visitVarInsn(Opcodes.ASTORE, 0);
        mv.visitLabel(idIssuerNotNull);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        
        // Log the call for debugging
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn("[DualAuth] getIdentityTokenForIssuer called with issuer: ");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

        // if (issuer == null) return f2pIdentityToken; // Default to F2P for safety
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        Label checkOfficial2 = new Label();
        mv.visitJumpInsn(Opcodes.IFNONNULL, checkOfficial2);
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        mv.visitLdcInsn("[DualAuth] No issuer provided for identity token, defaulting to F2P");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        mv.visitFieldInsn(Opcodes.GETSTATIC, TOKEN_MANAGER_CLASS, "f2pIdentityToken", "Ljava/lang/String;");
        mv.visitInsn(Opcodes.ARETURN);
        
        mv.visitLabel(checkOfficial2);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        
        // Check if issuer is from F2P (contains sanasol.ws)
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitLdcInsn("sanasol.ws");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "contains",
            "(Ljava/lang/CharSequence;)Z", false);
        Label identityIssuerIsF2P = new Label();
        mv.visitJumpInsn(Opcodes.IFEQ, identityIssuerIsF2P);

        // Issuer is F2P - return F2P identity token
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        mv.visitLdcInsn("[DualAuth] Issuer is F2P, using F2P identity token");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        mv.visitFieldInsn(Opcodes.GETSTATIC, TOKEN_MANAGER_CLASS, "f2pIdentityToken", "Ljava/lang/String;");
        mv.visitInsn(Opcodes.ARETURN);

        mv.visitLabel(identityIssuerIsF2P);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);

        // Check if issuer is hytale.com and we have official token
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitLdcInsn("hytale.com");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "contains",
            "(Ljava/lang/CharSequence;)Z", false);
        Label identityIssuerIsOfficial = new Label();
        mv.visitJumpInsn(Opcodes.IFEQ, identityIssuerIsOfficial);

        // Issuer is official - check if we have official identity token
        mv.visitFieldInsn(Opcodes.GETSTATIC, TOKEN_MANAGER_CLASS, "officialIdentityToken", "Ljava/lang/String;");
        Label hasOfficialIdentityToken = new Label();
        mv.visitJumpInsn(Opcodes.IFNONNULL, hasOfficialIdentityToken);

        // No official identity token - fallback to F2P with warning
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        mv.visitLdcInsn("[DualAuth] WARNING: Official identity token requested but not available, using F2P identity token");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        mv.visitFieldInsn(Opcodes.GETSTATIC, TOKEN_MANAGER_CLASS, "f2pIdentityToken", "Ljava/lang/String;");
        mv.visitInsn(Opcodes.ARETURN);

        mv.visitLabel(hasOfficialIdentityToken);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        mv.visitLdcInsn("[DualAuth] Issuer is official hytale.com, using official identity token");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        mv.visitFieldInsn(Opcodes.GETSTATIC, TOKEN_MANAGER_CLASS, "officialIdentityToken", "Ljava/lang/String;");
        mv.visitInsn(Opcodes.ARETURN);

        mv.visitLabel(identityIssuerIsOfficial);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);

        // Unknown issuer - fallback to F2P (most compatible)
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn("[DualAuth] Unknown issuer '");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitLdcInsn("', falling back to F2P identity token");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        mv.visitFieldInsn(Opcodes.GETSTATIC, TOKEN_MANAGER_CLASS, "f2pIdentityToken", "Ljava/lang/String;");
        mv.visitInsn(Opcodes.ARETURN);
        
        mv.visitMaxs(4, 1);
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
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
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
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
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
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        // fetchF2PTokens();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, TOKEN_MANAGER_CLASS, "fetchF2PTokens", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 0);
        mv.visitEnd();

        // public static void fetchF2PTokens()
        // Makes HTTP request to F2P auth server to get server tokens
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "fetchF2PTokens", "()V", null, null);
        mv.visitCode();

        // Log start
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        mv.visitLdcInsn("[DualAuth] Auto-fetching F2P server tokens from " + F2P_SESSION_URL + "/server/auto-auth...");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

        // try {
        Label tryStart = new Label();
        Label tryEnd = new Label();
        Label catchHandler = new Label();
        mv.visitTryCatchBlock(tryStart, tryEnd, catchHandler, "java/lang/Exception");
        mv.visitLabel(tryStart);

        // String serverUuid = System.getenv("HYTALE_SERVER_AUDIENCE");
        // if (serverUuid == null) serverUuid = UUID.randomUUID().toString();
        mv.visitLdcInsn("HYTALE_SERVER_AUDIENCE");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getenv",
            "(Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitVarInsn(Opcodes.ASTORE, 0); // serverUuid
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        Label hasUuid = new Label();
        mv.visitJumpInsn(Opcodes.IFNONNULL, hasUuid);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/UUID", "randomUUID", "()Ljava/util/UUID;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/UUID", "toString", "()Ljava/lang/String;", false);
        mv.visitVarInsn(Opcodes.ASTORE, 0);
        mv.visitLabel(hasUuid);
        mv.visitFrame(Opcodes.F_APPEND, 1, new Object[]{"java/lang/String"}, 0, null);

        // HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/net/http/HttpClient", "newBuilder",
            "()Ljava/net/http/HttpClient$Builder;", false);
        mv.visitLdcInsn(10L);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/time/Duration", "ofSeconds",
            "(J)Ljava/time/Duration;", false);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/net/http/HttpClient$Builder", "connectTimeout",
            "(Ljava/time/Duration;)Ljava/net/http/HttpClient$Builder;", true);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/net/http/HttpClient$Builder", "build",
            "()Ljava/net/http/HttpClient;", true);
        mv.visitVarInsn(Opcodes.ASTORE, 1); // client

        // String jsonBody = "{\"server_id\":\"" + serverUuid + "\"}";
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn("{\"server_id\":\"");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
            "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitLdcInsn("\"}");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
            "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
        mv.visitVarInsn(Opcodes.ASTORE, 2); // jsonBody

        // HttpRequest request = HttpRequest.newBuilder()
        //     .uri(URI.create(F2P_SESSION_URL + "/server/auto-auth"))
        //     .header("Content-Type", "application/json")
        //     .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
        //     .build();
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
        mv.visitVarInsn(Opcodes.ASTORE, 3); // request

        // HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/net/http/HttpResponse$BodyHandlers", "ofString",
            "()Ljava/net/http/HttpResponse$BodyHandler;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/net/http/HttpClient", "send",
            "(Ljava/net/http/HttpRequest;Ljava/net/http/HttpResponse$BodyHandler;)Ljava/net/http/HttpResponse;", false);
        mv.visitVarInsn(Opcodes.ASTORE, 4); // response

        // if (response.statusCode() == 200) {
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/net/http/HttpResponse", "statusCode", "()I", true);
        mv.visitIntInsn(Opcodes.SIPUSH, 200);
        Label notOk = new Label();
        mv.visitJumpInsn(Opcodes.IF_ICMPNE, notOk);

        // String body = (String) response.body();
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/net/http/HttpResponse", "body", "()Ljava/lang/Object;", true);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String");
        mv.visitVarInsn(Opcodes.ASTORE, 5); // body

        // Parse sessionToken
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitLdcInsn("sessionToken");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, TOKEN_MANAGER_CLASS, "extractJsonField",
            "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitVarInsn(Opcodes.ASTORE, 6); // sessionToken

        // Parse identityToken
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitLdcInsn("identityToken");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, TOKEN_MANAGER_CLASS, "extractJsonField",
            "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitVarInsn(Opcodes.ASTORE, 7); // identityToken

        // setF2PTokens(sessionToken, identityToken);
        mv.visitVarInsn(Opcodes.ALOAD, 6);
        mv.visitVarInsn(Opcodes.ALOAD, 7);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, TOKEN_MANAGER_CLASS, "setF2PTokens",
            "(Ljava/lang/String;Ljava/lang/String;)V", false);

        mv.visitLabel(notOk);
        mv.visitFrame(Opcodes.F_FULL, 5,
            new Object[]{"java/lang/String", "java/net/http/HttpClient", "java/lang/String",
                "java/net/http/HttpRequest", "java/net/http/HttpResponse"},
            0, new Object[]{});

        mv.visitLabel(tryEnd);
        Label afterCatch = new Label();
        mv.visitJumpInsn(Opcodes.GOTO, afterCatch);

        // } catch (Exception e) { log error }
        mv.visitLabel(catchHandler);
        mv.visitFrame(Opcodes.F_FULL, 0, new Object[]{}, 1, new Object[]{"java/lang/Exception"});
        mv.visitVarInsn(Opcodes.ASTORE, 0);
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;");
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn("[DualAuth] Failed to fetch F2P tokens: ");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Exception", "getMessage", "()Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
            "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

        mv.visitLabel(afterCatch);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(5, 8);
        mv.visitEnd();

        // private static String extractJsonField(String json, String field)
        // Simple JSON field extraction without external dependencies
        mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
            "extractJsonField", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", null, null);
        mv.visitCode();
        // String pattern = "\"" + field + "\":\"";
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn("\"");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
            "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitLdcInsn("\":\"");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
            "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
        mv.visitVarInsn(Opcodes.ASTORE, 2); // pattern

        // int idx = json.indexOf(pattern);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "indexOf", "(Ljava/lang/String;)I", false);
        mv.visitVarInsn(Opcodes.ISTORE, 3); // idx

        // if (idx < 0) return null;
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        Label foundIdx = new Label();
        mv.visitJumpInsn(Opcodes.IFGE, foundIdx);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);

        mv.visitLabel(foundIdx);
        mv.visitFrame(Opcodes.F_APPEND, 2, new Object[]{"java/lang/String", Opcodes.INTEGER}, 0, null);

        // int start = idx + pattern.length();
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
        mv.visitInsn(Opcodes.IADD);
        mv.visitVarInsn(Opcodes.ISTORE, 4); // start

        // int end = json.indexOf("\"", start);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitIntInsn(Opcodes.BIPUSH, '"');
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "indexOf", "(II)I", false);
        mv.visitVarInsn(Opcodes.ISTORE, 5); // end

        // if (end < 0) return null;
        mv.visitVarInsn(Opcodes.ILOAD, 5);
        Label foundEnd = new Label();
        mv.visitJumpInsn(Opcodes.IFGE, foundEnd);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);

        mv.visitLabel(foundEnd);
        mv.visitFrame(Opcodes.F_APPEND, 2, new Object[]{Opcodes.INTEGER, Opcodes.INTEGER}, 0, null);

        // return json.substring(start, end);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitVarInsn(Opcodes.ILOAD, 5);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring", "(II)Ljava/lang/String;", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(3, 6);
        mv.visitEnd();

        // public static void logStatus()
        // Debug method to log current token status
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "logStatus", "()V", null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        mv.visitLdcInsn("[DualAuth] Token Status:");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

        // Official tokens
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn("  Official tokens: ");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, TOKEN_MANAGER_CLASS, "hasOfficialTokens", "()Z", false);
        Label hasOfficialLabel = new Label();
        mv.visitJumpInsn(Opcodes.IFEQ, hasOfficialLabel);
        mv.visitLdcInsn("present");
        Label afterOfficialLabel = new Label();
        mv.visitJumpInsn(Opcodes.GOTO, afterOfficialLabel);
        mv.visitLabel(hasOfficialLabel);
        mv.visitFrame(Opcodes.F_FULL, 0, new Object[]{}, 3,
            new Object[]{"java/io/PrintStream", "java/lang/StringBuilder", "java/lang/StringBuilder"});
        mv.visitLdcInsn("missing");
        mv.visitLabel(afterOfficialLabel);
        mv.visitFrame(Opcodes.F_FULL, 0, new Object[]{}, 4,
            new Object[]{"java/io/PrintStream", "java/lang/StringBuilder", "java/lang/StringBuilder", "java/lang/String"});
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
            "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

        // F2P tokens
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn("  F2P tokens: ");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, TOKEN_MANAGER_CLASS, "hasF2PTokens", "()Z", false);
        Label hasF2PLabel = new Label();
        mv.visitJumpInsn(Opcodes.IFEQ, hasF2PLabel);
        mv.visitLdcInsn("present");
        Label afterF2PLabel = new Label();
        mv.visitJumpInsn(Opcodes.GOTO, afterF2PLabel);
        mv.visitLabel(hasF2PLabel);
        mv.visitFrame(Opcodes.F_FULL, 0, new Object[]{}, 3,
            new Object[]{"java/io/PrintStream", "java/lang/StringBuilder", "java/lang/StringBuilder"});
        mv.visitLdcInsn("missing");
        mv.visitLabel(afterF2PLabel);
        mv.visitFrame(Opcodes.F_FULL, 0, new Object[]{}, 4,
            new Object[]{"java/io/PrintStream", "java/lang/StringBuilder", "java/lang/StringBuilder", "java/lang/String"});
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
            "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

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
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, TOKEN_MANAGER_CLASS, "hasOfficialTokens", "()Z", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Z)Ljava/lang/StringBuilder;", false);
        mv.visitLdcInsn(", F2P: ");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, TOKEN_MANAGER_CLASS, "hasF2PTokens", "()Z", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Z)Ljava/lang/StringBuilder;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(4, 0);
        mv.visitEnd();

        // Static initializer to auto-fetch F2P tokens on server startup
        // MODIFIED: Synchronous fetch instead of background thread
        mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        
        // System.out.println("[DualAuth] DualServerTokenManager static init - starting synchronous F2P token fetch");
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        mv.visitLdcInsn("[DualAuth] DualServerTokenManager static init - starting synchronous F2P token fetch");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

        // Call ensureF2PTokens() synchronously
        // This will fetch tokens immediately during class loading
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, TOKEN_MANAGER_CLASS, "ensureF2PTokens", "()V", false);
        
        // Log completion
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        mv.visitLdcInsn("[DualAuth] F2P token fetch completed during static initialization");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 0);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Patch JWTValidator class
     * - Modify fetchJwksFromService to use DualJwksFetcher
     * - Patch issuer validation to accept both issuers
     */
    private static byte[] patchJWTValidator(byte[] classBytes) {
        try {
            ClassReader reader = new ClassReader(classBytes);
            ClassNode classNode = new ClassNode();
            reader.accept(classNode, 0);

            boolean modified = false;

            for (MethodNode method : classNode.methods) {
                if (method.instructions == null) continue;

                // Patch issuer validation in validateToken, validateIdentityToken, validateSessionToken
                if (method.name.equals("validateToken") ||
                    method.name.equals("validateIdentityToken") ||
                    method.name.equals("validateSessionToken")) {

                    if (patchIssuerValidation(method)) {
                        modified = true;
                        patchedMethods.add("JWTValidator." + method.name);
                        System.out.println("  [JWTValidator] Patched issuer validation in " + method.name + "()");
                    }
                }

                // Patch fetchJwksFromService to call DualJwksFetcher
                if (method.name.equals("fetchJwksFromService")) {
                    if (patchFetchJwksFromService(method)) {
                        modified = true;
                        patchedMethods.add("JWTValidator.fetchJwksFromService");
                        System.out.println("  [JWTValidator] Patched fetchJwksFromService() to use DualJwksFetcher");
                    }
                }
            }

            if (modified) {
                // Use COMPUTE_FRAMES to properly compute stack map frames for JVM verification
                ClassWriter writer = new SafeClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
                classNode.accept(writer);
                return writer.toByteArray();
            }

        } catch (Exception e) {
            System.out.println("  Error patching JWTValidator: " + e.getMessage());
            if (verbose) e.printStackTrace();
        }

        return null;
    }

    /**
     * Patch issuer validation to accept both official and F2P issuers
     */
    private static boolean patchIssuerValidation(MethodNode method) {
        InsnList insns = method.instructions;
        boolean patched = false;

        for (AbstractInsnNode insn : insns.toArray()) {
            // Look for: if (!this.expectedIssuer.equals(claims.issuer))
            // Pattern: ALOAD, GETFIELD expectedIssuer, ALOAD, GETFIELD issuer, INVOKEVIRTUAL equals
            if (insn.getOpcode() == Opcodes.INVOKEVIRTUAL) {
                MethodInsnNode mi = (MethodInsnNode) insn;
                if (mi.name.equals("equals") && mi.owner.equals("java/lang/String")) {
                    // Check if this is likely an issuer comparison
                    if (isIssuerComparison(insns, insns.indexOf(insn))) {
                        // Replace String.equals with DualAuthHelper.isValidIssuer
                        // Stack before equals: [expectedIssuer, actualIssuer]
                        // We need: isValidIssuer(actualIssuer)
                        InsnList replacement = new InsnList();
                        replacement.add(new InsnNode(Opcodes.SWAP));    // [actualIssuer, expectedIssuer]
                        replacement.add(new InsnNode(Opcodes.POP));     // [actualIssuer]
                        replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                            HELPER_CLASS, "isValidIssuer", "(Ljava/lang/String;)Z", false));
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
     * Patch fetchJwksFromService to use DualJwksFetcher for merged JWKS
     *
     * Original method calls sessionServiceClient.getJwks() which fetches from one URL.
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
     */
    private static boolean patchFetchJwksFromService(MethodNode method) {
        // Clear existing instructions and replace with our implementation
        method.instructions.clear();

        // Also clear any exception handlers since we're replacing everything
        method.tryCatchBlocks.clear();

        // Create new method body
        InsnList code = new InsnList();

        // Log start
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"));
        code.add(new LdcInsnNode("[DualAuth] fetchJwksFromService() called - using dual JWKS fetcher"));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false));

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
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0)); // this
        code.add(new FieldInsnNode(Opcodes.GETFIELD, JWT_VALIDATOR_CLASS, "cachedJwkSet", "Lcom/nimbusds/jose/jwk/JWKSet;"));
        code.add(new InsnNode(Opcodes.ARETURN));

        code.add(notNull);
        code.add(new FrameNode(Opcodes.F_APPEND, 1, new Object[]{"java/lang/String"}, 0, null));

        // Parse JSON using JwksResponse.CODEC
        // JwksResponse jwks = JwksResponse.CODEC.decodeJson(new RawJsonReader(mergedJson.toCharArray()), EmptyExtraInfo.EMPTY);
        code.add(new FieldInsnNode(Opcodes.GETSTATIC,
            "com/hypixel/hytale/server/core/auth/SessionServiceClient$JwksResponse", "CODEC",
            "Lcom/hypixel/hytale/codec/builder/BuilderCodec;"));
        code.add(new TypeInsnNode(Opcodes.NEW, "com/hypixel/hytale/codec/util/RawJsonReader"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toCharArray", "()[C", false));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "com/hypixel/hytale/codec/util/RawJsonReader", "<init>", "([C)V", false));
        code.add(new FieldInsnNode(Opcodes.GETSTATIC,
            "com/hypixel/hytale/codec/EmptyExtraInfo", "EMPTY", "Lcom/hypixel/hytale/codec/EmptyExtraInfo;"));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
            "com/hypixel/hytale/codec/builder/BuilderCodec", "decodeJson",
            "(Lcom/hypixel/hytale/codec/util/RawJsonReader;Lcom/hypixel/hytale/codec/ExtraInfo;)Ljava/lang/Object;", false));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, "com/hypixel/hytale/server/core/auth/SessionServiceClient$JwksResponse"));
        code.add(new VarInsnNode(Opcodes.ASTORE, 2)); // jwksResponse in var 2

        // if (jwksResponse == null || jwksResponse.keys == null || jwksResponse.keys.length == 0) return this.cachedJwkSet;
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        LabelNode jwksNotNull = new LabelNode();
        code.add(new JumpInsnNode(Opcodes.IFNONNULL, jwksNotNull));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, JWT_VALIDATOR_CLASS, "cachedJwkSet", "Lcom/nimbusds/jose/jwk/JWKSet;"));
        code.add(new InsnNode(Opcodes.ARETURN));

        code.add(jwksNotNull);
        code.add(new FrameNode(Opcodes.F_APPEND, 1, new Object[]{"com/hypixel/hytale/server/core/auth/SessionServiceClient$JwksResponse"}, 0, null));

        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new FieldInsnNode(Opcodes.GETFIELD,
            "com/hypixel/hytale/server/core/auth/SessionServiceClient$JwksResponse", "keys",
            "[Lcom/hypixel/hytale/server/core/auth/SessionServiceClient$JwkKey;"));
        LabelNode keysNotNull = new LabelNode();
        code.add(new JumpInsnNode(Opcodes.IFNONNULL, keysNotNull));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, JWT_VALIDATOR_CLASS, "cachedJwkSet", "Lcom/nimbusds/jose/jwk/JWKSet;"));
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
        code.add(new FieldInsnNode(Opcodes.GETFIELD, JWT_VALIDATOR_CLASS, "cachedJwkSet", "Lcom/nimbusds/jose/jwk/JWKSet;"));
        code.add(new InsnNode(Opcodes.ARETURN));

        code.add(keysNotEmpty);
        code.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));

        // Log success
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"));
        code.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new LdcInsnNode("[DualAuth] Got merged JWKS with "));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new FieldInsnNode(Opcodes.GETFIELD,
            "com/hypixel/hytale/server/core/auth/SessionServiceClient$JwksResponse", "keys",
            "[Lcom/hypixel/hytale/server/core/auth/SessionServiceClient$JwkKey;"));
        code.add(new InsnNode(Opcodes.ARRAYLENGTH));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(I)Ljava/lang/StringBuilder;", false));
        code.add(new LdcInsnNode(" keys"));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false));

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
            new Object[]{"java/util/ArrayList", "[Lcom/hypixel/hytale/server/core/auth/SessionServiceClient$JwkKey;", Opcodes.INTEGER, Opcodes.INTEGER},
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
            "(Lcom/hypixel/hytale/server/core/auth/SessionServiceClient$JwkKey;)Lcom/nimbusds/jose/jwk/JWK;", false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 8)); // jwk in var 8

        // if (jwk != null) jwkList.add(jwk);
        code.add(new VarInsnNode(Opcodes.ALOAD, 8));
        LabelNode skipAdd = new LabelNode();
        code.add(new JumpInsnNode(Opcodes.IFNULL, skipAdd));
        code.add(new VarInsnNode(Opcodes.ALOAD, 3)); // jwkList
        code.add(new VarInsnNode(Opcodes.ALOAD, 8)); // jwk
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "add", "(Ljava/lang/Object;)Z", false));
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
        code.add(new FieldInsnNode(Opcodes.GETFIELD, JWT_VALIDATOR_CLASS, "cachedJwkSet", "Lcom/nimbusds/jose/jwk/JWKSet;"));
        code.add(new InsnNode(Opcodes.ARETURN));

        code.add(listNotEmpty);
        code.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));

        // JWKSet newSet = new JWKSet(jwkList);
        code.add(new TypeInsnNode(Opcodes.NEW, "com/nimbusds/jose/jwk/JWKSet"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new VarInsnNode(Opcodes.ALOAD, 3)); // jwkList
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "com/nimbusds/jose/jwk/JWKSet", "<init>", "(Ljava/util/List;)V", false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 4)); // newSet in var 4 (reusing)

        // this.cachedJwkSet = newSet;
        code.add(new VarInsnNode(Opcodes.ALOAD, 0)); // this
        code.add(new VarInsnNode(Opcodes.ALOAD, 4)); // newSet
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, JWT_VALIDATOR_CLASS, "cachedJwkSet", "Lcom/nimbusds/jose/jwk/JWKSet;"));

        // this.lastJwksRefresh = Instant.now();
        // Note: Field changed from jwksCacheExpiry (long) to lastJwksRefresh (Instant) in newer versions
        code.add(new VarInsnNode(Opcodes.ALOAD, 0)); // this
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/time/Instant", "now", "()Ljava/time/Instant;", false));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, JWT_VALIDATOR_CLASS, "lastJwksRefresh", "Ljava/time/Instant;"));

        // Log final success
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"));
        code.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new LdcInsnNode("[DualAuth] JWKSet created with "));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "size", "()I", false));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(I)Ljava/lang/StringBuilder;", false));
        code.add(new LdcInsnNode(" JWK keys from dual backends"));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false));

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
     * Patch HandshakeHandler to use DualServerTokenManager for server session tokens
     */
    private static byte[] patchHandshakeHandler(byte[] classBytes) {
        try {
            ClassReader reader = new ClassReader(classBytes);
            ClassNode classNode = new ClassNode();
            reader.accept(classNode, 0);

            boolean modified = false;

            for (MethodNode method : classNode.methods) {
                if (method.instructions == null) continue;

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
                        System.out.println("  [HandshakeHandler] Patched " + method.name + "() to use dual token manager");
                    }
                }

                // Also patch any URL references
                if (patchUrlReferences(method, "HandshakeHandler")) {
                    modified = true;
                }
            }

            if (modified) {
                ClassWriter writer = new SafeClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
                classNode.accept(writer);
                return writer.toByteArray();
            }

        } catch (Exception e) {
            System.out.println("  Error patching HandshakeHandler: " + e.getMessage());
            if (verbose) e.printStackTrace();
        }

        return null;
    }

    /**
     * Patch individual HandshakeHandler method to use DualServerTokenManager
     */
    private static boolean patchHandshakeHandlerMethod(MethodNode method) {
        InsnList insns = method.instructions;
        boolean patched = false;

        // Look for patterns where server session token is accessed
        for (AbstractInsnNode insn : insns.toArray()) {
            // Look for field access to session tokens
            if (insn.getOpcode() == Opcodes.GETFIELD) {
                FieldInsnNode fi = (FieldInsnNode) insn;
                if (fi.name.contains("Session") && fi.name.contains("Token")) {
                    // Replace with call to DualServerTokenManager
                    InsnList replacement = new InsnList();
                    
                    // Log
                    replacement.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"));
                    replacement.add(new LdcInsnNode("[DualAuth] HandshakeHandler - replacing session token access with DualServerTokenManager"));
                    replacement.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false));
                    
                    // Call DualServerTokenManager.getSessionTokenForIssuer(null) for fallback
                    replacement.add(new InsnNode(Opcodes.ACONST_NULL));
                    replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        TOKEN_MANAGER_CLASS, "getSessionTokenForIssuer", "(Ljava/lang/String;)Ljava/lang/String;", false));
                    
                    insns.insert(insn, replacement);
                    insns.remove(insn);
                    patched = true;
                    patchCount++;
                }
            }
            
            // Intercept INVOKEVIRTUAL to ServerAuthManager getters
            else if (insn.getOpcode() == Opcodes.INVOKEVIRTUAL) {
                MethodInsnNode mi = (MethodInsnNode) insn;
                if (mi.owner.equals(SERVER_AUTH_MANAGER_CLASS) && mi.desc.endsWith("Ljava/lang/String;")) {
                    // We don't know the exact name, but if it returns string from AuthManager in handshake, it's likely the token
                    // Wrap the result: check if null, if so use ours
                    InsnList wrap = new InsnList();
                    wrap.add(new InsnNode(Opcodes.DUP));
                    LabelNode valid = new LabelNode();
                    wrap.add(new JumpInsnNode(Opcodes.IFNONNULL, valid));
                    wrap.add(new InsnNode(Opcodes.POP));
                    wrap.add(new InsnNode(Opcodes.ACONST_NULL));
                    wrap.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TOKEN_MANAGER_CLASS, "getSessionTokenForIssuer", "(Ljava/lang/String;)Ljava/lang/String;", false));
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
                            HELPER_CLASS, "getSessionUrl", "()Ljava/lang/String;", false));
                        
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
     * Patch SessionServiceClient constructor to use dynamic URL instead of hardcoded URL
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
                    if (str.equals(OFFICIAL_SESSION_URL) || str.equals("https://sessions.hytale.com")) {
                        // Replace with dynamic URL
                        InsnList replacement = new InsnList();
                        
                        // Log that we're using dynamic URL
                        replacement.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"));
                        replacement.add(new LdcInsnNode("[DualAuth] SessionServiceClient constructor - replacing hardcoded URL with dynamic URL"));
                        replacement.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false));
                        
                        // Call DualAuthHelper.getSessionUrl() which will return the appropriate URL
                        replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                            HELPER_CLASS, "getSessionUrl", "()Ljava/lang/String;", false));
                        
                        // Insert the replacement before the PUTFIELD that sets sessionServiceUrl
                        // Find the PUTFIELD instruction that follows
                        AbstractInsnNode putFieldInsn = findNextInstruction(insn, Opcodes.PUTFIELD);
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
     * Patch SessionServiceClient to route authorization requests based on token issuer
     */
    private static byte[] patchSessionServiceClient(byte[] classBytes) {
        try {
            ClassReader reader = new ClassReader(classBytes);
            ClassNode classNode = new ClassNode();
            reader.accept(classNode, 0);

            boolean modified = false;

            for (MethodNode method : classNode.methods) {
                if (method.instructions == null) continue;

                // Patch constructor to use dynamic URL
                if (method.name.equals("<init>")) {
                    if (patchSessionServiceClientConstructor(method)) {
                        modified = true;
                        patchedMethods.add("SessionServiceClient.<init>");
                        System.out.println("  [SessionServiceClient] Patched constructor to use dynamic URL");
                    }
                }

                // Patch lambda methods for requestAuthorizationGrantAsync
                // Lambda methods are named like lambda$requestAuthorizationGrantAsync$0
                // They capture identityToken and need to set context + use dynamic URL
                if (method.name.startsWith("lambda$requestAuthorizationGrantAsync")) {
                    System.out.println("  [SessionServiceClient] Found lambda: " + method.name + method.desc);

                    // Find identityToken parameter - it's captured in the lambda
                    // The lambda signature is typically: (SessionServiceClient, String identityToken, String, String) -> Object
                    // or it might be a closure where identityToken is a captured variable
                    int identityTokenIndex = findIdentityTokenParam(method);
                    if (identityTokenIndex >= 0) {
                        if (injectContextSetupInLambda(method, identityTokenIndex)) {
                            modified = true;
                            patchedMethods.add("SessionServiceClient." + method.name);
                            System.out.println("  [SessionServiceClient] Injected context setup in " + method.name + "() (token at index " + identityTokenIndex + ")");
                        }
                    }
                }

                // Patch lambda methods for exchangeAuthGrantForTokenAsync
                // This lambda also uses this.sessionServiceUrl and needs context from authorizationGrant token
                if (method.name.startsWith("lambda$exchangeAuthGrantForTokenAsync")) {
                    System.out.println("  [SessionServiceClient] Found lambda: " + method.name + method.desc);

                    // authorizationGrant is the first String param (after 'this')
                    int authGrantIndex = findIdentityTokenParam(method);
                    if (authGrantIndex >= 0) {
                        if (injectContextSetupInLambda(method, authGrantIndex)) {
                            modified = true;
                            patchedMethods.add("SessionServiceClient." + method.name);
                            System.out.println("  [SessionServiceClient] Injected context setup in " + method.name + "() (token at index " + authGrantIndex + ")");
                        }
                    }
                }

                // Patch lambda methods for refreshSessionAsync
                // This lambda needs to use dynamic URL based on the sessionToken's issuer
                // The sessionToken is passed as the first String parameter after 'this'
                if (method.name.startsWith("lambda$refreshSessionAsync")) {
                    System.out.println("  [SessionServiceClient] Found lambda: " + method.name + method.desc);

                    // sessionToken is the first String param (after 'this' if present)
                    int sessionTokenIndex = findIdentityTokenParam(method);
                    if (sessionTokenIndex >= 0) {
                        if (injectRefreshContextSetup(method, sessionTokenIndex)) {
                            modified = true;
                            patchedMethods.add("SessionServiceClient." + method.name);
                            System.out.println("  [SessionServiceClient] Injected refresh context setup in " + method.name + "() (token at index " + sessionTokenIndex + ")");
                        }
                    }
                }

                // Also patch URL references in ALL methods (including lambdas)
                if (patchUrlReferences(method, "SessionServiceClient")) {
                    modified = true;
                }
            }

            if (modified) {
                // Use COMPUTE_FRAMES to properly compute stack map frames for JVM verification
                ClassWriter writer = new SafeClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
                classNode.accept(writer);
                return writer.toByteArray();
            }

        } catch (Exception e) {
            System.out.println("  Error patching SessionServiceClient: " + e.getMessage());
            if (verbose) e.printStackTrace();
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
            reader.accept(classNode, 0);

            boolean modified = false;

            for (MethodNode method : classNode.methods) {
                if (method.instructions == null) continue;

                // Patch serialize() method - inject code at start to nullify serverIdentityToken for F2P
                if (method.name.equals("serialize") && method.desc.equals("(Lio/netty/buffer/ByteBuf;)V")) {
                    if (injectServerIdentityNullifier(method)) {
                        modified = true;
                        patchedMethods.add("AuthGrant.serialize");
                        System.out.println("  [AuthGrant] Patched serialize() to suppress serverIdentityToken for F2P");
                    }
                }
            }

            if (modified) {
                ClassWriter writer = new SafeClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
                classNode.accept(writer);
                return writer.toByteArray();
            }

        } catch (Exception e) {
            System.out.println("  Error patching AuthGrant: " + e.getMessage());
            if (verbose) e.printStackTrace();
        }

        return null;
    }

    /**
     * Patch ServerAuthManager to integrate with DualServerTokenManager.
     *
     * Modifications:
     * 1. In initialize() - call DualServerTokenManager.ensureF2PTokens() to auto-fetch F2P tokens
     * 2. When tokens are set via /auth login - also store in DualServerTokenManager.setOfficialTokens()
     */
    private static byte[] patchServerAuthManager(byte[] classBytes) {
        try {
            ClassReader reader = new ClassReader(classBytes);
            ClassNode classNode = new ClassNode();
            reader.accept(classNode, 0);

            boolean modified = false;

            for (MethodNode method : classNode.methods) {
                if (method.instructions == null) continue;

                // Patch initialize() to log token status
                if (method.name.equals("initialize") && method.desc.equals("()V")) {
                    if (injectF2PTokenFetch(method)) {
                        modified = true;
                        patchedMethods.add("ServerAuthManager.initialize");
                        System.out.println("  [ServerAuthManager] Patched initialize() to log token status");
                    }
                }

                // Patch createGameSessionFromOAuth to store official tokens
                // This is called after successful /auth login
                if (method.name.equals("createGameSessionFromOAuth")) {
                    if (injectOfficialTokenStorage(method)) {
                        modified = true;
                        patchedMethods.add("ServerAuthManager.createGameSessionFromOAuth");
                        System.out.println("  [ServerAuthManager] Patched createGameSessionFromOAuth() to store official tokens");
                    }
                }

                // Patch getter methods to fallback to F2P tokens if null
                if (method.desc.endsWith("Ljava/lang/String;")) {
                    if (patchGetterMethod(method)) {
                        modified = true;
                        System.out.println("  [ServerAuthManager] Patched getter: " + method.name);
                    }
                }
            }

            if (modified) {
                ClassWriter writer = new SafeClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
                classNode.accept(writer);
                return writer.toByteArray();
            }

        } catch (Exception e) {
            System.out.println("  Error patching ServerAuthManager: " + e.getMessage());
            if (verbose) e.printStackTrace();
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
        injection.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false));

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
     * Inject call to store official tokens in DualServerTokenManager after OAuth login
     */
    private static boolean injectOfficialTokenStorage(MethodNode method) {
        // This method sets this.gameSession which contains sessionToken and identityToken
        // We need to extract these and store in DualServerTokenManager

        InsnList injection = new InsnList();

        // Log
        injection.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"));
        injection.add(new LdcInsnNode("[DualAuth] Storing official tokens from /auth login..."));
        injection.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false));

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

        // DualServerTokenManager.setOfficialTokens(session.sessionToken, session.identityToken);
        injection.add(new VarInsnNode(Opcodes.ALOAD, 10));
        injection.add(new FieldInsnNode(Opcodes.GETFIELD,
            "com/hypixel/hytale/server/core/auth/SessionServiceClient$GameSessionResponse",
            "sessionToken", "Ljava/lang/String;"));
        injection.add(new VarInsnNode(Opcodes.ALOAD, 10));
        injection.add(new FieldInsnNode(Opcodes.GETFIELD,
            "com/hypixel/hytale/server/core/auth/SessionServiceClient$GameSessionResponse",
            "identityToken", "Ljava/lang/String;"));
        injection.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
            TOKEN_MANAGER_CLASS, "setOfficialTokens", "(Ljava/lang/String;Ljava/lang/String;)V", false));

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
     * Inspects a method to see if it's a getter for session/identity token.
     * If so, injects fallback logic before return.
     */
    private static boolean patchGetterMethod(MethodNode method) {
        boolean isSessionGetter = false;
        boolean isIdentityGetter = false;

        for (AbstractInsnNode insn : method.instructions) {
            if (insn.getOpcode() == Opcodes.GETFIELD) {
                FieldInsnNode fn = (FieldInsnNode) insn;
                if (fn.owner.equals("com/hypixel/hytale/server/core/auth/SessionServiceClient$GameSessionResponse")) {
                    if (fn.name.equals("sessionToken")) isSessionGetter = true;
                    if (fn.name.equals("identityToken")) isIdentityGetter = true;
                }
            }
        }

        if (!isSessionGetter && !isIdentityGetter) return false;

        // Found a getter. Inject fallback logic before ARETURN.
        // Logic: if (result == null) result = DualServerTokenManager.getToken(null);
        InsnList inject = new InsnList();
        inject.add(new InsnNode(Opcodes.DUP)); // Duplicate result on stack
        LabelNode notNull = new LabelNode();
        inject.add(new JumpInsnNode(Opcodes.IFNONNULL, notNull));
        
        inject.add(new InsnNode(Opcodes.POP)); // Pop null
        inject.add(new InsnNode(Opcodes.ACONST_NULL)); // Pass null issuer (will use Context)
        if (isSessionGetter) {
            inject.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TOKEN_MANAGER_CLASS, "getSessionTokenForIssuer", "(Ljava/lang/String;)Ljava/lang/String;", false));
        } else {
            inject.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TOKEN_MANAGER_CLASS, "getIdentityTokenForIssuer", "(Ljava/lang/String;)Ljava/lang/String;", false));
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
     * Inject code at start of serialize() to replace serverIdentityToken for F2P context.
     *
     * Since ThreadLocal doesn't work across threads (AuthGrant.serialize() is called
     * on a different thread than where we set the context), we determine F2P status
     * by examining the authorizationGrant JWT itself - if it came from F2P backend,
     * we need to use F2P server identity.
     *
     * Injected code:
     *   DualAuthHelper.maybeReplaceServerIdentity(this);
     *
     * Note: Uses COMPUTE_FRAMES so we don't add manual FrameNode entries.
     */
    private static boolean injectServerIdentityNullifier(MethodNode method) {
        InsnList injection = new InsnList();

        // Call DualAuthHelper.maybeReplaceServerIdentity(this)
        // This method checks if authorizationGrant is from F2P and replaces serverIdentityToken if needed
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
        // Descriptor like: (Lcom/hypixel/.../SessionServiceClient;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;

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
            HELPER_CLASS, "extractIssuerFromToken", "(Ljava/lang/String;)Ljava/lang/String;", false));

        // DualAuthContext.setIssuer(issuer);
        injection.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
            CONTEXT_CLASS, "setIssuer", "(Ljava/lang/String;)V", false));

        // Log
        injection.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"));
        injection.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
        injection.add(new InsnNode(Opcodes.DUP));
        injection.add(new LdcInsnNode("[DualAuth] Lambda: issuer from token: "));
        injection.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false));
        injection.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CONTEXT_CLASS, "getIssuer", "()Ljava/lang/String;", false));
        injection.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        injection.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false));
        injection.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false));

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
        injection.add(new LdcInsnNode("[DualAuth] refreshSessionAsync lambda - sessionToken at index " + sessionTokenIndex));
        injection.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false));
        injection.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false));
        injection.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false));

        // Set context from sessionToken so URL resolution works
        // String issuer = DualAuthHelper.extractIssuerFromToken(sessionToken);
        injection.add(new VarInsnNode(Opcodes.ALOAD, sessionTokenIndex));
        injection.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
            HELPER_CLASS, "extractIssuerFromToken", "(Ljava/lang/String;)Ljava/lang/String;", false));

        // DualAuthContext.setIssuer(issuer);
        injection.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
            CONTEXT_CLASS, "setIssuer", "(Ljava/lang/String;)V", false));

        // Log issuer
        injection.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"));
        injection.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
        injection.add(new InsnNode(Opcodes.DUP));
        injection.add(new LdcInsnNode("[DualAuth] Refresh token issuer: "));
        injection.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false));
        injection.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CONTEXT_CLASS, "getIssuer", "()Ljava/lang/String;", false));
        injection.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        injection.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false));
        injection.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false));

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
            HELPER_CLASS, "extractIssuerFromToken", "(Ljava/lang/String;)Ljava/lang/String;", false));

        // DualAuthContext.setIssuer(issuer);
        injection.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
            CONTEXT_CLASS, "setIssuer", "(Ljava/lang/String;)V", false));

        // Log
        injection.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"));
        injection.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
        injection.add(new InsnNode(Opcodes.DUP));
        injection.add(new LdcInsnNode("[DualAuth] Auth request, issuer from token: "));
        injection.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false));
        injection.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CONTEXT_CLASS, "getIssuer", "()Ljava/lang/String;", false));
        injection.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        injection.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false));
        injection.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false));

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
                    //   ALOAD 0 (this)
                    //   GETFIELD sessionServiceUrl
                    // With:
                    //   INVOKESTATIC DualAuthHelper.getSessionUrl()

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
                    System.out.println("  [" + className + "] Replaced GETFIELD sessionServiceUrl in " + method.name + "() -> DualAuthHelper.getSessionUrl()");
                }
            }
            else if (insn.getOpcode() == Opcodes.LDC) {
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
                            HELPER_CLASS, "getSessionUrl", "()Ljava/lang/String;", false));
                        insns.insert(insn, replacement);
                        insns.remove(insn);
                        patched = true;
                        patchCount++;
                        System.out.println("  [" + className + "] Replaced URL in " + method.name + "() -> DualAuthHelper.getSessionUrl()");
                    }
                    // Replace URL paths with dynamic routing
                    else if (str.startsWith(OFFICIAL_SESSION_URL + "/")) {
                        InsnList replacement = new InsnList();
                        replacement.add(new LdcInsnNode(str));
                        replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                            HELPER_CLASS, "resolveUrl", "(Ljava/lang/String;)Ljava/lang/String;", false));
                        insns.insert(insn, replacement);
                        insns.remove(insn);
                        patched = true;
                        patchCount++;
                        String path = str.substring(OFFICIAL_SESSION_URL.length());
                        System.out.println("  [" + className + "] Replaced URL" + path + " in " + method.name + "() -> DualAuthHelper.resolveUrl()");
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
            reader.accept(classNode, 0);

            boolean modified = false;
            String shortName = name.substring(name.lastIndexOf('/') + 1).replace(".class", "");

            for (MethodNode method : classNode.methods) {
                if (method.instructions == null) continue;

                if (patchUrlReferences(method, shortName)) {
                    modified = true;
                    patchedMethods.add(shortName + "." + method.name);
                }
            }

            if (modified) {
                // Use COMPUTE_FRAMES to properly compute stack map frames for JVM verification
                ClassWriter writer = new SafeClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
                classNode.accept(writer);
                return writer.toByteArray();
            }

        } catch (Exception e) {
            System.out.println("  Error patching " + name + ": " + e.getMessage());
            if (verbose) e.printStackTrace();
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
                if (method.instructions == null) continue;
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
