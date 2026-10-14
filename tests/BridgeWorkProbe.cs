using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Threading;
using LanLink;

static class BridgeWorkProbe {
 static void Require(bool ok,string message){if(!ok)throw new Exception(message);}
 static void Main(){
  var callbacks=new Queue<Action>();var commands=new List<int>();
  var dispatch=new BridgeDispatch(delegate(Action work){callbacks.Enqueue(work);});
  for(int i=0;i<100;i++){int id=i;dispatch.Post(delegate{commands.Add(id);});}
  Require(callbacks.Count==1,"one immediate wakeup should cover a burst");
  int turns=0;while(callbacks.Count>0){callbacks.Dequeue()();turns++;}
  Require(turns>=4&&commands.Count==100,"input dispatch must yield between bounded turns");
  for(int i=0;i<100;i++)Require(commands[i]==i,"input ordering changed");

  var entered=new ManualResetEvent(false);var resume=new ManualResetEvent(false);var finished=new ManualResetEvent(false);
  var screen=new BridgeWorker("test screen",false);var clipboard=new BridgeWorker("test clipboard",false);
  screen.Post(delegate{entered.Set();resume.WaitOne();});Require(entered.WaitOne(5000),"screen did not start");
  clipboard.Post(delegate{finished.Set();});Require(finished.WaitOne(1000),"screen blocked the separate clipboard worker");
  bool input=false;dispatch.Post(delegate{input=true;});callbacks.Dequeue()();Require(input,"screen blocked the input dispatcher");resume.Set();

  entered=new ManualResetEvent(false);resume=new ManualResetEvent(false);var values=new List<int>();
  var output=new BridgeOutput(delegate(object value){int n=(int)value;if(n==-1){entered.Set();resume.WaitOne();}values.Add(n);});
  output.Post(-1,false);Require(entered.WaitOne(5000),"writer did not start");
  var clock=Stopwatch.StartNew();for(int i=0;i<10000;i++)output.Post(i,true);for(int i=10000;i<10100;i++)output.Post(i,false);output.Post(10100,true);
  Require(clock.ElapsedMilliseconds<500,"input producer waited for the blocked writer");
  resume.Set();output.Flush();Require(values.Count==103,"cursor updates did not coalesce");Require(values[1]==9999,"cursor replacement lost the latest position");
  for(int i=0;i<100;i++)Require(values[i+2]==10000+i,"reliable output lost order");Require(values[102]==10100,"cursor crossed a reliable barrier");
  long now=0;var blocked=new ManualResetEvent(false);var go=new ManualResetEvent(false);var motion=new List<object>();
  var moves=new BridgeOutput(delegate(object value){if(value is string&&(string)value=="blocked"){blocked.Set();go.WaitOne();}motion.Add(value);},delegate{return now;});
  moves.Post("blocked",false);Require(blocked.WaitOne(5000),"motion writer did not start");
  moves.Move(10,1);now=100;moves.Move(20,2);now=150;moves.Move(30,3);moves.Post("button",false);now=200;moves.Move(5,4);
  go.Set();moves.Flush();Require(motion.Count==4,"raw moves did not coalesce within button barriers");
  Require(Convert.ToDouble(motion[1].GetType().GetProperty("dx").GetValue(motion[1],null))==50,"old raw displacement replayed after backpressure");
  Require((string)motion[2]=="button"&&Convert.ToDouble(motion[3].GetType().GetProperty("dx").GetValue(motion[3],null))==5,"button crossed raw movement");
  blocked.Reset();go.Reset();moves.Post("blocked",false);Require(blocked.WaitOne(5000),"writer did not block again");now=1000;moves.Move(400,0);now=2000;go.Set();moves.Flush();Require(motion.Count==5,"fully stale raw motion must not replay");
  var compact=new List<string>();var compactOutput=new BridgeOutput(delegate(object value){compact.Add((string)value);},null,delegate(double dx,double dy){compact.Add("M:"+dx.ToString(System.Globalization.CultureInfo.InvariantCulture)+":"+dy.ToString(System.Globalization.CultureInfo.InvariantCulture));});
  compactOutput.Move(.25,-1.5);compactOutput.Post("button",false);compactOutput.Move(.75,2.5);compactOutput.Flush();
  Require(compact.Count==3&&compact[0]=="M:0.25:-1.5"&&compact[1]=="button"&&compact[2]=="M:0.75:2.5","compact capture bypassed ordering or lost fractions");
  Console.WriteLine("WINDOWS_BRIDGE_WORK_OK dispatchTurns="+turns+" replies=100 cursorUpdates=2 motionBarriers=preserved");
 }
}
