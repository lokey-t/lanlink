// Disposable protocol vectors, never user credentials. Packaged only in the test APK.
import { mkdtemp, mkdir, writeFile, readFile, rm } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import os from 'node:os';
import path from 'node:path';
import assert from 'node:assert/strict';
import { Group } from '../src/group.js';
import { newToken } from '../src/identity.js';

const folder=await mkdtemp(path.join(os.tmpdir(),'lanlink-group-vector-'));
try {
  if(process.argv.includes('--verify')) {
    const fixture=JSON.parse(await readFile('.tools/group-interop-state.json','utf8'));
    const s={config:fixture.a,dir:folder,peers:new Map(),async groupChanged(){},options:{noDiscovery:true}};
    s.group=new Group(s); const box=JSON.parse(await readFile('.tools/group-interop-result.json','utf8'));
    s.group.data.revision=0;
    assert.equal(s.group.decodeBox(box).token,fixture.expected);
    s.group.verifyDissolution(JSON.parse(await readFile('.tools/group-dissolution-result.json','utf8')));
    console.log('PASS: Android signed whole-group dissolution verified by Node');
    console.log('PASS: Android RSA-OAEP/SHA-256, RSA signature and AES-GCM envelope verified by Node');
  } else {
    const token=newToken(), list=[];
    for(let i=0;i<4;i++) { const s={config:{id:randomUUID(),name:'Protocol fixture '+i,token,edges:{},deviceGrants:{},knownDevices:{}},dir:path.join(folder,String(i)),peers:new Map(),videoHost:{closePeer(){}},native:{async call(){}},async groupChanged(){},options:{noDiscovery:true}}; s.group=new Group(s);list.push(s); }
    for(const s of list) for(const other of list) if(s!==other) s.group.learn(other.group.own);
    const [a,b,c,d]=list; delete b.group.data.members[d.config.id];delete b.group.data.anchors[d.config.id]; const before=structuredClone(b.config); await a.group.admit(d.group.own); d.group.adopt(structuredClone(a.group.export()),a.config.token); await d.group.remove(c.config.id);
    const fixture={recipient:before,box:d.group.boxes.get(b.config.id),expected:d.config.token,removed:c.config.id, dissolution:d.group.dissolutionRecord()};
    await mkdir('android/app/src/androidTest/assets',{recursive:true});
    await writeFile('android/app/src/androidTest/assets/group-vector.json',JSON.stringify(fixture));
    await writeFile('.tools/group-interop-state.json',JSON.stringify({a:d.config,expected:d.config.token}));
    console.log('Disposable group protocol vectors generated');
  }
} finally { await rm(folder,{recursive:true,force:true}); }
