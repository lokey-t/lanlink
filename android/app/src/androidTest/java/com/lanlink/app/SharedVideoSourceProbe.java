package com.lanlink.app;

/** Deterministic fan-out and lifecycle checks without allocating a hardware encoder. */
final class SharedVideoSourceProbe {
  private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
  static final class Source implements VideoSource {
    int rate, keys, closed;
    public void setBitrate(int value) { rate = value; }
    public void requestKey() { keys++; }
    public void close() { closed++; }
  }
  static final class Output implements VideoEncoder.Output {
    int frames, failed;
    public void frame(VideoWire.Frame value) { frames++; }
    public void failed() { failed++; }
  }
  static void run() throws Exception {
    int[] idle = {0};
    Source source = new Source();
    SharedVideoSource shared = new SharedVideoSource(() -> idle[0]++);
    shared.bind(source);
    Output a = new Output(), b = new Output();
    VideoSource first = shared.attach(a), second = shared.attach(b);
    VideoWire.Frame frame = new VideoWire.Frame(new byte[] {1}, 10, 10, true, true);
    shared.frame(frame);
    check(a.frames == 1 && b.frames == 1, "both computers receive the same device frame");
    first.setBitrate(900_000); second.setBitrate(6_000_000);
    check(source.rate == 900_000, "weakest active viewer determines shared encoder bitrate");
    first.close(); first.close();
    check(source.closed == 0 && idle[0] == 0 && source.rate == 6_000_000, "closing one viewer preserves the other and releases its bitrate constraint");
    first.setBitrate(150_000); first.requestKey();
    check(source.rate == 6_000_000 && source.keys == 2, "stale lease cannot affect another viewer");
    shared.frame(frame);
    check(a.frames == 1 && b.frames == 2, "remaining viewer continues without restart");
    second.close();
    check(idle[0] == 1 && shared.empty(), "last viewer releases source exactly once");
    shared.close(); shared.close();
    check(source.closed == 1, "encoder closes exactly once");
    Source failing = new Source(); Output x = new Output(), y = new Output();
    SharedVideoSource failure = new SharedVideoSource(() -> {}); failure.bind(failing);
    failure.attach(x); failure.attach(y); failure.failed(); failure.failed();
    check(x.failed == 1 && y.failed == 1 && failing.closed == 1, "source failure reaches every viewer once");
  }
}
