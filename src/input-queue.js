// Adjacent movement may merge; clicks/keys/ownership changes remain ordering barriers.
export class InputQueue{
  constructor(send){this.send=send;this.queue=[];this.running=false;}
  request(method,args,timeout){
    return new Promise((resolve,reject)=>{
      const tail=this.queue.at(-1),motion=method==='input'&&['move','relative'].includes(args.kind);
      if(motion&&tail?.method==='input'&&tail.args.kind===args.kind){
        const dx=(tail.args.dx||0)+(args.dx||0),dy=(tail.args.dy||0)+(args.dy||0);
        if(Math.abs(dx)<=1000&&Math.abs(dy)<=1000){tail.args={...args,...(args.dx!==undefined?{dx,dy}:{})};tail.waiters.push({resolve,reject});return;}
      }
      if(this.queue.length>=64){reject(Error('输入队列繁忙，请稍后重试'));return;}
      this.queue.push({method,args:{...args},timeout,waiters:[{resolve,reject}]});this.flush();
    });
  }
  async flush(){if(this.running)return;this.running=true;try{while(this.queue.length){const job=this.queue.shift();try{const result=await this.send(job.method,job.args,job.timeout);for(const waiter of job.waiters)waiter.resolve(result);}catch(error){for(const waiter of job.waiters)waiter.reject(error);}}}finally{this.running=false;}}
}
