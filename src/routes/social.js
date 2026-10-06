const s = require('../services/social');
const socialMetrics = require('../services/socialMetrics');
const invites = require('../services/socialInvites');
const auth = require('../services/auth');
const storage = require('../services/storage');
const { createHmac } = require('crypto');
const { readFileSync } = require('fs');
const { sendJson, sendNoContent } = require('../utils/response');

async function handleSocialRoutes(req, res, path, body) {
  if (!/^\/(friends|friend-requests|blocks|presence|party|world-invites)(\/|$)/.test(path) && path !== '/profile/uuids' && path !== '/turn-credentials') return false;
  const token = /^Bearer (.+)$/.exec(req.headers.authorization || '')?.[1];
  const claims = auth.verifyToken(token);
  if (!claims || !s.validId(claims.uuid) || !Number.isFinite(claims.exp) || claims.exp <= Date.now() / 1000 || claims.scope !== 'hytale:server') {
    if (req.method === 'POST' && path === '/presence/join-world') await socialMetrics.record('worldJoinHttpDenied');
    sendJson(res, 401, { errorCode: 'unauthorized', message: 'Valid session token required' }); return true;
  }
  const id = claims.uuid.toLowerCase(), method = req.method;
  try {
    if (!body || typeof body !== 'object' || Array.isArray(body)) s.fail(400, 'JSON object required');
    let result;
    if (method === 'GET') {
      const u = await s.user(id);
      if (path === '/friends') result = { friends: await s.friends(id), truncated: false };
      else if (path === '/presence/friends') result = { friends: await s.friends(id, true) };
      else if (path === '/presence/settings') result = u.settings;
      else if (path === '/friends/favorites') result = { favorites: u.favorites };
      else if (path === '/blocks') result = { blocks: Object.entries(u.blocks).map(([blockedUuid, createdAt]) => ({ blockedUuid, createdAt })), truncated: false };
      else if (path === '/friend-requests/incoming') result = { requests: Object.values(u.incoming), truncated: false };
      else if (path === '/friend-requests/outgoing') result = { requests: Object.values(u.outgoing), truncated: false };
      else if (path === '/party') result = await invites.getParty(id);
      else if (/^\/party\/invites(\/sent)?$/.test(path)) result = { invites: await invites.list(id, 'party', path.endsWith('/sent')) };
      else if (/^\/world-invites(\/sent)?$/.test(path)) result = { invites: await invites.list(id, 'world', path.endsWith('/sent')) };
      else s.fail(404, 'Unknown social endpoint');
    } else if (method === 'POST' || method === 'PUT') {
      const route = `${method} ${path}`;
      const relation = { 'POST /friend-requests': ['request', 'targetUuid'], 'POST /friend-requests/accept': ['accept', 'requesterUuid'], 'POST /friend-requests/reject': ['reject', 'requesterUuid'], 'POST /friends/remove': ['remove', 'friendUuid'], 'POST /blocks': ['block', 'targetUuid'], 'POST /blocks/remove': ['unblock', 'blockedUuid'] }[route];
      if (relation) await s.relationship(id, body[relation[1]], relation[0]);
      else if (route === 'POST /friend-requests/by-username') await s.relationship(id, await s.resolveName(body.targetUsername), 'request');
      else if (route === 'PUT /friends/favorites') {
        if (!Array.isArray(body.favorites) || body.favorites.length > 500 || !body.favorites.every(s.validId)) s.fail(400, 'Invalid favorites');
        await s.change([id], r => { r[id].favorites = [...new Set(body.favorites.map(x => x.toLowerCase()))].filter(x => r[id].friends[x]); });
      } else if (route === 'PUT /presence/settings') await s.presence(id, 'settings', body);
      else if (route === 'PUT /presence/appear-offline') await s.presence(id, 'offline', body);
      else if (route === 'POST /presence/heartbeat') await s.presence(id, 'heartbeat', body);
      else if (route === 'POST /presence/clear') await s.presence(id, 'clear', body);
      else if (route === 'POST /presence/join-world') result = await s.join(id, body.targetUuid);
      else if (route === 'POST /party/create') result = await invites.createParty(id, body.maxSize);
      else if (route === 'POST /party/leave') await invites.leave(id);
      else if (route === 'POST /party/invites/send') result = await invites.send(id, body, 'party');
      else if (route === 'POST /world-invites') result = await invites.send(id, body, 'world');
      else if (method === 'POST' && /^\/(party\/invites|world-invites)\/(accept|reject|cancel)$/.test(path)) result = await invites.respond(id, body.inviteUuid, path.split('/').pop(), path.startsWith('/party') ? 'party' : 'world');
      else if (route === 'POST /profile/uuids') {
        if (!Array.isArray(body.uuids) || body.uuids.length > 100 || !body.uuids.every(s.validId)) s.fail(400, 'Invalid uuids');
        const profiles = await Promise.all([...new Set(body.uuids.map(x => x.toLowerCase()))].map(async uuid => {
          const [username, data] = await Promise.all([storage.getUsername(uuid), storage.getUserData(uuid)]);
          return username ? { uuid, username, skin: data.skin ? (typeof data.skin === 'string' ? data.skin : JSON.stringify(data.skin)) : null } : null;
        }));
        result = { profiles: profiles.filter(Boolean) };
      } else if (route === 'POST /turn-credentials') {
        const secret = process.env.TURN_SECRET || (process.env.TURN_SECRET_FILE ? readFileSync(process.env.TURN_SECRET_FILE, 'utf8').trim() : ''), urls = (process.env.TURN_URLS || '').split(',').filter(Boolean);
        if (!secret || !urls.length) s.fail(503, 'TURN is not configured');
        const exp = Math.floor(Date.now() / 1000) + 3600, username = `${exp}:${id}`;
        result = { iceServers: [{ urls, username, credential: createHmac('sha1', secret).update(username).digest('base64') }], expiresAt: new Date(exp * 1000).toISOString() };
      } else s.fail(501, 'Social operation not implemented');
    } else s.fail(405, 'Method not allowed');
    if (method === 'POST' && path === '/presence/join-world') await socialMetrics.record('worldJoinHttpSuccess');
    if (result === undefined) sendNoContent(res); else sendJson(res, 200, result);
  } catch (error) {
    if (method === 'POST' && path === '/presence/join-world') await socialMetrics.record(error.status && error.status < 500 ? 'worldJoinHttpDenied' : 'worldJoinHttpErrors');
    if (!error.status) console.error('Social request failed:', error.message);
    sendJson(res, error.status || 503, { errorCode: String(error.status || 503), message: error.status ? error.message : 'Social service unavailable' });
  }
  return true;
}
module.exports = { handleSocialRoutes, DEFAULT_PRESENCE_SETTINGS: s.DEFAULT_SETTINGS };
