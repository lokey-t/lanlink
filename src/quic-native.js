import {createRequire} from 'node:module';
import {existsSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
import {EventEmitter} from 'node:events';
const require=createRequire(import.meta.url),root=fileURLToPath(new URL('../',import.meta.url));
export const isQuicBusy=error=>error?.code==='QUIC_BUSY';
const busy=()=>Object.assign(Error('QUIC 通道繁忙，请稍后重试'),{code:'QUIC_BUSY'});
export function quicPath(){const name=process.platform==='win32'?'lanlink_quic.dll':process.platform==='darwin'?'liblanlink_quic.dylib':'liblanlink_quic.so';return [process.env.LANLINK_QUIC,path.join(root,'runtime',name),path.join(root,'native/quic/target/release',name)].find(p=>p&&path.isAbsolute(p)&&existsSync(p));}
export class QuicNative extends EventEmitter {
  async start(dir,host='0.0.0.0:0'){
    const file=quicPath();if(!file)throw Error('QUIC 原生库未安装');this.koffi=require('koffi');this.lib=this.koffi.load(file);
    this.begin=this.lib.func('void *ll_quic_start(const char *, const char *)');this.command=this.lib.func('int ll_quic_command(void *, const char *)');this.poll=this.lib.func('void *ll_quic_poll(void *)');this.free=this.lib.func('void ll_quic_free(void *)');this.stop=this.lib.func('void ll_quic_stop(void *)');
    try{this.enqueueMotion=this.lib.func('int ll_quic_motion(void *, uint64_t, const uint8_t *, size_t)');this.waitPacket=this.lib.func('void *ll_quic_wait_packet(void *)');this.freePacket=this.lib.func('void ll_quic_free_packet(void *)');this.binaryMotion=true;}catch{this.binaryMotion=false;}
    this.handle=this.begin(host,dir);if(!this.handle)throw Error('QUIC 网络初始化失败');this.pending=new Map();this.sequence=0;this.closed=false;
    const ready=new Promise((resolve,reject)=>{this.once('ready',resolve);this.once('error',reject);});if(this.binaryMotion)this.drainPackets();else{this.timer=setInterval(()=>this.drain(),4);this.timer.unref();this.drain();}this.info=await ready;return this.info;
  }
  drain(){if(this.closed)return;let pointer;try{pointer=this.poll(this.handle);if(!pointer)return;const events=JSON.parse(this.koffi.decode(pointer,'char',-1));for(const e of events){if(e.id){const p=this.pending.get(e.id);if(!p)continue;this.pending.delete(e.id);clearTimeout(p.timer);e.error?p.reject(Error(e.error)):p.resolve(e.result);}else if(e.event==='overflow'){this.close();return;}else this.emit(e.event,e);}}catch(error){if(this.listenerCount('error'))this.emit('error',error);this.close();}finally{if(pointer)this.free(pointer);}}
  sendMotion(conn,bytes){if(this.closed||!this.handle)throw Error('QUIC 已关闭');if(!this.binaryMotion)throw Error('Binary motion unavailable');if(this.enqueueMotion(this.handle,conn,bytes,bytes.length)!==0)throw busy();}
  drainPackets(){
    if(this.closed)return;this.waiting=true;
    this.waitPacket.async(this.handle,(error,pointer)=>{
      this.waiting=false;
      try{
        if(error)throw error;
        if(pointer&&!this.closed){
          const header=Buffer.from(this.koffi.decode(pointer,'uint8_t',4));const length=header.readUInt32BE();
          const packet=Buffer.from(this.koffi.decode(pointer,'uint8_t',length));
          for(let at=4;at<packet.length;){const kind=packet[at],size=packet.readUInt32BE(at+1);at+=5;const body=packet.subarray(at,at+size);at+=size;
            if(kind===1)this.emit('motion',{conn:Number(body.readBigUInt64BE()),bytes:body.subarray(8)});
            else this.deliver(JSON.parse(body));
            if(this.closed)break;
          }
        }
      }catch(e){if(this.listenerCount('error'))this.emit('error',e);this.close();}
      finally{if(pointer)this.freePacket(pointer);if(this.closed){if(this.handle){this.stop(this.handle);this.handle=null;}}else setImmediate(()=>this.drainPackets());}
    });
  }
  deliver(e){if(e.id){const p=this.pending.get(e.id);if(!p)return;this.pending.delete(e.id);clearTimeout(p.timer);e.error?p.reject(Error(e.error)):p.resolve(e.result);}else if(e.event==='overflow')this.close();else this.emit(e.event,e);}
  call(op,args={},timeout=15000){
    if(this.closed||!this.handle)return Promise.reject(Error('QUIC 已关闭'));
    // The FFI engine serves every peer. Reserve command slots for input,
    // replies and lease renewal even when another peer's work awaits its reply.
    const urgent=op==='request'?args.priority===0:op!=='video';
    let ordinary=0,requests=0,networkRequests=0;
    for(const pending of this.pending.values()){
      if(!pending.urgent)ordinary++;
      if(pending.op==='request'){networkRequests++;if(pending.conn===args.conn)requests++;}
    }
    // Even urgent RPCs wait for a network round trip. Keep eight slots for
    // local acknowledgements so replying or closing can release that pressure.
    if(this.pending.size>=64||!urgent&&ordinary>=48||op==='request'&&(networkRequests>=56||requests>=(urgent?32:24)))return Promise.reject(busy());
    return new Promise((resolve,reject)=>{
      const id=String(++this.sequence),timer=setTimeout(()=>{this.pending.delete(id);reject(Error('QUIC 响应超时'));},timeout);
      this.pending.set(id,{resolve,reject,timer,op,conn:args.conn,urgent});
      if(this.command(this.handle,JSON.stringify({id,op,...args,timeout}))!==0){clearTimeout(timer);this.pending.delete(id);reject(busy());}
    });
  }
  close(){if(this.closed)return;this.closed=true;clearInterval(this.timer);for(const p of this.pending?.values()||[]){clearTimeout(p.timer);p.reject(Error('QUIC 已关闭'));}this.pending?.clear();if(this.handle&&!this.waiting){this.stop(this.handle);this.handle=null;}this.emit('closed');}
}
