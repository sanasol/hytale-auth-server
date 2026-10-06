// Run with a disposable Redis: REDIS_URL=redis://127.0.0.1:16379 DATA_DIR=/tmp/hytale-social-keys node --test tests/integration/social.test.js
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { randomUUID } = require('crypto');
if (!process.env.REDIS_URL || !process.env.DATA_DIR) throw Error('Explicit disposable REDIS_URL and DATA_DIR required');
const s = require('../../src/services/social');
const invites = require('../../src/services/socialInvites');
const socialMetrics = require('../../src/services/socialMetrics');
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
    const initialMetrics=(await socialMetrics.read(1)).totals;
    for(let i=0;i<ids.length;i++) await s.redis.set(`username:${ids[i]}`,names[i]);
    // Losing the WATCH connection must not reconnect and commit an unguarded write.
    const duplicate = s.redis.duplicate;
    const beforeDisconnect = await s.redis.get(s.key(a));
    let callbackRan = false;
    try {
      s.redis.duplicate = function (...args) {
        const db = duplicate.apply(this,args), watch = db.watch.bind(db);
        db.watch = async (...keys) => {
          const result = await watch(...keys);
          const connectionId = await db.client('ID');
          assert.equal(await s.redis.client('KILL','ID',connectionId),1);
          return result;
        };
        return db;
      };
      await assert.rejects(s.change([a],r=>{ callbackRan=true; r[a].partyId='must-not-commit'; }),{status:503});
    } finally { s.redis.duplicate = duplicate; }
    assert.equal(callbackRan,false);
    assert.equal(await s.redis.get(s.key(a)),beforeDisconnect);
    assert.equal((await call(a,'POST','/friend-requests',{targetUuid:b},tokens[a]+'x')).status,401);
    await ok(a,'POST','/friend-requests/by-username',{targetUsername:names[1]});
    assert.equal((await ok(b,'GET','/friend-requests/incoming')).requests[0].requesterUuid,a);
    const beforeAccept=(await socialMetrics.read(1)).totals.friendsAccepted;
    await Promise.all([ok(b,'POST','/friend-requests/accept',{requesterUuid:a}),ok(b,'POST','/friend-requests/accept',{requesterUuid:a})]);
    assert.equal((await socialMetrics.read(1)).totals.friendsAccepted,beforeAccept+1);
    assert.equal((await ok(a,'GET','/friends')).friends[0].uuid,b);
    assert.equal((await ok(b,'GET','/friends')).friends[0].uuid,a);
    assert.equal((await s.user(a)).friendChanges[b].type,'friend.request.accepted');
    await ok(b,'POST','/presence/heartbeat',{status:'online',activity:'playing',serverUuid:b,serverName:'Host',worldName:'World',serverHost:'127.0.0.1',serverPort:5520,inviteCode:'opaque-code'});
    assert.equal((await ok(a,'GET','/presence/friends')).friends[0].canJoin,true);
    const beforeJoin=(await socialMetrics.read(1)).totals;
    assert.equal((await ok(a,'POST','/presence/join-world',{targetUuid:b})).inviteCode,'opaque-code');
    await ok(b,'PUT','/presence/settings',{showLocation:2});
    assert.equal((await call(a,'POST','/presence/join-world',{targetUuid:b})).status,403);
    const afterJoin=(await socialMetrics.read(1)).totals;
    assert.equal(afterJoin.worldJoinHttpSuccess,beforeJoin.worldJoinHttpSuccess+1);
    assert.equal(afterJoin.worldJoinHttpDenied,beforeJoin.worldJoinHttpDenied+1);
    assert.equal((await call(a,'POST','/presence/join-world',{targetUuid:b},tokens[a]+'x')).status,401);
    assert.equal((await socialMetrics.read(1)).totals.worldJoinHttpDenied,beforeJoin.worldJoinHttpDenied+2);
    await ok(b,'PUT','/presence/settings',{showLocation:1});
    await ok(b,'PUT','/presence/appear-offline',{appearOffline:true});
    assert.equal((await ok(a,'GET','/presence/friends')).friends[0].status,'offline');
    await ok(b,'PUT','/presence/appear-offline',{appearOffline:false});
    const p=await ok(a,'POST','/party/create',{maxSize:2}); partyIds.push(p.partyId);
    const declined=await ok(a,'POST','/party/invites/send',{targetUuid:c});
    assert.equal((await call(b,'POST','/party/invites/accept',{inviteUuid:declined.inviteUuid})).status,404);
    assert.equal((await call(c,'POST','/party/invites/cancel',{inviteUuid:declined.inviteUuid})).status,404);
    await ok(c,'POST','/party/invites/reject',{inviteUuid:declined.inviteUuid});
    assert.equal((await ok(c,'GET','/party/invites')).invites.length,0);
    assert.equal((await ok(a,'GET','/party/invites/sent')).invites.length,0);
    assert.equal((await call(c,'POST','/party/invites/accept',{inviteUuid:declined.inviteUuid})).status,404);
    const canceled=await ok(a,'POST','/party/invites/send',{targetUuid:c});
    await ok(a,'POST','/party/invites/cancel',{inviteUuid:canceled.inviteUuid});
    assert.equal((await ok(c,'GET','/party/invites')).invites.length,0);
    assert.equal((await ok(a,'GET','/party/invites/sent')).invites.length,0);
    assert.equal((await call(c,'POST','/party/invites/accept',{inviteUuid:canceled.inviteUuid})).status,404);
    const expiring=await ok(a,'POST','/party/invites/send',{targetUuid:c,expiresInSeconds:1});
    const now=Date.now, expires=Date.parse(expiring.expiresAt);
    try {
      Date.now=()=>expires-1;
      assert.equal((await ok(c,'GET','/party/invites')).invites[0].inviteUuid,expiring.inviteUuid);
      Date.now=()=>expires;
      assert.equal((await ok(c,'GET','/party/invites')).invites.length,0);
      assert.equal((await call(c,'POST','/party/invites/accept',{inviteUuid:expiring.inviteUuid})).status,404);
      assert.equal((await call(c,'GET','/party')).status,404);
    } finally { Date.now=now; }
    // Keep another invitation outstanding to check capacity again at acceptance.
    const waiting=await ok(a,'POST','/party/invites/send',{targetUuid:c});
    const invitation=await ok(a,'POST','/party/invites/send',{targetUuid:b,expiresInSeconds:60});
    assert.equal((await ok(b,'GET','/party/invites')).invites.length,1);
    const accepted=await ok(b,'POST','/party/invites/accept',{inviteUuid:invitation.inviteUuid}); assert.deepEqual(accepted,invitation);
    assert.deepEqual((await ok(b,'GET','/party')).members,[a,b]);
    assert.equal((await call(b,'POST','/party/invites/send',{targetUuid:c})).status,403);
    assert.equal((await call(a,'POST','/party/invites/send',{targetUuid:b})).status,409);
    assert.equal((await call(a,'POST','/party/invites/send',{targetUuid:c})).status,409);
    assert.equal((await call(c,'POST','/party/invites/accept',{inviteUuid:waiting.inviteUuid})).status,409);
    assert.deepEqual((await ok(a,'GET','/party')).members,[a,b]);
    await ok(a,'POST','/party/leave'); assert.equal((await ok(b,'GET','/party')).leaderUuid,b);
    assert.equal((await call(c,'POST','/party/invites/accept',{inviteUuid:waiting.inviteUuid})).status,410);
    const successorInvite=await ok(b,'POST','/party/invites/send',{targetUuid:c});
    await ok(c,'POST','/party/invites/accept',{inviteUuid:successorInvite.inviteUuid});
    await ok(c,'POST','/party/leave');
    assert.deepEqual((await ok(b,'GET','/party')).members,[b]);
    await ok(b,'POST','/party/leave');
    assert.equal((await call(b,'GET','/party')).status,404);
    assert.equal((await s.user(`party:${p.partyId}`)).party,undefined);
    const w=await ok(b,'POST','/world-invites',{targetUuid:a,inviteCode:'opaque-code'});
    assert.equal((await ok(a,'POST','/world-invites/accept',{inviteUuid:w.inviteUuid})).inviteCode,'opaque-code');
    // Expired notifications can still be dismissed by their recipient or sender.
    for (const action of ['reject','cancel']) {
      const expired=await ok(b,'POST','/world-invites',{targetUuid:a,inviteCode:'opaque-code'});
      await s.change([a,b],r=>{
        const past=new Date(Date.now()-1).toISOString();
        r[a].invites[expired.inviteUuid].expiresAt=past;
        r[b].sent[expired.inviteUuid].expiresAt=past;
      });
      assert.equal((await call(a,'POST','/world-invites/accept',{inviteUuid:expired.inviteUuid})).status,404);
      assert.equal((await call(c,'POST',`/world-invites/${action}`,{inviteUuid:expired.inviteUuid})).status,404);
      await ok(action==='cancel' ? b : a,'POST',`/world-invites/${action}`,{inviteUuid:expired.inviteUuid});
      assert.equal((await s.user(a)).invites[expired.inviteUuid],undefined);
      assert.equal((await s.user(b)).sent[expired.inviteUuid],undefined);
    }
    // A P2P host can heartbeat without a server UUID; native responses still require Guid text.
    await ok(b,'POST','/presence/heartbeat',{status:'online',activity:'playing',worldName:'P2P World',inviteCode:'p2p-code'});
    assert.equal((await ok(a,'POST','/presence/join-world',{targetUuid:b})).serverUuid,s.EMPTY_UUID);
    const subscriber=s.redis.duplicate();
    let notificationTimer;
    try {
      await subscriber.subscribe(s.CHANNEL);
      const received=new Promise((resolve,reject)=>{
        notificationTimer=setTimeout(()=>reject(Error('Missing world invite notification')),2000);
        subscriber.on('message',(_channel,text)=>{
          const event=JSON.parse(text);
          if (event.recipient===a && event.type==='world.invite.received') { clearTimeout(notificationTimer); resolve(event); }
        });
      });
      const p2p=await ok(b,'POST','/world-invites',{targetUuid:a,inviteCode:'p2p-code'});
      assert.equal(p2p.serverUuid,s.EMPTY_UUID);
      const event=await received;
      assert.equal(event.data.invite_uuid,p2p.inviteUuid);
      assert.equal(event.data.server_uuid,s.EMPTY_UUID);
      // Invitations written before the fix must also remain readable by the native client.
      await s.change([a,b],r=>{ r[a].invites[p2p.inviteUuid].serverUuid=null; r[b].sent[p2p.inviteUuid].serverUuid=null; });
      assert.equal((await ok(a,'GET','/world-invites')).invites[0].serverUuid,s.EMPTY_UUID);
      assert.equal((await ok(b,'GET','/world-invites/sent')).invites[0].serverUuid,s.EMPTY_UUID);
      const joined=await ok(a,'POST','/world-invites/accept',{inviteUuid:p2p.inviteUuid});
      assert.equal(joined.serverUuid,s.EMPTY_UUID);
      assert.equal(joined.inviteCode,'p2p-code');
    } finally { clearTimeout(notificationTimer); subscriber.disconnect(); }
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
    const finalMetrics=(await socialMetrics.read(1)).totals;
    for (const [name,count] of Object.entries({friendsAccepted:1,friendsRemoved:1,partiesCreated:1,partiesClosed:1,
      partyMembersJoined:2,partyMembersLeft:3,partyInvitesSent:6,partyInvitesAccepted:2,partyInvitesRejected:1,partyInvitesCanceled:1,
      worldInvitesSent:4,worldInvitesAccepted:2,worldInvitesRejected:1,worldInvitesCanceled:1})) {
      assert.equal(finalMetrics[name]-initialMetrics[name],count,name);
    }
  } finally {
    await s.redis.del(...ids.flatMap(id=>[s.key(id),`username:${id}`,`user:${id}`]),...partyIds.map(id=>s.key(`party:${id}`)));
    await s.redis.zrem(`${s.PREFIX}expiry`,...ids);
    s.redis.disconnect();
  }
});
