// Stop waiting without abandoning rejection handling for the underlying RPC.
export function withAbort(work,signal){
  if(!signal)return work;
  if(signal.aborted){Promise.resolve(work).catch(()=>{});signal.throwIfAborted();}
  return new Promise((resolve,reject)=>{const aborted=()=>reject(signal.reason);signal.addEventListener('abort',aborted,{once:true});Promise.resolve(work).then(resolve,reject).finally(()=>signal.removeEventListener('abort',aborted));});
}
