package com.lanlink.app;

interface VideoSource extends AutoCloseable {
  void setBitrate(int rate);
  default void setFrameRate(int fps) {}

  void requestKey();

  void close();
}
