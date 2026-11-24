package com.lanlink.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Reads UTF-8 assets using stream APIs available on Android 10. */
final class AssetText {
  static String read(InputStream input) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int count;
    while ((count = input.read(buffer)) != -1) {
      output.write(buffer, 0, count);
    }
    return new String(output.toByteArray(), StandardCharsets.UTF_8);
  }
}
