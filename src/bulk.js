import net from 'node:net';
import {randomBytes,createHash,createCipheriv,createDecipheriv,timingSafeEqual} from 'node:crypto';
import {once} from 'node:events';
import {BinaryReader,serverHandshake,hmac} from './video-wire.js';

export const BULK_CHUNK=192*1024;
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
export async function connectBulk(host,info){
  if(info.protocol!==1 || !Number.isInteger(info.port) || info.port<1 || info.port>65535 || !/^[a-f0-9]{64}$/.test(info.key))throw Error('文件连接无效');
  const socket=net.connect({host,port:info.port});socket.setNoDelay(true);socket.setTimeout(6000,()=>socket.destroy());socket.on('error',()=>{});
  try{const reader=new BinaryReader(socket),secret=Buffer.from(info.key,'hex'),sn=await reader.read(32),cn=randomBytes(32);
    socket.write(Buffer.concat([cn,hmac(secret,'auth',sn,cn)]));if(!(await reader.read(32)).equals(hmac(secret,'ack',sn,cn)))throw Error('文件连接认证失败');
    const key=hmac(secret,'media',sn,cn);let seq=0,finished=false;
    return {socket,async next(){const size=(await reader.read(4)).readUInt32BE();return unpackBulk(key,seq++,await reader.read(size));},complete(end){finished=true;if(info.completeAck)socket.write(hmac(key,'bulk-complete:'+end));},close(){if(finished)socket.end();else socket.destroy();}};
  }catch(error){socket.destroy();throw error;}
}
export async function receiveBulk(service,sourceId,args,end,write,progress){
  let offset=args.offset,retries=0;
  while(offset<end){let connection,route;
    try{const peer=service.getPeer(sourceId),info=await peer.request('bulkOpen',{...args,offset,end,completeAck:true});route=await service.links?.videoEndpoint(peer,info);connection=await connectBulk(route?.host||peer.socket.remoteAddress,route?.info||info);
      while(offset<end){service.allow('clipboard');if(!service.canShareClipboard(sourceId)||service.group.excluded(sourceId))throw Error('剪贴板共享未授权');const data=await connection.next();if(!data.length||offset+data.length>end)throw Error('文件传输校验失败');await write(data);offset+=data.length;progress(offset);}connection.complete(end);
    }catch(error){const transient=['ECONNRESET','ETIMEDOUT','ECONNREFUSED','EPIPE'].includes(error.code)||/连接已断开|设备不在线|串流连接已结束|响应超时|文件连接已结束/.test(error.message);if(!transient||retries++>=2)throw error;await new Promise(resolve=>setTimeout(resolve,500));}
    finally{connection?.close();await route?.close();}
  }
}
export async function receiveQuicFile(service,source,args,end,write,progress){
  let offset=args.offset;
  while(offset<end){
    if(!source.quicConn||source.fastLink){await receiveBulk(service,source.remote.id,{...args,offset},end,write,progress);return;}
    service.allow('clipboard');if(!service.canShareClipboard(source.remote.id))throw Error('剪贴板共享未授权');
    let data;try{data=await service.quic.rpc(source,'quicFile',{...args,offset});}catch(error){if(/QUIC (连接|响应|connection|request)|QUIC 已关闭/.test(error.message)){service.quic.drop(source);await receiveBulk(service,source.remote.id,{...args,offset},end,write,progress);return;}throw error;}
    service.allow('clipboard');if(!service.canShareClipboard(source.remote.id))throw Error('剪贴板共享未授权');
    if(!data.length||data.length>BULK_CHUNK||offset+data.length>end)throw Error('文件传输校验失败');await write(data);offset+=data.length;progress(offset);
    const busy=service.videoHost.sessions.size||service.videoViewer.sessions.size;
    if(busy){const rates=[...service.videoHost.sessions.values(),...service.videoViewer.sessions.values()].map(s=>s.adaptive?.rate||s.bitrate||4000000);const rate=Math.max(8192,Math.min(128*1024,Math.min(...rates)*.15/8));await new Promise(r=>setTimeout(r,data.length*1000/rate));}
  }
}
export class BulkHost{
  constructor(service){this.service=service;this.sessions=new Set();}
  async open(peer,args){
    const s=this.service;s.authorize(peer,'clipboard');
    if(this.sessions.size>=4)throw Error('文件传输会话已满');
    // Validate expiry and range before opening any listener.
    await s.fileBytes({...args,offset:args.offset},peer.remote.id);
    s.authorize(peer,'clipboard');if(this.sessions.size>=4)throw Error('文件传输会话已满');
    const secret=randomBytes(32),session={peer,sockets:new Set()},server=net.createServer();session.server=server;this.sessions.add(session);
    const close=()=>{clearTimeout(session.timer);peer.off('closed',close);for(const socket of session.sockets)socket.destroy();server.close();this.sessions.delete(session);};session.close=close;peer.once('closed',close);
    session.timer=setTimeout(close,6000);session.timer.unref();
    server.on('connection',async socket=>{
      if(session.sockets.size){socket.destroy();return;}session.sockets.add(socket);socket.setNoDelay(true);socket.setTimeout(6000,()=>socket.destroy());socket.on('error',()=>{});
      try{const {key,reader}=await serverHandshake(socket,secret,true);s.authorize(peer,'clipboard');clearTimeout(session.timer);server.close();
        let offset=args.offset,seq=0;const end=args.end;let nextAt=performance.now();
        while(offset<end){s.authorize(peer,'clipboard');if(s.getPeer(peer.remote.id)!==peer)throw Error('文件连接已结束');
          const data=(await s.fileBytes({...args,offset},peer.remote.id)).subarray(0,Math.min(BULK_CHUNK,end-offset));if(!data.length)throw Error('源文件已经变化');
          if(!socket.write(packBulk(key,seq++,data)))await once(socket,'drain');offset+=data.length;
          // Pace background files; active control retains network headroom.
          const media=[...s.videoHost.sessions.values(),...s.videoViewer.sessions.values()];
          const budget=media.length?Math.max(8192,Math.min(128*1024,Math.min(...media.map(v=>v.adaptive?.rate||v.bitrate||4000000))/8*.15)):s.active||s.owner?1024*1024:32*1024*1024;
          const rate=budget/Math.max(1,this.sessions.size);nextAt+=data.length*1000/rate;
          if(offset<end)await new Promise(resolve=>setTimeout(resolve,Math.max(1,nextAt-performance.now())));
        }
        if(args.completeAck&&!timingSafeEqual(await reader.read(32),hmac(key,'bulk-complete:'+end)))throw Error('文件接收确认无效');
        await new Promise((resolve,reject)=>{socket.once('error',reject);socket.end(resolve);});
      }catch(error){s.log('文件传输中断：'+error.message);socket.destroy();}finally{session.sockets.delete(socket);close();}
    });server.on('error',close);
    try{await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(0,s.options.host||'0.0.0.0',resolve);});return {protocol:1,port:server.address().port,key:secret.toString('hex'),completeAck:args.completeAck===true};}catch(error){close();throw error;}
  }
  closeAll(){for(const session of [...this.sessions])session.close();}
}
