import { saveConfig } from './identity.js';

export function masterChoices(service) {
  const entries = {...service.config.knownDevices, ...service.group.data.members, [service.config.id]:service.group.own};
  return Object.entries(entries).filter(([id,d]) => !service.group.excluded(id) && ['win32','darwin'].includes(d.platform)).map(([id,d]) => ({id,name:d.name,online:id===service.config.id || service.peers.has(id)}));
}
export function masterSelection(service) {
  const choice=service.config.masterSelection;
  return choice?.group===service.group.data.id ? choice : null;
}
export async function applyMaster(service, choice) {
  if(!choice || choice.group!==service.group.data.id || !Number.isSafeInteger(choice.revision) || choice.revision<1 || choice.revision>Date.now()+86400000 || typeof choice.origin!=='string' || choice.origin.length>64 || !masterChoices(service).some(d=>d.id===choice.id))return false;
  const old=masterSelection(service);
  if(old && (old.revision>choice.revision || old.revision===choice.revision && old.origin>=choice.origin))return false;
  service.config.masterSelection={group:choice.group,id:choice.id,revision:choice.revision,origin:choice.origin};
  service.config.master=choice.id===service.config.id;
  if(!service.config.master){service.cancelPendingEdge();if(service.capture)await service.returnLocal();}
  await saveConfig(service.dir,service.config);service.emit('change');return true;
}
export async function chooseMaster(service,id) {
  if(!masterChoices(service).some(d=>d.id===id))throw Error('请选择本组的 Windows 或 Mac 电脑');
  if(masterSelection(service)?.id===id)return;
  await applyMaster(service,{group:service.group.data.id,id,revision:Math.max(Date.now(),(masterSelection(service)?.revision||0)+1),origin:service.config.id});
  for(const peer of service.peers.values())peer.send({type:'master-selection',choice:masterSelection(service)});
}
