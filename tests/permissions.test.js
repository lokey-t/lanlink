import test from 'node:test';
import assert from 'node:assert/strict';
import { permissionReport, permissionRows, groupPermissions } from '../src/permissions.js';

test('permission report separates app, OS and per-controller consent without changing grants', () => {
  const service = { config:{id:'self',permissions:{input:true,screen:false,clipboard:true,shutdown:true},deviceGrants:{peer:{input:false,screen:true}}},native:{capabilities:{input:true,screen:false,clipboard:true,shutdown:true}},consent:{allowed:(id,key)=>!Object.hasOwn(service.config.deviceGrants,id)||service.config.deviceGrants[id]?.[key]===true},canShareClipboard:id=>!Object.hasOwn(service.config.deviceGrants,id)||service.config.deviceGrants[id].clipboard===true };
  const rows=permissionRows(permissionReport(service,'peer'));
  assert.equal(rows[0].status,'未允许此电脑'); assert.equal(rows[1].status,'应用未开启');
  assert.equal(permissionRows(permissionReport(service,'new'))[0].status,'已就绪');
  assert.equal(permissionRows(permissionReport(service))[0].status,'已就绪');
  assert.equal(permissionRows(null)[0].status,'状态未知');
  assert.equal(permissionRows({version:1,permissions:{shutdown:{supported:false}}})[3].status,'不支持');
  service.native.capabilities.simulated=true;
  assert.notEqual(permissionRows(permissionReport(service))[0].status,'已就绪');
});

test('group details include offline members and failures as unknown, never as authorized', async () => {
  const service={config:{id:'self',name:'本机',permissions:{},deviceGrants:{},knownDevices:{off:{name:'离线'}}},native:{capabilities:{}},consent:{allowed:()=>false},group:{data:{members:{on:{name:'在线'},off:{name:'离线'},gone:{}}},excluded:id=>id==='gone'},peers:new Map([['on',{info:{name:'在线',platform:'android'},request:async()=>{throw Error('older version');}}]]),refreshCapabilities:async()=>{}};
  const result=await groupPermissions(service);
  assert.equal(result.devices.length,3);
  assert.equal(result.devices.find(d=>d.id==='off').online,false);
  assert.equal(result.devices.find(d=>d.id==='on').permissions[0].status,'状态未知');
  assert.ok(result.devices.find(d=>d.id==='on').message);
});
