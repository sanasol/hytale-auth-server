const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const config = require('../config');

let privateKey, publicKey, publicKeyJwk;

/**
 * Build the issuer URL dynamically from request host
 *
 * Supports multiple endpoints with correct issuer for each:
 * - sessions.sanasol.ws → issuer https://sessions.sanasol.ws
 * - auth.sanasol.ws → issuer https://auth.sanasol.ws
 * - sanasol.ws → issuer https://sanasol.ws
 *
 * This enables backward compatibility with different client patch versions.
 *
 * @param {string} [requestHost] - The Host header from the request
 * @returns {string} The issuer URL
 */
function getIssuerUrl(requestHost) {
  if (requestHost) {
    // Remove port if present (e.g., "localhost:3000" -> "localhost")
    const host = requestHost.split(':')[0];
    // Check if host contains our domain (sanasol.ws)
    if (host.includes(config.domain)) {
      return `https://${host}`;
    }
  }
  // Fallback to base domain
  return `https://${config.domain}`;
}

/**
 * Load existing keys from disk or generate new ones
 */
function loadOrGenerateKeys() {
  try {
    if (fs.existsSync(config.keyFile)) {
      const keyData = JSON.parse(fs.readFileSync(config.keyFile, 'utf8'));
      privateKey = crypto.createPrivateKey({
        key: Buffer.from(keyData.privateKey, 'base64'),
        format: 'der',
        type: 'pkcs8'
      });
      publicKey = crypto.createPublicKey({
        key: Buffer.from(keyData.publicKey, 'base64'),
        format: 'der',
        type: 'spki'
      });
      publicKeyJwk = publicKey.export({ format: 'jwk' });
      console.log('Loaded existing Ed25519 key pair from disk');
      return;
    }
  } catch (e) {
    console.log('Could not load existing keys:', e.message);
  }

  // Generate new keys
  const keyPair = crypto.generateKeyPairSync('ed25519');
  privateKey = keyPair.privateKey;
  publicKey = keyPair.publicKey;
  publicKeyJwk = publicKey.export({ format: 'jwk' });

  // Save keys to disk
  try {
    const dir = path.dirname(config.keyFile);
    if (!fs.existsSync(dir)) {
      fs.mkdirSync(dir, { recursive: true });
    }
    const keyData = {
      privateKey: privateKey.export({ format: 'der', type: 'pkcs8' }).toString('base64'),
      publicKey: publicKey.export({ format: 'der', type: 'spki' }).toString('base64'),
      createdAt: new Date().toISOString()
    };
    fs.writeFileSync(config.keyFile, JSON.stringify(keyData, null, 2));
    console.log('Generated and saved new Ed25519 key pair');
  } catch (e) {
    console.log('Could not save keys:', e.message);
    console.log('Generated Ed25519 key pair (not persisted)');
  }
}

/**
 * Get the public key in JWK format for JWKS endpoint
 */
function getPublicKeyJwk() {
  return publicKeyJwk;
}

/**
 * Generate a JWT token with proper Ed25519 signing
 */
function generateToken(payload) {
  const header = Buffer.from(JSON.stringify({
    alg: 'EdDSA',
    kid: config.keyId,
    typ: 'JWT'
  })).toString('base64url');
  const body = Buffer.from(JSON.stringify(payload)).toString('base64url');
  const signingInput = `${header}.${body}`;

  const signature = crypto.sign(null, Buffer.from(signingInput), privateKey);
  return `${signingInput}.${signature.toString('base64url')}`;
}

// Default scopes that cover all client types (game client + asset editor)
const DEFAULT_SCOPES = 'hytale:server hytale:client hytale:editor';

/**
 * Normalize scopes to a space-separated string
 * @param {string|string[]|null} scopes - Scopes as array or string
 * @param {string} defaultScope - Default scope if none provided
 * @returns {string} Space-separated scope string
 */
function normalizeScopes(scopes, defaultScope = DEFAULT_SCOPES) {
  if (!scopes) return defaultScope;
  if (Array.isArray(scopes)) return scopes.join(' ');
  return scopes;
}

/**
 * Generate identity token for the game client/server
 * @param {string} uuid - User UUID
 * @param {string} name - Username
 * @param {string[]|string} [scopes] - Requested scopes (defaults to 'hytale:server hytale:client')
 * @param {string[]} [entitlements] - User entitlements
 * @param {string} [requestHost] - Request host for dynamic issuer
 */
function generateIdentityToken(uuid, name, scopes = null, entitlements = ['game.base'], requestHost = null) {
  const now = Math.floor(Date.now() / 1000);
  const exp = now + config.sessionTtl;
  const scope = normalizeScopes(scopes);

  return generateToken({
    sub: uuid,
    name: name,
    username: name,
    profile: {
      username: name
    },
    entitlements: entitlements,
    scope: scope,
    iat: now,
    exp: exp,
    iss: getIssuerUrl(requestHost),
    jti: crypto.randomUUID()
  });
}

/**
 * Generate session token for the game server
 * @param {string} uuid - User UUID
 * @param {string} [requestHost] - Request host for dynamic issuer
 */
function generateSessionToken(uuid, requestHost = null) {
  const now = Math.floor(Date.now() / 1000);
  const exp = now + config.sessionTtl;

  return generateToken({
    sub: uuid,
    scope: 'hytale:server',
    iat: now,
    exp: exp,
    iss: getIssuerUrl(requestHost),
    jti: crypto.randomUUID()
  });
}

/**
 * Generate authorization grant token for server connection
 * @param {string} uuid - User UUID
 * @param {string} name - Username
 * @param {string} audience - Server audience
 * @param {string[]|string} [scopes] - Requested scopes (defaults to 'hytale:server hytale:client')
 * @param {string} [requestHost] - Request host for dynamic issuer
 */
function generateAuthorizationGrant(uuid, name, audience, scopes = null, requestHost = null) {
  const now = Math.floor(Date.now() / 1000);
  const exp = now + config.sessionTtl;
  const scope = normalizeScopes(scopes);

  return generateToken({
    sub: uuid,
    name: name,
    username: name,
    aud: audience,
    scope: scope,
    iat: now,
    exp: exp,
    iss: getIssuerUrl(requestHost),
    jti: crypto.randomUUID()
  });
}

/**
 * Generate access token with audience and optional certificate binding
 * @param {string} uuid - User UUID
 * @param {string} name - Username
 * @param {string} audience - Server audience
 * @param {string} [certFingerprint] - Certificate fingerprint for mTLS binding
 * @param {string[]|string} [scopes] - Requested scopes (defaults to 'hytale:server hytale:client')
 * @param {string} [requestHost] - Request host for dynamic issuer
 */
function generateAccessToken(uuid, name, audience, certFingerprint = null, scopes = null, requestHost = null) {
  const now = Math.floor(Date.now() / 1000);
  const exp = now + config.sessionTtl;
  const scope = normalizeScopes(scopes);

  const tokenPayload = {
    sub: uuid,
    name: name,
    username: name,
    aud: audience,
    entitlements: ['game.base'],
    scope: scope,
    iat: now,
    exp: exp,
    iss: getIssuerUrl(requestHost),
    jti: crypto.randomUUID()
  };

  if (certFingerprint) {
    tokenPayload.cnf = {
      'x5t#S256': certFingerprint
    };
  }

  return generateToken(tokenPayload);
}

/**
 * Extract UUID and name from a JWT token string
 */
function parseToken(tokenString) {
  try {
    const parts = tokenString.split('.');
    if (parts.length >= 2) {
      const payload = JSON.parse(Buffer.from(parts[1], 'base64url').toString());
      return {
        uuid: payload.sub,
        name: payload.username || payload.name,
        scope: payload.scope,
        aud: payload.aud
      };
    }
  } catch (e) {
    // Invalid token format
  }
  return null;
}

/**
 * Extract server audience from bearer token in headers
 */
function extractServerAudienceFromHeaders(headers) {
  if (!headers || !headers.authorization) return null;

  try {
    const token = headers.authorization.replace('Bearer ', '');
    const parts = token.split('.');
    if (parts.length >= 2) {
      const payload = JSON.parse(Buffer.from(parts[1], 'base64url').toString());
      if (payload.aud) {
        return payload.aud;
      }
      if (payload.scope === 'hytale:server' && payload.sub) {
        return payload.sub;
      }
    }
  } catch (e) {
    // Silent fail - token parsing is optional
  }
  return null;
}

// Initialize keys on module load
loadOrGenerateKeys();

module.exports = {
  loadOrGenerateKeys,
  getPublicKeyJwk,
  generateToken,
  generateIdentityToken,
  generateSessionToken,
  generateAuthorizationGrant,
  generateAccessToken,
  parseToken,
  extractServerAudienceFromHeaders,
  normalizeScopes,
};
