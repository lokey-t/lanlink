package com.lanlink.app;

import java.io.*;
import java.security.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;

/** Mutual proof before accepting privileged commands, including after app restarts. */
final class ShellAuth {
  static String nonce() {
    byte[] value = new byte[24];
    new SecureRandom().nextBytes(value);
    return java.util.Base64.getEncoder().encodeToString(value);
  }

  static String proof(String secret, String role, String server, String client) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(Wire.parseToken(secret), "HmacSHA256"));
    return java.util.Base64.getEncoder()
        .encodeToString(
            mac.doFinal(Wire.bytes("lanlink-shell-v2|" + role + "|" + server + "|" + client)));
  }

  static void write(BufferedWriter out, JSONObject value) throws IOException {
    out.write(value.toString());
    out.write('\n');
    out.flush();
  }

  static String line(Reader in) throws IOException {
    StringBuilder value = new StringBuilder();
    int c;
    while ((c = in.read()) != -1 && c != '\n') {
      if (value.length() >= 65536) throw new IOException("消息过大");
      value.append((char) c);
    }
    if (c == -1) throw new EOFException();
    return value.toString();
  }

  static void check(String expected, String actual) throws IOException {
    if (!MessageDigest.isEqual(Wire.bytes(expected), Wire.bytes(actual)))
      throw new IOException("ADB 助手身份校验失败");
  }

  static int server(String secret, BufferedReader in, BufferedWriter out) throws Exception {
    String s = nonce();
    write(out, Wire.obj("challenge", s, "protocol", 2));
    JSONObject reply = new JSONObject(line(in));
    String c = reply.getString("nonce");
    if (c.length() != 32) throw new IOException("握手无效");
    check(proof(secret, "helper", s, c), reply.getString("proof"));
    write(out, Wire.obj("proof", proof(secret, "app", s, c)));
    return reply.optInt("agentVersion", 0);
  }

  static void client(String secret, BufferedReader in, BufferedWriter out) throws Exception {
    JSONObject hello = new JSONObject(line(in));
    String s = hello.getString("challenge"), c = nonce();
    if (hello.optInt("protocol") != 2 || s.length() != 32) throw new IOException("助手协议不兼容");
    write(out, Wire.obj("nonce", c, "proof", proof(secret, "helper", s, c), "agentVersion", 3));
    check(proof(secret, "app", s, c), new JSONObject(line(in)).getString("proof"));
  }
}
