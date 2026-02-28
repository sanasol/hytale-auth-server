package ws.sanasol.dualauth.protection;

import ws.sanasol.dualauth.agent.DualAuthConfig;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Checks the F2P auth server to determine if an identity (UUID/username) is
 * password-protected. Used to block Omni-Auth (self-signed) tokens for
 * protected identities — those players must authenticate via the F2P auth server.
 *
 * Features:
 * - In-memory cache with 60s TTL to avoid hammering the API
 * - Short HTTP timeout (3s) — fail-open if auth server unreachable
 * - Only called for Omni-Auth tokens (not for F2P or Official)
 */
public class IdentityProtectionChecker {
    private static final Logger LOGGER = Logger.getLogger("DualAuthAgent");

    private static final int CACHE_TTL_MS = 60_000; // 60 seconds
    private static final int HTTP_TIMEOUT_MS = 3_000; // 3 seconds

    private static final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private static class CacheEntry {
        final boolean allowed;
        final String reason;
        final long timestamp;

        CacheEntry(boolean allowed, String reason) {
            this.allowed = allowed;
            this.reason = reason;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }

    /**
     * Check if an Omni-Auth token is allowed for this UUID/username.
     *
     * @param uuid     Player UUID from token claims
     * @param username Player username from token claims (may be null)
     * @return true if Omni-Auth is allowed (identity not protected), false if blocked
     */
    public static boolean isOmniAllowed(String uuid, String username) {
        if (uuid == null) return true; // No UUID — can't check, allow

        // Check cache first
        String cacheKey = uuid + "|" + (username != null ? username.toLowerCase() : "");
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            if (!cached.allowed) {
                LOGGER.info("[IdentityProtection] CACHED BLOCK for " + uuid + " (" + username + "): " + cached.reason);
            }
            return cached.allowed;
        }

        // HTTP check against F2P auth server
        boolean allowed = true;
        String reason = null;

        try {
            String baseUrl = DualAuthConfig.F2P_SESSION_URL;
            StringBuilder urlStr = new StringBuilder(baseUrl);
            urlStr.append("/api/check-identity?uuid=").append(URLEncoder.encode(uuid, StandardCharsets.UTF_8));
            if (username != null && !username.isEmpty()) {
                urlStr.append("&username=").append(URLEncoder.encode(username, StandardCharsets.UTF_8));
            }

            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr.toString()).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(HTTP_TIMEOUT_MS);
            conn.setReadTimeout(HTTP_TIMEOUT_MS);

            int code = conn.getResponseCode();
            if (code == 200) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    String json = sb.toString();

                    // Simple JSON parsing (avoid adding dependency)
                    allowed = json.contains("\"allowed\":true") || json.contains("\"allowed\": true");
                    if (!allowed) {
                        // Extract reason
                        int reasonIdx = json.indexOf("\"reason\"");
                        if (reasonIdx >= 0) {
                            int colonIdx = json.indexOf(':', reasonIdx);
                            int quoteStart = json.indexOf('"', colonIdx + 1);
                            int quoteEnd = json.indexOf('"', quoteStart + 1);
                            if (quoteStart >= 0 && quoteEnd > quoteStart) {
                                reason = json.substring(quoteStart + 1, quoteEnd);
                            }
                        }
                        LOGGER.info("[IdentityProtection] BLOCKED Omni-Auth for " + uuid +
                                " (" + username + "): " + (reason != null ? reason : "protected"));
                    }
                }
            } else {
                LOGGER.warning("[IdentityProtection] Auth server returned HTTP " + code + " — fail-open (allowing)");
            }
        } catch (Exception e) {
            // Fail-open: if auth server unreachable, allow Omni-Auth
            LOGGER.warning("[IdentityProtection] Auth server unreachable (" + e.getMessage() + ") — fail-open (allowing)");
        }

        // Cache result
        cache.put(cacheKey, new CacheEntry(allowed, reason));

        // Periodic cleanup of expired entries (every ~100 checks)
        if (cache.size() > 100 && Math.random() < 0.1) {
            cache.entrySet().removeIf(e -> e.getValue().isExpired());
        }

        return allowed;
    }
}
