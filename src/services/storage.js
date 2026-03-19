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

// ============================================================================
// ATOMIC PLAYER SKINS OPERATIONS (Multi-worker safe)
// ============================================================================

/**
 * Atomically add a new player skin to the playerSkins array
 * Also sets the new skin as active
 */
async function atomicAddPlayerSkin(uuid, newSkin) {
  if (!isConnected()) {
    console.error('atomicAddPlayerSkin: Redis not connected');
    return null;
  }

  const key = `${KEYS.USER}${uuid}`;

  const luaScript = `
    local currentData = redis.call('GET', KEYS[1])
    local userData = {}

    if currentData then
      userData = cjson.decode(currentData)
    end

    if not userData.playerSkins then
      userData.playerSkins = {}
    end

    -- Add new skin to array
    local newSkin = cjson.decode(ARGV[1])
    table.insert(userData.playerSkins, newSkin)

    -- Set new skin as active
    userData.activeSkin = newSkin.id

    -- Also update legacy skin field
    if newSkin.skinData then
      local ok, parsedSkin = pcall(cjson.decode, newSkin.skinData)
      if ok then
        userData.skin = parsedSkin
      end
    end

    -- Save back
    local result = cjson.encode(userData)
    redis.call('SET', KEYS[1], result)

    return cjson.encode({playerSkins = userData.playerSkins, activeSkin = userData.activeSkin})
  `;

  try {
    const result = await redis.eval(luaScript, 1, key, JSON.stringify(newSkin));
    const parsed = JSON.parse(result);
    console.log('atomicAddPlayerSkin: added skin', newSkin.id, 'for', uuid, 'total skins:', parsed.playerSkins?.length, 'now active');
    return parsed;
  } catch (e) {
    console.error('atomicAddPlayerSkin failed:', e.message);
    return null;
  }
}

/**
 * Atomically update an existing player skin
 * Also sets the updated skin as active
 */
async function atomicUpdatePlayerSkin(uuid, skinId, updates) {
  if (!isConnected()) {
    console.error('atomicUpdatePlayerSkin: Redis not connected');
    return null;
  }

  const key = `${KEYS.USER}${uuid}`;
  const now = new Date().toISOString();

  const luaScript = `
    local currentData = redis.call('GET', KEYS[1])
    local userData = {}

    if currentData then
      userData = cjson.decode(currentData)
    end

    if not userData.playerSkins then
      userData.playerSkins = {}
    end

    local skinId = ARGV[1]
    local updates = cjson.decode(ARGV[2])
    local now = ARGV[3]
    local found = false
    local skinDataStr = nil

    -- Find and update the skin
    for i, skin in ipairs(userData.playerSkins) do
      if skin.id == skinId then
        if updates.name ~= nil then
          skin.name = updates.name
        end
        if updates.skinData ~= nil then
          skin.skinData = updates.skinData
          skinDataStr = updates.skinData
        else
          skinDataStr = skin.skinData
        end
        skin.updatedAt = now
        found = true
        break
      end
    end

    -- If not found, create it
    if not found then
      local newSkin = {
        id = skinId,
        name = updates.name or 'Avatar',
        skinData = updates.skinData or '',
        createdAt = now
      }
      table.insert(userData.playerSkins, newSkin)
      skinDataStr = updates.skinData
    end

    -- Set updated/created skin as active
    userData.activeSkin = skinId

    -- Also update legacy skin field
    if skinDataStr then
      local ok, parsedSkin = pcall(cjson.decode, skinDataStr)
      if ok then
        userData.skin = parsedSkin
      end
    end

    -- Save back
    local result = cjson.encode(userData)
    redis.call('SET', KEYS[1], result)

    return cjson.encode({playerSkins = userData.playerSkins, activeSkin = userData.activeSkin, found = found})
  `;

  try {
    const result = await redis.eval(luaScript, 1, key, skinId, JSON.stringify(updates), now);
    const parsed = JSON.parse(result);
    console.log('atomicUpdatePlayerSkin: updated skin', skinId, 'for', uuid, 'found:', parsed.found, 'now active');
    return parsed;
  } catch (e) {
    console.error('atomicUpdatePlayerSkin failed:', e.message);
    return null;
  }
}

/**
 * Atomically set the active skin
 */
async function atomicSetActiveSkin(uuid, skinId) {
  if (!isConnected()) {
    console.error('atomicSetActiveSkin: Redis not connected');
    return null;
  }

  const key = `${KEYS.USER}${uuid}`;

  const luaScript = `
    local currentData = redis.call('GET', KEYS[1])
    local userData = {}

    if currentData then
      userData = cjson.decode(currentData)
    end

    local skinId = ARGV[1]
    userData.activeSkin = skinId

    -- Also update legacy skin field if we have this skin
    if userData.playerSkins then
      for i, skin in ipairs(userData.playerSkins) do
        if skin.id == skinId and skin.skinData then
          local ok, parsedSkin = pcall(cjson.decode, skin.skinData)
          if ok then
            userData.skin = parsedSkin
          end
          break
        end
      end
    end

    -- Save back
    local result = cjson.encode(userData)
    redis.call('SET', KEYS[1], result)

    return cjson.encode({activeSkin = userData.activeSkin})
  `;

  try {
    const result = await redis.eval(luaScript, 1, key, skinId);
    const parsed = JSON.parse(result);
    console.log('atomicSetActiveSkin: set active skin', skinId, 'for', uuid);
    return parsed;
  } catch (e) {
    console.error('atomicSetActiveSkin failed:', e.message);
    return null;
  }
}

/**
 * Atomically delete a player skin
 */
async function atomicDeletePlayerSkin(uuid, skinId) {
  if (!isConnected()) {
    console.error('atomicDeletePlayerSkin: Redis not connected');
    return null;
  }

  const key = `${KEYS.USER}${uuid}`;

  const luaScript = `
    local currentData = redis.call('GET', KEYS[1])
    local userData = {}

    if currentData then
      userData = cjson.decode(currentData)
    end

    if not userData.playerSkins then
      return cjson.encode({deleted = false, playerSkins = {}, activeSkin = userData.activeSkin})
    end

    local skinId = ARGV[1]
    local deleted = false
    local newSkins = {}

    for i, skin in ipairs(userData.playerSkins) do
      if skin.id ~= skinId then
        table.insert(newSkins, skin)
      else
        deleted = true
      end
    end

    userData.playerSkins = newSkins

    -- If we deleted the active skin, reset to first available
    if userData.activeSkin == skinId then
      if #newSkins > 0 then
        userData.activeSkin = newSkins[1].id
        if newSkins[1].skinData then
          local ok, parsedSkin = pcall(cjson.decode, newSkins[1].skinData)
          if ok then
            userData.skin = parsedSkin
          end
        end
      else
        userData.activeSkin = nil
      end
    end

    -- Save back
    local result = cjson.encode(userData)
    redis.call('SET', KEYS[1], result)

    return cjson.encode({deleted = deleted, playerSkins = userData.playerSkins, activeSkin = userData.activeSkin})
  `;

  try {
    const result = await redis.eval(luaScript, 1, key, skinId);
    const parsed = JSON.parse(result);
    console.log('atomicDeletePlayerSkin: deleted skin', skinId, 'for', uuid, 'success:', parsed.deleted);
    return parsed;
  } catch (e) {
    console.error('atomicDeletePlayerSkin failed:', e.message);
    return null;
  }
}

/**
 * Get player skins data (read-only, safe for any worker)
 */
async function getPlayerSkins(uuid) {
  if (!isConnected()) return { playerSkins: [], activeSkin: null };

  try {
    const data = await redis.get(`${KEYS.USER}${uuid}`);
    if (!data) return { playerSkins: [], activeSkin: null };

    const userData = JSON.parse(data);
    return {
      playerSkins: userData.playerSkins || [],
      activeSkin: userData.activeSkin || null
    };
  } catch (e) {
    console.error('getPlayerSkins failed:', e.message);
    return { playerSkins: [], activeSkin: null };
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
  ttl: 60000 // 60 second cache (matches metrics interval)
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
    const countKeys = async (pattern) => {
      let count = 0;
      let cursor = '0';
      do {
        const [newCursor, keys] = await redis.scan(cursor, 'MATCH', pattern, 'COUNT', 5000);
        cursor = newCursor;
        count += keys.length;
      } while (cursor !== '0');
      return count;
    };

    // Get counts in parallel - full scan, no artificial limits
    const [sessions, users] = await Promise.all([
      countKeys(`${KEYS.SESSION}*`),
      countKeys(`${KEYS.USER}*`)
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

    // Also get servers from active:servers sorted set (includes servers with 0 players)
    const activeServerIds = await redis.zrevrange('active:servers', 0, -1);

    // Get player counts for all servers from SCAN
    // Filter out 'hytale-client' which contains ALL players with valid tokens
    let serverCounts = (await Promise.all(serverKeys.map(async (key) => ({
      key,
      audience: key.replace(KEYS.SERVER_PLAYERS, ''),
      count: await redis.scard(key)
    })))).filter(s => s.audience !== 'hytale-client');

    // Add servers from active:servers that aren't already in the list
    const existingAudiences = new Set(serverCounts.map(s => s.audience));
    const activeServerSet = new Set(activeServerIds);
    for (const serverId of activeServerIds) {
      if (!existingAudiences.has(serverId)) {
        const key = `${KEYS.SERVER_PLAYERS}${serverId}`;
        serverCounts.push({
          key,
          audience: serverId,
          count: await redis.scard(key)
        });
      }
    }

    // If activeOnly, filter servers with players — but always keep servers from active:servers
    if (activeOnly) {
      serverCounts = serverCounts.filter(s => s.count > 0 || activeServerSet.has(s.audience));
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
// ACTIVITY WINDOWS (real-time online counts)
// ============================================================================

/**
 * Get player and server counts for time windows + estimated real-time online.
 *
 * Uses active:players sorted set scores: score = lastActive + sessionTtl*1000
 * So a player active within N ms has score > now + sessionTtl*1000 - N
 *
 * Estimated online uses survival analysis:
 *   For each time cohort (players who started X minutes ago),
 *   multiply by probability they're still playing (from session_duration histogram).
 *   Sum = estimated concurrent players.
 */
async function getActivityWindows() {
  if (!isConnected()) {
    const empty = { players: 0, servers: 0, topServers: [] };
    return { estimatedOnline: 0, windows: { '5m': empty, '15m': empty, '30m': empty, '1h': empty, '2h': empty, '4h': empty } };
  }

  try {
    const now = Date.now();
    const sessionTtlMs = config.sessionTtl * 1000;

    // Windows ordered smallest to largest (needed for cohort calculation)
    const windowDefs = [
      ['5m', 5 * 60 * 1000],
      ['15m', 15 * 60 * 1000],
      ['30m', 30 * 60 * 1000],
      ['1h', 60 * 60 * 1000],
      ['2h', 2 * 60 * 60 * 1000],
      ['4h', 4 * 60 * 60 * 1000]
    ];

    // Fetch all player counts per window (just ZCOUNT for speed, no UUID fetching)
    const windowCounts = {};
    for (const [label, windowMs] of windowDefs) {
      const minScore = now + sessionTtlMs - windowMs;
      windowCounts[label] = await redis.zcount('active:players', minScore, '+inf');
    }

    // Build survival function from session_duration histogram
    // Buckets: [60, 300, 600, 1800, 3600, 7200, 14400, 28800] seconds
    // Read histogram from Redis
    const histData = await redis.hgetall('metrics:histogram:session_duration');
    const bucketBounds = [60, 300, 600, 1800, 3600, 7200, 14400, 28800]; // seconds
    const bucketCounts = [];
    let totalSessions = 0;
    for (let i = 0; i <= bucketBounds.length; i++) {
      const count = parseInt(histData[i.toString()] || '0', 10);
      bucketCounts.push(count);
      totalSessions += count;
    }

    // Build survival function: P(session lasts > T seconds)
    // survival[i] = fraction of sessions lasting longer than bucketBounds[i]
    function survivalAt(seconds) {
      if (totalSessions === 0) return 0.5; // no data, assume 50%
      let ended = 0;
      for (let i = 0; i < bucketBounds.length; i++) {
        if (seconds <= bucketBounds[i]) {
          // Interpolate within this bucket
          const prevBound = i === 0 ? 0 : bucketBounds[i - 1];
          const fraction = (seconds - prevBound) / (bucketBounds[i] - prevBound);
          ended += bucketCounts[i] * fraction;
          return (totalSessions - ended) / totalSessions;
        }
        ended += bucketCounts[i];
      }
      // Beyond last bucket
      const remaining = totalSessions - ended;
      // Assume exponential decay beyond 8h with half-life of 4h
      const beyondSeconds = seconds - bucketBounds[bucketBounds.length - 1];
      const halfLife = 4 * 3600;
      return (remaining / totalSessions) * Math.pow(0.5, beyondSeconds / halfLife);
    }

    // Compute estimated online players using cohort survival
    // Cohort: players who started in each time slice
    // arrivals_in_slice = window[i] - window[i-1]
    // P(still playing) = survival(midpoint of slice)
    const cohorts = [
      { label: '0-5m',   arrivals: windowCounts['5m'],                                  midpointSec: 2.5 * 60 },
      { label: '5-15m',  arrivals: windowCounts['15m'] - windowCounts['5m'],             midpointSec: 10 * 60 },
      { label: '15-30m', arrivals: windowCounts['30m'] - windowCounts['15m'],            midpointSec: 22.5 * 60 },
      { label: '30m-1h', arrivals: windowCounts['1h']  - windowCounts['30m'],            midpointSec: 45 * 60 },
      { label: '1-2h',   arrivals: windowCounts['2h']  - windowCounts['1h'],             midpointSec: 90 * 60 },
      { label: '2-4h',   arrivals: windowCounts['4h']  - windowCounts['2h'],             midpointSec: 180 * 60 }
    ];

    let estimatedOnline = 0;
    const cohortDetails = [];
    for (const c of cohorts) {
      const prob = survivalAt(c.midpointSec);
      const expected = Math.round(c.arrivals * prob);
      estimatedOnline += expected;
      cohortDetails.push({
        cohort: c.label,
        arrivals: c.arrivals,
        survivalProb: Math.round(prob * 1000) / 1000,
        expectedActive: expected
      });
    }

    // Also get heartbeat-confirmed count
    const heartbeatCount = await redis.keys('player:state:*');
    const confirmedOnline = heartbeatCount.length;

    // Use the higher of estimated or confirmed (confirmed is a floor)
    const realTimeOnline = Math.max(estimatedOnline, confirmedOnline);

    // Get top servers (only for 1h window to keep it fast)
    const minScore1h = now + sessionTtlMs - (60 * 60 * 1000);
    const uuids1h = await redis.zrangebyscore('active:players', minScore1h, '+inf');
    const serverCounts = {};
    if (uuids1h.length > 0) {
      const serverAudiences = await Promise.all(
        uuids1h.map(uuid => redis.get(`${KEYS.PLAYER_SERVER}${uuid}`))
      );
      for (let i = 0; i < uuids1h.length; i++) {
        const server = serverAudiences[i];
        if (server && server !== 'hytale-client') {
          serverCounts[server] = (serverCounts[server] || 0) + 1;
        }
      }
    }

    // Get server names for top 10
    const sortedServers = Object.entries(serverCounts)
      .sort(([,a], [,b]) => b - a)
      .slice(0, 10);

    let topServers = [];
    if (sortedServers.length > 0) {
      const names = await Promise.all(
        sortedServers.map(([aud]) => redis.get(`${KEYS.SERVER_NAME}${aud}`))
      );
      topServers = sortedServers.map(([audience, players], i) => ({
        audience,
        name: names[i] || audience,
        players
      }));
    }

    // Build windows result (counts only, no UUID lists)
    const result = {};
    for (const [label] of windowDefs) {
      result[label] = { players: windowCounts[label] };
    }

    return {
      estimatedOnline: realTimeOnline,
      confirmedOnline,
      windows: result,
      cohorts: cohortDetails,
      survival: {
        totalSessions,
        buckets: bucketBounds.map((b, i) => ({
          le: b,
          label: b < 3600 ? `${b/60}m` : `${b/3600}h`,
          count: bucketCounts[i]
        })).concat([{ le: '+Inf', label: '8h+', count: bucketCounts[bucketBounds.length] }])
      },
      topServers,
      timestamp: new Date().toISOString()
    };
  } catch (e) {
    console.error('getActivityWindows error:', e.message);
    return { estimatedOnline: 0, error: e.message };
  }
}

/**
 * Get total database stats - player base size, cosmetics, sessions, etc.
 * Scans Redis key patterns to count total stored data.
 */
async function getDatabaseStats() {
  if (!isConnected()) {
    return { totalPlayers: 0, totalSessions: 0, totalServers: 0, totalUsernames: 0 };
  }

  try {
    const countKeys = async (pattern) => {
      let count = 0;
      let cursor = '0';
      do {
        const [newCursor, keys] = await redis.scan(cursor, 'MATCH', pattern, 'COUNT', 5000);
        cursor = newCursor;
        count += keys.length;
      } while (cursor !== '0');
      return count;
    };

    // Count all key types in parallel
    const [totalUsers, totalUsernames, totalSessions, totalServers, totalPlayerStates, totalHardware, dbSize] = await Promise.all([
      countKeys(`${KEYS.USER}*`),
      countKeys(`${KEYS.USERNAME}*`),
      countKeys(`${KEYS.SESSION}*`),
      countKeys(`${KEYS.SERVER_PLAYERS}*`),
      countKeys('player:state:*'),
      redis.scard('players:with_hardware'),
      redis.dbsize()
    ]);

    // Count users with cosmetics (skin data)
    // User data is stored as JSON in user:{uuid}, need to sample for cosmetics
    let usersWithCosmetics = 0;
    let sampleCount = 0;
    let cursor = '0';
    const sampleSize = Math.min(totalUsers, 500); // sample up to 500
    do {
      const [newCursor, keys] = await redis.scan(cursor, 'MATCH', `${KEYS.USER}*`, 'COUNT', 100);
      cursor = newCursor;
      for (const key of keys) {
        if (sampleCount >= sampleSize) break;
        try {
          const data = await redis.get(key);
          if (data) {
            const parsed = JSON.parse(data);
            if (parsed.skin || parsed.skins) {
              usersWithCosmetics++;
            }
          }
        } catch (e) { /* skip */ }
        sampleCount++;
      }
      if (sampleCount >= sampleSize) break;
    } while (cursor !== '0');

    // Extrapolate if sampled
    if (sampleCount > 0 && sampleCount < totalUsers) {
      usersWithCosmetics = Math.round((usersWithCosmetics / sampleCount) * totalUsers);
    }

    return {
      totalPlayers: totalUsers,
      totalUsernames,
      totalSessions,
      totalServers: totalServers - (totalServers > 0 ? 1 : 0), // exclude hytale-client
      playersWithState: totalPlayerStates,
      playersWithHardware: totalHardware || 0,
      playersWithCosmetics: usersWithCosmetics,
      redisDbSize: dbSize,
      timestamp: new Date().toISOString()
    };
  } catch (e) {
    console.error('getDatabaseStats error:', e.message);
    return { totalPlayers: 0, error: e.message };
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

// Default patches CDN base URL (MEGA S4 mirror — used by Caddy redirect)
const DEFAULT_PATCHES_CDN_BASE_URL = 'https://s3.g.s4.mega.io/kcvismkrtfcalgwxzsazbq46l72dwsypqaham/hytale/patches';

// Default patches redirect URL (what launchers should use)
const DEFAULT_PATCHES_REDIRECT_URL = 'https://dl.vboro.de/patches';

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
 * Get patches CDN base URL
 */
async function getPatchesCdnBaseUrl() {
  const settings = await getSettings();
  return settings.patchesCdnBaseUrl || DEFAULT_PATCHES_CDN_BASE_URL;
}

/**
 * Set patches CDN base URL
 */
async function setPatchesCdnBaseUrl(url) {
  const settings = await getSettings();
  settings.patchesCdnBaseUrl = url;
  return await saveSettings(settings);
}

/**
 * Get patches redirect URL (for launchers to use)
 */
async function getPatchesRedirectUrl() {
  const settings = await getSettings();
  return settings.patchesRedirectUrl || DEFAULT_PATCHES_REDIRECT_URL;
}

/**
 * Set patches redirect URL
 */
async function setPatchesRedirectUrl(url) {
  const settings = await getSettings();
  settings.patchesRedirectUrl = url;
  return await saveSettings(settings);
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

// ============================================================================
// LOG SUBMISSIONS
// ============================================================================

/**
 * Save log submission metadata to Redis
 */
async function saveLogSubmission(id, metadata) {
  if (!isConnected()) return false;

  try {
    await redis.hset(`logsub:${id}`, ...Object.entries(metadata).flat());
    await redis.zadd('logsub:list', Date.now(), id);
    return true;
  } catch (e) {
    console.error('Failed to save log submission:', e.message);
    return false;
  }
}

/**
 * Get log submission metadata
 */
async function getLogSubmission(id) {
  if (!isConnected()) return null;

  try {
    // Support lookup by short ID (first 8 chars)
    let fullId = id;
    if (id.length === 8) {
      // Scan sorted set for matching prefix
      const allIds = await redis.zrevrange('logsub:list', 0, -1);
      fullId = allIds.find(i => i.startsWith(id));
      if (!fullId) return null;
    }

    const data = await redis.hgetall(`logsub:${fullId}`);
    if (!data || !data.id) return null;
    return data;
  } catch (e) {
    console.error('Failed to get log submission:', e.message);
    return null;
  }
}

/**
 * List log submissions with pagination and optional search
 */
async function listLogSubmissions(page = 1, limit = 20, search = '') {
  if (!isConnected()) return { submissions: [], total: 0, page, limit };

  try {
    const allIds = await redis.zrevrange('logsub:list', 0, -1);
    let submissions = [];

    for (const id of allIds) {
      const data = await redis.hgetall(`logsub:${id}`);
      if (!data || !data.id) continue;

      if (search) {
        const q = search.toLowerCase();
        const matchesId = id.toLowerCase().startsWith(q);
        const matchesUser = (data.username || '').toLowerCase().includes(q);
        if (!matchesId && !matchesUser) continue;
      }

      submissions.push(data);
    }

    const total = submissions.length;
    const offset = (page - 1) * limit;
    const paged = submissions.slice(offset, offset + limit);

    return { submissions: paged, total, page, limit, totalPages: Math.ceil(total / limit) };
  } catch (e) {
    console.error('Failed to list log submissions:', e.message);
    return { submissions: [], total: 0, page, limit };
  }
}

/**
 * Delete log submission from Redis
 */
async function deleteLogSubmission(id) {
  if (!isConnected()) return false;

  try {
    await redis.del(`logsub:${id}`);
    await redis.zrem('logsub:list', id);
    return true;
  } catch (e) {
    console.error('Failed to delete log submission:', e.message);
    return false;
  }
}

/**
 * Check rate limit for log submissions (5/hour/IP)
 * Returns true if allowed, false if rate limited
 */
async function checkLogRateLimit(ip) {
  if (!isConnected()) return true; // Allow if Redis is down

  try {
    const key = `logsub:rate:${ip}`;
    const count = await redis.incr(key);
    if (count === 1) {
      await redis.expire(key, 3600); // 1 hour TTL
    }
    return count <= 5;
  } catch (e) {
    return true; // Allow on error
  }
}

/**
 * Get expired log submission IDs (older than cutoff timestamp)
 */
async function getExpiredLogSubmissions(cutoffTimestamp) {
  if (!isConnected()) return [];

  try {
    return await redis.zrangebyscore('logsub:list', 0, cutoffTimestamp);
  } catch (e) {
    console.error('Failed to get expired log submissions:', e.message);
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

  // Player skins (atomic operations)
  getPlayerSkins,
  atomicAddPlayerSkin,
  atomicUpdatePlayerSkin,
  atomicSetActiveSkin,
  atomicDeletePlayerSkin,

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
  getActivityWindows,
  getDatabaseStats,

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

  // Patches CDN
  getPatchesCdnBaseUrl,
  setPatchesCdnBaseUrl,
  DEFAULT_PATCHES_CDN_BASE_URL,

  // Patches redirect (for launchers)
  getPatchesRedirectUrl,
  setPatchesRedirectUrl,
  DEFAULT_PATCHES_REDIRECT_URL,

  // Log submissions
  saveLogSubmission,
  getLogSubmission,
  listLogSubmissions,
  deleteLogSubmission,
  checkLogRateLimit,
  getExpiredLogSubmissions,
};
