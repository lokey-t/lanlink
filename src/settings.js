import {commitConfig} from './identity.js';
import {masterChoices,masterSelection,defaultMasterPatch,masterChanged,publishMaster} from './master.js';
import {layoutPatch,legacyPositions,publishLayout,layoutChanged} from './layout.js';
import {languageIds} from './language.js';

// Validate the entire edit before writing or producing any runtime side effects.
export async function applySettings(service,a){
  const c=service.config,patch={};
  const choice=(key,values,message)=>{if(a[key]!==undefined){if(!values.includes(a[key]))throw Error(message);patch[key]=a[key];}};
  choice('theme',['system','dark','light'],'请选择跟随系统、深色或浅色主题');
  choice('language',languageIds,'请选择支持的界面语言');
  choice('switchMode',['dwell','hotkey'],'请选择有效的切换方式');
  choice('edgeHotkey',['ctrl-alt','ctrl-shift','alt-shift','meta-shift'],'请选择有效的快捷键');
  for(const key of ['clipboardUpload','clipboardDownload','clipboardShortcuts','edgeGlow','edgeGlowHideAway','master'])if(a[key]!==undefined){if(typeof a[key]!=='boolean')throw Error('开关设置无效');patch[key]=a[key];}
  if(a.name!==undefined){if(typeof a.name!=='string'||!a.name.trim()||a.name.length>80)throw Error('设备名称应为 1–80 字符');patch.name=a.name.trim();}
  if(a.dwell!==undefined){if(!Number.isInteger(a.dwell)||a.dwell<0||a.dwell>3000)throw Error('边缘等待时间范围为 0–3000 毫秒');patch.dwell=a.dwell;}
  if(a.edges!==undefined){if(!a.edges||Array.isArray(a.edges)||typeof a.edges!=='object'||Object.entries(a.edges).some(([key,value])=>!['left','right','top','bottom'].includes(key)||typeof value!=='string'||value.length>64))throw Error('设备布局无效');patch.edges={...c.edges,...a.edges};}
  if(a.layoutPositions!==undefined&&a.edges!==undefined)throw Error('请只提交一种设备布局');
  if(a.layoutPositions!==undefined)Object.assign(patch,layoutPatch(service,a.layoutPositions));
  else if(a.edges!==undefined)Object.assign(patch,layoutPatch(service,legacyPositions(service,patch.edges)));
  if(a.permissions!==undefined){if(!a.permissions||Array.isArray(a.permissions)||typeof a.permissions!=='object'||Object.entries(a.permissions).some(([key,value])=>!['input','screen','clipboard','shutdown'].includes(key)||typeof value!=='boolean'))throw Error('功能权限无效');patch.permissions={...c.permissions,...a.permissions};}
  const oldChoice=masterSelection(service);
  if(oldChoice)delete patch.master;
  if(a.masterId!==undefined){if(!masterChoices(service).some(d=>d.id===a.masterId))throw Error('请选择本组的 Windows 或 Mac 电脑');patch.masterSelection={group:service.group.data.id,id:a.masterId,revision:Math.max(Date.now(),(oldChoice?.revision||0)+1),origin:c.id};patch.master=a.masterId===c.id;}
  if(!oldChoice&&!patch.masterSelection&&(patch.layout||patch.master===true))Object.assign(patch,defaultMasterPatch(service));
  const renamed=patch.name!==undefined&&patch.name!==c.name;
  await commitConfig(service.dir,c,current=>({...patch,...(renamed?{group:{...current.group,members:{...current.group.members,[c.id]:{...service.group.own,name:patch.name}}}}:{})}));
  service.group.data=c.group;
  const warnings=[];const effect=async work=>{try{await work();}catch(error){warnings.push(error.message);service.log('设置已保存，运行状态恢复失败：'+error.message);}};
  if(renamed){service.group.data.members[c.id]=service.group.own;for(const peer of service.peers.values())try{peer.send({type:'identity',...service.identity()});}catch(error){service.log(error.message);}service.group.publish();}
  if(patch.masterSelection){await effect(()=>masterChanged(service));publishMaster(service);}
  if(patch.layout){await effect(()=>layoutChanged(service));publishLayout(service);}
  if(['switchMode','dwell','edgeHotkey','edgeGlow'].some(k=>a[k]!==undefined)){service.edge.reset();service.cancelEdgeFeedback();}
  if(a.edgeGlow!==undefined||a.edgeGlowHideAway!==undefined){service.emit('edge-feedback',{phase:'clear'});if(service.active&&service.capture&&c.edgeGlow!==false)service.emit('edge-feedback',{phase:'hold',edge:service.enteredEdge,hideAway:!!c.edgeGlowHideAway});}
  if(!c.permissions.input&&service.owner){service.clearInboundEdge();service.owner=null;await effect(()=>service.native.call('release'));}
  if(!c.permissions.screen){await effect(()=>service.videoHost.closeAll());service.screenViewers.clear();}
  if(!c.permissions.clipboard)await effect(()=>service.staging.cancelAll());
  if(!c.permissions.shutdown)service.cancelShutdown();
  if(!c.master&&service.capture)await effect(()=>service.returnLocal());
  service.emit('change');return warnings.length?{message:'设置已保存，部分运行状态未能恢复，请重启服务',warning:warnings.join('；')}:{};
}
