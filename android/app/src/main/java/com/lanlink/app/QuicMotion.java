package com.lanlink.app;

import java.io.IOException;
import java.util.*;
import org.json.JSONObject;

/** Cumulative raw motion survives loss, including absolute moves at a clamped edge. */
final class QuicMotion {
  static final class Sender {
    String epoch;long seq;double dx,dy;JSONObject snapshot;
    Sender(){reset();}
    void reset(){epoch=UUID.randomUUID().toString();seq=0;dx=dy=0;snapshot=null;}
    JSONObject move(JSONObject a)throws Exception{
      String kind=a.getString("kind");
      if(kind.equals("relative")){double x=a.getDouble("dx"),y=a.getDouble("dy");if(!Double.isFinite(x)||!Double.isFinite(y)||Math.abs(x)>1000||Math.abs(y)>1000)throw new IOException("鼠标位移无效");dx+=x;dy+=y;}
      else {
        if(!kind.equals("move")||!unit(a.getDouble("x"))||!unit(a.getDouble("y")))throw new IOException("鼠标位置无效");
        if(a.has("dx")||a.has("dy")){double x=a.getDouble("dx"),y=a.getDouble("dy");if(!Double.isFinite(x)||!Double.isFinite(y)||Math.abs(x)>32000||Math.abs(y)>32000)throw new IOException("鼠标位移无效");dx+=x;dy+=y;}
      }
      snapshot=Wire.obj("epoch",epoch,"seq",++seq,"kind",kind,"dx",dx,"dy",dy);
      if(kind.equals("move")){snapshot.put("x",a.getDouble("x"));snapshot.put("y",a.getDouble("y"));if(a.has("dx"))snapshot.put("raw",true);}return snapshot;
    }
  }
  static boolean unit(double n){return Double.isFinite(n)&&n>=0&&n<=1;}
  static final class Receiver {
    String epoch;long seq;double dx,dy;
    void reset(String next){epoch=next;seq=0;dx=dy=0;}
    List<JSONObject> accept(JSONObject m)throws Exception {
      String next=m.getString("epoch"),kind=m.getString("kind");long number=m.getLong("seq");double x=m.getDouble("dx"),y=m.getDouble("dy");
      if(next.length()!=36||number<=0||number>9007199254740991L||!Double.isFinite(x)||!Double.isFinite(y)||Math.abs(x)>1e12||Math.abs(y)>1e12||!kind.equals("relative")&&!kind.equals("move"))throw new IOException("鼠标 Datagram 无效");
      if(kind.equals("move")&&(!unit(m.getDouble("x"))||!unit(m.getDouble("y"))||m.has("raw")&&!(m.get("raw") instanceof Boolean)))throw new IOException("鼠标位置无效");
      if(epoch==null)epoch=next;else if(!epoch.equals(next))throw new IOException("鼠标会话已结束");
      List<JSONObject> result=new ArrayList<>();if(number<=seq)return result;
      double remainingX=x-dx,remainingY=y-dy;
      if(Math.abs(remainingX)>32000||Math.abs(remainingY)>32000){
        if(!kind.equals("move")||!m.optBoolean("raw"))throw new IOException("鼠标恢复位移过大");
        // Consume an excessive stale gap so subsequent live movement can recover.
        remainingX=remainingY=0;
      }
      seq=number;dx=x;dy=y;
      if(kind.equals("move")&&!m.optBoolean("raw")){result.add(Wire.obj("kind","move","x",m.getDouble("x"),"y",m.getDouble("y")));return result;}
      if(kind.equals("relative")&&Math.abs(remainingX)<=0.000001&&Math.abs(remainingY)<=0.000001)return result;
      do {double stepX=Math.max(-1000,Math.min(1000,remainingX)),stepY=Math.max(-1000,Math.min(1000,remainingY));JSONObject input=Wire.obj("kind",kind,"dx",stepX,"dy",stepY);if(kind.equals("move")){input.put("x",m.getDouble("x"));input.put("y",m.getDouble("y"));}result.add(input);remainingX-=stepX;remainingY-=stepY;}while(Math.abs(remainingX)>0.000001||Math.abs(remainingY)>0.000001);return result;
    }
  }
}
