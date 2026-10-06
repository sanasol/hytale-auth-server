const { randomUUID } = require('crypto');
const { redis } = require('./redis');
const storage = require('./storage');
const config = require('../config');
const PREFIX = 'social:v1:';
const CHANNEL = `${PREFIX}events`;
const EMPTY_UUID = '00000000-0000-0000-0000-000000000000';
const DEFAULT_SETTINGS = { allowFriendRequests: true, allowInvites: 0, allowJoin: true, showActivity: 0, showLocation: 0, showOnline: 0 };
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const empty = () => ({ friends: {}, incoming: {}, outgoing: {}, blocks: {}, favorites: [], invites: {}, sent: {}, settings: { ...DEFAULT_SETTINGS } });
const key = id => `${PREFIX}user:${id}`;
const fail = (status, message) => { throw Object.assign(new Error(message), { status }); };
const validId = value => typeof value === 'string' && UUID.test(value);
const requireId = value => { if (!validId(value)) fail(400, 'Invalid UUID'); return value.toLowerCase(); };
const blocked = (a, b, aid, bid) => !!(a.blocks[bid] || b.blocks[aid]);
const permits = (rule, friend) => rule === 0 || (rule === 1 && friend);
function decode(value, legacy) {
  const data = { ...empty(), ...JSON.parse(value || '{}') };
  if (!value && legacy) data.settings = { ...DEFAULT_SETTINGS, ...(JSON.parse(legacy).presenceSettings || {}) };
  return data;
}
async function user(id) { const [value, legacy] = await redis.mget(key(id), `${config.redisKeys.USER}${id}`); return decode(value, legacy); }

// A dedicated connection keeps WATCH isolated from other requests/workers.
async function change(ids, fn) {
  // WATCH belongs to one TCP connection; never continue it after reconnect.
  const db = redis.duplicate({ lazyConnect: true, retryStrategy: null, enableOfflineQueue: false, autoResendUnfulfilledCommands: false });
  db.on('error', () => {});
  ids = [...new Set(ids)];
  try {
    await db.connect();
    for (let attempt = 0; attempt < 8; attempt++) {
      await db.watch(...ids.map(key));
      const values = await db.mget(...ids.map(key));
      const legacy = await db.mget(...ids.map(id => `${config.redisKeys.USER}${id}`));
      const records = Object.fromEntries(ids.map((id, i) => [id, decode(values[i], legacy[i])]));
      const events = [];
      const emit = (recipient, type, data) => events.push({ recipient, type, data });
      const result = fn(records, emit);
      // Reconnect has no resume cursor. Keep the latest relationship change per peer.
      for (const event of events.filter(e => e.type.startsWith('friend.'))) {
        const record = records[event.recipient], peer = event.data.player_uuid || event.data.requester_uuid;
        if (!record || !peer) continue;
        record.friendChanges ||= {};
        delete record.friendChanges[peer];
        record.friendChanges[peer] = event;
        // ponytail: last 1000 peer changes; older removals require a client restart.
        while (Object.keys(record.friendChanges).length > 1000) delete record.friendChanges[Object.keys(record.friendChanges)[0]];
      }
      const tx = db.multi();
      for (const id of ids) tx.set(key(id), JSON.stringify(records[id]));
      // State and publication commit together; gateway replays relationship changes.
      for (const event of events) tx.publish(CHANNEL, JSON.stringify(event));
      const committed = await tx.exec();
      if (committed) {
        if (committed.some(([error]) => error)) throw new Error('Social transaction failed');
        return result;
      }
    }
    fail(409, 'Concurrent update; retry');
  } catch (error) {
    if (error.status) throw error;
    // EXEC may have committed before its reply was lost. Do not replay it here.
    fail(503, 'Social storage unavailable; refresh state before retrying');
  } finally { db.disconnect(); }
}
async function resolveName(name) {
  if (typeof name !== 'string' || !name.trim() || name.length > 32 || /[\x00-\x1f\x7f]/.test(name)) fail(400, 'Invalid username');
  const indexed = await redis.smembers(`${PREFIX}names:${name.toLowerCase()}`);
  const current = [];
  for (const id of indexed) if ((await storage.getUsername(id))?.toLowerCase() === name.toLowerCase()) current.push(id);
  if (current.length === 1) return current[0];
  if (current.length > 1) fail(409, 'Username is ambiguous');
  const reserved = await redis.get(`${config.redisKeys.USERNAME_RESERVED}${name.toLowerCase()}`);
  if (reserved) {
    let id = reserved;
    try { const v = JSON.parse(reserved); id = v.uuid || v; } catch {}
    if (validId(id) && (await storage.getUsername(id))?.toLowerCase() === name.toLowerCase()) return id;
  }
  if (await redis.get(`${PREFIX}names-ready`)) fail(404, 'Player not found');
  // Bootstrap fallback only; run scripts/index-social-usernames.js after deployment.
  const deadline = Date.now() + 3000;
  let cursor = '0'; const matches = new Set();
  do {
    if (Date.now() > deadline) fail(503, 'Username index is rebuilding; retry shortly');
    const [next, keys] = await redis.scan(cursor, 'MATCH', `${config.redisKeys.USERNAME}*`, 'COUNT', 500);
    cursor = next;
    if (keys.length) {
      const names = await redis.mget(...keys);
      names.forEach((value, i) => { const id = keys[i].slice(config.redisKeys.USERNAME.length); if (value?.toLowerCase() === name.toLowerCase() && validId(id)) matches.add(id); });
    }
  } while (cursor !== '0');
  if (matches.size !== 1) fail(matches.size ? 409 : 404, matches.size ? 'Username is ambiguous' : 'Player not found');
  await redis.sadd(`${PREFIX}names:${name.toLowerCase()}`, ...matches);
  return [...matches][0];
}
async function relationship(id, target, action) {
  target = requireId(target);
  if (target === id) fail(400, 'Cannot target yourself');
  if (!await storage.getUsername(target)) fail(404, 'Player not found');
  return change([id, target], (r, emit) => {
    const a = r[id], b = r[target], now = new Date().toISOString();
    if (action === 'request') {
      if (blocked(a, b, id, target) || b.settings.allowFriendRequests === false) fail(403, 'Friend requests disabled');
      if (a.friends[target] || a.outgoing[target]) return;
      if (Object.keys(a.outgoing).length >= 200 || Object.keys(b.incoming).length >= 200) fail(429, 'Too many requests');
      const request = { requesterUuid: id, targetUuid: target, createdAt: now };
      a.outgoing[target] = b.incoming[id] = request;
      emit(target, 'friend.request.received', { requester_uuid: id, created_at: now });
    } else if (action === 'accept') {
      if (a.friends[target]) return;
      if (!a.incoming[target] || blocked(a, b, id, target)) fail(404, 'Request not found');
      if (Object.keys(a.friends).length >= 500 || Object.keys(b.friends).length >= 500) fail(409, 'Friend limit reached');
      a.friends[target] = b.friends[id] = now;
      delete a.incoming[target]; delete b.outgoing[id]; delete a.outgoing[target]; delete b.incoming[id];
      emit(target, 'friend.request.accepted', { player_uuid: id, accepted_at: now });
      emit(id, 'friend.request.accepted', { player_uuid: target, accepted_at: now });
    } else if (action === 'reject') {
      delete a.incoming[target]; delete b.outgoing[id];
      emit(target, 'friend.request.rejected', { player_uuid: id });
    } else if (action === 'unblock') delete a.blocks[target];
    else {
      delete a.friends[target]; delete b.friends[id];
      delete a.incoming[target]; delete b.outgoing[id]; delete a.outgoing[target]; delete b.incoming[id];
      a.favorites = a.favorites.filter(x => x !== target); b.favorites = b.favorites.filter(x => x !== id);
      for (const [owner, other] of [[a, target], [b, id]]) {
        for (const field of ['invites', 'sent']) for (const [k, inv] of Object.entries(owner[field])) {
          if (inv.inviterUuid === other || inv.invitedPlayerUuid === other) delete owner[field][k];
        }
      }
      if (action === 'block') a.blocks[target] = now;
      emit(target, action === 'block' ? 'friend.blocked' : 'friend.unfriended', { player_uuid: id });
      emit(id, 'friend.unfriended', { player_uuid: target });
    }
  });
}
function projection(owner, viewer, ownerId, viewerId) {
  const p = owner.presence || {}, friend = !!owner.friends[viewerId];
  const online = !blocked(owner, viewer, ownerId, viewerId) && !owner.appearOffline && p.status === 'online' && p.updatedAt > Date.now() - 300000 && permits(owner.settings.showOnline, friend);
  const location = online && permits(owner.settings.showLocation, friend);
  return { uuid: ownerId, status: online ? 'online' : 'offline', activity: online && permits(owner.settings.showActivity, friend) ? p.activity : null,
    serverName: location ? p.serverName || null : null, worldName: location ? p.worldName || null : null, gameMode: location ? p.gameMode || null : null,
    canJoin: !!(location && owner.settings.allowJoin && (p.inviteCode || (p.serverHost && p.serverPort))), isWorldOwner: !!(location && p.inviteCode) };
}
async function notifyPresence(id) {
  const owner = await user(id);
  for (const viewerId of Object.keys(owner.friends)) {
    const p = projection(owner, await user(viewerId), id, viewerId);
    await redis.publish(CHANNEL, JSON.stringify({ recipient: viewerId, type: 'friend.presence.updated', data: {
      player_uuid: id, status: p.status, activity: p.activity, game_mode: p.gameMode,
      server_uuid: p.status === 'online' && p.worldName ? owner.presence?.serverUuid || null : null,
      server_name: p.serverName, world_name: p.worldName, can_join: p.canJoin, is_world_owner: p.isWorldOwner,
    } }));
  }
}
async function presence(id, action, body) {
  await change([id], r => {
    const a = r[id];
    if (action === 'settings') {
      for (const [k, v] of Object.entries(body)) {
        if (!(k in DEFAULT_SETTINGS)) continue;
        if (typeof DEFAULT_SETTINGS[k] === 'boolean' ? typeof v !== 'boolean' : ![0, 1, 2].includes(v)) fail(400, `Invalid ${k}`);
        a.settings[k] = v;
      }
    } else if (action === 'offline') {
      if (typeof body.appearOffline !== 'boolean') fail(400, 'Invalid appearOffline');
      a.appearOffline = body.appearOffline; a.settings.showOnline = body.appearOffline ? 2 : 1;
    } else if (action === 'clear') a.presence = { status: 'offline', updatedAt: Date.now() };
    else {
      if (!['online', 'offline'].includes(body.status) || !['menus', 'playing'].includes(body.activity)) fail(400, 'Invalid presence');
      const p = { status: body.status, activity: body.activity, updatedAt: Date.now() };
      for (const field of ['serverUuid', 'serverName', 'worldName', 'gameMode', 'serverHost', 'inviteCode']) {
        if (body[field] != null) {
          if (typeof body[field] !== 'string' || body[field].length > (field === 'inviteCode' ? 16384 : 256)) fail(400, `Invalid ${field}`);
          if (field === 'serverUuid' && !validId(body[field])) fail(400, 'Invalid serverUuid');
          p[field] = body[field];
        }
      }
      if (body.serverPort != null) { if (!Number.isInteger(body.serverPort) || body.serverPort < 1 || body.serverPort > 65535) fail(400, 'Invalid serverPort'); p.serverPort = body.serverPort; }
      a.presence = p;
    }
  });
  if (action === 'heartbeat') await redis.zadd(`${PREFIX}expiry`, Date.now() + 300000, id);
  await notifyPresence(id);
}
async function friends(id, presenceOnly = false) {
  const a = await user(id);
  return Promise.all(Object.entries(a.friends).map(async ([other, since]) => {
    const p = projection(await user(other), a, other, id);
    return presenceOnly ? p : { ...p, since, isOnline: p.status === 'online', discordUserId: null, isFavorite: a.favorites.includes(other) };
  }));
}
async function join(id, target) {
  target = requireId(target);
  const [a, b] = await Promise.all([user(id), user(target)]);
  if (!b.friends[id] || !projection(b, a, target, id).canJoin) fail(403, 'World is not joinable');
  return joinData(b.presence);
}
function joinData(p) { return { serverUuid: p.serverUuid || EMPTY_UUID, host: p.serverHost || null, port: p.serverPort || 0, serverName: p.serverName || null, worldName: p.worldName || null, inviteCode: p.inviteCode || null }; }
module.exports = { redis, PREFIX, CHANNEL, EMPTY_UUID, DEFAULT_SETTINGS, validId, requireId, fail, key, user, change, resolveName, relationship, projection, notifyPresence, presence, friends, join, joinData, blocked, permits };

async function expirePresence() {
  const ids = await redis.zrangebyscore(`${PREFIX}expiry`, 0, Date.now(), 'LIMIT', 0, 100);
  for (const id of ids) {
    if (!await redis.zrem(`${PREFIX}expiry`, id)) continue;
    const u = await user(id), expires = (u.presence?.updatedAt || 0) + 300000;
    if (expires > Date.now()) await redis.zadd(`${PREFIX}expiry`, expires, id);
    else await notifyPresence(id);
  }
}
module.exports.expirePresence = expirePresence;
