package ws.sanasol.dualauth.context;

import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class DualAuthContext {
    private static final Logger LOGGER = Logger.getLogger("DualAuthAgent");
    
    private static final ThreadLocal<String> currentIssuer = new ThreadLocal<>();
    private static final ThreadLocal<String> currentJwk = new ThreadLocal<>();
    private static final ThreadLocal<String> currentPlayerUuid = new ThreadLocal<>();
    private static final ThreadLocal<String> currentUsername = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> isCurrentTokenOmni = new ThreadLocal<>();

    private static final ConcurrentHashMap<String, String> globalJwkCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> globalPlayerJwkCache = new ConcurrentHashMap<>();

    public static void setIssuer(String issuer) {
        currentIssuer.set(issuer);
    }

    public static String getIssuer() {
        return currentIssuer.get();
    }

    public static void setPlayerUuid(String uuid) {
        currentPlayerUuid.set(uuid);
    }

    public static String getPlayerUuid() {
        return currentPlayerUuid.get();
    }

    public static void setJwk(String jwk) {
        // --- INICIO FIX CRÍTICO OMNI-AUTH ---
        if (jwk == null) {
            currentJwk.remove();
            return;
        }

        // Protección del ThreadLocal:
        // Si el cliente envía primero un token con "d" (Identity) y luego uno sin "d" (AuthToken),
        // no debemos permitir que el segundo sobrescriba al primero en el contexto actual,
        // o perderemos la capacidad de firmar la respuesta.
        String existing = currentJwk.get();
        boolean newHasPrivate = jwk.contains("\"d\"");
        boolean existingHasPrivate = existing != null && existing.contains("\"d\"");

        if (existingHasPrivate && !newHasPrivate) {
            // Mantener la clave privada existente en el ThreadLocal.
            // No hacemos currentJwk.set(jwk).
            if (Boolean.getBoolean("dualauth.debug")) {
                LOGGER.info("DualAuthContext: Preserving existing Private JWK in ThreadLocal against Public-only overwrite.");
            }
        } else {
            currentJwk.set(jwk);
        }
        // --- FIN FIX CRÍTICO ---

        // Lógica de caché global (ya tenía protección, pero el ThreadLocal no)
        String issuer = getIssuer();
        if (issuer != null) {
            globalJwkCache.compute(issuer, (k, existingVal) -> {
                if (existingVal != null && existingVal.contains("\"d\"") && !newHasPrivate) {
                    return existingVal; 
                }
                return jwk;
            });
        }

        String uuid = getPlayerUuid();
        if (uuid != null) {
            globalPlayerJwkCache.compute(uuid, (k, existingVal) -> {
                if (existingVal != null && existingVal.contains("\"d\"") && !newHasPrivate) {
                    return existingVal;
                }
                return jwk;
            });
        }
    }

    public static String getJwk() {
        String jwk = currentJwk.get();
        
        // --- MEJORA DE RECUPERACIÓN ---
        // Si el ThreadLocal tiene una clave, pero le falta la "d", intentamos
        // ver si la caché global tiene una copia MEJOR (con "d") para este usuario/issuer.
        // Esto recupera la clave si el ThreadLocal fue limpiado o sobrescrito incorrectamente.
        if (jwk != null && !jwk.contains("\"d\"")) {
            String uuid = getPlayerUuid();
            if (uuid != null) {
                String cached = globalPlayerJwkCache.get(uuid);
                if (cached != null && cached.contains("\"d\"")) {
                    // Encontrada clave privada en caché, usar esta en lugar de la pública del contexto
                    return cached;
                }
            }
            
            String issuer = getIssuer();
            if (issuer != null) {
                String cached = globalJwkCache.get(issuer);
                if (cached != null && cached.contains("\"d\"")) {
                    return cached;
                }
            }
        }

        if (jwk != null) return jwk;

        // Fallback estándar a cachés si el ThreadLocal es null
        String uuid = getPlayerUuid();
        if (uuid != null) {
            jwk = globalPlayerJwkCache.get(uuid);
            if (jwk != null) return jwk;
        }

        String issuer = getIssuer();
        if (issuer != null) {
            return globalJwkCache.get(issuer);
        }

        return null;
    }

    public static void setUsername(String username) {
        currentUsername.set(username);
    }

    public static String getUsername() {
        return currentUsername.get();
    }

    public static void setOmni(boolean val) {
        isCurrentTokenOmni.set(val);
    }

    public static boolean isOmni() {
        Boolean val = isCurrentTokenOmni.get();
        return val != null && val;
    }

    public static void clear() {
        currentIssuer.remove();
        currentJwk.remove();
        currentPlayerUuid.remove();
        currentUsername.remove();
        isCurrentTokenOmni.remove();
    }

    /**
     * CRITICAL: Resets context for a new connection to prevent Omni-Auth state leakage.
     * This MUST be called at the start of every connection/validation entry point.
     * Without this, ThreadLocal values from a previous player can leak into another
     * player's session when threads are reused by the server's Executor.
     * 
     * Also performs defensive cleanup to prevent memory leaks.
     */
    public static void resetForNewConnection() {
        try {
            // Explicitly set Omni to false BEFORE clearing (prevents race conditions)
            isCurrentTokenOmni.set(Boolean.FALSE);
            
            // Clear thread-local state (but NOT global caches - they're shared intentionally)
            currentIssuer.remove();
            currentJwk.remove();
            currentPlayerUuid.remove();
            currentUsername.remove();
            isCurrentTokenOmni.remove();
            
            // Debug logging if enabled
            if (Boolean.getBoolean("dualauth.debug.connections")) {
                LOGGER.info("Connection boundary: context reset completed");
            }
        } catch (Exception e) {
            // Last resort cleanup - don't let errors prevent context reset
            try {
                currentIssuer.remove();
                currentJwk.remove();
                currentPlayerUuid.remove();
                currentUsername.remove();
                isCurrentTokenOmni.remove();
            } catch (Exception ignored) {
                // If even this fails, log but don't crash
                LOGGER.warning("Critical error in context reset: " + ignored.getMessage());
            }
        }
    }

    /**
     * Soft clear: Clears Omni and JWK state but preserves issuer for method chain continuity.
     * Useful within a single method call chain to avoid state interference.
     */
    public static void softClear() {
        isCurrentTokenOmni.set(Boolean.FALSE);
        currentJwk.remove();
    }

    /**
     * Checks if the current context represents a F2P (sanasol.ws) issuer.
     * This is distinct from Omni-Auth - F2P uses standard JWKS flow but with F2P backend.
     */
    public static boolean isF2P() {
        String issuer = getIssuer();
        if (issuer == null) return false;
        
        // Check against F2P domain from config
        String f2pBase = ws.sanasol.dualauth.agent.DualAuthConfig.F2P_BASE_DOMAIN;
        return f2pBase != null && issuer.contains(f2pBase);
    }
}
