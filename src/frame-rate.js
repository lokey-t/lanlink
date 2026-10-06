import {performance} from 'node:perf_hooks';
// Count consumer updates in a recent window, never the lifetime average.
export class FrameRate {
  constructor(now=()=>performance.now()){this.now=now;this.samples=[];}
  tick(){this.samples.push(this.now());return this.read();}
  read(){const now=this.now();while(this.samples.length&&this.samples[0]<=now-1000)this.samples.shift();return this.samples.length;}
}
