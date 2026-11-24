package com.lanlink.app;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Regression coverage for asset reads on Android versions without readAllBytes(). */
public final class AssetTextTest {
  public static void main(String[] args) throws Exception {
    for (String expected : new String[] {"", "中文 · English 🌍", "语言".repeat(10000)}) {
      InputStream input = new ByteArrayInputStream(expected.getBytes(StandardCharsets.UTF_8)) {
        @Override public byte[] readAllBytes() {
          throw new NoSuchMethodError("Android 10 has no InputStream.readAllBytes");
        }
        @Override public synchronized int read(byte[] buffer, int offset, int length) {
          return super.read(buffer, offset, Math.min(length, 7));
        }
      };
      if (!expected.equals(AssetText.read(input))) throw new AssertionError("Asset text changed");
    }
    IOException failure = new IOException("Asset read failed");
    try {
      AssetText.read(new InputStream() {
        @Override public int read() throws IOException { throw failure; }
      });
      throw new AssertionError("Read failure was swallowed");
    } catch (IOException actual) {
      if (actual != failure) throw new AssertionError("Read failure changed");
    }
    System.out.println("AssetText tests passed");
  }
}
