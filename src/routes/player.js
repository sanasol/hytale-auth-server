const auth = require('../services/auth');
const passwordService = require('../services/password');
const requestLogger = require('../services/requestLogger');
const { sendJson } = require('../utils/response');

const MIN_PASSWORD_LENGTH = 6;

/**
 * Extract UUID and username from Bearer token in Authorization header
 */
function extractFromBearer(headers) {
  if (!headers || !headers.authorization) return {};
  const token = headers.authorization.replace('Bearer ', '');
  // MUST use verifyToken (signature check) — parseToken would allow forged JWTs
  const data = auth.verifyToken(token);
  return data ? { uuid: data.uuid, username: data.name } : {};
}

/**
 * GET /player/password/status/{uuid}
 * Public — returns whether a UUID has a password set
 */
async function handlePasswordStatus(req, res, uuid) {
  if (!uuid) {
    sendJson(res, 400, { error: 'UUID required' });
    return;
  }
  const has = await passwordService.hasPassword(uuid);
  const registeredName = has ? await passwordService.getReservedUsername(uuid) : null;
  sendJson(res, 200, { hasPassword: has, registeredName: registeredName || null });
}

/**
 * POST /player/password/set
 * Requires Bearer token whose sub matches the uuid
 * Body: { uuid, password, currentPassword? }
 */
async function handlePasswordSet(req, res, body, headers) {
  const { uuid: tokenUuid, username: tokenUsername } = extractFromBearer(headers);
  const uuid = body.uuid;
  const clientIp = requestLogger.getClientIp(req);

  if (!uuid) {
    sendJson(res, 400, { error: 'UUID required' });
    return;
  }

  if (!tokenUuid || tokenUuid !== uuid) {
    sendJson(res, 403, { error: 'Token UUID does not match requested UUID' });
    return;
  }

  if (!body.password || body.password.length < MIN_PASSWORD_LENGTH) {
    sendJson(res, 400, { error: `Password must be at least ${MIN_PASSWORD_LENGTH} characters` });
    return;
  }

  // IP rate limit — prevent mass password/username claiming from one IP
  const rateCheck = await passwordService.checkPasswordSetRateLimit(clientIp);
  if (!rateCheck.ok) {
    console.log(`[PASSWORD-RATELIMIT] IP ${clientIp} blocked (exceeded ${rateCheck.retryAfter}s window) for UUID ${uuid}`);
    sendJson(res, 429, { error: 'Too many password operations from this IP. Try again later.', retryAfter: rateCheck.retryAfter });
    return;
  }

  // If already has a password, require current password
  const has = await passwordService.hasPassword(uuid);
  if (has) {
    if (!body.currentPassword) {
      sendJson(res, 400, { error: 'Current password required to change password' });
      return;
    }
    const verify = await passwordService.verifyPassword(uuid, body.currentPassword);
    if (!verify.ok) {
      if (verify.lockedOut) {
        sendJson(res, 429, { error: 'Too many failed attempts', lockoutSeconds: verify.lockoutSeconds });
        return;
      }
      sendJson(res, 401, { error: 'Current password is incorrect', attemptsRemaining: verify.attemptsRemaining });
      return;
    }
  }

  await passwordService.setPassword(uuid, body.password);
  await passwordService.incrementPasswordSetRateLimit(clientIp);

  console.log(`[PASSWORD-SET] uuid=${uuid} ip=${clientIp} username="${tokenUsername || body.username || '-'}"`);

  // Reserve the username for this UUID (from token or body)
  const usernameToReserve = tokenUsername || body.username;
  if (usernameToReserve) {
    const reserve = await passwordService.reserveUsername(uuid, usernameToReserve, clientIp);
    if (!reserve.ok) {
      sendJson(res, 200, { success: true, username_reserved: false, username_taken_by: 'another player' });
      return;
    }
    sendJson(res, 200, { success: true, username_reserved: true, reserved_username: usernameToReserve });
  } else {
    sendJson(res, 200, { success: true, username_reserved: false });
  }
}

/**
 * POST /player/password/remove
 * Requires Bearer token whose sub matches the uuid
 * Body: { uuid, currentPassword }
 */
async function handlePasswordRemove(req, res, body, headers) {
  const { uuid: tokenUuid } = extractFromBearer(headers);
  const uuid = body.uuid;
  const clientIp = requestLogger.getClientIp(req);

  if (!uuid) {
    sendJson(res, 400, { error: 'UUID required' });
    return;
  }

  if (!tokenUuid || tokenUuid !== uuid) {
    sendJson(res, 403, { error: 'Token UUID does not match requested UUID' });
    return;
  }

  const has = await passwordService.hasPassword(uuid);
  if (!has) {
    sendJson(res, 400, { error: 'No password set for this UUID' });
    return;
  }

  if (!body.currentPassword) {
    sendJson(res, 400, { error: 'Current password required' });
    return;
  }

  const verify = await passwordService.verifyPassword(uuid, body.currentPassword);
  if (!verify.ok) {
    if (verify.lockedOut) {
      sendJson(res, 429, { error: 'Too many failed attempts', lockoutSeconds: verify.lockoutSeconds });
      return;
    }
    sendJson(res, 401, { error: 'Current password is incorrect', attemptsRemaining: verify.attemptsRemaining });
    return;
  }

  await passwordService.removePassword(uuid);
  await passwordService.releaseUsername(uuid, clientIp, 'player_removed');
  console.log(`[PASSWORD-REMOVE] uuid=${uuid} ip=${clientIp}`);
  sendJson(res, 200, { success: true });
}

/**
 * GET /player/username/status/{username}
 * Public — check if a username is reserved
 */
async function handleUsernameStatus(req, res, username) {
  if (!username) {
    sendJson(res, 400, { error: 'Username required' });
    return;
  }
  const result = await passwordService.checkUsernameReservation(username);
  sendJson(res, 200, { reserved: result.reserved });
}

/**
 * GET /api/check-identity?uuid=...&username=...
 * Public — used by DualAuth agent to check if an identity is protected.
 * Returns { allowed: true } or { allowed: false, reason: "..." }
 */
async function handleCheckIdentity(req, res, query) {
  const uuid = query.uuid;
  const username = query.username;

  if (!uuid && !username) {
    sendJson(res, 400, { error: 'uuid or username required' });
    return;
  }

  // Check 1: Is this UUID password-protected?
  if (uuid) {
    const has = await passwordService.hasPassword(uuid);
    if (has) {
      sendJson(res, 200, { allowed: false, reason: 'password_protected' });
      return;
    }
  }

  // Check 2: Is this username reserved by a different UUID?
  if (username) {
    const reservation = await passwordService.checkUsernameReservation(username);
    if (reservation.reserved && reservation.ownerUuid !== uuid) {
      sendJson(res, 200, { allowed: false, reason: 'username_reserved', owner_uuid: reservation.ownerUuid });
      return;
    }
  }

  sendJson(res, 200, { allowed: true });
}

module.exports = {
  handlePasswordStatus,
  handlePasswordSet,
  handlePasswordRemove,
  handleUsernameStatus,
  handleCheckIdentity,
};
