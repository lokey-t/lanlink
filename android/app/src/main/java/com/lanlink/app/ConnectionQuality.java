package com.lanlink.app;
import java.util.ArrayList;
import java.util.Locale;
final class ConnectionQuality {
 static String analyze(Wire peer) throws Exception {
  ArrayList<Double> samples=new ArrayList<>();
  for(int i=0;i<10;i++) {
   long start=android.os.SystemClock.elapsedRealtimeNanos();
   try {peer.requestDirect("linkPing",Wire.obj(),1000);samples.add((android.os.SystemClock.elapsedRealtimeNanos()-start)/1e6);}catch(Exception ignored){}
   if(i<9)Thread.sleep(100);
  }
  double average=0,jitter=0;for(double n:samples)average+=n;
  if(!samples.isEmpty())average/=samples.size();
  for(int i=1;i<samples.size();i++)jitter+=Math.abs(samples.get(i)-samples.get(i-1));
  if(samples.size()>1)jitter/=samples.size()-1;
  int loss=(10-samples.size())*10;
  String grade=samples.isEmpty()?"无法连接":loss>=20||average>=150||jitter>=50?"较差":loss>0||average>=60||jitter>=20?"一般":"良好";
  return Language.t(grade)+"\n"+Language.t("平均往返延迟：")+(samples.isEmpty()?"—":String.format(Locale.ROOT,"%.1f ms",average))+"\n"+Language.t("抖动：")+(samples.size()<2?"—":String.format(Locale.ROOT,"%.1f ms",jitter))+"\n"+Language.t("探测超时率：")+loss+"% (10)\n"+Language.t(grade.equals("良好")?"连接稳定，适合键鼠和屏幕协作。":"建议靠近路由器、减少网络占用，或使用有线网络 / USB 后重测。")+"\n"+Language.t("仅测量加密 TCP 控制通道；超时率不等于网络丢包率，不代表传输带宽。");
 }
}
