import test from 'node:test';
import assert from 'node:assert/strict';
import {EventEmitter} from 'node:events';
import {randomUUID} from 'node:crypto';
import {mkdtemp,mkdir,readFile,rm} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {Service} from '../src/service.js';
import {newToken} from '../src/identity.js';
import {createActions} from '../src/actions.js';
import {applyLayout,layoutEdges,initializeLayout,layoutExchange,rememberLayoutVersion} from '../src/layout.js';
import {applyMaster} from '../src/master.js';

const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function until(check){const end=Date.now()+5000;while(!check()){if(Date.now()>end)throw Error('layout did not converge');await pause(5);}}
class Native extends EventEmitter {
  capabilities={host:true,input:true,screen:true,clipboard:true,width:1000,height:600};calls=[];
  async start(){}async call(method,args){this.calls.push({method,args});return method==='capabilities'?this.capabilities:{};}close(){}
}
async function mesh(t,legacy=false){
  const dir=await mkdtemp(path.join(os.tmpdir(),'lanlink-layout-')),token=newToken(),ids=Array.from({length:4},()=>randomUUID()),services=[];
  const known=Object.fromEntries(ids.map((id,i)=>[id,{name:`Device ${i}`,platform:i<2?'darwin':'android'}]));
  t.after(async()=>{await Promise.all(services.map(service=>service.close()));await rm(dir,{recursive:true,force:true});});
  for(let i=0;i<ids.length;i++){
    const folder=path.join(dir,String(i));await mkdir(folder);
    const legacyMaster=legacy==='competing'?i<2:legacy&&i===0;
    const config={id:ids[i],name:`Device ${i}`,token,knownDevices:structuredClone(known),edges:legacyMaster?(i===0?{right:ids[1],bottom:ids[2]}:{left:ids[0]}):{},master:legacyMaster,permissions:{input:true,screen:true,clipboard:false,shutdown:false}};
    const service=new Service(config,folder,new Native(),{host:'127.0.0.1',port:0,noDiscovery:true,noQuic:true});services.push(service);await service.start();clearInterval(service.heartbeat);
  }
  for(let i=0;i<services.length;i++)for(let j=i+1;j<services.length;j++)await services[i].connect('127.0.0.1',services[j].port);
  await until(()=>services.every(service=>service.peers.size===3&&[...service.peers.values()].every(peer=>peer.info)));
  return services;
}
async function converge(services,positions){await until(()=>services.every(service=>JSON.stringify(service.state().layout.positions)===JSON.stringify(positions)));}

test('one group shares fixed grid coordinates, cardinal chains and an independent master selection',async t=>{
  const devices=await mesh(t),[a,b,c,d]=devices,actions=createActions(a);
  await actions('layout-place',{id:a.config.id,x:0,y:0});
  await actions('layout-place',{id:b.config.id,x:1,y:0});
  await Promise.all([actions('layout-place',{id:c.config.id,x:2,y:0}),actions('layout-place',{id:d.config.id,x:1,y:1})]);
  const positions=a.state().layout.positions;await converge(devices,positions);
  assert.deepEqual(layoutEdges(a,a.config.id),{right:b.config.id});
  assert.deepEqual(layoutEdges(a,b.config.id),{left:a.config.id,right:c.config.id,bottom:d.config.id});
  assert.deepEqual(layoutEdges(a,d.config.id),{top:b.config.id});
  assert.ok(devices.every(service=>service.state().layoutDevices.length===4));
  await until(()=>devices.every(service=>service.state().masterId===a.config.id));
  await b.settings({masterId:b.config.id});await until(()=>devices.every(service=>service.state().masterId===b.config.id));
  assert.ok(devices.every(service=>JSON.stringify(service.state().layout.positions)===JSON.stringify(positions)),'changing master never translates or rotates the grid');
  assert.equal(a.config.master,false);assert.equal(b.config.master,true);
  // Any member can choose the desktop controller from its layout; the badge
  // derives from the same masterId on every member, independently of the grid.
  await c.settings({masterId:a.config.id});await until(()=>devices.every(service=>service.state().masterId===a.config.id));
  assert.ok(devices.every(service=>JSON.stringify(service.state().layout.positions)===JSON.stringify(positions)));
  assert.ok(devices.every(service=>service.state().masterId===a.config.id));

  await createActions(c)('layout-remove',{id:d.config.id});await converge(devices,c.state().layout.positions);
  assert.equal(a.state().layout.positions[d.config.id],undefined);assert.deepEqual(a.state().layout.positions[b.config.id],{x:1,y:0});
});

test('concurrent layout revisions converge by origin and reconnecting members receive the winner',async t=>{
  const devices=await mesh(t),[a,b,c,d]=devices;
  for(const peer of [...d.peers.values()])peer.close();await until(()=>d.peers.size===0&&devices.slice(0,3).every(service=>!service.peers.has(d.config.id)));
  const revision=Date.now()+1000;
  const low={group:a.group.data.id,revision,origin:[a.config.id,b.config.id].sort()[0],positions:{[a.config.id]:{x:0,y:0},[b.config.id]:{x:1,y:0}}};
  const high={...low,origin:[a.config.id,b.config.id].sort()[1],positions:{...low.positions,[c.config.id]:{x:2,y:0}}};
  await Promise.all([applyLayout(a,low),applyLayout(b,high)]);await converge(devices.slice(0,3),high.positions);
  assert.equal(d.state().layout.revision,0);
  assert.equal(a.state().layoutDevices.find(device=>device.id===d.config.id).online,false);
  await a.settings({masterId:b.config.id});await a.connect('127.0.0.1',d.port);
  await until(()=>d.state().layout.revision===revision&&d.state().masterId===b.config.id);
  assert.deepEqual(d.state().layout.positions,high.positions);
});

test('legacy local edges migrate once around the current master and retain outer nodes on compatibility edits',async t=>{
  const devices=await mesh(t,true),[a,b,c,d]=devices;
  await converge(devices,a.state().layout.positions);
  assert.equal(a.state().layout.revision,1);assert.deepEqual(a.state().layout.positions[a.config.id],{x:0,y:0});
  await createActions(a)('layout-place',{id:d.config.id,x:2,y:0});
  await a.settings({edges:{bottom:''}});await converge(devices,a.state().layout.positions);
  assert.deepEqual(a.state().layout.positions[d.config.id],{x:2,y:0});assert.equal(a.state().layout.positions[c.config.id],undefined);
  const saved=structuredClone(a.config.layout);await initializeLayout(a);assert.deepEqual(a.config.layout,saved);
  const disk=JSON.parse(await readFile(path.join(a.dir,'config.json'),'utf8'));assert.deepEqual(disk.layout,saved);
});

test('invalid, foreign-group and obsolete authenticated layouts cannot mutate memory or disk',async t=>{
  const devices=await mesh(t),[a,b]=devices;await createActions(a)('layout-place',{id:a.config.id,x:0,y:0});await createActions(a)('layout-place',{id:b.config.id,x:1,y:0});await converge(devices,a.state().layout.positions);
  const saved=structuredClone(a.config.layout),actions=createActions(a);
  await assert.rejects(actions('layout-place',{id:b.config.id,x:0,y:0}),/已有设备/);
  await assert.rejects(a.settings({name:'must not persist',layoutPositions:{[randomUUID()]:{x:1,y:1}}}),/本组设备/);assert.equal(a.config.name,'Device 0');
  await assert.rejects(actions('layout-place',{id:b.config.id,x:33,y:0}),/坐标/);
  assert.equal(await applyLayout(a,{...saved,group:'f'.repeat(24),revision:saved.revision+1}),false);
  assert.equal(await applyLayout(a,{...saved,revision:saved.revision-1}),false);
  const peer=a.getPeer(b.config.id);await a.message({...peer,closed:false},{type:'layout-update',layout:{...saved,revision:saved.revision+10,positions:{}}});
  assert.deepEqual(a.config.layout,saved);
  await a.leaveGroup();assert.equal(a.state().layout.revision,0);assert.deepEqual(a.state().layout.positions,{});assert.equal(a.state().masterId,'');
  assert.equal(await applyLayout(a,{...saved,revision:saved.revision+100}),false);
});

test('competing legacy masters elect one authority without repeatedly moving the migrated layout',async t=>{
  const devices=await mesh(t,'competing'),[a,b]=devices,winner=[a.config.id,b.config.id].sort().at(-1);
  await until(()=>devices.every(service=>service.state().masterId===winner&&service.state().layout.origin===winner));
  const layout=structuredClone(a.state().layout);
  assert.equal(devices.filter(service=>service.config.master).length,1);assert.equal(layout.revision,1);
  await Promise.all(devices.map(service=>initializeLayout(service)));await converge(devices,layout.positions);
  assert.ok(devices.every(service=>service.state().layout.revision===1));
});

test('explicitly confirming the current master supersedes a delayed legacy election',async t=>{
  const devices=await mesh(t,'competing'),[a,b]=devices;
  await until(()=>devices.every(service=>service.state().masterId===a.state().masterId));
  const current=a.state().masterId,prior=a.config.masterSelection.revision;
  await a.settings({masterId:current});assert.ok(a.config.masterSelection.revision>prior);
  assert.equal(await applyMaster(a,{group:a.group.data.id,id:b.config.id,revision:1,origin:b.config.id}),false);
  await until(()=>devices.every(service=>service.state().masterId===current));
});

test('membership removal prunes old layout positions and unrelated edits preserve active capture',async t=>{
  const devices=await mesh(t),[a,b,c,d]=devices,actions=createActions(a);
  await actions('layout-place',{id:a.config.id,x:0,y:0});
  await actions('layout-place',{id:b.config.id,x:1,y:0});await actions('layout-place',{id:d.config.id,x:3,y:0});await converge(devices,a.state().layout.positions);
  await a.switchTo(b.config.id,'right',true);
  await actions('layout-place',{id:c.config.id,x:2,y:0});assert.equal(a.active,b.config.id);assert.equal(a.capture,true);
  await actions('layout-remove',{id:b.config.id});assert.equal(a.active,null);assert.equal(b.owner,null);
  await a.group.remove(d.config.id);
  assert.equal(a.state().layout.positions[d.config.id],undefined);assert.equal(a.state().layoutDevices.some(device=>device.id===d.config.id),false);
  assert.ok(a.state().layout.positions[c.config.id]);
});

test('an empty shared layout places only the selected device without inventing a master position',async t=>{
  const devices=await mesh(t),[a,b]=devices;
  await createActions(a)('layout-place',{id:b.config.id,x:0,y:0});await converge(devices,a.state().layout.positions);
  assert.deepEqual(a.state().layout.positions,{[b.config.id]:{x:0,y:0}});
  assert.equal(a.state().masterId,a.config.id,'master selection is independent of grid coordinates');
  await createActions(a)('layout-place',{id:a.config.id,x:-1,y:0});await converge(devices,a.state().layout.positions);
  assert.deepEqual(layoutEdges(a),{right:b.config.id});
});

test('old authenticated connections cannot restore members or use default grants after leaving a group',async t=>{
  const [a,b]=await mesh(t),peer=a.getPeer(b.config.id),identity=b.identity();
  const pending=a.message(peer,{type:'identity',...identity,name:'Delayed old identity'});
  await a.leaveGroup();await pending;
  await a.message(peer,{type:'identity',...identity});
  assert.equal(a.config.knownDevices[b.config.id],undefined);
  assert.equal(a.group.data.members[b.config.id],undefined);
  await assert.rejects(a.handle(peer,'acquire',{pointer:false}),/连接已结束/);
  assert.throws(()=>a.authorize(peer,'clipboard'),/连接已结束/);
  assert.equal(a.owner,null);
});

test('rotating the group credential invalidates old business requests before old sockets close',async t=>{
  const [a,b]=await mesh(t),peer=a.getPeer(b.config.id),group=a.group.data.id;
  a.config.token=newToken();
  assert.equal(a.group.data.id,group,'same-group rotation must still retire the old credential');
  await assert.rejects(a.handle(peer,'acquire',{pointer:false}),/连接已结束/);
  assert.throws(()=>a.authorize(peer,'clipboard'),/连接已结束/);
  await a.message(peer,{type:'layout-update',layout:{group,revision:Date.now(),origin:b.config.id,positions:{[b.config.id]:{x:0,y:0}}}});
  assert.equal(a.state().layout.revision,0);assert.equal(a.owner,null);
});

test('layout summaries omit confirmed graphs and retry rejected graphs after membership arrives',async t=>{
  const devices=await mesh(t),[a,b,c]=devices,peer=a.getPeer(b.config.id);
  await createActions(a)('layout-place',{id:c.config.id,x:0,y:0});await converge(devices,a.state().layout.positions);
  assert.ok(layoutExchange(a,peer).layout,'no implicit acknowledgement from layout-update');
  rememberLayoutVersion(a,peer,{...a.config.layout,group:'old-group'});assert.ok(layoutExchange(a,peer).layout);
  await a.tick();await until(()=>!layoutExchange(a,peer).layout);
  // Model a member learning the graph before the corresponding membership.
  const member=b.group.data.members[c.config.id],known=b.config.knownDevices[c.config.id];
  delete b.group.data.members[c.config.id];delete b.config.knownDevices[c.config.id];
  const next={...a.config.layout,revision:a.config.layout.revision+1,positions:{[a.config.id]:{x:0,y:0},[c.config.id]:{x:1,y:0}}};
  await applyLayout(a,next);await pause(30);assert.notEqual(b.config.layout.revision,next.revision);
  await b.tick();await until(()=>peer.layoutVersion?.revision===b.config.layout.revision);
  assert.ok(layoutExchange(a,peer).layout,'a rejected graph never produces a false confirmation');
  b.group.data.members[c.config.id]=member;b.config.knownDevices[c.config.id]=known;
  await a.tick();await until(()=>b.config.layout.revision===next.revision);
  await b.tick();await until(()=>!layoutExchange(a,peer).layout);
  peer.close();await until(()=>!a.peers.has(b.config.id)&&!b.peers.has(a.config.id));
  await a.connect('127.0.0.1',b.port);
  assert.ok(layoutExchange(a,a.getPeer(b.config.id)).layout,'a new connection must confirm its own graph');
});
