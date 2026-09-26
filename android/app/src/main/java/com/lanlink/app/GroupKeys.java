package com.lanlink.app;

import static com.lanlink.app.Wire.*;

import android.content.SharedPreferences;
import java.io.*;
import java.net.*;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.json.*;

/** Per-device RSA keys and signed, recipient-encrypted membership updates. */
final class GroupKeys implements AutoCloseable, Pairing.Members {
  final LinkService service;
  final SharedPreferences prefs;
  final KeyPair keys;
  JSONObject data;
  volatile Set<String> revoked = Collections.emptySet();
  DatagramSocket socket;
  volatile boolean closed;

  GroupKeys(LinkService service, SharedPreferences prefs) throws Exception {
    this.service = service;
    this.prefs = prefs;
    KeyFactory factory = KeyFactory.getInstance("RSA");
    if (!prefs.contains("device_private")) {
      KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
      g.initialize(2048);
      keys = g.generateKeyPair();
      if (!prefs
          .edit()
          .putString("device_private", b64(keys.getPrivate().getEncoded()))
          .putString("device_public", b64(keys.getPublic().getEncoded()))
          .commit()) throw new IOException("设备密钥保存失败");
    } else
      keys =
          new KeyPair(
              factory.generatePublic(
                  new X509EncodedKeySpec(decode(prefs.getString("device_public", "")))),
              factory.generatePrivate(
                  new PKCS8EncodedKeySpec(decode(prefs.getString("device_private", "")))));
    String saved = prefs.getString("group", null);
    data = saved == null ? fresh(service.token) : new JSONObject(saved);
    if (!data.has("anchors")) data.put("anchors", obj());
    cacheRemoved();
    data.getJSONObject("members").put(service.deviceId, own());
    save();
  }

  synchronized boolean established() {
    return data.optLong("revision") > 0
        || data.optJSONObject("members").length() > 1
        || service.knownDevices().length() > 0;
  }

  boolean isDissolved() {
    try {
      return dissolvedGroups().has(data.optString("id"));
    } catch (Exception e) {
      return true;
    }
  }

  JSONObject dissolvedGroups() throws Exception {
    return new JSONObject(prefs.getString("dissolved_groups", "{}"));
  }

  static String dissolutionTranscript(JSONObject b) {
    return "lanlink-group-dissolved-v1|"
        + b.optString("group")
        + "|"
        + b.optLong("revision")
        + "|"
        + b.optString("issuer");
  }

  synchronized JSONObject dissolutionRecord() throws Exception {
    JSONArray clean = new JSONArray(), chain = certificateChain();
    for (int i = 0; i < chain.length(); i++) {
      JSONObject m = chain.getJSONObject(i);
      clean.put(
          obj(
              "id",
              m.getString("id"),
              "key",
              m.getString("key"),
              "certificate",
              m.opt("certificate")));
    }
    JSONObject b =
        obj(
            "kind",
            "lanlink-group-dissolved-v1",
            "group",
            data.getString("id"),
            "revision",
            Math.max(System.currentTimeMillis(), data.getLong("revision") + 1),
            "issuer",
            service.deviceId,
            "chain",
            clean);
    Signature signature = Signature.getInstance("SHA256withRSA");
    signature.initSign(keys.getPrivate());
    signature.update(bytes(dissolutionTranscript(b)));
    b.put("signature", b64(signature.sign()));
    return b;
  }

  synchronized void verifyDissolution(JSONObject b) throws Exception {
    if (!b.optString("kind").equals("lanlink-group-dissolved-v1")
        || !b.optString("group").equals(data.optString("id"))
        || b.optLong("revision") <= 0
        || b.optLong("revision") > System.currentTimeMillis() + 86400000
        || b.optString("signature").length() > 500
        || b.toString().length() > 50000) throw new IOException("解散通知无效或已处理");
    JSONObject issuer = resolveIssuer(b, b.optString("issuer"), new HashSet<>());
    if (issuer == null) throw new IOException("解散通知签名无效");
    Signature signature = Signature.getInstance("SHA256withRSA");
    signature.initVerify(publicKey(issuer));
    signature.update(bytes(dissolutionTranscript(b)));
    if (!signature.verify(decode(b.getString("signature")))) throw new IOException("解散通知签名无效");
  }

  synchronized void rememberDissolution(JSONObject b) throws Exception {
    JSONObject records = dissolvedGroups();
    if (!records.has(b.getString("group")) && records.length() >= 64)
      throw new IOException("保留的解散通知已达上限，请联系支持以保留离线成员更新记录");
    records.put(b.getString("group"), b);
    if (!prefs.edit().putString("dissolved_groups", records.toString()).commit())
      throw new IOException("解散通知保存失败");
  }

  synchronized void dissolve() throws Exception {
    JSONObject notice;
    synchronized (this) {
      notice = dissolutionRecord();
      rememberDissolution(notice);
    }
    if (service.pairing != null) service.pairing.hide();
    service.stopInbound();
    service.releaseControl();
    for (Wire peer : new ArrayList<>(service.peers.values()))
      try {
        peer.send(obj("type", "group-dissolved", "notice", notice));
      } catch (Exception ignored) {
      }
    service.join(Wire.token(), null);
  }

  synchronized void acceptDissolution(JSONObject b) throws Exception {
    synchronized (this) {
      verifyDissolution(b);
      rememberDissolution(b);
    }
    service.stopInbound();
    service.join(Wire.token(), null);
    service.status = "原组已解散，所有成员已退出，请重新添加设备";
  }

  static String b64(byte[] b) {
    return Base64.getEncoder().encodeToString(b);
  }

  static byte[] decode(String s) {
    return Base64.getDecoder().decode(s);
  }

  static boolean id(String s) {
    return s.matches("[a-f0-9-]{36}");
  }

  static PublicKey publicKey(JSONObject m) throws Exception {
    String k = m.getString("key");
    if (!id(m.getString("id")) || k.length() > 800) throw new IOException("设备公钥无效");
    PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(decode(k)));
    if (((java.security.interfaces.RSAPublicKey) key).getModulus().bitLength() != 2048)
      throw new IOException("设备公钥无效");
    return key;
  }

  JSONObject fresh(String token) throws Exception {
    return obj(
        "id",
        hex(MessageDigest.getInstance("SHA-256").digest(parseToken(token))).substring(0, 24),
        "revision",
        0,
        "members",
        obj(),
        "removed",
        new JSONArray(),
        "history",
        new JSONArray(),
        "anchors",
        obj());
  }

  public JSONObject own() {
    return obj(
        "id",
        service.deviceId,
        "name",
        service.name,
        "platform",
        "android",
        "key",
        b64(keys.getPublic().getEncoded()),
        "certificate",
        data == null
                || data.optJSONObject("members") == null
                || data.optJSONObject("members").optJSONObject(service.deviceId) == null
            ? JSONObject.NULL
            : data.optJSONObject("members").optJSONObject(service.deviceId).opt("certificate"));
  }

  public synchronized JSONObject export() {
    return data;
  }

  boolean excluded(String id) {
    return revoked.contains(id);
  }

  void cacheRemoved() {
    Set<String> ids = new HashSet<>();
    JSONArray list = data.optJSONArray("removed");
    for (int i = 0; list != null && i < list.length(); i++) ids.add(list.optString(i));
    revoked = Collections.unmodifiableSet(ids);
  }

  synchronized void save() throws IOException {
    if (!prefs
        .edit()
        .putString("group", data.toString())
        .putString("token", service.token)
        .commit()) throw new IOException("组身份保存失败");
  }

  synchronized boolean learn(JSONObject member, boolean explicit) throws Exception {
    if (isDissolved()) return false;
    publicKey(member);
    String id = member.getString("id");
    if (id.equals(service.deviceId) || excluded(id) && !explicit) return false;
    JSONObject members = data.getJSONObject("members"), prior = members.optJSONObject(id);
    if (prior != null && !prior.getString("key").equals(member.getString("key")) && !explicit)
      return false;
    if (prior == null && members.length() >= 16) throw new IOException("每个组最多支持 16 台设备");
    JSONObject clean =
        obj(
            "id",
            id,
            "key",
            member.getString("key"),
            "name",
            member.optString("name", "设备"),
            "platform",
            member.optString("platform"));
    if (clean.getString("name").length() > 80 || clean.getString("platform").length() > 30)
      throw new IOException("设备信息过长");
    JSONObject cert = member.optJSONObject("certificate");
    if (cert == null && prior != null) cert = prior.optJSONObject("certificate");
    if (cert != null && cert.toString().length() < 1000) clean.put("certificate", cert);
    members.put(id, clean);
    data.getJSONObject("anchors").put(id, clean);
    if (explicit) {
      JSONArray list = new JSONArray(), old = data.getJSONArray("removed");
      for (int i = 0; i < old.length(); i++)
        if (!id.equals(old.getString(i))) list.put(old.getString(i));
      data.put("removed", list);
      cacheRemoved();
    }
    return prior == null || !prior.toString().equals(clean.toString());
  }

  void checkHistory() throws Exception {
    Set<Long> revisions = new HashSet<>();
    JSONArray h = data.getJSONArray("history");
    for (int i = 0; i < h.length(); i++) revisions.add(h.getJSONObject(i).getLong("revision"));
    if (h.toString().length() > 1500000 || revisions.size() >= 64)
      throw new IOException("离线成员更新记录已满，请先让所有设备上线后创建新组");
  }

  public synchronized void admit(JSONObject m) throws Exception {
    if (dissolvedGroups().has(data.getString("id"))) throw new IOException("当前组已解散");
    checkHistory();
    Signature cert = Signature.getInstance("SHA256withRSA");
    cert.initSign(keys.getPrivate());
    cert.update(
        bytes(
            "lanlink-member-v1|"
                + data.getString("id")
                + "|"
                + m.getString("id")
                + "|"
                + m.getString("key")));
    m = new JSONObject(m.toString());
    m.put("certificate", obj("issuer", service.deviceId, "signature", b64(cert.sign())));
    learn(m, true);
    data.put("revision", Math.max(System.currentTimeMillis(), data.getLong("revision") + 1));
    refresh();
    save();
    publish();
  }

  static void validateMeta(JSONObject meta) throws Exception {
    JSONObject members = meta.getJSONObject("members");
    if (!meta.getString("id").matches("[a-f0-9]{24}")
        || members.length() > 16
        || meta.getLong("revision") < 0) throw new IOException("组信息无效");
    for (Iterator<String> it = members.keys(); it.hasNext(); )
      publicKey(members.getJSONObject(it.next()));
    if (meta.has("anchors") && meta.getJSONObject("anchors").length() > 128)
      throw new IOException("成员证明过多");
    if (meta.has("removed")) meta.getJSONArray("removed");
    if (meta.has("history")) {
      JSONArray history = meta.getJSONArray("history");
      if (history.length() > 1024) throw new IOException("组历史记录过多");
      for (int i = 0; i < history.length(); i++) history.getJSONObject(i);
    }
  }

  synchronized void adopt(JSONObject meta, String token) throws Exception {
    parseToken(token);
    if (meta == null) meta = fresh(token);
    validateMeta(meta);
    if (dissolvedGroups().has(meta.optString("id"))) throw new IOException("该组已经解散，请生成新的组邀请");
    JSONObject members = meta.getJSONObject("members");
    if (!meta.getString("id").matches("[a-f0-9]{24}")
        || members.length() > 16
        || meta.getLong("revision") < 0) throw new IOException("组信息无效");
    for (Iterator<String> it = members.keys(); it.hasNext(); )
      publicKey(members.getJSONObject(it.next()));
    data = new JSONObject(meta.toString());
    if (!data.has("anchors")) data.put("anchors", obj());
    if (data.getJSONObject("anchors").length() > 128) throw new IOException("成员证明过多");
    cacheRemoved();
    data.getJSONObject("members").put(service.deviceId, own());
    if (!data.has("history")) data.put("history", new JSONArray());
    service.token = token;
    refresh();
    save();
  }

  static String transcript(JSONObject b) {
    return "lanlink-group-v1|"
        + b.optString("group")
        + "|"
        + b.optString("to")
        + "|"
        + b.optLong("revision")
        + "|"
        + b.optString("issuer")
        + "|"
        + b.optString("wrapped")
        + "|"
        + b.optString("body");
  }

  static Cipher rsa(int mode, Key key) throws Exception {
    Cipher c = Cipher.getInstance("RSA/ECB/OAEPPadding");
    c.init(
        mode,
        key,
        new OAEPParameterSpec(
            "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
    return c;
  }

  static JSONObject open(byte[] key, String body) throws Exception {
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
    c.init(
        Cipher.DECRYPT_MODE,
        new SecretKeySpec(key, "AES"),
        new GCMParameterSpec(128, new byte[12]));
    c.updateAAD(bytes("lanlink-v1:0"));
    return new JSONObject(
        new String(c.doFinal(decode(body)), java.nio.charset.StandardCharsets.UTF_8));
  }

  static String seal(byte[] key, JSONObject payload) throws Exception {
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
    c.init(
        Cipher.ENCRYPT_MODE,
        new SecretKeySpec(key, "AES"),
        new GCMParameterSpec(128, new byte[12]));
    c.updateAAD(bytes("lanlink-v1:0"));
    return b64(c.doFinal(bytes(payload.toString())));
  }

  JSONArray certificateChain() {
    JSONArray chain = new JSONArray();
    Set<String> seen = new HashSet<>();
    JSONObject member = own();
    while (member != null && seen.add(member.optString("id")) && chain.length() < 32) {
      chain.put(member);
      JSONObject cert = member.optJSONObject("certificate");
      if (cert == null) break;
      member = data.optJSONObject("members").optJSONObject(cert.optString("issuer"));
      if (member == null)
        member = data.optJSONObject("anchors").optJSONObject(cert.optString("issuer"));
    }
    return chain;
  }

  JSONObject resolveIssuer(JSONObject box, String id, Set<String> seen) throws Exception {
    if (excluded(id) || !seen.add(id) || seen.size() > 32) return null;
    JSONObject trusted = data.getJSONObject("members").optJSONObject(id);
    if (trusted == null) trusted = data.getJSONObject("anchors").optJSONObject(id);
    if (trusted != null) {
      publicKey(trusted);
      return trusted;
    }
    JSONArray chain = box.optJSONArray("chain");
    if (chain == null || chain.length() > 32) return null;
    for (int i = 0; i < chain.length(); i++) {
      JSONObject member = chain.getJSONObject(i);
      if (!id.equals(member.optString("id"))) continue;
      publicKey(member);
      JSONObject cert = member.optJSONObject("certificate");
      if (cert == null || cert.optString("signature").length() > 500) return null;
      JSONObject parent = resolveIssuer(box, cert.optString("issuer"), seen);
      if (parent == null) return null;
      Signature sig = Signature.getInstance("SHA256withRSA");
      sig.initVerify(publicKey(parent));
      sig.update(
          bytes(
              "lanlink-member-v1|"
                  + data.getString("id")
                  + "|"
                  + id
                  + "|"
                  + member.getString("key")));
      return sig.verify(decode(cert.getString("signature"))) ? member : null;
    }
    return null;
  }

  synchronized JSONObject box(JSONObject member) throws Exception {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    JSONObject b =
        obj(
            "kind",
            "lanlink-group-update-v1",
            "group",
            data.getString("id"),
            "to",
            member.getString("id"),
            "revision",
            data.getLong("revision"),
            "issuer",
            service.deviceId,
            "chain",
            certificateChain(),
            "wrapped",
            b64(rsa(Cipher.ENCRYPT_MODE, publicKey(member)).doFinal(key)),
            "body",
            seal(
                key,
                obj(
                    "token",
                    service.token,
                    "removed",
                    data.getJSONArray("removed"),
                    "members",
                    data.getJSONObject("members"))));
    Signature signature = Signature.getInstance("SHA256withRSA");
    signature.initSign(keys.getPrivate());
    signature.update(bytes(transcript(b)));
    b.put("signature", b64(signature.sign()));
    return b;
  }

  boolean verifies(JSONObject b, JSONObject issuer) throws Exception {
    Signature s = Signature.getInstance("SHA256withRSA");
    s.initVerify(publicKey(issuer));
    s.update(bytes(transcript(b)));
    return s.verify(decode(b.getString("signature")));
  }

  synchronized void refresh() throws Exception {
    if (data.getLong("revision") == 0) return;
    JSONObject members = data.getJSONObject("members");
    JSONArray history = data.getJSONArray("history");
    for (Iterator<String> it = members.keys(); it.hasNext(); ) {
      String id = it.next();
      if (id.equals(service.deviceId) || excluded(id)) continue;
      boolean exists = false;
      for (int i = 0; i < history.length(); i++) {
        JSONObject b = history.getJSONObject(i);
        if (b.optString("to").equals(id) && b.optLong("revision") == data.getLong("revision"))
          exists = true;
      }
      if (!exists) history.put(box(members.getJSONObject(id)));
    }
  }

  synchronized void accept(JSONObject b) throws Exception {
    if (isDissolved()) throw new IOException("当前组已经解散");
    long rev = b.getLong("revision");
    if (!b.optString("kind").equals("lanlink-group-update-v1")
        || !b.optString("group").equals(data.getString("id"))
        || !b.optString("to").equals(service.deviceId)
        || rev <= data.getLong("revision")
        || rev > System.currentTimeMillis() + 86400000
        || b.getString("body").length() > 40000
        || b.getString("signature").length() > 500
        || b.getString("wrapped").length() > 500) throw new IOException("组更新无效或已处理");
    JSONObject issuer = resolveIssuer(b, b.getString("issuer"), new HashSet<>());
    if (issuer == null || excluded(b.getString("issuer")) || !verifies(b, issuer))
      throw new IOException("组更新签名无效");
    JSONObject payload =
        open(
            rsa(Cipher.DECRYPT_MODE, keys.getPrivate()).doFinal(decode(b.getString("wrapped"))),
            b.getString("body"));
    parseToken(payload.getString("token"));
    JSONArray removed = payload.getJSONArray("removed");
    JSONObject members = payload.getJSONObject("members");
    if (removed.length() > 64
        || members.length() > 16
        || !members.getJSONObject(service.deviceId).getString("key").equals(own().getString("key")))
      throw new IOException("组成员无效");
    for (int i = 0; i < removed.length(); i++)
      if (!id(removed.getString(i)) || removed.getString(i).equals(service.deviceId))
        throw new IOException("成员变更无效");
    for (Iterator<String> it = members.keys(); it.hasNext(); )
      publicKey(members.getJSONObject(it.next()));
    JSONObject oldMembers = data.getJSONObject("members");
    for (Iterator<String> it = oldMembers.keys(); it.hasNext(); ) {
      String id = it.next();
      data.getJSONObject("anchors").put(id, oldMembers.getJSONObject(id));
    }
    data.put("revision", rev).put("members", members).put("removed", removed);
    cacheRemoved();
    service.token = payload.getString("token");
    for (int i = 0; i < removed.length(); i++) service.forgetDevice(removed.getString(i));
    refresh();
    save();
    service.groupChanged();
    publish();
  }

  synchronized void merge(JSONObject m) throws Exception {
    if (!m.optString("group").equals(data.getString("id"))) return;
    JSONObject members = m.optJSONObject("members");
    if (members == null || members.length() > 16) return;
    boolean changed = false;
    for (Iterator<String> it = members.keys(); it.hasNext(); )
      changed = learn(members.getJSONObject(it.next()), false) || changed;
    JSONArray incoming = m.optJSONArray("history"), history = data.getJSONArray("history");
    for (int i = 0; incoming != null && i < Math.min(1024, incoming.length()); i++) {
      JSONObject b = incoming.getJSONObject(i),
          issuer = data.getJSONObject("members").optJSONObject(b.optString("issuer"));
      if (issuer == null
          || !b.optString("group").equals(data.getString("id"))
          || b.optLong("revision") > data.getLong("revision")
          || b.optString("body").length() > 40000) continue;
      boolean exists = false;
      for (int j = 0; j < history.length(); j++) {
        JSONObject old = history.getJSONObject(j);
        if (old.optLong("revision") == b.optLong("revision")
            && old.optString("to").equals(b.optString("to"))) exists = true;
      }
      if (!exists && history.length() < 1024 && verifies(b, issuer)) {
        history.put(b);
        changed = true;
      }
    }
    if (changed) {
      save();
      publish();
    }
  }

  synchronized void publish() {
    for (Wire p : service.peers.values())
      if (!excluded(p.id))
        try {
          p.send(
              obj(
                  "type",
                  "group-members",
                  "group",
                  data.getString("id"),
                  "members",
                  data.getJSONObject("members"),
                  "history",
                  data.getJSONArray("history")));
        } catch (Exception ignored) {
        }
  }

  void remove(String id) throws Exception {
    List<Wire> peers;
    Map<String, JSONObject> updates = new HashMap<>();
    synchronized (this) {
      if (!id(id)
          || id.equals(service.deviceId)
          || !service.knownDevices().has(id) && !data.getJSONObject("members").has(id))
        throw new IOException("请选择组内其他设备");
      JSONObject known = service.knownDevices();
      for (Iterator<String> it = known.keys(); it.hasNext(); ) {
        String k = it.next();
        if (!k.equals(id) && !data.getJSONObject("members").has(k))
          throw new IOException("请先让其余组员升级并上线一次，保存设备公钥后才能保留离线成员身份");
      }
      checkHistory();
      if (data.getJSONArray("removed").length() >= 64) throw new IOException("组成员变更过多，请创建新组");
      data.getJSONArray("removed").put(id);
      cacheRemoved();
      JSONObject removedMember = data.getJSONObject("members").optJSONObject(id);
      if (removedMember != null) data.getJSONObject("anchors").put(id, removedMember);
      data.getJSONObject("members").remove(id);
      service.forgetDevice(id);
      service.token = Wire.token();
      data.put("revision", Math.max(System.currentTimeMillis(), data.getLong("revision") + 1));
      refresh();
      save();
      peers = new ArrayList<>(service.peers.values());
      for (Wire p : peers)
        if (!p.id.equals(id) && data.getJSONObject("members").has(p.id))
          updates.put(p.id, box(data.getJSONObject("members").getJSONObject(p.id)));
    }
    for (Wire p : peers)
      try {
        if (p.id.equals(id)) p.send(obj("type", "group-removed"));
        else if (updates.containsKey(p.id))
          p.request("group-update", obj("box", updates.get(p.id)));
      } catch (Exception ignored) {
      }
    service.groupChanged();
    publish();
  }

  synchronized JSONObject recoveryQuery() {
    return obj(
        "kind",
        "lanlink-group-recovery-v1",
        "group",
        data.optString("id"),
        "id",
        service.deviceId,
        "revision",
        data.optLong("revision"));
  }

  synchronized JSONObject recoveryResponse(JSONObject query) throws Exception {
    if (query.optString("kind").equals("lanlink-group-recovery-v1")
        && id(query.optString("id"))
        && query.optLong("revision", -1) >= 0) {
      JSONObject notice = dissolvedGroups().optJSONObject(query.optString("group"));
      if (notice != null) return notice;
    }
    if (!query.optString("kind").equals("lanlink-group-recovery-v1")
        || !query.optString("group").equals(data.optString("id"))
        || !id(query.optString("id"))
        || excluded(query.optString("id"))
        || query.optLong("revision", -1) < 0) return null;
    JSONObject result = null;
    JSONArray history = data.getJSONArray("history");
    for (int i = 0; i < history.length(); i++) {
      JSONObject b = history.getJSONObject(i);
      if (b.optString("to").equals(query.optString("id"))
          && b.optLong("revision") > query.optLong("revision")
          && (result == null || b.optLong("revision") < result.optLong("revision"))) result = b;
    }
    return result;
  }

  void announceRecovery() throws Exception {
    byte[] raw = bytes(recoveryQuery().toString());
    Set<InetAddress> targets = new HashSet<>();
    targets.add(InetAddress.getByName("255.255.255.255"));
    Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
    while (interfaces.hasMoreElements())
      for (InterfaceAddress a : interfaces.nextElement().getInterfaceAddresses())
        if (a.getBroadcast() != null) targets.add(a.getBroadcast());
    for (InetAddress a : targets) socket.send(new DatagramPacket(raw, raw.length, a, 45834));
  }

  void start() throws Exception {
    socket = new DatagramSocket(null);
    socket.setReuseAddress(true);
    socket.bind(new InetSocketAddress(45834));
    socket.setBroadcast(true);
    socket.setSoTimeout(250);
    new Thread(
            () -> {
              long next = 0;
              Map<String, Long> replyTimes = new HashMap<>(), replyVersions = new HashMap<>();
              while (!closed) {
                try {
                  if (System.currentTimeMillis() >= next) {
                    next = System.currentTimeMillis() + 2000;
                    announceRecovery();
                  }
                  DatagramPacket packet = new DatagramPacket(new byte[60001], 60001);
                  socket.receive(packet);
                  if (packet.getLength() > 60000) continue;
                  JSONObject m =
                      new JSONObject(
                          new String(
                              packet.getData(),
                              0,
                              packet.getLength(),
                              java.nio.charset.StandardCharsets.UTF_8));
                  if (m.optString("kind").equals("lanlink-group-recovery-v1")) {
                    if (packet.getPort() != 45834) continue;
                    JSONObject reply = recoveryResponse(m);
                    if (reply == null) continue;
                    String id = m.optString("id");
                    long now = System.currentTimeMillis(), version = m.optLong("revision");
                    if (now - replyTimes.getOrDefault(id, 0L) < 1500
                        && replyVersions.getOrDefault(id, -1L) == version) continue;
                    replyTimes.put(id, now);
                    replyVersions.put(id, version);
                    byte[] raw = bytes(reply.toString());
                    if (raw.length <= 60000)
                      socket.send(
                          new DatagramPacket(
                              raw, raw.length, packet.getAddress(), packet.getPort()));
                  } else {
                    if (m.optString("kind").equals("lanlink-group-dissolved-v1"))
                      acceptDissolution(m);
                    else accept(m);
                    next = 0;
                  }
                } catch (Exception ignored) {
                }
              }
            },
            "lanlink-group-recovery")
        .start();
  }

  public void close() {
    closed = true;
    if (socket != null) socket.close();
  }
}
