import test from 'node:test';
import assert from 'node:assert/strict';
import { Consent, permissionNames } from '../src/consent.js';

test('group defaults allow all recognized features without creating grants or prompts', () => {
  const c = new Consent();
  for (const id of ['computer-a', 'computer-b', 'phone-a', 'phone-b']) {
    for (const key of permissionNames) { assert.equal(c.allowed(id, key), true); c.require(id, id, key); }
    assert.equal(c.request(id, id), true);
  }
  assert.equal(c.allowed('a', 'unknown'), false);
  assert.deepEqual(c.grants, {}); assert.deepEqual(c.list(), []);
});

test('explicit restrictions survive requests, denial, persistence and reconnection', () => {
  const c = new Consent({ a: { input: false, screen: true } });
  assert.throws(() => c.require('a', 'A', 'input', 0), /CONSENT_DENIED/);
  c.request('a', 'A', 0); const request = c.list(0)[0];
  c.request('a', 'A', 1); assert.equal(c.list(1)[0], request);
  c.decide('a', request.requestId, false, {}, 2);
  assert.throws(() => c.request('a', 'A', 3), /CONSENT_DENIED/);
  c.request('a', 'A', 120003);
  assert.throws(() => c.decide('a', request.requestId, true, {}, 120003), /请求已结束/);
  const next = c.list(120003)[0]; c.decide('a', next.requestId, true, { input: true }, 120004);
  assert.equal(c.allowed('a', 'input'), true); assert.equal(c.allowed('a', 'screen'), false);
  const persisted = new Consent(JSON.parse(JSON.stringify(c.grants)));
  persisted.require('a', 'A', 'input'); persisted.disconnect('a');
  assert.throws(() => persisted.require('a', 'A', 'screen'), /CONSENT_DENIED/);
  c.reset(); assert.equal(c.allowed('a', 'screen'), true);
});

test('only requests for restricted features prompt and expired decisions cannot restore access', () => {
  const c = new Consent({ a: { input: true, screen: false } });
  assert.equal(c.request('a', 'A', 0, ['input']), true); assert.equal(c.list(0).length, 0);
  c.request('a', 'A', 0, ['screen']); const request = c.list(0)[0];
  assert.equal(c.list(120000).length, 0);
  assert.throws(() => c.decide('a', request.requestId, true, { screen: true }, 120001), /请求已结束/);
  assert.throws(() => c.require('a', 'A', 'screen', 120001), /CONSENT_DENIED/);
  c.require('a', 'A', 'input', 120001);
});
