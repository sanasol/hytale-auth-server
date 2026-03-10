package ws.sanasol.dualauth.plugin;

import com.hypixel.hytale.server.core.auth.JWTValidator;
import com.hypixel.hytale.server.core.auth.SessionServiceClient;

import java.security.cert.X509Certificate;
import java.util.Base64;

/**
 * Dual JWT validator that routes tokens to official or F2P validation based on issuer.
 * Extends the server's JWTValidator to act as a drop-in replacement for HandshakeHandler.
 *
 * When loaded via reflection fallback (no Instrumentation), this replaces the
 * HandshakeHandler's static jwtValidator field.
 *
 * Official tokens → super.validateXxx() (official issuer + official JWKS)
 * F2P tokens → f2pValidator.validateXxx() (F2P issuer + F2P JWKS)
 */
public class DualJWTValidator extends JWTValidator {

    /**
     * F2P validator instance, set via reflection from DualAuthBootstrap.
     * This is a standard JWTValidator constructed with F2P session URL and F2P issuer.
     */
    public volatile Object f2pValidator;

    public DualJWTValidator(SessionServiceClient sessionServiceClient,
                            String expectedIssuer,
                            String expectedAudience) {
        super(sessionServiceClient, expectedIssuer, expectedAudience);
    }

    @Override
    public JWTClaims validateToken(String accessToken, X509Certificate clientCert) {
        String issuer = extractIssuerFromToken(accessToken);
        if (issuer != null && !isOfficialIssuer(issuer)) {
            // F2P token — route to F2P validator
            return validateTokenViaF2P(accessToken, clientCert);
        }
        // Official token (or unknown — let official handle it)
        return super.validateToken(accessToken, clientCert);
    }

    @Override
    public IdentityTokenClaims validateIdentityToken(String identityToken) {
        String issuer = extractIssuerFromToken(identityToken);
        if (issuer != null && !isOfficialIssuer(issuer)) {
            return validateIdentityTokenViaF2P(identityToken);
        }
        return super.validateIdentityToken(identityToken);
    }

    @Override
    public SessionTokenClaims validateSessionToken(String sessionToken) {
        String issuer = extractIssuerFromToken(sessionToken);
        if (issuer != null && !isOfficialIssuer(issuer)) {
            return validateSessionTokenViaF2P(sessionToken);
        }
        return super.validateSessionToken(sessionToken);
    }

    // --- F2P validation via reflection (f2pValidator is loaded in server's classloader) ---

    private JWTClaims validateTokenViaF2P(String accessToken, X509Certificate clientCert) {
        Object validator = f2pValidator;
        if (validator == null) {
            System.err.println("[DualAuth-Reflection] F2P validator not set, falling back to official");
            return super.validateToken(accessToken, clientCert);
        }
        try {
            java.lang.reflect.Method m = validator.getClass().getMethod(
                    "validateToken", String.class, X509Certificate.class);
            return (JWTClaims) m.invoke(validator, accessToken, clientCert);
        } catch (Exception e) {
            System.err.println("[DualAuth-Reflection] F2P validateToken failed: " + e.getMessage());
            return null;
        }
    }

    private IdentityTokenClaims validateIdentityTokenViaF2P(String identityToken) {
        Object validator = f2pValidator;
        if (validator == null) {
            System.err.println("[DualAuth-Reflection] F2P validator not set, falling back to official");
            return super.validateIdentityToken(identityToken);
        }
        try {
            java.lang.reflect.Method m = validator.getClass().getMethod(
                    "validateIdentityToken", String.class);
            return (IdentityTokenClaims) m.invoke(validator, identityToken);
        } catch (Exception e) {
            System.err.println("[DualAuth-Reflection] F2P validateIdentityToken failed: " + e.getMessage());
            return null;
        }
    }

    private SessionTokenClaims validateSessionTokenViaF2P(String sessionToken) {
        Object validator = f2pValidator;
        if (validator == null) {
            System.err.println("[DualAuth-Reflection] F2P validator not set, falling back to official");
            return super.validateSessionToken(sessionToken);
        }
        try {
            java.lang.reflect.Method m = validator.getClass().getMethod(
                    "validateSessionToken", String.class);
            return (SessionTokenClaims) m.invoke(validator, sessionToken);
        } catch (Exception e) {
            System.err.println("[DualAuth-Reflection] F2P validateSessionToken failed: " + e.getMessage());
            return null;
        }
    }

    // --- Token issuer extraction (no Nimbus dependency — pure Base64+String parsing) ---

    /**
     * Extracts the "iss" claim from a JWT without full parsing.
     * JWT format: header.payload.signature (Base64url encoded).
     */
    static String extractIssuerFromToken(String token) {
        if (token == null) return null;
        try {
            int firstDot = token.indexOf('.');
            int secondDot = token.indexOf('.', firstDot + 1);
            if (firstDot < 0 || secondDot < 0) return null;

            String payload = token.substring(firstDot + 1, secondDot);
            String json = new String(Base64.getUrlDecoder().decode(payload));

            // Simple JSON extraction — find "iss":"value"
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

    /**
     * Checks if the issuer belongs to the official Hytale domain.
     */
    static boolean isOfficialIssuer(String issuer) {
        return issuer != null && issuer.contains("hytale.com");
    }
}
