package com.lanlink.app;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;

final class ApkDelta {
  private static final byte[] MAGIC = "LLAPK001".getBytes(StandardCharsets.US_ASCII);

  private ApkDelta() {}

  static void apply(File patch, File installed, File output, long expectedSize, String expectedSha)
      throws IOException, NoSuchAlgorithmException {
    byte[] buffer = new byte[64 * 1024];
    MessageDigest hash = MessageDigest.getInstance("SHA-256");
    try (FileInputStream compressed = new FileInputStream(patch);
         DataInputStream in = new DataInputStream(new GZIPInputStream(compressed, buffer.length));
         RandomAccessFile base = new RandomAccessFile(installed, "r")) {
      byte[] magic = new byte[MAGIC.length];
      byte[] baseSha = new byte[32];
      in.readFully(magic);
      if (!Arrays.equals(magic, MAGIC)) throw new IOException("Unsupported APK delta format");
      in.readFully(baseSha);
      for (int n; (n = base.read(buffer)) != -1;) hash.update(buffer, 0, n);
      if (!MessageDigest.isEqual(baseSha, hash.digest())) throw new IOException("Installed APK checksum mismatch");

      long remaining = expectedSize;
      try (FileOutputStream out = new FileOutputStream(output)) {
        for (int op; (op = in.readUnsignedByte()) != 0;) {
          int length = in.readInt();
          if (length <= 0 || length > remaining) throw new IOException("Invalid APK delta length");
          if (op == 1) {
            int offset = in.readInt();
            if (offset < 0 || (long) offset + length > base.length()) throw new IOException("Invalid APK delta range");
            base.seek(offset);
          } else if (op != 2) {
            throw new IOException("Invalid APK delta operation");
          }
          remaining -= length;
          while (length > 0) {
            int count = Math.min(length, buffer.length);
            if (op == 1) base.readFully(buffer, 0, count);
            else in.readFully(buffer, 0, count);
            out.write(buffer, 0, count);
            hash.update(buffer, 0, count);
            length -= count;
          }
        }
        if (remaining != 0 || in.read() != -1 || !hex(hash.digest()).equals(expectedSha))
          throw new IOException("Reconstructed APK checksum mismatch");
      }
    }
  }

  static String hex(byte[] bytes) {
    StringBuilder result = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      result.append(Character.forDigit((b & 255) >>> 4, 16));
      result.append(Character.forDigit(b & 15, 16));
    }
    return result.toString();
  }
}
