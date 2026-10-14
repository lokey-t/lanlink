import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { AdbGuide } from '../src/adb-guide.js';
import { Service } from '../src/service.js';

function fixture(engine = {ready:false}) {
  const service = new EventEmitter(), calls=[];
  service.config={};service.group={data:{id:'group'}};service.entryPosition=Service.prototype.entryPosition;
  const peer={info:{platform:'android',name:'Phone'},request:async method=>{calls.push(method);return {engine};}};
  service.peers=new Map([['phone',peer]]);service.getPeer=id=>{if(!service.peers.has(id))throw Error('offline');return service.peers.get(id);};
  let leave=0; service.edge={waitForLeave:()=>leave++};
  const guide=new AdbGuide(service), intent={id:'phone',capture:true,screen:false,edge:'right',newSession:true};
  return {service,peer,calls,engine,guide,intent,leave:()=>leave};
}
test('ADB preflight creates one notice and never invokes Android permission prompts',async()=>{
  const f=fixture();await assert.rejects(f.guide.check(f.peer,f.intent),/ADB_SETUP_REQUIRED/);
  const id=f.guide.notice.requestId;
  await assert.rejects(f.guide.check(f.peer,f.intent),/ADB_SETUP_REQUIRED/);
  assert.equal(f.guide.notice.requestId,id);assert.deepEqual(f.calls,['permission-status']);
  assert.equal((await f.guide.status(id)).ready,false);
  await assert.rejects(f.guide.complete(id),/ADB_SETUP_REQUIRED/);
  f.engine.ready=true;const intent=await f.guide.complete(id);assert.equal(intent.capture,true);assert.equal(f.guide.notice,null);
});
test('Root, ready input and desktops skip ADB setup; recovery waits without a guide',async()=>{
  for(const engine of [{root:true,ready:true},{ready:true}]){const f=fixture(engine);await f.guide.check(f.peer,f.intent);assert.equal(f.guide.notice,null);}
  const desktop=fixture();desktop.peer.info.platform='win32';await desktop.guide.check(desktop.peer,desktop.intent);assert.deepEqual(desktop.calls,[]);
  const f=fixture({recovering:true});await assert.rejects(f.guide.check(f.peer,f.intent),/正在恢复/);assert.equal(f.guide.notice,null);
});
test('a pending setup for one Android device does not block another ready phone', async () => {
  const f = fixture({ ready: false });
  await assert.rejects(f.guide.check(f.peer, f.intent), /ADB_SETUP_REQUIRED/);
  const pending = f.guide.notice;
  const other = { info: { platform: 'android', name: 'Other phone' }, request: async () => ({ engine: { ready: true, authorized: true } }) };
  f.service.peers.set('other', other);
  await f.guide.check(other, { ...f.intent, id: 'other' });
  assert.equal(f.guide.notice, pending);
  other.request = async () => ({ engine: { ready: false } });
  await assert.rejects(f.guide.check(other, { ...f.intent, id: 'other' }), /另一台手机/);
  assert.equal(f.guide.notice.id, 'phone');
});
test('first-time missing ADB helper keeps cable setup available without another app consent prompt',async()=>{
  const f=fixture({ready:false,authorized:false});
  await assert.rejects(f.guide.check(f.peer,f.intent),/ADB_SETUP_REQUIRED/);
  const id=f.guide.notice.requestId;assert.equal(f.guide.notice.unauthorized,true);
  const status=await f.guide.status(id);assert.equal(status.revoked,false);assert.match(status.message,/尚未完成 ADB 授权/);
  await f.guide.status(id);assert.equal(f.calls.filter(m=>m==='adb-permission-request').length,0);
  assert.ok(!f.calls.includes('adbBootstrap'));
  f.engine.authorized=true;f.engine.ready=true;assert.equal((await f.guide.complete(id)).id,'phone');
});
test('missing authorization overrides stale readiness; a missing Root helper never enters USB setup',async()=>{
  const revoked=fixture({ready:true,revoked:true});await assert.rejects(revoked.guide.check(revoked.peer,revoked.intent),/ADB_SETUP_REQUIRED/);
  assert.equal((await revoked.guide.status(revoked.guide.notice.requestId)).ready,false);
  const missing=fixture({ready:true,authorized:false,recovering:true});await assert.rejects(missing.guide.check(missing.peer,missing.intent),/ADB_SETUP_REQUIRED/);assert.equal((await missing.guide.status(missing.guide.notice.requestId)).ready,false);
  const root=fixture({root:true,ready:false});await assert.rejects(root.guide.check(root.peer,root.intent),/Root 助手尚未就绪/);assert.equal(root.guide.notice,null);assert.equal(root.calls.at(-1),'permission-prepare');
});
test('Android permission failure after successful preflight reopens setup and releases capture',async()=>{
  for(const failure of ['prepare','acquire','input']){
    const f=fixture({ready:true,authorized:true}),s=f.service;let released=false,captured=false;
    Object.assign(s,{native:{capabilities:{host:true},call:async()=>{captured=true;}},adbGuide:f.guide,access:{check:async()=>{}},assertCanSendControl(){},cancelEdgeFeedback(){},needsFallback:()=>false,active:null,returnLocal:async()=>{s.active=null;released=true;},position:{}});
    f.peer.info.capabilities={controlPrepare:true};
    f.peer.request=async method=>{f.calls.push(method);if(method==='permission-status')return {engine:f.engine};if(method===failure){f.engine.ready=false;f.engine.authorized=false;throw Error('PERMISSION_REQUIRED: ADB 未授权');}return {};};
    await assert.rejects(Service.prototype.switchTo.call(s,'phone','right',true,true),/ADB_SETUP_REQUIRED/);
    assert.equal(f.guide.notice.unauthorized,true);assert.equal(f.guide.notice.capture,true);assert.equal(s.active,null);assert.equal(s.switching,false);assert.equal(captured,false);if(failure==='input')assert.equal(released,true);
    assert.equal(f.calls.filter(m=>m==='adb-permission-request').length,0);
  }
});
test('revoked ADB changes guide and asks phone once without restoring consent remotely',async()=>{
  const f=fixture({ready:false,revoked:true});
  await assert.rejects(f.guide.check(f.peer,f.intent),/ADB_SETUP_REQUIRED/);
  assert.equal(f.guide.notice.revoked,true);assert.equal(f.calls.filter(m=>m==='adb-permission-request').length,1);
  const id=f.guide.notice.requestId;
  assert.equal((await f.guide.status(id)).revoked,true);await f.guide.status(id);
  assert.equal(f.calls.filter(m=>m==='adb-permission-request').length,1);
  assert.ok(!f.calls.includes('adbBootstrap'));assert.equal(f.engine.revoked,true);
  f.engine.revoked=false;assert.equal((await f.guide.status(id)).revoked,false);
  f.guide.cancel(id);assert.ok(f.calls.includes('permission-cancel'));
});
test('cancel ignores stale dialogs, stops edge retriggers and invalidates in-flight checks',async()=>{
  const f=fixture();await assert.rejects(f.guide.check(f.peer,f.intent));const id=f.guide.notice.requestId;
  f.guide.cancel('old');assert.equal(f.guide.notice.requestId,id);
  f.guide.cancel(id);assert.equal(f.guide.notice,null);assert.equal(f.leave(),1);
  assert.equal((await f.guide.status(id)).ended,true);await assert.rejects(f.guide.complete(id),/CONTROL_ENDED/);
  let finish;f.peer.request=()=>new Promise(r=>finish=r);const pending=f.guide.check(f.peer,f.intent);f.guide.cancel();finish({engine:{ready:false}});
  await assert.rejects(pending,/CONTROL_ENDED/);assert.equal(f.guide.notice,null);
});
test('offline and replaced peers cannot authorize or continue a stale request',async()=>{
  const f=fixture();await assert.rejects(f.guide.check(f.peer,f.intent));const id=f.guide.notice.requestId;
  f.service.peers.clear();assert.equal((await f.guide.status(id)).ready,false);await assert.rejects(f.guide.complete(id),/ADB_SETUP_REQUIRED/);
  f.service.peers.set('phone',f.peer);let finish;f.peer.request=()=>new Promise(r=>finish=r);
  const pending=f.guide.complete(id);f.guide.cancel(id);finish({engine:{ready:true}});await assert.rejects(pending,/CONTROL_ENDED/);
});
test('screen and keyboard control cannot acquire input or enable capture before ADB setup',async()=>{
  for(const capture of [true,false]){
    const f=fixture(),s=f.service;
    Object.assign(s,{native:{capabilities:{host:true},call:async()=>{throw Error('must not capture');}},adbGuide:f.guide,assertCanSendControl(){},cancelEdgeFeedback(){},needsFallback:()=>false,active:null});
    await assert.rejects(Service.prototype.switchTo.call(s,'phone','right',capture,true,false,!capture),/ADB_SETUP_REQUIRED/);
    assert.deepEqual(f.calls,['permission-status']);assert.equal(s.switching,false);assert.equal(f.guide.notice.capture,capture);assert.equal(f.guide.notice.screen,!capture);
  }
});
