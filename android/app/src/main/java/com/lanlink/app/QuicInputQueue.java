package com.lanlink.app;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.json.JSONObject;

/** Merge pending cumulative motion while preserving click, key and ownership barriers. */
final class QuicInputQueue {
  static final class Job {JSONObject motion;Runnable work;Job(JSONObject motion,Runnable work){this.motion=motion;this.work=work;}}
  final ArrayDeque<Job> jobs=new ArrayDeque<>();final Executor executor;boolean running;
  QuicInputQueue(Executor executor){this.executor=executor;}
  synchronized void add(JSONObject motion,Runnable work){
    Job tail=jobs.peekLast();if(motion!=null&&tail!=null&&tail.motion!=null&&motion.optString("kind").equals(tail.motion.optString("kind"))&&motion.optString("epoch").equals(tail.motion.optString("epoch"))){tail.motion=motion;tail.work=work;return;}
    if(jobs.size()>=64)throw new RejectedExecutionException("QUIC 输入队列已满");jobs.addLast(new Job(motion,work));if(!running){running=true;try{executor.execute(this::next);}catch(RuntimeException error){running=false;jobs.clear();throw error;}}
  }
  void next(){Job job;synchronized(this){job=jobs.pollFirst();}try{if(job!=null)job.work.run();}finally{synchronized(this){if(jobs.isEmpty())running=false;else try{executor.execute(this::next);}catch(RuntimeException error){running=false;jobs.clear();}}}}
}
