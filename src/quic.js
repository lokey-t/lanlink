import {randomBytes,randomUUID,createHash} from 'node:crypto';
import path from 'node:path';
import {QuicNative,quicPath,isQuicBusy} from './quic-native.js';
import {MotionSender,MotionReceiver} from './quic-motion.js';
import {encodeMotion,decodeMotion} from './motion-wire.js';
import {RtcFrameQueue} from './rtc-video.js';
import {controlMethods} from './links.js';
import {InputQueue,motionRttSample} from './input-queue.js';
const notifyTypes=new Set(['clipboard','clipboard-record','clipboard-inventory','cursor','group-members','group-dissolved','group-removed','control-ended','master-selection','layout-update','edge-glow']);
const wire=v=>Buffer.from(JSON.stringify(v)).toString('base64');
const controlPriority=method=>controlMethods.has(method)||method==='linkPing'?0:127;
export class QuicManager {
  constructor(service){this.service=service;this.connections=new Map();this.offers=new Map();this.opening=new Set();this.videos=new Map();this.enabled=false;}
  async start(){if(this.service.options?.noQuic||!quicPath())return;try{this.native=new QuicNative();this.info=await this.native.start(path.join(this.service.dir,'quic'),this.service.options?.host?this.service.options.host+':0':'0.0.0.0:0');this.enabled=true;
    this.native.on('connected',e=>{const p=this.offers.get(e.owner);if(p)this.bind(p,e.conn);});
    this.native.on('request',e=>this.incoming(e).catch(error=>{if(process.env.LANLINK_QUIC_DEBUG)process.stderr.write('QUIC incoming failure: '+error.message+' conn '+e.conn+' known '+this.connections.has(e.conn)+'\n');this.native.call('close',{conn:e.conn}).catch(()=>{});}));
    this.native.on('motion',e=>this.motion(e).catch(()=>{}));
    this.native.on('datagram',e=>this.motion(e).catch(()=>{}));this.native.on('video',e=>this.videos.get(e.conn+':'+e.channel)?.receive(e));
    this.native.on('stats',e=>{this.connections.get(e.conn)?.inputScheduler?.feedback({queued:e.queued});for(const video of this.videos.values())if(video.conn===e.conn)video.queued=e.queued;});
    this.native.on('closed',e=>{if(!e){this.enabled=false;for(const p of this.connections.values())this.drop(p);}else{const p=this.connections.get(e.conn);if(p)this.drop(p);}});
    this.timer=setInterval(()=>{for(const p of this.service.peers.values()){if(p.quicConn)this.rpc(p,'linkPing',{},2500).catch(error=>{if(!isQuicBusy(error))this.drop(p);});else this.connect(p).catch(()=>{});}},4000);this.timer.unref();
  }catch(e){this.enabled=false;this.native?.close();this.service.log('QUIC 不可用，使用兼容通道：'+e.message);}}
  valid(p){return this.enabled&&!this.service.closing&&!this.service.changingGroup&&!p.closed&&this.service.peers.get(p.remote.id)===p&&!this.service.group.excluded(p.remote.id);}
  async offer(p){if(!this.valid(p))throw Error('QUIC 不可用');const owner=randomUUID(),ticket=randomBytes(32).toString('hex');this.offers.set(owner,p);const timer=setTimeout(()=>this.offers.delete(owner),6500);timer.unref();await this.native.call('ticket',{owner,ticket});return {...this.info,ticket,protocol:1};}
  async connect(p){if(!this.valid(p)||p.fastLink||p.quicConn||this.opening.has(p)||p.info?.capabilities?.quic!==1||this.service.config.id>p.remote.id||(p.quicRetry||0)>Date.now())return;
    this.opening.add(p);let owner;try{const info=await (p.tcpRequest||p.lanRequest||p.request.bind(p))('quicOpen',{},3000);if(!this.valid(p))return;
      if(info.protocol!==1||!Number.isInteger(info.port)||info.port<1||info.port>65535||!/^[a-f0-9]{64}$/.test(info.fingerprint)||!/^[a-f0-9]{64}$/.test(info.ticket))throw Error('QUIC 协商无效');
      const host=p.socket.remoteAddress,address=host.includes(':')?'['+host+']:'+info.port:host+':'+info.port;owner=randomUUID();this.offers.set(owner,p);
      const result=await this.native.call('connect',{address,owner,fingerprint:info.fingerprint,ticket:info.ticket},6000);if(this.valid(p))this.bind(p,result.conn);else await this.native.call('close',{conn:result.conn});
    }catch{p.quicRetry=Date.now()+15000;}finally{if(owner)this.offers.delete(owner);this.opening.delete(p);}}
  bind(p,conn){if(!this.valid(p)){this.native.call('close',{conn}).catch(()=>{});return;}if(p.quicConn===conn)return;if(p.quicConn&&p.quicConn!==conn){this.native.call('close',{conn}).catch(()=>{});return;}p.quicConn=conn;p.quicTx=new MotionSender();p.quicRx=new MotionReceiver();p.inputTx=0;p.inputRx=0;p.heldMotion=null;this.connections.set(conn,p);
    if(!p.tcpRequest){p.tcpRequest=p.lanRequest||p.request.bind(p);p.lanRequest=(method,args={},timeout)=>p.quicConn&&method!=='quicOpen'?this.rpc(p,method,args,timeout):p.tcpRequest(method,args,timeout);
      const send=p.send.bind(p);p.send=value=>{if(p.quicConn&&notifyTypes.has(value.type)){this.rpc(p,'__notify',value,5000).catch(error=>{if(!isQuicBusy(error))this.drop(p);if(!p.closed)send(value);});}else send(value);};
      p.once('closed',()=>this.drop(p));
    }this.service.emit('change');}
  modern(p){return !!(p.quicConn&&this.native?.binaryMotion&&p.info?.capabilities?.motionV2===2);}
  async rpc(p,method,args={},timeout=15000){if(!this.valid(p)||!p.quicConn)throw Error('QUIC 连接已结束');const conn=p.quicConn;
    if(method==='acquire'||method==='inputReset'||method==='release'){p.quicTx.reset();p.inputTx=0;args={...args,__motionEpoch:p.quicTx.epoch};}
    if(method==='input'&&['move','relative'].includes(args.kind)){const data=p.quicTx.move(args);if(this.modern(p)){data.afterInput=p.inputTx;try{this.native.sendMotion(conn,encodeMotion(data));}catch(e){if(!isQuicBusy(e)){this.drop(p);throw e;}p.inputScheduler?.feedback({queued:131072});}return {};}try{const result=await this.native.call('datagram',{conn,data:wire(data)},timeout);if(p.quicConn===conn)p.inputScheduler?.feedback({queued:result.queued||0});return {};}catch(e){if(isQuicBusy(e)||/\bDone\b|datagram.*(?:full|congest)/i.test(e.message)){p.inputScheduler?.feedback({queued:131072});return {};}this.drop(p);throw e;}}
    if(method==='input'&&this.modern(p))args={...args,__inputSeq:++p.inputTx,__inputEpoch:p.quicTx.epoch};
    if(method==='input'&&p.quicTx.snapshot)args={...args,__motion:p.quicTx.barrier()};
    const started=performance.now();let r;try{r=await this.native.call('request',{conn,priority:controlPriority(method),data:wire({method,args})},timeout+250);}catch(error){if(method==='input'&&this.modern(p))this.drop(p);throw error;}if(p.quicConn===conn&&motionRttSample(method,args))p.inputScheduler?.feedback({rttMs:performance.now()-started});const data=Buffer.from(r.data,'base64');
    if(method==='quicFile'){if(data[0]!==0)throw Error(data.subarray(1).toString()||'QUIC 文件传输失败');if(data.length<33||!createHash('sha256').update(data.subarray(33)).digest().equals(data.subarray(1,33)))throw Error('QUIC 文件校验失败');return data.subarray(33);}const result=JSON.parse(data);if(result.error){if(method==='input'&&this.modern(p))this.drop(p);throw Error(result.error);}return result.result;
  }
  inputQueue(p){return p.quicInputs ||= new InputQueue(async(method,a)=>a.motion?(p.quicConn===a.conn?this.applyMotion(p,a.motion):undefined):a.run());}
  track(task){return this.service.trackTask?.(task)||task;}
  ordered(p,work){return this.inputQueue(p).request('__control',{run:work});}
  async applyMotion(p,m){if(!this.valid(p)||!p.quicConn)return;
    if(m.afterInput!==undefined){
      if(m.epoch!==p.quicRx.epoch||!Number.isSafeInteger(m.afterInput)||m.afterInput<0||m.afterInput>p.inputRx+1)return;
      if(m.afterInput>p.inputRx){if(!p.heldMotion||m.seq>p.heldMotion.seq)p.heldMotion=m;return;}
    }
const inputs=p.quicRx.accept(m);if(this.service.motionInputs)await this.service.motionInputs(p,inputs);else for(const input of inputs)await this.service.handle(p,'input',input);}
  motion(e){return this.track(this.receiveMotion(e));}
  async receiveMotion(e){const p=this.connections.get(e.conn);if(!p||!this.valid(p)||p.quicConn!==e.conn)return;const m=e.bytes?decodeMotion(e.bytes):JSON.parse(Buffer.from(e.data,'base64'));await this.inputQueue(p).request('input',{kind:m.kind,motion:m,conn:e.conn});}
  incoming(e){return this.track(this.receiveRequest(e));}
  async receiveRequest(e){const p=this.connections.get(e.conn);if(!p||!this.valid(p)||p.quicConn!==e.conn){if(process.env.LANLINK_QUIC_DEBUG)process.stderr.write('QUIC rejected state '+JSON.stringify({conn:e.conn,current:p?.quicConn,enabled:this.enabled,closing:this.service.closing,changing:this.service.changingGroup,closed:p?.closed,primary:p&&this.service.peers.get(p.remote.id)===p,excluded:p&&this.service.group.excluded(p.remote.id)})+'\n');throw Error('QUIC 会话已结束');}const m=JSON.parse(Buffer.from(e.data,'base64'));let bytes;
    const run=async()=>{if(!this.valid(p)||p.quicConn!==e.conn)throw Error('设备连接已结束');const a={...m.args};
      if(['acquire','inputReset','release'].includes(m.method)){p.quicRx.reset();p.inputRx=0;p.heldMotion=null;if(typeof a.__motionEpoch==='string'&&a.__motionEpoch.length===36)p.quicRx.epoch=a.__motionEpoch;delete a.__motionEpoch;}
      const sequence=a.__inputSeq;
      if(m.method==='input'&&sequence!==undefined)this.service.requireOwner?.(p);
      if(sequence!==undefined&&(!Number.isSafeInteger(sequence)||sequence!==p.inputRx+1||a.__inputEpoch!==p.quicRx.epoch))throw Error('输入顺序无效');
      delete a.__inputSeq;delete a.__inputEpoch;
      if(a.__motion){await this.applyMotion(p,a.__motion);delete a.__motion;}
      if(m.method==='quicFile'){this.service.authorize(p,'clipboard');return await (this.service.bulk?.quicBytes(p,a)||this.service.fileBytes(a,p.remote.id));}
      if(m.method==='__notify'){if(!notifyTypes.has(a.type))throw Error('QUIC 消息无效');p.emit('message',a);return {};}
      const result=await this.service.handle(p,m.method,a);
      if(sequence!==undefined){p.inputRx=sequence;const held=p.heldMotion;p.heldMotion=null;if(held)await this.applyMotion(p,held);}
      return result;
    };
    // Heartbeats only renew the existing owner; a slow native acknowledgement
    // must not hold the position/button ordering queue on the receiving side.
    try{const result=controlMethods.has(m.method)&&m.method!=='keepControl'?await this.ordered(p,run):await run();bytes=m.method==='quicFile'?Buffer.concat([Buffer.from([0]),createHash('sha256').update(result).digest(),result]):Buffer.from(JSON.stringify({result}));}
    catch(error){bytes=m.method==='quicFile'?Buffer.concat([Buffer.from([1]),Buffer.from(error.message)]):Buffer.from(JSON.stringify({error:error.message}));}
    if(this.valid(p))await this.native.call('reply',{conn:e.conn,stream:e.stream,data:bytes.toString('base64')});
  }
  video(p,channel){if(!this.valid(p)||!p.quicConn)throw Error('QUIC 串流未就绪');const key=p.quicConn+':'+channel;this.videos.get(key)?.close();const video=new QuicVideo(this,p,p.quicConn,channel);this.videos.set(key,video);return video;}
  drop(p){if(!p?.quicConn)return;const conn=p.quicConn;p.quicConn=0;p.quicRetry=Date.now()+15000;this.connections.delete(conn);for(const [key,v]of this.videos)if(v.conn===conn){v.close();this.videos.delete(key);}this.native?.call('close',{conn},1000).catch(()=>{});
    const cleanup=[this.service.handle(p,'release',{})];if(p.tcpRequest&&!p.closed)cleanup.push(p.tcpRequest('release',{},1500));if(this.service.active===p.remote.id)cleanup.push(this.service.returnLocal());this.service.emit('change');return this.track(Promise.allSettled(cleanup));}
  async reset(){this.offers.clear();const work=[...this.connections.values()].map(p=>this.drop(p));this.native?.call('resetTickets').catch(()=>{});await Promise.all(work);}
  close(){clearInterval(this.timer);this.enabled=false;this.reset().catch(()=>{});this.native?.close();}
}
export class QuicVideo {
  constructor(manager,peer,conn,channel){this.manager=manager;this.peer=peer;this.conn=conn;this.channel=channel;this.assembler={needKey:true};this.queue=new RtcFrameQueue(this.assembler);this.last=-1;this.queued=0;this.inflight=0;this.closed=false;}
  receive(e){if(this.closed)return;const b=Buffer.from(e.data,'base64');if(b.length<16)return;const seq=e.sequence;if(seq<=this.last)return;if(this.last>=0&&seq!==this.last+1)this.assembler.needKey=true;this.last=seq;const codec=['','jpeg','h264','ping'][b[0]],key=!!b[1],width=b.readUInt16BE(2),height=b.readUInt16BE(4);
    if(!codec||width<1||height<1||width>4096||height>4096||width*height>8388608)return;if(codec==='h264'&&this.assembler.needKey&&!key){this.key();return;}if(codec!=='ping')this.assembler.needKey=false;
    this.queue.push({codec,key,width,height,time:Number(b.readBigUInt64BE(8)),data:b.subarray(16)});if(this.assembler.needKey)this.key();if(this.waiter&&this.queue.frames.length){const resolve=this.waiter;this.waiter=null;resolve(this.queue.shift());}}
  key(){if(Date.now()-(this.keyAt||0)>500){this.keyAt=Date.now();this.onNeedKey?.();}}
  send(frame){if(this.closed||this.inflight>=4)return false;const b=Buffer.alloc(16);b[0]=frame.codec==='h264'?2:frame.codec==='jpeg'?1:3;b[1]=frame.key?1:0;b.writeUInt16BE(frame.width,2);b.writeUInt16BE(frame.height,4);b.writeBigUInt64BE(BigInt(Math.floor(frame.time??Date.now())),8);const data=Buffer.concat([b,frame.data]);this.inflight++;this.queued+=data.length;
    this.manager.native.call('video',{conn:this.conn,channel:this.channel,data:data.toString('base64')},3000).then(r=>{this.queued=Math.max(0,r.queued);},()=>this.onSendDrop?.()).finally(()=>{this.inflight--;});return true;}
  async next(){if(this.closed)throw Error('QUIC 串流已关闭');const f=this.queue.shift();if(f)return f;if(this.assembler.needKey)this.key();return new Promise((resolve,reject)=>{const t=setTimeout(()=>{this.waiter=null;reject(Error('QUIC 等待画面超时'));},5000);this.waiter=f=>{clearTimeout(t);f?resolve(f):reject(Error('QUIC 串流已关闭'));};});}
  close(){if(this.closed)return;this.closed=true;this.waiter?.(null);this.waiter=null;this.queue.clear();const key=this.conn+':'+this.channel;if(this.manager.videos.get(key)===this)this.manager.videos.delete(key);}
}
