package com.lanlink.app;

import java.io.IOException;
import java.util.*;
import org.json.JSONObject;

/** Cumulative raw motion survives loss, including absolute moves at a clamped edge. */
final class QuicMotion {
  static final class Sender {
    String epoch;long seq;double dx,dy;JSONObject snapshot;final ArrayDeque<double[]> samples=new ArrayDeque<>();
    final java.util.function.LongSupplier clock;Sender(java.util.function.LongSupplier clock){this.clock=clock;reset();}
    Sender(){this(android.os.SystemClock::elapsedRealtime);}
    void reset(){epoch=UUID.randomUUID().toString();seq=0;dx=dy=0;snapshot=null;samples.clear();}
    JSONObject barrier()throws Exception{return snapshot==null?null:new JSONObject(snapshot.toString()).put("ageMs",Math.max(0,Math.min(60000,clock.getAsLong()-snapshot.optLong("time"))));}
    JSONObject move(JSONObject a)throws Exception{
      long time=clock.getAsLong();double beforeX=dx,beforeY=dy;String kind=a.getString("kind");
      if(kind.equals("relative")){double x=a.getDouble("dx"),y=a.getDouble("dy");if(!Double.isFinite(x)||!Double.isFinite(y)||Math.abs(x)>1000||Math.abs(y)>1000)throw new IOException("鼠标位移无效");dx+=x;dy+=y;}
      else {
        if(!kind.equals("move")||!unit(a.getDouble("x"))||!unit(a.getDouble("y")))throw new IOException("鼠标位置无效");
        if(a.has("dx")||a.has("dy")){double x=a.getDouble("dx"),y=a.getDouble("dy");if(!Double.isFinite(x)||!Double.isFinite(y)||Math.abs(x)>32000||Math.abs(y)>32000)throw new IOException("鼠标位移无效");dx+=x;dy+=y;}
      }
      while(!samples.isEmpty()&&time-samples.peekFirst()[0]>120)samples.removeFirst();samples.addLast(new double[]{time,dx-beforeX,dy-beforeY});double recentX=0,recentY=0;for(double[] sample:samples){recentX+=sample[1];recentY+=sample[2];}
      snapshot=Wire.obj("epoch",epoch,"seq",++seq,"kind",kind,"dx",dx,"dy",dy,"time",time,"recentDx",recentX,"recentDy",recentY);
      if(kind.equals("move")){snapshot.put("x",a.getDouble("x"));snapshot.put("y",a.getDouble("y"));if(a.has("dx"))snapshot.put("raw",true);}return snapshot;
    }
  }
  /** Per-connection pacing: native Datagram enqueue time is not network RTT. */
  static final class Pacing {
    int interval=8,good;long changedAt=-2000,lastSent=-50;double rtt,base=Double.POSITIVE_INFINITY;
    synchronized void feedback(double sample,long queued){
      long now=android.os.SystemClock.elapsedRealtime();if(queued<0||!Double.isFinite(sample)||sample>30000)return;
      if(sample>=0){base=Math.min(base,sample);rtt=rtt==0?sample:rtt*.75+sample*.25;}
      double latency=Math.max(rtt,sample);
      if(queued>32768||latency>120||latency>Math.max(70,base*2)){good=0;int target=(int)Math.max(16,Math.min(50,Math.ceil(latency/4)));if(now-changedAt>=250&&(target>interval||queued>32768)){interval=Math.min(50,Math.max(target,interval+4));changedAt=now;}}
      else if(sample>=0&&queued<8192&&++good>=8&&now-changedAt>=2000){interval=Math.max(8,interval-4);good=0;changedAt=now;}
    }
    void pace()throws InterruptedException{long wait;synchronized(this){wait=interval-(android.os.SystemClock.elapsedRealtime()-lastSent);}if(wait>0)Thread.sleep(wait);synchronized(this){lastSent=android.os.SystemClock.elapsedRealtime();}}
  }
  static boolean unit(double n){return Double.isFinite(n)&&n>=0&&n<=1;}
  static final class Receiver {
    String epoch;long seq,time=-1;double dx,dy;
    void reset(String next){epoch=next;seq=0;dx=dy=0;time=-1;}
    List<JSONObject> accept(JSONObject m)throws Exception {
      String next=m.getString("epoch"),kind=m.getString("kind");long number=m.getLong("seq");double x=m.getDouble("dx"),y=m.getDouble("dy");
      if(next.length()!=36||number<=0||number>9007199254740991L||!Double.isFinite(x)||!Double.isFinite(y)||Math.abs(x)>1e12||Math.abs(y)>1e12||!kind.equals("relative")&&!kind.equals("move"))throw new IOException("鼠标 Datagram 无效");
      if(kind.equals("move")&&(!unit(m.getDouble("x"))||!unit(m.getDouble("y"))||m.has("raw")&&!(m.get("raw") instanceof Boolean)))throw new IOException("鼠标位置无效");
      if(epoch==null)epoch=next;else if(!epoch.equals(next))throw new IOException("鼠标会话已结束");
      List<JSONObject> result=new ArrayList<>();if(number<=seq)return result;
      boolean recent=m.has("time")||m.has("recentDx")||m.has("recentDy");long sent=m.optLong("time",-1);double recentX=m.optDouble("recentDx",Double.NaN),recentY=m.optDouble("recentDy",Double.NaN);
      if(recent&&(sent<0||sent>9007199254740991L||!Double.isFinite(recentX)||!Double.isFinite(recentY)||Math.abs(recentX)>1e12||Math.abs(recentY)>1e12))throw new IOException("鼠标 Datagram 时间无效");
      double age=m.optDouble("ageMs",0);if(!Double.isFinite(age)||age<0||age>60000)throw new IOException("鼠标 Datagram 时效无效");
      double remainingX=x-dx,remainingY=y-dy;
      if(recent&&(seq==0||number>seq+1&&sent-time>250)){remainingX=recentX;remainingY=recentY;}
      if(age>250)remainingX=remainingY=0;
      if(Math.abs(remainingX)>32000||Math.abs(remainingY)>32000){
        // Consume an excessive stale gap so subsequent live movement can recover.
        remainingX=remainingY=0;
      }
      seq=number;dx=x;dy=y;time=recent?sent:-1;
      if(kind.equals("move")&&!m.optBoolean("raw")){result.add(Wire.obj("kind","move","x",m.getDouble("x"),"y",m.getDouble("y")));return result;}
      if(kind.equals("relative")&&Math.abs(remainingX)<=0.000001&&Math.abs(remainingY)<=0.000001)return result;
      do {double stepX=Math.max(-1000,Math.min(1000,remainingX)),stepY=Math.max(-1000,Math.min(1000,remainingY));JSONObject input=Wire.obj("kind",kind,"dx",stepX,"dy",stepY);if(kind.equals("move")){input.put("x",m.getDouble("x"));input.put("y",m.getDouble("y"));}result.add(input);remainingX-=stepX;remainingY-=stepY;}while(Math.abs(remainingX)>0.000001||Math.abs(remainingY)>0.000001);return result;
    }
  }
}
