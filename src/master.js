import { commitConfig } from './identity.js';
import {layoutDevices} from './layout.js';

export function masterChoices(service) {
  return layoutDevices(service).filter(device=>['win32','darwin'].includes(device.platform));
}
export function masterSelection(service) {
  const choice=service.config.masterSelection;
  return choice?.group===service.group.data.id ? choice : null;
}
export async function applyMaster(service, choice) {
  return service.configure(async()=>{
    if(!choice || choice.group!==service.group.data.id || !Number.isSafeInteger(choice.revision) || choice.revision<1 || choice.revision>Date.now()+86400000 || !layoutDevices(service).some(d=>d.id===choice.origin) || !masterChoices(service).some(d=>d.id===choice.id))return false;
    const old=masterSelection(service);
    if(old && (old.revision>choice.revision || old.revision===choice.revision && old.origin>=choice.origin))return false;
    await commitConfig(service.dir,service.config,()=>({masterSelection:{group:choice.group,id:choice.id,revision:choice.revision,origin:choice.origin},master:choice.id===service.config.id}));
    await masterChanged(service);publishMaster(service);return true;
  });
}
export function publishMaster(service){const choice=masterSelection(service);if(choice)for(const peer of service.peers.values())if(service.currentPeer(peer)&&!service.group.excluded(peer.remote.id))try{peer.send({type:'master-selection',choice});}catch(error){service.log(error.message);}}
export async function masterChanged(service){
  const choice=masterSelection(service);
  if(!service.config.master){service.cancelPendingEdge();if(service.capture)await service.returnLocal();}
  if(choice&&service.owner?.pointer&&service.owner.id!==choice.id){const owner=service.owner;service.clearInboundEdge();service.owner=null;await service.native.call('release');try{service.peers.get(owner.id)?.send({type:'control-ended',...(owner.edgeSession?{session:owner.edgeSession}:{})});}catch{}}
  service.emit('change');
}
export function defaultMasterPatch(service){
  if(masterSelection(service))return {};
  const choices=masterChoices(service),id=choices.find(device=>device.id===service.config.id)?.id||choices[0]?.id;
  return id?{masterSelection:{group:service.group.data.id,id,revision:1,origin:service.config.id},master:id===service.config.id}:{};
}
export async function initializeMaster(service){
  return service.configure(async()=>{
    const current=service.config.masterSelection;
    if(current?.group===service.group.data.id&&masterChoices(service).some(device=>device.id===current.id)){service.config.master=current.id===service.config.id;return;}
    const patch=current?{masterSelection:null,master:false}:service.config.master?defaultMasterPatch(service):{};
    if(Object.keys(patch).length){await commitConfig(service.dir,service.config,()=>patch);await masterChanged(service);publishMaster(service);}
  });
}
export async function chooseMaster(service,id) {
  if(!masterChoices(service).some(d=>d.id===id))throw Error('请选择本组的 Windows 或 Mac 电脑');
  await applyMaster(service,{group:service.group.data.id,id,revision:Math.max(Date.now(),(masterSelection(service)?.revision||0)+1),origin:service.config.id});
  publishMaster(service);
}
