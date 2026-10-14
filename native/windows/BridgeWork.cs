namespace LanLink {
 using System;
 using System.Collections.Generic;
 using System.Collections.Concurrent;
 using System.Diagnostics;
 using System.Threading;

 // Wake the input/message-pump thread when a command arrives. A bounded turn
 // keeps a burst of commands from starving raw-input and emergency-key messages.
 internal sealed class BridgeDispatch {
  readonly ConcurrentQueue<Action> pending=new ConcurrentQueue<Action>();
  readonly Action<Action> wake;int scheduled;
  internal BridgeDispatch(Action<Action> wake){this.wake=wake;}
  internal void Post(Action work){pending.Enqueue(work);Schedule();}
  void Schedule(){if(Interlocked.CompareExchange(ref scheduled,1,0)==0)wake(Drain);}
  void Drain(){try{var clock=Stopwatch.StartNew();Action work;int count=0;while(count++<32&&pending.TryDequeue(out work)){work();if(clock.ElapsedMilliseconds>=4)break;}}finally{Interlocked.Exchange(ref scheduled,0);if(!pending.IsEmpty)Schedule();}}
 }

 // Clipboard operations need their own STA; capture/encoding needs neither the
 // hook thread nor the clipboard apartment. Each lane preserves its own order.
 internal sealed class BridgeWorker {
  readonly BlockingCollection<Action> pending=new BlockingCollection<Action>();
  internal BridgeWorker(string name,bool sta){var thread=new Thread(delegate(){foreach(var work in pending.GetConsumingEnumerable())work();});thread.IsBackground=true;thread.Name=name;if(sta)thread.SetApartmentState(ApartmentState.STA);thread.Start();}
  internal void Post(Action work){pending.Add(work);}
 }

 // Pipe backpressure must never hold the OS input-hook thread. Only consecutive
 // cursor snapshots replace one another; every reply/key/button retains order.
 internal sealed class BridgeOutput {
  sealed class Sample {internal long At;internal double Dx,Dy;}
  sealed class Item {internal object Value;internal bool Cursor;internal List<Sample> Motion;internal Action Completed;}
  readonly object gate=new object();readonly LinkedList<Item> pending=new LinkedList<Item>();
  readonly Action<double,double> writeMove;
  readonly AutoResetEvent ready=new AutoResetEvent(false);readonly Action<object> write;readonly Func<long> now;
  internal BridgeOutput(Action<object> write,Func<long> now=null,Action<double,double> writeMove=null){this.write=write;this.writeMove=writeMove;this.now=now??delegate{return (long)(Stopwatch.GetTimestamp()*1000.0/Stopwatch.Frequency);};var thread=new Thread(Run);thread.IsBackground=true;thread.Name="LanLink output";thread.Start();}
  internal void Post(object value,bool cursor,Action completed=null){lock(gate){if(cursor&&completed==null&&pending.Last!=null&&pending.Last.Value.Cursor&&pending.Last.Value.Completed==null)pending.Last.Value.Value=value;else pending.AddLast(new Item{Value=value,Cursor=cursor,Completed=completed});}ready.Set();}
  internal void Move(double dx,double dy){long at=now();lock(gate){Item item;if(pending.Last!=null&&pending.Last.Value.Motion!=null)item=pending.Last.Value;else{item=new Item{Motion=new List<Sample>()};pending.AddLast(item);}item.Motion.RemoveAll(delegate(Sample s){return at-s.At>120;});var samples=item.Motion;Sample last=samples.Count>0?samples[samples.Count-1]:null;if(last!=null&&last.At==at){last.Dx+=dx;last.Dy+=dy;}else samples.Add(new Sample{At=at,Dx=dx,Dy=dy});}ready.Set();}
  internal void Flush(){using(var sent=new ManualResetEvent(false)){Post(null,false,delegate{sent.Set();});sent.WaitOne();}}
  void Run(){for(;;){Item item=null;lock(gate){if(pending.First!=null){item=pending.First.Value;pending.RemoveFirst();}}if(item==null){ready.WaitOne();continue;}if(item.Motion!=null){long at=now();double dx=0,dy=0;bool fresh=false;foreach(var sample in item.Motion)if(at-sample.At<=120){fresh=true;dx+=sample.Dx;dy+=sample.Dy;}if(fresh){if(writeMove!=null)writeMove(dx,dy);else write(new{type="input",kind="move",dx=dx,dy=dy});}}else if(item.Value!=null)write(item.Value);if(item.Completed!=null)item.Completed();}}
 }
}
