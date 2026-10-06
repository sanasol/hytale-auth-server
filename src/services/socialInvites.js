const { randomUUID } = require('crypto');
const storage = require('./storage');
const s = require('./social');
const alive = inv => Date.parse(inv.expiresAt) > Date.now();
const partyKey = id => `party:${id}`;
const prune = u => { for (const f of ['invites', 'sent']) for (const [id, inv] of Object.entries(u[f])) if (!alive(inv)) delete u[f][id]; };
const publicInvite = inv => {
  const { kind, inviteCode, ...result } = inv;
  // Native WorldInviteResponse uses non-nullable Guid, including old stored invites.
  if (kind === 'world') result.serverUuid ||= s.EMPTY_UUID;
  return result;
};
async function getParty(id) {
  const u = await s.user(id);
  if (!u.partyId) s.fail(404, 'Not in a party');
  const p = (await s.user(partyKey(u.partyId))).party;
  if (!p || !p.members.includes(id)) s.fail(404, 'Not in a party');
  return { ...p, currentSize: p.members.length };
}
async function createParty(id, size = 4) {
  if (!Number.isInteger(size) || size < 2 || size > 16) s.fail(400, 'maxSize must be 2..16');
  const partyId = randomUUID();
  return s.change([id, partyKey(partyId)], r => {
    if (r[id].partyId) s.fail(409, 'Already in a party');
    const p = { partyId, leaderUuid: id, members: [id], maxSize: size };
    r[id].partyId = partyId; r[partyKey(partyId)].party = p;
    return { ...p, currentSize: 1 };
  });
}
async function leave(id) {
  const p = await getParty(id);
  await s.change([id, partyKey(p.partyId)], (r, emit) => {
    const party = r[partyKey(p.partyId)].party;
    if (r[id].partyId !== p.partyId) s.fail(409, 'Party changed');
    delete r[id].partyId; party.members = party.members.filter(x => x !== id);
    for (const member of party.members) emit(member, 'party.member.left', { party_id: p.partyId, player_uuid: id, member_count: party.members.length });
    if (party.leaderUuid === id) {
      party.leaderUuid = party.members[0] || null;
      for (const member of party.members) emit(member, 'party.leader.changed', { party_id: p.partyId, new_leader_uuid: party.leaderUuid });
    }
    if (!party.members.length) delete r[partyKey(p.partyId)].party;
  });
}
async function send(id, body, kind) {
  const target = s.requireId(body.targetUuid);
  if (target === id) s.fail(400, 'Cannot invite yourself');
  if (!await storage.getUsername(target)) s.fail(404, 'Player not found');
  const source = await s.user(id);
  const partyId = kind === 'party' ? source.partyId : null;
  if (kind === 'party' && !partyId) s.fail(409, 'Create a party first');
  const seconds = body.expiresInSeconds ?? 300;
  if (!Number.isInteger(seconds) || seconds < 1 || seconds > 3600) s.fail(400, 'Invalid invite expiry');
  if (kind === 'world' && (typeof body.inviteCode !== 'string' || !body.inviteCode || body.inviteCode.length > 16384)) s.fail(400, 'Invalid invite code');
  const ids = [id, target, ...(partyId ? [partyKey(partyId)] : [])];
  return s.change(ids, (r, emit) => {
    const a = r[id], b = r[target]; prune(a); prune(b);
    if (s.blocked(a, b, id, target) || !s.permits(b.settings.allowInvites, !!b.friends[id])) s.fail(403, 'Invitations disabled');
    if (Object.keys(a.sent).length >= 100 || Object.keys(b.invites).length >= 100) s.fail(429, 'Too many invitations');
    if (partyId) {
      const party = r[partyKey(partyId)].party;
      if (a.partyId !== partyId || !party?.members.includes(id)) s.fail(409, 'Party changed');
      if (party.leaderUuid !== id) s.fail(403, 'Only the party leader can invite');
      if (b.partyId === partyId || party.members.includes(target)) s.fail(409, 'Already in this party');
      if (party.members.length >= party.maxSize) s.fail(409, 'Party is full');
    }
    if (kind === 'world' && (!a.presence || a.presence.updatedAt < Date.now() - 300000 || a.presence.status !== 'online')) s.fail(409, 'World is offline');
    const inv = { kind, inviteUuid: randomUUID(), inviterUuid: id, invitedPlayerUuid: target,
      createdAt: new Date().toISOString(), expiresAt: new Date(Date.now() + seconds * 1000).toISOString(),
      ...(kind === 'party' ? { partyId } : { inviteCode: body.inviteCode, serverUuid: a.presence.serverUuid || s.EMPTY_UUID, serverName: a.presence.serverName || null, worldName: a.presence.worldName || null, isP2P: true }) };
    a.sent[inv.inviteUuid] = b.invites[inv.inviteUuid] = inv;
    emit(target, `${kind}.invite.received`, { invite_uuid: inv.inviteUuid, inviter_uuid: id, invited_player_uuid: target, party_id: partyId,
      expires_at: inv.expiresAt, created_at: inv.createdAt, server_uuid: inv.serverUuid, server_name: inv.serverName, world_name: inv.worldName, is_p2p: inv.isP2P });
    return publicInvite(inv);
  });
}
async function respond(id, inviteId, action, kind) {
  inviteId = s.requireId(inviteId);
  const self = await s.user(id), initial = (action === 'cancel' ? self.sent : self.invites)[inviteId];
  if (!initial || initial.kind !== kind) s.fail(404, 'Invitation not found');
  const ids = [id, initial.inviterUuid, initial.invitedPlayerUuid, ...(initial.partyId ? [partyKey(initial.partyId)] : [])];
  return s.change(ids, (r, emit) => {
    const sender = r[initial.inviterUuid], recipient = r[initial.invitedPlayerUuid];
    const inv = sender.sent[inviteId];
    if (!inv || !recipient.invites[inviteId] || (action === 'accept' && !alive(inv))) s.fail(404, 'Invitation expired or canceled');
    if (action === 'accept' && s.blocked(sender, recipient, inv.inviterUuid, inv.invitedPlayerUuid)) s.fail(403, 'Invitation blocked');
    let result;
    if (action === 'accept' && kind === 'party') {
      const p = r[partyKey(inv.partyId)].party;
      if (!p || !p.members.includes(inv.inviterUuid)) s.fail(410, 'Party no longer available');
      if (recipient.partyId) s.fail(409, 'Already in a party');
      if (p.members.length >= p.maxSize) s.fail(409, 'Party is full');
      p.members.push(id); recipient.partyId = p.partyId;
      for (const member of p.members) emit(member, 'party.member.joined', { party_id: p.partyId, player_uuid: id, member_count: p.members.length });
      result = publicInvite(inv);
    } else if (action === 'accept') {
      const p = sender.presence;
      if (!p || p.status !== 'online' || p.updatedAt < Date.now() - 300000) s.fail(410, 'World is offline');
      result = { ...s.joinData(p), inviteCode: inv.inviteCode };
      // Short-lived grant allows signaling for an explicitly accepted non-friend invite.
      recipient.peerGrants = { ...recipient.peerGrants, [inv.inviterUuid]: Date.now() + 300000 };
      sender.peerGrants = { ...sender.peerGrants, [id]: Date.now() + 300000 };
    }
    delete sender.sent[inviteId]; delete recipient.invites[inviteId];
    if (kind === 'world') emit(action === 'cancel' ? inv.invitedPlayerUuid : inv.inviterUuid, `world.invite.${action === 'cancel' ? 'canceled' : action === 'accept' ? 'accepted' : 'rejected'}`, { invite_uuid: inviteId, ...(action === 'accept' ? { accepted_by_uuid: id } : action === 'reject' ? { rejected_by_uuid: id } : {}) });
    else if (action !== 'accept') emit(inv.invitedPlayerUuid, 'party.invite.canceled', { invite_uuid: inviteId, party_id: inv.partyId });
    return result;
  });
}
async function list(id, kind, sent) { const u = await s.user(id); return Object.values(sent ? u.sent : u.invites).filter(x => x.kind === kind && alive(x)).map(publicInvite); }
module.exports = { getParty, createParty, leave, send, respond, list };
