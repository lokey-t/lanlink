import assert from 'node:assert/strict';
import {EventEmitter} from 'node:events';
import {mkdtemp,mkdir,writeFile,rm} from 'node:fs/promises';
import {randomUUID} from 'node:crypto';
import path from 'node:path';
import {Service} from '/Users/alice/Documents/项目/LLM/project/lanlink/src/service.js';
import {newToken} from '/Users/alice/Documents/项目/LLM/project/lanlink/src/identity.js';
class Native extends EventEmitter {
  capabilities={input:true,screen:true,clipboard:true,host:true};clip={text:'',files:[]};
  async call(method,args){if(method==='clipboardGet')return this.clip;if(method==='clipboardSet')this.clip={text:args.text||'',files:args.files||[]};return {};}
}
const root=await mkdtemp('/tmp/lanlink-crosscheck-'),token=newToken(),wire=[];
async function make(name){const dir=path.join(root,name);await mkdir(dir);return new Service({id:randomUUID(),name,token,permissions:{input:true,screen:true,clipboard:true,shutdown:true},edges:{}},dir,new Native());}
try{
 const a=await make('A'),b=await make('B');
 const fromA={remote:{id:a.config.id},info:{name:'A'},closed:false,request:(method,args)=>a.fileChunk(args)};
 const fromB={remote:{id:b.config.id},info:{name:'B'},closed:false,request:(method,args)=>b.fileChunk(args)};
 fromA.send=m=>{if(m.type==='clipboard')wire.push(a.clipboardSync.receive(fromB,m.offer));};
 fromB.send=m=>{if(m.type==='clipboard')wire.push(b.clipboardSync.receive(fromA,m.offer));};
 a.peers.set(b.config.id,fromB);b.peers.set(a.config.id,fromA);
 const drain=async()=>{while(wire.length)await Promise.all(wire.splice(0));};
 async function userCopies(s,text){s.native.clip={text,files:[]};await s.nativeEvent({type:'clipboardChanged'});await drain();}
 await userCopies(a,'X');assert.equal(b.native.clip.text,'X');
 await userCopies(b,'Y');assert.equal(a.native.clip.text,'Y');
 await userCopies(a,'X');assert.equal(b.native.clip.text,'Y');assert.equal(a.clipboardSync.latest.text,'Y');
 // A repeated OS change notification also cannot recover the missed update.
 await a.nativeEvent({type:'clipboardChanged'});await drain();assert.equal(b.native.clip.text,'Y');
 console.log(JSON.stringify({case:'text-return-via-nativeEvent',confirmed:true,aClipboard:a.native.clip.text,bClipboard:b.native.clip.text,latest:a.clipboardSync.latest.text,scope:'actual Service/nativeEvent and ClipboardSync; simulated native clipboard and in-memory peer delivery'}));
 const file=path.join(a.dir,'sample.txt');await writeFile(file,'test payload');
 b.config.clipboardDownload=false;a.native.clip={text:'',files:[file]};
 await a.clipboardSync.uploadNow();await drain();const original=a.clipboardSync.latest;
 const first=await a.fileBytes({offerId:original.id,fileId:original.files[0].id,offset:0});assert.equal(first.toString(),'test payload');
 for(let i=0;i<20;i++){await a.clipboardSync.uploadNow();await drain();}
 assert.equal(a.clipboardSync.latest.id,original.id);assert.equal(b.clipboardSync.latest.id,original.id);
 assert.equal(a.offers.has(original.id),false);
 await assert.rejects(b.clipboardSync.apply(b.clipboardSync.latest),/文件分享已过期/);
 console.log(JSON.stringify({case:'file-repeated-upload-then-recipient-download',confirmed:true,uploads:21,latestOfferMissing:true,scope:'actual Service file transfer and ClipboardSync; receiver automatic download disabled, simulated native clipboard and in-memory requests'}));
}finally{await rm(root,{recursive:true,force:true});}
