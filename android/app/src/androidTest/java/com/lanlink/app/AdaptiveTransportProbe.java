package com.lanlink.app;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** Deterministic weak-link regressions; no device input or screen permission required. */
final class AdaptiveTransportProbe {
  static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
  static String run() throws Exception {
    long[] clock={1000};
    QuicMotion.Sender sender=new QuicMotion.Sender(()->clock[0]);
    QuicMotion.Receiver receiver=new QuicMotion.Receiver();
    receiver.accept(sender.move(Wire.obj("kind","relative","dx",5,"dy",0)));
    clock[0]+=30;sender.move(Wire.obj("kind","relative","dx",10,"dy",0));
    clock[0]+=30;List<JSONObject> recovered=receiver.accept(sender.move(Wire.obj("kind","relative","dx",15,"dy",0)));
    check(recovered.size()==1&&recovered.get(0).getDouble("dx")==25,"brief loss preserves physical distance");
    for(int i=0;i<20;i++){clock[0]+=50;sender.move(Wire.obj("kind","relative","dx",10,"dy",0));}
    recovered=receiver.accept(sender.snapshot);check(recovered.size()==1&&recovered.get(0).getDouble("dx")==30,"long outage keeps only fresh movement");
    sender.move(Wire.obj("kind","relative","dx",600,"dy",0));clock[0]+=5000;
    check(receiver.accept(sender.barrier()).isEmpty(),"late click does not replay old movement");
    check(receiver.accept(sender.snapshot).isEmpty(),"delayed datagram is consumed by barrier");
    check(receiver.accept(sender.move(Wire.obj("kind","relative","dx",5,"dy",0))).get(0).getDouble("dx")==5,"live movement resumes from new baseline");

    List<Runnable> work=new ArrayList<>();List<Integer> delivered=new ArrayList<>();QuicInputQueue queue=new QuicInputQueue(work::add);
    queue.add(null,()->delivered.add(0));queue.add(Wire.obj("kind","move","epoch",sender.epoch,"seq",3),()->delivered.add(3));queue.add(Wire.obj("kind","move","epoch",sender.epoch,"seq",2),()->delivered.add(2));
    while(!work.isEmpty())work.remove(0).run();check(delivered.equals(java.util.Arrays.asList(0,3)),"reordering cannot replace a newer queued snapshot");

    AdaptiveVideo adaptive=new AdaptiveVideo(()->clock[0]),other=new AdaptiveVideo(()->clock[0]);
    for(int i=0;i<20;i++){clock[0]+=1000;adaptive.feedback(Wire.obj("delayMs",0,"rttMs",350),0);}
    check(adaptive.rate>=1500000&&adaptive.rate<=2000000,"initial slow link has finite bitrate budget");check(other.rate==4000000,"another viewer is independent");
    for(int i=0;i<20;i++){clock[0]+=2100;adaptive.feedback(Wire.obj("delayMs",200,"rttMs",350),0);}
    check(adaptive.rate==500000,"minimum bitrate is bounded");
    for(int i=0;i<30;i++){clock[0]+=1000;adaptive.feedback(Wire.obj("delayMs",0,"rttMs",10,"frames",0,"sampleAgeMs",5000),0);}
    check(adaptive.rate==500000,"stale reports cannot raise bitrate");
    for(int i=0;i<180;i++){clock[0]+=1000;adaptive.feedback(Wire.obj("delayMs",0,"rttMs",10,"frames",30),0);}
    check(adaptive.rate==8000000,"healthy link gradually recovers within upper bound");
    motionPacing();
    InputHeartbeatProbe.run();
    return "PASS: short/long mouse loss, stale click, reordered queue, per-viewer bitrate, 4-24ms adaptive mouse pacing and independent encrypted TCP/USB heartbeats";
  }

  static void motionPacing() {
    long[] now={0};
    QuicMotion.Pacing stable=new QuicMotion.Pacing(()->now[0]);
    for(int i=0;i<20;i++){now[0]+=200;stable.feedback(200d,0L);}
    check(stable.interval==4,"stable propagation RTT does not slow mouse");
    QuicMotion.Pacing newRoute=new QuicMotion.Pacing(()->now[0]);
    newRoute.feedback(8d,0L);
    for(int i=0;i<120;i++){now[0]+=100;newRoute.feedback(200d,0L);}
    check(newRoute.interval==4&&newRoute.rttBuckets.size()<=20,"stable replacement route recovers within 12s with bounded RTT history");
    QuicMotion.Pacing queue=new QuicMotion.Pacing(()->now[0]);
    queue.feedback(null,131072L);check(queue.interval==24,"severe queue pressure has a 24ms ceiling");
    now[0]+=150;queue.feedback(10d,null);check(queue.interval==24,"RTT-only report cannot erase fresh queue pressure");
    int good=queue.good;long queueAt=queue.queueAt,rttAt=queue.rttAt;
    queue.feedback(null,null);check(queue.good==good&&queue.queueAt==queueAt&&queue.rttAt==rttAt,"empty report preserves all feedback state");
    queue.feedback(Double.NaN,0L);check(queue.queueAt==queueAt,"invalid sample cannot clear valid queue pressure");
    for(int i=0;i<20;i++){now[0]+=100;queue.feedback(null,0L);}
    check(queue.interval==4,"healthy Datagram queue reports recover without reliable RPC samples");
    QuicMotion.Pacing spike=new QuicMotion.Pacing(()->now[0]);
    spike.feedback(10d,0L);now[0]+=200;spike.feedback(210d,null);
    check(spike.interval==8,"rising RTT adds one bounded pacing step");
    now[0]+=200;spike.feedback(210d,null);check(spike.interval==12,"sustained RTT growth reaches modest coalescing delay");
    for(int i=0;i<35;i++){now[0]+=100;spike.feedback(null,0L);}
    check(spike.interval==4,"expired RTT sample cannot prevent queue-led recovery");
    QuicMotion.Pacing expiredQueue=new QuicMotion.Pacing(()->now[0]);
    expiredQueue.feedback(null,131072L);
    for(int i=0;i<20;i++){now[0]+=150;expiredQueue.feedback(20d,null);}
    check(expiredQueue.interval==4,"expired queue pressure does not survive fresh healthy RTT indefinitely");
    check(QuicMotion.rttSample("keepControl",Wire.obj())&&QuicMotion.rttSample("linkPing",Wire.obj()),"lightweight lease and ping are RTT samples");
    for(String kind:new String[]{"move","relative","button","key","wheel"})check(QuicMotion.rttSample("input",Wire.obj("kind",kind)),"input RTT sample "+kind);
    for(String method:new String[]{"videoOpen","videoKeep","fileBytes","text","acquire"})check(!QuicMotion.rttSample(method,Wire.obj()),"application work is not RTT: "+method);
    check(!QuicMotion.rttSample("input",Wire.obj("kind","navigation")),"slow navigation action is not RTT");
    check(TouchpadView.nextPollDelay(0)==4&&TouchpadView.nextPollDelay(4)==0&&TouchpadView.nextPollDelay(8)==0&&TouchpadView.nextPollDelay(200)==0,"touchpad subtracts RPC duration instead of adding fixed latency");
  }
}
