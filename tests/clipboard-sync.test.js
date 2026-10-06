import test from 'node:test';
import assert from 'node:assert/strict';
import {EventEmitter} from 'node:events';
import {randomUUID} from 'node:crypto';
import {mkdtemp,mkdir,rm,writeFile} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {Service} from '../src/service.js';
import {newToken,loadConfig} from '../src/identity.js';
import {createActions} from '../src/actions.js';

const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function until(fn){const end=Date.now()+4000;while(!fn()){if(Date.now()>end)throw Error('clipboard condition timed out');await delay(10);}}
class ClipboardNative extends EventEmitter{
  constructor(){super();this.capabilities={host:true,input:true,screen:true,clipboard:true};this.clip={text:'',files:[]};this.writes=0;}
  async start(){}
  async call(method,args){if(method==='clipboardGet')return this.clip;if(method==='clipboardSet'){this.clip={text:args.text||'',files:args.files||[]};this.writes++;queueMicrotask(()=>this.emit('event',{type:'clipboardChanged'}));}return {};}
  copy(text){this.clip={text,files:[]};this.emit('event',{type:'clipboardChanged'});}
  close(){}
}
async function devices(t,n=2){const folder=await mkdtemp(path.join(os.tmpdir(),'lanlink-clips-')),token=newToken(),list=[];t.after(async()=>{await Promise.all(list.map(s=>s.close()));await rm(folder,{recursive:true,force:true});});for(let i=0;i<n;i++){const dir=path.join(folder,String(i));await mkdir(dir);const service=new Service({id:randomUUID(),name:['A','B','C'][i],token,permissions:{input:true,screen:true,clipboard:true},edges:{}},dir,new ClipboardNative(),{host:'127.0.0.1',port:0,noDiscovery:true});list.push(service);await service.start();}for(let i=1;i<n;i++)list[i-1].connect('127.0.0.1',list[i].port);await until(()=>list.every(s=>s.peers.size>0));await until(()=>list.every(s=>[...s.peers.values()].every(p=>p.info)));return list;}

test('group text auto sync works before input consent and system writes do not echo',async t=>{
  const [a,b]=await devices(t);assert.equal(a.state().clipboardSettings.upload,true);assert.equal(b.state().clipboardSettings.download,true);
  a.native.copy('第一条');await until(()=>b.native.clip.text==='第一条');await delay(80);
  assert.equal(a.clipboardSync.list().length,1);assert.equal(b.clipboardSync.list().length,1);assert.equal(a.clipboardSync.list()[0].id,b.clipboardSync.list()[0].id);
  assert.equal(a.consent.allowed(b.config.id,'input'),false);assert.equal(b.native.writes,1);
  b.native.copy('第二条');await until(()=>a.native.clip.text==='第二条');assert.equal(a.clipboardSync.list()[0].source,b.config.id);assert.equal(a.clipboardSync.list().length,2);
});

test('upload/download toggles persist locally and manual sync overrides each toggle',async t=>{
  const [a,b]=await devices(t);await a.settings({clipboardUpload:false,clipboardShortcuts:true});await b.settings({clipboardDownload:false});
  a.native.copy('手动上传');await delay(60);assert.equal(b.clipboardSync.latest,null);
  const actions=createActions(a);assert.match((await actions('clipboard-upload',{})).message,/上传/);await until(()=>b.clipboardSync.latest?.text==='手动上传');assert.equal(b.native.clip.text,'');
  const result=await createActions(b)('clipboard-download',{});assert.match(result.message,/本机/);assert.equal(b.native.clip.text,'手动上传');
  assert.equal((await loadConfig(a.dir)).clipboardUpload,false);assert.equal((await loadConfig(b.dir)).clipboardDownload,false);assert.equal(b.state().clipboardSettings.upload,true);assert.equal(b.state().clipboardSettings.shortcuts,false);
});

test('home clipboard shortcuts require a manual direction and retain the saved preference',async t=>{
  const [a]=await devices(t);await a.settings({clipboardShortcuts:true});
  assert.equal(a.state().clipboardSettings.shortcuts,true);assert.equal(a.state().clipboardSettings.showShortcuts,false);
  await a.settings({clipboardUpload:false});assert.equal(a.state().clipboardSettings.showShortcuts,true);
  await a.settings({clipboardUpload:true,clipboardDownload:false});assert.equal(a.state().clipboardSettings.showShortcuts,true);
  await a.settings({clipboardDownload:true});assert.equal(a.state().clipboardSettings.showShortcuts,false);
  await a.settings({clipboardUpload:false});assert.equal(a.state().clipboardSettings.showShortcuts,true);
  await a.settings({clipboardShortcuts:false});assert.equal(a.state().clipboardSettings.showShortcuts,false);
});

test('explicit clipboard denial prevents live and incremental sync while input stays separate',async t=>{
  const [a,b]=await devices(t);b.config.deviceGrants[a.config.id]={input:true,clipboard:false};a.native.copy('不可共享');await delay(100);assert.equal(b.clipboardSync.list().length,0);assert.equal(b.native.clip.text,'');
  a.clipboardSync.sendInventory(a.getPeer(b.config.id));await delay(50);assert.equal(b.clipboardSync.list().length,0);assert.equal(b.consent.allowed(a.config.id,'input'),true);
});

test('offline copies merge incrementally in both directions without duplicating records',async t=>{
  const [a,b]=await devices(t);a.native.copy('连接时');await until(()=>b.clipboardSync.list().length===1);a.getPeer(b.config.id).close();await until(()=>!a.peers.size&&!b.peers.size);
  a.native.copy('A 离线复制');await until(()=>a.clipboardSync.list().length===2);b.native.copy('B 离线复制');await until(()=>b.clipboardSync.list().length===2);
  a.connect('127.0.0.1',b.port);await until(()=>a.peers.size&&b.peers.size);await until(()=>a.clipboardSync.list().length===3&&b.clipboardSync.list().length===3);
  assert.deepEqual(a.clipboardSync.list().map(r=>r.id),b.clipboardSync.list().map(r=>r.id));await a.tick();await b.tick();await delay(100);assert.equal(a.clipboardSync.list().length,3);assert.equal(b.clipboardSync.list().length,3);
});

test('history relays missing text to indirectly connected group members with source preserved',async t=>{
  const [a,b,c]=await devices(t,3);c.config.knownDevices[a.config.id]={name:'A',platform:'win32'};a.native.copy('经 B 补齐');await until(()=>b.clipboardSync.list().length===1);await b.tick();await until(()=>c.clipboardSync.list().length===1);assert.equal(c.native.clip.text,'经 B 补齐');assert.equal(c.clipboardSync.list()[0].source,a.config.id);
});

test('history keeps newest 30, expires at ten minutes, and copies complete long text',async t=>{
  const [a,b]=await devices(t);let now=Date.now();a.clipboardSync.now=()=>now;
  for(let i=0;i<35;i++)a.clipboardSync.add({id:String(i).padStart(2,'0'),source:a.config.id,name:'A',text:'记录'+i,at:now+i});
  assert.equal(a.clipboardSync.list().length,30);assert.equal(a.clipboardSync.list()[0].id,'34');assert.equal(a.clipboardSync.list().at(-1).id,'05');
  now+=600000+35;assert.equal(a.clipboardSync.list().length,0);await assert.rejects(a.clipboardSync.copy('34'),/过期/);
  a.clipboardSync.now=()=>Date.now();const text='完整文字'.repeat(900);b.native.copy(text);await until(()=>a.clipboardSync.list().length===1);
  assert.equal(a.state().clipboardSettings.history[0].text.length,2000);await a.clipboardSync.copy(b.clipboardSync.latest.id);assert.equal(a.native.clip.text,text);
});

test('expired, future, unknown and oversized records are excluded from incremental history',async t=>{
  const [a,b]=await devices(t);const record={id:'r',source:b.config.id,name:'B',text:'内容',at:Date.now()};
  assert.equal(a.clipboardSync.add({...record,at:Date.now()-600001}),false);assert.equal(a.clipboardSync.add({...record,at:Date.now()+60000}),false);assert.equal(a.clipboardSync.add({...record,source:randomUUID()}),false);assert.equal(a.clipboardSync.add({...record,text:'x'.repeat(1000001)}),false);
  assert.equal(a.clipboardSync.add({...record,files:[{id:'f',name:'文件',size:0}]}),false);assert.equal(a.clipboardSync.list().length,0);
});

test('manual upload of unchanged local text reuses its shared record',async t=>{
  const [a,b]=await devices(t);a.native.copy('重复上传');await until(()=>b.clipboardSync.list().length===1);
  const id=a.clipboardSync.latest.id;await a.clipboardSync.uploadNow();await a.clipboardSync.uploadNow();await delay(80);
  assert.equal(a.clipboardSync.latest.id,id);assert.equal(b.clipboardSync.list().length,1);
  const report=await a.getPeer(b.config.id).request('permission-status',{});assert.equal(report.permissions.clipboard.peerAllowed,true);assert.equal(report.permissions.input.peerAllowed,null);
});

test('slow automatic file transfer cannot overwrite a newer local clipboard copy',async t=>{
  const [a,b]=await devices(t);const location=path.join(a.dir,'slow.txt');await writeFile(location,'文件内容');
  let release,started=false;const gate=new Promise(resolve=>release=resolve),original=a.fileBytes.bind(a);
  a.fileBytes=async args=>{started=true;await gate;return original(args);};
  a.native.clip={text:'',files:[location]};a.native.emit('event',{type:'clipboardChanged'});await until(()=>started);
  b.native.copy('新的本机内容');await until(()=>b.clipboardSync.latest?.text==='新的本机内容');release();await until(()=>!b.receiving);await delay(100);
  assert.equal(b.native.clip.text,'新的本机内容');assert.deepEqual(b.native.clip.files,[]);
});
