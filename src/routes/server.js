const crypto = require('crypto');
const config = require('../config');
const auth = require('../services/auth');
const storage = require('../services/storage');
const { sendJson } = require('../utils/response');

/**
 * Server auto-authentication for F2P mode
 *
 * This endpoint allows game servers to automatically obtain auth tokens
 * without going through the OAuth device flow. Used when:
 * - HYTALE_AUTH_MODE=f2p (F2P only mode)
 * - HYTALE_AUTH_MODE=dual (dual mode, for F2P backend tokens)
 *
 * The server provides a unique identifier (server_id or generates one)
 * and receives session + identity tokens immediately.
 */
function handleServerAutoAuth(req, res, body) {
  console.log('server/auto-auth request:', JSON.stringify(body));

  // Server can provide its own ID or we generate one
  const serverId = body.server_id || body.serverId || crypto.randomUUID();

  // Server name for logging/identification (optional)
  const serverName = body.server_name || body.serverName || `Server-${serverId.substring(0, 8)}`;

  // Capture server IP from proxy headers
  const serverIp = req.headers['x-forwarded-for']?.split(',')[0].trim()
                   || req.headers['x-real-ip']
                   || req.socket?.remoteAddress
                   || 'unknown';

  // Generate a server-specific UUID (deterministic based on server_id for consistency)
  const serverUuid = generateServerUuid(serverId);

  // Get request host for dynamic issuer (backward compatibility)
  const requestHost = req.headers.host;

  // Generate tokens with server scope (issuer based on request host)
  const identityToken = auth.generateIdentityToken(
    serverUuid,
    serverName,
    'hytale:server',  // Server scope only
    ['game.base', 'server.host'],  // Server entitlements
    requestHost
  );

  const sessionToken = auth.generateSessionToken(serverUuid, requestHost);

  // Register the server session
  storage.registerSession(sessionToken, serverUuid, serverName, serverId);

  // Store server name and IP for admin dashboard
  storage.setServerName(serverId, serverName);
  storage.setServerIp(serverId, serverIp);

  // Calculate expiresAt for Java client compatibility
  const expiresAt = new Date(Date.now() + config.sessionTtl * 1000).toISOString();

  console.log(`server/auto-auth success: ${serverUuid} "${serverName}" from IP ${serverIp}`);

  sendJson(res, 200, {
    identityToken: identityToken,
    sessionToken: sessionToken,
    expiresIn: config.sessionTtl,
    expiresAt: expiresAt,
    tokenType: 'Bearer',
    serverId: serverId,
    serverUuid: serverUuid,
    serverName: serverName
  });
}

/**
 * Server game profiles endpoint
 *
 * Returns a single profile for the server (used by ServerAuthManager
 * after OAuth flow to select which profile to use).
 *
 * For auto-auth, we just return the server's own profile.
 */
function handleServerGameProfiles(req, res, headers) {
  console.log('server/game-profiles request');

  // Extract server info from the bearer token
  let serverUuid = null;
  let serverName = 'Server';

  if (headers && headers.authorization) {
    const token = headers.authorization.replace('Bearer ', '');
    const tokenData = auth.parseToken(token);
    if (tokenData) {
      serverUuid = tokenData.uuid;
      serverName = tokenData.name || 'Server';
    }
  }

  if (!serverUuid) {
    // Generate a random server UUID if no token provided
    serverUuid = crypto.randomUUID();
  }

  // Return a single profile for the server
  sendJson(res, 200, [
    {
      uuid: serverUuid,
      username: serverName,
      isDefault: true
    }
  ]);
}

/**
 * OAuth device authorization endpoint (stub for F2P mode)
 *
 * In F2P mode, we don't need the device flow but the server still calls it.
 * This returns a mock response that tells the server to use auto-auth instead.
 *
 * Note: For official mode, this would redirect to hytale.com
 */
async function handleOAuthDeviceAuth(req, res, body) {
  console.log('oauth2/device/auth request:', JSON.stringify(body));

  const clientId = body.client_id || 'hytale-server';
  const scope = body.scope || 'openid offline auth:server';

  // Generate a device code that can be immediately exchanged
  const deviceCode = crypto.randomUUID();
  const userCode = generateUserCode();

  // Store the device code for later exchange (auto-approved for F2P)
  await storage.registerDeviceCode(deviceCode, userCode, clientId, scope);

  // Return verification URLs pointing to oauth.accounts subdomain (matches what patched server expects)
  // Note: Traefik routes oauth.accounts.sanasol.ws -> this same auth server
  sendJson(res, 200, {
    device_code: deviceCode,
    user_code: userCode,
    verification_uri: `https://oauth.accounts.${config.domain}/oauth2/device/verify`,
    verification_uri_complete: `https://oauth.accounts.${config.domain}/oauth2/device/verify?user_code=${userCode}`,
    expires_in: 600,
    interval: 1  // Fast polling for F2P (auto-approved)
  });
}

/**
 * OAuth device verification page
 * Displays user code entry form (or auto-approves in F2P mode)
 */
async function handleOAuthDeviceVerify(req, res, query) {
  const userCode = query.user_code || query.code || '';

  // In F2P mode, auto-approve any device code
  if (userCode) {
    await storage.approveDeviceCode(userCode);
  }

  // Return a simple HTML page
  const html = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Device Authorization - Hytale F2P</title>
  <style>
    * { margin: 0; padding: 0; box-sizing: border-box; }
    body {
      font-family: 'Segoe UI', sans-serif;
      background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
      min-height: 100vh;
      display: flex;
      align-items: center;
      justify-content: center;
      color: #fff;
    }
    .card {
      background: rgba(255,255,255,0.1);
      backdrop-filter: blur(10px);
      border-radius: 16px;
      padding: 40px;
      max-width: 400px;
      text-align: center;
      border: 1px solid rgba(255,255,255,0.2);
    }
    h1 { margin-bottom: 20px; color: #4ade80; }
    .code {
      font-size: 2rem;
      font-family: monospace;
      background: rgba(0,0,0,0.3);
      padding: 15px 30px;
      border-radius: 8px;
      margin: 20px 0;
      letter-spacing: 4px;
    }
    .success { color: #4ade80; }
    p { color: #94a3b8; line-height: 1.6; }
  </style>
</head>
<body>
  <div class="card">
    <h1>✓ Authorized</h1>
    <div class="code">${userCode || 'AUTO'}</div>
    <p class="success">Your server has been authorized!</p>
    <p style="margin-top: 15px;">You can close this window and return to your server console.</p>
  </div>
</body>
</html>`;

  res.writeHead(200, { 'Content-Type': 'text/html' });
  res.end(html);
}

/**
 * OAuth token endpoint
 * Handles device code exchange and token refresh
 */
function handleOAuthToken(req, res, body) {
  console.log('oauth2/token request:', body.grant_type);

  const grantType = body.grant_type;

  if (grantType === 'urn:ietf:params:oauth:grant-type:device_code') {
    // Device code exchange
    return handleDeviceCodeExchange(req, res, body);
  } else if (grantType === 'refresh_token') {
    // Token refresh
    return handleTokenRefresh(req, res, body);
  } else if (grantType === 'authorization_code') {
    // Auth code exchange (browser flow)
    return handleAuthCodeExchange(req, res, body);
  }

  sendJson(res, 400, {
    error: 'unsupported_grant_type',
    error_description: `Grant type '${grantType}' is not supported`
  });
}

/**
 * Handle device code exchange for tokens
 */
async function handleDeviceCodeExchange(req, res, body) {
  const deviceCode = body.device_code;
  const clientId = body.client_id || 'hytale-server';

  if (!deviceCode) {
    return sendJson(res, 400, {
      error: 'invalid_request',
      error_description: 'device_code is required'
    });
  }

  // Check if device code exists and is approved
  const deviceData = await storage.getDeviceCode(deviceCode);

  if (!deviceData) {
    return sendJson(res, 400, {
      error: 'invalid_grant',
      error_description: 'Device code not found or expired'
    });
  }

  if (!deviceData.approved) {
    // In F2P mode, auto-approve
    await storage.approveDeviceCode(deviceData.userCode);
    deviceData.approved = true;
  }

  // Generate tokens for the server
  const serverUuid = crypto.randomUUID();
  const serverName = `Server-${serverUuid.substring(0, 8)}`;
  const requestHost = req.headers.host;

  const accessToken = auth.generateIdentityToken(
    serverUuid,
    serverName,
    'hytale:server',
    ['game.base', 'server.host'],
    requestHost
  );

  const refreshToken = auth.generateSessionToken(serverUuid, requestHost);
  const idToken = auth.generateIdentityToken(serverUuid, serverName, 'openid hytale:server', ['game.base'], requestHost);

  // Clean up device code
  await storage.consumeDeviceCode(deviceCode);

  sendJson(res, 200, {
    access_token: accessToken,
    refresh_token: refreshToken,
    id_token: idToken,
    token_type: 'Bearer',
    expires_in: config.sessionTtl,
    scope: 'openid offline auth:server hytale:server'
  });
}

/**
 * Handle token refresh
 */
function handleTokenRefresh(req, res, body) {
  const refreshToken = body.refresh_token;

  if (!refreshToken) {
    return sendJson(res, 400, {
      error: 'invalid_request',
      error_description: 'refresh_token is required'
    });
  }

  // Parse the refresh token to get server info
  const tokenData = auth.parseToken(refreshToken);
  const serverUuid = tokenData?.uuid || crypto.randomUUID();
  const serverName = tokenData?.name || `Server-${serverUuid.substring(0, 8)}`;
  const requestHost = req.headers.host;

  const accessToken = auth.generateIdentityToken(
    serverUuid,
    serverName,
    'hytale:server',
    ['game.base', 'server.host'],
    requestHost
  );

  const newRefreshToken = auth.generateSessionToken(serverUuid, requestHost);

  sendJson(res, 200, {
    access_token: accessToken,
    refresh_token: newRefreshToken,
    token_type: 'Bearer',
    expires_in: config.sessionTtl
  });
}

/**
 * Handle authorization code exchange (browser flow)
 */
function handleAuthCodeExchange(req, res, body) {
  const code = body.code;
  const clientId = body.client_id || 'hytale-server';
  const redirectUri = body.redirect_uri;

  if (!code) {
    return sendJson(res, 400, {
      error: 'invalid_request',
      error_description: 'code is required'
    });
  }

  // For F2P, just generate tokens (no code validation needed)
  const serverUuid = crypto.randomUUID();
  const serverName = `Server-${serverUuid.substring(0, 8)}`;
  const requestHost = req.headers.host;

  const accessToken = auth.generateIdentityToken(
    serverUuid,
    serverName,
    'hytale:server',
    ['game.base', 'server.host'],
    requestHost
  );

  const refreshToken = auth.generateSessionToken(serverUuid, requestHost);
  const idToken = auth.generateIdentityToken(serverUuid, serverName, 'openid hytale:server', ['game.base'], requestHost);

  sendJson(res, 200, {
    access_token: accessToken,
    refresh_token: refreshToken,
    id_token: idToken,
    token_type: 'Bearer',
    expires_in: config.sessionTtl,
    scope: 'openid offline auth:server hytale:server'
  });
}

/**
 * Generate a deterministic UUID from server ID
 */
function generateServerUuid(serverId) {
  const hash = crypto.createHash('sha256').update(`f2p-server-${serverId}`).digest('hex');
  // Format as UUID v4
  return `${hash.substring(0, 8)}-${hash.substring(8, 12)}-4${hash.substring(13, 16)}-a${hash.substring(17, 20)}-${hash.substring(20, 32)}`;
}

/**
 * Generate a user-friendly device code
 */
function generateUserCode() {
  const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';  // Avoiding ambiguous chars
  let code = '';
  for (let i = 0; i < 8; i++) {
    code += chars[Math.floor(Math.random() * chars.length)];
  }
  return code;
}

module.exports = {
  handleServerAutoAuth,
  handleServerGameProfiles,
  handleOAuthDeviceAuth,
  handleOAuthDeviceVerify,
  handleOAuthToken,
};
