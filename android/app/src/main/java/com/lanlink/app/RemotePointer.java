package com.lanlink.app;

import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.json.JSONObject;

/** Ordered absolute mouse input. Slow transport cannot reorder a press and its release. */
final class RemotePointer implements VideoViewport.DragListener, AutoCloseable {
  final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final TouchpadView.Sender sender;
  private final Consumer<Exception> onError;
  private final ArrayDeque<JSONObject> pending = new ArrayDeque<>();
  private boolean draining, dragging, closed;
  private float x, y;

  RemotePointer(TouchpadView.Sender sender, Consumer<Exception> onError) {
    this.sender = sender;
    this.onError = onError;
  }

  synchronized void tap(float x, float y) {
    if (closed || !valid(x, y)) return;
    releaseDrag();
    position(x, y);
    button(true);
    button(false);
    schedule();
  }

  @Override public synchronized void start(float x, float y) {
    if (closed || !valid(x, y)) return;
    releaseDrag();
    position(x, y);
    button(true);
    dragging = true;
    schedule();
  }

  @Override public synchronized void move(float x, float y) {
    if (closed || !dragging || !valid(x, y)) return;
    position(x, y);
    schedule();
  }

  @Override public synchronized void end(float x, float y) {
    if (closed || !dragging) return;
    if (valid(x, y)) position(x, y);
    releaseDrag();
    schedule();
  }

  private static boolean valid(float x, float y) {
    return Float.isFinite(x) && Float.isFinite(y);
  }

  /** Called while holding the monitor; merging must never cross a button boundary. */
  private void position(float x, float y) {
    this.x = Math.max(0, Math.min(1, x));
    this.y = Math.max(0, Math.min(1, y));
    JSONObject previous = pending.peekLast();
    if (previous != null && "move".equals(previous.optString("kind"))) pending.removeLast();
    pending.addLast(Wire.obj("kind", "move", "x", this.x, "y", this.y));
  }

  private void button(boolean down) {
    pending.addLast(Wire.obj("kind", "button", "button", 0, "down", down));
  }

  private void releaseDrag() {
    if (!dragging) return;
    position(x, y);
    button(false);
    dragging = false;
  }

  private void schedule() {
    if (draining || pending.isEmpty()) return;
    draining = true;
    worker.execute(this::drain);
  }

  private void drain() {
    while (true) {
      JSONObject event;
      synchronized (this) {
        event = pending.pollFirst();
        if (event == null) {
          draining = false;
          return;
        }
      }
      try {
        sender.send(event);
      } catch (Exception error) {
        synchronized (this) {
          pending.clear();
          dragging = false;
        }
        // The remote may have applied an input before its reply failed. Always try
        // releasing the button before allowing a subsequent gesture onto the wire.
        try { sender.send(Wire.obj("kind", "button", "button", 0, "down", false)); }
        catch (Exception releaseError) {
          if (releaseError != error) error.addSuppressed(releaseError);
        }
        try { onError.accept(error); }
        catch (RuntimeException ignored) { /* A reporting callback must not wedge the queue. */ }
      }
    }
  }

  @Override public synchronized void close() {
    if (closed) return;
    releaseDrag();
    closed = true;
    schedule();
    // shutdown() lets the already scheduled drain finish, without blocking the UI.
    worker.shutdown();
  }
}
