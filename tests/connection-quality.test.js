import test from 'node:test';
import assert from 'node:assert/strict';
import {summarizeQuality,analyzeConnection} from '../src/connection-quality.js';
test('quality grades include missing and unstable replies without claiming packet loss',()=>{
 assert.equal(summarizeQuality([]).grade,'无法连接');
 assert.equal(summarizeQuality(Array(10).fill(8)).grade,'良好');
 assert.equal(summarizeQuality([10,110,10,110,10,110,10,110,10,110]).grade,'较差');
 assert.equal(summarizeQuality(Array(8).fill(8)).timeoutPercent,20);
 assert.equal(summarizeQuality([5]).jitterMs,null);
});
test('probe uses encrypted TCP path and prevents overlapping runs',async()=>{
 let count=0;const peer={lanRequest:async(method,args,timeout)=>{assert.equal(method,'linkPing');assert.equal(timeout,1000);count++;},request:()=>{throw Error('wrong route');}};
 const pending=analyzeConnection(peer);
 await assert.rejects(analyzeConnection(peer),/正在分析/);
 assert.equal((await pending).received,10);assert.equal(count,10);
 await assert.rejects(analyzeConnection(null),/未连接/);
});
