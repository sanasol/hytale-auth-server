const crypto = require('crypto');
const storage = require('../services/storage');
const assets = require('../services/assets');
const passwordService = require('../services/password');
const { sendJson, sendNoContent } = require('../utils/response');

/**
 * Profile endpoint
 */
function handleProfile(req, res, body, uuid, name) {
  sendJson(res, 200, {
    success: true,
    uuid, name,
    display_name: name,
    premium: true,
    created_at: '2024-01-01T00:00:00Z',
    settings: { language: 'en', notifications: true },
    stats: { playtime: 0, worlds_created: 0 }
  });
}

/**
 * Profile lookup by UUID - used by ProfileServiceClient.getProfileByUuid()
 */
async function handleProfileLookupByUuid(req, res, lookupUuid, headers) {
  const { extractServerAudienceFromHeaders } = require('../services/auth');
  const serverAudience = extractServerAudienceFromHeaders(headers);
  console.log('Profile lookup by UUID:', lookupUuid, serverAudience ? `(server: ${serverAudience})` : '(no server context)');

  let username = null;

  // First, check if player is active on this server (most accurate)
  if (serverAudience) {
    const players = await storage.getPlayersOnServer(serverAudience);
    const activePlayer = players.find(p => p.uuid === lookupUuid);
    if (activePlayer) {
      username = activePlayer.username;
      console.log(`Found active player on server: ${username}`);
    }
  }

  // Check in-memory cache first
  if (!username) {
    username = storage.getCachedUsername(lookupUuid);
  }

  // Check Redis for persisted username
  if (!username) {
    username = await storage.getUsername(lookupUuid);
  }

  // If not found, return a generic name based on UUID
  if (!username) {
    username = `Player_${lookupUuid.substring(0, 8)}`;
    console.log(`UUID ${lookupUuid} not found in records, returning generic name`);
  }

  sendJson(res, 200, {
    uuid: lookupUuid,
    username: username
  });
}

/**
 * Profile lookup by username - used by ProfileServiceClient.getProfileByUsername()
 */
async function handleProfileLookupByUsername(req, res, lookupUsername, headers) {
  const { extractServerAudienceFromHeaders } = require('../services/auth');
  const serverAudience = extractServerAudienceFromHeaders(headers);
  console.log('Profile lookup by username:', lookupUsername, serverAudience ? `(server: ${serverAudience})` : '(no server context)');

  // PRIORITY 1: Check active players on this specific server
  if (serverAudience) {
    const serverMatches = await storage.findPlayerOnServer(serverAudience, lookupUsername);
    if (serverMatches.length === 1) {
      console.log(`Found unique player "${lookupUsername}" on server ${serverAudience}: ${serverMatches[0].uuid}`);
      sendJson(res, 200, {
        uuid: serverMatches[0].uuid,
        username: serverMatches[0].username
      });
      return;
    } else if (serverMatches.length > 1) {
      console.log(`Multiple players with username "${lookupUsername}" on server ${serverAudience}, returning first: ${serverMatches[0].uuid}`);
      sendJson(res, 200, {
        uuid: serverMatches[0].uuid,
        username: serverMatches[0].username
      });
      return;
    }
    console.log(`Player "${lookupUsername}" not found on server ${serverAudience}, searching globally`);
  }

  // PRIORITY 2: Global search
  const matches = [];

  // Get all active sessions and search
  const { sessions } = await storage.getAllActiveSessions();
  for (const session of sessions) {
    if (session.username && session.username.toLowerCase() === lookupUsername.toLowerCase()) {
      if (!matches.find(m => m.uuid === session.uuid)) {
        matches.push({
          uuid: session.uuid,
          username: session.username,
          lastSeen: session.createdAt || new Date().toISOString()
        });
      }
    }
  }

  if (matches.length === 0) {
    console.log('Username not found:', lookupUsername);
    sendJson(res, 404, {
      error: 'Profile not found',
      message: `No profile found for username: ${lookupUsername}`
    });
    return;
  }

  if (matches.length > 1) {
    console.log(`Multiple players with username "${lookupUsername}" globally: ${matches.map(m => m.uuid).join(', ')}`);
    matches.sort((a, b) => new Date(b.lastSeen) - new Date(a.lastSeen));
  }

  const bestMatch = matches[0];
  console.log(`Returning profile for "${lookupUsername}": ${bestMatch.uuid} (${matches.length} total global matches)`);

  sendJson(res, 200, {
    uuid: bestMatch.uuid,
    username: bestMatch.username
  });
}

/**
 * Game profile endpoint
 */
async function handleGameProfile(req, res, body, uuid, name) {
  const nextNameChange = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000).toISOString();

  let skin = null;
  const userDataObj = await storage.getUserData(uuid);
  if (userDataObj && userDataObj.skin) {
    skin = JSON.stringify(userDataObj.skin);
  }

  // Set no-cache headers to prevent caching of user profile/skin data
  res.setHeader('Cache-Control', 'no-store, no-cache, must-revalidate');
  res.setHeader('Pragma', 'no-cache');
  res.setHeader('Expires', '0');

  const hasPass = await passwordService.hasPassword(uuid);

  sendJson(res, 200, {
    uuid, username: name,
    entitlements: ["game.base"],
    createdAt: "2024-01-01T00:00:00Z",
    nextNameChangeAt: nextNameChange,
    skin: skin,
    password_protected: hasPass
  });
}

/**
 * Skin update endpoint
 *
 * IMPORTANT: This merges new skin data with existing data to prevent
 * accidental overwrites when partial updates are sent.
 * Required fields: face, ears, mouth, bodyCharacteristic, underwear, eyes
 */
async function handleSkin(req, res, body, uuid, name, invalidateHeadCache) {
  console.log('skin update:', uuid, 'fields:', Object.keys(body).join(', '));
  console.log('skin update body sample:', uuid, 'haircut:', body.haircut, 'bodyType:', body.bodyCharacteristic);

  // Use atomic update to prevent race conditions with multiple workers
  const savedData = await storage.atomicUpdateSkin(uuid, body);

  // Log the result
  console.log('skin saved atomically:', uuid, 'haircut:', savedData?.skin?.haircut);

  // Invalidate head image cache since skin changed
  if (invalidateHeadCache) {
    invalidateHeadCache(uuid);
  }

  sendNoContent(res);
}

/**
 * Launcher data endpoint
 */
function handleLauncherData(req, res, body, uuid, name) {
  sendJson(res, 200, {
    EulaAcceptedAt: "2024-01-01T00:00:00Z",
    Owner: uuid,
    Patchlines: {
      PreRelease: { BuildVersion: "1.0.0", Newest: 1 },
      Release: { BuildVersion: "1.0.0", Newest: 1 }
    },
    Profiles: [{
      UUID: uuid,
      Username: name,
      Entitlements: ["game.base"]
    }]
  });
}

/**
 * Get profiles endpoint
 */
function handleGetProfiles(req, res, body, uuid, name) {
  console.log('get-profiles:', uuid, name);

  sendJson(res, 200, {
    profiles: [{
      uuid: uuid,
      username: name,
      entitlements: ["game.base"]
    }]
  });
}

/**
 * Player skins GET endpoint - returns list of player's saved avatar profiles
 * Used by pre-release multi-avatar feature
 */
async function handlePlayerSkinsGet(req, res, body, uuid, name) {
  console.log('player-skins GET:', uuid, name);

  // Use atomic read to get consistent data
  const { playerSkins, activeSkin: storedActiveSkin } = await storage.getPlayerSkins(uuid);

  // Get active skin - default to first skin if not set
  let activeSkin = storedActiveSkin;
  if (!activeSkin && playerSkins.length > 0) {
    activeSkin = playerSkins[0].id;
  }

  console.log('player-skins GET: returning', playerSkins.length, 'skins, activeSkin:', activeSkin);

  // F2P: Allow up to 10 avatar profiles (more generous than official 5)
  sendJson(res, 200, {
    activeSkin: activeSkin || null,
    maxSkins: 10,
    skins: playerSkins
  });
}

/**
 * Player skins POST endpoint - creates a new avatar profile
 * Returns NewPlayerSkinResponse with skinId
 */
async function handlePlayerSkinsPost(req, res, body, uuid, name, invalidateHeadCache) {
  console.log('player-skins POST:', uuid, body.name || name, 'body keys:', Object.keys(body || {}).join(', '));

  // Generate a new unique skin ID
  const skinId = crypto.randomUUID();

  // Extract skin data and name from request
  const skinName = body.name || 'Default Avatar';
  const skinData = body.skinData || '';

  // Create new skin object
  const newSkin = {
    id: skinId,
    name: skinName,
    skinData: skinData,
    createdAt: new Date().toISOString()
  };

  // Use atomic operation to add skin (prevents race conditions)
  const result = await storage.atomicAddPlayerSkin(uuid, newSkin);

  if (!result) {
    console.error('player-skins POST: failed to add skin for', uuid);
    sendJson(res, 500, { error: 'Failed to create skin' });
    return;
  }

  // Invalidate head cache since skin changed
  if (invalidateHeadCache) {
    invalidateHeadCache(uuid);
  }

  console.log('player-skins POST: created new skin for', uuid, 'skinId:', skinId, 'name:', skinName, 'total:', result.playerSkins?.length);

  // Return 201 Created with skinId (matches official Hytale API)
  sendJson(res, 201, {
    skinId: skinId
  });
}

/**
 * Player skins PUT /active endpoint - select active skin
 * Request body: { skinId: "uuid" }
 */
async function handlePlayerSkinsSetActive(req, res, body, uuid) {
  const skinId = body.skinId;
  console.log('player-skins PUT active:', uuid, 'skinId:', skinId);

  // Use atomic operation to set active skin (prevents race conditions)
  const result = await storage.atomicSetActiveSkin(uuid, skinId);

  if (!result) {
    console.error('player-skins PUT active: failed to set active skin for', uuid);
  }

  sendNoContent(res);
}

/**
 * Player skins PUT /{skinId} endpoint - update a specific skin
 */
async function handlePlayerSkinsUpdate(req, res, body, uuid, skinId, invalidateHeadCache) {
  console.log('player-skins PUT update:', uuid, 'skinId:', skinId, 'body keys:', Object.keys(body || {}).join(', '));

  // Build updates object
  const updates = {};
  if (body.name !== undefined) updates.name = body.name;
  if (body.skinData !== undefined) updates.skinData = body.skinData;

  // Use atomic operation to update skin (prevents race conditions)
  const result = await storage.atomicUpdatePlayerSkin(uuid, skinId, updates);

  if (!result) {
    console.error('player-skins PUT update: failed to update skin for', uuid);
  }

  // Invalidate head cache since skin changed
  if (invalidateHeadCache) {
    invalidateHeadCache(uuid);
  }

  sendNoContent(res);
}

/**
 * Player skins DELETE /{skinId} endpoint - delete a specific skin
 * Returns 204 No Content
 */
async function handlePlayerSkinsDelete(req, res, uuid, skinId, invalidateHeadCache) {
  console.log('player-skins DELETE:', uuid, 'skinId:', skinId);

  // Use atomic operation to delete skin (prevents race conditions)
  const result = await storage.atomicDeletePlayerSkin(uuid, skinId);

  if (result?.deleted) {
    console.log('player-skins DELETE: removed skin', skinId, 'for', uuid);

    // Invalidate head cache since skin changed
    if (invalidateHeadCache) {
      invalidateHeadCache(uuid);
    }
  } else {
    console.log('player-skins DELETE: skin not found', skinId, 'for', uuid);
  }

  sendNoContent(res);
}

/**
 * Cosmetics endpoint
 */
function handleCosmetics(req, res, body, uuid, name) {
  const assetsCosmetics = assets.loadCosmeticsFromAssets();

  if (assetsCosmetics && Object.keys(assetsCosmetics).length > 0) {
    console.log('Returning cosmetics from Assets.zip');
    sendJson(res, 200, assetsCosmetics);
    return;
  }

  console.log('Using fallback cosmetics');
  sendJson(res, 200, assets.getFallbackCosmetics());
}

module.exports = {
  handleProfile,
  handleProfileLookupByUuid,
  handleProfileLookupByUsername,
  handleGameProfile,
  handleSkin,
  handlePlayerSkinsGet,
  handlePlayerSkinsPost,
  handlePlayerSkinsSetActive,
  handlePlayerSkinsUpdate,
  handlePlayerSkinsDelete,
  handleLauncherData,
  handleGetProfiles,
  handleCosmetics,
};
