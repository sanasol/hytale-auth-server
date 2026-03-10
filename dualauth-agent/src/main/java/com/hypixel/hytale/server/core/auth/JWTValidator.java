package com.hypixel.hytale.server.core.auth;

import java.security.cert.X509Certificate;
import java.util.UUID;

/**
 * COMPILE-TIME STUB ONLY — excluded from final JAR.
 * Matches decompiled JWTValidator signatures for subclass compilation.
 */
public class JWTValidator {

    public JWTValidator(SessionServiceClient sessionServiceClient,
                        String expectedIssuer,
                        String expectedAudience) {
        throw new UnsupportedOperationException("Stub");
    }

    public JWTClaims validateToken(String accessToken, X509Certificate clientCert) {
        throw new UnsupportedOperationException("Stub");
    }

    public IdentityTokenClaims validateIdentityToken(String identityToken) {
        throw new UnsupportedOperationException("Stub");
    }

    public SessionTokenClaims validateSessionToken(String sessionToken) {
        throw new UnsupportedOperationException("Stub");
    }

    public static class JWTClaims {
        public String issuer;
        public String audience;
        public String subject;
        public String username;
        public String ipAddress;
        public String certificateFingerprint;
        public Long issuedAt;
        public Long expiresAt;
        public Long notBefore;

        public UUID getSubjectAsUUID() {
            return subject != null ? UUID.fromString(subject) : null;
        }
    }

    public static class IdentityTokenClaims {
        public String issuer;
        public String subject;
        public String username;
        public String skin;
        public String scope;
        public String[] entitlements;
        public Long issuedAt;
        public Long expiresAt;
        public Long notBefore;

        public UUID getSubjectAsUUID() {
            return subject != null ? UUID.fromString(subject) : null;
        }

        public String[] getScopes() {
            return scope != null ? scope.split(" ") : new String[0];
        }

        public boolean hasScope(String targetScope) {
            for (String s : getScopes()) {
                if (s.equals(targetScope)) return true;
            }
            return false;
        }
    }

    public static class SessionTokenClaims {
        public String issuer;
        public String subject;
        public String scope;
        public Long issuedAt;
        public Long expiresAt;
        public Long notBefore;

        public UUID getSubjectAsUUID() {
            return subject != null ? UUID.fromString(subject) : null;
        }

        public String[] getScopes() {
            return scope != null ? scope.split(" ") : new String[0];
        }

        public boolean hasScope(String targetScope) {
            for (String s : getScopes()) {
                if (s.equals(targetScope)) return true;
            }
            return false;
        }
    }
}
