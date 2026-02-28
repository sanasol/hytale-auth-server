const crypto = require('crypto');
const config = require('../config');
const auth = require('../services/auth');
const storage = require('../services/storage');
const passwordService = require('../services/password');
const { sendJson } = require('../utils/response');

/**
 * Extract and verify Bearer token from Authorization header.
 * Returns verified payload { uuid, name, ... } or null.
 */
function verifyBearerToken(req) {
  const authHeader = req.headers && req.headers.authorization;
  if (!authHeader || !authHeader.startsWith('Bearer ')) return null;
  const token = authHeader.slice(7);
  if (!token || token.length < 20) return null;
  return auth.verifyToken(token);
}

/**
 * For password-protected UUIDs, require either:
 * 1. A valid Bearer token with matching sub (game client already authenticated), OR
 * 2. Correct password in body (launcher direct call)
 * Returns true if allowed, false if blocked (response already sent).
 */
async function requireAuth(req, res, uuid, name, body) {
  // Check if Bearer token proves prior authentication
  const tokenData = verifyBearerToken(req);
  if (tokenData && tokenData.uuid === uuid) {
    return true; // Valid token for this UUID — already authenticated
  }

  // Password check
  const pwResult = await passwordService.verifyPassword(uuid, body.password || null);
  if (!pwResult.ok) {
    if (pwResult.lockedOut) {
      sendJson(res, 429, { error: 'Too many failed attempts. Try again later.', lockoutSeconds: pwResult.lockoutSeconds });
      return false;
    }
    sendJson(res, 401, { error: 'Password required', password_required: true, attemptsRemaining: pwResult.attemptsRemaining });
    return false;
  }

  // Username reservation check
  if (name) {
    const reservation = await passwordService.checkUsernameReservation(name);
    if (reservation.reserved && reservation.ownerUuid !== uuid) {
      sendJson(res, 403, { error: 'This username is reserved by another player', username_taken: true });
      return false;
    }
  }

  // Name lock: password-protected UUIDs must use their registered name
  if (name) {
    const registeredName = await passwordService.getReservedUsername(uuid);
    if (registeredName && registeredName.toLowerCase() !== name.toLowerCase()) {
      sendJson(res, 403, { error: `This UUID is locked to username "${registeredName}"`, name_locked: true, registeredName });
      return false;
    }
  }

  return true;
}

/**
 * Create new game session (used by official launcher and servers)
 */
async function handleGameSessionNew(req, res, body, uuid, name) {
  console.log('game-session/new:', uuid, name, 'scopes:', body.scopes || body.scope);

  if (!await requireAuth(req, res, uuid, name, body)) return;

  // Extract server audience from request
  const serverAudience = body.serverAudience || body.server_id || null;

  // Extract requested scopes (array or space-separated string)
  const scopes = body.scopes || body.scope || null;

  // Get request host for dynamic issuer
  const requestHost = req.headers.host;

  const identityToken = auth.generateIdentityToken(uuid, name, scopes, ['game.base'], requestHost);
  const sessionToken = auth.generateSessionToken(uuid, name, requestHost);

  // Register the session
  storage.registerSession(sessionToken, uuid, name, serverAudience);

  // Calculate expiresAt for Java client compatibility
  const expiresAt = new Date(Date.now() + config.sessionTtl * 1000).toISOString();

  sendJson(res, 200, {
    identityToken: identityToken,
    sessionToken: sessionToken,
    expiresIn: config.sessionTtl,
    expiresAt: expiresAt,
    tokenType: 'Bearer'
  });
}

/**
 * Refresh game session
 */
async function handleGameSessionRefresh(req, res, body, uuid, name, headers) {
  console.log('game-session/refresh:', uuid, name, 'scopes:', body.scopes || body.scope);

  if (!await requireAuth(req, res, uuid, name, body)) return;

  // Extract server audience from token if present
  const serverAudience = auth.extractServerAudienceFromHeaders(headers);

  // Extract requested scopes (array or space-separated string)
  const scopes = body.scopes || body.scope || null;

  // Get request host for dynamic issuer
  const requestHost = req.headers.host;

  const identityToken = auth.generateIdentityToken(uuid, name, scopes, ['game.base'], requestHost);
  const sessionToken = auth.generateSessionToken(uuid, name, requestHost);

  // Update session
  storage.registerSession(sessionToken, uuid, name, serverAudience);

  // Calculate expiresAt for Java client compatibility
  const expiresAt = new Date(Date.now() + config.sessionTtl * 1000).toISOString();

  sendJson(res, 200, {
    identityToken: identityToken,
    sessionToken: sessionToken,
    expiresIn: config.sessionTtl,
    expiresAt: expiresAt,
    tokenType: 'Bearer'
  });
}

/**
 * Create child session
 */
async function handleGameSessionChild(req, res, body, uuid, name) {
  console.log('game-session/child:', uuid, name, 'scopes:', body.scopes || body.scope);

  if (!await requireAuth(req, res, uuid, name, body)) return;

  // Extract requested scopes (array or space-separated string)
  const scopes = body.scopes || body.scope || null;

  // Get request host for dynamic issuer
  const requestHost = req.headers.host;

  const childToken = auth.generateIdentityToken(uuid, name, scopes, ['game.base'], requestHost);
  const sessionToken = auth.generateSessionToken(uuid, name, requestHost);

  // Calculate expiresAt for Java client compatibility
  const expiresAt = new Date(Date.now() + config.sessionTtl * 1000).toISOString();

  sendJson(res, 200, {
    identityToken: childToken,
    sessionToken: sessionToken,
    expiresIn: config.sessionTtl,
    expiresAt: expiresAt,
    tokenType: 'Bearer'
  });
}

/**
 * Delete game session (logout/cleanup)
 * The client sends the identity token in Authorization header (not session token)
 * We need to parse it to get the UUID and remove the player from all servers
 */
async function handleGameSessionDelete(req, res, headers) {
  console.log('game-session delete');

  if (headers && headers.authorization) {
    const token = headers.authorization.replace('Bearer ', '');

    // First try to remove by session token (in case it is one)
    const removed = await storage.removeSession(token);

    if (!removed) {
      // Token is likely an identity token, parse it to get UUID
      const tokenData = auth.parseToken(token);
      if (tokenData && tokenData.uuid) {
        console.log(`Session delete for player UUID: ${tokenData.uuid} (${tokenData.name || 'unknown'})`);
        await storage.removePlayerFromAllServers(tokenData.uuid);
      } else {
        console.log('Session delete: could not extract UUID from token');
      }
    }
  }

  res.writeHead(204);
  res.end();
}

/**
 * Authorization grant endpoint - server requests this to authorize a client connection
 */
async function handleAuthorizationGrant(req, res, body, uuid, name, headers) {
  console.log('Authorization grant request:', uuid, name, 'body:', JSON.stringify(body));

  // Extract scopes from request or identity token
  let scopes = body.scopes || body.scope || null;

  // Extract user info from identity token if present in request (verify signature)
  if (body.identityToken) {
    const tokenData = auth.verifyToken(body.identityToken) || auth.parseToken(body.identityToken);
    if (tokenData) {
      if (tokenData.uuid) uuid = tokenData.uuid;
      if (tokenData.name) name = tokenData.name;
      // Preserve scopes from identity token if not explicitly specified in request
      if (!scopes && tokenData.scope) scopes = tokenData.scope;
      console.log('Extracted from identity token - uuid:', uuid, 'name:', name, 'scope:', tokenData.scope);
    }
  }

  // Extract audience from request (server's unique ID)
  const audience = body.aud || body.audience || body.server_id || crypto.randomUUID();

  // Capture server info from User-Agent if this is a HytaleServer request
  const userAgent = req.headers['user-agent'] || '';
  if (userAgent.startsWith('HytaleServer/')) {
    // Extract version from User-Agent: HytaleServer/2026.01.27-734d39026
    const versionMatch = userAgent.match(/HytaleServer\/(\S+)/);
    if (versionMatch && audience) {
      storage.setServerVersion(audience, versionMatch[1]);
    }
    // Capture server IP
    const serverIp = req.headers['x-forwarded-for']?.split(',')[0].trim()
                     || req.headers['x-real-ip']
                     || req.socket?.remoteAddress;
    if (serverIp && audience) {
      storage.setServerIp(audience, serverIp);
    }
  }

  // Get request host for dynamic issuer
  const requestHost = req.headers.host;

  const authGrant = auth.generateAuthorizationGrant(uuid, name, audience, scopes, requestHost);
  const expiresAt = new Date(Date.now() + config.sessionTtl * 1000).toISOString();

  // Track this auth grant - player is joining this server
  storage.registerAuthGrant(authGrant, uuid, name, audience);

  sendJson(res, 200, {
    authorizationGrant: authGrant,
    expiresAt: expiresAt
  });
}

/**
 * Token exchange endpoint - client exchanges auth grant for access token
 */
async function handleTokenExchange(req, res, body, uuid, name, headers) {
  console.log('Token exchange request:', uuid, name);

  // Extract scopes from request or auth grant
  let scopes = body.scopes || body.scope || null;

  // Extract audience from the authorization grant JWT (verify signature)
  let audience = null;
  if (body.authorizationGrant) {
    const tokenData = auth.verifyToken(body.authorizationGrant) || auth.parseToken(body.authorizationGrant);
    if (tokenData) {
      audience = tokenData.aud;
      if (tokenData.uuid) uuid = tokenData.uuid;
      if (tokenData.name) name = tokenData.name;
      // Preserve scopes from auth grant if not explicitly specified in request
      if (!scopes && tokenData.scope) scopes = tokenData.scope;
      console.log('Extracted from auth grant - aud:', audience, 'sub:', uuid, 'name:', name, 'scope:', tokenData.scope);
    }
  }

  // Get certificate fingerprint from request (for mTLS binding)
  const certFingerprint = body.x509Fingerprint || body.certFingerprint || body.fingerprint;
  console.log('Certificate fingerprint:', certFingerprint);

  // Get request host for dynamic issuer
  const requestHost = req.headers.host;

  const accessToken = auth.generateAccessToken(uuid, name, audience, certFingerprint, scopes, requestHost);
  const refreshToken = auth.generateSessionToken(uuid, name, requestHost);
  const expiresAt = new Date(Date.now() + config.sessionTtl * 1000).toISOString();

  // Normalize scopes for response
  const responseScope = auth.normalizeScopes(scopes);

  // Register session with server audience so it persists across restarts
  storage.registerSession(accessToken, uuid, name, audience);

  sendJson(res, 200, {
    accessToken: accessToken,
    tokenType: 'Bearer',
    expiresIn: config.sessionTtl,
    refreshToken: refreshToken,
    expiresAt: expiresAt,
    scope: responseScope
  });
}

/**
 * Generic session handler
 */
async function handleSession(req, res, body, uuid, name) {
  if (!await requireAuth(req, res, uuid, name, body)) return;

  const requestHost = req.headers.host;
  sendJson(res, 200, {
    success: true,
    session_id: crypto.randomUUID(),
    identityToken: auth.generateIdentityToken(uuid, name, null, ['game.base'], requestHost),
    identity_token: auth.generateIdentityToken(uuid, name, null, ['game.base'], requestHost),
    sessionToken: auth.generateSessionToken(uuid, name, requestHost),
    session_token: auth.generateSessionToken(uuid, name, requestHost),
    expires_in: 86400,
    token_type: 'Bearer',
    user: { uuid, name, premium: true }
  });
}

/**
 * Generic auth handler
 */
async function handleAuth(req, res, body, uuid, name) {
  if (!await requireAuth(req, res, uuid, name, body)) return;

  const scopes = body.scopes || body.scope || null;
  const requestHost = req.headers.host;
  sendJson(res, 200, {
    success: true,
    authenticated: true,
    identity_token: auth.generateIdentityToken(uuid, name, scopes, ['game.base'], requestHost),
    session_token: auth.generateSessionToken(uuid, name, requestHost),
    token_type: 'Bearer',
    expires_in: 86400,
    user: { uuid, name, premium: true }
  });
}

/**
 * Generic token handler
 */
async function handleToken(req, res, body, uuid, name) {
  if (!await requireAuth(req, res, uuid, name, body)) return;

  const requestHost = req.headers.host;
  sendJson(res, 200, {
    access_token: auth.generateIdentityToken(uuid, name, null, ['game.base'], requestHost),
    identity_token: auth.generateIdentityToken(uuid, name, null, ['game.base'], requestHost),
    session_token: auth.generateSessionToken(uuid, name, requestHost),
    token_type: 'Bearer',
    expires_in: 86400,
    refresh_token: auth.generateSessionToken(uuid, name, requestHost)
  });
}

/**
 * Validate endpoint
 */
async function handleValidate(req, res, body, uuid, name) {
  if (!await requireAuth(req, res, uuid, name, body)) return;

  sendJson(res, 200, {
    valid: true,
    success: true,
    user: { uuid, name, premium: true }
  });
}

/**
 * Refresh endpoint
 */
async function handleRefresh(req, res, body, uuid, name) {
  if (!await requireAuth(req, res, uuid, name, body)) return;

  const requestHost = req.headers.host;
  sendJson(res, 200, {
    success: true,
    identity_token: auth.generateIdentityToken(uuid, name, null, ['game.base'], requestHost),
    session_token: auth.generateSessionToken(uuid, name, requestHost),
    token_type: 'Bearer',
    expires_in: 86400
  });
}

module.exports = {
  handleGameSessionNew,
  handleGameSessionRefresh,
  handleGameSessionChild,
  handleGameSessionDelete,
  handleAuthorizationGrant,
  handleTokenExchange,
  handleSession,
  handleAuth,
  handleToken,
  handleValidate,
  handleRefresh,
};
