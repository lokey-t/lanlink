import {commitConfig} from './identity.js';
import {layoutDevices} from './layout.js';
import {masterSelection} from './master.js';
import {showPermissionGuide} from './permissions.js';

export const mouseDefaults=Object.freeze({smooth:false,reverse:false,sensitivity:1,disableAcceleration:false});
const needsPermission=(platform,caps)=>caps?.input===false||platform==='darwin'&&(caps?.host===false||caps?.inputMonitoring===false);
export async function checkLocalMousePermission(s,requester=s.config.id,name=s.config.name){
  await s.refreshCapabilities();
  if(needsPermission(process.platform,s.native.capabilities)){showPermissionGuide(s,'input',requester,name);return {guideRequired:true};}
  return {guideRequired:false};
}
export async function checkMousePermission(s,id){
  const device=mouseDevices(s).find(d=>d.id===id);if(!device?.supported)throw Error('请选择支持鼠标设置的本组设备');
  if(!device.online)return {offline:true};
  if(id===s.config.id)return checkLocalMousePermission(s);
  const peer=s.getPeer(id);if(peer.info?.capabilities?.mouseFeel!==1)return {upgradeRequired:true};
  return peer.request('mouse-feel-check',{},4000);
}
export function mouseRecords(s){return s.config.mouseFeel?.group===s.group.data.id?Object.fromEntries(Object.entries(s.config.mouseFeel.devices||{}).filter(([id])=>Object.hasOwn(s.group.data.members,id)&&!s.group.excluded(id))):{};}
export function mouseDevices(s){
  const master=masterSelection(s)?.id||(s.config.master?s.config.id:'');
  return layoutDevices(s).filter(d=>Object.hasOwn(s.group.data.members,d.id)).map(d=>{
    const caps=d.id===s.config.id?s.native.capabilities:s.peers.get(d.id)?.info?.capabilities;
    return {...d,master:d.id===master,supported:['win32','darwin'].includes(d.platform),needsPermission:d.online&&needsPermission(d.platform,caps),needsUpgrade:d.online&&caps?.mouseFeel!==1,settings:{...mouseDefaults,...mouseRecords(s)[d.id]?.settings}};
  }).sort((a,b)=>Number(b.master)-Number(a.master)||Number(b.online)-Number(a.online)||a.name.localeCompare(b.name)||a.id.localeCompare(b.id));
}
export function validateMouseSettings(value){
  if(!value||typeof value!=='object'||Array.isArray(value)||Object.keys(value).some(k=>!Object.hasOwn(mouseDefaults,k)))throw Error('鼠标设置无效');
  for(const k of ['smooth','reverse','disableAcceleration'])if(typeof value[k]!=='boolean')throw Error('鼠标开关设置无效');
  if(!Number.isFinite(value.sensitivity)||value.sensitivity<0.2||value.sensitivity>3)throw Error('鼠标灵敏度范围为 0.2–3.0');
  return Object.fromEntries(Object.keys(mouseDefaults).map(k=>[k,value[k]]));
}
export async function applyLocalMouse(s){
  if(s.native.capabilities.mouseFeel!==1)return;
  const record=mouseRecords(s)[s.config.id],settings={...mouseDefaults,...record?.settings},signature=JSON.stringify(settings);
  if(s.mouseFeelApplied!==signature){await s.native.call('mouse-feel',settings);s.mouseFeelApplied=signature;}
  if((settings.smooth||settings.reverse||settings.disableAcceleration||settings.sensitivity!==1)&&needsPermission(process.platform,s.native.capabilities)&&!(s.permissionNotice?.permission==='input'&&s.permissionNotice.expires>Date.now())){
    const origin=record?.origin||s.config.id;showPermissionGuide(s,'input',origin,s.group.data.members[origin]?.name||s.config.name);
  }
}
export async function saveMouseFeel(s,edits){
  if(!Array.isArray(edits)||!edits.length||edits.length>16)throw Error('鼠标设备设置无效');
  const devices=mouseDevices(s),records={...mouseRecords(s)},seen=new Set();
  for(const edit of edits){
    const device=devices.find(d=>d.id===edit?.id);
    if(!device?.supported||seen.has(edit.id))throw Error('请选择支持鼠标设置的本组设备');
    seen.add(edit.id);const settings=validateMouseSettings(edit.settings);
    if(device.platform!=='darwin'&&settings.disableAcceleration)throw Error('此设备不支持关闭鼠标加速度');
    records[edit.id]={settings,revision:Math.max(Date.now(),(records[edit.id]?.revision||0)+1),origin:s.config.id};
  }
  await commitConfig(s.dir,s.config,()=>({mouseFeel:{group:s.group.data.id,devices:records}}));
  await applyLocalMouse(s);publishMouseFeel(s);s.emit('change');return {};
}
export function mouseExchange(s){return {group:s.group.data.id,devices:mouseRecords(s)};}
export function publishMouseFeel(s){for(const p of s.peers.values())if(s.currentPeer(p))try{p.send({type:'mouse-feel',mouseFeel:mouseExchange(s)});}catch(e){s.log(e.message);}}
export async function receiveMouseFeel(s,value){
  if(!value||value.group!==s.group.data.id||!value.devices||typeof value.devices!=='object'||Array.isArray(value.devices)||Object.keys(value.devices).length>16)return;
  return s.configure(async()=>{
    const records={...mouseRecords(s)};let changed=false;
    for(const [id,r]of Object.entries(value.devices)){
      const member=s.group.data.members[id];
      if(!member||s.group.excluded(id)||!['win32','darwin'].includes(member.platform)||!r||!Object.hasOwn(s.group.data.members,r.origin)||s.group.excluded(r.origin)||!Number.isSafeInteger(r.revision)||r.revision<1||r.revision>Date.now()+86400000)continue;
      let settings;try{settings=validateMouseSettings(r.settings);}catch{continue;}
      if(member.platform!=='darwin'&&settings.disableAcceleration)continue;
      const old=records[id];if(old&&(old.revision>r.revision||old.revision===r.revision&&old.origin>=r.origin))continue;
      records[id]={settings,revision:r.revision,origin:r.origin};changed=true;
    }
    if(!changed)return;
    await commitConfig(s.dir,s.config,()=>({mouseFeel:{group:s.group.data.id,devices:records}}));
    await applyLocalMouse(s);publishMouseFeel(s);s.emit('change');
  });
}
