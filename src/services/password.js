const bcrypt = require('bcryptjs');
const config = require('../config');
const { redis } = require('./redis');

const BCRYPT_ROUNDS = 12;
const MAX_ATTEMPTS = 5;
const LOCKOUT_SECONDS = 900; // 15 minutes

// Dummy hash for timing attack mitigation (bcrypt compare always runs)
const DUMMY_HASH = '$2a$12$LJ3m4ys3Lg2VBe7Xaq3OOuHPGMqmCfIGuJEj.YVFYp6dMtYqq.way';

/**
 * Check if a UUID has a password set
 */
async function hasPassword(uuid) {
  const key = config.redisKeys.PLAYER_PASSWORD + uuid;
  const exists = await redis.exists(key);
  return exists === 1;
}

/**
 * Verify password for a UUID.
 * Returns { ok, attemptsRemaining, lockoutSeconds }
 * - ok: true if no password set or correct password provided
 * - ok: false if wrong/missing password
 */
async function verifyPassword(uuid, plaintext) {
  const pwKey = config.redisKeys.PLAYER_PASSWORD + uuid;
  const attKey = config.redisKeys.PASSWORD_ATTEMPTS + uuid;

  const hash = await redis.get(pwKey);

  // No password set — allow through
  if (!hash) {
    // Still run bcrypt.compare for timing consistency
    await bcrypt.compare('dummy', DUMMY_HASH);
    return { ok: true };
  }

  // Password required but not provided
  if (!plaintext) {
    await bcrypt.compare('dummy', DUMMY_HASH);
    const attempts = parseInt(await redis.get(attKey) || '0');
    return {
      ok: false,
      passwordRequired: true,
      attemptsRemaining: Math.max(0, MAX_ATTEMPTS - attempts),
    };
  }

  // Check lockout
  const attempts = parseInt(await redis.get(attKey) || '0');
  if (attempts >= MAX_ATTEMPTS) {
    const ttl = await redis.ttl(attKey);
    return {
      ok: false,
      lockedOut: true,
      lockoutSeconds: ttl > 0 ? ttl : LOCKOUT_SECONDS,
    };
  }

  // Verify password
  const match = await bcrypt.compare(plaintext, hash);

  if (match) {
    // Correct — clear attempts
    await redis.del(attKey);
    return { ok: true };
  }

  // Wrong password — increment attempts
  const newAttempts = await redis.incr(attKey);
  await redis.expire(attKey, LOCKOUT_SECONDS);

  return {
    ok: false,
    passwordRequired: true,
    attemptsRemaining: Math.max(0, MAX_ATTEMPTS - newAttempts),
  };
}

/**
 * Set or change password for a UUID
 */
async function setPassword(uuid, plaintext) {
  const hash = await bcrypt.hash(plaintext, BCRYPT_ROUNDS);
  const key = config.redisKeys.PLAYER_PASSWORD + uuid;
  await redis.set(key, hash);
}

/**
 * Remove password for a UUID
 */
async function removePassword(uuid) {
  const pwKey = config.redisKeys.PLAYER_PASSWORD + uuid;
  const attKey = config.redisKeys.PASSWORD_ATTEMPTS + uuid;
  await redis.del(pwKey);
  await redis.del(attKey);
}

/**
 * Reset failed attempt counter
 */
async function resetAttempts(uuid) {
  const attKey = config.redisKeys.PASSWORD_ATTEMPTS + uuid;
  await redis.del(attKey);
}

/**
 * Get current attempt count
 */
async function getAttemptCount(uuid) {
  const attKey = config.redisKeys.PASSWORD_ATTEMPTS + uuid;
  const count = await redis.get(attKey);
  return parseInt(count || '0');
}

// ─── Username Reservation ───

const MAX_PASSWORD_SETS_PER_IP = 20;       // max password-set calls per IP per window
const PASSWORD_SET_RATELIMIT_WINDOW = 3600; // 1 hour

/**
 * Log a username reservation action to Redis audit list and console.
 * Entries are stored as JSON in a capped list (last 1000 entries).
 */
async function auditLog(action, data) {
  const entry = {
    action,
    timestamp: new Date().toISOString(),
    ...data,
  };
  console.log(`[USERNAME-AUDIT] ${action}: uuid=${data.uuid || '-'} username="${data.username || '-'}" ip=${data.ip || '-'}${data.oldUsername ? ` old="${data.oldUsername}"` : ''}${data.reason ? ` reason="${data.reason}"` : ''}`);
  await redis.lpush(config.redisKeys.USERNAME_AUDIT, JSON.stringify(entry));
  await redis.ltrim(config.redisKeys.USERNAME_AUDIT, 0, 999); // keep last 1000
}

/**
 * Check IP rate limit for password set operations.
 * Returns { ok: bool, remaining: number }
 */
async function checkPasswordSetRateLimit(ip) {
  if (!ip) return { ok: true, remaining: MAX_PASSWORD_SETS_PER_IP };
  const key = config.redisKeys.PASSWORD_SET_RATELIMIT + ip;
  const count = parseInt(await redis.get(key) || '0');
  if (count >= MAX_PASSWORD_SETS_PER_IP) {
    const ttl = await redis.ttl(key);
    return { ok: false, remaining: 0, retryAfter: ttl > 0 ? ttl : PASSWORD_SET_RATELIMIT_WINDOW };
  }
  return { ok: true, remaining: MAX_PASSWORD_SETS_PER_IP - count };
}

/**
 * Increment IP rate limit counter for password set.
 */
async function incrementPasswordSetRateLimit(ip) {
  if (!ip) return;
  const key = config.redisKeys.PASSWORD_SET_RATELIMIT + ip;
  const count = await redis.incr(key);
  if (count === 1) {
    await redis.expire(key, PASSWORD_SET_RATELIMIT_WINDOW);
  }
}

/**
 * Reserve a username for a UUID. Case-insensitive.
 * Stores reservation with IP and timestamp for audit.
 */
async function reserveUsername(uuid, username, ip) {
  const nameKey = config.redisKeys.USERNAME_RESERVED + username.toLowerCase();
  const uuidKey = config.redisKeys.UUID_USERNAME + uuid;

  // Check if already reserved by someone else
  const existingData = await redis.get(nameKey);
  if (existingData) {
    // Parse stored data (may be plain UUID for backwards compat, or JSON)
    let ownerUuid;
    try {
      const parsed = JSON.parse(existingData);
      ownerUuid = parsed.uuid;
    } catch {
      ownerUuid = existingData;
    }
    if (ownerUuid !== uuid) {
      await auditLog('reserve_denied', { uuid, username, ip, reason: `already owned by ${ownerUuid}` });
      return { ok: false, owner: ownerUuid };
    }
  }

  // Clear any previous username reservation for this UUID
  const oldName = await redis.get(uuidKey);
  if (oldName && oldName.toLowerCase() !== username.toLowerCase()) {
    await redis.del(config.redisKeys.USERNAME_RESERVED + oldName.toLowerCase());
    await auditLog('reserve_changed', { uuid, username, ip, oldUsername: oldName });
  }

  // Store reservation with metadata
  const reservationData = JSON.stringify({
    uuid,
    username,
    ip: ip || 'unknown',
    reservedAt: new Date().toISOString(),
  });
  await redis.set(nameKey, reservationData);
  await redis.set(uuidKey, username);

  await auditLog('reserve_claimed', { uuid, username, ip });
  return { ok: true };
}

/**
 * Release username reservation for a UUID
 */
async function releaseUsername(uuid, ip, reason) {
  const uuidKey = config.redisKeys.UUID_USERNAME + uuid;
  const name = await redis.get(uuidKey);
  if (name) {
    await redis.del(config.redisKeys.USERNAME_RESERVED + name.toLowerCase());
    await redis.del(uuidKey);
    await auditLog('reserve_released', { uuid, username: name, ip: ip || 'system', reason: reason || 'password_removed' });
  }
}

/**
 * Check if a username is reserved and by which UUID.
 * Returns { reserved, ownerUuid, reservedAt, ip }
 */
async function checkUsernameReservation(username) {
  const nameKey = config.redisKeys.USERNAME_RESERVED + username.toLowerCase();
  const data = await redis.get(nameKey);
  if (!data) return { reserved: false, ownerUuid: null };

  // Parse stored data
  try {
    const parsed = JSON.parse(data);
    return { reserved: true, ownerUuid: parsed.uuid, reservedAt: parsed.reservedAt, ip: parsed.ip };
  } catch {
    // Backwards compat: plain UUID string
    return { reserved: true, ownerUuid: data };
  }
}

/**
 * Get the reserved username for a UUID
 */
async function getReservedUsername(uuid) {
  const uuidKey = config.redisKeys.UUID_USERNAME + uuid;
  return await redis.get(uuidKey);
}

/**
 * Get audit log entries. Returns array of { action, timestamp, uuid, username, ip, ... }
 */
async function getAuditLog(start, count) {
  const entries = await redis.lrange(config.redisKeys.USERNAME_AUDIT, start || 0, (start || 0) + (count || 50) - 1);
  return entries.map(e => { try { return JSON.parse(e); } catch { return { raw: e }; } });
}

module.exports = {
  hasPassword,
  verifyPassword,
  setPassword,
  removePassword,
  resetAttempts,
  getAttemptCount,
  reserveUsername,
  releaseUsername,
  checkUsernameReservation,
  getReservedUsername,
  checkPasswordSetRateLimit,
  incrementPasswordSetRateLimit,
  getAuditLog,
};
