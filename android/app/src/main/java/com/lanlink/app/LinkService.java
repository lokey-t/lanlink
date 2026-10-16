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
  StagingFiles staging;
  public volatile JSONObject remoteCursor;
  public volatile String cursorPeer;
  public final ConcurrentHashMap<String, Wire> peers = new ConcurrentHashMap<>();
  final Set<AuxLink> auxLinks = ConcurrentHashMap.newKeySet();
  private final Set<Wire> sockets = ConcurrentHashMap.newKeySet();
  private final Set<String> connecting = ConcurrentHashMap.newKeySet();
  public final ExecutorService jobs = Executors.newFixedThreadPool(6);
  final ThreadPoolExecutor inputJobs =
      new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(128));
  private final ScheduledExecutorService clock = Executors.newScheduledThreadPool(2);
  private final Handler main = new Handler(Looper.getMainLooper());
  private SharedPreferences prefs;
  BackgroundRecovery backgroundRecovery;
  GroupKeys group;
  final GroupLayout groupLayout = new GroupLayout(this);
  private EdgeGlow edgeGlow;
  private final Set<String> pausedPeers = ConcurrentHashMap.newKeySet();
  public volatile String endedPeer;
  public volatile long endedAt;
  public String deviceId, name;
  final QuicManager quic=new QuicManager(this);
  public volatile String token;
  public volatile Pairing pairing;
  public volatile String status = "正在启动", active;
  private volatile String owner;
  private boolean ownerFallbackAccepted;
  private volatile boolean ownerPointer;
  private String ownerEdgeSession;
  private long ownerGlowSeq;
  private volatile long glowGeneration;
  private volatile boolean ownerNativePointer;
  private volatile RootCapture rootCapture;
  private long rootCaptureRetry;

  public boolean rootEnabled() {
    return prefs.getBoolean("root_mode", false);
  }

  public boolean adbRevoked() {
    return prefs.getBoolean("adb_revoked", false);
  }

  public boolean adbAuthorized() {
    return !adbRevoked()
        && getSharedPreferences("adb-helper", 0).getBoolean("adb_authorized", false);
  }

  public void enableAdbAuthorization() {
    prefs.edit().putBoolean("adb_revoked", false).putBoolean("allow_input", true).apply();
    status = "已允许电脑授权，请在电脑安卓授权页选择本机";
  }

  public synchronized void revokeAdbAuthorization() {
    prefs.edit().putBoolean("adb_revoked", true).apply();
    String previous = owner;
    owner = null;
    releaseInput();
    if (previous != null) {
      Wire peer = peers.get(previous);
      if (peer != null)
        try {
          peer.send(obj("type", "control-ended"));
        } catch (Exception ignored) {
        }
    }
    if (!rootEnabled()) stopProjection();
    shell.revokeAdb();
    dismissPermissionNotice();
    status = "已解除本应用的 ADB 授权";
  }

  public void rename(String value) throws IOException {
    value = value.trim();
    if (value.isEmpty() || value.length() > 40 || value.matches("(?s).*[\\p{Cntrl}].*"))
      throw new IOException("请输入 1–40 字的设备名称");
    if (!prefs.edit().putString("name", value).commit()) throw new IOException("设备名称保存失败");
    name = value;
    lastCapabilities = null;
  }

  public synchronized void setDevicePermission(String id, String key, boolean enabled)
      throws IOException {
    if (!authorizationDevices().has(id))
      throw new IOException("设备不在当前组中");
    String previous = prefs.getString("device_grants", "{}");
    String saved = consent.setPermission(id, key, enabled);
    if (!prefs.edit().putString("device_grants", saved).commit()) {
      consent = new DeviceConsent(previous);
      throw new IOException("权限保存失败");
    }
    if (enabled && !rootEnabled()) prefs.edit().putBoolean("allow_" + key, true).apply();
    if (!enabled && key.equals("input") && id.equals(owner)) {
      owner = null;
      releaseInput();
      Wire peer = peers.get(id);
      if (peer != null)
        try {
          peer.send(obj("type", "control-ended"));
        } catch (Exception ignored) {
        }
    }
    if (!enabled && key.equals("screen")) {
      screenViewers.remove(id);
      closeVideoHost(id);
    }
    if (!enabled && key.equals("clipboard") && clip != null && id.equals(clip.optString("source")))
      {clip=null;groupClipboard.prune();}
    PermissionNotice.Request guide = permissionNotice.peek(System.currentTimeMillis());
    if (!enabled || guide == null || !guide.explicit) permissionNotice.removePeer(id);
  }

  public boolean rootActive() {
    return rootEnabled() && shell != null && shell.rootReady();
  }

  private final java.util.concurrent.atomic.AtomicBoolean rootChanging =
      new java.util.concurrent.atomic.AtomicBoolean();

  public boolean rootModeChanging() {
    return rootChanging.get();
  }

  public void setRootMode(boolean enabled) throws Exception {
    if (!rootChanging.compareAndSet(false, true)) throw new IOException("权限模式正在切换，请稍候");
    try {
      if (enabled) {
        try {
          shell.root();
          if (projection != null || rootCapture != null) stopProjection();
          prefs.edit().putBoolean("root_mode", true).commit();
          RootPermissions.enable(this);
          status = "已 Root 授权";
          dismissPermissionNotice();
        } catch (Exception error) {
          shell.disableRoot();
          prefs.edit().putBoolean("root_mode", false).commit();
          throw error;
        }
      } else {
        owner = null;
        releaseInput();
        stopProjection();
        RootPermissions.restore(this);
        prefs.edit().putBoolean("root_mode", false).commit();
        shell.disableRoot();
        status = "Root 模式已关闭";
      }
    } finally {
      rootChanging.set(false);
    }
  }

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
  private final ProjectionIdle projectionIdle = new ProjectionIdle();
  private final Map<String, Long> discoveryReplies = new HashMap<>();
  private android.net.ConnectivityManager.NetworkCallback networkCallback;
  private VirtualDisplay display;
  private ImageReader reader;
  private final HandlerThread captureThread = new HandlerThread("lanlink-jpeg-capture");
  private Handler captureHandler;
  private final Map<String, VideoHost> videoHosts = new ConcurrentHashMap<>();
  private static final class VideoOpening {
    final Wire peer;
    final boolean h264;
    VideoOpening(Wire peer, boolean h264) { this.peer = peer; this.h264 = h264; }
  }
  private final Map<String, VideoOpening> openingVideoHosts = new ConcurrentHashMap<>();
  private volatile SharedVideoSource sharedVideoSource;
  private volatile boolean projectionJpegFallback;
  private volatile VideoEncoder videoEncoder;
  private volatile byte[] jpeg;
  private int screenWidth, screenHeight;
  private volatile long lastFrame;
  volatile JSONObject clip;
  private final Map<String, Map<String, Uri>> offers = new ConcurrentHashMap<>();
  final BulkTransfer bulk=new BulkTransfer(this);
  private final Map<String, Long> offerExpiry = new ConcurrentHashMap<>();
  private volatile boolean settingClipboard;final GroupClipboard groupClipboard=new GroupClipboard(this);
  private final java.util.concurrent.atomic.AtomicBoolean receiving =
      new java.util.concurrent.atomic.AtomicBoolean();
  private final ClipboardManager.OnPrimaryClipChangedListener clipListener =
      () -> {
        if (allowed("clipboard") && groupClipboard.upload())
          jobs.execute(
              () -> {
                try {
                  publishClipboard(false);
                } catch (Exception e) {
                  status = e.getMessage();
                }
              });
      };
  private ClipboardManager clipboard;
  public ShellChannel shell;
  final PermissionNotice permissionNotice = new PermissionNotice();
  DeviceConsent consent;
  final Map<String,JSONObject> peerAccess = new ConcurrentHashMap<>();
  final Set<String> accessPolling = ConcurrentHashMap.newKeySet();
  final Map<String,Long> accessRequested = new ConcurrentHashMap<>();
  private long lastAccessPoll;
  public volatile boolean uiVisible;

  public boolean privilegedScreenReady() {
    RootCapture capture = rootCapture;
    return shell != null && shell.ready() || capture != null && capture.running();
  }

  public boolean screenReady() {
    return projection != null || privilegedScreenReady();
  }

  public void dismissPermissionNotice() {
    permissionNotice.dismiss();
    getSystemService(NotificationManager.class).cancel(2);
  }

  public void clearResolvedPermissionNotice() {
    PermissionNotice.Request request = permissionNotice.peek(System.currentTimeMillis());
    if (request == null || request.explicit || request.needs.contains("consent")) return;
    for (String need : request.needs) {
      if (need.equals("adb") && !rootEnabled() && (adbRevoked() || !adbAuthorized())) return;
      if (need.equals("input")
          && (!allowed("input") || !shell.ready() || shell.lastInputError != null)) return;
      if (need.equals("screen") && (!allowed("screen") || !screenReady())) return;
      if (need.equals("clipboard") && !allowed("clipboard")) return;
    }
    dismissPermissionNotice();
  }

  private void needPermission(Wire peer, String need) {
    // Only an authenticated, currently connected peer can produce a local prompt.
    if (peers.get(peer.id) != peer) return;
    String label = peer.info.optString("name", Language.t("已配对设备"));
    label = label.replaceAll("[\\p{Cntrl}]", " ");
    if (label.length() > 60) label = label.substring(0, 60);
    final String device = label;
    if (!permissionNotice.offer(peer.id, device, need, System.currentTimeMillis())) return;
    status = device + (need.equals("consent") ? Language.t(" 请求首次控制，请在手机确认") : Language.t(" 请求协作，请在手机上开启所需权限"));
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
                  .setSmallIcon(R.drawable.lanlink_notification)
                  .setContentTitle(device + Language.t(" 请求协作"))
                  .setContentText(
                      need.equals("consent") ? Language.t("首次控制需要你同意，点击选择允许的功能") : Language.t("需要开启手机权限，点击查看并设置"))
                  .setStyle(
                      new Notification.BigTextStyle()
                          .bigText(
                              need.equals("consent")
                                  ? Language.t("首次控制需要你同意。点击选择允许的功能，同意后记住此设备。")
                                  : Language.t("点击查看缺少的权限和设置入口，已具备的权限会显示已授权。")))
                  .setContentIntent(target)
                  .setAutoCancel(true)
                  .setOnlyAlertOnce(true)
                  .setVisibility(Notification.VISIBILITY_PRIVATE)
                  .setTimeoutAfter(PermissionNotice.TTL)
                  .addAction(new Notification.Action.Builder(null, Language.t("查看权限"), target).build())
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
    if (rootModeChanging()) throw new IOException("INPUT_RECOVERING: 权限模式正在切换，请稍候");
    if (adbRevoked() && !rootEnabled())
      throw new IOException("CONSENT_DENIED: 手机已解除 ADB 授权，请在手机重新开启");
    if (!allowed("input") || !shell.ready() || shell.lastInputError != null) {
      if (allowed("input") && shell.recovering())
        throw new IOException("INPUT_RECOVERING: 输入助手正在重连，请稍候");
      needPermission(p, "input");
      throw new IOException(
          "PERMISSION_REQUIRED: "
              + (shell.lastInputError != null
                  ? shell.lastInputError
                  : "手机输入助手尚未就绪，请恢复 ADB 连接或开启 Root 模式"));
    }
  }

  private void prepareControl(Wire p, boolean screen, boolean showPointer) throws Exception {
    requireConsent(p, "input");
    if (!allowed("input")) requireInputPermission(p);
    if (shell.ready() && (!shell.inputVerified || shell.lastInputError != null)) {
      try {
        shell.request(obj("method", "probe"));
      } catch (Exception error) {
        if (!String.valueOf(error.getMessage()).startsWith("PERMISSION_REQUIRED:")) throw error;
      }
    }
    requireInputPermission(p);
    if (screen) requireScreen(p);
    if (showPointer && !shell.request(obj("method", "activatePointer")).optBoolean("nativePointer"))
      throw new IOException("系统虚拟键鼠暂不可用，请恢复 ADB / Root 助手后重试");
  }

  public long screenIdleDelay() {
    return prefs.getLong("screen_idle_delay", 60_000);
  }

  public void setScreenIdleDelay(long delay) {
    boolean valid = false;
    for (long option : ProjectionIdle.OPTIONS) if (option == delay) valid = true;
    if (!valid) return;
    prefs.edit().putLong("screen_idle_delay", delay).apply();
    main.post(this::updateProjectionIdle);
  }

  void updateProjectionIdle() {
    if (!running || (projection == null && rootCapture == null)) return;
    if (projectionIdle.expired(
        controlledBy() != null, SystemClock.elapsedRealtime(), screenIdleDelay())) {
      stopProjection();
      status = "桌面控制已结束，屏幕共享已按设置关闭";
    }
  }

  void screenSessionStarted() {
    main.post(() -> projectionIdle.expired(true, SystemClock.elapsedRealtime(), screenIdleDelay()));
  }

  void screenSessionEnded() {
    main.post(() -> {
      if (!hasVideoHosts()) projectionJpegFallback = false;
      updateProjectionIdle();
    });
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

  boolean canShareClipboard(String id) {
    return consent.allowed(id,"clipboard");
  }

  public void decideConsent(DeviceConsent.Request request, boolean accepted, JSONObject permissions)
      throws IOException {
    if (!peers.containsKey(request.id)) throw new IOException("设备已断开");
    String saved = consent.decide(request.id, request.requestId, accepted, permissions);
    SharedPreferences.Editor decision = prefs.edit().putString("device_grants", saved);
    if(accepted){pausedPeers.remove(request.id);decision.putStringSet("paused_peers",new HashSet<>(pausedPeers));}
    if (accepted && !rootEnabled())
      for (String k : new String[] {"input", "screen", "clipboard"})
        if (permissions.optBoolean(k)) decision.putBoolean("allow_" + k, true);
    if (!decision.commit()) {
      consent.reset();
      throw new IOException("设备授权保存失败，请重试");
    }
    dismissPermissionNotice();
  }

  public synchronized void revokeConsent(String id) {
    closeVideoHost(id);
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
    return (rootActive() && !key.equals("shutdown")) || prefs.getBoolean("allow_" + key, Arrays.asList(DeviceConsent.KEYS).contains(key));
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
    Language.initialize(this);
    captureThread.start();
    captureHandler = new Handler(captureThread.getLooper());
    instance = this;
    prefs = getSharedPreferences("identity", MODE_PRIVATE);
    backgroundRecovery = new BackgroundRecovery(this);
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
    createNotificationChannels();
    startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
    clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
    clipboard.addPrimaryClipChangedListener(clipListener);
    shell = new ShellChannel(this);
    staging=new StagingFiles(this);
    if (rootEnabled())
      jobs.execute(
          () -> {
            try {
              setRootMode(true);
            } catch (Exception e) {
              status = e.getMessage();
            }
          });
    running = true;
    jobs.execute(this::network);
    clock.scheduleAtFixedRate(this::tick, 1, 1, TimeUnit.SECONDS);
    networkCallback =
        new android.net.ConnectivityManager.NetworkCallback() {
          public void onAvailable(android.net.Network network) {
            clock.execute(LinkService.this::announce);
          }
        };
    getSystemService(android.net.ConnectivityManager.class)
        .registerDefaultNetworkCallback(networkCallback);
  }

  private String stageNotificationSignature="",stageNotificationToken="";
  void updateStageNotification(){
    main.post(()->{
      if(staging==null||!running)return;
      JSONArray pending=staging.pending();NotificationManager nm=getSystemService(NotificationManager.class);
      if(pending.length()==0){nm.cancel(17);stageNotificationSignature="";stageNotificationToken="";return;}
      JSONObject first=pending.optJSONObject(0),version=first.optJSONObject("preview");
      String token=version==null?first.optString("id"):version.optString("id");
      String signature=token+":"+pending.length();if(signature.equals(stageNotificationSignature))return;
      if(!token.equals(stageNotificationToken))nm.cancel(17);stageNotificationToken=token;stageNotificationSignature=signature;
      Intent open=new Intent(this,MainActivity.class).setAction("com.lanlink.app.FILES");
      Notification.Builder notice=new Notification.Builder(this,"lanlink-files")
        .setSmallIcon(R.drawable.lanlink_notification).setContentTitle(Language.t("收到文件")+" · "+first.optString("sourceName"))
        .setContentText(first.optJSONObject("offer").optString("name")+(pending.length()>1?" · "+pending.length()+" "+Language.t("文件"):""))
        .setContentIntent(PendingIntent.getActivity(this,17,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE))
        .setAutoCancel(false).setOnlyAlertOnce(true);
      for(boolean accept:new boolean[]{true,false}){
        String action=accept?"stage-accept":"stage-reject";
        Intent intent=new Intent(this,LinkService.class).setAction(action).setData(Uri.parse("lanlink://file-preview/"+token+"/"+action)).putExtra("token",token);
        notice.addAction(new Notification.Action.Builder(null,Language.t(accept?"接受":"拒绝"),PendingIntent.getService(this,17,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE)).build());
      }
      try{nm.notify(17,notice.build());}catch(SecurityException ignored){}
    });
  }
  private void createNotificationChannels() {
    getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("lanlink-files",Language.t("收到文件"),NotificationManager.IMPORTANCE_HIGH));
    NotificationManager nm = getSystemService(NotificationManager.class);
    nm.createNotificationChannel(
        new NotificationChannel("lanlink", Language.t("局域网协作"), NotificationManager.IMPORTANCE_LOW));
    nm.createNotificationChannel(
        new NotificationChannel(
            "lanlink-permissions", Language.t("协作权限提醒"), NotificationManager.IMPORTANCE_DEFAULT));
  }

  void refreshLanguage() {
    Language.refresh(this);
    createNotificationChannels();
    try { getSystemService(NotificationManager.class).notify(1, notification()); }
    catch (SecurityException ignored) { /* Language changes also work with notifications disabled. */ }
  }

  @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
    super.onConfigurationChanged(configuration);
    refreshLanguage();
  }

  private Notification notification() {
    String controller = controlledBy();
    Intent stop =
        new Intent(this, LinkService.class).setAction(controller == null ? "stop" : "stop-control");
    return new Notification.Builder(this, "lanlink")
        .setContentTitle(controller == null ? Language.t("LanLink 正在运行") : Language.t("正在被 ") + controller + Language.t(" 控制 / 共享屏幕"))
        .setContentText(controller == null ? Language.t("同组设备可使用你已开启的功能") : Language.t("可随时结束本次控制"))
        .setSmallIcon(R.drawable.lanlink_notification)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE))
        .addAction(
            new Notification.Action.Builder(
                    null,
                    controller == null ? Language.t("停止协作") : Language.t("结束控制"),
                    PendingIntent.getService(
                        this, controller == null ? 1 : 3, stop, PendingIntent.FLAG_IMMUTABLE))
                .build())
        .setOngoing(true)
        .build();
  }

  public int onStartCommand(Intent i, int flags, int startId) {
    if(i!=null&&("stage-accept".equals(i.getAction())||"stage-reject".equals(i.getAction()))){jobs.execute(()->{try{if(staging!=null)staging.handlePending(i.getStringExtra("token"),"stage-accept".equals(i.getAction()));}catch(Exception e){status=String.valueOf(e.getMessage());}updateStageNotification();});return START_NOT_STICKY;}
    if (i != null && "stop-control".equals(i.getAction())) {
      stopInbound();
      return START_NOT_STICKY;
    }
    if (i != null && "stop".equals(i.getAction())) {
      backgroundRecovery.stopped();
      stopSelf();
      return START_NOT_STICKY;
    }
    return START_NOT_STICKY;
  }

  public JSONObject capabilities() {
    android.util.DisplayMetrics m = displayMetrics();
    return obj(
        "backgroundSessionId", backgroundRecovery == null ? "" : backgroundRecovery.sessionId,
        "backgroundPolicy", backgroundRecovery == null ? JSONObject.NULL : backgroundRecovery.policy(),
        "backgroundRecovery", backgroundRecovery == null || backgroundRecovery.recovery() == null
            ? JSONObject.NULL : backgroundRecovery.recovery(),
        "staging",1,"quic",quic.enabled?1:0,"bulk",1,"rtcVideo",1,"dualLink",
        true,
        "controlPrepare",
        true,
        "input",
        shell != null && shell.ready(),
        "host",
        false,
        "screen",
        projection != null || privilegedScreenReady(),
        "videoH264Only",
        rootEnabled() || privilegedScreenReady(),
        "clipboard",
        true,
        "shutdown",
        false,
        "width",
        m.widthPixels,
        "height",
        m.heightPixels,
        "motionV2", quic!=null&&quic.nativeEngine!=null&&quic.nativeEngine.binaryMotion?2:0,
        "inputMode",
        shell != null && shell.ready() ? "adb/root" : "unavailable",
        "inputWarning",
        shell != null && shell.ready() ? "" : "请在电脑端启用 ADB 键鼠授权，或启动 Root 助手");
  }

  android.util.DisplayMetrics displayMetrics() {
    android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
    getSystemService(android.view.WindowManager.class).getDefaultDisplay().getRealMetrics(metrics);
    return metrics;
  }

  private JSONObject identity() {
    JSONObject caps = capabilities();
    if (group != null)
      try {
        caps.put("memberKey", group.own().getString("key"));
      } catch (Exception ignored) {
      }
    return groupLayout.attach(obj("id", deviceId, "name", name, "capabilities", caps));
  }

  public JSONObject knownDevices() {
    try {
      return new JSONObject(prefs.getString("known_devices", "{}"));
    } catch (JSONException e) {
      return new JSONObject();
    }
  }

  public JSONObject authorizationDevices() {
    JSONObject result = new JSONObject();
    if (group == null || group.isDissolved()) return result;
    // Membership is enough: a device need not have connected to this phone yet.
    JSONObject[] sources = {group.deviceLabels(), knownDevices()};
    for (JSONObject source : sources) for (Iterator<String> it = source.keys(); it.hasNext();) {
      String id = it.next();
      if (id.equals(deviceId) || group.excluded(id)) continue;
      try { result.put(id, source.getJSONObject(id)); } catch (JSONException ignored) {}
    }
    for (Wire peer : peers.values()) {
      if (peer.id.equals(deviceId) || group.excluded(peer.id)) continue;
      try { result.put(peer.id, peer.info); } catch (JSONException ignored) {}
    }
    return result;
  }

  private synchronized void rememberDevice(Wire p) {
    if (peers.get(p.id) != p) return;
    JSONObject known = knownDevices();
    // Bound storage from device-supplied metadata, and retain no account secrets here.
    if (!known.has(p.id) && known.length() >= 32) known.remove(known.keys().next());
    String label = p.info.optString("name", "已配对设备").replaceAll("[\\p{Cntrl}]", " ");
    if (label.length() > 60) label = label.substring(0, 60);
    try {
      JSONObject capabilities = p.info.optJSONObject("capabilities");
      known.put(p.id, obj("name", label, "platform", p.info.optString("platform", "设备"),
          "capabilities", obj("relativeInput", capabilities != null && capabilities.optBoolean("relativeInput"))));
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
      if (meta.getString("id").equals(expectedGroup)) { connect(result.getString("host"), result.getInt("port")); return; }
      if (group.established()) {
        if (!dissolve) throw new IOException("组已发生变化，请重新配对以合并设备组");
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
    clip = null;groupClipboard.clear();
    status = "已更新组，等待自动连接";
  }

  private void network() {
    try {
      server = new ServerSocket(45831);
      group = new GroupKeys(this, prefs);
      JSONObject pendingMerge = new JSONObject(prefs.getString("pending_group_merge", "{}"));
      if (pendingMerge.has("group")) {
        group.rememberMigrations(pendingMerge.optJSONObject("migrations"));
        group.adopt(pendingMerge.getJSONObject("group"), pendingMerge.getString("token"));
        for (int i = 0; i < group.data.getJSONArray("removed").length(); i++) forgetDevice(group.data.getJSONArray("removed").getString(i));
        prefs.edit().remove("pending_group_merge").commit();
      }
      groupLayout.reconcile();
      JSONObject pendingJoin = new JSONObject(prefs.getString("pending_group_join", "{}"));
      if (group.isDissolved()
          || group.dissolvedGroups().has(pendingJoin.optString("previousGroup"))) {
        if (pendingJoin.has("group"))
          join(pendingJoin.getString("token"), pendingJoin.getJSONObject("group"));
        else join(Wire.token(), null);
      }
      prefs.edit().remove("pending_group_join").commit();
      group.start();
      quic.start();
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
      clock.scheduleAtFixedRate(this::announce, 0, 1, TimeUnit.SECONDS);
      clock.schedule(this::announce, 300, TimeUnit.MILLISECONDS);
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
          if (!id.equals(deviceId)
              && b.optInt("v") == 1
              && id.matches("[a-f0-9-]{36}")
              && Math.abs(System.currentTimeMillis() - time) < 30000
              && System.currentTimeMillis() - discoveryReplies.getOrDefault(id, 0L) > 5000) {
            if (discoveryReplies.size() > 128) discoveryReplies.clear();
            discoveryReplies.put(id, System.currentTimeMillis());
            announce();
          }
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
    if (!p.matchesGroupToken(token)) { p.close(); return; }
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
    backgroundRecovery.connected(true);
    status = "已连接同组设备";
    try{groupClipboard.inventory(p);JSONObject current=groupClipboard.latest();if(current!=null && canShareClipboard(p.id) && deviceId.equals(current.optString("source")))p.send(obj("type","clipboard","offer",current));}catch(Exception ignored){}

  }

  void offerInitialConsent(Wire peer) {
    if (peers.get(peer.id) != peer || consent.hasGrant(peer.id) || pausedPeers.contains(peer.id))
      return;
    try {
      consent.require(peer.id, peer.info.optString("name", "同组设备"), "input");
    } catch (IOException error) {
      if (error.getMessage().startsWith("CONSENT_PENDING:")) needPermission(peer, "consent");
    }
  }

  public void closed(Wire p) {
    for (AuxLink link : auxLinks) if (link.primary == p) link.close();
    VideoHost stream = p.id == null ? null : videoHosts.get(p.id);
    if (stream != null && stream.peer == p && videoHosts.remove(p.id, stream)) stream.close();
    VideoOpening opening = p.id == null ? null : openingVideoHosts.get(p.id);
    if (opening != null && opening.peer == p) openingVideoHosts.remove(p.id, opening);
    sockets.remove(p);
    boolean removed;
    synchronized (this) {
      removed = p.id != null && peers.remove(p.id, p);
      if (removed) {
        if (backgroundRecovery != null) backgroundRecovery.connected(!peers.isEmpty());
        screenViewers.remove(p.id);
        endViewing(p.id);
        if (p.id.equals(outgoingPending)) outgoingCancelled = true;
        if (p.id.equals(owner)) {
          owner = null;
          releaseInput();
        }
        if (p.id.equals(active)) active = null;
      }
    }
    if (removed) {
      if (staging != null) staging.disconnected(p.id);
      permissionNotice.removePeer(p.id);
      consent.disconnect(p.id);
      if (permissionNotice.peek(System.currentTimeMillis()) == null)
        getSystemService(NotificationManager.class).cancel(2);
    }
  }

  void auxInputLost(Wire p) {
    try {
      inputJobs.execute(() -> {
        try {
          synchronized (LinkService.this) {
            if (peers.get(p.id) == p && p.fastPath == null && p.id.equals(owner))
              shell.request(obj("method", "release"));
          }
        } catch (Exception ignored) {}
      });
    } catch (RejectedExecutionException ignored) {}
  }

  public void message(Wire p, JSONObject m) { message(p, m, p); }

  void message(Wire p, JSONObject m, Wire reply) {
    if (p.id == null || peers.get(p.id) != p || !p.connected || !p.matchesGroupToken(token)) return;
    String type = m.optString("type");
    try {
      if(type.equals("edge-glow")){inboundEdge(p,m,false);return;}
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
      if (type.equals("layout-update") || type.equals("master-selection")) {
        if (peers.get(p.id) != p || !p.connected) return;
        if (type.equals("layout-update")) groupLayout.applyLayout(m.optJSONObject("layout"));
        else groupLayout.applyMaster(m.optJSONObject("choice"));
        return;
      }
      if (type.equals("group-removed")) {
        join(Wire.token(), null);
        status = "本设备已移出组，需要重新配对";
        return;
      }
      if (type.equals("control-ended")) {
        // Android only initiates manual viewing/touchpad sessions. A tagged
        // desktop edge-session end cannot belong to those sessions.
        if (m.has("session")) return;
        synchronized (this) {
          endedPeer = p.id;
          endedAt = System.currentTimeMillis();
          // A remote end may arrive before acquire resolves; cancel the pending lease too.
          if (p.id.equals(active) || p.id.equals(outgoingPending)) releaseControl();
        }
        return;
      }
      if (type.equals("identity")) {
        boolean firstIdentity = !p.info.has("name");
        p.info = m;
        rememberDevice(p);
        jobs.execute(()->quic.connect(p));
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
        // Consent is requested for an operation, never simply for joining the group.
        groupLayout.receive(m);
        return;
      }
      if (type.equals("ping")) {
        if (peers.get(p.id) != p || !p.connected) return;
        groupLayout.noteVersion(p, m);
        groupLayout.receive(m);
        p.send(groupLayout.heartbeat(p, "pong"));
        return;
      }
      if (type.equals("pong")) { if (peers.get(p.id) == p && p.connected) { groupLayout.noteVersion(p, m); groupLayout.receive(m); } return; }
      if(type.equals("clipboard")){groupClipboard.receive(p,m.getJSONObject("offer"));return;}
      if(type.equals("clipboard-inventory")){groupClipboard.mergeInventory(p,m.optJSONArray("ids"));return;}
      if(type.equals("clipboard-record")){groupClipboard.record(p,m.getJSONObject("record"));return;}
      if (type.equals("request")) {
        String operation = m.optString("method");
        ExecutorService executor =
            (AuxLink.CONTROL.contains(operation) || operation.equals("prepare"))
                ? inputJobs
                : jobs;
        if (executor instanceof ThreadPoolExecutor
            && ((ThreadPoolExecutor) executor).getQueue().size() > 100) {
          p.close();
          return;
        }
        executor.execute(
            () -> {
              try {
                if (!reply.connected || peers.get(p.id) != p) return;
                reply.send(
                    obj(
                        "type",
                        "response",
                        "id",
                        m.optString("id"),
                        "result",
                        handle(p, m.optString("method"), m.optJSONObject("args"))));
              } catch (Exception e) {
                try {
                  reply.send(
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

  void allow(String k) throws Exception {
    if (!allowed(k)) throw new IOException("安卓端未允许此功能：" + k);
  }

  synchronized void requireOwner(Wire p) throws Exception {
    groupLayout.requireMaster(p.id, p.id.equals(owner) && ownerPointer);
    requireInputPermission(p);
    if (!p.id.equals(owner) || ownerUntil < System.currentTimeMillis())
      throw new IOException("请先取得控制权");
    ownerUntil = System.currentTimeMillis() + 4000;
  }

  JSONObject handle(Wire p, String method, JSONObject a) throws Exception {
    if (p.id == null || peers.get(p.id) != p || !p.matchesGroupToken(token)) throw new IOException("设备连接已结束");
    if (group != null && group.excluded(p.id)) throw new IOException("设备已移出组");
    if (method.equals("permission-guide-open") || method.equals("permission-toggle")) {
      String key = a.optString("permission");
      if (!Arrays.asList(DeviceConsent.KEYS).contains(key)) throw new IOException("此设备不支持该功能");
      if (method.equals("permission-toggle") && !(a.opt("enabled") instanceof Boolean)) throw new IOException("权限开关无效");
      if (method.equals("permission-guide-open") || a.optBoolean("enabled") && (!allowed(key) || !consent.allowed(p.id, key))) {
        needPermission(p, key);
        permissionNotice.openGuide(p.id, p.info.optString("name", "设备"), key, System.currentTimeMillis());
        main.post(() -> { Intent intent = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP); intent.putExtra("permission", true); try { startActivity(intent); } catch (Exception ignored) {} });
        return obj("opened", true, "guideRequired", method.equals("permission-toggle"));
      }
      if (!(a.opt("enabled") instanceof Boolean)) throw new IOException("权限开关无效");
      setDevicePermission(p.id, key, a.optBoolean("enabled"));
      return obj("message", "权限已保存");
    }
    if(method.equals("quicOpen"))return quic.offer(p);
    if(method.equals("stage-cancel"))return staging.remoteStopped(p,a.optString("id"),false,"");
    if(method.equals("stage-failed"))return staging.remoteStopped(p,a.optString("id"),true,a.optString("error","接收失败，请重试"));
    if(method.equals("stage-complete"))return staging.complete(p,a.optString("id"));
    if(method.equals("stage-preview"))return staging.applyPreview(p,a.optJSONObject("preview"));
    if(method.equals("stage-offer"))return staging.accept(p,a);
    if(method.equals("stage-retract"))return staging.retract(p,a.optString("session"));
    if(method.equals("stage-status")){requireClipboardPermission(p);return staging.available(p,a.optString("id"));}
    if(method.equals("stage-begin")){requireClipboardPermission(p);return staging.begin(p,a.optString("id"),a.optBoolean("resume"));}
    if (method.equals("adb-permission-request")) {
      if (peers.get(p.id) != p) throw new IOException("设备连接已结束");
      if (!rootEnabled() && adbRevoked()) needPermission(p, "adb");
      return obj();
    }
    if (method.equals("permission-request")) {
      if (peers.get(p.id) != p) throw new IOException("设备连接已结束");
      JSONArray requested = a == null ? null : a.optJSONArray("permissions");
      boolean ready = true;
      if (requested == null || requested.length() == 0) {
        for (String key : DeviceConsent.KEYS) ready &= consent.allowed(p.id, key);
      } else {
        for (int i = 0; i < requested.length(); i++) ready &= consent.allowed(p.id, requested.optString(i));
      }
      if (ready) return obj("ready", true);
      consent.request(p.id, p.info.optString("name", "组内设备"));
      needPermission(p, "consent"); return obj();
    }
    if(method.equals("permission-cancel")){consent.disconnect(p.id);permissionNotice.removePeer(p.id);return obj();}
    if (method.equals("permission-prepare")) {
      JSONArray keys=a.optJSONArray("permissions");
      if(keys!=null)for(int i=0;i<keys.length();i++) {
        String key=keys.optString(i);
        if(Arrays.asList(DeviceConsent.KEYS).contains(key) && (!allowed(key) || key.equals("input") && !shell.ready() || key.equals("screen") && !screenReady())) needPermission(p,key);
      }
      return obj();
    }
    if (method.equals("permission-status")) {
      if (peers.get(p.id) != p) throw new IOException("设备连接已结束");
      JSONObject entries = obj();
      boolean inputReady = shell != null && shell.healthy() && shell.lastInputError == null && (rootEnabled() || adbAuthorized());
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
                consent.allowed(p.id, key)));
      return obj(
          "version",
          1,
          "requestRejected", consent.rejected(p.id),
          "requestPending", consent.pending(p.id),
          "permissions",
          entries,
          "engine",
          obj(
              "ready",
              inputReady,
              "persistent",
              true,
              "root",
              rootEnabled(),
              "revoked",
              adbRevoked(),
              "authorized",
              adbAuthorized(),
              "recovering",
              shell != null && shell.recovering(),
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
    if (method.equals("linkOpen")) {
      if (peers.get(p.id) != p || !p.connected || auxLinks.size() >= 8)
        throw new IOException("辅助通道暂不可用");
      AuxLink link = new AuxLink(this, p, identity());
      auxLinks.add(link);
      return link.info();
    }
    if (method.equals("linkPing")) return obj();
    if (method.equals("inputReset")) {
      requireOwner(p);
      shell.request(obj("method", "release"));
      return obj();
    }
    if (method.equals("videoOpen")) {
      VideoOpening ticket = new VideoOpening(p, a != null && a.optBoolean("h264"));
      VideoHost previous;
      synchronized (this) {
        requireScreen(p);
        videoHosts.values().removeIf(stream -> stream.closed);
        if (!privilegedScreenReady()) {
          screenViewers.values().removeIf(expires -> expires <= System.currentTimeMillis());
          if (ticket.h264) for (String viewer : screenViewers.keySet())
            if (!viewer.equals(p.id)) requireVideoCodec(false, true);
          for (VideoHost existing : videoHosts.values())
            if (existing.peer != p) requireVideoCodec(existing.h264Requested, ticket.h264);
          for (VideoOpening existing : openingVideoHosts.values())
            if (existing.peer != p) requireVideoCodec(existing.h264, ticket.h264);
        }
        if (!videoHosts.containsKey(p.id) && !openingVideoHosts.containsKey(p.id)
            && videoHosts.size() + openingVideoHosts.size() >= 16) throw new IOException("屏幕查看设备已达上限");
        previous = videoHosts.remove(p.id);
        screenViewers.remove(p.id);
        openingVideoHosts.put(p.id, ticket);
      }
      if (previous != null) previous.close();
      VideoHost stream = null;
      try {
        // RTC negotiation may take seconds; never hold the input/screen service monitor here.
        stream = new VideoHost(this, p, ticket.h264, a == null ? null : a.optString("rtc", null), a == null ? 0 : a.optLong("quic"));
        synchronized (this) {
          if (openingVideoHosts.get(p.id) != ticket || peers.get(p.id) != p)
            throw new IOException("CONTROL_ENDED: 屏幕请求已取消或设备连接已改变");
          requireScreen(p);
          videoHosts.put(p.id, stream);
          openingVideoHosts.remove(p.id, ticket);
        }
        return stream.info();
      } catch (Exception error) {
        if (stream != null) stream.close();
        throw error;
      } finally {
        openingVideoHosts.remove(p.id, ticket);
      }
    }
    if (method.equals("videoKeep") || method.equals("videoStop") || method.equals("videoKey")) {
      VideoHost stream = videoHosts.get(p.id);
      if (stream == null || stream.peer != p || a == null || !stream.id.equals(a.optString("id")))
        throw new IOException("串流已结束");
      if (method.equals("videoKeep")) return stream.keep(p, a.optJSONObject("feedback"));
      else if(method.equals("videoKey"))return stream.keep(p,obj("needKey",true));
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
        boolean ready = shell.healthy() && shell.lastInputError == null;
        return obj("ready", ready, "inputMode", capabilities().optString("inputMode"));
      case "prepare":
      case "acquire":
        synchronized (this) {
          requireIncomingDirection();
          groupLayout.requireMaster(p.id, a.optBoolean("pointer", !a.optBoolean("screen")));
          if (owner != null && !owner.equals(p.id) && ownerUntil > System.currentTimeMillis())
            throw new IOException("CONTROL_CONFLICT: 本设备正在被另一台设备控制");
        }
        if (a.optBoolean("newSession") && pausedPeers.remove(p.id)) {
          // Ending a session blocks retries, but does not revoke the saved grant.
          prefs.edit().putStringSet("paused_peers", new HashSet<>(pausedPeers)).apply();
        }
        prepareControl(p, a.optBoolean("screen"), a.optBoolean("pointer", !a.optBoolean("screen")));
        if (method.equals("prepare")) return obj("ready", true);
        synchronized (this) {
          if (peers.get(p.id) != p) throw new IOException("设备连接已结束");
          requireIncomingDirection();
          groupLayout.requireMaster(p.id, a.optBoolean("pointer", !a.optBoolean("screen")));
          if (owner != null && !owner.equals(p.id) && ownerUntil > System.currentTimeMillis())
            throw new IOException("CONTROL_CONFLICT: 本设备正在被另一台设备控制");
          boolean pointerMode = a.optBoolean("pointer", !a.optBoolean("screen"));
          if (!p.id.equals(owner) || ownerPointer != pointerMode)
            shell.request(obj("method", "release"));
          owner = p.id;
          ownerFallbackAccepted = a.optBoolean("fallbackAccepted");
          ownerPointer = pointerMode;
          ownerEdgeSession=a.optString("edgeSession",null);if(ownerEdgeSession!=null && ownerEdgeSession.length()!=36)ownerEdgeSession=null;ownerGlowSeq=0;
          ownerNativePointer = false;
          ownerUntil = System.currentTimeMillis() + 4000;
        }
        if (ownerPointer) {
          try {
            ownerNativePointer =
                shell.request(obj("method", "activatePointer")).optBoolean("nativePointer");
          } catch (Exception ignored) {
            ownerNativePointer = false;
          }
          if (!ownerNativePointer) {
            synchronized (this) {
              if (p.id.equals(owner)) owner = null;
            }
            releaseInput();
            throw new IOException("系统虚拟键鼠暂不可用，请恢复 ADB / Root 助手后重试");
          }
        }
        main.post(
            () -> {
              if (p.id.equals(owner)) {
                projectionIdle.expired(true, SystemClock.elapsedRealtime(), screenIdleDelay());
              }
            });
        clearEdgeGlow();
        return capabilities();
      case "release":
        synchronized (this) {
          if (peers.get(p.id) != p) throw new IOException("设备连接已结束");
          if (p.id.equals(owner)) {
            boolean departing=a.has("edgeExit") && inboundEdge(p,a.optJSONObject("edgeExit"),true);
            owner = null;
            releaseInput(departing);
          }
        }
        return obj();
      case "keepControl":
        requireOwner(p);
        if (shell.ready()) {
          if (ownerPointer) {
            boolean nativePointer =
                shell.request(obj("method", "activatePointer")).optBoolean("nativePointer");
            ownerNativePointer = nativePointer;
          } else shell.request(obj("method", "heartbeat"));
        }
        return obj();
      case "input":
        requireOwner(p);
        try {
          input(p, a);
        } catch (Exception error) {
          if (String.valueOf(error.getMessage()).startsWith("PERMISSION_REQUIRED:"))
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
        if (privilegedScreenReady()) throw new IOException("ADB / Root 投屏使用 H.264 串流，请升级控制端并打开远程窗口");
        synchronized (this) {
          requireScreen(p);
          if (videoEncoder != null) throw new IOException("VIDEO_H264_REQUIRED: 当前屏幕正在使用 H.264 串流，请更新控制端并打开远程窗口");
          screenViewers.put(p.id, System.currentTimeMillis() + 4000);
        }
        VideoWire.Frame frame = latestVideoFrame();
        if (frame == null) throw new IOException("手机正在准备屏幕画面，请稍后重试");
        return obj(
            "mime",
            "image/jpeg",
            "data",
            Base64.getEncoder().encodeToString(frame.data),
            "width",
            frame.width,
            "height",
            frame.height);
      case "clipboard-sync":
        if(!allowed("clipboard") || !canShareClipboard(p.id))throw new IOException("剪贴板共享未授权");groupClipboard.mergeInventory(p,a.optJSONArray("ids"));return obj("latest",groupClipboard.latest());
      case "clipboardGet":
        requireClipboardPermission(p);
        return snapshotClipboard();
      case "fileChunk":
        requireClipboardPermission(p);
        byte[] fileData=fileBytes(a,p.id);return obj("data",Base64.getEncoder().encodeToString(fileData),"sha256",Wire.hex(Wire.sha(fileData)));
      case "bulkOpen":
        requireClipboardPermission(p);
        return bulk.open(p,a);
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

  void requireClipboardPermission(Wire p) throws IOException {
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

  synchronized void motionInputs(Wire peer,java.util.List<JSONObject> inputs)throws Exception{
    if(inputs.size()<2||!shell.supportsInputBatch()){for(JSONObject a:inputs)handle(peer,"input",a);return;}
    requireOwner(peer);if(!shell.ready())throw new IOException("INPUT_RECOVERING: 输入助手正在恢复连接");
    if(inputs.size()>64)throw new IOException("批量输入过多");org.json.JSONArray batch=new org.json.JSONArray();
    for(JSONObject a:inputs){String kind=a.optString("kind");if(!kind.equals("move")&&!kind.equals("relative"))throw new IOException("无效批量输入");batch.put(a);}
    try{shell.request(obj("method","inputBatch","inputs",batch,"pointer",ownerPointer));}
    catch(Exception error){if(shell.lastInputError!=null)needPermission(peer,"input");throw error;}
  }
  private synchronized void input(Wire peer, JSONObject a) throws Exception {
    requireOwner(peer);
    if (!shell.ready()) throw new IOException("INPUT_RECOVERING: 输入助手正在恢复连接");
    shell.request(obj("method", "input", "args", a, "pointer", ownerPointer));
  }

  private synchronized void clearEdgeGlow(){long generation=++glowGeneration;main.post(()->{if(generation==glowGeneration){if(edgeGlow!=null)edgeGlow.close();edgeGlow=null;}});}
  private synchronized boolean inboundEdge(Wire p,JSONObject m,boolean exiting){
    if(m==null || peers.get(p.id)!=p || !p.id.equals(owner) || ownerUntil<System.currentTimeMillis() || !ownerPointer || !allowed("input") || !consent.allowed(p.id,"input") || ownerEdgeSession==null || !ownerEdgeSession.equals(m.optString("session")))return false;
    long seq=m.optLong("seq");String phase=m.optString("phase"),edge=m.optString("edge");double progress=m.optDouble("progress",0);
    if(seq<=ownerGlowSeq || seq>9007199254740991L || !Arrays.asList("left","right","top","bottom").contains(edge) || !(exiting?phase.equals("depart"):phase.equals("countdown")||phase.equals("cancel")) || phase.equals("countdown")&&(!Double.isFinite(progress)||progress<0||progress>1))return false;
    ownerGlowSeq=seq;long generation=++glowGeneration;main.post(()->{if(generation==glowGeneration)edgeGlow=EdgeGlow.feedback(this,edgeGlow,phase,edge,progress);});return true;
  }
  private void releaseInput(){releaseInput(false);}
  private void releaseInput(boolean preserveGlow) {
    if(!preserveGlow)clearEdgeGlow();
    ownerNativePointer = false;
    main.post(this::updateProjectionIdle);
    touched = false;
    if (shell != null && shell.ready())
      jobs.execute(
          () -> {
            try {
              synchronized (LinkService.this) {
                if (owner == null) shell.request(obj("method", "release"));
              }
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
        || hasVideoHosts()
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
    if (adbRevoked() && !rootEnabled()) throw new IOException("CONSENT_DENIED: 手机已解除 ADB 授权，请在手机重新开启");
    // Preparing a local helper does not grant this peer input or screen access.
    // The computer still needs trusted USB debugging, and acquire enforces consent.
    if (peers.get(peer.id) != peer || group.excluded(peer.id)) throw new IOException("设备连接已结束");
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
    control(id, newSession, fallbackAccepted, true);
  }

  /** A touchpad only needs native input permission; it never opens a screen session. */
  public void controlTouchpad(String id, boolean newSession) throws Exception {
    JSONObject capabilities = peer(id).info.optJSONObject("capabilities");
    if (capabilities == null || !capabilities.optBoolean("relativeInput"))
      throw new IOException("此设备不支持原生触控板控制");
    control(id, newSession, false, false);
  }

  private void control(String id, boolean newSession, boolean fallbackAccepted, boolean screen) throws Exception {
    Wire target = peer(id);
    String[] permissions = screen ? new String[]{"input", "screen"} : new String[]{"input"};
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
      JSONObject report=target.request("permission-status",obj());peerAccess.put(id,report);
      JSONObject rows=report.optJSONObject("permissions");
      if(rows!=null)for(String key:permissions){JSONObject row=rows.optJSONObject(key);if(row!=null && (!row.optBoolean("appEnabled") || !row.optBoolean("systemGranted"))){target.request("permission-prepare",obj("permissions",new JSONArray(Arrays.asList(permissions))));throw new IOException("PERMISSION_REQUIRED: 设备未就绪，请在被控设备完成权限设置");}}
      if(!PeerAccess.allowed(report,permissions)) {
        if(!"有限授权".equals(PeerAccess.status(report,target.info.optString("platform"))) && System.currentTimeMillis()-accessRequested.getOrDefault(id,0L)>120000){target.request("permission-request",obj("permissions",new JSONArray(Arrays.asList(permissions))));accessRequested.put(id,System.currentTimeMillis());}
        throw new IOException("有限授权".equals(PeerAccess.status(report,target.info.optString("platform")))?"CONSENT_DENIED: 此功能未授权，请在设备页申请授权":"CONSENT_PENDING: 请在被控设备允许本设备控制");
      }
      JSONObject capability = target.info.optJSONObject("capabilities");
      if (capability != null && capability.optBoolean("controlPrepare"))
        target.request("prepare", obj("newSession", newSession, "screen", screen));
      JSONObject caps =
          target.request(
              "acquire",
              obj("newSession", newSession, "fallbackAccepted", fallbackAccepted, "screen", screen));
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
      if (!QuicNative.isBusy(error) && target.equals(active)) releaseControl();
      throw error;
    }
  }

  public void pasteRemote() throws Exception {
    if (active == null) throw new IOException("请先开始控制");
    if (clip == null) clip = snapshotClipboard();
    peer(active).request("paste", obj("offer", clip));
  }

  private void tick() {
    if(staging!=null)staging.poll();
    groupClipboard.poll();for(Wire peer:peers.values())try{groupClipboard.inventory(peer);}catch(Exception ignored){}
    if (!running) return;
    if (backgroundRecovery != null) backgroundRecovery.sample(false);
    main.post(this::updateProjectionIdle);
    if(System.currentTimeMillis()-lastAccessPoll>3000){lastAccessPoll=System.currentTimeMillis();for(Wire p:peers.values())if(accessPolling.add(p.id))jobs.execute(()->{try{JSONObject report=p.request("permission-status",obj());if(peers.get(p.id)==p)peerAccess.put(p.id,report);}catch(Exception ignored){peerAccess.remove(p.id);}finally{accessPolling.remove(p.id);}});}
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
          else p.send(groupLayout.heartbeat(p, "ping"));
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
                if (!QuicNative.isBusy(e) && id.equals(active)) active = null;
              }
            });
      }
      synchronized (this) {
        if (owner != null && ownerUntil < System.currentTimeMillis()) {
          owner = null;
          releaseInput();
        }
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
          if(c==null){if(shell!=null && shell.ready()){JSONObject read=shell.request(obj("method","clipboard-get"));if(read.optBoolean("exists"))return obj("id",UUID.randomUUID().toString(),"text",read.optString("text"),"files",new JSONArray(),"source",deviceId);}throw new IOException("暂时无法读取剪贴板，请打开 LanLink 或恢复 ADB / Root 助手");}
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
          JSONObject previous = groupClipboard.reusable(c.toString(), files);
          if (previous != null) { clip = previous; return previous; }
          String id = UUID.randomUUID().toString();
          JSONObject offer = obj("id", id, "text", text, "files", files, "source", deviceId,"_fingerprint",c.toString());
          validateOffer(offer);
          if (files.length() > 0 && offers.size() >= 20) {
            String first = offers.keySet().iterator().next();
            offers.remove(first);
            offerExpiry.remove(first);
          }
          if (files.length() > 0) { offers.put(id, uris); offerExpiry.put(id, System.currentTimeMillis() + 3600000); }
          clip = offer;
          return offer;
        });
  }

  boolean clipboardOfferAvailable(String id){return offers.containsKey(id)&&offerExpiry.getOrDefault(id,0L)>System.currentTimeMillis();}
  public void publishClipboard() throws Exception {publishClipboard(true);}
  public void publishClipboard(boolean manual) throws Exception {allow("clipboard");groupClipboard.publish(snapshotClipboard(),manual);}
  void writeClipboardText(String text)throws Exception{
    groupClipboard.applied(text);
    if(shell!=null && shell.ready())try{shell.request(obj("method","clipboard-set","text",text));return;}catch(Exception ignored){}
    onMain(()->{settingClipboard=true;try{clipboard.setPrimaryClip(ClipData.newPlainText("LanLink",text));}finally{main.postDelayed(()->settingClipboard=false,300);}return true;});
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
    byte[] data=fileBytes(a);return obj("data",Base64.getEncoder().encodeToString(data),"sha256",Wire.hex(Wire.sha(data)));
  }
  byte[] fileBytes(JSONObject a) throws Exception {
    return fileBytes(a,null);
  }
  byte[] fileBytes(JSONObject a,String requester) throws Exception {
    if(staging!=null&&staging.owns(a.getString("offerId")))return staging.bytes(a,requester);
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
      byte[] data = new byte[fileChunkLimit(a)];
      int n = 0, k;
      while (n < data.length && (k = in.read(data, n, data.length - n)) != -1) n += k;
      data = Arrays.copyOf(data, n);
      return data;
    }
  }

  static int fileChunkLimit(JSONObject args) throws IOException {
    if (!args.has("maxBytes")) return 192 * 1024;
    Object value = args.opt("maxBytes");
    if (!(value instanceof Number)) throw new IOException("文件块大小无效");
    double number = ((Number) value).doubleValue();
    if (!Double.isFinite(number) || number != Math.floor(number) || number < 1 || number > 192 * 1024)
      throw new IOException("文件块大小无效");
    return (int) number;
  }

  long bulkRate(){long rate=outgoingViewer!=null?128*1024:owner!=null||active!=null?1024*1024:32L*1024*1024;for(VideoHost stream:videoHosts.values())if(!stream.closed)rate=Math.min(rate,Math.max(8192,Math.min(128*1024,stream.adaptive.rate*15L/800)));return rate;}

  public void receiveCurrent() throws Exception {
    if (clip == null) throw new IOException("没有共享内容");
    receive(clip);
  }

  void receive(JSONObject offer) throws Exception {receive(offer,()->true);}
  void receive(JSONObject offer,java.util.function.BooleanSupplier current) throws Exception {
    allow("clipboard");
    validateOffer(offer);
    if (!receiving.compareAndSet(false, true)) throw new IOException("正在接收文件，请稍后重试");
    JSONArray files = offer.getJSONArray("files");
    List<File> written = new ArrayList<>();
    try {
      if(offer.getJSONArray("files").length()==0){if(current.getAsBoolean())writeClipboardText(offer.getString("text"));return;}
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
          BulkTransfer.Reader bulkReader=null;
          long bulkBudget=0,bulkBudgetAt=0;
          int bulkRetries=0;
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
              Wire source=offer.optString("source").equals(deviceId)?null:peer(offer.getString("source"));
              if(source!=null&&source.quicConn!=0&&source.fastPath==null){
                long budget=bulkRate(),started=SystemClock.elapsedRealtime();
                args.put("maxBytes",Math.min(192*1024,Math.max(256,budget/10))).put("maxRate",budget).put("end",size);
                try{byte[] bytes=quic.file(source,args);requireClipboardPermission(source);if(bytes.length==0||bytes.length>192*1024||offset+bytes.length>size)throw new IOException("文件校验失败");out.write(bytes);offset+=bytes.length;long wait=Math.min(250,bytes.length*1000L/Math.max(1,Math.min(budget,bulkRate()))-(SystemClock.elapsedRealtime()-started));if(wait>0&&offset<size)Thread.sleep(wait);continue;}
                catch(Exception error){String reason=String.valueOf(error.getMessage());if(reason.contains("校验")||reason.contains("授权")||reason.contains("过期"))throw error;quic.drop(source);}
              }
              long budget=bulkRate();
              if(bulkReader!=null&&(budget<bulkBudget*0.85 || budget>bulkBudget*1.5&&SystemClock.elapsedRealtime()-bulkBudgetAt>1500)){bulkReader.close();bulkReader=null;}
              if(source!=null && source.info.optJSONObject("capabilities")!=null && source.info.optJSONObject("capabilities").optInt("bulk")==1 && bulkReader==null){bulkReader=BulkTransfer.connect(source,source.request("bulkOpen",new JSONObject(args.toString()).put("end",size).put("maxRate",budget).put("completeAck",true)));bulkBudget=budget;bulkBudgetAt=SystemClock.elapsedRealtime();}
              JSONObject chunk =bulkReader!=null?null:
                  offer.optString("source").equals(deviceId)
                      ? fileChunk(args)
                      : peer(offer.getString("source")).request("fileChunk", args);
              byte[] b;
              try{b=bulkReader!=null?bulkReader.next():Base64.getDecoder().decode(chunk.getString("data"));}
              catch(java.io.EOFException|java.net.SocketException|java.net.SocketTimeoutException interrupted){if(bulkReader==null||bulkRetries++>=2)throw interrupted;bulkReader.close();bulkReader=null;Thread.sleep(500);continue;}
              if (b.length == 0
                  || b.length > 192 * 1024
                  || offset + b.length > size
                  || chunk!=null && !Wire.hex(Wire.sha(b)).equals(chunk.getString("sha256")))
                throw new IOException("文件校验失败");
              out.write(b);
              offset += b.length;
              status =
                  "正在接收 " + f.getString("name") + " " + (offset * 100 / Math.max(1, size)) + "%";
            }
          }finally{if(bulkReader!=null){try{if(offset==size)bulkReader.complete(size);}finally{bulkReader.close();}}}
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
              if(current.getAsBoolean()){groupClipboard.appliedFiles(result.toString());clipboard.setPrimaryClip(result);}
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
    projectionIdle.reset();
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
    android.util.DisplayMetrics metrics = displayMetrics();
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
    closeVideoHosts();
    closeSharedVideoSource();
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
    MediaProjection old = projection;
    projection = null;
    RootCapture capture = rootCapture;
    rootCapture = null;
    if (capture != null) capture.close();
    projectionIdle.reset();
    closeVideoHosts();
    closeSharedVideoSource();
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
    peers.clear(); // Old-key traffic is no longer authorized while its update reply drains.
    try { groupLayout.reconcile(); } catch (IOException error) { status = error.getMessage(); }
    if(staging!=null)try{staging.clearExports();}catch(Exception ignored){}
    quic.reset();
    closeVideoHosts();
    owner = null;
    releaseInput();
    releaseControl();
    synchronized (this) {
      outgoingViewer = null;
      screenViewers.clear();
    }
    offers.clear();
    offerExpiry.clear();
    clip = null;groupClipboard.clear();
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
    if (id == null) for (VideoHost stream : videoHosts.values()) if (!stream.closed) { id = stream.peer.id; break; }
    if (id == null)
      for (Map.Entry<String, Long> viewer : screenViewers.entrySet())
        if (viewer.getValue() > System.currentTimeMillis()) {
          id = viewer.getKey();
          break;
        }
    Wire p = id == null ? null : peers.get(id);
    return p == null ? null : p.info.optString("name", "已配对设备");
  }

  void masterSelectionChanged() {
    try { inputJobs.execute(() -> {
      synchronized (LinkService.this) {
        JSONObject selected = groupLayout.master();
        if (selected == null || owner == null || !ownerPointer || owner.equals(selected.optString("id"))) return;
        Wire previous = peers.get(owner); String session = ownerEdgeSession;
        owner = null; ownerEdgeSession = null; releaseInput();
        if (previous != null) try {
          JSONObject message = obj("type", "control-ended");
          if (session != null) message.put("session", session);
          previous.send(message);
        } catch (Exception ignored) {}
      }
    }); } catch (RejectedExecutionException ignored) {}
  }

  public void stopInbound() {
    Set<String> ids = new HashSet<>();
    if (owner != null) ids.add(owner);
    ids.addAll(openingVideoHosts.keySet());
    for (VideoHost stream : videoHosts.values()) if (!stream.closed) ids.add(stream.peer.id);
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
    closeVideoHosts();
    owner = null;
    releaseInput();
    status = "已结束桌面控制";
  }

  public void onDestroy() {
    if (backgroundRecovery != null) backgroundRecovery.stopped();
    if(staging!=null)staging.close();
    getSystemService(NotificationManager.class).cancel(17);
    quic.close();
    bulk.close();
    if(edgeGlow!=null)edgeGlow.close();
    inputJobs.shutdownNow();
    if (group != null) group.close();
    running = false;
    if (networkCallback != null)
      getSystemService(android.net.ConnectivityManager.class)
          .unregisterNetworkCallback(networkCallback);
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
    if (allowed("screen") && privilegedScreenReady()) {
      return;
    }
    if (rootEnabled() || allowed("screen") && shell.recovering()) {
      // A recovering input helper is not a missing MediaProjection grant.
      if (rootCapture != null && rootCapture.running()) return;
      throw new IOException("INPUT_RECOVERING: ADB / Root 助手正在恢复，屏幕连接将自动重试");
    }
    if (!allowed("screen") || projection == null) {
      needPermission(p, "screen");
      throw new IOException("PERMISSION_REQUIRED: 手机尚未授权本次屏幕共享，请在手机开启");
    }
  }

  VideoWire.Frame latestVideoFrame() {
    byte[] data = jpeg;
    return data == null ? null : new VideoWire.Frame(data, screenWidth, screenHeight, false, true);
  }

  private boolean hasVideoHosts() {
    if (!openingVideoHosts.isEmpty()) return true;
    for (VideoHost stream : videoHosts.values()) if (!stream.closed) return true;
    return false;
  }

  private void closeVideoHost(String id) {
    VideoHost stream;
    synchronized (this) {
      openingVideoHosts.remove(id);
      stream = videoHosts.remove(id);
    }
    if (stream != null) stream.close();
  }

  private void closeVideoHosts() {
    List<VideoHost> streams;
    synchronized (this) {
      openingVideoHosts.clear();
      streams = new ArrayList<>(videoHosts.values());
      videoHosts.clear();
    }
    for (VideoHost stream : streams) stream.close();
  }

  private static void requireVideoCodec(boolean existingH264, boolean requestedH264) throws IOException {
    if (existingH264 == requestedH264) return;
    throw new IOException(requestedH264
        ? "VIDEO_CODEC_CONFLICT: 当前设备正在使用兼容画面共享，请先结束该查看会话再启动 H.264 串流"
        : "VIDEO_H264_REQUIRED: 当前设备正在共享 H.264 画面，请更新控制端并启用 H.264 解码");
  }

  private void closeSharedVideoSource() {
    SharedVideoSource source = sharedVideoSource;
    sharedVideoSource = null;
    if (source != null) source.close();
    videoEncoder = null;
    projectionJpegFallback = false;
  }

  private void releaseIdleVideoSource() {
    main.post(() -> {
      SharedVideoSource source = sharedVideoSource;
      if (source == null || !source.empty()) return;
      if (videoEncoder != null && display != null && reader != null && projection != null)
        display.setSurface(reader.getSurface());
      closeSharedVideoSource();
    });
  }

  VideoSource startVideoEncoder(VideoEncoder.Output output) throws Exception {
    return onMain(() -> {
      SharedVideoSource existing = sharedVideoSource;
      if (existing != null && existing.running()) return existing.attach(output);
      SharedVideoSource shared = new SharedVideoSource(this::releaseIdleVideoSource);
      try {
        if (privilegedScreenReady()) {
          if (rootCapture == null || !rootCapture.running()) {
            if (SystemClock.elapsedRealtime() < rootCaptureRetry)
              throw new IOException("Root 串流正在恢复，请稍后重试");
            rootCaptureRetry = SystemClock.elapsedRealtime() + 2000;
            rootCapture = new RootCapture(this);
          }
          shared.bind(rootCapture.attach(shared));
        } else {
          if (projection == null || display == null) throw new IOException("屏幕共享已结束");
          if (projectionJpegFallback) throw new IOException("当前查看会话正在使用兼容画面共享");
          VideoEncoder encoder = new VideoEncoder(screenWidth, screenHeight, shared);
          shared.bind(encoder);
          videoEncoder = encoder;
          display.setSurface(encoder.surface);
        }
        sharedVideoSource = shared;
        return shared.attach(output);
      } catch (Exception error) {
        // Keep every current projection viewer on JPEG after an encoder failure;
        // a later viewer must not switch its surface away from existing readers.
        if (!privilegedScreenReady() && videoEncoder == null) projectionJpegFallback = true;
        shared.close();
        if (sharedVideoSource == shared) sharedVideoSource = null;
        throw error;
      }
    });
  }

  void stopVideoEncoder(VideoSource encoder) {
    // Each stream owns only its subscription; the last one releases the shared source.
    encoder.close();
  }
}
