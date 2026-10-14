package com.lanlink.app;

import java.io.IOException;
import java.util.*;
import org.json.JSONObject;

/** Cumulative raw motion survives loss, including absolute moves at a clamped edge. */
final class QuicMotion {
  static byte[] encode(JSONObject m)throws Exception {
    java.nio.ByteBuffer b=java.nio.ByteBuffer.allocate(104).order(java.nio.ByteOrder.BIG_ENDIAN);
    UUID epoch=UUID.fromString(m.getString("epoch"));boolean move=m.getString("kind").equals("move");
    b.put((byte)2).put((byte)((move?1:0)|(m.optBoolean("raw")?2:0)));b.position(8);
    b.putLong(epoch.getMostSignificantBits()).putLong(epoch.getLeastSignificantBits());
    b.putLong(m.getLong("seq")).putLong(m.getLong("time")).putLong(m.optLong("afterInput"));
    for(String key:new String[]{"dx","dy","recentDx","recentDy","x","y","ageMs"})b.putDouble(m.optDouble(key,0));
    return b.array();
  }
  static JSONObject decode(byte[] bytes)throws Exception {
    if(bytes.length!=104||bytes[0]!=2||(bytes[1]&~3)!=0)throw new IOException("鼠标数据无效");
    for(int i=2;i<8;i++)if(bytes[i]!=0)throw new IOException("鼠标数据无效");
    java.nio.ByteBuffer b=java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.BIG_ENDIAN);b.position(8);
    String epoch=new UUID(b.getLong(),b.getLong()).toString();long seq=b.getLong(),time=b.getLong(),after=b.getLong();
    if(seq<=0||seq>9007199254740991L||time<0||time>9007199254740991L||after<0||after>9007199254740991L)throw new IOException("鼠标序号无效");
    JSONObject m=Wire.obj("epoch",epoch,"seq",seq,"time",time,"afterInput",after,"kind",(bytes[1]&1)!=0?"move":"relative");
    for(String key:new String[]{"dx","dy","recentDx","recentDy","x","y","ageMs"})m.put(key,b.getDouble());
    if((bytes[1]&2)!=0)m.put("raw",true);return m;
  }
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
    static final int MIN=4,MAX=24;
    final java.util.function.LongSupplier clock;
    final ArrayDeque<double[]> rttBuckets=new ArrayDeque<>();
    int interval=MIN,good;long changedAt=-1000000,lastSent=-1000000,recoverSince=-1;
    long rttAt=-1,queueAt=-1,delayAt=-1,queued;double rtt,delay,base=Double.POSITIVE_INFINITY;
    Pacing(){this(android.os.SystemClock::elapsedRealtime);}
    Pacing(java.util.function.LongSupplier clock){this.clock=clock;}
    void feedback(Double sample,Long queued){feedback(sample,null,queued);}
    synchronized void feedback(Double sample,Double delaySample,Long queueSample){
      if(sample==null&&delaySample==null&&queueSample==null)return;
      if(sample!=null&&(!Double.isFinite(sample)||sample<0||sample>30000)||delaySample!=null&&(!Double.isFinite(delaySample)||delaySample<0)||queueSample!=null&&queueSample<0)return;
      long now=clock.getAsLong();
      if(sample!=null){
        long bucket=now/500;while(!rttBuckets.isEmpty()&&rttBuckets.peekFirst()[0]<=bucket-20)rttBuckets.removeFirst();
        double[] last=rttBuckets.peekLast();if(last!=null&&last[0]==bucket)last[1]=Math.min(last[1],sample);else rttBuckets.addLast(new double[]{bucket,sample});
        base=Double.POSITIVE_INFINITY;for(double[] entry:rttBuckets)base=Math.min(base,entry[1]);
        rtt=rttAt<0?sample:rtt+(sample-rtt)*(sample<rtt?.5:.25);rttAt=now;
      }
      if(queueSample!=null){queued=queueSample;queueAt=now;}
      if(delaySample!=null){delay=delaySample;delayAt=now;}
      double excess=rttAt>=0&&now-rttAt<=2000?Math.max(0,rtt-base):0;
      long backlog=queueAt>=0&&now-queueAt<=500?queued:0;
      double freshDelay=delayAt>=0&&now-delayAt<=500?delay:0;
      int target=backlog>=65536||freshDelay>=80?24:backlog>=16384?20:backlog>=4096||excess>=40||freshDelay>=35?16:excess>=15||freshDelay>=15?12:4;
      if(target>interval){good=0;recoverSince=-1;if(now-changedAt>=120){interval=backlog>=131072?MAX:Math.min(target,interval+4);changedAt=now;}}
      else if(target<interval){if(recoverSince<0)recoverSince=now;if(++good>=3&&now-recoverSince>=200&&now-changedAt>=250){interval=Math.max(target,interval-4);changedAt=now;good=0;recoverSince=now;}}
      else{good=0;recoverSince=-1;}
    }
    void pace()throws InterruptedException{long wait;synchronized(this){wait=interval-(clock.getAsLong()-lastSent);}if(wait>0)Thread.sleep(wait);synchronized(this){lastSent=clock.getAsLong();}}
  }
  static boolean rttSample(String method,JSONObject args){return method.equals("linkPing")||method.equals("keepControl")||method.equals("input")&&Arrays.asList("move","relative","button","key","wheel").contains(args.optString("kind"));}
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
