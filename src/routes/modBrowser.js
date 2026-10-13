const { sendJson } = require('../utils/response');
const { createModBrowserService } = require('../services/modBrowser');
const service = createModBrowserService();

async function handleModBrowserRoutes(req, res, url, urlPath) {
  if (!/^\/api\/(?:categories|mods|creators)(?:\/|$)/.test(urlPath)) return false;
  if (req.method !== 'GET') {
    sendJson(res, 405, { error: 'Method not allowed' });
    return true;
  }
  try {
    sendJson(res, 200, await service.handle(decodeURIComponent(urlPath), url.searchParams), req);
  } catch (error) {
    const status = error instanceof URIError ? 400 : error.status || 502;
    sendJson(res, status, { error: error.status ? error.message : 'Mod browser request failed' });
  }
  return true;
}
module.exports = { handleModBrowserRoutes };
