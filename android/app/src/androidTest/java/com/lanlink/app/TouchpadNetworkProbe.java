package com.lanlink.app;

import android.app.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.net.Socket;
import java.util.concurrent.*;
import org.json.JSONObject;

/** The only real desktop input in this probe is a bounded pointer move, never a click. */
final class TouchpadNetworkProbe {
  static String run(Instrumentation test, Activity activity, LinkService service, Bundle args)
      throws Exception {
    CountDownLatch done = new CountDownLatch(1);
    String[] failure = {null};
    Wire peer =
        new Wire(
            new Socket("10.0.2.2", Integer.parseInt(args.getString("touchpadPort"))),
            Wire.obj(
                "id",
                service.deviceId,
                "name",
                "Touchpad test phone",
                "capabilities",
                service.capabilities()),
            Wire.parseToken(args.getString("touchpadToken")),
            new Wire.Listener() {
              public void ready(Wire p) {
                service.ready(p);
              }

              public void closed(Wire p) {
                service.closed(p);
              }

              public void message(Wire p, JSONObject m) {
                if (m.optString("type").equals("request")
                    && m.optString("method").equals("testMove")) {
                  service.jobs.execute(
                      () -> {
                        TouchpadView[] pad = {null};
                        FrameLayout[] parent = {null};
                        CountDownLatch moved = new CountDownLatch(1);
                        try {
                          service.control(p.id, true);
                          test.runOnMainSync(
                              () -> {
                                pad[0] =
                                    new TouchpadView(
                                        activity,
                                        event -> {
                                          service.remoteInput(p.id, event);
                                          if (event.optString("kind").equals("relative"))
                                            moved.countDown();
                                        },
                                        error -> {
                                          failure[0] = error.toString();
                                          moved.countDown();
                                        });
                                parent[0] = new FrameLayout(activity);
                                parent[0].addView(pad[0], new FrameLayout.LayoutParams(400, 400));
                                activity.addContentView(
                                    parent[0], new FrameLayout.LayoutParams(400, 400));
                                long now = SystemClock.uptimeMillis();
                                float end = m.optJSONObject("args").optBoolean("left") ? 40 : 260;
                                for (MotionEvent e :
                                    new MotionEvent[] {
                                      MotionEvent.obtain(now, now, 0, 150, 150, 0),
                                      MotionEvent.obtain(now, now + 80, 2, end, 150, 0),
                                      MotionEvent.obtain(now, now + 120, 1, end, 150, 0)
                                    }) {
                                  pad[0].onTouchEvent(e);
                                  e.recycle();
                                }
                              });
                          if (!moved.await(8, TimeUnit.SECONDS) || failure[0] != null)
                            throw new AssertionError("touchpad input not delivered: " + failure[0]);
                          long cursorDeadline = System.currentTimeMillis() + 3000;
                          while (!p.id.equals(service.cursorPeer)
                              && System.currentTimeMillis() < cursorDeadline) Thread.sleep(20);
                          if (!p.id.equals(service.cursorPeer) || service.remoteCursor == null)
                            throw new AssertionError("authenticated cursor feedback missing");
                          p.send(
                              Wire.obj(
                                  "type",
                                  "response",
                                  "id",
                                  m.optString("id"),
                                  "result",
                                  Wire.obj("moved", true)));
                        } catch (Throwable e) {
                          failure[0] = e.toString();
                          done.countDown();
                        } finally {
                          service.releaseControl();
                          test.runOnMainSync(
                              () -> {
                                if (pad[0] != null) pad[0].close();
                                if (parent[0] != null)
                                  ((android.view.ViewGroup) parent[0].getParent())
                                      .removeView(parent[0]);
                              });
                        }
                      });
                } else if (m.optString("type").equals("request")
                    && m.optString("method").equals("testDone")) {
                  try {
                    p.send(
                        Wire.obj(
                            "type", "response", "id", m.optString("id"), "result", Wire.obj()));
                  } catch (Exception e) {
                  }
                  done.countDown();
                } else service.message(p, m);
              }
            });
    try {
      peer.outgoing = true;
      peer.start();
      if (!done.await(50, TimeUnit.SECONDS)) throw new AssertionError("touchpad network timeout");
      if (failure[0] != null) throw new AssertionError(failure[0]);
      return "PASS: touchpad MotionEvent -> encrypted desktop input";
    } finally {
      service.releaseControl();
      peer.close();
    }
  }
}
