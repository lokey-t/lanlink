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
    QuicMotion.Pacing pacing=new QuicMotion.Pacing();pacing.feedback(200,0);check(pacing.interval==50,"initial high RTT slows mouse without losing input");
    return "PASS: short/long mouse loss, stale click, reordered queue, per-viewer bitrate, slow-link budget, bounded recovery and mouse pacing";
  }
}
