package com.lanlink.app;

import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.json.JSONObject;

public final class Wire implements AutoCloseable {
  public interface Listener {
    void ready(Wire peer);

    void message(Wire peer, JSONObject message);

    void closed(Wire peer);
  }

  public final Socket socket;
  public volatile String id;
  public volatile JSONObject info = new JSONObject();
  public volatile long lastSeen = System.currentTimeMillis();
  public volatile boolean connected;
  public volatile String failure;
  public boolean outgoing;
  public volatile Wire fastPath;
  volatile QuicManager quic;volatile long quicConn,quicRetry;
  QuicMotion.Sender quicTx;QuicMotion.Receiver quicRx;
  QuicInputQueue quicInputs;
  private final Object controlOrder = new Object();
  private final Object heartbeatOrder = new Object();
  private FutureTask<JSONObject> heartbeat;
  private final JSONObject identity;
  private final byte[] secret;
  private final Listener listener;
  private final ConcurrentHashMap<String, CompletableFuture<JSONObject>> pending =
      new ConcurrentHashMap<>();
  private OutputStream out;
  private byte[] txKey, rxKey;
  private long tx, rx;
  private volatile boolean closed;

  public Wire(Socket socket, JSONObject identity, byte[] secret, Listener listener) {
    this.socket = socket;
    this.identity = identity;
    this.secret = secret;
    this.listener = listener;
  }

  public static JSONObject obj(Object... pairs) {
    JSONObject o = new JSONObject();
    try {
      for (int i = 0; i < pairs.length; i += 2) o.put((String) pairs[i], pairs[i + 1]);
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
    return o;
  }

  public static byte[] sha(byte[] bytes) throws Exception {
    return MessageDigest.getInstance("SHA-256").digest(bytes);
  }

  public static byte[] hmac(byte[] key, byte[] bytes) throws Exception {
    Mac m = Mac.getInstance("HmacSHA256");
    m.init(new SecretKeySpec(key, "HmacSHA256"));
    return m.doFinal(bytes);
  }

  public static String hex(byte[] bytes) {
    StringBuilder b = new StringBuilder();
    for (byte v : bytes) b.append(String.format(Locale.ROOT, "%02x", v & 255));
    return b.toString();
  }

  public static byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  public static String proof(byte[] secret, String s) throws Exception {
    return hex(hmac(secret, bytes(s)));
  }

  public static byte[] parseToken(String token) {
    if (token == null || !token.matches("lanlink1:[A-Za-z0-9_-]{43}"))
      throw new IllegalArgumentException("配对口令格式无效");
    byte[] data = Base64.getUrlDecoder().decode(token.substring(9));
    if (data.length != 32
        || !Base64.getUrlEncoder().withoutPadding().encodeToString(data).equals(token.substring(9)))
      throw new IllegalArgumentException("配对口令格式无效");
    return data;
  }

  public static String token() {
    byte[] b = new byte[32];
    new SecureRandom().nextBytes(b);
    return "lanlink1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  private static byte[] derive(byte[] key, byte[] salt, String info) throws Exception {
    byte[] prk = hmac(salt, key);
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    b.write(bytes(info));
    b.write(1);
    return hmac(prk, b.toByteArray());
  }

  public void start() {
    new Thread(this::run, "lanlink-peer").start();
  }

  private void run() {
    try {
      socket.setSoTimeout(6000);
      socket.setTcpNoDelay(true);
      out = socket.getOutputStream();
      InputStream input = new BufferedInputStream(socket.getInputStream());
      byte[] random = new byte[32];
      new SecureRandom().nextBytes(random);
      String nonce = hex(random), localId = identity.getString("id");
      line(obj("kind", "hello", "v", 1, "id", localId, "nonce", nonce));
      JSONObject hello = read(input);
      id = hello.getString("id");
      String other = hello.getString("nonce");
      if (!hello.optString("kind").equals("hello")
          || hello.optInt("v") != 1
          || !id.matches("[a-f0-9-]{36}")
          || id.equals(localId)
          || !other.matches("[a-f0-9]{64}")) throw new IOException("无效握手");
      boolean first = localId.compareTo(id) < 0;
      String transcript =
          first
              ? localId + ":" + nonce + "|" + id + ":" + other
              : id + ":" + other + "|" + localId + ":" + nonce;
      byte[] salt = sha(bytes(transcript));
      txKey = derive(secret, salt, "lanlink-v1/" + (first ? "a-b" : "b-a"));
      rxKey = derive(secret, salt, "lanlink-v1/" + (first ? "b-a" : "a-b"));
      line(obj("kind", "auth", "proof", proof(secret, "auth|" + transcript + "|" + localId)));
      JSONObject auth = read(input);
      String expected = proof(secret, "auth|" + transcript + "|" + id);
      if (!auth.optString("kind").equals("auth")
          || !MessageDigest.isEqual(bytes(expected), bytes(auth.optString("proof"))))
        throw new IOException("身份验证失败");
      connected = true;
      socket.setSoTimeout(15000);
      send(
          obj(
              "type",
              "identity",
              "name",
              identity.optString("name"),
              "platform",
              "android",
              "capabilities",
              identity.optJSONObject("capabilities")));
      listener.ready(this);
      while (!closed) {
        JSONObject packet = read(input);
        if (packet.getLong("seq") != rx) throw new IOException("重放消息");
        long sequence = rx++;
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(
            Cipher.DECRYPT_MODE,
            new SecretKeySpec(rxKey, "AES"),
            new GCMParameterSpec(128, ByteBuffer.allocate(12).putInt(0).putLong(sequence).array()));
        c.updateAAD(bytes("lanlink-v1:" + sequence));
        JSONObject m =
            new JSONObject(
                new String(
                    c.doFinal(Base64.getDecoder().decode(packet.getString("body"))),
                    StandardCharsets.UTF_8));
        lastSeen = System.currentTimeMillis();
        if (m.optString("type").equals("response")) {
          CompletableFuture<JSONObject> p = pending.remove(m.optString("id"));
          if (p != null) {
            if (m.has("error")) p.completeExceptionally(new IOException(m.getString("error")));
            else
              p.complete(
                  m.optJSONObject("result") == null ? new JSONObject() : m.getJSONObject("result"));
          }
        } else listener.message(this, m);
      }
    } catch (Exception error) {
      if (!closed) {
        failure = error.getClass().getSimpleName() + ": " + error.getMessage();
        if (Boolean.getBoolean("lanlink.debug")) error.printStackTrace();
      }
    } finally {
      close();
    }
  }

  private JSONObject read(InputStream in) throws Exception {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    int c;
    while ((c = in.read()) != -1 && c != 10) {
      if (b.size() >= 8 * 1024 * 1024) throw new IOException("消息过大");
      b.write(c);
    }
    if (c == -1) throw new EOFException();
    return new JSONObject(b.toString("UTF-8"));
  }

  private synchronized void line(JSONObject o) throws IOException {
    byte[] b = bytes(o.toString() + "\n");
    if (b.length > 8 * 1024 * 1024) throw new IOException("消息过大");
    out.write(b);
    out.flush();
  }

  public void send(JSONObject m) throws Exception {
    if(quicConn!=0&&quic!=null&&QuicManager.NOTIFY.contains(m.optString("type"))){quic.notify(this,m);return;}sendDirect(m);
  }
  synchronized void sendDirect(JSONObject m) throws Exception {
    if (!connected || closed) throw new IOException("设备未连接");
    long sequence = tx++;
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
    c.init(
        Cipher.ENCRYPT_MODE,
        new SecretKeySpec(txKey, "AES"),
        new GCMParameterSpec(128, ByteBuffer.allocate(12).putInt(0).putLong(sequence).array()));
    c.updateAAD(bytes("lanlink-v1:" + sequence));
    line(
        obj(
            "seq",
            sequence,
            "body",
            Base64.getEncoder().encodeToString(c.doFinal(bytes(m.toString())))));
  }

  void receiveNotify(JSONObject message){listener.message(this,message);}

  public JSONObject request(String method, JSONObject args) throws Exception {
    if (method.equals("keepControl")) {
      FutureTask<JSONObject> task;
      synchronized (heartbeatOrder) {
        if (heartbeat == null) heartbeat = new FutureTask<>(() -> requestControl(method, args));
        task = heartbeat;
      }
      task.run(); // FutureTask executes only once, including concurrent renewals.
      try { return task.get(); }
      catch (ExecutionException error) {
        Throwable cause = error.getCause();
        if (cause instanceof Exception) throw (Exception) cause;
        throw error;
      } finally {
        synchronized (heartbeatOrder) { if (task.isDone() && heartbeat == task) heartbeat = null; }
      }
    }
    if (AuxLink.CONTROL.contains(method)) synchronized (controlOrder) {
      return requestControl(method, args);
    }
    int timeout=method.equals("paste")?120000:15000;
    return quicConn!=0&&!method.equals("quicOpen")?quic.request(this,method,args,timeout):requestDirect(method,args,timeout);
  }

  private JSONObject requestControl(String method, JSONObject args) throws Exception {
      Wire fast = fastPath;
      if (fast != null && fast.connected) {
        try { return fast.requestDirect(method, args, 1500); }
        catch (Exception error) {
          if (!fast.connected || error instanceof TimeoutException) {
            fastPath = null;
            fast.close();
            // Transport recovery remains ordered even when a lease detected it.
            synchronized (controlOrder) {
              try { requestDirect("inputReset", obj(), 1500); } catch (Exception ignored) {}
            }
          }
          throw error;
        }
      }
      return quicConn!=0?quic.request(this,method,args,15000):requestDirect(method, args, 15000);
  }

  JSONObject requestDirect(String method, JSONObject args, int timeout) throws Exception {
    if (pending.size() >= 32) throw new IOException("请求过多");
    String id = UUID.randomUUID().toString();
    CompletableFuture<JSONObject> p = new CompletableFuture<>();
    pending.put(id, p);
    try {
      send(obj("type", "request", "id", id, "method", method, "args", args));
      return p.get(timeout, TimeUnit.MILLISECONDS);
    } finally {
      pending.remove(id);
    }
  }

  public void close() {
    if (closed) return;
    if(quic!=null)quic.drop(this);
    closed = true;
    connected = false;
    Wire fast = fastPath;
    fastPath = null;
    if (fast != null) fast.close();
    try {
      socket.close();
    } catch (Exception ignored) {
    }
    for (CompletableFuture<JSONObject> p : pending.values())
      p.completeExceptionally(new IOException("连接已断开"));
    pending.clear();
    listener.closed(this);
  }
}
