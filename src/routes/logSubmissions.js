const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const config = require('../config');
const storage = require('../services/storage');
const telegram = require('../services/telegram');
const { sendJson, sendHtml } = require('../utils/response');
const requestLogger = require('../services/requestLogger');

/**
 * POST /logs/submit — public endpoint, rate limited
 * Accepts raw gzip body, stores to disk, metadata to Redis, notifies Telegram
 */
async function handleLogSubmit(req, res) {
  const clientIp = requestLogger.getClientIp(req);

  // Rate limit: 5/hour/IP
  const allowed = await storage.checkLogRateLimit(clientIp);
  if (!allowed) {
    sendJson(res, 429, { error: 'Rate limit exceeded. Max 5 submissions per hour.' });
    return;
  }

  // Read raw body
  const chunks = [];
  let totalSize = 0;
  const MAX_SIZE = 10 * 1024 * 1024; // 10MB max

  try {
    await new Promise((resolve, reject) => {
      req.on('data', (chunk) => {
        totalSize += chunk.length;
        if (totalSize > MAX_SIZE) {
          reject(new Error('Payload too large'));
          return;
        }
        chunks.push(chunk);
      });
      req.on('end', resolve);
      req.on('error', reject);
    });
  } catch (err) {
    sendJson(res, 413, { error: 'Payload too large. Max 10MB.' });
    return;
  }

  const body = Buffer.concat(chunks);
  if (body.length === 0) {
    sendJson(res, 400, { error: 'Empty body' });
    return;
  }

  // Generate submission ID
  const id = crypto.randomUUID();

  // Parse metadata from headers
  const username = req.headers['x-log-username'] || 'unknown';
  const platform = req.headers['x-log-platform'] || 'unknown';
  const version = req.headers['x-log-version'] || 'unknown';
  const fileCount = parseInt(req.headers['x-log-file-count']) || 0;
  const files = req.headers['x-log-files'] || '';

  // Save .zip file to disk
  const dir = config.logSubmissionsDir;
  if (!fs.existsSync(dir)) {
    fs.mkdirSync(dir, { recursive: true });
  }

  const filePath = path.join(dir, `${id}.zip`);
  fs.writeFileSync(filePath, body);

  // Store metadata in Redis
  const metadata = {
    id,
    username,
    platform,
    version,
    ip: clientIp,
    fileSize: body.length,
    fileCount,
    files,
    createdAt: new Date().toISOString()
  };

  await storage.saveLogSubmission(id, metadata);

  // Fire-and-forget Telegram notification
  telegram.sendLogNotification(metadata, filePath);

  // Fire-and-forget: trigger AI analysis
  if (config.logAnalyzerUrl) {
    const http = require('http');
    const payload = JSON.stringify({ id, filePath: `/app/data/log-submissions/${id}.zip`, metadata });
    const aUrl = new URL(config.logAnalyzerUrl + '/analyze');
    const aReq = http.request({
      hostname: aUrl.hostname, port: aUrl.port, path: aUrl.pathname, method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(payload) },
      timeout: 5000,
    });
    aReq.on('error', (err) => console.log(`[LogSubmit] Analyzer trigger failed: ${err.message}`));
    aReq.end(payload);
  }

  console.log(`[LogSubmit] ${id.substring(0, 8)} from ${username} (${platform} v${version}) ${body.length} bytes, ${fileCount} files`);

  sendJson(res, 200, {
    id: id.substring(0, 8),
    message: 'Logs submitted successfully. Share this ID with support.'
  });
}

/**
 * GET /admin/api/log-submissions — paginated list
 */
async function handleListLogSubmissions(req, res, url) {
  const page = parseInt(url.searchParams.get('page')) || 1;
  const limit = parseInt(url.searchParams.get('limit')) || 20;
  const search = url.searchParams.get('search') || '';

  const result = await storage.listLogSubmissions(page, limit, search);
  sendJson(res, 200, result);
}

/**
 * GET /admin/api/log-submissions/:id — single submission details
 */
async function handleGetLogSubmission(req, res, id) {
  const submission = await storage.getLogSubmission(id);
  if (!submission) {
    sendJson(res, 404, { error: 'Submission not found' });
    return;
  }
  sendJson(res, 200, submission);
}

/**
 * GET /admin/api/log-submissions/:id/download — raw .zip download
 */
async function handleDownloadLogSubmission(req, res, id) {
  const submission = await storage.getLogSubmission(id);
  if (!submission) {
    sendJson(res, 404, { error: 'Submission not found' });
    return;
  }

  const filePath = path.join(config.logSubmissionsDir, `${submission.id}.zip`);
  if (!fs.existsSync(filePath)) {
    sendJson(res, 404, { error: 'File not found on disk' });
    return;
  }

  const fileContent = fs.readFileSync(filePath);
  const filename = `logs-${(submission.username || 'unknown').replace(/[^a-zA-Z0-9_-]/g, '_')}-${submission.id.substring(0, 8)}.zip`;

  res.writeHead(200, {
    'Content-Type': 'application/zip',
    'Content-Disposition': `attachment; filename="${filename}"`,
    'Content-Length': fileContent.length
  });
  res.end(fileContent);
}

/**
 * DELETE /admin/api/log-submissions/:id — delete from disk + Redis
 */
async function handleDeleteLogSubmission(req, res, id) {
  const submission = await storage.getLogSubmission(id);
  if (!submission) {
    sendJson(res, 404, { error: 'Submission not found' });
    return;
  }

  // Delete file from disk
  const filePath = path.join(config.logSubmissionsDir, `${submission.id}.zip`);
  if (fs.existsSync(filePath)) {
    fs.unlinkSync(filePath);
  }

  // Delete from Redis
  await storage.deleteLogSubmission(submission.id);

  console.log(`[LogSubmit] Deleted submission ${submission.id.substring(0, 8)}`);
  sendJson(res, 200, { ok: true });
}

/**
 * Cleanup old submissions (called on startup)
 * Removes submissions older than 30 days
 */
async function cleanupOldSubmissions() {
  try {
    const thirtyDaysAgo = Date.now() - (30 * 24 * 60 * 60 * 1000);
    const expired = await storage.getExpiredLogSubmissions(thirtyDaysAgo);

    for (const id of expired) {
      const filePath = path.join(config.logSubmissionsDir, `${id}.zip`);
      if (fs.existsSync(filePath)) {
        fs.unlinkSync(filePath);
      }
      await storage.deleteLogSubmission(id);
    }

    if (expired.length > 0) {
      console.log(`[LogSubmit] Cleaned up ${expired.length} old submissions`);
    }
  } catch (err) {
    console.error('[LogSubmit] Cleanup error:', err.message);
  }
}

/**
 * GET /internal/log-submission/:id — internal (no auth) metadata endpoint
 * Only accessible on Docker internal network
 */
async function handleInternalGetLogSubmission(req, res, id) {
  const submission = await storage.getLogSubmission(id);
  if (!submission) {
    sendJson(res, 404, { error: 'Submission not found' });
    return;
  }
  sendJson(res, 200, submission);
}

/**
 * GET /admin/api/log-submissions/:id/analysis — get case analysis for a submission
 * Reads from shared /app/data/cases/ volume
 */
async function handleGetSubmissionAnalysis(req, res, submissionId) {
  try {
    const casesDir = path.join(config.dataDir, 'cases');
    const indexPath = path.join(casesDir, 'index.json');

    if (!fs.existsSync(indexPath)) {
      sendJson(res, 200, { found: false });
      return;
    }

    const index = JSON.parse(fs.readFileSync(indexPath, 'utf8'));
    // Match by submissionId or by short ID
    const shortId = submissionId.substring(0, 8);
    const entry = index.find(e =>
      e.submissionId === submissionId ||
      e.id === shortId
    );

    if (!entry) {
      sendJson(res, 200, { found: false });
      return;
    }

    const caseFilePath = path.join(casesDir, entry.file);
    const content = fs.existsSync(caseFilePath)
      ? fs.readFileSync(caseFilePath, 'utf8')
      : '';

    sendJson(res, 200, { found: true, ...entry, content });
  } catch (err) {
    sendJson(res, 500, { error: err.message });
  }
}

/**
 * GET /admin/api/cases — return full cases index
 */
async function handleListCases(req, res) {
  try {
    const casesDir = path.join(config.dataDir, 'cases');
    const indexPath = path.join(casesDir, 'index.json');

    if (!fs.existsSync(indexPath)) {
      sendJson(res, 200, { cases: [] });
      return;
    }

    const index = JSON.parse(fs.readFileSync(indexPath, 'utf8'));
    sendJson(res, 200, { cases: index });
  } catch (err) {
    sendJson(res, 500, { error: err.message });
  }
}

/**
 * GET /admin/api/cases/:id — return single case file content
 */
async function handleGetCase(req, res, caseId) {
  try {
    const casesDir = path.join(config.dataDir, 'cases');
    const indexPath = path.join(casesDir, 'index.json');

    if (!fs.existsSync(indexPath)) {
      sendJson(res, 404, { error: 'No cases' });
      return;
    }

    const index = JSON.parse(fs.readFileSync(indexPath, 'utf8'));
    const entry = index.find(e => e.id === caseId);
    if (!entry) {
      sendJson(res, 404, { error: 'Case not found' });
      return;
    }

    const caseFilePath = path.join(casesDir, entry.file);
    const content = fs.existsSync(caseFilePath)
      ? fs.readFileSync(caseFilePath, 'utf8')
      : '';

    sendJson(res, 200, { ...entry, content });
  } catch (err) {
    sendJson(res, 500, { error: err.message });
  }
}

/**
 * PATCH /admin/api/cases/:id — update case status/fields
 * Body: { status: 'open'|'fixed'|'wrong'|'closed', note: 'optional note' }
 */
async function handleUpdateCase(req, res, caseId, body) {
  try {
    const casesDir = path.join(config.dataDir, 'cases');
    const indexPath = path.join(casesDir, 'index.json');

    if (!fs.existsSync(indexPath)) {
      sendJson(res, 404, { error: 'No cases' });
      return;
    }

    let index = JSON.parse(fs.readFileSync(indexPath, 'utf8'));
    const entry = index.find(e => e.id === caseId);
    if (!entry) {
      sendJson(res, 404, { error: 'Case not found' });
      return;
    }

    // Update allowed fields
    const validStatuses = ['open', 'fixed', 'wrong', 'closed', 'launcher-fixed'];
    if (body.status && validStatuses.includes(body.status)) {
      entry.status = body.status;
    }
    if (body.note !== undefined) {
      entry.note = body.note;
    }

    fs.writeFileSync(indexPath, JSON.stringify(index, null, 2));

    // Append status note to case file if provided
    if (body.note) {
      const caseFilePath = path.join(casesDir, entry.file);
      if (fs.existsSync(caseFilePath)) {
        const timestamp = new Date().toISOString();
        const statusNote = `\n\n## Status Update — ${timestamp}\n\n**Status**: ${entry.status || 'open'}\n**Note**: ${body.note}\n`;
        fs.appendFileSync(caseFilePath, statusNote);
      }
    }

    sendJson(res, 200, { ok: true, ...entry });
  } catch (err) {
    sendJson(res, 500, { error: err.message });
  }
}

/**
 * DELETE /admin/api/cases/:id — delete case file and remove from index
 */
async function handleDeleteCase(req, res, caseId) {
  try {
    const casesDir = path.join(config.dataDir, 'cases');
    const indexPath = path.join(casesDir, 'index.json');

    if (!fs.existsSync(indexPath)) {
      sendJson(res, 404, { error: 'No cases' });
      return;
    }

    let index = JSON.parse(fs.readFileSync(indexPath, 'utf8'));
    const entryIdx = index.findIndex(e => e.id === caseId);
    if (entryIdx === -1) {
      sendJson(res, 404, { error: 'Case not found' });
      return;
    }

    const entry = index[entryIdx];
    const caseFilePath = path.join(casesDir, entry.file);
    if (fs.existsSync(caseFilePath)) fs.unlinkSync(caseFilePath);

    index.splice(entryIdx, 1);
    fs.writeFileSync(indexPath, JSON.stringify(index, null, 2));

    sendJson(res, 200, { ok: true });
  } catch (err) {
    sendJson(res, 500, { error: err.message });
  }
}

module.exports = {
  handleLogSubmit,
  handleListLogSubmissions,
  handleGetLogSubmission,
  handleDownloadLogSubmission,
  handleDeleteLogSubmission,
  cleanupOldSubmissions,
  handleInternalGetLogSubmission,
  handleGetSubmissionAnalysis,
  handleListCases,
  handleGetCase,
  handleUpdateCase,
  handleDeleteCase,
};
