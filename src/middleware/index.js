const crypto = require('crypto');
const storage = require('../services/storage');
const auth = require('../services/auth');

/**
 * Apply CORS headers to response
 */
function corsHeaders(res) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, PUT, DELETE, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization');
}

/**
 * Handle OPTIONS preflight request
 */
function handleOptions(req, res) {
  corsHeaders(res);
  res.writeHead(200);
  res.end();
}

/**
 * Parse JSON body from request
 */
function parseBody(req) {
  return new Promise((resolve) => {
    let body = '';
    req.on('data', chunk => { body += chunk; });
    req.on('end', () => {
      try {
        resolve(body ? JSON.parse(body) : {});
      } catch (e) {
        resolve({});
      }
    });
  });
}

/**
 * Extract user context from request (UUID, name from body or token)
 */
function extractUserContext(body, headers) {
  let uuid = body.uuid || crypto.randomUUID();
  let name = body.name || null;
  let tokenScope = null;

  // If we have a valid name from body (not 'Player'), cache it immediately
  if (uuid && name && name !== 'Player') {
    storage.setCachedUsername(uuid, name);
    console.log(`Cached username from body for UUID ${uuid}: ${name}`);
  }

  // Extract UUID and name from Authorization header
  if (headers && headers.authorization) {
    const tokenData = auth.parseToken(headers.authorization.replace('Bearer ', ''));
    if (tokenData) {
      if (tokenData.uuid) uuid = tokenData.uuid;
      tokenScope = tokenData.scope;

      // Use token name if available (regardless of scope - session tokens now include name too)
      if (tokenData.name && tokenData.name !== 'Player') {
        if (!name || name === 'Player') {
          name = tokenData.name;
        }
        // Cache and persist valid names
        if (uuid) {
          storage.setCachedUsername(uuid, tokenData.name);
          storage.persistUsername(uuid, tokenData.name);
        }
      }
    }
  }

  // If we don't have a valid name yet, try the cache
  if (!name || name === 'Player') {
    const cachedName = storage.getCachedUsername(uuid);
    if (cachedName) {
      name = cachedName;
      console.log(`Using cached username for UUID ${uuid}: ${name}`);
    }
  }

  // Final fallback
  if (!name) name = 'Player';

  // Persist valid username from body (e.g., from /game-session/new)
  if (uuid && name && name !== 'Player') {
    storage.persistUsername(uuid, name);
  }

  return { uuid, name, tokenScope };
}

/**
 * Verify admin token from headers
 */
async function verifyAdminAuth(headers) {
  const token = headers['x-admin-token'];
  if (!token) return false;
  return await storage.verifyAdminToken(token);
}

module.exports = {
  corsHeaders,
  handleOptions,
  parseBody,
  extractUserContext,
  verifyAdminAuth,
};
