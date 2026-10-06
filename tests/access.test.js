import test from 'node:test';
import assert from 'node:assert/strict';
import {EventEmitter} from 'node:events';
import {Access,accessState} from '../src/access.js';
import {Consent} from '../src/consent.js';
import {AdbManager} from '../src/adb.js';

const report=(grant=null)=>({permissions:Object.fromEntries(['input','screen','clipboard','shutdown'].map(k=>[k,{supported:true,appEnabled:true,systemGranted:true,peerAllowed:grant}]))});
test('device states separate readiness from full, absent and limited grants; Android excludes shutdown',()=>{
  const r=report(true);r.permissions.shutdown.supported=false;
  assert.equal(accessState(r,'android').status,'在线');r.permissions.input.peerAllowed=false;assert.equal(accessState(r,'android').status,'有限授权');
  for(const p of Object.values(r.permissions))p.peerAllowed=false;
  assert.equal(accessState(r,'android').status,'未授权');r.permissions.input.systemGranted=false;assert.equal(accessState(r,'android').status,'未就绪');
  assert.equal(accessState(null,'win32').status,'未就绪');assert.equal(Object.keys(accessState(r,'android').permissions).length,3);
});
function fixture(r=report()){
  const calls=[],s=new EventEmitter();const peer={info:{name:'Phone',platform:'android'},request:async(m,a)=>{calls.push(m);return m==='permission-status'?r:{};}};
  s.peers=new Map([['phone',peer]]);s.getPeer=id=>s.peers.get(id);s.edge={waitForLeave(){}};
  const access=new Access(s);return {s,peer,access,calls,r};
}
test('feature preflight is read-only until explicit consent and blocks before opening a viewer',async()=>{
  const f=fixture();f.s.adbGuide={check:async()=>{throw Error('ADB_SETUP_REQUIRED: setup');}};
  assert.deepEqual(await f.access.prepare('phone',['input','screen']),{ready:false,guide:true});
  assert.deepEqual(f.calls,[]);assert.equal(f.access.notice,null);
  f.s.adbGuide.check=async()=>{};
  assert.deepEqual(await f.access.prepare('phone',['input','screen']),{ready:true,allowed:false});
  assert.deepEqual(f.calls,['permission-status']);assert.equal(f.access.notice,null);
  f.r.permissions.input.peerAllowed=true;f.r.permissions.screen.peerAllowed=false;
  assert.equal((await f.access.prepare('phone',['input','screen'])).allowed,false);
  f.r.permissions.screen.peerAllowed=true;
  assert.equal((await f.access.prepare('phone',['input','screen'])).allowed,true);
  f.r.permissions.screen.systemGranted=false;
  await assert.rejects(f.access.prepare('phone',['input','screen']),/PERMISSION_REQUIRED/);
  assert.equal(f.calls.at(-1),'permission-prepare');assert.ok(!f.calls.includes('permission-request'));
});
test('USB detection explains outdated Android instead of claiming no cable',async()=>{
  const peer={info:{platform:'android'}};
  const adb=new AdbManager({getPeer:()=>peer,links:{verifyUsb:async()=>false}},
    {locate:async()=>'/adb',run:async(exe,args)=>({stdout:args[0]==='devices'?'phone device model:Xiaomi\n':'versionCode=20 versionName=0.3.18'})});
  const result=await adb.match('phone');assert.equal(result.connected,false);assert.match(result.message,/版本过旧/);
});
test('readiness precedes consent; only authorized required features may proceed',async()=>{
  const f=fixture();f.r.permissions.input.systemGranted=false;
  await assert.rejects(f.access.check(f.peer,{id:'phone',screen:true}),/PERMISSION_REQUIRED/);assert.deepEqual(f.calls,['permission-status','permission-prepare']);
  f.r.permissions.input.systemGranted=true;f.calls.length=0;
  await assert.rejects(f.access.check(f.peer,{id:'phone',capture:true}),/CONSENT_PENDING/);
  const id=f.access.notice.requestId;await assert.rejects(f.access.check(f.peer,{id:'phone'}),/CONSENT_PENDING/);assert.equal(f.calls.filter(x=>x==='permission-request').length,1);
  f.r.permissions.input.peerAllowed=true;assert.equal((await f.access.complete(id)).capture,true);
  await f.access.check(f.peer,{id:'phone'});await assert.rejects(f.access.check(f.peer,{id:'phone',screen:true}),/CONSENT_DENIED/);
});
test('explicit upgrade preserves existing grants until user approves, cancellation and denial end pending flows',async()=>{
  const c=new Consent({phone:{input:true,screen:false}});c.request('phone','Phone');const n=c.list()[0];assert.equal(c.allowed('phone','screen'),false);c.decide('phone',n.requestId,false,{});assert.equal(c.allowed('phone','input'),true);
  const f=fixture();const notice=await f.access.request('phone',['screen']);f.access.cancel('stale');assert.equal(f.access.notice,notice);f.access.cancel(notice.requestId);assert.equal(f.access.notice,null);assert.ok(f.calls.includes('permission-cancel'));
  const g=fixture();const n2=await g.access.request('phone',['input']);g.r.requestRejected=true;assert.equal((await g.access.status(n2.requestId)).ended,true);
});
test('automatic ADB selection verifies paired identity and never uses first/model matches',async()=>{
  const tried=[],peer={info:{platform:'android',name:'Phone'}};
  const service={getPeer:()=>peer,links:{verifyUsb:async(id,serial)=>{tried.push(serial);return serial==='right-phone';}}};
  const adb=new AdbManager(service,{locate:async()=>'/adb',run:async()=>({stdout:'other-phone device model:Same_Model\nright-phone device model:Same_Model\n'})});
  assert.equal((await adb.match('target')).serial,'right-phone');assert.deepEqual(tried,['other-phone','right-phone']);
  service.links.verifyUsb=async()=>false;assert.equal((await adb.match('target')).connected,false);
});

test('group clipboard defaults do not skip first input consent or end its pending request',async()=>{
  const f=fixture();f.r.permissions.clipboard.peerAllowed=true;f.r.requestPending=false;
  await assert.rejects(f.access.check(f.peer,{id:'phone'}),/CONSENT_PENDING/);
  assert.ok(f.calls.includes('permission-request'));assert.equal((await f.access.status(f.access.notice.requestId)).ended,false);
});
