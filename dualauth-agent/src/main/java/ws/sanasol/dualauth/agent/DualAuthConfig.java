package ws.sanasol.dualauth.agent;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class DualAuthConfig {
    public static final String F2P_DOMAIN = getEnvChain(new String[]{"HYTALE_AUTH_SERVER", "HYTALE_AUTH_DOMAIN"}, "auth.sanasol.ws");
    
    // Protocol selection (HTTP for localhost/127.x.x.x, HTTPS for others)
    public static final String PROTOCOL = (F2P_DOMAIN.startsWith("127.") || F2P_DOMAIN.startsWith("localhost"))
            ? "http"
            : "https";

    public static final String F2P_SESSION_URL = PROTOCOL + "://" + F2P_DOMAIN;
    public static final String F2P_ISSUER = F2P_SESSION_URL;
    
    public static final String OFFICIAL_SESSION_URL = "https://sessions.hytale.com";
    public static final String OFFICIAL_ISSUER = "https://sessions.hytale.com";
    public static final String OFFICIAL_DOMAIN = "hytale.com";
    
    public static final String F2P_BASE_DOMAIN = extractBaseDomain(F2P_DOMAIN);
    
    public static final boolean TRUST_ALL_ISSUERS = getBoolean("HYTALE_TRUST_ALL_ISSUERS", true);
    public static final boolean TRUST_OFFICIAL = getBoolean("HYTALE_TRUST_OFFICIAL", true);
    public static final Set<String> TRUSTED_ISSUERS = parseCsv("HYTALE_TRUSTED_ISSUERS");
    public static final long KEYS_CACHE_TTL_MS = getEnvLongSeconds("HYTALE_KEYS_CACHE_TTL", 10800L) * 1000L; // Unified TTL for JWKS and tokens
    
    // Legacy compatibility (deprecated)
    @Deprecated
    public static final long JWKS_CACHE_TTL_MS = KEYS_CACHE_TTL_MS;

    // Issuer Detection Configuration (Public Discovery)
    public static final long ISSUER_DETECTION_CACHE_TTL = getEnvLongSeconds("HYTALE_ISSUER_DETECTION_TTL", 3600L) * 1000L; // 1 hour default
    public static final Set<String> ISSUER_BLACKLIST = parseCsv("HYTALE_ISSUER_BLACKLIST");
    public static final boolean FORCE_DETECTION_FOR_ALL = getBoolean("HYTALE_FORCE_ISSUER_DETECTION", false);

    private static String getEnv(String name, String defaultValue) {
        String value = System.getenv(name);
        return (value != null && !value.isEmpty()) ? value : defaultValue;
    }

    private static String getEnvChain(String[] names, String defaultValue) {
        for (String name : names) {
            String value = System.getenv(name);
            if (value != null && !value.isEmpty()) return value;
        }
        return defaultValue;
    }

    private static boolean getBoolean(String name, boolean defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) return defaultValue;
        return value.equalsIgnoreCase("true") || value.equals("1") || value.equalsIgnoreCase("yes") || value.equalsIgnoreCase("on");
    }

    private static long getEnvLongSeconds(String name, long defaultValueSeconds) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) return defaultValueSeconds;
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : defaultValueSeconds;
        } catch (NumberFormatException e) {
            return defaultValueSeconds;
        }
    }

    private static Set<String> parseCsv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) return Collections.emptySet();
        Set<String> set = new HashSet<>();
        for (String s : value.split(",")) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) {
                if (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
                set.add(trimmed);
            }
        }
        return set;
    }

    private static String extractBaseDomain(String domain) {
        if (domain == null || domain.isEmpty() || Character.isDigit(domain.charAt(0))) {
            return domain;
        }
        int firstDot = domain.indexOf('.');
        if (firstDot > 0) {
            String afterFirstDot = domain.substring(firstDot + 1);
            if (afterFirstDot.indexOf('.') > 0) {
                return afterFirstDot;
            }
        }
        return domain;
    }
}
