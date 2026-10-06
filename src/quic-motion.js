import {randomUUID} from 'node:crypto';
export class MotionSender {
  constructor(){this.reset();}
  reset(){this.epoch=randomUUID();this.seq=0;this.dx=0;this.dy=0;this.snapshot=null;}
  move(input){
    if(input.kind==='relative'){if(![input.dx,input.dy].every(n=>Number.isFinite(n)&&Math.abs(n)<=1000))throw Error('鼠标位移无效');this.dx+=input.dx;this.dy+=input.dy;}
    else {
      if(input.kind!=='move'||![input.x,input.y].every(n=>Number.isFinite(n)&&n>=0&&n<=1))throw Error('鼠标位置无效');
      // Android's external mouse consumes raw distance. Clamped coordinates are
      // only an absolute-position fallback, never a limit on physical movement.
      if(input.dx!==undefined||input.dy!==undefined){
        if(![input.dx,input.dy].every(n=>Number.isFinite(n)&&Math.abs(n)<=32000))throw Error('鼠标位移无效');
        this.dx+=input.dx;this.dy+=input.dy;
      }
    }
    return this.snapshot={epoch:this.epoch,seq:++this.seq,kind:input.kind,dx:this.dx,dy:this.dy,...(input.kind==='move'?{x:input.x,y:input.y,...(input.dx!==undefined?{raw:true}:{})}:{})};
  }
}
export class MotionReceiver {
  constructor(){this.reset();}
  reset(){this.epoch=null;this.seq=0;this.dx=0;this.dy=0;}
  accept(m){
    if(!m||typeof m.epoch!=='string'||m.epoch.length!==36||!Number.isSafeInteger(m.seq)||m.seq<=0||!['relative','move'].includes(m.kind)||![m.dx,m.dy].every(n=>Number.isFinite(n)&&Math.abs(n)<=1e12))throw Error('鼠标 Datagram 无效');
    if(m.kind==='move'&&(![m.x,m.y].every(n=>Number.isFinite(n)&&n>=0&&n<=1)||(m.raw!==undefined&&typeof m.raw!=='boolean')))throw Error('鼠标 Datagram 位置无效');
    if(this.epoch===null)this.epoch=m.epoch;else if(this.epoch!==m.epoch)throw Error('鼠标会话已结束');
    if(m.seq<=this.seq)return [];
    let dx=m.dx-this.dx,dy=m.dy-this.dy;
    if(Math.abs(dx)>32000||Math.abs(dy)>32000){
      if(m.kind!=='move'||!m.raw)throw Error('鼠标恢复位移过大');
      // A long outage must not lock the mouse forever against the old baseline.
      // Discard excessive stale motion, consume the sample, then resume live input.
      dx=dy=0;
    }
    this.seq=m.seq;this.dx=m.dx;this.dy=m.dy;
    if(m.kind==='move'&&!m.raw)return [{kind:'move',x:m.x,y:m.y}];
    if(m.kind==='relative'&&Math.abs(dx)<=0.000001&&Math.abs(dy)<=0.000001)return [];
    const result=[];do {const x=Math.max(-1000,Math.min(1000,dx)),y=Math.max(-1000,Math.min(1000,dy));result.push({kind:m.kind,...(m.kind==='move'?{x:m.x,y:m.y}:{}),dx:x,dy:y});dx-=x;dy-=y;}while(Math.abs(dx)>0.000001||Math.abs(dy)>0.000001);return result;
  }
}
