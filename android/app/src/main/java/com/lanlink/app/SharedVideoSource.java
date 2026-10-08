package com.lanlink.app;

import java.io.IOException;
import java.util.*;

/** One device encoder, independent viewer leases and a bitrate safe for every active viewer. */
final class SharedVideoSource implements VideoEncoder.Output, VideoSource {
  private final Map<Lease, Integer> viewers = new LinkedHashMap<>();
  private final Runnable idle;
  private VideoSource source;
  private boolean closed;
  private int bitrate = 4_000_000;

  SharedVideoSource(Runnable idle) { this.idle = idle; }

  synchronized void bind(VideoSource value) throws IOException {
    if (closed) { value.close(); throw new IOException("屏幕串流已停止"); }
    source = value;
    value.setBitrate(bitrate);
  }

  synchronized VideoSource attach(VideoEncoder.Output output) throws IOException {
    if (closed || source == null) throw new IOException("屏幕串流已停止");
    Lease lease = new Lease(output);
    viewers.put(lease, 4_000_000);
    updateRate();
    source.requestKey();
    return lease;
  }

  synchronized boolean empty() { return viewers.isEmpty(); }
  synchronized boolean running() { return !closed; }

  private void updateRate() {
    int minimum = 8_000_000;
    for (int value : viewers.values()) minimum = Math.min(minimum, value);
    if (viewers.isEmpty()) minimum = 4_000_000;
    if (minimum == bitrate) return;
    bitrate = minimum;
    if (source != null) source.setBitrate(minimum);
  }

  private synchronized List<Lease> snapshot() { return new ArrayList<>(viewers.keySet()); }

  public void frame(VideoWire.Frame frame) {
    for (Lease lease : snapshot()) if (!lease.closed) {
      try { lease.output.frame(frame); }
      catch (RuntimeException error) { lease.close(); lease.output.failed(); }
    }
  }

  public void failed() {
    List<Lease> targets = snapshot();
    close();
    for (Lease lease : targets) lease.output.failed();
  }

  public synchronized void setBitrate(int rate) { /* Rates are supplied through individual leases. */ }
  public synchronized void requestKey() { if (!closed && source != null) source.requestKey(); }

  public synchronized void close() {
    if (closed) return;
    closed = true;
    for (Lease lease : viewers.keySet()) lease.closed = true;
    viewers.clear();
    if (source != null) source.close();
    source = null;
  }

  private final class Lease implements VideoSource {
    final VideoEncoder.Output output;
    volatile boolean closed;
    Lease(VideoEncoder.Output output) { this.output = output; }
    public void setBitrate(int rate) {
      synchronized (SharedVideoSource.this) {
        if (closed || !viewers.containsKey(this)) return;
        viewers.put(this, Math.max(500_000, Math.min(8_000_000, rate)));
        updateRate();
      }
    }
    public void requestKey() { if (!closed) SharedVideoSource.this.requestKey(); }
    public void close() {
      boolean last;
      synchronized (SharedVideoSource.this) {
        if (closed) return;
        closed = true;
        viewers.remove(this);
        updateRate();
        last = viewers.isEmpty();
      }
      if (last) idle.run();
    }
  }
}
