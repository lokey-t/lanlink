import assert from 'node:assert/strict';
import {EventEmitter} from 'node:events';
import {mkdtemp,mkdir,rm} from 'node:fs/promises';
import path from 'node:path';
import {randomUUID} from 'node:crypto';
import {Service} from '/Users/alice/Documents/项目/LLM/project/lanlink/src/service.js';
import {newToken} from '/Users/alice/Documents/项目/LLM/project/lanlink/src/identity.js';
class Native extends EventEmitter {
  capabilities={host:true,input:true,screen:true,clipboard:true};clip={text:'',files:[]};writes=0;
  async start(){}close(){}
  copy(text){this.clip={text,files:[]};this.emit('event',{type:'clipboardChanged'});}
  async call(method,args){if(method==='clipboardGet')return this.clip;if(method==='clipboardSet'){this.clip={text:args.text||'',files:args.files||[]};this.writes++;queueMicrotask(()=>this.emit('event',{type:'clipboardChanged'}));}return {};}
}
const pause=ms=>new Promise(r=>setTimeout(r,ms));async function until(fn){let end=Date.now()+4000;while(!fn()){if(Date.now()>end)throw Error('timeout');await pause(10);}}
const folder=await mkdtemp('/tmp/lanlink-xyx-'),token=newToken(),services=[];
try{
 for(const name of ['A','B']){const dir=path.join(folder,name);await mkdir(dir);const s=new Service({id:randomUUID(),name,token,permissions:{input:true,screen:true,clipboard:true},edges:{}},dir,new Native(),{port:0,host:'127.0.0.1',noDiscovery:true});services.push(s);await s.start();}
 const [a,b]=services;a.connect('127.0.0.1',b.port);await until(()=>a.peers.size&&b.peers.size);
 a.native.copy('X: frequently reused text');await until(()=>b.native.clip.text==='X: frequently reused text');console.log('STEP1: A user-copy X -> B = X');
 b.native.copy('Y: another device text');await until(()=>a.native.clip.text==='Y: another device text');console.log('STEP2: B user-copy Y -> A = Y');
 a.native.copy('X: frequently reused text');await pause(1500);assert.equal(a.native.clip.text,'X: frequently reused text');assert.equal(b.native.clip.text,'Y: another device text');
 console.log('STEP3: A user-copy X again -> A = X, B = Y; A shared latest = '+a.clipboardSync.latest.text);
 console.log('CONFIRMED: automatic repeated-content synchronization fails using native clipboardChanged events and encrypted TCP peers; no real clipboard read/write occurred.');
}finally{await Promise.all(services.map(s=>s.close()));await rm(folder,{recursive:true,force:true});}
