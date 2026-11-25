import test from 'node:test';
import assert from 'node:assert/strict';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {mkdtemp,rm} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
const run=promisify(execFile);

test('macOS permission revocation and disabled taps fail open and end capture despite a live lease', {skip:process.platform!=='darwin',timeout:120000}, async t=>{
  const dir=await mkdtemp(path.join(os.tmpdir(),'lanlink-input-safety-'));t.after(()=>rm(dir,{recursive:true,force:true}));
  const binary=path.join(dir,'probe');
  await run('xcrun',['swiftc','-swift-version','5','-D','LANLINK_BRIDGE_TEST','-module-cache-path',path.join(os.tmpdir(),'lanlink-native-latency-cache'),...['AppKit','CoreGraphics','ScreenCaptureKit','CoreBluetooth','AVFoundation','VideoToolbox'].flatMap(name=>['-framework',name]),...['Bridge','BridgeOutput','BluetoothHid','StreamAudio','StreamVideo'].map(name=>'native/macos/'+name+'.swift'),'tests/MacInputSafetyProbe.swift','-o',binary],{timeout:110000});
  const result=await run(binary,[],{timeout:10000});
  assert.match(result.stdout,/MAC_INPUT_SAFETY_OK/);
});
