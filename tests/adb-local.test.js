import test from 'node:test';
import assert from 'node:assert/strict';
import {AdbManager} from '../src/adb.js';
const apk='/data/app/com.lanlink.app-fixture/base.apk';
const bootstrap={apkPath:apk,port:41001,secret:'lanlink1:'+'A'.repeat(43),agentVersion:9};
const encode=data=>'Result: Bundle[{data='+Buffer.from(JSON.stringify(data)).toString('base64')+'}]';
test('physical ADB setup does not require or modify group membership or control grants',async()=>{
  let launched=false;const calls=[],config={group:{id:'unchanged'},deviceGrants:{}};
  const manager=new AdbManager({config},{locate:async()=>'/adb',wait:async()=>{},run:async(exe,args)=>{
    calls.push(args);
    if(args[0]==='devices')return {stdout:'phone device model:Phone\n'};
    if(args.includes('content'))return {stdout:encode(args.at(-1)==='bootstrap'?bootstrap:{ready:launched})};
    if(args.includes('pm'))return {stdout:'package:'+apk};
    if(args.includes('ps'))return {stdout:''};
    if(args.at(-1).includes('app_process'))launched=true;
    return {stdout:''};
  }});
  assert.match((await manager.authorizeLocal('phone')).message,/ADB 授权已完成/);
  assert.deepEqual(config,{group:{id:'unchanged'},deviceGrants:{}});assert.equal(calls.filter(a=>a.at(-1).includes('app_process')).length,1);
  await manager.authorizeLocal('phone');assert.equal(calls.filter(a=>a.at(-1).includes('app_process')).length,1);
});
test('local setup rejects untrusted devices and malformed bootstrap before launch',async()=>{
  const manager=new AdbManager({},{locate:async()=>'/adb',run:async()=>({stdout:'phone unauthorized\n'})});
  await assert.rejects(manager.authorizeLocal('phone'),/未连接|USB/);assert.equal(manager.busy,false);
  await assert.rejects(manager.authorizeLocal('x;evil'),/请选择/);
});
