import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { permissionRows, openPermissionGuide, showPermissionGuide, enablePermissionGuide, togglePermission, receivePermissionToggle } from '../src/permissions.js';
import { Service } from '../src/service.js';

function fixture() {
  const service = new EventEmitter();
  service.config = { id:'target', name:'目标设备', permissions:{ input:true, screen:false, clipboard:true, shutdown:true } };
  service.group = { excluded: id => id === 'removed' };
  service.peers = new Map();
  service.permissionNotice = null;
  service.settings = async ({permissions}) => { Object.assign(service.config.permissions, permissions); return {}; };
  service.grants = { requester: { input:true, screen:false, clipboard:true, shutdown:true }, other: { input:false } };
  service.consent = { allowed: (id,key) => service.grants[id]?.[key] === true };
  service.setConsent = async (id,permissions) => { Object.assign(service.grants[id],permissions); return {message:'权限已保存'}; };
  return service;
}

test('permission colors distinguish disabled, missing system authorization and unknown reports', () => {
  const report = {version:1,permissions:{input:{appEnabled:true,systemGranted:true,peerAllowed:true},screen:{appEnabled:true,systemGranted:false,peerAllowed:true},clipboard:{appEnabled:false,systemGranted:true,peerAllowed:true},shutdown:{supported:false}}};
  assert.deepEqual(permissionRows(report).map(row=>row.state), ['ready','unready','disabled','unsupported']);
  assert.ok(permissionRows(null).every(row=>row.state==='unknown'));
  report.permissions.input.peerAllowed=false;
  assert.equal(permissionRows(report)[0].state,'disabled');
  delete report.permissions.input.systemGranted;
  report.permissions.input.peerAllowed=true;
  assert.equal(permissionRows(report)[0].state,'unknown');
});

test('a remote guide opens only on the selected target and does not grant permissions', async () => {
  const source=fixture(), target=fixture();source.config.id='source';
  source.peers.set('target',{info:{name:'目标设备'},request:async (method,args)=>{assert.equal(method,'permission-guide-open');return showPermissionGuide(target,args.permission,'requester','发起设备');}});
  const result=await openPermissionGuide(source,'target','screen');
  assert.equal(result.remote,true);
  assert.equal(source.permissionNotice,null);
  assert.equal(target.permissionNotice.permission,'screen');
  assert.equal(target.permissionNotice.name,'发起设备');
  assert.equal(target.config.permissions.screen,false);
  assert.equal(target.grants.requester.screen,false);
});

test('local guide enable is scoped to the selected permission and requester', async () => {
  const target=fixture();showPermissionGuide(target,'screen','requester','发起设备');
  const id=target.permissionNotice.requestId;
  await enablePermissionGuide(target,id);
  assert.equal(target.config.permissions.screen,true);
  assert.equal(target.grants.requester.screen,true);
  assert.equal(target.grants.other.input,false);
  showPermissionGuide(target,'input');
  await assert.rejects(enablePermissionGuide(target,id), /请求已结束/);
  target.permissionNotice.expires=Date.now()-1;
  await assert.rejects(enablePermissionGuide(target,target.permissionNotice.requestId), /请求已结束/);
});

test('disabling remote permission does not change other controllers; reenabling needs target confirmation', async () => {
  const target=fixture(),peer={remote:{id:'requester'},info:{name:'发起设备'}};
  await receivePermissionToggle(target,peer,'input',false);
  assert.equal(target.grants.requester.input,false);
  assert.equal(target.grants.other.input,false);
  assert.equal(target.config.permissions.input,true);
  const result=await receivePermissionToggle(target,peer,'input',true);
  assert.equal(result.guideRequired,true);
  assert.equal(target.grants.requester.input,false);
  assert.equal(target.permissionNotice.permission,'input');
  await enablePermissionGuide(target,target.permissionNotice.requestId);
  assert.equal(target.grants.requester.input,true);
});

test('invalid keys, malformed switches and offline targets fail without mutation', async () => {
  const service=fixture();
  assert.throws(()=>showPermissionGuide(service,'host'),/有效/);
  await assert.rejects(togglePermission(service,'target','input','true'),/无效/);
  await assert.rejects(openPermissionGuide(service,'offline','input'),/离线/);
  await assert.rejects(togglePermission(service,'removed','input',true),/离线/);
  assert.equal(service.permissionNotice,null);
});

test('unrecognized older target responses do not show a false guide acknowledgement', async () => {
  const service=fixture();service.peers.set('old',{request:async()=>({})});
  await assert.rejects(openPermissionGuide(service,'old','input'),/升级/);
});

test('guide and toggle wire methods reject stale and excluded peers before changing state', async () => {
  const service=fixture();service.currentPeer=()=>false;
  const peer={remote:{id:'requester'}};
  await assert.rejects(Service.prototype.handle.call(service,peer,'permission-guide-open',{permission:'screen'}),/连接已结束/);
  service.currentPeer=()=>true;peer.remote.id='removed';
  await assert.rejects(Service.prototype.handle.call(service,peer,'permission-toggle',{permission:'input',enabled:false}),/已移出/);
  assert.equal(service.permissionNotice,null);
});
