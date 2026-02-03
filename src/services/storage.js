const crypto = require('crypto');
const config = require('../config');
const { redis, isConnected } = require('./redis');

const KEYS = config.redisKeys;

// Local cache for usernames (reduces Redis roundtrips for frequent lookups)
const uuidUsernameCache = new Map();

// ============================================================================
// SESSION MANAGEMENT
// ============================================================================

/**
 * Register a new game session
 */
async function registerSession(sessionToken, uuid, username, serverAudience = null) {
  const sessionData = {
    uuid,
    username,
    serverAudience,
    createdAt: new Date().toISOString()
  };

  if (isConnected()) {
    try {
      await redis.setex(`${KEYS.SESSION}${sessionToken}`, config.sessionTtl, JSON.stringify(sessionData));

      if (serverAudience) {
        const previousServer = await redis.get(`${KEYS.PLAYER_SERVER}${uuid}`);
        if (previousServer && previousServer !== serverAudience) {
          await redis.srem(`${KEYS.SERVER_PLAYERS}${previousServer}`, uuid);
          console.log(`Player ${uuid} moved from server ${previousServer} to ${serverAudience}`);
        }

        await redis.sadd(`${KEYS.SERVER_PLAYERS}${serverAudience}`, uuid);
        await redis.setex(`${KEYS.PLAYER_SERVER}${uuid}`, config.sessionTtl, serverAudience);
      }

      if (username && username !== 'Player') {
        await redis.set(`${KEYS.USERNAME}${uuid}`, username);
        uuidUsernameCache.set(uuid, username);
      }

      // Track in active sets for fast admin queries
      await trackActivePlayer(uuid, serverAudience);

      console.log(`Session registered: ${uuid} (${username}) on server ${serverAudience || 'unknown'}`);
    } catch (e) {
      console.error('Failed to register session in Redis:', e.message);
    }
  }
}

/**
 * Register an auth grant (player joining a server)
 */
async function registerAuthGrant(authGrant, playerUuid, playerName, serverAudience) {
  const grantData = {
    playerUuid,
    playerName,
    serverAudience,
    createdAt: new Date().toISOString()
  };

  if (isConnected()) {
    try {
      await redis.setex(`${KEYS.AUTH_GRANT}${authGrant}`, config.sessionTtl, JSON.stringify(grantData));

      const previousServer = await redis.get(`${KEYS.PLAYER_SERVER}${playerUuid}`);
      if (previousServer && previousServer !== serverAudience) {
        await redis.srem(`${KEYS.SERVER_PLAYERS}${previousServer}`, playerUuid);
        console.log(`Player ${playerUuid} moved from server ${previousServer} to ${serverAudience}`);
      }

      await redis.sadd(`${KEYS.SERVER_PLAYERS}${serverAudience}`, playerUuid);
      await redis.setex(`${KEYS.PLAYER_SERVER}${playerUuid}`, config.sessionTtl, serverAudience);

      await persistUsername(playerUuid, playerName);

      // Track in active sets for fast admin queries
      await trackActivePlayer(playerUuid, serverAudience);

      console.log(`Auth grant registered: ${playerUuid} (${playerName}) -> server ${serverAudience}`);
    } catch (e) {
      console.error('Failed to register auth grant in Redis:', e.message);
    }
  }
}

/**
 * Remove a session
 */
async function removeSession(sessionToken) {
  if (!isConnected()) return false;

  try {
    const sessionJson = await redis.get(`${KEYS.SESSION}${sessionToken}`);
    if (!sessionJson) return false;

    const session = JSON.parse(sessionJson);

    if (session.serverAudience) {
      await redis.srem(`${KEYS.SERVER_PLAYERS}${session.serverAudience}`, session.uuid);

      const remaining = await redis.scard(`${KEYS.SERVER_PLAYERS}${session.serverAudience}`);
      if (remaining === 0) {
        await redis.del(`${KEYS.SERVER_PLAYERS}${session.serverAudience}`);
      }
    }

    await redis.del(`${KEYS.PLAYER_SERVER}${session.uuid}`);
    await redis.del(`${KEYS.SESSION}${sessionToken}`);

    console.log(`Session removed: ${session.uuid} (${session.username})`);
    return true;
  } catch (e) {
    console.error('Failed to remove session:', e.message);
    return false;
  }
}

// ============================================================================
// PLAYER/SERVER QUERIES
// ============================================================================

/**
 * Get players on a specific server
 */
async function getPlayersOnServer(serverAudience) {
  if (!isConnected()) return [];

  try {
    const playerUuids = await redis.smembers(`${KEYS.SERVER_PLAYERS}${serverAudience}`);
    if (!playerUuids || playerUuids.length === 0) return [];

    const players = [];
    for (const uuid of playerUuids) {
      let username = uuidUsernameCache.get(uuid);
      if (!username) {
        username = await redis.get(`${KEYS.USERNAME}${uuid}`);
        if (username) {
          uuidUsernameCache.set(uuid, username);
        }
      }
      players.push({
        uuid,
        username: username || `Player_${uuid.substring(0, 8)}`
      });
    }
    return players;
  } catch (e) {
    console.error('Failed to get players on server:', e.message);
    return [];
  }
}

/**
 * Find player by username on a specific server
 */
async function findPlayerOnServer(serverAudience, username) {
  const players = await getPlayersOnServer(serverAudience);
  return players.filter(p => p.username.toLowerCase() === username.toLowerCase());
}

/**
 * Get all active sessions
 */
async function getAllActiveSessions() {
  if (!isConnected()) return { sessions: [], servers: [] };

  try {
    const sessionKeys = await redis.keys(`${KEYS.SESSION}*`);
    const sessions = [];
    const playerTtls = new Map();

    for (const key of sessionKeys) {
      const sessionJson = await redis.get(key);
      if (sessionJson) {
        const session = JSON.parse(sessionJson);
        session.token = key.replace(KEYS.SESSION, '').substring(0, 8) + '...';

        const ttl = await redis.ttl(key);
        session.ttl = ttl;
        session.ttlMinutes = Math.round(ttl / 60);
        session.ttlHours = Math.round(ttl / 3600 * 10) / 10;

        if (!playerTtls.has(session.uuid) || ttl > playerTtls.get(session.uuid)) {
          playerTtls.set(session.uuid, ttl);
        }

        sessions.push(session);
      }
    }

    const validPlayerUuids = new Set(sessions.map(s => s.uuid));

    const serverKeys = await redis.keys(`${KEYS.SERVER_PLAYERS}*`);
    const servers = [];

    for (const key of serverKeys) {
      const serverAudience = key.replace(KEYS.SERVER_PLAYERS, '');
      const playerUuids = await redis.smembers(key);

      const activePlayers = [];
      const staleUuids = [];

      for (const uuid of playerUuids) {
        if (validPlayerUuids.has(uuid)) {
          let username = uuidUsernameCache.get(uuid);
          if (!username) {
            username = await redis.get(`${KEYS.USERNAME}${uuid}`);
            if (username) {
              uuidUsernameCache.set(uuid, username);
            }
          }
          const ttl = playerTtls.get(uuid) || 0;
          activePlayers.push({
            uuid,
            username: username || `Player_${uuid.substring(0, 8)}`,
            ttl: ttl,
            ttlMinutes: Math.round(ttl / 60),
            ttlHours: Math.round(ttl / 3600 * 10) / 10
          });
        } else {
          staleUuids.push(uuid);
        }
      }

      if (staleUuids.length > 0) {
        for (const uuid of staleUuids) {
          await redis.srem(key, uuid);
        }
        console.log(`Cleaned ${staleUuids.length} stale players from server ${serverAudience}`);
      }

      if (activePlayers.length === 0) {
        await redis.del(key);
        console.log(`Removed empty server: ${serverAudience}`);
        continue;
      }

      let serverName = await getServerName(serverAudience);
      let serverIp = await getServerIp(serverAudience);
      let serverVersion = await getServerVersion(serverAudience);

      // Enrich players with state data
      for (const player of activePlayers) {
        const stateJson = await redis.get(`player:state:${player.uuid}`);
        if (stateJson) {
          try {
            const state = JSON.parse(stateJson);
            player.state = {
              current_state: state.current_state,
              activity_state: state.activity_state,
              game_mode: state.game_mode,
              fps: state.fps,
              latency: state.latency,
              connected: state.connected,
              session_duration: state.session_duration_seconds,
              updated_at: state.updated_at
            };
          } catch (e) {
            // Ignore parse errors
          }
        }
      }

      servers.push({
        audience: serverAudience,
        name: serverName,
        ip: serverIp,
        version: serverVersion,
        playerCount: activePlayers.length,
        players: activePlayers
      });
    }

    servers.sort((a, b) => b.playerCount - a.playerCount);

    return { sessions, servers };
  } catch (e) {
    console.error('Failed to get active sessions:', e.message);
    return { sessions: [], servers: [] };
  }
}

// ============================================================================
// SERVER NAME MANAGEMENT
// ============================================================================

/**
 * Get server display name from Redis
 */
async function getServerName(audience) {
  if (!audience || !isConnected()) return null;

  try {
    return await redis.get(`${KEYS.SERVER_NAME}${audience}`);
  } catch (e) {
    return null;
  }
}

/**
 * Set server display name in Redis
 */
async function setServerName(audience, name) {
  if (!audience || !name || !isConnected()) return false;

  try {
    await redis.set(`${KEYS.SERVER_NAME}${audience}`, name);
    console.log(`Server name set: ${audience} -> "${name}"`);
    return true;
  } catch (e) {
    console.error('Failed to set server name:', e.message);
    return false;
  }
}

/**
 * Set server IP address in Redis
 */
async function setServerIp(audience, ip) {
  if (!audience || !ip || !isConnected()) return false;

  try {
    await redis.set(`${KEYS.SERVER_NAME}${audience}:ip`, ip);
    console.log(`Server IP set: ${audience} -> "${ip}"`);
    return true;
  } catch (e) {
    console.error('Failed to set server IP:', e.message);
    return false;
  }
}

/**
 * Get server IP address from Redis
 */
async function getServerIp(audience) {
  if (!audience || !isConnected()) return null;

  try {
    return await redis.get(`${KEYS.SERVER_NAME}${audience}:ip`);
  } catch (e) {
    return null;
  }
}

/**
 * Remove a player from all servers (used for session delete/logout)
 */
async function removePlayerFromAllServers(playerUuid) {
  if (!playerUuid || !isConnected()) return false;

  try {
    // Get the server the player is on
    const currentServer = await redis.get(`${KEYS.PLAYER_SERVER}${playerUuid}`);

    // Only proceed if player is actually on a server
    if (!currentServer) return false;

    // Remove player from server's player set
    await redis.srem(`${KEYS.SERVER_PLAYERS}${currentServer}`, playerUuid);

    // Check if server is now empty
    const remaining = await redis.scard(`${KEYS.SERVER_PLAYERS}${currentServer}`);
    if (remaining === 0) {
      await redis.del(`${KEYS.SERVER_PLAYERS}${currentServer}`);
      await redis.zrem('active:servers', currentServer);
      console.log(`Removed empty server: ${currentServer}`);
    }

    // Remove player's server tracking (but keep in active:players - they're still in game)
    await redis.del(`${KEYS.PLAYER_SERVER}${playerUuid}`);

    // DON'T remove from active:players - player is still active, just not on a server
    // They'll be removed when their TTL expires or when session_end is received

    console.log(`Player ${playerUuid} left server ${currentServer}`);
    return true;
  } catch (e) {
    console.error('Failed to remove player from servers:', e.message);
    return false;
  }
}

/**
 * Set server version in Redis
 */
async function setServerVersion(audience, version) {
  if (!audience || !version || !isConnected()) return false;

  try {
    await redis.set(`${KEYS.SERVER_NAME}${audience}:version`, version);
    return true;
  } catch (e) {
    console.error('Failed to set server version:', e.message);
    return false;
  }
}

/**
 * Get server version from Redis
 */
async function getServerVersion(audience) {
  if (!audience || !isConnected()) return null;

  try {
    return await redis.get(`${KEYS.SERVER_NAME}${audience}:version`);
  } catch (e) {
    return null;
  }
}

// ============================================================================
// PLAYER STATE (from telemetry heartbeat)
// ============================================================================

/**
 * Update player's current state from heartbeat telemetry
 */
async function updatePlayerState(playerUuid, state) {
  if (!playerUuid || !isConnected()) return false;

  try {
    const stateData = {
      ...state,
      updated_at: new Date().toISOString()
    };
    // 5 minute TTL - if no heartbeat, state becomes stale
    await redis.setex(`player:state:${playerUuid}`, 300, JSON.stringify(stateData));
    return true;
  } catch (e) {
    console.error('Failed to update player state:', e.message);
    return false;
  }
}

/**
 * Get player's current state
 */
async function getPlayerState(playerUuid) {
  if (!playerUuid || !isConnected()) return null;

  try {
    const stateJson = await redis.get(`player:state:${playerUuid}`);
    if (stateJson) {
      return JSON.parse(stateJson);
    }
    return null;
  } catch (e) {
    return null;
  }
}

/**
 * Update player's hardware info from session_start telemetry
 */
async function updatePlayerHardware(playerUuid, hardware) {
  if (!playerUuid || !isConnected()) return false;

  try {
    const userKey = `${KEYS.USER}${playerUuid}`;
    let userData = {};
    const existing = await redis.get(userKey);
    if (existing) {
      userData = JSON.parse(existing);
    }
    userData.hardware = hardware;
    userData.lastSessionStart = new Date().toISOString();
    await redis.set(userKey, JSON.stringify(userData));

    // Track in persistent set of all players with hardware data
    await redis.sadd('players:with_hardware', playerUuid);

    return true;
  } catch (e) {
    console.error('Failed to update player hardware:', e.message);
    return false;
  }
}

/**
 * Record session end data for analytics
 */
async function recordSessionEnd(playerUuid, sessionData) {
  if (!playerUuid || !isConnected()) return false;

  try {
    // Store in time-series key for recent sessions (keep last 100 per player)
    const sessionEndKey = `session_end:${playerUuid}`;
    const record = {
      ...sessionData,
      recorded_at: new Date().toISOString()
    };

    // Add to list (LPUSH) and trim to keep last 10 sessions per player
    await redis.lpush(sessionEndKey, JSON.stringify(record));
    await redis.ltrim(sessionEndKey, 0, 9);
    await redis.expire(sessionEndKey, 86400 * 30); // Keep for 30 days

    // Update user data with last session stats
    const userKey = `${KEYS.USER}${playerUuid}`;
    let userData = {};
    const existing = await redis.get(userKey);
    if (existing) {
      userData = JSON.parse(existing);
    }

    // Aggregate playtime
    userData.totalPlaytimeSeconds = (userData.totalPlaytimeSeconds || 0) + (sessionData.total_duration_seconds || 0);
    userData.totalInGameSeconds = (userData.totalInGameSeconds || 0) + (sessionData.total_in_game_seconds || 0);
    userData.sessionCount = (userData.sessionCount || 0) + 1;
    userData.lastSessionEnd = new Date().toISOString();
    userData.lastExitReason = sessionData.exit_reason;

    await redis.set(userKey, JSON.stringify(userData));

    // Track global stats in sorted set for analytics
    const today = new Date().toISOString().split('T')[0];
    await redis.hincrby(`stats:daily:${today}`, 'sessions_ended', 1);
    await redis.hincrby(`stats:daily:${today}`, 'playtime_seconds', sessionData.total_duration_seconds || 0);
    await redis.expire(`stats:daily:${today}`, 86400 * 90); // Keep 90 days

    // Track exit reason counts
    if (sessionData.exit_reason) {
      await redis.hincrby(`stats:exit_reasons`, sessionData.exit_reason, 1);
    }

    return true;
  } catch (e) {
    console.error('Failed to record session end:', e.message);
    return false;
  }
}

/**
 * Record telemetry event for analytics
 */
async function recordEvent(playerUuid, eventData) {
  if (!playerUuid || !isConnected()) return false;

  try {
    const eventName = eventData.event_name;

    // Store recent events in a capped list per event type
    const eventKey = `events:${eventName}`;
    const record = {
      uuid: playerUuid,
      ...eventData,
      recorded_at: new Date().toISOString()
    };

    await redis.lpush(eventKey, JSON.stringify(record));
    await redis.ltrim(eventKey, 0, 999); // Keep last 1000 events per type
    await redis.expire(eventKey, 86400 * 7); // Keep for 7 days

    // Track event counts daily
    const today = new Date().toISOString().split('T')[0];
    await redis.hincrby(`stats:events:${today}`, eventName, 1);
    await redis.expire(`stats:events:${today}`, 86400 * 90);

    // Special handling for specific events
    if (eventName === 'server_disconnect' && eventData.event_data?.reason) {
      await redis.hincrby('stats:disconnect_reasons', eventData.event_data.reason, 1);
    }

    return true;
  } catch (e) {
    console.error('Failed to record event:', e.message);
    return false;
  }
}

/**
 * Get session end stats for a player
 */
async function getPlayerSessionStats(playerUuid) {
  if (!playerUuid || !isConnected()) return null;

  try {
    const userKey = `${KEYS.USER}${playerUuid}`;
    const userData = await redis.get(userKey);
    if (!userData) return null;

    const data = JSON.parse(userData);
    return {
      totalPlaytimeSeconds: data.totalPlaytimeSeconds || 0,
      totalInGameSeconds: data.totalInGameSeconds || 0,
      sessionCount: data.sessionCount || 0,
      lastSessionEnd: data.lastSessionEnd,
      lastExitReason: data.lastExitReason
    };
  } catch (e) {
    return null;
  }
}

/**
 * Get global analytics stats
 */
async function getAnalyticsStats() {
  if (!isConnected()) return {};

  try {
    const today = new Date().toISOString().split('T')[0];

    // Get daily stats
    const dailyStats = await redis.hgetall(`stats:daily:${today}`) || {};

    // Get exit reasons distribution
    const exitReasons = await redis.hgetall('stats:exit_reasons') || {};

    // Get disconnect reasons distribution
    const disconnectReasons = await redis.hgetall('stats:disconnect_reasons') || {};

    // Get language distribution from hardware
    const languageStats = {};
    // Scan users to aggregate languages (cached)
    const userKeys = [];
    let cursor = '0';
    do {
      const [newCursor, keys] = await redis.scan(cursor, 'MATCH', `${KEYS.USER}*`, 'COUNT', 500);
      cursor = newCursor;
      userKeys.push(...keys.slice(0, 1000)); // Limit to first 1000
    } while (cursor !== '0' && userKeys.length < 1000);

    for (const key of userKeys.slice(0, 500)) { // Sample 500
      try {
        const userData = await redis.get(key);
        if (userData) {
          const data = JSON.parse(userData);
          const lang = data.hardware?.language || data.hardware?.settings?.language || 'unknown';
          languageStats[lang] = (languageStats[lang] || 0) + 1;
        }
      } catch (e) {}
    }

    return {
      daily: {
        sessions_ended: parseInt(dailyStats.sessions_ended) || 0,
        playtime_seconds: parseInt(dailyStats.playtime_seconds) || 0,
        playtime_hours: Math.round((parseInt(dailyStats.playtime_seconds) || 0) / 3600 * 10) / 10
      },
      exitReasons,
      disconnectReasons,
      languages: languageStats
    };
  } catch (e) {
    console.error('getAnalyticsStats error:', e.message);
    return {};
  }
}

// ============================================================================
// USER DATA
// ============================================================================

/**
 * Persist username to Redis
 */
async function persistUsername(uuid, name) {
  if (!uuid || !name || name === 'Player') return;

  uuidUsernameCache.set(uuid, name);

  if (isConnected()) {
    try {
      await redis.set(`${KEYS.USERNAME}${uuid}`, name);

      const userKey = `${KEYS.USER}${uuid}`;
      let userData = {};
      const existing = await redis.get(userKey);
      if (existing) {
        userData = JSON.parse(existing);
      }
      userData.username = name;
      userData.lastSeen = new Date().toISOString();
      await redis.set(userKey, JSON.stringify(userData));
    } catch (e) {
      console.error('Failed to persist username:', e.message);
    }
  }
}

/**
 * Get user data from Redis
 */
async function getUserData(uuid) {
  if (!isConnected()) return {};

  try {
    const data = await redis.get(`${KEYS.USER}${uuid}`);
    return data ? JSON.parse(data) : {};
  } catch (e) {
    console.error('Failed to get user data:', e.message);
    return {};
  }
}

/**
 * Save user data to Redis
 */
async function saveUserData(uuid, data) {
  if (!isConnected()) {
    console.error('saveUserData: Redis not connected, skipping save for', uuid);
    return;
  }

  try {
    await redis.set(`${KEYS.USER}${uuid}`, JSON.stringify(data));
    console.log('saveUserData: saved to Redis for', uuid);
    if (data.username) {
      await redis.set(`${KEYS.USERNAME}${uuid}`, data.username);
      uuidUsernameCache.set(uuid, data.username);
    }
  } catch (e) {
    console.error('Failed to save user data:', e.message);
  }
}

/**
 * Atomically update skin data using Lua script
 * This prevents race conditions when multiple workers handle concurrent skin updates
 */
async function atomicUpdateSkin(uuid, newSkinData) {
  if (!isConnected()) {
    console.error('atomicUpdateSkin: Redis not connected, skipping save for', uuid);
    return null;
  }

  const key = `${KEYS.USER}${uuid}`;
  const now = new Date().toISOString();

  // Lua script for atomic read-modify-write
  // KEYS[1] = user key
  // ARGV[1] = new skin data JSON
  // ARGV[2] = lastUpdated timestamp
  const luaScript = `
    local currentData = redis.call('GET', KEYS[1])
    local userData = {}

    if currentData then
      userData = cjson.decode(currentData)
    end

    -- Parse new skin data
    local newSkin = cjson.decode(ARGV[1])

    -- Merge skin data (new values overlay existing)
    if not userData.skin then
      userData.skin = {}
    end

    for k, v in pairs(newSkin) do
      userData.skin[k] = v
    end

    -- Update timestamp
    userData.lastUpdated = ARGV[2]

    -- Save back
    local result = cjson.encode(userData)
    redis.call('SET', KEYS[1], result)

    return result
  `;

  try {
    const result = await redis.eval(luaScript, 1, key, JSON.stringify(newSkinData), now);
    const savedData = JSON.parse(result);
    console.log('atomicUpdateSkin: saved to Redis for', uuid, 'haircut:', savedData.skin?.haircut);
    return savedData;
  } catch (e) {
    console.error('atomicUpdateSkin failed:', e.message);
    // Fallback to non-atomic update
    console.log('atomicUpdateSkin: falling back to non-atomic save for', uuid);
    const existingData = await getUserData(uuid);
    existingData.skin = { ...existingData.skin, ...newSkinData };
    existingData.lastUpdated = now;
    await saveUserData(uuid, existingData);
    return existingData;
  }
}

/**
 * Get username from cache or Redis
 */
async function getUsername(uuid) {
  if (uuidUsernameCache.has(uuid)) {
    return uuidUsernameCache.get(uuid);
  }

  if (isConnected()) {
    try {
      const username = await redis.get(`${KEYS.USERNAME}${uuid}`);
      if (username) {
        uuidUsernameCache.set(uuid, username);
        return username;
      }
    } catch (e) {
      // Fall through to default
    }
  }

  return null;
}

/**
 * Get username from local cache only (sync)
 */
function getCachedUsername(uuid) {
  return uuidUsernameCache.get(uuid);
}

/**
 * Set username in local cache (sync)
 */
function setCachedUsername(uuid, username) {
  uuidUsernameCache.set(uuid, username);
}

// ============================================================================
// ADMIN STATS AND QUERIES (OPTIMIZED)
// ============================================================================

// Cache for expensive operations
const statsCache = {
  data: null,
  timestamp: 0,
  ttl: 10000 // 10 second cache
};

const activePlayersCache = {
  data: null,
  timestamp: 0,
  ttl: 15000 // 15 second cache
};

/**
 * Check if Redis is connected
 */
function isRedisConnected() {
  return isConnected();
}

/**
 * Get key counts for admin stats - CACHED for performance
 */
async function getKeyCounts() {
  const now = Date.now();
  if (statsCache.data && (now - statsCache.timestamp) < statsCache.ttl) {
    return statsCache.data;
  }

  const counts = { sessions: 0, authGrants: 0, users: 0, servers: 0, activePlayers: 0 };
  if (!isConnected()) return counts;

  try {
    // Use Redis DBSIZE for rough count, then sample for accuracy
    // Count only essential keys in parallel using SCAN with limits
    const countKeysLimited = async (pattern, limit = 10000) => {
      let count = 0;
      let cursor = '0';
      let iterations = 0;
      do {
        const [newCursor, keys] = await redis.scan(cursor, 'MATCH', pattern, 'COUNT', 1000);
        cursor = newCursor;
        count += keys.length;
        iterations++;
        if (iterations > 20) break; // Limit iterations
      } while (cursor !== '0' && count < limit);
      return count;
    };

    // Get counts in parallel - fast operations only
    const [sessions, users] = await Promise.all([
      countKeysLimited(`${KEYS.SESSION}*`, 5000),
      countKeysLimited(`${KEYS.USER}*`, 50000)
    ]);

    counts.sessions = sessions;
    counts.users = users;

    // Get active counts from sorted set (fast O(1) operation)
    const activeServers = await redis.zcard('active:servers');
    const activePlayers = await redis.zcard('active:players');

    counts.servers = activeServers || 0;
    counts.activePlayers = activePlayers || 0;

    statsCache.data = counts;
    statsCache.timestamp = now;
  } catch (e) {
    console.error('Error getting key counts:', e.message);
  }

  return counts;
}

/**
 * Track active player (called on session/auth grant)
 */
async function trackActivePlayer(uuid, serverAudience) {
  if (!isConnected() || !uuid) return;
  try {
    const now = Date.now();
    const expiry = now + (config.sessionTtl * 1000);

    // Add to sorted sets with expiry timestamp as score
    await redis.zadd('active:players', expiry, uuid);
    if (serverAudience && serverAudience !== 'hytale-client') {
      await redis.zadd('active:servers', expiry, serverAudience);
    }

    // Clean expired entries periodically (1% chance per call)
    if (Math.random() < 0.01) {
      await redis.zremrangebyscore('active:players', 0, now);
      await redis.zremrangebyscore('active:servers', 0, now);
    }
  } catch (e) {
    // Non-critical, ignore errors
  }
}

/**
 * Get active players list - CACHED and paginated
 */
async function getActivePlayers(page = 1, limit = 50) {
  if (!isConnected()) return { players: [], total: 0, page, limit };

  try {
    const now = Date.now();

    // Clean expired first
    await redis.zremrangebyscore('active:players', 0, now);

    const total = await redis.zcard('active:players');
    const offset = (page - 1) * limit;

    // Get UUIDs sorted by most recent (highest score = latest expiry)
    const uuids = await redis.zrevrange('active:players', offset, offset + limit - 1);

    if (!uuids.length) return { players: [], total, page, limit };

    // Batch fetch player data
    const players = await Promise.all(uuids.map(async (uuid) => {
      const [username, serverAudience, stateJson, userData] = await Promise.all([
        getUsername(uuid),
        redis.get(`${KEYS.PLAYER_SERVER}${uuid}`),
        redis.get(`player:state:${uuid}`),
        getUserData(uuid)
      ]);

      let state = null;
      if (stateJson) {
        try { state = JSON.parse(stateJson); } catch (e) {}
      }

      // Extract hardware info if available
      const hw = userData?.hardware;

      return {
        uuid,
        username: username || `Player_${uuid.substring(0, 8)}`,
        server: serverAudience,
        state: state ? {
          fps: state.fps,
          latency: state.latency,
          activity_state: state.activity_state,
          current_state: state.current_state,
          connected: state.connected,
          updated_at: state.updated_at
        } : null,
        hardware: hw ? {
          os: hw.os,
          gpu: hw.gpu_vendor,
          resolution: hw.resolution,
          memory_mb: hw.system_memory_mb,
          cpu_cores: hw.cpu_cores
        } : null
      };
    }));

    return {
      players,
      total,
      page,
      limit,
      totalPages: Math.ceil(total / limit)
    };
  } catch (e) {
    console.error('getActivePlayers error:', e.message);
    return { players: [], total: 0, page, limit };
  }
}

/**
 * Get active servers list - OPTIMIZED with sorted set
 */
async function getActiveServers(page = 1, limit = 20) {
  if (!isConnected()) return { servers: [], total: 0, page, limit };

  try {
    const now = Date.now();

    // Clean expired
    await redis.zremrangebyscore('active:servers', 0, now);

    // Get all active server audiences
    const allServers = await redis.zrevrange('active:servers', 0, -1);

    if (!allServers.length) return { servers: [], total: 0, page, limit };

    // Get player counts for sorting
    const serversWithCounts = await Promise.all(allServers.map(async (audience) => {
      const count = await redis.scard(`${KEYS.SERVER_PLAYERS}${audience}`);
      return { audience, count };
    }));

    // Sort by player count descending
    serversWithCounts.sort((a, b) => b.count - a.count);

    const total = serversWithCounts.length;
    const offset = (page - 1) * limit;
    const pageServers = serversWithCounts.slice(offset, offset + limit);

    // Fetch full details for page
    const servers = await Promise.all(pageServers.map(async ({ audience, count }) => {
      const [name, ip, version, playerUuids] = await Promise.all([
        redis.get(`${KEYS.SERVER_NAME}${audience}`),
        redis.get(`${KEYS.SERVER_NAME}${audience}:ip`),
        redis.get(`${KEYS.SERVER_NAME}${audience}:version`),
        redis.smembers(`${KEYS.SERVER_PLAYERS}${audience}`)
      ]);

      // Get player details (limit to first 20 for performance)
      const players = await Promise.all(playerUuids.slice(0, 20).map(async (uuid) => {
        const [username, stateJson] = await Promise.all([
          getUsername(uuid),
          redis.get(`player:state:${uuid}`)
        ]);

        let state = null;
        if (stateJson) {
          try { state = JSON.parse(stateJson); } catch (e) {}
        }

        return {
          uuid,
          username: username || `Player_${uuid.substring(0, 8)}`,
          state: state ? { fps: state.fps, latency: state.latency } : null
        };
      }));

      return {
        audience,
        name,
        ip,
        version,
        playerCount: count,
        players,
        hasMore: playerUuids.length > 20
      };
    }));

    return {
      servers,
      total,
      page,
      limit,
      totalPages: Math.ceil(total / limit)
    };
  } catch (e) {
    console.error('getActiveServers error:', e.message);
    return { servers: [], total: 0, page, limit };
  }
}

/**
 * Get paginated servers with players (for admin dashboard)
 * @param {number} page - Page number (1-indexed)
 * @param {number} limit - Items per page
 * @param {boolean} activeOnly - If true, only return servers with active players (TTL > 0)
 */
async function getPaginatedServers(page, limit, activeOnly = true) {
  const offset = (page - 1) * limit;

  if (!isConnected()) {
    return {
      servers: [],
      pagination: { page, limit, totalServers: 0, totalPages: 0, hasNext: false, hasPrev: false },
      timestamp: new Date().toISOString()
    };
  }

  try {
    // Get all server keys using SCAN
    const serverKeys = [];
    let cursor = '0';
    do {
      const [newCursor, keys] = await redis.scan(cursor, 'MATCH', `${KEYS.SERVER_PLAYERS}*`, 'COUNT', 500);
      cursor = newCursor;
      serverKeys.push(...keys);
    } while (cursor !== '0');

    // Get player counts for all servers
    // Filter out 'hytale-client' which contains ALL players with valid tokens
    let serverCounts = (await Promise.all(serverKeys.map(async (key) => ({
      key,
      audience: key.replace(KEYS.SERVER_PLAYERS, ''),
      count: await redis.scard(key)
    })))).filter(s => s.audience !== 'hytale-client');

    // If activeOnly, filter servers with players and verify players have valid TTL
    if (activeOnly) {
      serverCounts = serverCounts.filter(s => s.count > 0);
    }

    // Sort by player count descending
    serverCounts.sort((a, b) => b.count - a.count);

    const totalServers = serverCounts.length;
    const totalPages = Math.ceil(totalServers / limit);

    // Get only the servers for this page
    const pageServers = serverCounts.slice(offset, offset + limit);

    // Fetch full details for this page's servers
    let servers = await Promise.all(pageServers.map(async ({ key, audience, count }) => {
      const [playerUuids, serverName, serverIp, serverVersion] = await Promise.all([
        redis.smembers(key),
        redis.get(`${KEYS.SERVER_NAME}${audience}`),
        redis.get(`${KEYS.SERVER_NAME}${audience}:ip`),
        redis.get(`${KEYS.SERVER_NAME}${audience}:version`)
      ]);

      // Get usernames, TTLs, state, and hardware for players
      let players = await Promise.all(playerUuids.map(async (uuid) => {
        let username = uuidUsernameCache.get(uuid);

        const [usernameFromRedis, ttl, stateJson, userData] = await Promise.all([
          username ? Promise.resolve(null) : redis.get(`${KEYS.USERNAME}${uuid}`),
          redis.ttl(`${KEYS.PLAYER_SERVER}${uuid}`),
          redis.get(`player:state:${uuid}`),
          getUserData(uuid)
        ]);

        if (!username && usernameFromRedis) {
          username = usernameFromRedis;
          uuidUsernameCache.set(uuid, username);
        }

        // Parse player state from telemetry
        let state = null;
        if (stateJson) {
          try {
            state = JSON.parse(stateJson);
          } catch (e) {
            // Ignore parse errors
          }
        }

        // Extract hardware info
        const hw = userData?.hardware;

        return {
          uuid,
          username: username || `Player_${uuid.substring(0, 8)}`,
          ttl: ttl > 0 ? ttl : 0,
          state: state ? {
            current_state: state.current_state,
            activity_state: state.activity_state,
            game_mode: state.game_mode,
            fps: state.fps,
            latency: state.latency,
            connected: state.connected,
            session_duration: state.session_duration_seconds,
            updated_at: state.updated_at
          } : null,
          hardware: hw ? {
            os: hw.os,
            gpu: hw.gpu_vendor,
            resolution: hw.resolution,
            memory_mb: hw.system_memory_mb,
            cpu_cores: hw.cpu_cores
          } : null
        };
      }));

      // If activeOnly, filter out players with expired TTL and clean them from the set
      if (activeOnly) {
        const stalePlayers = players.filter(p => p.ttl <= 0);
        if (stalePlayers.length > 0) {
          // Clean up stale players from this server
          for (const p of stalePlayers) {
            await redis.srem(key, p.uuid);
          }
        }
        players = players.filter(p => p.ttl > 0);
      }

      return {
        audience,
        name: serverName,
        ip: serverIp,
        version: serverVersion,
        playerCount: players.length,
        players
      };
    }));

    // If activeOnly, filter out servers that ended up with no active players
    if (activeOnly) {
      servers = servers.filter(s => s.players.length > 0);
    }

    return {
      servers,
      pagination: {
        page,
        limit,
        totalServers,
        totalPages,
        hasNext: page < totalPages,
        hasPrev: page > 1
      },
      timestamp: new Date().toISOString()
    };
  } catch (e) {
    console.error('getPaginatedServers error:', e.message);
    return {
      servers: [],
      pagination: { page, limit, totalServers: 0, totalPages: 0, hasNext: false, hasPrev: false },
      error: e.message,
      timestamp: new Date().toISOString()
    };
  }
}

/**
 * Get all player UUIDs from all servers (for prerender queue)
 */
async function getAllPlayerUuids() {
  if (!isConnected()) return [];

  try {
    const serverKeys = [];
    let cursor = '0';
    do {
      const [newCursor, keys] = await redis.scan(cursor, 'MATCH', `${KEYS.SERVER_PLAYERS}*`, 'COUNT', 500);
      cursor = newCursor;
      serverKeys.push(...keys);
    } while (cursor !== '0');

    const allUuids = new Set();
    for (const key of serverKeys) {
      const uuids = await redis.smembers(key);
      uuids.forEach(uuid => allUuids.add(uuid));
    }

    return Array.from(allUuids);
  } catch (e) {
    console.error('Error getting all player UUIDs:', e.message);
    return [];
  }
}

// ============================================================================
// ADMIN TOKENS
// ============================================================================

/**
 * Create admin token
 */
async function createAdminToken(token) {
  if (!isConnected()) return false;

  try {
    await redis.setex(`${KEYS.ADMIN_TOKEN}${token}`, config.adminTokenTtl, '1');
    return true;
  } catch (e) {
    console.error('Failed to create admin token:', e.message);
    return false;
  }
}

/**
 * Verify admin token
 */
async function verifyAdminToken(token) {
  if (!token || !isConnected()) return false;

  try {
    const exists = await redis.exists(`${KEYS.ADMIN_TOKEN}${token}`);
    if (exists) {
      await redis.expire(`${KEYS.ADMIN_TOKEN}${token}`, config.adminTokenTtl);
      return true;
    }
    return false;
  } catch (e) {
    console.error('Redis error checking admin token:', e.message);
    return false;
  }
}

/**
 * Search players by username or UUID - REDIS-BASED (no local cache dependency)
 * Always fetches from Redis to ensure consistent results across workers
 */
async function searchPlayers(query, limit = 50) {
  if (!query || !isConnected()) return [];

  query = query.toLowerCase().trim();
  if (query.length < 2) return [];

  try {
    const now = Date.now();

    // Get active player UUIDs from sorted set (limit to 5000 for search coverage)
    const activeUuids = await redis.zrangebyscore('active:players', now, '+inf', 'LIMIT', 0, 5000);
    if (!activeUuids.length) return [];

    // Batch fetch ALL usernames from Redis (consistent across workers)
    const usernameKeys = activeUuids.map(uuid => `${KEYS.USERNAME}${uuid}`);
    const usernames = await redis.mget(usernameKeys);

    // Build lookup map and find matches
    const matched = [];
    for (let i = 0; i < activeUuids.length && matched.length < limit; i++) {
      const uuid = activeUuids[i];
      const username = usernames[i] || 'Player';

      // Update local cache for other operations
      if (username !== 'Player') {
        uuidUsernameCache.set(uuid, username);
      }

      const uuidMatch = uuid.toLowerCase().includes(query);
      const usernameMatch = username.toLowerCase().includes(query);

      if (uuidMatch || usernameMatch) {
        matched.push({ uuid, username });
      }
    }

    if (matched.length === 0) return [];

    // Batch fetch details for matched players only
    const detailKeys = [];
    for (const m of matched) {
      detailKeys.push(`${KEYS.PLAYER_SERVER}${m.uuid}`);
      detailKeys.push(`player:state:${m.uuid}`);
    }

    const details = await redis.mget(detailKeys);
    const results = [];

    for (let i = 0; i < matched.length; i++) {
      const m = matched[i];
      const serverAudience = details[i * 2];
      const stateJson = details[i * 2 + 1];

      let state = null;
      if (stateJson) {
        try {
          const parsed = JSON.parse(stateJson);
          state = {
            fps: parsed.fps,
            latency: parsed.latency,
            current_state: parsed.current_state,
            connected: parsed.connected,
            activity_state: parsed.activity_state
          };
        } catch (e) {}
      }

      results.push({
        uuid: m.uuid,
        username: m.username || 'Player',
        ttl: 0,
        state,
        server: serverAudience,
        servers: serverAudience ? [{
          audience: serverAudience,
          name: serverAudience.substring(0, 8)
        }] : []
      });
    }

    return results;
  } catch (e) {
    console.error('Error searching players:', e.message);
    return [];
  }
}

// ============================================================================
// CLEANUP FUNCTIONS
// ============================================================================

/**
 * Clean up all stale servers and players
 * Removes servers with no active players and players with expired TTL
 */
async function cleanupStaleData() {
  if (!isConnected()) return { cleaned: 0, servers: 0, players: 0 };

  let cleanedServers = 0;
  let cleanedPlayers = 0;

  try {
    // Get all server keys
    const serverKeys = [];
    let cursor = '0';
    do {
      const [newCursor, keys] = await redis.scan(cursor, 'MATCH', `${KEYS.SERVER_PLAYERS}*`, 'COUNT', 500);
      cursor = newCursor;
      serverKeys.push(...keys);
    } while (cursor !== '0');

    for (const key of serverKeys) {
      const audience = key.replace(KEYS.SERVER_PLAYERS, '');
      if (audience === 'hytale-client') continue;

      const playerUuids = await redis.smembers(key);

      for (const uuid of playerUuids) {
        const ttl = await redis.ttl(`${KEYS.PLAYER_SERVER}${uuid}`);
        if (ttl <= 0) {
          await redis.srem(key, uuid);
          cleanedPlayers++;
        }
      }

      // Check if server is now empty
      const remaining = await redis.scard(key);
      if (remaining === 0) {
        await redis.del(key);
        cleanedServers++;
      }
    }

    if (cleanedServers > 0 || cleanedPlayers > 0) {
      console.log(`Cleanup: removed ${cleanedServers} empty servers, ${cleanedPlayers} stale players`);
    }

    return { cleaned: cleanedServers + cleanedPlayers, servers: cleanedServers, players: cleanedPlayers };
  } catch (e) {
    console.error('Cleanup error:', e.message);
    return { cleaned: 0, servers: 0, players: 0, error: e.message };
  }
}

/**
 * Get counts for active vs total data
 */
async function getDataCounts() {
  if (!isConnected()) return { activeServers: 0, totalServers: 0, activePlayers: 0, totalPlayers: 0 };

  try {
    const serverKeys = [];
    let cursor = '0';
    do {
      const [newCursor, keys] = await redis.scan(cursor, 'MATCH', `${KEYS.SERVER_PLAYERS}*`, 'COUNT', 500);
      cursor = newCursor;
      serverKeys.push(...keys);
    } while (cursor !== '0');

    const filteredKeys = serverKeys.filter(k => !k.endsWith('hytale-client'));
    const totalServers = filteredKeys.length;

    let activeServers = 0;
    let totalPlayers = 0;
    let activePlayers = 0;

    for (const key of filteredKeys) {
      const playerUuids = await redis.smembers(key);
      const playerCount = playerUuids.length;
      totalPlayers += playerCount;

      let hasActivePlayer = false;
      for (const uuid of playerUuids) {
        const ttl = await redis.ttl(`${KEYS.PLAYER_SERVER}${uuid}`);
        if (ttl > 0) {
          activePlayers++;
          hasActivePlayer = true;
        }
      }
      if (hasActivePlayer) {
        activeServers++;
      }
    }

    return { activeServers, totalServers, activePlayers, totalPlayers };
  } catch (e) {
    console.error('getDataCounts error:', e.message);
    return { activeServers: 0, totalServers: 0, activePlayers: 0, totalPlayers: 0 };
  }
}

// ============================================================================
// DEVICE CODE MANAGEMENT (OAuth Device Flow) - REDIS-BACKED
// ============================================================================

const DEVICE_CODE_TTL = 600; // 10 minutes

/**
 * Register a device code for OAuth device flow (Redis-backed for multi-worker)
 */
async function registerDeviceCode(deviceCode, userCode, clientId, scope) {
  const data = {
    deviceCode,
    userCode,
    clientId,
    scope,
    approved: false,
    createdAt: Date.now(),
    expiresAt: Date.now() + DEVICE_CODE_TTL * 1000
  };

  if (isConnected()) {
    const json = JSON.stringify(data);
    await redis.setex(`devicecode:${deviceCode}`, DEVICE_CODE_TTL, json);
    await redis.setex(`devicecode:user:${userCode}`, DEVICE_CODE_TTL, json);
  }

  console.log(`Device code registered: ${userCode} (expires in 10 min)`);
  return data;
}

/**
 * Get device code data (Redis-backed)
 */
async function getDeviceCode(deviceCode) {
  if (!isConnected()) return null;

  const json = await redis.get(`devicecode:${deviceCode}`);
  if (!json) return null;

  try {
    const data = JSON.parse(json);
    if (Date.now() > data.expiresAt) {
      await redis.del(`devicecode:${deviceCode}`);
      await redis.del(`devicecode:user:${data.userCode}`);
      return null;
    }
    return data;
  } catch (e) {
    return null;
  }
}

/**
 * Approve a device code by user code (Redis-backed)
 */
async function approveDeviceCode(userCode) {
  if (!isConnected()) return false;

  const json = await redis.get(`devicecode:user:${userCode}`);
  if (!json) return false;

  try {
    const data = JSON.parse(json);
    data.approved = true;
    const updatedJson = JSON.stringify(data);

    // Update both keys
    const ttl = Math.max(1, Math.floor((data.expiresAt - Date.now()) / 1000));
    await redis.setex(`devicecode:${data.deviceCode}`, ttl, updatedJson);
    await redis.setex(`devicecode:user:${userCode}`, ttl, updatedJson);

    console.log(`Device code approved: ${userCode}`);
    return true;
  } catch (e) {
    return false;
  }
}

/**
 * Consume (delete) a device code after token exchange (Redis-backed)
 */
async function consumeDeviceCode(deviceCode) {
  if (!isConnected()) return false;

  const json = await redis.get(`devicecode:${deviceCode}`);
  if (!json) return false;

  try {
    const data = JSON.parse(json);
    await redis.del(`devicecode:${deviceCode}`);
    await redis.del(`devicecode:user:${data.userCode}`);
    console.log(`Device code consumed: ${data.userCode}`);
    return true;
  } catch (e) {
    return false;
  }
}

// ============================================================================
// SETTINGS MANAGEMENT (For CDN download links, etc.)
// ============================================================================

const SETTINGS_KEY = 'settings:global';

// Default download links (used if not configured)
const DEFAULT_DOWNLOAD_LINKS = {
  'HytaleServer.jar': 'https://s3.g.s4.mega.io/kcvismkrtfcalgwxzsazbq46l72dwsypqaham/hytale/HytaleServer.jar',
  'Assets.zip': 'https://s3.g.s4.mega.io/kcvismkrtfcalgwxzsazbq46l72dwsypqaham/hytale/Assets.zip'
};

/**
 * Get all settings
 */
async function getSettings() {
  if (!isConnected()) {
    return { downloadLinks: DEFAULT_DOWNLOAD_LINKS };
  }

  try {
    const json = await redis.get(SETTINGS_KEY);
    if (json) {
      const settings = JSON.parse(json);
      // Ensure downloadLinks exists
      if (!settings.downloadLinks) {
        settings.downloadLinks = DEFAULT_DOWNLOAD_LINKS;
      }
      return settings;
    }
    return { downloadLinks: DEFAULT_DOWNLOAD_LINKS };
  } catch (e) {
    console.error('Error getting settings:', e.message);
    return { downloadLinks: DEFAULT_DOWNLOAD_LINKS };
  }
}

/**
 * Save all settings
 */
async function saveSettings(settings) {
  if (!isConnected()) return false;

  try {
    await redis.set(SETTINGS_KEY, JSON.stringify(settings));
    console.log('Settings saved:', Object.keys(settings));
    return true;
  } catch (e) {
    console.error('Error saving settings:', e.message);
    return false;
  }
}

/**
 * Get download link for a file
 * Returns { url, isExternal } - isExternal true means redirect to CDN
 */
async function getDownloadLink(filename) {
  const settings = await getSettings();
  const links = settings.downloadLinks || DEFAULT_DOWNLOAD_LINKS;

  if (links[filename]) {
    return { url: links[filename], isExternal: true };
  }

  return { url: null, isExternal: false };
}

/**
 * Set download link for a file
 */
async function setDownloadLink(filename, url) {
  const settings = await getSettings();
  if (!settings.downloadLinks) {
    settings.downloadLinks = {};
  }
  settings.downloadLinks[filename] = url;
  return await saveSettings(settings);
}

/**
 * Get all download links
 */
async function getDownloadLinks() {
  const settings = await getSettings();
  return settings.downloadLinks || DEFAULT_DOWNLOAD_LINKS;
}

// ============================================================================
// DOWNLOAD METRICS (Per-URL tracking)
// ============================================================================

const DOWNLOAD_METRICS_KEY = 'metrics:downloads';
const DOWNLOAD_HISTORY_KEY = 'metrics:downloads:history';

/**
 * Record a download request
 * @param {string} filename - The filename being downloaded
 * @param {string} url - The URL being redirected to
 */
async function recordDownload(filename, url) {
  if (!isConnected()) return;

  try {
    const now = Date.now();
    const hour = Math.floor(now / 3600000) * 3600000; // Round to hour

    // Create a unique key for this URL (proper hash to ensure uniqueness)
    const urlHash = crypto.createHash('sha256').update(url).digest('hex').substring(0, 16);

    // Increment total counter for this filename
    await redis.hincrby(DOWNLOAD_METRICS_KEY, filename, 1);

    // Increment counter for this specific URL
    await redis.hincrby(DOWNLOAD_METRICS_KEY, `${filename}:${urlHash}`, 1);

    // Store URL -> hash mapping for later lookup
    await redis.hset(`${DOWNLOAD_METRICS_KEY}:urls`, urlHash, url);

    // Add to hourly history (for charts)
    const historyKey = `${DOWNLOAD_HISTORY_KEY}:${filename}:${urlHash}`;
    await redis.hincrby(historyKey, hour.toString(), 1);

    // Set expiry on history (keep 30 days)
    await redis.expire(historyKey, 30 * 24 * 3600);
  } catch (e) {
    // Non-critical, ignore errors
  }
}

/**
 * Get download stats for all files
 */
async function getDownloadStats() {
  if (!isConnected()) return { files: {}, total: 0 };

  try {
    const allMetrics = await redis.hgetall(DOWNLOAD_METRICS_KEY);
    const urlMappings = await redis.hgetall(`${DOWNLOAD_METRICS_KEY}:urls`) || {};

    const files = {};
    let total = 0;

    for (const [key, count] of Object.entries(allMetrics || {})) {
      const countNum = parseInt(count, 10);

      if (!key.includes(':')) {
        // This is a total for a filename
        files[key] = files[key] || { total: 0, urls: {} };
        files[key].total = countNum;
        total += countNum;
      } else {
        // This is a per-URL count (filename:urlHash)
        const [filename, urlHash] = key.split(':');
        const url = urlMappings[urlHash] || 'unknown';

        files[filename] = files[filename] || { total: 0, urls: {} };
        files[filename].urls[url] = countNum;
      }
    }

    return { files, total };
  } catch (e) {
    console.error('Error getting download stats:', e.message);
    return { files: {}, total: 0 };
  }
}

/**
 * Get download history for charts (hourly data)
 * @param {string} filename - The filename
 * @param {string} url - The URL (optional, if not provided returns all URLs)
 * @param {number} hours - How many hours back to fetch (default 168 = 7 days)
 */
async function getDownloadHistory(filename, url = null, hours = 168) {
  if (!isConnected()) return [];

  try {
    const now = Date.now();
    const startHour = Math.floor((now - hours * 3600000) / 3600000) * 3600000;

    // Get all URL hashes for this filename
    const urlMappings = await redis.hgetall(`${DOWNLOAD_METRICS_KEY}:urls`) || {};
    const reverseMap = {};
    for (const [hash, mappedUrl] of Object.entries(urlMappings)) {
      reverseMap[mappedUrl] = hash;
    }

    // Build list of URL hashes to fetch
    let urlHashes;
    if (url) {
      const hash = reverseMap[url];
      if (!hash) return [];
      urlHashes = [{ hash, url }];
    } else {
      // Get all URLs for this filename from metrics
      const allMetrics = await redis.hgetall(DOWNLOAD_METRICS_KEY) || {};
      urlHashes = [];
      for (const key of Object.keys(allMetrics)) {
        if (key.startsWith(`${filename}:`)) {
          const hash = key.split(':')[1];
          const mappedUrl = urlMappings[hash] || 'unknown';
          urlHashes.push({ hash, url: mappedUrl });
        }
      }
    }

    // Fetch history for each URL
    const result = [];
    for (const { hash, url: currentUrl } of urlHashes) {
      const historyKey = `${DOWNLOAD_HISTORY_KEY}:${filename}:${hash}`;
      const history = await redis.hgetall(historyKey) || {};

      const dataPoints = [];
      for (let h = startHour; h <= now; h += 3600000) {
        const count = parseInt(history[h.toString()] || '0', 10);
        dataPoints.push({ timestamp: h, count });
      }

      result.push({
        url: currentUrl,
        data: dataPoints,
        total: dataPoints.reduce((sum, p) => sum + p.count, 0)
      });
    }

    return result;
  } catch (e) {
    console.error('Error getting download history:', e.message);
    return [];
  }
}

module.exports = {
  // Sessions
  registerSession,
  registerAuthGrant,
  removeSession,

  // Players/Servers
  getPlayersOnServer,
  findPlayerOnServer,
  getAllActiveSessions,

  // Server names/info
  getServerName,
  setServerName,
  getServerIp,
  setServerIp,
  getServerVersion,
  setServerVersion,
  removePlayerFromAllServers,

  // Player state (telemetry)
  updatePlayerState,
  getPlayerState,
  updatePlayerHardware,

  // Session/event analytics
  recordSessionEnd,
  recordEvent,
  getPlayerSessionStats,
  getAnalyticsStats,

  // User data
  persistUsername,
  getUserData,
  saveUserData,
  atomicUpdateSkin,
  getUsername,
  getCachedUsername,
  setCachedUsername,

  // Admin stats (optimized)
  isRedisConnected,
  getKeyCounts,
  getPaginatedServers,
  getAllPlayerUuids,
  getDataCounts,
  cleanupStaleData,
  trackActivePlayer,
  getActivePlayers,
  getActiveServers,

  // Player search
  searchPlayers,

  // Admin tokens
  createAdminToken,
  verifyAdminToken,

  // Device codes (OAuth device flow)
  registerDeviceCode,
  getDeviceCode,
  approveDeviceCode,
  consumeDeviceCode,

  // Settings
  getSettings,
  saveSettings,
  getDownloadLink,
  setDownloadLink,
  getDownloadLinks,

  // Download metrics
  recordDownload,
  getDownloadStats,
  getDownloadHistory,
};
