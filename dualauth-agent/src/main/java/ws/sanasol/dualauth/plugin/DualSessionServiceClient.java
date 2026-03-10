package ws.sanasol.dualauth.plugin;

import com.hypixel.hytale.server.core.auth.SessionServiceClient;

import java.util.Base64;
import java.util.concurrent.CompletableFuture;

/**
 * Dual session service client that routes requests to official or F2P backend based on token issuer.
 * Extends the server's SessionServiceClient to act as a drop-in replacement for HandshakeHandler.
 *
 * Identity token requests → parse issuer → route to correct backend.
 * Auth grant exchanges → try official first, fallback to F2P (can't determine origin from grant).
 */
public class DualSessionServiceClient extends SessionServiceClient {

    /**
     * F2P session service client, set via reflection from DualAuthBootstrap.
     */
    public volatile Object f2pClient;

    public DualSessionServiceClient(String sessionServiceUrl) {
        super(sessionServiceUrl);
    }

    @Override
    public CompletableFuture<String> requestAuthorizationGrantAsync(
            String identityToken,
            String serverAudience,
            String bearerToken) {
        String issuer = extractIssuerFromToken(identityToken);
        if (issuer != null && !isOfficialIssuer(issuer)) {
            return requestAuthGrantViaF2P(identityToken, serverAudience, bearerToken);
        }
        return super.requestAuthorizationGrantAsync(identityToken, serverAudience, bearerToken);
    }

    @Override
    public CompletableFuture<String> exchangeAuthGrantForTokenAsync(
            String authorizationGrant,
            String x509Fingerprint,
            String bearerToken) {
        // Can't determine grant origin — try official first, fallback to F2P
        return super.exchangeAuthGrantForTokenAsync(authorizationGrant, x509Fingerprint, bearerToken)
                .thenCompose(result -> {
                    if (result != null) {
                        return CompletableFuture.completedFuture(result);
                    }
                    // Official returned null — try F2P
                    return exchangeAuthGrantViaF2P(authorizationGrant, x509Fingerprint, bearerToken);
                })
                .exceptionallyCompose(ex -> {
                    // Official threw exception — try F2P
                    System.out.println("[DualAuth-Reflection] Official auth grant exchange failed, trying F2P...");
                    return exchangeAuthGrantViaF2P(authorizationGrant, x509Fingerprint, bearerToken);
                });
    }

    // --- F2P routing via reflection ---

    @SuppressWarnings("unchecked")
    private CompletableFuture<String> requestAuthGrantViaF2P(
            String identityToken, String serverAudience, String bearerToken) {
        Object client = f2pClient;
        if (client == null) {
            System.err.println("[DualAuth-Reflection] F2P client not set, falling back to official");
            return super.requestAuthorizationGrantAsync(identityToken, serverAudience, bearerToken);
        }
        try {
            java.lang.reflect.Method m = client.getClass().getMethod(
                    "requestAuthorizationGrantAsync", String.class, String.class, String.class);
            return (CompletableFuture<String>) m.invoke(client, identityToken, serverAudience, bearerToken);
        } catch (Exception e) {
            System.err.println("[DualAuth-Reflection] F2P requestAuthorizationGrantAsync failed: " + e.getMessage());
            return CompletableFuture.completedFuture(null);
        }
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<String> exchangeAuthGrantViaF2P(
            String authorizationGrant, String x509Fingerprint, String bearerToken) {
        Object client = f2pClient;
        if (client == null) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            java.lang.reflect.Method m = client.getClass().getMethod(
                    "exchangeAuthGrantForTokenAsync", String.class, String.class, String.class);
            return (CompletableFuture<String>) m.invoke(client, authorizationGrant, x509Fingerprint, bearerToken);
        } catch (Exception e) {
            System.err.println("[DualAuth-Reflection] F2P exchangeAuthGrantForTokenAsync failed: " + e.getMessage());
            return CompletableFuture.completedFuture(null);
        }
    }

    // --- Token issuer extraction (same as DualJWTValidator) ---

    static String extractIssuerFromToken(String token) {
        if (token == null) return null;
        try {
            int firstDot = token.indexOf('.');
            int secondDot = token.indexOf('.', firstDot + 1);
            if (firstDot < 0 || secondDot < 0) return null;

            String payload = token.substring(firstDot + 1, secondDot);
            String json = new String(Base64.getUrlDecoder().decode(payload));

            int issIdx = json.indexOf("\"iss\"");
            if (issIdx < 0) return null;

            int colonIdx = json.indexOf(':', issIdx + 5);
            if (colonIdx < 0) return null;

            int quoteStart = json.indexOf('"', colonIdx + 1);
            if (quoteStart < 0) return null;

            int quoteEnd = json.indexOf('"', quoteStart + 1);
            if (quoteEnd < 0) return null;

            return json.substring(quoteStart + 1, quoteEnd);
        } catch (Exception e) {
            return null;
        }
    }

    static boolean isOfficialIssuer(String issuer) {
        return issuer != null && issuer.contains("hytale.com");
    }
}
