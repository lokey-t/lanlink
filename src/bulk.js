import net from 'node:net';
import {withAbort} from './abort.js';
import {randomBytes,createHash,createCipheriv,createDecipheriv,timingSafeEqual} from 'node:crypto';
import {BinaryReader,serverHandshake,hmac} from './video-wire.js';

export const BULK_CHUNK=192*1024;
export function bulkBudget(service){
  const media=[...(service.videoHost?.sessions.values()||[]),...(service.videoViewer?.sessions.values()||[])];
  return media.length?Math.max(8192,Math.min(128*1024,Math.min(...media.map(v=>v.adaptive?.rate||v.bitrate||4000000))/8*.15)):service.active||service.owner?1024*1024:32*1024*1024;
}
// Small slices keep control latency low and traffic moving within socket idle limits.
export function bulkSlice(rate,maxBytes=BULK_CHUNK){return Math.max(1,Math.min(BULK_CHUNK,maxBytes,Math.max(128,Math.floor(rate/10))));}
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,Math.max(1,ms)));
const receivers=new WeakMap();
function receiverLease(service,reuse){let active=receivers.get(service);if(!active){active=new Set();receivers.set(service,active);}const ticket=reuse||Symbol('file');active.add(ticket);return {ticket,close(){if(!reuse)active.delete(ticket);}};}
function receiveRate(service){return Math.max(128,Math.floor(bulkBudget(service)/Math.max(1,receivers.get(service)?.size||0)));}
function requestedRate(rate){if(rate===undefined)return 32*1024*1024;if(!Number.isSafeInteger(rate)||rate<128||rate>32*1024*1024)throw Error('文件速率无效');return rate;}
function requestedBytes(bytes){if(bytes===undefined)return BULK_CHUNK;if(!Number.isSafeInteger(bytes)||bytes<1||bytes>BULK_CHUNK)throw Error('文件块大小无效');return bytes;}
async function drain(socket){if(socket.destroyed)throw Error('文件连接已结束');await new Promise((resolve,reject)=>{const done=()=>finish(),closed=()=>finish(Error('文件连接已结束')),error=e=>finish(e);function finish(e){socket.off('drain',done);socket.off('close',closed);socket.off('error',error);e?reject(e):resolve();}socket.once('drain',done);socket.once('close',closed);socket.once('error',error);});}
const nonce=n=>{const b=Buffer.alloc(12);b.writeBigUInt64BE(BigInt(n),4);return b;};
export function packBulk(key,seq,data){
  if(data.length>BULK_CHUNK)throw Error('文件块过大');
  const c=createCipheriv('aes-256-gcm',key,nonce(seq));c.setAAD(Buffer.from('lanlink-bulk-v1:'+seq));
  const body=Buffer.concat([c.update(Buffer.concat([createHash('sha256').update(data).digest(),data])),c.final(),c.getAuthTag()]);
  const size=Buffer.alloc(4);size.writeUInt32BE(body.length);return Buffer.concat([size,body]);
}
export function unpackBulk(key,seq,body){
  if(body.length<48 || body.length>BULK_CHUNK+48)throw Error('文件块长度无效');
  const c=createDecipheriv('aes-256-gcm',key,nonce(seq));c.setAAD(Buffer.from('lanlink-bulk-v1:'+seq));c.setAuthTag(body.subarray(-16));
  const plain=Buffer.concat([c.update(body.subarray(0,-16)),c.final()]),data=plain.subarray(32);
  if(!createHash('sha256').update(data).digest().equals(plain.subarray(0,32)))throw Error('文件校验失败');return data;
}
export async function connectBulk(host,info,signal){
  if(info.protocol!==1 || !Number.isInteger(info.port) || info.port<1 || info.port>65535 || !/^[a-f0-9]{64}$/.test(info.key))throw Error('文件连接无效');
  signal?.throwIfAborted();const socket=net.connect({host,port:info.port});const aborted=()=>socket.destroy();signal?.addEventListener('abort',aborted,{once:true});socket.once('close',()=>signal?.removeEventListener('abort',aborted));socket.setNoDelay(true);socket.setTimeout(6000,()=>socket.destroy());socket.on('error',()=>{});
  try{const reader=new BinaryReader(socket),secret=Buffer.from(info.key,'hex'),sn=await reader.read(32),cn=randomBytes(32);
    socket.write(Buffer.concat([cn,hmac(secret,'auth',sn,cn)]));if(!(await reader.read(32)).equals(hmac(secret,'ack',sn,cn)))throw Error('文件连接认证失败');
    const key=hmac(secret,'media',sn,cn);let seq=0,finished=false;
    return {socket,async next(){const size=(await reader.read(4)).readUInt32BE();return unpackBulk(key,seq++,await reader.read(size));},complete(end){finished=true;if(info.completeAck)socket.write(hmac(key,'bulk-complete:'+end));},close(){if(finished)socket.end();else socket.destroy();}};
  }catch(error){socket.destroy();throw error;}
}
export async function receiveBulk(service,sourceId,args,end,write,progress,reservation,signal){
  const lease=receiverLease(service,reservation);let offset=args.offset,retries=0;
  try{while(offset<end){signal?.throwIfAborted();let connection,route;
    try{const peer=service.getPeer(sourceId),maxRate=receiveRate(service),info=await withAbort(peer.request('bulkOpen',{...args,offset,end,completeAck:true,maxRate}),signal);signal?.throwIfAborted();route=await service.links?.videoEndpoint(peer,info);signal?.throwIfAborted();connection=await connectBulk(route?.host||peer.socket.remoteAddress,route?.info||info,signal);
      while(offset<end){signal?.throwIfAborted();service.allow('clipboard');if(!service.canShareClipboard(sourceId)||service.group.excluded(sourceId))throw Error('剪贴板共享未授权');
        const rate=receiveRate(service);
        // Re-negotiate a bounded record size when remote control starts/stops on
        // another device; keep the verified offset and never count this as failure.
        if(rate<maxRate*.75||rate>maxRate*1.5)break;
        const started=performance.now(),data=await connection.next();service.allow('clipboard');if(!service.canShareClipboard(sourceId)||service.group.excluded(sourceId))throw Error('剪贴板共享未授权');if(!data.length||offset+data.length>end)throw Error('文件传输校验失败');await write(data);offset+=data.length;progress(offset);
        if(offset<end){const wait=data.length*1000/rate-(performance.now()-started);if(wait>0)await sleep(Math.min(data.length>bulkSlice(rate)?250:1000,wait));}
      }if(offset===end)connection.complete(end);
    }catch(error){signal?.throwIfAborted();const transient=['ECONNRESET','ETIMEDOUT','ECONNREFUSED','EPIPE'].includes(error.code)||/连接已断开|设备不在线|串流连接已结束|响应超时|文件连接已结束/.test(error.message);if(!transient||retries++>=2)throw error;await sleep(500);}
    finally{connection?.close();await route?.close();}
  }}finally{lease.close();}
}
export async function receiveQuicFile(service,source,args,end,write,progress,signal){
  const lease=receiverLease(service);let offset=args.offset;
  try{while(offset<end){
    signal?.throwIfAborted();if(!source.quicConn||source.fastLink){await receiveBulk(service,source.remote.id,{...args,offset},end,write,progress,lease.ticket,signal);return;}
    service.allow('clipboard');if(!service.canShareClipboard(source.remote.id)||service.group?.excluded(source.remote.id))throw Error('剪贴板共享未授权');
    const rate=receiveRate(service),maxBytes=bulkSlice(rate),started=performance.now();
    let data;try{data=await withAbort(service.quic.rpc(source,'quicFile',{...args,offset,end,maxBytes,maxRate:rate}),signal);signal?.throwIfAborted();}catch(error){if(/QUIC (连接|响应|connection|request)|QUIC 已关闭/.test(error.message)){service.quic.drop(source);await receiveBulk(service,source.remote.id,{...args,offset},end,write,progress,lease.ticket,signal);return;}throw error;}
    service.allow('clipboard');if(!service.canShareClipboard(source.remote.id)||service.group?.excluded(source.remote.id))throw Error('剪贴板共享未授权');
    if(!data.length||data.length>BULK_CHUNK||offset+data.length>end)throw Error('文件传输校验失败');await write(data);offset+=data.length;progress(offset);
    // Count request and disk time toward the receiver's budget, avoiding double
    // throttling while protecting its other active remote-control connections.
    if(offset<end){const wait=data.length*1000/rate-(performance.now()-started);if(wait>0)await sleep(Math.min(data.length>maxBytes?250:1000,wait));}
  }}finally{lease.close();}
}
export class BulkHost{
  constructor(service){this.service=service;this.sessions=new Set();this.quicSessions=new Map();}
  rate(){const now=performance.now();for(const [key,session] of this.quicSessions)if(session.until<now)this.quicSessions.delete(key);return bulkBudget(this.service)/Math.max(1,this.sessions.size+this.quicSessions.size);}
  async quicBytes(peer,args){
    const s=this.service;s.authorize(peer,'clipboard');requestedRate(args.maxRate);requestedBytes(args.maxBytes);
    const key=peer.remote.id+':'+args.offerId+':'+args.fileId,now=performance.now();this.rate();
    let session=this.quicSessions.get(key);if(!session){if(this.quicSessions.size>=16)throw Error('文件传输会话已满');session={nextAt:now,until:now+10000};this.quicSessions.set(key,session);}
    const rate=Math.min(this.rate(),requestedRate(args.maxRate)),maxBytes=bulkSlice(rate,requestedBytes(args.maxBytes)),start=Math.max(now,session.nextAt);session.nextAt=start+maxBytes*1000/rate;session.until=session.nextAt+10000;
    try{if(start>now)await sleep(start-now);s.authorize(peer,'clipboard');if(s.getPeer(peer.remote.id)!==peer)throw Error('文件连接已结束');const data=await s.fileBytes({...args,maxBytes},peer.remote.id);if(args.end!==undefined&&args.offset+data.length>=args.end)this.quicSessions.delete(key);return data;}
    catch(error){this.quicSessions.delete(key);throw error;}
  }
  async open(peer,args){
    const s=this.service;s.authorize(peer,'clipboard');requestedRate(args.maxRate);requestedBytes(args.maxBytes);
    if(this.sessions.size>=4)throw Error('文件传输会话已满');
    // Validate expiry and range before opening any listener.
    await s.fileBytes({...args,offset:args.offset,maxBytes:1},peer.remote.id);
    s.authorize(peer,'clipboard');if(this.sessions.size>=4)throw Error('文件传输会话已满');
    const secret=randomBytes(32),session={peer,offerId:args.offerId,sockets:new Set()},server=net.createServer();session.server=server;this.sessions.add(session);
    const close=()=>{clearTimeout(session.timer);peer.off('closed',close);for(const socket of session.sockets)socket.destroy();server.close();this.sessions.delete(session);};session.close=close;peer.once('closed',close);
    session.timer=setTimeout(close,6000);session.timer.unref();
    server.on('connection',async socket=>{
      if(session.sockets.size){socket.destroy();return;}session.sockets.add(socket);socket.setNoDelay(true);socket.setTimeout(6000,()=>socket.destroy());socket.on('error',()=>{});
      try{const {key,reader}=await serverHandshake(socket,secret,true);s.authorize(peer,'clipboard');clearTimeout(session.timer);server.close();
        let offset=args.offset,seq=0;const end=args.end;let nextAt=performance.now();
        while(offset<end){s.authorize(peer,'clipboard');if(s.getPeer(peer.remote.id)!==peer)throw Error('文件连接已结束');
          const rate=Math.min(this.rate(),requestedRate(args.maxRate)),maxBytes=bulkSlice(rate,requestedBytes(args.maxBytes));
          const data=(await s.fileBytes({...args,offset,maxBytes},peer.remote.id)).subarray(0,Math.min(maxBytes,end-offset));if(!data.length)throw Error('源文件已经变化');
          if(!socket.write(packBulk(key,seq++,data)))await drain(socket);offset+=data.length;
          // Pace background files; active control retains network headroom.
          nextAt=Math.max(nextAt,performance.now())+data.length*1000/rate;
          if(offset<end)await sleep(nextAt-performance.now());
        }
        if(args.completeAck&&!timingSafeEqual(await reader.read(32),hmac(key,'bulk-complete:'+end)))throw Error('文件接收确认无效');
        await new Promise((resolve,reject)=>{socket.once('error',reject);socket.end(resolve);});
      }catch(error){s.log('文件传输中断：'+error.message);socket.destroy();}finally{session.sockets.delete(socket);close();}
    });server.on('error',close);
    try{await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(0,s.options.host||'0.0.0.0',resolve);});return {protocol:1,port:server.address().port,key:secret.toString('hex'),completeAck:args.completeAck===true};}catch(error){close();throw error;}
  }
  closeOffer(id,offerId){for(const session of [...this.sessions])if(session.peer.remote.id===id&&session.offerId===offerId)session.close();for(const key of this.quicSessions.keys())if(key.startsWith(id+':'+offerId+':'))this.quicSessions.delete(key);}
  closeAll(){for(const session of [...this.sessions])session.close();this.quicSessions.clear();}
}
