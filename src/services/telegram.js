const https = require('https');
const fs = require('fs');
const path = require('path');
const config = require('../config');

/**
 * Send log submission notification to Telegram with file attachment
 * Fire-and-forget — does not block the response
 */
function sendLogNotification(meta, filePath) {
  const token = config.telegramBotToken;
  const chatId = config.telegramChannelId;

  if (!token || !chatId) {
    console.log('[Telegram] Bot token or channel ID not configured, skipping notification');
    return;
  }

  const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  const caption = [
    `📋 Log Submission: <code>${esc(meta.id.substring(0, 8))}</code>`,
    `👤 ${esc(meta.username || 'unknown')}`,
    `💻 ${esc(meta.platform || 'unknown')} | v${esc(meta.version || '?')}`,
    `📁 ${meta.fileCount || 0} files, ${formatBytes(meta.fileSize || 0)}`,
    `🕐 ${new Date(meta.createdAt).toUTCString()}`,
  ].join('\n');

  if (!filePath || !fs.existsSync(filePath)) {
    // Send text-only message if no file
    sendMessage(token, chatId, caption);
    return;
  }

  sendDocument(token, chatId, filePath, `logs-${meta.id.substring(0, 8)}.zip`, caption);
}

function formatBytes(bytes) {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
}

/**
 * Send a text message to Telegram
 */
function sendMessage(token, chatId, text) {
  const payload = JSON.stringify({
    chat_id: chatId,
    text: text,
    parse_mode: 'HTML'
  });

  const options = {
    hostname: 'api.telegram.org',
    path: `/bot${token}/sendMessage`,
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Content-Length': Buffer.byteLength(payload)
    }
  };

  const req = https.request(options, (res) => {
    let body = '';
    res.on('data', (chunk) => body += chunk);
    res.on('end', () => {
      if (res.statusCode !== 200) {
        console.error('[Telegram] sendMessage failed:', res.statusCode, body);
      }
    });
  });

  req.on('error', (err) => {
    console.error('[Telegram] sendMessage error:', err.message);
  });

  req.write(payload);
  req.end();
}

/**
 * Send a document to Telegram via multipart/form-data
 */
function sendDocument(token, chatId, filePath, filename, caption) {
  const boundary = '----TelegramBoundary' + Date.now().toString(16);
  const fileContent = fs.readFileSync(filePath);

  const parts = [];

  // chat_id field
  parts.push(
    `--${boundary}\r\n` +
    `Content-Disposition: form-data; name="chat_id"\r\n\r\n` +
    `${chatId}\r\n`
  );

  // caption field
  parts.push(
    `--${boundary}\r\n` +
    `Content-Disposition: form-data; name="caption"\r\n\r\n` +
    `${caption}\r\n`
  );

  // parse_mode field
  parts.push(
    `--${boundary}\r\n` +
    `Content-Disposition: form-data; name="parse_mode"\r\n\r\n` +
    `HTML\r\n`
  );

  // document field (binary)
  const fileHeader = Buffer.from(
    `--${boundary}\r\n` +
    `Content-Disposition: form-data; name="document"; filename="${filename}"\r\n` +
    `Content-Type: application/zip\r\n\r\n`
  );

  const fileFooter = Buffer.from(`\r\n--${boundary}--\r\n`);

  const textParts = Buffer.from(parts.join(''));
  const body = Buffer.concat([textParts, fileHeader, fileContent, fileFooter]);

  const options = {
    hostname: 'api.telegram.org',
    path: `/bot${token}/sendDocument`,
    method: 'POST',
    headers: {
      'Content-Type': `multipart/form-data; boundary=${boundary}`,
      'Content-Length': body.length
    }
  };

  const req = https.request(options, (res) => {
    let respBody = '';
    res.on('data', (chunk) => respBody += chunk);
    res.on('end', () => {
      if (res.statusCode !== 200) {
        console.error('[Telegram] sendDocument failed:', res.statusCode, respBody);
      } else {
        console.log('[Telegram] Log notification sent successfully');
      }
    });
  });

  req.on('error', (err) => {
    console.error('[Telegram] sendDocument error:', err.message);
  });

  req.write(body);
  req.end();
}

module.exports = { sendLogNotification };
