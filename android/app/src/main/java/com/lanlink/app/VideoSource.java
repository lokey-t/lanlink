package com.lanlink.app;

interface VideoSource extends AutoCloseable {
  void setBitrate(int rate);

  void requestKey();

  void close();
}
