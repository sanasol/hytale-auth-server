const { redis } = require('./redis');
const PREFIX = 'social:metrics:v1:';
const TOTALS_KEY = `${PREFIX}totals`;
const RETENTION_DAYS = 365;
const FIELDS = ['serverFavoritesAdded', 'serverFavoritesRemoved', 'serverLikesAdded', 'serverLikesRemoved', 'clientP2PConnectSuccess', 'clientP2PConnectFailure', 'clientConnectSuccess', 'clientConnectFailure', 'clientWorldJoined', 'iceDirect', 'iceRelay', 'iceFailed', 'iceUnknown', 'friendRequestsSent', 'friendRequestsRejected', 'friendsAccepted', 'friendsRemoved', 'blocksCreated', 'blocksRemoved', 'partiesCreated', 'partiesClosed', 'partyMembersJoined', 'partyMembersLeft', 'partyInvitesSent', 'partyInvitesAccepted', 'partyInvitesRejected', 'partyInvitesCanceled', 'worldInvitesSent', 'worldInvitesAccepted', 'worldInvitesRejected', 'worldInvitesCanceled', 'worldJoinHttpSuccess', 'worldJoinHttpDenied', 'worldJoinHttpErrors'];
const DELTAS = ['friendEdgesDelta', 'partiesDelta'];
const dailyKey = date => `${PREFIX}daily:${date}`;
function increment(counts, name, delta = 1) {
  if (![...FIELDS, ...DELTAS].includes(name) || !Number.isSafeInteger(delta)) throw Error('Invalid social metric');
  counts[name] = (counts[name] || 0) + delta;
}
// Queue alongside the state mutation: aborted WATCH attempts do not increment counters.
function append(tx, counts, now = Date.now()) {
  const entries = Object.entries(counts).filter(([,delta])=>delta);
  if (!entries.length) return;
  const day = new Date(now).toISOString().slice(0,10), key = dailyKey(day);
  // Tracking start is ingestion time; delayed client events keep their own UTC day.
  tx.hsetnx(TOTALS_KEY, 'since', new Date().toISOString());
  for (const [name,delta] of entries) {
    tx.hincrby(TOTALS_KEY, name, delta);
    if (FIELDS.includes(name)) tx.hincrby(key, name, delta);
  }
  // Fixed UTC-day expiry, not extended by subsequent requests.
  tx.expireat(key, Math.floor(Date.parse(`${day}T00:00:00Z`)/1000) + RETENTION_DAYS*86400);
}
async function record(name) {
  const db = redis.duplicate({ lazyConnect:true, retryStrategy:null, enableOfflineQueue:false, autoResendUnfulfilledCommands:false });
  db.on('error',()=>{});
  try {
    await db.connect();
    const counts = {}; increment(counts,name);
    const tx = db.multi(); append(tx,counts);
    const result = await tx.exec();
    if (!result || result.some(([error])=>error)) throw Error('Metric write failed');
  } catch (error) { console.error('Social HTTP metric unavailable:',error.message); }
  finally { db.disconnect(); }
}
async function read(days = 30) {
  days = Math.max(1,Math.min(RETENTION_DAYS,Math.trunc(Number(days)) || 30));
  const today = Date.parse(new Date().toISOString().slice(0,10));
  const dates = Array.from({length:days},(_,i)=>new Date(today-(days-1-i)*86400000).toISOString().slice(0,10));
  const tx = redis.pipeline().hgetall(TOTALS_KEY);
  for (const date of dates) tx.hgetall(dailyKey(date));
  const results = await tx.exec();
  if (results.some(([error])=>error)) throw Error('Social metrics unavailable');
  const totals = results[0][1];
  const numbers = row => Object.fromEntries(FIELDS.map(name=>[name,Number(row[name] || 0)]));
  const baselineKnown = totals.baselineKnown === '1';
  return { since:totals.since || null, retentionDays:RETENTION_DAYS, baselineKnown,
    current:{friendEdges:baselineKnown ? Number(totals.friendEdgesDelta || 0)+Number(totals.friendEdgesBaseline) : null,
      parties:baselineKnown ? Number(totals.partiesDelta || 0)+Number(totals.partiesBaseline) : null},
    changesSinceTracking:{friendEdges:Number(totals.friendEdgesDelta || 0),parties:Number(totals.partiesDelta || 0)},
    totals:numbers(totals), daily:dates.map((date,i)=>({date,...numbers(results[i+1][1])})) };
}
// Inputs must all come from the SAME isolated checkpoint, after counter hooks were deployed.
// Keep offsets separate: writes since that checkpoint remain included without scanning live state.
async function initBaseline(snapshot) {
  for (const name of ['friendEdges','parties','friendEdgesDelta','partiesDelta']) {
    if (!Number.isSafeInteger(snapshot[name]) || (['friendEdges','parties'].includes(name) && snapshot[name]<0)) throw Error(`Invalid snapshot ${name}`);
  }
  if (typeof snapshot.baselineId !== 'string' || !snapshot.baselineId || snapshot.baselineId.length>128) throw Error('Snapshot baselineId required');
  return !!await redis.eval(`
    if redis.call('HGET', KEYS[1], 'baselineKnown') == '1' then return 0 end
    redis.call('HSET', KEYS[1], 'friendEdgesBaseline', ARGV[1], 'partiesBaseline', ARGV[2], 'baselineId', ARGV[3], 'baselineKnown', '1')
    return 1
  `,1,TOTALS_KEY,snapshot.friendEdges-snapshot.friendEdgesDelta,snapshot.parties-snapshot.partiesDelta,snapshot.baselineId);
}
module.exports = { append, increment, record, read, initBaseline, PREFIX, TOTALS_KEY, dailyKey, RETENTION_DAYS, FIELDS };
