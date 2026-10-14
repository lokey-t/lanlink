package com.lanlink.app;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** In-memory slow/failing senders exercise ordering without delivering remote input. */
final class RemotePointerProbe {
  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static void waitFor(CountDownLatch latch, String message) throws Exception {
    check(latch.await(3, TimeUnit.SECONDS), message);
  }

  private static void finish(RemotePointer pointer) throws Exception {
    pointer.close();
    check(pointer.worker.awaitTermination(3, TimeUnit.SECONDS), "pointer worker finishes after close");
  }

  private static void move(JSONObject event, float x, float y, String message) {
    check("move".equals(event.optString("kind"))
        && Math.abs(event.optDouble("x") - x) < .00001
        && Math.abs(event.optDouble("y") - y) < .00001, message + ": " + event);
  }

  private static void button(JSONObject event, boolean down, String message) {
    check("button".equals(event.optString("kind")) && event.optInt("button") == 0
        && event.optBoolean("down") == down, message + ": " + event);
  }

  static String run() throws Exception {
    orderedDragAndTap();
    closeReleases();
    failureReleases();
    return "PASS: ordered absolute mouse input, slow-sender coalescing and close/failure release";
  }

  private static void orderedDragAndTap() throws Exception {
    List<JSONObject> events = new CopyOnWriteArrayList<>();
    List<Exception> errors = new CopyOnWriteArrayList<>();
    CountDownLatch firstSend = new CountDownLatch(1), resume = new CountDownLatch(1);
    AtomicBoolean first = new AtomicBoolean(true);
    RemotePointer pointer = new RemotePointer(event -> {
      if (first.getAndSet(false)) {
        firstSend.countDown();
        waitFor(resume, "slow sender resumed");
      }
      events.add(event);
    }, errors::add);
    try {
      pointer.start(.1f, .2f);
      waitFor(firstSend, "first move starts on the worker");
      for (int i = 0; i < 200; i++) pointer.move(i / 250f, i / 300f);
      pointer.end(.8f, .9f);
      pointer.tap(.3f, .4f);
      pointer.close();
      resume.countDown();
      finish(pointer);
      check(events.size() == 7, "pending drag moves coalesce without merging across button events");
      move(events.get(0), .1f, .2f, "drag begins at its initial position");
      button(events.get(1), true, "drag presses after its initial move");
      move(events.get(2), .8f, .9f, "last drag position survives slow transport");
      button(events.get(3), false, "drag releases after its final position");
      move(events.get(4), .3f, .4f, "subsequent tap has its own position");
      button(events.get(5), true, "tap press stays after preceding drag release");
      button(events.get(6), false, "tap release stays after its press");
      check(errors.isEmpty(), "ordered input has no errors");
    } finally {
      resume.countDown();
      finish(pointer);
    }
  }

  private static void closeReleases() throws Exception {
    List<JSONObject> events = new CopyOnWriteArrayList<>();
    List<Exception> errors = new CopyOnWriteArrayList<>();
    CountDownLatch pressed = new CountDownLatch(1), resume = new CountDownLatch(1);
    RemotePointer pointer = new RemotePointer(event -> {
      events.add(event);
      if ("button".equals(event.optString("kind")) && event.optBoolean("down")) {
        pressed.countDown();
        waitFor(resume, "held press resumed");
      }
    }, errors::add);
    try {
      pointer.start(.2f, .3f);
      waitFor(pressed, "drag press is in flight");
      pointer.move(.7f, .8f);
      pointer.close();
      pointer.close();
      pointer.end(.9f, 1f);
      pointer.tap(.4f, .5f);
      resume.countDown();
      finish(pointer);
      check(events.size() == 4, "closing an active drag emits exactly one release");
      move(events.get(2), .7f, .8f, "close preserves the last queued position");
      button(events.get(3), false, "close releases an in-flight drag");
      check(errors.isEmpty(), "closing ignores subsequent gestures without errors");
    } finally {
      resume.countDown();
      finish(pointer);
    }
  }

  private static void failureReleases() throws Exception {
    List<JSONObject> events = new CopyOnWriteArrayList<>();
    List<Exception> errors = new CopyOnWriteArrayList<>();
    CountDownLatch pressed = new CountDownLatch(1), resume = new CountDownLatch(1);
    AtomicBoolean fail = new AtomicBoolean(true);
    RemotePointer pointer = new RemotePointer(event -> {
      events.add(event);
      if ("button".equals(event.optString("kind")) && event.optBoolean("down")
          && fail.getAndSet(false)) {
        pressed.countDown();
        waitFor(resume, "failing press resumed");
        throw new IOException("reply lost after remote press");
      }
    }, errors::add);
    try {
      pointer.start(.1f, .2f);
      waitFor(pressed, "failing drag reaches its press");
      pointer.move(.8f, .9f);
      pointer.end(.9f, 1f);
      pointer.tap(.3f, .4f);
      resume.countDown();
      pointer.worker.submit(() -> {}).get(3, TimeUnit.SECONDS);
      check(events.size() == 3, "failed send discards remaining positions and queued clicks");
      button(events.get(2), false, "failed press is followed by a best-effort release");
      check(errors.size() == 1, "send failure is reported once");
      pointer.move(.5f, .6f);
      pointer.end(.5f, .6f);
      pointer.worker.submit(() -> {}).get(3, TimeUnit.SECONDS);
      check(events.size() == 3, "old drag updates cannot resume after failure");
      pointer.tap(.4f, .5f);
      finish(pointer);
      check(events.size() == 6, "a fresh gesture can recover after a transient failure");
      move(events.get(3), .4f, .5f, "recovery starts at the new gesture position");
      button(events.get(4), true, "recovery press");
      button(events.get(5), false, "recovery release");
    } finally {
      resume.countDown();
      finish(pointer);
    }
  }
}
