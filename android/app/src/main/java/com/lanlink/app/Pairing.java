package com.lanlink.app;

import static com.lanlink.app.Wire.*;
import java.io.*;
import java.net.*;
import java.math.BigInteger;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.json.JSONObject;

/** Short-lived SRP-6a invitations. Wire format matches src/pairing*.js. */
public final class Pairing implements AutoCloseable {
  public static final int DISCOVERY_PORT = 45833;
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final BigInteger N = new BigInteger("AC6BDB41324A9A9BF166DE5E1389582FAF72B6651987EE07FC3192943DB56050A37329CBB4A099ED8193E0757767A13DD52312AB4B03310DCD7F48A9DA04FD50E8083969EDB767B0CF6095179A163AB3661A05FBD5FAAAE82918A9962F0B93B855F97993EC975EEAA80D740ADBF4FF747359D041D5C33EA71D281E446B14773BCA97B43A23FB801676BD207A436C6481F1D2B9078717461A5B9D32E688F87748544523B524B0D57D5EA77A2775D2ECFA032CFBDBF52FB3786160279004E57AE6AF874E7303CE53299CCC041C7BC308D82A5698F3A8D0C38271AE35F8E9DBFBB694B5C803D89F7AE435DE236D525F54759B65E372FCD68EF20FA7111F9E4AFF73", 16), G = BigInteger.valueOf(2);
  public interface Members { JSONObject own(); void admit(JSONObject m) throws Exception; JSONObject export(); }
  public Members members;
  private final Supplier<String> token;
  private final int servicePort;
  private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
  private final ThreadPoolExecutor workers = new ThreadPoolExecutor(8, 8, 0, TimeUnit.SECONDS, new SynchronousQueue<>());
  private final List<Long> attempts = new ArrayList<>();
  private ServerSocket server;
  private DatagramSocket udp;
  private volatile boolean closed;
  private JSONObject invitation;
  private long leaseUntil;
  public Pairing(Supplier<String> token, int servicePort) { this.token = token; this.servicePort = servicePort; }
  private static String randomHex() { byte[] b = new byte[16]; RANDOM.nextBytes(b); return hex(b); }
  private static BigInteger privateValue() { return new BigInteger(256, RANDOM).add(BigInteger.ONE); }
  private static byte[] unhex(String s) { byte[] b = new byte[s.length() / 2]; for (int i = 0; i < b.length; i++) b[i] = (byte)Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16); return b; }
  private static byte[] pad(BigInteger n) { byte[] raw = n.toByteArray(), out = new byte[256]; int count = Math.min(raw.length, 256); System.arraycopy(raw, raw.length - count, out, 256 - count, count); return out; }
  private static byte[] hash(byte[]... parts) throws Exception { MessageDigest d = MessageDigest.getInstance("SHA-256"); for (byte[] p : parts) d.update(p); return d.digest(); }
  private static BigInteger integer(byte[] b) { return new BigInteger(1, b); }
  private static BigInteger multiplier() throws Exception { return integer(hash(pad(N), pad(G))); }
  private static BigInteger publicValue(String hex) {
    if (!hex.matches("[a-f0-9]{512}")) throw new IllegalArgumentException("配对握手无效");
    BigInteger n = new BigInteger(hex, 16); if (n.compareTo(BigInteger.ONE) <= 0 || n.compareTo(N.subtract(BigInteger.ONE)) >= 0) throw new IllegalArgumentException("配对握手无效"); return n;
  }
  private static BigInteger exponent(String code, String salt, String id) throws Exception {
    if (!code.matches("[0-9]{8}") || !salt.matches("[a-f0-9]{32}") || !id.matches("[a-f0-9]{32}")) throw new IllegalArgumentException("配对信息无效");
    return integer(hash(unhex(salt), hash(bytes("lanlink-pair-v2/" + id + ":" + code))));
  }
  private static final class Keys {
    byte[] key; String client, server;
    Keys(BigInteger s, BigInteger a, BigInteger b, String salt, String id) throws Exception {
      byte[] context = hash(bytes("lanlink-pair-v2"), unhex(id), unhex(salt), pad(a), pad(b));
      key = hash(pad(s), context); client = proof(key, "client|" + hex(context)); server = proof(key, "server|" + hex(context));
    }
  }
  private static boolean equal(String a, String b) { return a.length() == b.length() && MessageDigest.isEqual(bytes(a), bytes(b)); }
  private static String encrypt(byte[] key, JSONObject data) throws Exception {
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, new byte[12])); c.updateAAD(bytes("lanlink-v1:0")); return Base64.getEncoder().encodeToString(c.doFinal(bytes(data.toString())));
  }
  private static JSONObject decrypt(byte[] key, String body) throws Exception {
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, new byte[12])); c.updateAAD(bytes("lanlink-v1:0")); return new JSONObject(new String(c.doFinal(Base64.getDecoder().decode(body)), java.nio.charset.StandardCharsets.UTF_8));
  }
  private static JSONObject read(Socket s) throws Exception {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream(); InputStream in = s.getInputStream();
    long deadline = System.currentTimeMillis() + 6000;
    for (int i = 0; i < 2000000; i++) { if (System.currentTimeMillis() >= deadline) throw new IOException("配对超时"); int b = in.read(); if (b < 0) throw new EOFException(); if (b == '\n') return new JSONObject(buffer.toString("UTF-8")); buffer.write(b); }
    throw new IOException("配对数据过大");
  }
  private static void send(Socket s, JSONObject m) throws Exception { s.getOutputStream().write(bytes(m.toString() + "\n")); s.getOutputStream().flush(); }
  private synchronized boolean current() { return invitation != null && invitation.optLong("expiresAt") > System.currentTimeMillis() && leaseUntil > System.currentTimeMillis(); }
  public synchronized JSONObject show() {
    long now = System.currentTimeMillis();
    if (!current()) invitation = obj("id", randomHex(), "salt", randomHex(), "code", String.format(Locale.ROOT, "%08d", RANDOM.nextInt(100000000)), "expiresAt", now + 60000);
    leaseUntil = now + 15000;
    return obj("id", invitation.optString("id"), "code", invitation.optString("code"), "expiresAt", invitation.optLong("expiresAt"), "remainingMs", invitation.optLong("expiresAt") - now, "durationMs", 60000);
  }
  public synchronized void hide() { invitation = null; leaseUntil = 0; for (Socket s : sockets) try { s.close(); } catch (Exception ignored) {} }
  public int port() { return server.getLocalPort(); }
  public void start(boolean discovery) throws Exception {
    server = new ServerSocket(0);
    Thread listener = new Thread(() -> {
      while (!closed) try { Socket s = server.accept(); if (!current()) { s.close(); continue; } try { workers.execute(() -> serve(s)); } catch (RejectedExecutionException e) { s.close(); } } catch (Exception ignored) {}
    }, "pairing-listener"); listener.setDaemon(true); listener.start();
    if (!discovery) return;
    udp = new DatagramSocket(null); udp.setReuseAddress(true); udp.bind(new InetSocketAddress(DISCOVERY_PORT));
    Thread discoveryThread = new Thread(() -> {
      while (!closed) try {
        DatagramPacket p = new DatagramPacket(new byte[512], 512); udp.receive(p); if (p.getLength() > 256 || !current()) continue;
        JSONObject q = new JSONObject(new String(p.getData(), 0, p.getLength(), java.nio.charset.StandardCharsets.UTF_8));
        if (!q.optString("kind").equals("lanlink-pair-find-v2") || !q.optString("nonce").matches("[a-f0-9]{32}")) continue;
        byte[] reply;
        synchronized (this) { if (!current()) continue; reply = bytes(obj("kind", "lanlink-pair-offer-v2", "nonce", q.getString("nonce"), "id", invitation.getString("id"), "port", port()).toString()); }
        udp.send(new DatagramPacket(reply, reply.length, p.getAddress(), p.getPort()));
      } catch (Exception ignored) {}
    }, "pairing-discovery"); discoveryThread.setDaemon(true); discoveryThread.start();
  }
  private void serve(Socket socket) {
    sockets.add(socket);
    try (Socket s = socket) {
      s.setSoTimeout(2500); s.setTcpNoDelay(true); long start = System.currentTimeMillis(); JSONObject hello = read(s), invite;
      synchronized (this) {
        if (!current() || !hello.optString("kind").equals("hello") || !hello.optString("id").equals(invitation.optString("id"))) return;
        long now = System.currentTimeMillis(); attempts.removeIf(t -> t <= now - 60000); if (attempts.size() >= 10) return; attempts.add(now); invite = invitation;
      }
      String id = invite.getString("id"), salt = invite.getString("salt"); BigInteger a = publicValue(hello.getString("A"));
      BigInteger v = G.modPow(exponent(invite.getString("code"), salt, id), N), b = privateValue(), B = multiplier().multiply(v).add(G.modPow(b, N)).mod(N), u = integer(hash(pad(a), pad(B)));
      if (u.signum() == 0) return; Keys keys = new Keys(a.multiply(v.modPow(u, N)).mod(N).modPow(b, N), a, B, salt, id);
      send(s, obj("kind", "challenge", "id", id, "salt", salt, "B", hex(pad(B)))); JSONObject proof = read(s);
      synchronized (this) {
        if (System.currentTimeMillis() - start > 6000 || !current() || invitation != invite || !proof.optString("kind").equals("proof") || !equal(proof.optString("proof"), keys.client)) return;
        invitation = null; if(members != null && proof.has("member")) members.admit(cryptMember(keys.key, proof.getString("member"))); send(s, obj("kind", "joined", "proof", keys.server, "body", encrypt(keys.key, obj("token", token.get(), "port", servicePort, "group", members == null ? JSONObject.NULL : members.export()))));
      }
    } catch (Exception ignored) {} finally { sockets.remove(socket); }
  }
  private static JSONObject cryptMember(byte[] key, String body) throws Exception {
    byte[] iv=new byte[12]; iv[11]=1; Cipher c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv)); c.updateAAD(bytes("lanlink-v1:1")); return new JSONObject(new String(c.doFinal(Base64.getDecoder().decode(body)),java.nio.charset.StandardCharsets.UTF_8));
  }
  private static String cryptMember(byte[] key, JSONObject body) throws Exception {
    byte[] iv=new byte[12]; iv[11]=1; Cipher c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv)); c.updateAAD(bytes("lanlink-v1:1")); return Base64.getEncoder().encodeToString(c.doFinal(bytes(body.toString())));
  }
  public static JSONObject exchange(String host, int port, String id, String code) throws Exception { return exchange(host,port,id,code,null); }
  public static JSONObject exchange(String host, int port, String id, String code, JSONObject member) throws Exception {
    if (!code.matches("[0-9]{8}") || !id.matches("[a-f0-9]{32}")) throw new IllegalArgumentException("请输入八位数字配对码");
    BigInteger a = privateValue(), A = G.modPow(a, N);
    try (Socket s = new Socket()) {
      s.connect(new InetSocketAddress(host, port), 2500); s.setSoTimeout(2500); s.setTcpNoDelay(true);
      send(s, obj("kind", "hello", "id", id, "A", hex(pad(A)))); JSONObject m = read(s);
      if (!m.optString("kind").equals("challenge") || !m.optString("id").equals(id)) throw new IOException("配对握手失败");
      String salt = m.getString("salt"); BigInteger B = publicValue(m.getString("B")), x = exponent(code, salt, id), u = integer(hash(pad(A), pad(B)));
      if (u.signum() == 0) throw new IOException("配对握手失败"); Keys keys = new Keys(B.subtract(multiplier().multiply(G.modPow(x, N))).mod(N).modPow(a.add(u.multiply(x)), N), A, B, salt, id);
      JSONObject proofMessage=obj("kind","proof","proof",keys.client); if(member!=null) proofMessage.put("member",cryptMember(keys.key,member)); send(s, proofMessage); m = read(s);
      if (!m.optString("kind").equals("joined") || !equal(m.optString("proof"), keys.server)) throw new IOException("配对验证失败");
      JSONObject result = decrypt(keys.key, m.getString("body")); parseToken(result.getString("token")); int peerPort = result.getInt("port"); if (peerPort < 1 || peerPort > 65535) throw new IOException("端口无效"); result.put("host", host); return result;
    }
  }
  public synchronized String ownId() { return invitation == null ? "" : invitation.optString("id"); }
  public JSONObject join(String code, String targetId) throws Exception {
    if (!code.matches("[0-9]{8}")) throw new IllegalArgumentException("请输入八位数字配对码");
    Map<String, JSONObject> offers = new HashMap<>(); String nonce = randomHex();
    try (DatagramSocket probe = new DatagramSocket()) {
      probe.setBroadcast(true); probe.setSoTimeout(200); byte[] query = bytes(obj("kind", "lanlink-pair-find-v2", "nonce", nonce).toString());
      Set<InetAddress> destinations = new HashSet<>(); destinations.add(InetAddress.getByName("255.255.255.255")); destinations.add(InetAddress.getByName("127.0.0.1"));
      Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces(); while (interfaces.hasMoreElements()) for (InterfaceAddress a : interfaces.nextElement().getInterfaceAddresses()) if (a.getBroadcast() != null) destinations.add(a.getBroadcast());
      long until = System.currentTimeMillis() + 1100, nextSend = 0;
      while (System.currentTimeMillis() < until) {
        if (System.currentTimeMillis() >= nextSend) { for (InetAddress host : destinations) probe.send(new DatagramPacket(query, query.length, host, DISCOVERY_PORT)); nextSend = System.currentTimeMillis() + 450; }
        try { DatagramPacket p = new DatagramPacket(new byte[513], 513); probe.receive(p); if (p.getLength() > 512) continue; JSONObject m = new JSONObject(new String(p.getData(), 0, p.getLength(), java.nio.charset.StandardCharsets.UTF_8)); String id = m.optString("id"); int port = m.optInt("port");
          if (offers.size() < 16 && m.optString("kind").equals("lanlink-pair-offer-v2") && m.optString("nonce").equals(nonce) && id.matches("[a-f0-9]{32}") && !id.equals(ownId()) && (targetId == null || targetId.equals(id)) && port > 0 && port <= 65535) { m.put("host", p.getAddress().getHostAddress()); offers.put(id, m); }
        } catch (Exception ignored) {}
      }
    }
    if (offers.isEmpty()) throw new IOException("未找到设备，请在同一局域网的另一台设备打开添加设备界面");
    ExecutorService clients = Executors.newFixedThreadPool(offers.size()); List<Future<JSONObject>> jobs = new ArrayList<>();
    try {
      for (JSONObject o : offers.values()) jobs.add(clients.submit(() -> exchange(o.getString("host"), o.getInt("port"), o.getString("id"), code, members == null ? null : members.own())));
      JSONObject result = null; for (Future<JSONObject> job : jobs) { JSONObject r; try { r = job.get(7, TimeUnit.SECONDS); } catch (Exception e) { continue; } if (result != null && !result.getString("token").equals(r.getString("token"))) throw new IOException("配对码冲突，请扫描二维码或重试"); result = r; }
      if (result == null) throw new IOException("配对码错误、已过期或尝试过于频繁，请查看最新配对码"); return result;
    } finally { clients.shutdownNow(); }
  }
  public void close() { closed = true; hide(); if (udp != null) udp.close(); try { if (server != null) server.close(); } catch (Exception ignored) {} workers.shutdownNow(); }
}
