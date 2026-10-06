const { createHash } = require('crypto');
const { redis } = require('./redis');
const usage = require('./socialMetrics');
const EVENTS = new Set(['server_connect','world_joined','ice_result']);
// Track only authenticated native events with an identity/session/sequence envelope.
// Seven-day retry window bounds deduplication storage; aggregates retain 365 days.
async function record(uuid, body) {
  if (body.type !== 'event' || !EVENTS.has(body.event_name)) return null;
  const time = Date.parse(body.timestamp), now = Date.now(), data = body.event_data;
  if (!Number.isFinite(time) || time > now+300000 || time < now-7*86400000 ||
      typeof body.session_id !== 'string' || !body.session_id.length || body.session_id.length>128 ||
      !Number.isSafeInteger(body.sequence) || body.sequence<0 || !data || typeof data!=='object' || Array.isArray(data)) return false;
  const counts = {};
  if (body.event_name === 'server_connect') {
    if (typeof data.success !== 'boolean' || typeof data.is_p2p !== 'boolean') return false;
    // Local single-player hosting also sends server_connect; use world_joined separately.
    usage.increment(counts,data.success?'clientConnectSuccess':'clientConnectFailure');
    if (data.is_p2p) usage.increment(counts,data.success?'clientP2PConnectSuccess':'clientP2PConnectFailure');
  } else if (body.event_name === 'world_joined') {
    if (typeof data.is_singleplayer !== 'boolean') return false;
    if (!data.is_singleplayer) usage.increment(counts,'clientWorldJoined');
  } else {
    const nominated = data.reason === 'Nominated';
    const kind = nominated && typeof data.used_relay==='boolean' ? (data.used_relay?'iceRelay':'iceDirect') : (['AllPairsFailed','NoCandidates','SignalingUnavailable'].includes(data.reason) ? 'iceFailed' : 'iceUnknown');
    usage.increment(counts,kind);
  }
  const day = new Date(time).toISOString().slice(0,10);
  const session = createHash('sha256').update(`${uuid}:${body.session_id}`).digest('hex');
  const key = `${usage.PREFIX}seen:${day}:${session}`;
  const member = createHash('sha256').update(`${uuid}:${body.session_id}:${body.sequence}`).digest('hex');
  // WATCH prevents two workers counting the same retry. An ambiguous commit is
  // safe to retry: the dedup marker and counts commit in the same transaction.
  const db=redis.duplicate({lazyConnect:true,retryStrategy:null,enableOfflineQueue:false,autoResendUnfulfilledCommands:false});
  db.on('error',()=>{});
  try {
    await db.connect();
    for(let attempt=0;attempt<8;attempt++) {
      await db.watch(key);
      if(await db.sismember(key,member)) return false;
      const tx=db.multi().sadd(key,member).expireat(key,Math.floor(Date.parse(day)/1000)+9*86400);
      usage.append(tx,counts,Math.min(time,now));
      const result=await tx.exec();
      if(result) { if(result.some(([error])=>error)) throw Error('Telemetry metrics write failed'); return true; }
    }
    throw Error('Telemetry metrics busy; retry');
  } finally {db.disconnect();}
}
module.exports = { record };
