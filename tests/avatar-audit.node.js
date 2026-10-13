const { test } = require('node:test');
const assert = require('node:assert/strict');
const { eligiblePlayer, diverseSample, MAX_CHARACTERS } = require('../assets/avatar-audit');
const uuid = n => '00000000-0000-4000-8000-' + String(n).padStart(12, '0');
test('gallery excludes server identities and malformed UUIDs', () => {
  assert.equal(eligiblePlayer({ uuid: uuid(1), name: 'Server-00000000' }), false);
  assert.equal(eligiblePlayer({ uuid: 'not-a-uuid', username: 'Alice' }), false);
  assert.equal(eligiblePlayer({ uuid: uuid(1), username: 'Alice' }), true);
});
test('sample is capped and prefers diverse saved body / accessory features', () => {
  const candidates = Array.from({ length: 30 }, (_, index) => ({ uuid: uuid(index), model: { raw: {
    bodyCharacteristic: 'Default.01', face: 'Face_Default', eyes: 'Medium_Eyes.Blue', haircut: 'Morning.Black'
  } } }));
  candidates[29].model.raw.bodyCharacteristic = 'Goblin.22';
  candidates[28].model.raw.headAccessory = 'Hoodie.Black';
  candidates[27].model.raw.faceAccessory = 'Blindfold.Red';
  const selected = diverseSample(candidates);
  assert.equal(selected.length, MAX_CHARACTERS);
  for (const index of [27, 28, 29]) assert(selected.some(candidate => candidate.uuid === uuid(index)));
});
test('sample never duplicates a player and preserves the latest fetched model', () => {
  const stale = { uuid: uuid(1), model: { raw: { bodyCharacteristic: 'Default.01' } } };
  const latest = { uuid: uuid(1), model: { raw: { bodyCharacteristic: 'Goblin.22' } } };
  const selected = diverseSample([stale, latest]);
  assert.equal(selected.length, 1);
  assert.equal(selected[0].model.raw.bodyCharacteristic, 'Goblin.22');
});
