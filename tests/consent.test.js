import test from 'node:test';
import assert from 'node:assert/strict';
import { Consent } from '../src/consent.js';

test('consent coalesces retries, expires, refuses stale decisions and preserves explicit denials', () => {
  const c = new Consent();
  assert.throws(() => c.require('a', 'A', 'input', 0), /CONSENT_PENDING/);
  const request = c.list(0)[0];
  assert.throws(() => c.require('a', 'A', 'screen', 1), /CONSENT_PENDING/);
  assert.equal(c.list(1)[0], request);
  c.decide('a', request.requestId, false, {}, 2);
  assert.throws(() => c.require('a', 'A', 'input', 3), /CONSENT_DENIED/);
  assert.throws(() => c.require('a', 'A', 'input', 120003), /CONSENT_PENDING/);
  assert.throws(() => c.decide('a', request.requestId, true, { input: true }, 120003), /请求已结束/);
  const next = c.list(120003)[0]; c.decide('a', next.requestId, true, { screen: true }, 120004);
  assert.equal(c.allowed('a', 'screen'), true); assert.equal(c.allowed('a', 'input'), false);
  assert.throws(() => c.require('a', 'A', 'input', 120005), /CONSENT_DENIED/);
  const persisted = new Consent(JSON.parse(JSON.stringify(c.grants))); persisted.require('a', 'A', 'screen');
  c.reset(); assert.equal(c.allowed('a', 'screen'), false);
  assert.throws(() => c.require('a', 'A', 'input', 0), /CONSENT_PENDING/);
  assert.equal(c.list(120000).length, 0); assert.throws(() => c.require('a', 'A', 'input', 120001), /CONSENT_DENIED/);
  assert.throws(() => c.require('b', 'B', 'input', 120001), /CONSENT_PENDING/); c.disconnect('b'); assert.equal(c.list(120001).length, 0);
});
