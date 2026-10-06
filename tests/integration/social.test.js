// Run with a disposable Redis: REDIS_URL=redis://127.0.0.1:16379 DATA_DIR=/tmp/hytale-social-keys node --test tests/integration/social.test.js
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { randomUUID } = require('crypto');
if (!process.env.REDIS_URL || !process.env.DATA_DIR) throw Error('Explicit disposable REDIS_URL and DATA_DIR required');
const s = require('../../src/services/social');
const invites = require('../../src/services/socialInvites');
const auth = require('../../src/services/auth');
const { handleSocialRoutes } = require('../../src/routes/social');
const { connect } = require('../../src/services/redis');

test('social routes: two users, concurrency, privacy, invites, profiles, expiry and identity boundaries', { timeout: 15000 }, async () => {
  await connect();
  const a = randomUUID(), b = randomUUID(), c = randomUUID();
  const ids = [a,b,c], names = ['TestA','TestB','TestC'].map(x=>x+randomUUID().slice(0,8));
  const tokens = Object.fromEntries(ids.map(id=>[id,auth.generateSessionToken(id)]));
  const partyIds = [];
  const call = async (id,method,path,body={}, token=tokens[id]) => {
    const response = { writeHead(code) {this.status=code;}, end(text) {this.body=text ? JSON.parse(text) : null;} };
    assert.equal(await handleSocialRoutes({method,headers:{authorization:`Bearer ${token}`}},response,path,body),true);
    return response;
  };
  const ok = async (...args) => { const r=await call(...args); assert.ok(r.status>=200&&r.status<300,`${args[2]}: ${r.status} ${JSON.stringify(r.body)}`); return r.body; };
  try {
    for(let i=0;i<ids.length;i++) await s.redis.set(`username:${ids[i]}`,names[i]);
    assert.equal((await call(a,'POST','/friend-requests',{targetUuid:b},tokens[a]+'x')).status,401);
    await ok(a,'POST','/friend-requests/by-username',{targetUsername:names[1]});
    assert.equal((await ok(b,'GET','/friend-requests/incoming')).requests[0].requesterUuid,a);
    await Promise.all([ok(b,'POST','/friend-requests/accept',{requesterUuid:a}),ok(b,'POST','/friend-requests/accept',{requesterUuid:a})]);
    assert.equal((await ok(a,'GET','/friends')).friends[0].uuid,b);
    assert.equal((await ok(b,'GET','/friends')).friends[0].uuid,a);
    assert.equal((await s.user(a)).friendChanges[b].type,'friend.request.accepted');
    await ok(b,'POST','/presence/heartbeat',{status:'online',activity:'playing',serverUuid:b,serverName:'Host',worldName:'World',serverHost:'127.0.0.1',serverPort:5520,inviteCode:'opaque-code'});
    assert.equal((await ok(a,'GET','/presence/friends')).friends[0].canJoin,true);
    assert.equal((await ok(a,'POST','/presence/join-world',{targetUuid:b})).inviteCode,'opaque-code');
    await ok(b,'PUT','/presence/settings',{showLocation:2});
    assert.equal((await call(a,'POST','/presence/join-world',{targetUuid:b})).status,403);
    await ok(b,'PUT','/presence/settings',{showLocation:1});
    await ok(b,'PUT','/presence/appear-offline',{appearOffline:true});
    assert.equal((await ok(a,'GET','/presence/friends')).friends[0].status,'offline');
    await ok(b,'PUT','/presence/appear-offline',{appearOffline:false});
    const p=await ok(a,'POST','/party/create',{maxSize:2}); partyIds.push(p.partyId);
    const invitation=await ok(a,'POST','/party/invites/send',{targetUuid:b,expiresInSeconds:60});
    assert.equal((await ok(b,'GET','/party/invites')).invites.length,1);
    const party=await ok(b,'POST','/party/invites/accept',{inviteUuid:invitation.inviteUuid}); assert.deepEqual(party.members,[a,b]);
    await ok(a,'POST','/party/leave'); assert.equal((await ok(b,'GET','/party')).leaderUuid,b);
    const w=await ok(b,'POST','/world-invites',{targetUuid:a,inviteCode:'opaque-code'});
    assert.equal((await ok(a,'POST','/world-invites/accept',{inviteUuid:w.inviteUuid})).inviteCode,'opaque-code');
    await s.redis.set(`user:${b}`,JSON.stringify({skin:{bodyCharacteristic:'Default'}}));
    const profiles=await ok(a,'POST','/profile/uuids',{uuids:[b]}); assert.equal(profiles.profiles[0].username,names[1]); assert.equal(typeof profiles.profiles[0].skin,'string');
    await ok(a,'PUT','/friends/favorites',{favorites:[b]}); assert.deepEqual((await ok(a,'GET','/friends/favorites')).favorites,[b]);
    await s.change([b],r=>{r[b].presence.updatedAt=Date.now()-400000;});
    assert.equal((await ok(a,'GET','/presence/friends')).friends[0].status,'offline');
    await ok(a,'POST','/blocks',{targetUuid:b});
    assert.equal((await ok(a,'GET','/blocks')).blocks[0].blockedUuid,b);
    assert.equal((await ok(b,'GET','/friends')).friends.length,0);
    assert.equal((await s.user(b)).friendChanges[a].type,'friend.blocked');
    assert.equal((await call(b,'POST','/friend-requests',{targetUuid:a})).status,403);
    assert.equal((await call(c,'POST','/world-invites/accept',{inviteUuid:w.inviteUuid})).status,404);
    assert.equal((await call(a,'POST','/friends/unknown')).status,501);
  } finally {
    await s.redis.del(...ids.flatMap(id=>[s.key(id),`username:${id}`,`user:${id}`]),...partyIds.map(id=>s.key(`party:${id}`)));
    await s.redis.zrem(`${s.PREFIX}expiry`,...ids);
    s.redis.disconnect();
  }
});
