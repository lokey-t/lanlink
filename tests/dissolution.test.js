import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { EventEmitter } from 'node:events';
import os from 'node:os';
import path from 'node:path';
import { Service } from '../src/service.js';
import { Group } from '../src/group.js';
import { loadConfig, saveConfig } from '../src/identity.js';
import { createActions } from '../src/actions.js';

class Native extends EventEmitter { capabilities = {}; async start() {} async call() { return {}; } close() {} }
async function fixture(t) {
  const root = await mkdtemp(path.join(os.tmpdir(), 'lanlink-dissolve-')), services = [];
  t.after(async () => { for (const s of services) await s.close(); await rm(root, { recursive: true, force: true }); });
  for (const name of ['a', 'b', 'offline', 'new']) {
    const dir = path.join(root, name), config = await loadConfig(dir); config.knownDevices = {};
    if (services.length && name !== 'new') config.token = services[0].config.token;
    const s = new Service(config, dir, new Native(), { host: '127.0.0.1', port: 0, noDiscovery: true }); await s.start(); services.push(s);
  }
  const [a,b,c,d] = services;
  for (const s of [a,b,c]) for (const other of [a,b,c]) if (s !== other) { s.group.learn(other.group.own); s.config.knownDevices[other.config.id] = { name: other.config.name }; }
  return { a,b,c,d, services };
}
async function until(fn) { for (let i=0; !fn(); i++) { assert(i<200, 'event completed'); await new Promise(r=>setTimeout(r,10)); } }

test('switch group requires explicit consent and validates pairing before dissolving; own-group invitation is harmless', async t => {
  const {a,b,d} = await fixture(t), call = createActions(a), old = a.group.data.id, token = a.config.token;
  a.pairing.join = async () => { throw Error('配对码错误'); };
  await assert.rejects(call('join', { code: '12345678' }), /确认解散/);
  await assert.rejects(call('join', { code: '12345678', dissolveCurrentGroup:true }), /配对码错误/);
  assert.equal(a.group.data.id,old); assert.equal(a.config.token,token); assert.equal(b.group.data.id,old); assert.equal(a.config.dissolvedGroups,undefined);
  a.pairing.join = async () => ({ token, group: structuredClone(a.group.export()) });
  assert.deepEqual(await call('join',{code:'12345678',dissolveCurrentGroup:true}),{alreadyInGroup:true});
  a.pairing.join = async () => ({ token:d.config.token, group:{...structuredClone(d.group.export()), members:{invalid:{id:'bad'}}} });
  await assert.rejects(call('join',{code:'12345678',dissolveCurrentGroup:true}),/公钥/);
  assert.equal(a.config.dissolvedGroups,undefined); assert.equal(a.group.data.id,old);
  await assert.rejects(call('join',{code:'12345678',dissolveCurrentGroup:true,expectedGroup:d.group.data.id}),/发生变化/);
});

test('dissolving exits every online member; offline notice survives restart and joining a new group', async t => {
  const {a,b,c,d} = await fixture(t), old=a.group.data.id;
  a.connect('127.0.0.1',b.port); await until(()=>a.peers.has(b.config.id)&&b.peers.has(a.config.id));
  a.config.edges.left=b.config.id; a.config.deviceGrants[b.config.id]={input:true};
  a.pairing.join=async()=>({token:d.config.token,group:structuredClone(d.group.export())});
  await createActions(a)('join',{code:'12345678',dissolveCurrentGroup:true,expectedGroup:old});
  await until(()=>b.group.data.id!==old);
  assert.equal(a.group.data.id,d.group.data.id); assert.notEqual(b.group.data.id,a.group.data.id);
  assert.deepEqual(a.config.edges,{}); assert.deepEqual(a.config.deviceGrants,{}); assert.equal(b.group.established,false);
  assert.equal(c.group.data.id,old);
  const saved=await loadConfig(a.dir), restarted={config:saved}; restarted.group=new Group(restarted);
  const notice=restarted.group.recoveryResponse(c.group.recoveryQuery()); assert.equal(notice.kind,'lanlink-group-dissolved-v1');
  await c.group.acceptDissolution(notice); assert.notEqual(c.group.data.id,old); assert.deepEqual(c.config.knownDevices,{});
  assert.deepEqual(c.group.recoveryResponse({kind:'lanlink-group-recovery-v1',id:b.config.id,group:old,revision:0}),notice);
  await assert.rejects(c.group.acceptDissolution(notice),/无效/);
  assert.throws(()=>c.group.adopt({id:old},a.config.token),/已经解散/);
});

test('terminal notices reject tampering, foreign and removed issuers but outrank later membership revisions', async t => {
  const {a,b,c,d}=await fixture(t), notice=a.group.dissolutionRecord();
  assert.throws(()=>b.group.verifyDissolution({...notice,signature:'AAAA'}),/签名/);
  assert.throws(()=>b.group.verifyDissolution(d.group.dissolutionRecord()),/无效/);
  b.group.data.removed.push(a.config.id); assert.throws(()=>b.group.verifyDissolution(notice),/签名/);
  c.group.data.revision=notice.revision+100; c.group.verifyDissolution(notice);
});

test('restart completes a pending switch after the terminal record is durable', async t => {
  const {a,d,services}=await fixture(t), previousGroup=a.group.data.id;
  a.config.pendingGroupJoin={previousGroup,token:d.config.token,group:structuredClone(d.group.export())};
  await a.group.rememberDissolution(a.group.dissolutionRecord()); await saveConfig(a.dir,a.config); await a.close();
  services.splice(services.indexOf(a),1);
  const restarted=new Service(await loadConfig(a.dir),a.dir,new Native(),{host:'127.0.0.1',port:0,noDiscovery:true}); services.push(restarted); await restarted.start();
  assert.equal(restarted.group.data.id,d.group.data.id); assert.equal(restarted.config.pendingGroupJoin,undefined); assert.deepEqual(restarted.config.knownDevices,{});
  assert.ok(restarted.config.dissolvedGroups[previousGroup]);
});
