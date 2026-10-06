import {createRequire} from 'node:module';
import {MAX_VIDEO} from './video-wire.js';
const require=createRequire(import.meta.url);

const PART=12000,HEADER=16;
export function splitRtcFrame(frame,id){
  if(!['h264','jpeg','ping'].includes(frame.codec)||frame.data.length>MAX_VIDEO-16||frame.width<1||frame.width>4096||frame.height<1||frame.height>4096||frame.width*frame.height>8388608)throw Error('RTC 画面无效');
  const header=Buffer.alloc(16);header[0]=frame.codec==='h264'?2:frame.codec==='jpeg'?1:3;header[1]=frame.key?1:0;header.writeUInt16BE(frame.width,2);header.writeUInt16BE(frame.height,4);header.writeBigUInt64BE(BigInt(Math.floor(frame.time??Date.now())),8);
  const body=Buffer.concat([header,frame.data]),count=Math.ceil(body.length/PART),parts=[];
  for(let index=0;index<count;index++){const offset=index*PART,p=Buffer.alloc(HEADER+Math.min(PART,body.length-offset));p.writeUInt32BE(id,0);p.writeUInt16BE(index,4);p.writeUInt16BE(count,6);p.writeUInt32BE(body.length,8);p.writeUInt32BE(offset,12);body.copy(p,HEADER,offset,offset+PART);parts.push(p);}return parts;
}
export class RtcAssembler{
  constructor(now=Date.now){this.now=now;this.parts=new Map();this.last=-1;this.needKey=true;}
  push(packet){
    if(!Buffer.isBuffer(packet)||packet.length<HEADER||packet.length>HEADER+PART)throw Error('RTC 分片无效');
    const id=packet.readUInt32BE(0),index=packet.readUInt16BE(4),count=packet.readUInt16BE(6),total=packet.readUInt32BE(8),offset=packet.readUInt32BE(12);
    if(total<16||total>MAX_VIDEO||count!==Math.ceil(total/PART)||index>=count||offset!==index*PART||packet.length-HEADER!==Math.min(PART,total-offset))throw Error('RTC 分片长度无效');
    for(const [key,value] of this.parts)if(this.now()-value.at>200){this.parts.delete(key);this.needKey=true;}
    if(id<=this.last)return null;
    let entry=this.parts.get(id);if(!entry){if(this.parts.size>=4){this.parts.delete(this.parts.keys().next().value);this.needKey=true;}entry={at:this.now(),total,count,seen:new Set(),data:Buffer.alloc(total)};this.parts.set(id,entry);}
    if(entry.total!==total||entry.count!==count)throw Error('RTC 分片冲突');if(entry.seen.has(index))return null;
    packet.copy(entry.data,offset,HEADER);entry.seen.add(index);if(entry.seen.size!==count)return null;this.parts.delete(id);
    const b=entry.data,codec=b[0]===2?'h264':b[0]===1?'jpeg':b[0]===3?'ping':'',key=!!b[1],width=b.readUInt16BE(2),height=b.readUInt16BE(4);
    if(!codec||width<1||height<1||width>4096||height>4096||width*height>8388608)throw Error('RTC 尺寸无效');
    if(this.last>=0&&id!==this.last+1)this.needKey=true;this.last=id;
    if(codec==='h264'&&this.needKey&&!key)return null;if(codec!=='ping')this.needKey=false;
    for(const old of this.parts.keys())if(old<=id)this.parts.delete(old);
    return {codec,key,width,height,time:Number(b.readBigUInt64BE(8)),data:b.subarray(16)};
  }
}
function timeout(work,ms,label){let timer;return Promise.race([work,new Promise((_,reject)=>timer=setTimeout(()=>reject(Error(label)),ms))]).finally(()=>clearTimeout(timer));}
// Retain a short reference chain through scheduler hiccups. Dropping one P-frame
// invalidates all following P-frames, so overflow must explicitly request an IDR.
export class RtcFrameQueue {
  constructor(assembler,now=Date.now){this.assembler=assembler;this.now=now;this.frames=[];this.bytes=0;}
  clear(){this.frames=[];this.bytes=0;}
  push(frame){
    if(frame.codec==='ping'){if(this.frames.length)return;}
    else if(frame.codec==='jpeg'||frame.key)this.clear();
    if(this.frames.length>=8||this.bytes+frame.data.length>4*1024*1024||this.frames.length&&this.now()-this.frames[0].at>150){this.clear();if(frame.codec==='h264'&&!frame.key){this.assembler.needKey=true;return;}}
    this.frames.push({frame,at:this.now()});this.bytes+=frame.data.length;
  }
  shift(){const value=this.frames.shift();if(!value)return null;this.bytes-=value.frame.data.length;return value.frame;}
}
export class RtcVideo{
  constructor(){
    const {RTCPeerConnection}=require('werift');
    // No external STUN/TURN: pairing and ICE signaling remain inside the group.
    this.pc=new RTCPeerConnection({iceServers:[],iceAdditionalHostAddresses:['127.0.0.1'],iceUseIpv6:false});
    this.assembler=new RtcAssembler();this.queue=new RtcFrameQueue(this.assembler);this.seq=0;this.waiter=null;this.channels=new Map();this.closed=false;
    this.pc.ondatachannel=event=>this.attach(event.channel);
    this.pc.onconnectionstatechange=()=>{if(['failed','closed'].includes(this.pc.connectionState))this.close();};
  }
  attach(channel){
    if(!['ll-video','ll-key'].includes(channel.label)){channel.close();return;}
    this.channels.set(channel.label,channel);
    channel.onmessage=event=>{try{const frame=this.assembler.push(Buffer.from(event.data));if(frame)this.queue.push(frame);if(this.assembler.last>=0&&this.assembler.needKey&&Date.now()-(this.keyAt||0)>500){this.keyAt=Date.now();this.onNeedKey?.();}if(this.waiter&&this.queue.frames.length){const resolve=this.waiter;this.waiter=null;resolve(this.queue.shift());}}catch{this.close();}};
  }
  lanOnly(){for(const transport of this.pc.iceTransports)transport.connection.stunServer=undefined;}
  // werift 0.24.4's ICE layer supplies Google STUN even for iceServers: [].
  // Explicitly clear that fallback before gathering, keeping the product offline.
  async offer(){this.attach(this.pc.createDataChannel('ll-video',{ordered:false,maxRetransmits:0}));this.attach(this.pc.createDataChannel('ll-key',{ordered:true}));this.lanOnly();await timeout(this.pc.setLocalDescription(await this.pc.createOffer()),3500,'RTC 协商超时');return this.pc.localDescription.sdp;}
  async answer(sdp){await timeout(this.pc.setRemoteDescription({type:'offer',sdp}),3500,'RTC 协商失败');this.lanOnly();await timeout(this.pc.setLocalDescription(await this.pc.createAnswer()),3500,'RTC 协商超时');return this.pc.localDescription.sdp;}
  async accept(sdp){await this.pc.setRemoteDescription({type:'answer',sdp});}
  async ready(){await timeout(new Promise((resolve,reject)=>{const tick=()=>{if(this.closed)return reject(Error('RTC 已关闭'));if(this.channels.size===2&&[...this.channels.values()].every(c=>c.readyState==='open'))return resolve();setTimeout(tick,20);};tick();}),3500,'RTC 连接超时');}
  get queued(){return [...this.channels.values()].reduce((n,c)=>n+c.bufferedAmount,0);}
  send(frame){if(this.closed)throw Error('RTC 已关闭');const channel=this.channels.get(frame.key?'ll-key':'ll-video');if(!channel||channel.readyState!=='open')return false;if(this.queued>128*1024)return false;for(const packet of splitRtcFrame(frame,this.seq++))channel.send(packet);return true;}
  async next(){if(this.closed)throw Error('RTC 串流已关闭');const frame=this.queue.shift();if(frame)return frame;return timeout(new Promise((resolve,reject)=>{this.waiter=frame=>frame?resolve(frame):reject(Error('RTC 串流已关闭'));}),5000,'RTC 等待画面超时').finally(()=>this.waiter=null);}
  close(){if(this.closed)return;this.closed=true;this.queue.clear();this.waiter?.(null);this.waiter=null;this.pc.close().catch(()=>{});}
}
