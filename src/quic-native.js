import {createRequire} from 'node:module';
import {existsSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
import {EventEmitter} from 'node:events';
const require=createRequire(import.meta.url),root=fileURLToPath(new URL('../',import.meta.url));
export function quicPath(){const name=process.platform==='win32'?'lanlink_quic.dll':process.platform==='darwin'?'liblanlink_quic.dylib':'liblanlink_quic.so';return [process.env.LANLINK_QUIC,path.join(root,'runtime',name),path.join(root,'native/quic/target/release',name)].find(p=>p&&path.isAbsolute(p)&&existsSync(p));}
export class QuicNative extends EventEmitter {
  async start(dir,host='0.0.0.0:0'){
    const file=quicPath();if(!file)throw Error('QUIC 原生库未安装');this.koffi=require('koffi');this.lib=this.koffi.load(file);
    this.begin=this.lib.func('void *ll_quic_start(const char *, const char *)');this.command=this.lib.func('int ll_quic_command(void *, const char *)');this.poll=this.lib.func('void *ll_quic_poll(void *)');this.free=this.lib.func('void ll_quic_free(void *)');this.stop=this.lib.func('void ll_quic_stop(void *)');
    this.handle=this.begin(host,dir);if(!this.handle)throw Error('QUIC 网络初始化失败');this.pending=new Map();this.sequence=0;this.closed=false;
    const ready=new Promise((resolve,reject)=>{this.once('ready',resolve);this.once('error',reject);});this.timer=setInterval(()=>this.drain(),4);this.timer.unref();this.drain();this.info=await ready;return this.info;
  }
  drain(){if(this.closed)return;let pointer;try{pointer=this.poll(this.handle);if(!pointer)return;const events=JSON.parse(this.koffi.decode(pointer,'char',-1));for(const e of events){if(e.id){const p=this.pending.get(e.id);if(!p)continue;this.pending.delete(e.id);clearTimeout(p.timer);e.error?p.reject(Error(e.error)):p.resolve(e.result);}else if(e.event==='overflow'){this.close();return;}else this.emit(e.event,e);}}catch(error){if(this.listenerCount('error'))this.emit('error',error);this.close();}finally{if(pointer)this.free(pointer);}}
  call(op,args={},timeout=15000){if(this.closed||!this.handle)return Promise.reject(Error('QUIC 已关闭'));if(this.pending.size>=64)return Promise.reject(Error('QUIC 请求过多'));return new Promise((resolve,reject)=>{const id=String(++this.sequence),timer=setTimeout(()=>{this.pending.delete(id);reject(Error('QUIC 响应超时'));},timeout);this.pending.set(id,{resolve,reject,timer});if(this.command(this.handle,JSON.stringify({id,op,...args,timeout}))!==0){clearTimeout(timer);this.pending.delete(id);reject(Error('QUIC 缓冲区已满'));}});}
  close(){if(this.closed)return;this.closed=true;clearInterval(this.timer);for(const p of this.pending?.values()||[]){clearTimeout(p.timer);p.reject(Error('QUIC 已关闭'));}this.pending?.clear();if(this.handle){this.stop(this.handle);this.handle=null;}this.emit('closed');}
}
