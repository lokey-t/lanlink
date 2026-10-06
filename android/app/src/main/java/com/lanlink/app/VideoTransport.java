package com.lanlink.app;

interface VideoTransport extends AutoCloseable {
  void ready() throws Exception;
  long queued();
  boolean send(VideoWire.Frame frame) throws Exception;
  VideoWire.Frame next() throws Exception;
  boolean needsKey();
  void keyCallback(Runnable callback);
  void close();
}
