package ws.sanasol.dualauth.fetcher;

import ws.sanasol.dualauth.agent.DualAuthConfig;
import ws.sanasol.dualauth.context.DualAuthContext;
import ws.sanasol.dualauth.context.DualAuthHelper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DualJwksFetcher {
    private static final Logger LOGGER = Logger.getLogger("DualAuthAgent");
    
    // Official and F2P base JWKS URLs
    public static final String OFFICIAL_JWKS_URL = DualAuthConfig.OFFICIAL_SESSION_URL + "/.well-known/jwks.json";
    public static final String F2P_JWKS_URL = DualAuthConfig.F2P_SESSION_URL + "/.well-known/jwks.json";
    
    // Caches
    private static volatile String cachedBaseKeysContent = "";
    private static volatile long lastBaseFetchMs = 0;
    private static final ConcurrentHashMap<String, String> dynamicIssuerCache = new ConcurrentHashMap<>();
    private static volatile String finalAggregatedJson = null;

    /**
     * Registers a new issuer and discovers its JWKS keys.
     * This is used for Federated or Omni-Auth issuers that aren't hardcoded.
     */
    public static void registerIssuer(String issuer) {
        if (issuer == null) return;
        if (!DualAuthHelper.isValidIssuer(issuer)) return;
        
        // Skip Official (Fixed Path)
        if (DualAuthHelper.isOfficialIssuer(issuer)) return;
        
        // Skip if already cached
        if (dynamicIssuerCache.containsKey(issuer)) return;

        // Discovery: Fetch JWKS from the new domain
        String jwksUrl = issuer + (issuer.endsWith("/") ? "" : "/") + ".well-known/jwks.json";
        
        try {
            String json = fetchJwksJson(jwksUrl);
            if (json != null) {
                String content = extractKeysContent(json);
                if (!content.isEmpty()) {
                    dynamicIssuerCache.put(issuer, content);
                    finalAggregatedJson = null; // Invalidate cache
                    LOGGER.info("Discovered new key source: " + jwksUrl);
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed discovery for issuer: " + issuer, e);
        }
    }

    /**
     * Fetches and merges all JWKS from Official, F2P, and Dynamic sources.
     * This fulfills the server's requirement for a single JWKSet.
     */
    public static synchronized String fetchMergedJwksJson() {
        // Return cached aggregation if available
        if (finalAggregatedJson != null) return finalAggregatedJson;

        long now = System.currentTimeMillis();
        // Refresh base keys every hour (3600s) or if never fetched
        if (now - lastBaseFetchMs > 3600000L) {
            updateBaseKeys();
        }

        // Aggregate everything
        StringBuilder sb = new StringBuilder("{\"keys\":[");
        boolean first = true;
        
        // 1. Append Base (Official + F2P)
        if (cachedBaseKeysContent != null && !cachedBaseKeysContent.isEmpty()) {
            sb.append(cachedBaseKeysContent);
            first = false;
        }
        
        // 2. Append Dynamic Issuers
        Collection<String> dynamicKeys = dynamicIssuerCache.values();
        for (String keys : dynamicKeys) {
            if (keys == null || keys.isEmpty()) continue;
            if (!first) sb.append(",");
            sb.append(keys);
            first = false;
        }
        
        // 3. Append Omni-Auth (Current Context)
        String omniJwk = DualAuthContext.getJwk();
        if (omniJwk != null && !omniJwk.isEmpty()) {
            if (!first) sb.append(",");
            sb.append(omniJwk);
            LOGGER.info("Including current Omni-Auth key in JWKS");
            first = false;
        }

        sb.append("]}");
        finalAggregatedJson = sb.toString();
        
        // Log summary of what we merged
        int keyCount = countOccurrences(finalAggregatedJson, "kid") / 2;
        LOGGER.info("fetchMergedJwksJson: Created merged set with multiple keys (estimated " + keyCount + " kids).");
        
        return finalAggregatedJson;
    }

    private static void updateBaseKeys() {
        LOGGER.info("Refreshing base JWKS from Official and F2P backends...");
        LOGGER.info("Official URL: " + OFFICIAL_JWKS_URL);
        LOGGER.info("F2P URL: " + F2P_JWKS_URL);
        
        String officialJson = fetchJwksJson(OFFICIAL_JWKS_URL);
        if (officialJson != null) {
            LOGGER.info("Successfully fetched Official JWKS.");
        } else {
            LOGGER.warning("Failed to fetch Official JWKS.");
        }

        String f2pJson = fetchJwksJson(F2P_JWKS_URL);
        if (f2pJson != null) {
            LOGGER.info("Successfully fetched F2P JWKS.");
        } else {
            LOGGER.warning("Failed to fetch F2P JWKS from " + F2P_JWKS_URL);
        }
        
        StringBuilder sb = new StringBuilder();
        
        if (officialJson != null) {
            sb.append(extractKeysContent(officialJson));
        }
        
        if (f2pJson != null) {
            String f2pContent = extractKeysContent(f2pJson);
            if (!f2pContent.isEmpty()) {
                if (sb.length() > 0) sb.append(",");
                sb.append(f2pContent);
            }
        }
        
        cachedBaseKeysContent = sb.toString();
        lastBaseFetchMs = System.currentTimeMillis();
        LOGGER.info("Base JWKS update complete. Merged keys content length: " + cachedBaseKeysContent.length());
    }

    public static String fetchJwksJson(String targetUrl) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(targetUrl).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            
            int code = conn.getResponseCode();
            if (code == 200) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    return sb.toString();
                }
            } else {
                LOGGER.warning("JWKS fetch returned HTTP " + code + " for " + targetUrl);
            }
        } catch (Exception e) {
            LOGGER.warning("Exception fetching JWKS from " + targetUrl + ": " + e.getMessage());
        }
        return null;
    }

    private static int countOccurrences(String str, String sub) {
        if (str == null || sub == null || str.isEmpty() || sub.isEmpty()) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = str.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    /**
     * Extracts the content inside the "keys" array of a JWKS JSON.
     */
    public static String extractKeysContent(String json) {
        if (json == null || json.isEmpty()) return "";
        try {
            int idx = json.indexOf("\"keys\":");
            if (idx < 0) return "";
            
            int start = json.indexOf('[', idx);
            int end = json.lastIndexOf(']');
            
            if (start >= 0 && end > start) {
                return json.substring(start + 1, end).trim();
            }
        } catch (Exception e) {
            // Log parse error
        }
        return "";
    }
}

