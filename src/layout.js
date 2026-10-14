import {commitConfig} from './identity.js';

export const layoutDirections={left:{x:-1,y:0},right:{x:1,y:0},top:{x:0,y:-1},bottom:{x:0,y:1}};
const object=value=>value!==null&&typeof value==='object'&&!Array.isArray(value);
export function layoutDevices(service){
  const entries={...service.config.knownDevices,...service.group.data.members,[service.config.id]:service.group.own};
  for(const [id,peer]of service.peers)if(Object.hasOwn(entries,id)&&service.currentPeer(peer)&&peer.info)entries[id]={...entries[id],...peer.info};
  return Object.entries(entries).filter(([id])=>!service.group.excluded(id)).map(([id,device])=>({id,name:String(device.name||'设备').slice(0,80),platform:String(device.platform||'').slice(0,30),online:id===service.config.id||!!service.peers.get(id)&&service.currentPeer(service.peers.get(id))})).sort((a,b)=>a.id.localeCompare(b.id,'en'));
}
export function validateLayoutPositions(service,value){
  if(!object(value)||Object.keys(value).length>64)throw Error('设备布局无效，最多支持 64 个位置');
  const known=new Set(layoutDevices(service).map(device=>device.id)),cells=new Set(),positions={};
  for(const [id,point]of Object.entries(value)){
    if(!known.has(id)||!object(point)||![point.x,point.y].every(n=>Number.isInteger(n)&&Math.abs(n)<=32))throw Error('布局只能包含本组设备，坐标范围为 -32 到 32');
    const cell=`${point.x},${point.y}`;if(cells.has(cell))throw Error('此位置已有设备');cells.add(cell);positions[id]={x:point.x,y:point.y};
  }
  return positions;
}
export function layoutSelection(service){
  const value=service.config.layout;
  return value?.group===service.group.data.id?value:{group:service.group.data.id,revision:0,origin:'',positions:{}};
}
export function rememberLayoutVersion(service,peer,value){
  if(!object(value)||value.group!==service.group.data.id||!Number.isSafeInteger(value.revision)||value.revision<0||value.revision>Date.now()+86400000)return;
  if(value.revision===0?value.origin!=='':!layoutDevices(service).some(device=>device.id===value.origin))return;
  peer.layoutVersion={group:value.group,revision:value.revision,origin:value.origin};
}
export function layoutExchange(service,peer){
  const layout=layoutSelection(service),layoutVersion={group:layout.group,revision:layout.revision,origin:layout.origin},known=peer.layoutVersion;
  const confirmed=known?.group===layout.group&&known.revision===layout.revision&&known.origin===layout.origin;
  return {layoutVersion,...(!confirmed&&layout.revision?{layout}:{})};
}
export function layoutEdges(service,id=service.config.id,positions=layoutSelection(service).positions){
  if(!positions?.[id])return id===service.config.id&&layoutSelection(service).revision===0?{...service.config.edges}:{};
  const edges={},origin=positions[id];
  for(const [target,point]of Object.entries(positions))if(target!==id&&!service.group.excluded(target))for(const [edge,delta]of Object.entries(layoutDirections))if(point.x===origin.x+delta.x&&point.y===origin.y+delta.y)edges[edge]=target;
  return edges;
}
export function legacyPositions(service,edges,base=layoutSelection(service).positions,anchor=service.config.id){
  const positions=structuredClone(base),known=new Set(layoutDevices(service).map(device=>device.id));
  if(!known.has(anchor))anchor=service.config.id;
  const center=positions[anchor]||{x:0,y:0};positions[anchor]=center;
  const targets=Object.values(edges).filter(Boolean);
  if(new Set(targets).size!==targets.length||targets.includes(anchor))throw Error('同一设备只能放在一个位置');
  for(const [edge,id]of Object.entries(edges)){
    const delta=layoutDirections[edge];if(!delta)throw Error('设备布局无效');
    const point={x:center.x+delta.x,y:center.y+delta.y};
    for(const [existing,location]of Object.entries(positions))if(existing!==anchor&&location.x===point.x&&location.y===point.y)delete positions[existing];
    if(id)positions[id]=point;
  }
  return validateLayoutPositions(service,positions);
}
export function layoutPatch(service,positions){
  const old=layoutSelection(service),layout={group:service.group.data.id,revision:Math.max(Date.now(),old.revision+1),origin:service.config.id,positions:validateLayoutPositions(service,positions)};
  return {layout,edges:layoutEdges(service,service.config.id,layout.positions)};
}
export function publishLayout(service){const layout=layoutSelection(service);if(!layout.revision)return;for(const peer of service.peers.values())if(service.currentPeer(peer)&&!service.group.excluded(peer.remote.id))try{peer.send({type:'layout-update',layout});}catch(error){service.log(error.message);}}
export async function layoutChanged(service){
  service.cancelPendingEdge();service.edge.reset();service.cancelEdgeFeedback();
  const layout=layoutSelection(service),master=service.config.masterSelection?.id||service.config.id;
  if(layout.revision&&service.capture&&(!layout.positions[service.active]||!layout.positions[master]))await service.returnLocal();
  service.emit('change');
}
export async function initializeLayout(service){
  return service.configure(async()=>{
    const current=service.config.layout,group=service.group.data.id;
    let layout;
    if((!current||current.group===group&&current.revision===0)&&Object.values(service.config.edges||{}).some(Boolean)){
      const anchor=service.config.masterSelection?.group===group?service.config.masterSelection.id:service.config.id;
      try{layout={group,revision:1,origin:service.config.id,positions:legacyPositions(service,service.config.edges,{},anchor)};}catch{layout={group,revision:0,origin:'',positions:{}};}
    }else if(current?.group===group){
      const known=new Set(layoutDevices(service).map(device=>device.id));
      try{const positions=validateLayoutPositions(service,Object.fromEntries(Object.entries(current.positions||{}).filter(([id])=>known.has(id))));layout={...current,positions};
        if(JSON.stringify(positions)!==JSON.stringify(current.positions))layout={...layout,revision:Math.max(Date.now(),current.revision+1),origin:service.config.id};
      }catch{layout={group,revision:0,origin:'',positions:{}};}
    }else layout={group,revision:0,origin:'',positions:{}};
    if(JSON.stringify(current)===JSON.stringify(layout))return;
    const edges=layout.revision?layoutEdges(service,service.config.id,layout.positions):current?{}:service.config.edges||{};
    await commitConfig(service.dir,service.config,()=>({layout,edges}));
    await layoutChanged(service);publishLayout(service);
  });
}
export async function applyLayout(service,value){
  return service.configure(async()=>{
    if(!object(value)||value.group!==service.group.data.id||!Number.isSafeInteger(value.revision)||value.revision<1||value.revision>Date.now()+86400000||!layoutDevices(service).some(device=>device.id===value.origin))return false;
    const old=layoutSelection(service);if(old.revision>value.revision||old.revision===value.revision&&old.origin>=value.origin)return false;
    let positions;try{positions=validateLayoutPositions(service,value.positions);}catch{return false;}
    const layout={group:value.group,revision:value.revision,origin:value.origin,positions};
    await commitConfig(service.dir,service.config,()=>({layout,edges:layoutEdges(service,service.config.id,positions)}));
    await layoutChanged(service);publishLayout(service);return true;
  });
}
// The caller's serialized settings transaction reads the current graph; an old
// window can place one device without replacing another window's newer nodes.
export function editLayoutPositions(service,action){
  const positions=structuredClone(layoutSelection(service).positions);
  if(!layoutDevices(service).some(device=>device.id===action.id))throw Error('请选择本组设备');
  if(action.remove)delete positions[action.id];else positions[action.id]={x:action.x,y:action.y};
  return validateLayoutPositions(service,positions);
}
