package com.lanlink.app;

import static com.lanlink.app.Wire.obj;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.graphics.*;
import android.hardware.display.*;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.*;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.*;
import android.provider.OpenableColumns;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public class LinkService extends Service implements Wire.Listener {
  public static volatile LinkService instance;
  public volatile JSONObject remoteCursor;
  public volatile String cursorPeer;
  public final ConcurrentHashMap<String, Wire> peers = new ConcurrentHashMap<>();
  private final Set<Wire> sockets = ConcurrentHashMap.newKeySet();
  private final Set<String> connecting = ConcurrentHashMap.newKeySet();
  public final ExecutorService jobs = Executors.newFixedThreadPool(6);
  private final ScheduledExecutorService clock = Executors.newScheduledThreadPool(2);
  private final Handler main = new Handler(Looper.getMainLooper());
  private SharedPreferences prefs;
  GroupKeys group;
  private final Set<String> pausedPeers = ConcurrentHashMap.newKeySet();
  public volatile String endedPeer;
  public volatile long endedAt;
  public String deviceId, name;
  public volatile String token;
  public volatile Pairing pairing;
  public volatile String status = "正在启动", active;
  private volatile String owner;
  private boolean ownerFallbackAccepted;
  private String notifiedController, lastCapabilities;
  private volatile long ownerUntil;
  private String outgoingPending, outgoingViewer;
  private boolean outgoingCancelled, outgoingReleasing;
  private final Map<String, Long> screenViewers = new ConcurrentHashMap<>();
  private volatile boolean running;
  private ServerSocket server;
  private DatagramSocket discovery;
  private WifiManager.MulticastLock multicast;
  private volatile MediaProjection projection;
  private VirtualDisplay display;
  private ImageReader reader;
  private final HandlerThread captureThread = new HandlerThread("lanlink-jpeg-capture");
  private Handler captureHandler;
  private volatile VideoHost videoHost;
  private VideoEncoder videoEncoder;
  private volatile byte[] jpeg;
  private int screenWidth, screenHeight;
  private volatile long lastFrame;
  private volatile JSONObject clip;
  private final Map<String, Map<String, Uri>> offers = new ConcurrentHashMap<>();
  private final Map<String, Long> offerExpiry = new ConcurrentHashMap<>();
  private volatile boolean settingClipboard;
  private final java.util.concurrent.atomic.AtomicBoolean receiving =
      new java.util.concurrent.atomic.AtomicBoolean();
  private final ClipboardManager.OnPrimaryClipChangedListener clipListener =
      () -> {
        if (!settingClipboard && allowed("clipboard"))
          jobs.execute(
              () -> {
                try {
                  publishClipboard();
                } catch (Exception e) {
                  status = e.getMessage();
                }
              });
      };
  private ClipboardManager clipboard;
  public ShellChannel shell;
  final PermissionNotice permissionNotice = new PermissionNotice();
  DeviceConsent consent;
  public volatile boolean uiVisible;

  public boolean screenReady() {
    return projection != null;
  }

  public void dismissPermissionNotice() {
    permissionNotice.dismiss();
    getSystemService(NotificationManager.class).cancel(2);
  }

  private void needPermission(Wire peer, String need) {
    // Only an authenticated, currently connected peer can produce a local prompt.
    if (peers.get(peer.id) != peer) return;
    String label = peer.info.optString("name", "已配对设备");
    label = label.replaceAll("[\\p{Cntrl}]", " ");
    if (label.length() > 60) label = label.substring(0, 60);
    final String device = label;
    if (!permissionNotice.offer(peer.id, device, need, System.currentTimeMillis())) return;
    status = device + (need.equals("consent") ? " 请求首次控制，请在手机确认" : " 请求协作，请在手机上开启所需权限");
    main.post(
        () -> {
          if (uiVisible || permissionNotice.peek(System.currentTimeMillis()) == null) return;
          Intent open =
              new Intent(this, MainActivity.class)
                  .setAction("com.lanlink.app.PERMISSIONS")
                  .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
          PendingIntent target =
              PendingIntent.getActivity(
                  this, 2, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
          Notification n =
              new Notification.Builder(this, "lanlink-permissions")
                  .setSmallIcon(android.R.drawable.stat_sys_warning)
                  .setContentTitle(device + " 请求协作")
                  .setContentText(
                      need.equals("consent") ? "首次控制需要你同意，点击选择允许的功能" : "需要开启手机权限，点击查看并设置")
                  .setStyle(
                      new Notification.BigTextStyle()
                          .bigText(
                              need.equals("consent")
                                  ? "首次控制需要你同意。点击选择允许的功能，同意后记住此设备。"
                                  : "点击查看缺少的权限和设置入口，已具备的权限会显示已授权。"))
                  .setContentIntent(target)
                  .setAutoCancel(true)
                  .setOnlyAlertOnce(true)
                  .setVisibility(Notification.VISIBILITY_PRIVATE)
                  .setTimeoutAfter(PermissionNotice.TTL)
                  .addAction(new Notification.Action.Builder(null, "查看权限", target).build())
                  .build();
          // Notification denial must not break the protocol error response; the in-app card
          // remains.
          try {
            getSystemService(NotificationManager.class).notify(2, n);
          } catch (SecurityException ignored) {
          }
        });
  }

  private void requireInputPermission(Wire p) throws IOException {
    requireConsent(p, "input");
    if (!allowed("input") || !capabilities().optBoolean("input")) {
      needPermission(p, "input");
      throw new IOException("PERMISSION_REQUIRED: 手机输入助手尚未就绪，请在电脑端恢复 ADB 连接或在手机启动 Root 助手");
    }
  }

  private void requireConsent(Wire p, String key) throws IOException {
    if (group != null && group.isDissolved()) throw new IOException("当前组已解散");
    if (pausedPeers.contains(p.id)) throw new IOException("CONTROL_ENDED: 被控设备已结束控制，请重新请求");
    if (group != null && group.excluded(p.id) || peers.get(p.id) != p)
      throw new IOException("设备连接已结束");
    if (key.equals("clipboard") && canShareClipboard(p.id)) return;
    String name = p.info.optString("name", "已配对设备").replaceAll("[\\p{Cntrl}]", " ");
    if (name.length() > 60) name = name.substring(0, 60);
    try {
      consent.require(p.id, name, key);
    } catch (IOException e) {
      if (e.getMessage().startsWith("CONSENT_PENDING:")) needPermission(p, "consent");
      throw e;
    }
  }

  private boolean canShareClipboard(String id) {
    return consent.allowed(id, "clipboard") || id.equals(active) && !consent.hasGrant(id);
  }

  public void decideConsent(DeviceConsent.Request request, boolean accepted, JSONObject permissions)
      throws IOException {
    if (!peers.containsKey(request.id)) throw new IOException("设备已断开");
    String saved = consent.decide(request.id, request.requestId, accepted, permissions);
    SharedPreferences.Editor decision = prefs.edit().putString("device_grants", saved);
    if (accepted)
      for (String k : new String[] {"input", "screen", "clipboard"})
        if (permissions.optBoolean(k)) decision.putBoolean("allow_" + k, true);
    if (!decision.commit()) {
      consent.reset();
      throw new IOException("设备授权保存失败，请重试");
    }
    dismissPermissionNotice();
  }

  public synchronized void revokeConsent(String id) {
    if (videoHost != null && videoHost.peer.id.equals(id)) videoHost.close();
    prefs.edit().putString("device_grants", consent.revoke(id)).apply();
    if (id.equals(owner)) {
      owner = null;
      releaseInput();
    }
    if (clip != null && id.equals(clip.optString("source"))) clip = null;
    permissionNotice.removePeer(id);
  }

  private float px = .5f, py = .5f, downX, downY;
  private long downTime;
  private boolean touched;

  public android.os.IBinder onBind(Intent i) {
    return null;
  }

  public boolean allowed(String key) {
    return prefs.getBoolean("allow_" + key, false);
  }

  public void setAllowed(String key, boolean value) {
    prefs.edit().putBoolean("allow_" + key, value).apply();
    if (!value && key.equals("input")) {
      owner = null;
      releaseInput();
    }
    if (!value && key.equals("screen")) stopProjection();
  }

  public void onCreate() {
    super.onCreate();
    captureThread.start();
    captureHandler = new Handler(captureThread.getLooper());
    instance = this;
    prefs = getSharedPreferences("identity", MODE_PRIVATE);
    pausedPeers.addAll(prefs.getStringSet("paused_peers", new HashSet<>()));
    consent = new DeviceConsent(prefs.getString("device_grants", "{}"));
    deviceId = prefs.getString("id", UUID.randomUUID().toString());
    token = prefs.getString("token", Wire.token());
    name = prefs.getString("name", Build.MODEL);
    prefs
        .edit()
        .putString("id", deviceId)
        .putString("token", token)
        .putString("name", name)
        .apply();
    NotificationManager nm = getSystemService(NotificationManager.class);
    nm.createNotificationChannel(
        new NotificationChannel("lanlink", "局域网协作", NotificationManager.IMPORTANCE_LOW));
    nm.createNotificationChannel(
        new NotificationChannel(
            "lanlink-permissions", "协作权限提醒", NotificationManager.IMPORTANCE_DEFAULT));
    startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
    clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
    clipboard.addPrimaryClipChangedListener(clipListener);
    shell = new ShellChannel(this);
    running = true;
    jobs.execute(this::network);
    clock.scheduleAtFixedRate(this::tick, 1, 1, TimeUnit.SECONDS);
  }

  private Notification notification() {
    String controller = controlledBy();
    Intent stop =
        new Intent(this, LinkService.class).setAction(controller == null ? "stop" : "stop-control");
    return new Notification.Builder(this, "lanlink")
        .setContentTitle(controller == null ? "LanLink 正在运行" : "正在被 " + controller + " 控制 / 共享屏幕")
        .setContentText(controller == null ? "同组设备可使用你已开启的功能" : "可随时结束本次控制")
        .setSmallIcon(android.R.drawable.stat_sys_upload_done)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE))
        .addAction(
            new Notification.Action.Builder(
                    null,
                    controller == null ? "停止协作" : "结束控制",
                    PendingIntent.getService(
                        this, controller == null ? 1 : 3, stop, PendingIntent.FLAG_IMMUTABLE))
                .build())
        .setOngoing(true)
        .build();
  }

  public int onStartCommand(Intent i, int flags, int startId) {
    if (i != null && "stop-control".equals(i.getAction())) {
      stopInbound();
      return START_NOT_STICKY;
    }
    if (i != null && "stop".equals(i.getAction())) {
      stopSelf();
      return START_NOT_STICKY;
    }
    return START_NOT_STICKY;
  }

  public JSONObject capabilities() {
    android.util.DisplayMetrics m = getResources().getDisplayMetrics();
    return obj(
        "input",
        shell != null && shell.ready(),
        "host",
        false,
        "screen",
        projection != null,
        "clipboard",
        true,
        "shutdown",
        false,
        "width",
        m.widthPixels,
        "height",
        m.heightPixels,
        "inputMode",
        shell != null && shell.ready() ? "adb/root" : "unavailable",
        "inputWarning",
        shell != null && shell.ready() ? "" : "请在电脑端启用 ADB 键鼠授权，或启动 Root 助手");
  }

  private JSONObject identity() {
    JSONObject caps = capabilities();
    if (group != null)
      try {
        caps.put("memberKey", group.own().getString("key"));
      } catch (Exception ignored) {
      }
    return obj("id", deviceId, "name", name, "capabilities", caps);
  }

  public JSONObject knownDevices() {
    try {
      return new JSONObject(prefs.getString("known_devices", "{}"));
    } catch (JSONException e) {
      return new JSONObject();
    }
  }

  private synchronized void rememberDevice(Wire p) {
    if (peers.get(p.id) != p) return;
    JSONObject known = knownDevices();
    // Bound storage from device-supplied metadata, and retain no account secrets here.
    if (!known.has(p.id) && known.length() >= 32) known.remove(known.keys().next());
    String label = p.info.optString("name", "已配对设备").replaceAll("[\\p{Cntrl}]", " ");
    if (label.length() > 60) label = label.substring(0, 60);
    try {
      known.put(p.id, obj("name", label, "platform", p.info.optString("platform", "设备")));
    } catch (JSONException ignored) {
      return;
    }
    prefs.edit().putString("known_devices", known.toString()).apply();
  }

  public boolean groupEstablished() {
    return group != null && group.established();
  }

  public void joinGroup(JSONObject result, boolean dissolve, String expectedGroup)
      throws Exception {
    if (group == null) throw new IOException("组服务尚未就绪");
    synchronized (group) {
      if (group == null || !group.data.optString("id").equals(expectedGroup))
        throw new IOException("组已发生变化，请重新打开添加设备");
      JSONObject meta = result.getJSONObject("group");
      Wire.parseToken(result.getString("token"));
      GroupKeys.validateMeta(meta);
      if (group.dissolvedGroups().has(meta.getString("id")))
        throw new IOException("该组已经解散，请生成新的组邀请");
      if (!meta.getString("id").matches("[a-f0-9]{24}")
          || meta.getLong("revision") < 0
          || meta.getJSONObject("members").length() > 16) throw new IOException("组信息无效");
      for (Iterator<String> it = meta.getJSONObject("members").keys(); it.hasNext(); )
        GroupKeys.publicKey(meta.getJSONObject("members").getJSONObject(it.next()));
      if (meta.getString("id").equals(expectedGroup)) return;
      if (group.established()) {
        if (!dissolve) throw new IOException("组已有成员，请重新确认解散当前组");
        JSONObject pending = new JSONObject(result.toString()).put("previousGroup", expectedGroup);
        if (!prefs.edit().putString("pending_group_join", pending.toString()).commit())
          throw new IOException("加入信息保存失败");
        group.dissolve();
      }
      join(result.getString("token"), meta);
      prefs.edit().remove("pending_group_join").commit();
      connect(result.getString("host"), result.getInt("port"));
    }
  }

  public void join(String value) throws Exception {
    join(value, null);
  }

  public void join(String value, JSONObject meta) throws Exception {
    Wire.parseToken(value);
    permissionNotice.reset();
    getSystemService(NotificationManager.class).cancel(2);
    releaseControl();
    for (Wire p : sockets) p.close();
    token = value;
    if (group != null) group.adopt(meta, value);
    pausedPeers.clear();
    prefs.edit().remove("paused_peers").apply();
    if (pairing != null) pairing.hide();
    consent.reset();
    prefs.edit().putString("token", value).remove("known_devices").remove("device_grants").apply();
    synchronized (this) {
      outgoingViewer = null;
      screenViewers.clear();
    }
    offers.clear();
    offerExpiry.clear();
    clip = null;
    status = "已更新组，等待自动连接";
  }

  private void network() {
    try {
      server = new ServerSocket(45831);
      group = new GroupKeys(this, prefs);
      JSONObject pendingJoin = new JSONObject(prefs.getString("pending_group_join", "{}"));
      if (group.isDissolved()
          || group.dissolvedGroups().has(pendingJoin.optString("previousGroup"))) {
        if (pendingJoin.has("group"))
          join(pendingJoin.getString("token"), pendingJoin.getJSONObject("group"));
        else join(Wire.token(), null);
      }
      prefs.edit().remove("pending_group_join").commit();
      group.start();
      pairing = new Pairing(() -> token, 45831);
      pairing.members = group;
      try {
        pairing.start(true);
      } catch (Exception e) {
        status = "配对发现受限：" + e.getMessage();
      }
      new Thread(
              () -> {
                while (running)
                  try {
                    attach(server.accept());
                  } catch (Exception e) {
                    if (running) status = "监听失败";
                  }
              },
              "lanlink-listener")
          .start();
      WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
      multicast = wifi.createMulticastLock("lanlink-discovery");
      multicast.setReferenceCounted(false);
      multicast.acquire();
      discovery = new DatagramSocket(null);
      discovery.setReuseAddress(true);
      discovery.bind(new InetSocketAddress(45832));
      discovery.setBroadcast(true);
      clock.scheduleAtFixedRate(this::announce, 0, 3, TimeUnit.SECONDS);
      status = "已启动，可在设置中开启所需功能";
      byte[] buffer = new byte[2048];
      while (running) {
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        discovery.receive(packet);
        try {
          JSONObject r =
              new JSONObject(
                  new String(
                      packet.getData(),
                      0,
                      packet.getLength(),
                      java.nio.charset.StandardCharsets.UTF_8));
          String body = r.getString("body");
          if (!java.security.MessageDigest.isEqual(
              Wire.bytes(r.getString("proof")),
              Wire.bytes(Wire.proof(Wire.parseToken(token), "discovery|" + body)))) continue;
          JSONObject b = new JSONObject(body);
          String id = b.getString("id");
          long time = b.getLong("time");
          int port = b.getInt("port");
          if (b.optInt("v") == 1
              && id.matches("[a-f0-9-]{36}")
              && Math.abs(System.currentTimeMillis() - time) < 30000
              && port > 0
              && port < 65536
              && deviceId.compareTo(id) < 0
              && !peers.containsKey(id)) connect(packet.getAddress().getHostAddress(), port);
        } catch (Exception ignored) {
        }
      }
    } catch (Exception e) {
      if (running) status = "自动发现受限，可手动连接：" + e.getMessage();
    }
  }

  private void announce() {
    try {
      String body =
          obj("v", 1, "id", deviceId, "port", 45831, "time", System.currentTimeMillis()).toString();
      byte[] data =
          Wire.bytes(
              obj("body", body, "proof", Wire.proof(Wire.parseToken(token), "discovery|" + body))
                  .toString());
      Set<InetAddress> destinations = new HashSet<>();
      destinations.add(InetAddress.getByName("255.255.255.255"));
      Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
      while (interfaces.hasMoreElements())
        for (InterfaceAddress a : interfaces.nextElement().getInterfaceAddresses())
          if (a.getBroadcast() != null) destinations.add(a.getBroadcast());
      for (InetAddress a : destinations)
        discovery.send(new DatagramPacket(data, data.length, a, 45832));
    } catch (Exception ignored) {
    }
  }

  public void connect(String host, int port) {
    String key = host + ":" + port;
    if (!connecting.add(key)) return;
    jobs.execute(
        () -> {
          try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(host, port), 5000);
            attach(s, true);
          } catch (Exception e) {
            status = "连接失败：" + e.getMessage();
          } finally {
            connecting.remove(key);
          }
        });
  }

  private void attach(Socket socket) {
    attach(socket, false);
  }

  private void attach(Socket socket, boolean outgoing) {
    if (sockets.size() >= 24) {
      try {
        socket.close();
      } catch (Exception ignored) {
      }
      return;
    }
    Wire p = new Wire(socket, identity(), Wire.parseToken(token), this);
    p.outgoing = outgoing;
    sockets.add(p);
    p.start();
  }

  public synchronized void ready(Wire p) {
    if (group != null && group.excluded(p.id)) {
      p.close();
      return;
    }
    Wire old = peers.get(p.id);
    if (old != null) {
      boolean preferOutgoing = deviceId.compareTo(p.id) < 0;
      if (p.outgoing == preferOutgoing && old.outgoing != preferOutgoing) {
        peers.put(p.id, p);
        old.close();
      } else {
        p.close();
        return;
      }
    } else peers.put(p.id, p);
    status = "已连接同组设备";
    if (allowed("clipboard")
        && consent.allowed(p.id, "clipboard")
        && clip != null
        && deviceId.equals(clip.optString("source")))
      try {
        p.send(obj("type", "clipboard", "offer", clip));
      } catch (Exception ignored) {
      }
  }

  public void closed(Wire p) {
    if (videoHost != null && videoHost.peer == p) videoHost.close();
    sockets.remove(p);
    if (p.id != null && peers.remove(p.id, p)) {
      synchronized (this) {
        screenViewers.remove(p.id);
        endViewing(p.id);
        if (p.id.equals(outgoingPending)) outgoingCancelled = true;
      }
      permissionNotice.removePeer(p.id);
      consent.disconnect(p.id);
      if (permissionNotice.peek(System.currentTimeMillis()) == null)
        getSystemService(NotificationManager.class).cancel(2);
      if (p.id.equals(owner)) {
        owner = null;
        releaseInput();
      }
      if (p.id.equals(active)) active = null;
    }
  }

  public void message(Wire p, JSONObject m) {
    String type = m.optString("type");
    try {
      if (type.equals("cursor")) {
        if (peers.get(p.id) != p || !p.id.equals(active)) return;
        double x = m.optDouble("x", -1), y = m.optDouble("y", -1);
        if (!Double.isFinite(x) || !Double.isFinite(y) || x < 0 || x > 1 || y < 0 || y > 1) return;
        JSONObject previous = remoteCursor;
        if (!p.id.equals(cursorPeer)
            || previous == null
            || previous.optDouble("x") != x
            || previous.optDouble("y") != y) {
          cursorPeer = p.id;
          remoteCursor = m;
        }
        return;
      }
      if (group != null && group.excluded(p.id)) {
        p.close();
        return;
      }
      if (type.equals("group-dissolved")) {
        group.acceptDissolution(m.getJSONObject("notice"));
        return;
      }
      if (type.equals("group-members")) {
        group.merge(m);
        return;
      }
      if (type.equals("group-removed")) {
        join(Wire.token(), null);
        status = "本设备已移出组，需要重新配对";
        return;
      }
      if (type.equals("control-ended")) {
        endedPeer = p.id;
        endedAt = System.currentTimeMillis();
        if (p.id.equals(active)) releaseControl();
        return;
      }
      if (type.equals("identity")) {
        p.info = m;
        rememberDevice(p);
        JSONObject caps = m.optJSONObject("capabilities");
        if (group != null
            && caps != null
            && caps.has("memberKey")
            && group.learn(
                obj(
                    "id",
                    p.id,
                    "name",
                    m.optString("name"),
                    "platform",
                    m.optString("platform"),
                    "key",
                    caps.getString("memberKey")),
                false)) {
          group.save();
          group.publish();
        }
        return;
      }
      if (type.equals("ping")) {
        p.send(obj("type", "pong"));
        return;
      }
      if (type.equals("pong")) return;
      if (type.equals("clipboard")) {
        if (allowed("clipboard") && canShareClipboard(p.id)) {
          JSONObject offer = m.getJSONObject("offer");
          validateOffer(offer);
          offer.put("source", p.id);
          clip = offer;
        }
        return;
      }
      if (type.equals("request")) {
        if (jobs instanceof ThreadPoolExecutor
            && ((ThreadPoolExecutor) jobs).getQueue().size() > 100) {
          p.close();
          return;
        }
        jobs.execute(
            () -> {
              try {
                p.send(
                    obj(
                        "type",
                        "response",
                        "id",
                        m.optString("id"),
                        "result",
                        handle(p, m.optString("method"), m.optJSONObject("args"))));
              } catch (Exception e) {
                try {
                  p.send(
                      obj(
                          "type",
                          "response",
                          "id",
                          m.optString("id"),
                          "error",
                          e.getMessage() == null ? "操作失败" : e.getMessage()));
                } catch (Exception ignored) {
                }
              }
            });
      }
    } catch (Exception e) {
      p.close();
    }
  }

  private void allow(String k) throws Exception {
    if (!allowed(k)) throw new IOException("安卓端未允许此功能：" + k);
  }

  private synchronized void requireOwner(Wire p) throws Exception {
    requireInputPermission(p);
    if (!p.id.equals(owner) || ownerUntil < System.currentTimeMillis())
      throw new IOException("请先取得控制权");
    ownerUntil = System.currentTimeMillis() + 4000;
  }

  private JSONObject handle(Wire p, String method, JSONObject a) throws Exception {
    if (group != null && group.excluded(p.id)) throw new IOException("设备已移出组");
    if (method.equals("permission-status")) {
      if (peers.get(p.id) != p) throw new IOException("设备连接已结束");
      JSONObject entries = obj();
      boolean inputReady = shell != null && shell.healthy() && shell.lastInputError == null;
      for (String key : new String[] {"input", "screen", "clipboard", "shutdown"})
        entries.put(
            key,
            obj(
                "supported",
                !key.equals("shutdown"),
                "appEnabled",
                allowed(key),
                "systemGranted",
                key.equals("input")
                    ? inputReady
                    : key.equals("screen") ? screenReady() : !key.equals("shutdown"),
                "peerAllowed",
                consent.hasGrant(p.id) ? consent.allowed(p.id, key) : JSONObject.NULL));
      return obj(
          "version",
          1,
          "permissions",
          entries,
          "engine",
          obj(
              "ready",
              inputReady,
              "persistent",
              true,
              "message",
              shell != null && shell.lastInputError != null
                  ? shell.lastInputError
                  : inputReady ? "ADB / Root 助手已连接" : "等待已信任的 USB / 无线调试连接恢复"));
    }
    if (method.equals("group-dissolve")) {
      group.acceptDissolution(a.getJSONObject("notice"));
      return obj();
    }
    if (method.equals("group-update")) {
      group.accept(a.getJSONObject("box"));
      return obj();
    }
    if (method.equals("videoOpen")) {
      synchronized (this) {
        requireScreen(p);
        if (videoHost != null && !videoHost.closed && videoHost.peer != p)
          throw new IOException("手机已有屏幕串流会话");
        if (videoHost != null) videoHost.close();
        videoHost = new VideoHost(this, p, a != null && a.optBoolean("h264"));
        return videoHost.info();
      }
    }
    if (method.equals("videoKeep") || method.equals("videoStop")) {
      VideoHost stream = videoHost;
      if (stream == null || stream.peer != p || a == null || !stream.id.equals(a.optString("id")))
        throw new IOException("串流已结束");
      if (method.equals("videoKeep")) return stream.keep(p, a.optJSONObject("feedback"));
      else stream.close();
      return obj();
    }
    if (a == null) a = new JSONObject();
    switch (method) {
      case "adbBootstrap":
        requireAdbPermission(p);
        return shell.bootstrap();
      case "adbStatus":
        requireAdbPermission(p);
        boolean ready = shell.healthy();
        return obj("ready", ready, "inputMode", capabilities().optString("inputMode"));
      case "acquire":
        synchronized (this) {
          requireIncomingDirection();
        }
        if (a.optBoolean("newSession") && pausedPeers.remove(p.id)) {
          revokeConsent(p.id);
          prefs.edit().putStringSet("paused_peers", new HashSet<>(pausedPeers)).apply();
        }
        requireInputPermission(p);
        synchronized (this) {
          requireIncomingDirection();
          if (owner != null && !owner.equals(p.id) && ownerUntil > System.currentTimeMillis())
            throw new IOException("CONTROL_CONFLICT: 本设备正在被另一台设备控制");
          owner = p.id;
          ownerFallbackAccepted = a.optBoolean("fallbackAccepted");
          ownerUntil = System.currentTimeMillis() + 4000;
        }
        return capabilities();
      case "release":
        if (p.id.equals(owner)) {
          owner = null;
          releaseInput();
        }
        return obj();
      case "keepControl":
        requireOwner(p);
        if (shell.ready()) shell.request(obj("method", "heartbeat"));
        return obj();
      case "input":
        requireOwner(p);
        try {
          input(a);
        } catch (Exception error) {
          needPermission(p, "input");
          throw error;
        }
        return obj();
      case "text":
        requireOwner(p);
        String text = a.getString("text");
        if (text.length() > 100000) throw new IOException("文本过长");
        requireClipboardPermission(p);
        onMain(
            () -> {
              clipboard.setPrimaryClip(android.content.ClipData.newPlainText("LanLink 输入", text));
              return true;
            });
        requireOwner(p);
        shell.request(obj("method", "paste"));
        return obj();
      case "screenStop":
        if (peers.get(p.id) != p) throw new IOException("设备连接已结束");
        screenViewers.remove(p.id);
        return obj();
      case "screen":
        synchronized (this) {
          requireScreen(p);
          screenViewers.put(p.id, System.currentTimeMillis() + 4000);
        }
        if (jpeg == null) throw new IOException("手机正在准备屏幕画面，请稍后重试");
        return obj(
            "mime",
            "image/jpeg",
            "data",
            Base64.getEncoder().encodeToString(jpeg),
            "width",
            screenWidth,
            "height",
            screenHeight);
      case "clipboardGet":
        requireClipboardPermission(p);
        return snapshotClipboard();
      case "fileChunk":
        requireClipboardPermission(p);
        return fileChunk(a);
      case "paste":
        requireOwner(p);
        requireClipboardPermission(p);
        receive(a.getJSONObject("offer"));
        requireOwner(p);
        requireClipboardPermission(p);
        shell.request(obj("method", "paste"));
        return obj();
      default:
        throw new IOException("安卓不支持此操作");
    }
  }

  private void requireClipboardPermission(Wire p) throws IOException {
    requireConsent(p, "clipboard");
    if (!allowed("clipboard")) {
      needPermission(p, "clipboard");
      throw new IOException("PERMISSION_REQUIRED: 手机尚未允许剪贴板与文件共享，请在手机开启");
    }
  }

  private <T> T onMain(Callable<T> task) throws Exception {
    if (Looper.myLooper() == Looper.getMainLooper()) return task.call();
    FutureTask<T> f = new FutureTask<>(task);
    main.post(f);
    return f.get(8, TimeUnit.SECONDS);
  }

  private synchronized void input(JSONObject a) throws Exception {
    if (!shell.ready()) throw new IOException("PERMISSION_REQUIRED: ADB / Root 输入助手未就绪，请在电脑端恢复连接");
    shell.request(obj("method", "input", "args", a));
  }

  private void releaseInput() {
    touched = false;
    if (shell != null && shell.ready())
      jobs.execute(
          () -> {
            try {
              shell.request(obj("method", "release"));
            } catch (Exception ignored) {
            }
          });
  }

  private synchronized void requireIncomingDirection() throws IOException {
    if (active != null || outgoingPending != null || outgoingViewer != null || outgoingReleasing)
      throw new IOException("CONTROL_CONFLICT: 本设备正在控制或查看其他设备，请先关闭远程窗口");
  }

  private synchronized void requireOutgoingDirection() throws IOException {
    long now = System.currentTimeMillis();
    screenViewers.values().removeIf(expires -> expires < now);
    if (owner != null && ownerUntil > now
        || videoHost != null && !videoHost.closed
        || !screenViewers.isEmpty())
      throw new IOException("CONTROL_CONFLICT: 本设备正在被控制或共享屏幕，请先结束被控会话");
  }

  public synchronized void beginViewing(String id) throws IOException {
    requireOutgoingDirection();
    if (outgoingViewer != null && !id.equals(outgoingViewer))
      throw new IOException("CONTROL_CONFLICT: 请先关闭当前远程窗口");
    outgoingViewer = id;
  }

  public synchronized void endViewing(String id) {
    if (id.equals(outgoingViewer)) outgoingViewer = null;
  }

  private void requireAdbPermission(Wire peer) throws IOException {
    requireConsent(peer, "input");
    if (!allowed("input")) {
      needPermission(peer, "input");
      throw new IOException("PERMISSION_REQUIRED: 请先在手机同意远程输入");
    }
  }

  public void control(String id) throws Exception {
    control(id, false);
  }

  public void control(String id, boolean newSession) throws Exception {
    control(id, newSession, false);
  }

  public void control(String id, boolean newSession, boolean fallbackAccepted) throws Exception {
    Wire target = peer(id);
    synchronized (this) {
      requireOutgoingDirection();
      if (outgoingPending != null || outgoingReleasing)
        throw new IOException("CONTROL_CONFLICT: 上次控制请求尚未结束，请稍后重试");
      if (active != null) {
        if (id.equals(active)) return;
        throw new IOException("CONTROL_CONFLICT: 请先结束当前控制");
      }
      outgoingPending = id;
      outgoingCancelled = false;
    }
    boolean acquired = false;
    try {
      JSONObject caps =
          target.request(
              "acquire", obj("newSession", newSession, "fallbackAccepted", fallbackAccepted));
      acquired = true;
      synchronized (this) {
        if (outgoingCancelled || peers.get(id) != target)
          throw new IOException("CONTROL_ENDED: 控制请求已取消");
        target.info.put("capabilities", caps);
        active = id;
      }
    } finally {
      boolean cancel;
      synchronized (this) {
        cancel = acquired && !id.equals(active);
      }
      if (cancel)
        try {
          target.request("release", obj());
        } catch (Exception ignored) {
        }
      synchronized (this) {
        outgoingPending = null;
        outgoingCancelled = false;
      }
    }
  }

  public void releaseControl() {
    final Wire target;
    synchronized (this) {
      String id = active;
      target = id == null ? null : peers.get(id);
      active = null;
      if (outgoingPending != null) outgoingCancelled = true;
      if (target != null) outgoingReleasing = true;
    }
    if (target != null)
      jobs.execute(
          () -> {
            try {
              target.request("release", obj());
            } catch (Exception ignored) {
            } finally {
              synchronized (LinkService.this) {
                outgoingReleasing = false;
              }
            }
          });
  }

  public Wire peer(String id) throws IOException {
    Wire p = peers.get(id);
    if (p == null) throw new IOException("设备不在线");
    return p;
  }

  public JSONObject screen(String id) throws Exception {
    return peer(id).request("screen", obj());
  }

  public void remoteInput(JSONObject a) throws Exception {
    remoteInput(active, a);
  }

  public void remoteInput(String target, JSONObject a) throws Exception {
    if (target == null || !target.equals(active)) throw new IOException("等待电脑同意控制；已同意时请点击重新请求");
    Wire destination = peer(target);
    JSONObject caps = destination.info.optJSONObject("capabilities");
    if (a.optString("kind").equals("relative")
        && (caps == null || !caps.optBoolean("relativeInput")))
      throw new IOException("电脑端版本不支持触控板，请升级并启动新版电脑端");
    try {
      destination.request("input", a);
    } catch (Exception error) {
      if (target.equals(active)) releaseControl();
      throw error;
    }
  }

  public void pasteRemote() throws Exception {
    if (active == null) throw new IOException("请先开始控制");
    if (clip == null) clip = snapshotClipboard();
    peer(active).request("paste", obj("offer", clip));
  }

  private void tick() {
    if (!running) return;
    String controller = controlledBy();
    if (!java.util.Objects.equals(controller, notifiedController)) {
      notifiedController = controller;
      try {
        getSystemService(NotificationManager.class).notify(1, notification());
      } catch (SecurityException ignored) {
      }
    }
    try {
      String currentCapabilities = capabilities().toString();
      if (!currentCapabilities.equals(lastCapabilities)) {
        lastCapabilities = currentCapabilities;
        JSONObject changed = identity().put("type", "identity").put("platform", "android");
        for (Wire peer : peers.values())
          try {
            peer.send(changed);
          } catch (Exception ignored) {
          }
      }
      for (Wire p : peers.values())
        try {
          if (System.currentTimeMillis() - p.lastSeen > 10000) p.close();
          else p.send(obj("type", "ping"));
        } catch (Exception e) {
          p.close();
        }
      if (active != null) {
        String id = active;
        jobs.execute(
            () -> {
              try {
                peer(id).request("keepControl", obj());
              } catch (Exception e) {
                if (id.equals(active)) active = null;
              }
            });
      }
      if (owner != null && ownerUntil < System.currentTimeMillis()) {
        owner = null;
        releaseInput();
      }
    } catch (Exception ignored) {
    }
  }

  public static void validateOffer(JSONObject offer) throws Exception {
    if (offer.getString("id").length() > 64 || offer.getString("text").length() > 1000000)
      throw new IOException("剪贴板过大");
    JSONArray files = offer.getJSONArray("files");
    if (files.length() > 100) throw new IOException("文件过多");
    long total = 0;
    for (int i = 0; i < files.length(); i++) {
      JSONObject f = files.getJSONObject(i);
      safeName(f.getString("name"));
      long size = f.getLong("size");
      if (size < 0 || size > 2147483648L || f.getString("id").length() > 64)
        throw new IOException("文件大小无效");
      total += size;
    }
    if (total > 2147483648L) throw new IOException("单次最多传输 2 GB");
  }

  public static String safeName(String s) throws IOException {
    if (s.isEmpty()
        || s.length() > 180
        || s.matches(".*[<>:\"/\\\\|?*\\x00-\\x1f].*")
        || s.matches(".*[. ]$")
        || s.matches("(?i)^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\..*)?$"))
      throw new IOException("文件名不兼容");
    return s;
  }

  public JSONObject snapshotClipboard() throws Exception {
    return onMain(
        () -> {
          ClipData c = clipboard.getPrimaryClip();
          if (c == null) throw new IOException("安卓限制后台剪贴板读取，请切到 LanLink 或启用其输入法");
          JSONArray files = new JSONArray();
          Map<String, Uri> uris = new HashMap<>();
          String text = "";
          for (int i = 0; i < c.getItemCount(); i++) {
            ClipData.Item item = c.getItemAt(i);
            if (item.getUri() != null) {
              Uri uri = item.getUri();
              String fileName = "shared-file";
              long size = -1;
              try (Cursor cursor =
                  getContentResolver()
                      .query(
                          uri,
                          new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                          null,
                          null,
                          null)) {
                if (cursor != null && cursor.moveToFirst()) {
                  fileName = cursor.getString(0);
                  size = cursor.getLong(1);
                }
              }
              String id = UUID.randomUUID().toString();
              files.put(obj("id", id, "name", safeName(fileName), "size", size));
              uris.put(id, uri);
            } else if (i == 0 && item.getText() != null) text = item.getText().toString();
          }
          String id = UUID.randomUUID().toString();
          JSONObject offer = obj("id", id, "text", text, "files", files, "source", deviceId);
          validateOffer(offer);
          if (offers.size() >= 20) {
            String first = offers.keySet().iterator().next();
            offers.remove(first);
            offerExpiry.remove(first);
          }
          offers.put(id, uris);
          offerExpiry.put(id, System.currentTimeMillis() + 3600000);
          clip = offer;
          return offer;
        });
  }

  public void publishClipboard() throws Exception {
    allow("clipboard");
    JSONObject o = snapshotClipboard();
    for (Wire p : peers.values())
      if (canShareClipboard(p.id)) p.send(obj("type", "clipboard", "offer", o));
  }

  public void publishText(String text) throws Exception {
    allow("clipboard");
    onMain(
        () -> {
          settingClipboard = true;
          try {
            clipboard.setPrimaryClip(ClipData.newPlainText("LanLink", text));
          } finally {
            main.postDelayed(() -> settingClipboard = false, 200);
          }
          return true;
        });
    publishClipboard();
  }

  public void publishUri(Uri uri) throws Exception {
    allow("clipboard");
    onMain(
        () -> {
          clipboard.setPrimaryClip(ClipData.newUri(getContentResolver(), "LanLink", uri));
          return true;
        });
    publishClipboard();
  }

  private JSONObject fileChunk(JSONObject a) throws Exception {
    String offerId = a.getString("offerId");
    Map<String, Uri> map = offers.get(offerId);
    Uri uri = map == null ? null : map.get(a.getString("fileId"));
    long offset = a.getLong("offset");
    if (uri == null
        || offset < 0
        || offerExpiry.getOrDefault(offerId, 0L) < System.currentTimeMillis())
      throw new IOException("文件分享已过期");
    try (InputStream in = getContentResolver().openInputStream(uri)) {
      if (in == null) throw new IOException("无法打开文件");
      long left = offset;
      while (left > 0) {
        long n = in.skip(left);
        if (n <= 0) {
          if (in.read() == -1) throw new IOException("文件已变化");
          n = 1;
        }
        left -= n;
      }
      byte[] data = new byte[192 * 1024];
      int n = 0, k;
      while (n < data.length && (k = in.read(data, n, data.length - n)) != -1) n += k;
      data = Arrays.copyOf(data, n);
      return obj(
          "data", Base64.getEncoder().encodeToString(data), "sha256", Wire.hex(Wire.sha(data)));
    }
  }

  public void receiveCurrent() throws Exception {
    if (clip == null) throw new IOException("没有共享内容");
    receive(clip);
  }

  private void receive(JSONObject offer) throws Exception {
    allow("clipboard");
    validateOffer(offer);
    if (!receiving.compareAndSet(false, true)) throw new IOException("正在接收文件，请稍后重试");
    JSONArray files = offer.getJSONArray("files");
    List<File> written = new ArrayList<>();
    try {
      ClipData data;
      if (files.length() == 0) data = ClipData.newPlainText("LanLink", offer.getString("text"));
      else {
        File root = new File(getFilesDir(), "received");
        if (!root.exists() && !root.mkdirs()) throw new IOException("无法创建接收目录");
        data = null;
        for (int i = 0; i < files.length(); i++) {
          JSONObject f = files.getJSONObject(i);
          String filename = UUID.randomUUID() + "-" + safeName(f.getString("name"));
          File destination = new File(root, filename);
          written.add(destination);
          long size = f.getLong("size"), offset = 0;
          try (FileOutputStream out = new FileOutputStream(destination)) {
            while (offset < size) {
              JSONObject args =
                  obj(
                      "offerId",
                      offer.getString("id"),
                      "fileId",
                      f.getString("id"),
                      "offset",
                      offset);
              JSONObject chunk =
                  offer.optString("source").equals(deviceId)
                      ? fileChunk(args)
                      : peer(offer.getString("source")).request("fileChunk", args);
              byte[] b = Base64.getDecoder().decode(chunk.getString("data"));
              if (b.length == 0
                  || b.length > 192 * 1024
                  || offset + b.length > size
                  || !Wire.hex(Wire.sha(b)).equals(chunk.getString("sha256")))
                throw new IOException("文件校验失败");
              out.write(b);
              offset += b.length;
              status =
                  "正在接收 " + f.getString("name") + " " + (offset * 100 / Math.max(1, size)) + "%";
            }
          }
          Uri uri =
              new Uri.Builder()
                  .scheme("content")
                  .authority("com.lanlink.app.received")
                  .appendPath(filename)
                  .build();
          if (data == null) data = ClipData.newUri(getContentResolver(), "LanLink", uri);
          else data.addItem(new ClipData.Item(uri));
        }
      }
      final ClipData result = data;
      onMain(
          () -> {
            settingClipboard = true;
            try {
              clipboard.setPrimaryClip(result);
            } finally {
              main.postDelayed(() -> settingClipboard = false, 300);
            }
            return true;
          });
      status = "内容已放入安卓剪贴板，目标应用需支持相应格式";
    } catch (Exception e) {
      for (File f : written) f.delete();
      throw e;
    } finally {
      receiving.set(false);
    }
  }

  public void startProjection(int code, Intent data) {
    stopProjection();
    startForeground(
        1,
        notification(),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
    projection = getSystemService(MediaProjectionManager.class).getMediaProjection(code, data);
    final MediaProjection session = projection;
    projection.registerCallback(
        new MediaProjection.Callback() {
          public void onStop() {
            main.post(
                () -> {
                  if (projection == session) stopProjection();
                });
          }

          public void onCapturedContentResize(int width, int height) {
            main.post(
                () -> {
                  if (projection == session && display != null) resizeCapture(width, height);
                });
          }
        },
        main);
    android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
    resizeCapture(metrics.widthPixels, metrics.heightPixels);
    display =
        projection.createVirtualDisplay(
            "LanLink",
            screenWidth,
            screenHeight,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.getSurface(),
            null,
            main);
    prefs.edit().putBoolean("allow_screen", true).apply();
    status = "屏幕共享已授权";
  }

  private void resizeCapture(int width, int height) {
    if (width < 1 || height < 1) return;
    float scale = Math.min(1f, 1280f / Math.max(width, height));
    int w = Math.max(2, Math.round(width * scale / 2) * 2),
        h = Math.max(2, Math.round(height * scale / 2) * 2);
    if (reader != null && w == screenWidth && h == screenHeight) return;
    if (videoHost != null) videoHost.close();
    if (videoEncoder != null) {
      videoEncoder.close();
      videoEncoder = null;
    }
    screenWidth = w;
    screenHeight = h;
    jpeg = null;
    ImageReader old = reader;
    reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
    reader.setOnImageAvailableListener(
        r -> {
          try (Image image = r.acquireLatestImage()) {
            if (image == null || System.currentTimeMillis() - lastFrame < 50) return;
            lastFrame = System.currentTimeMillis();
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int stride = plane.getRowStride() / plane.getPixelStride();
            Bitmap full = Bitmap.createBitmap(stride, h, Bitmap.Config.ARGB_8888);
            full.copyPixelsFromBuffer(buffer);
            Bitmap cropped = Bitmap.createBitmap(full, 0, 0, w, h);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            cropped.compress(Bitmap.CompressFormat.JPEG, 65, out);
            jpeg = out.toByteArray();
            if (cropped != full) cropped.recycle();
            full.recycle();
          } catch (Exception e) {
            status = "捕获失败：" + e.getMessage();
          }
        },
        captureHandler);
    if (display != null) {
      display.resize(w, h, getResources().getDisplayMetrics().densityDpi);
      display.setSurface(reader.getSurface());
    }
    if (old != null) old.close();
  }

  public void stopProjection() {
    if (videoHost != null) videoHost.close();
    if (videoEncoder != null) {
      videoEncoder.close();
      videoEncoder = null;
    }
    MediaProjection old = projection;
    projection = null;
    jpeg = null;
    if (display != null) {
      display.release();
      display = null;
    }
    if (reader != null) {
      reader.close();
      reader = null;
    }
    if (old != null) old.stop();
  }

  public void forgetDevice(String id) {
    JSONObject known = knownDevices();
    known.remove(id);
    prefs.edit().putString("known_devices", known.toString()).apply();
    revokeConsent(id);
  }

  public void groupChanged() {
    if (videoHost != null) videoHost.close();
    owner = null;
    releaseInput();
    releaseControl();
    synchronized (this) {
      outgoingViewer = null;
      screenViewers.clear();
    }
    offers.clear();
    offerExpiry.clear();
    clip = null;
    permissionNotice.reset();
    List<Wire> old = new ArrayList<>(sockets);
    main.postDelayed(
        () -> {
          for (Wire p : old) p.close();
        },
        100);
    status = "组成员已更新";
    announce();
  }

  public String controlledBy() {
    String id = owner;
    VideoHost stream = videoHost;
    if (id == null && stream != null && !stream.closed) id = stream.peer.id;
    if (id == null)
      for (Map.Entry<String, Long> viewer : screenViewers.entrySet())
        if (viewer.getValue() > System.currentTimeMillis()) {
          id = viewer.getKey();
          break;
        }
    Wire p = id == null ? null : peers.get(id);
    return p == null ? null : p.info.optString("name", "已配对设备");
  }

  public void stopInbound() {
    Set<String> ids = new HashSet<>();
    if (owner != null) ids.add(owner);
    if (videoHost != null && !videoHost.closed) ids.add(videoHost.peer.id);
    ids.addAll(screenViewers.keySet());
    screenViewers.clear();
    for (String id : ids) {
      pausedPeers.add(id);
      Wire p = peers.get(id);
      try {
        if (p != null) p.send(obj("type", "control-ended"));
      } catch (Exception ignored) {
      }
    }
    prefs.edit().putStringSet("paused_peers", new HashSet<>(pausedPeers)).apply();
    if (videoHost != null) videoHost.close();
    owner = null;
    releaseInput();
    status = "已结束远程控制";
  }

  public void onDestroy() {
    if (group != null) group.close();
    running = false;
    permissionNotice.reset();
    getSystemService(NotificationManager.class).cancel(2);
    if (pairing != null) pairing.close();
    releaseControl();
    owner = null;
    releaseInput();
    stopProjection();
    captureThread.quitSafely();
    clipboard.removePrimaryClipChangedListener(clipListener);
    for (Wire p : sockets) p.close();
    try {
      if (server != null) server.close();
    } catch (Exception ignored) {
    }
    if (discovery != null) discovery.close();
    if (multicast != null && multicast.isHeld()) multicast.release();
    if (shell != null) shell.close();
    clock.shutdownNow();
    jobs.shutdownNow();
    instance = null;
    super.onDestroy();
  }

  synchronized void requireScreen(Wire p) throws IOException {
    requireIncomingDirection();
    requireConsent(p, "screen");
    if (!allowed("screen") || projection == null) {
      needPermission(p, "screen");
      throw new IOException("PERMISSION_REQUIRED: 手机尚未授权本次屏幕共享，请在手机开启");
    }
  }

  VideoWire.Frame latestVideoFrame() {
    byte[] data = jpeg;
    return data == null ? null : new VideoWire.Frame(data, screenWidth, screenHeight, false, true);
  }

  VideoEncoder startVideoEncoder(VideoEncoder.Output output) throws Exception {
    return onMain(
        () -> {
          if (projection == null || display == null) throw new IOException("屏幕共享已结束");
          if (videoEncoder != null) videoEncoder.close();
          VideoEncoder encoder = new VideoEncoder(screenWidth, screenHeight, output);
          videoEncoder = encoder;
          display.setSurface(encoder.surface);
          return encoder;
        });
  }

  void stopVideoEncoder(VideoEncoder encoder) {
    main.post(
        () -> {
          if (videoEncoder == encoder) {
            if (display != null && reader != null && projection != null)
              display.setSurface(reader.getSurface());
            videoEncoder = null;
          }
          encoder.close();
        });
  }
}
