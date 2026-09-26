package com.lanlink.app;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.security.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.json.JSONObject;

/** Binary video v1: challenge authentication, per-session AES-GCM, strict frame sequence. */
public final class VideoWire implements AutoCloseable {
  static final int MAX = 4 * 1024 * 1024;
  final Socket socket;
  final DataInputStream input;
  final DataOutputStream output;
  byte[] key;
  long sequence;

  public static final class Frame {
    public byte[] data;
    public int width, height;
    public boolean h264, key, heartbeat;
    public long time;

    public Frame(byte[] data, int width, int height, boolean h264, boolean key) {
      this.data = data;
      this.width = width;
      this.height = height;
      this.h264 = h264;
      this.key = key;
      this.time = System.currentTimeMillis();
    }
  }

  static byte[] random() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return bytes;
  }

  static byte[] join(byte[]... parts) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] p : parts) out.write(p);
    return out.toByteArray();
  }

  static byte[] mac(byte[] key, String label, byte[] sn, byte[] cn) throws Exception {
    return Wire.hmac(key, join(Wire.bytes(label), sn, cn));
  }

  static byte[] unhex(String hex) throws IOException {
    if (hex == null || !hex.matches("[a-f0-9]{64}")) throw new IOException("串流密钥无效");
    byte[] bytes = new byte[32];
    for (int i = 0; i < 32; i++)
      bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
    return bytes;
  }

  VideoWire(Socket socket) throws IOException {
    this.socket = socket;
    socket.setTcpNoDelay(true);
    socket.setSoTimeout(6000);
    input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
    output = new DataOutputStream(socket.getOutputStream());
  }

  static VideoWire accept(Socket socket, byte[] secret) throws Exception {
    VideoWire wire = new VideoWire(socket);
    byte[] sn = random(), cn = new byte[32], proof = new byte[32];
    wire.output.write(sn);
    wire.input.readFully(cn);
    wire.input.readFully(proof);
    if (!MessageDigest.isEqual(proof, mac(secret, "auth", sn, cn)))
      throw new IOException("串流身份验证失败");
    wire.output.write(mac(secret, "ack", sn, cn));
    wire.key = mac(secret, "media", sn, cn);
    return wire;
  }

  public static VideoWire connect(String host, JSONObject info) throws Exception {
    int port = info.getInt("port");
    if (info.optInt("protocol") != 1 || port < 1 || port > 65535) throw new IOException("串流协商无效");
    Socket socket = new Socket();
    try {
      socket.connect(new InetSocketAddress(host, port), 5000);
      VideoWire wire = new VideoWire(socket);
      byte[] secret = unhex(info.getString("key")),
          sn = new byte[32],
          cn = random(),
          ack = new byte[32];
      wire.input.readFully(sn);
      wire.output.write(join(cn, mac(secret, "auth", sn, cn)));
      wire.input.readFully(ack);
      if (!MessageDigest.isEqual(ack, mac(secret, "ack", sn, cn)))
        throw new IOException("串流身份验证失败");
      wire.key = mac(secret, "media", sn, cn);
      return wire;
    } catch (Exception error) {
      socket.close();
      throw error;
    }
  }

  private Cipher cipher(int mode) throws Exception {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        mode,
        new SecretKeySpec(key, "AES"),
        new GCMParameterSpec(128, ByteBuffer.allocate(12).putInt(0).putLong(sequence).array()));
    cipher.updateAAD(
        join(Wire.bytes("lanlink-video-v1"), ByteBuffer.allocate(8).putLong(sequence).array()));
    return cipher;
  }

  static void validate(Frame f) throws IOException {
    if (f.width < 1
        || f.height < 1
        || f.width > 4096
        || f.height > 4096
        || (long) f.width * f.height > 8388608
        || f.data.length > MAX - 64) throw new IOException("视频帧尺寸无效");
  }

  public synchronized void send(Frame f) throws Exception {
    validate(f);
    byte[] plain =
        ByteBuffer.allocate(14 + f.data.length)
            .put((byte) (f.heartbeat ? 3 : f.h264 ? 2 : 1))
            .put((byte) (f.key ? 1 : 0))
            .putShort((short) f.width)
            .putShort((short) f.height)
            .putLong(f.time)
            .put(f.data)
            .array();
    byte[] encrypted = cipher(Cipher.ENCRYPT_MODE).doFinal(plain);
    output.writeInt(8 + encrypted.length);
    output.writeLong(sequence++);
    output.write(encrypted);
    output.flush();
  }

  public Frame next() throws Exception {
    int size = input.readInt();
    if (size < 38 || size > MAX || input.readLong() != sequence)
      throw new IOException("视频帧序号或长度无效");
    byte[] bytes = new byte[size - 8];
    input.readFully(bytes);
    ByteBuffer b = ByteBuffer.wrap(cipher(Cipher.DECRYPT_MODE).doFinal(bytes));
    sequence++;
    int codec = b.get() & 255;
    if (codec != 1 && codec != 2 && codec != 3) throw new IOException("视频编码无效");
    boolean keyframe = b.get() == 1;
    int w = b.getShort() & 65535, h = b.getShort() & 65535;
    long time = b.getLong();
    byte[] data = new byte[b.remaining()];
    b.get(data);
    Frame frame = new Frame(data, w, h, codec == 2, keyframe);
    frame.heartbeat = codec == 3;
    frame.time = time;
    validate(frame);
    return frame;
  }

  public void close() {
    try {
      socket.close();
    } catch (IOException ignored) {
    }
  }
}
