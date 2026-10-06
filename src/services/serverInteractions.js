const s = require('./social');
const config = require('../config');
const CATALOG = 'discovery:v1:listings';
const field = type => type === 'like' ? 'likedServers' : 'favoriteServers';
function normalize(value = {}) {
  return Object.fromEntries(['likedServers', 'favoriteServers'].map(k => [k, [...new Set((Array.isArray(value[k]) ? value[k] : []).filter(s.validId).map(x => x.toLowerCase()))]]));
}
async function get(id) {
  if (!id) return normalize();
  const [record, legacy] = await s.redis.mget(s.key(id), `${config.redisKeys.USER}${id}`);
  return normalize(JSON.parse(record || '{}').serverInteractions || JSON.parse(legacy || '{}'));
}
async function update(id, serverId, type, enabled) {
  serverId = s.requireId(serverId);
  const legacy = await get(id);
  await s.change([id], (records, emit, metric) => {
    const data = records[id].serverInteractions ||= legacy;
    const key = field(type), values = new Set(data[key]);
    if (values.has(serverId) !== enabled) metric(`server${type === 'like' ? 'Likes' : 'Favorites'}${enabled ? 'Added' : 'Removed'}`);
    if (enabled) values.add(serverId); else values.delete(serverId);
    data[key] = [...values];
  });
}
function flags(listing, data) {
  return { ...listing, isLiked: data.likedServers.includes(listing.uuid), isFavorited: data.favoriteServers.includes(listing.uuid) };
}
async function remember(listings) {
  if (listings.length) await s.redis.hset(CATALOG, ...listings.flatMap(x => [x.uuid, JSON.stringify(x)]));
}
async function cached(ids) {
  return ids.length ? (await s.redis.hmget(CATALOG, ...ids)).filter(Boolean).map(JSON.parse) : [];
}
module.exports = { get, update, flags, remember, cached, field, CATALOG };
