const auth = require('../services/auth');
const { redis } = require('../services/redis');
const { sendJson } = require('../utils/response');
const fs = require('node:fs');
const pathModule = require('node:path');
const config = require('../config');
const sourceCatalog = require('./minigames-catalog.json');
const imageBase = `https://auth.${config.domain}/minigames/`;
const imageNames = new Set(sourceCatalog.flatMap(game => [game.iconUrl, ...game.screenshots.map(image => image.url)]).map(url => new URL(url).pathname.split('/').pop()));
const catalog = sourceCatalog.map(game => ({ ...game,
  iconUrl: imageBase + new URL(game.iconUrl).pathname.split('/').pop(),
  screenshots: game.screenshots.map(image => ({ ...image, url: imageBase + new URL(image.url).pathname.split('/').pop() })),
}));

async function handleMatchmakingRoutes(req, res, url, path) {
  if (path.startsWith('/minigames/')) {
    const name = path.slice('/minigames/'.length);
    if (!['GET', 'HEAD'].includes(req.method)) sendJson(res, 405, { errorCode: 'method_not_allowed' });
    else if (!imageNames.has(name)) sendJson(res, 404, { errorCode: 'not_found' });
    else {
      const bytes = await fs.promises.readFile(pathModule.join(__dirname, '../static/minigames', name));
      res.writeHead(200, { 'Content-Type': 'image/png', 'Content-Length': bytes.length, 'Cache-Control': 'public, max-age=31536000, immutable' });
      res.end(req.method === 'HEAD' ? undefined : bytes);
    }
    return true;
  }
  if (!['/game-types', '/rules/acceptance', '/tickets', '/tickets/current'].includes(path) && !/^\/tickets\/[^/]+$/.test(path)) return false;
  const claims = auth.verifyToken(/^Bearer (.+)$/.exec(req.headers.authorization || '')?.[1]);
  if (!claims || !/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(claims.uuid || '') || claims.scope !== 'hytale:server' || !Number.isFinite(claims.exp) || claims.exp <= Date.now() / 1000) {
    sendJson(res, 401, { errorCode: 'unauthorized', message: 'Valid session token required' });
    return true;
  }
  try {
    if (path === '/game-types' && req.method === 'GET') {
      const version = url.searchParams.get('clientVersion') || '';
      if (!/^\d{1,10}$/.test(version) || Number(version) > 0xffffffff) {
        sendJson(res, 400, { errorCode: 'invalid_client_version', message: 'clientVersion must be the unsigned decimal protocol version' });
      } else {
        // No minigame hosts are provisioned; never advertise a playable queue without a destination.
        sendJson(res, 200, catalog);
      }
    } else if (path === '/rules/acceptance' && ['GET', 'PUT'].includes(req.method)) {
      const key = `matchmaking:rules:${claims.uuid.toLowerCase()}`;
      if (req.method === 'PUT') await redis.set(key, new Date().toISOString(), 'NX');
      sendJson(res, 200, { acceptedAt: await redis.get(key) });
    } else if (path.startsWith('/tickets') && req.method === 'GET') {
      sendJson(res, 404, { errorCode: 'ticket_not_found', message: 'No active matchmaking ticket' });
    } else if (path === '/tickets' && req.method === 'POST') {
      sendJson(res, 503, { errorCode: 'game_unavailable', message: 'No minigame servers are configured' });
    } else {
      sendJson(res, 405, { errorCode: 'method_not_allowed' });
    }
  } catch (error) {
    console.error('[Matchmaking] Request failed:', error.message);
    sendJson(res, 503, { errorCode: 'service_unavailable' });
  }
  return true;
}
module.exports = { handleMatchmakingRoutes };
