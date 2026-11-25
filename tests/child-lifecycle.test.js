import test from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {once} from 'node:events';
import {stopChild} from '../src/child-lifecycle.js';
import {NativeBridge} from '../src/native.js';

async function child(t, script) {
 const process = spawn(globalThis.process.execPath, ['-e',script], {windowsHide:true,stdio:['pipe','pipe','ignore']});
 t.after(async () => { if (process.exitCode === null && process.signalCode === null) { const done=once(process,'close'); process.kill(); await done; } });
 await once(process.stdout,'data'); return process;
}

test('native shutdown waits for a helper to finish cleanup after stdin closes', async t => {
 const helper=await child(t, "process.stdin.resume();process.stdin.on('end',()=>setTimeout(()=>process.exit(0),300));console.log('ready')");
 const bridge=new NativeBridge({disabled:true}); bridge.hidChild=helper;
 let closed=false; helper.once('close',()=>closed=true);
 const closing=bridge.close(); assert.equal(closing,bridge.close());
 await closing; assert.equal(closed,true); assert.equal(helper.exitCode,0);
 await assert.rejects(bridge.call('capabilities'),/服务停止/);
});

test('a helper ignoring stdin EOF is terminated and reaped within the shutdown budget', async t => {
 const helper=await child(t,"process.stdin.resume();process.stdin.on('end',()=>{});setInterval(()=>{},1000);console.log('ready')");
 let closed=false; helper.once('close',()=>closed=true);
 await stopChild(helper,{graceMs:50,killMs:2000}); assert.equal(closed,true);
});
