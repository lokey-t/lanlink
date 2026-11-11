package com.lanlink.app;

import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AppUpdater {
  private static final String[] FEEDS = {
      "https://api.github.com/repos/lokey-t/lanlink/releases/latest",
      "https://gitee.com/api/v5/repos/lokey-t/lanlink/releases/latest"};
  private static final String WEBSITE = "https://lanlink.sites.show/#get";
  private static final long MAX_APK = 512L * 1024 * 1024;
  private static final long CHECK_INTERVAL = 6 * 60 * 60 * 1000L;
  private static final String TAG = "LanLinkUpdater";

  enum Phase { IDLE, CHECKING, DOWNLOADING, VERIFYING, READY, INSTALLING, FAILED }

  private static AppUpdater instance;
  static AppUpdater get(Context context) {
    if (instance == null) instance = new AppUpdater(context.getApplicationContext());
    return instance;
  }

  private final Context app;
  private final SharedPreferences updates;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final File directory;
  private final File apk;
  private MainActivity activity;
  private AlertDialog offer;
  private Runnable observer;
  private Intent confirmation;
  private Release available;
  private boolean showOfferOnResume;
  private boolean manualOffer;
  private final long[] lastCheckAttempt = new long[2];
  private final Runnable checkAgain = this::automatic;
  private final Runnable remindAgain = this::showOffer;
  private boolean retryDownload;
  private boolean websiteFallback;
  private boolean migrationRequired;
  private Phase phase = Phase.IDLE;
  private String status = "";
  private long received, total;
  private double speed;

  private AppUpdater(Context app) {
    this.app = app;
    updates = app.getSharedPreferences("updates", Context.MODE_PRIVATE);
    directory = new File(app.getFilesDir(), "updates");
    apk = new File(directory, "lanlink-update.apk");
    try {
      long target = updates.getLong("versionCode", 0);
      if (target > installed().getLongVersionCode() && apk.isFile()) {
        setStatus(Phase.READY, "更新已下载");
      } else if (target != 0) {
        discardDownload();
        if (target == installed().getLongVersionCode()) status = "更新已安装";
      }
    } catch (Exception e) { Log.w(TAG, "Reading saved update", e); }
  }

  private SharedPreferences preferences() { return app.getSharedPreferences("identity", Context.MODE_PRIVATE); }
  boolean beta() { return preferences().getBoolean("update_beta", false); }
  private String checkTimeKey(boolean beta) { return beta ? "beta_update_check_time" : "update_check_time"; }
  private boolean working() {
    return phase == Phase.CHECKING || phase == Phase.DOWNLOADING || phase == Phase.VERIFYING || phase == Phase.INSTALLING;
  }

  void attach(MainActivity current) {
    activity = current;
    if (phase == Phase.READY && updates.getBoolean("awaitingInstallPermission", false)) {
      updates.edit().remove("awaitingInstallPermission").apply();
      if (app.getPackageManager().canRequestPackageInstalls()) install();
      else setStatus(Phase.READY, "尚未允许安装未知应用");
    }
    if (confirmation != null) showConfirmation();
    if (showOfferOnResume) showOffer();
    changed();
  }

  void detach(MainActivity current) {
    if (activity != current) return;
    activity = null;
    main.removeCallbacks(checkAgain);
    main.removeCallbacks(remindAgain);
    if (offer != null) {
      offer.dismiss();
      offer = null;
    }
  }

  void observe(Runnable observer) {
    this.observer = observer;
    changed();
  }
  void unobserve(Runnable observer) { if (this.observer == observer) this.observer = null; }
  private void changed() { if (observer != null) observer.run(); }
  Phase phase() { return phase; }
  boolean websiteFallback() { return websiteFallback; }
  boolean migrationRequired() { return migrationRequired; }
  boolean hasReleasePage() { return !WEBSITE.equals(downloadPage()); }
  private String downloadPage() { return available == null ? updates.getString("page", WEBSITE) : available.page; }
  boolean canCheck() { return !working() && phase != Phase.READY; }
  boolean canRetryDownload() { return retryDownload && available != null; }
  boolean canInstall() { return phase == Phase.READY || (phase == Phase.INSTALLING && confirmation != null); }
  long received() { return received; }
  long total() { return total; }
  String status() {
    String text = Language.t(status);
    if ((phase == Phase.FAILED || phase == Phase.IDLE) && available != null) return available.version() + " · " + text;
    if ((phase != Phase.DOWNLOADING && phase != Phase.VERIFYING) || total == 0) return text;
    String size = String.format(Locale.getDefault(), "%s · %d%% · %.1f / %.1f MiB", text,
        received * 100 / total, received / 1048576.0, total / 1048576.0);
    if (phase == Phase.VERIFYING) return size;
    return size + String.format(Locale.getDefault(), " · %.2f MiB/s", speed / 1048576.0);
  }

  private void setStatus(Phase phase, String status) {
    this.phase = phase;
    this.status = status;
    changed();
  }

  void automatic() {
    main.removeCallbacks(checkAgain);
    if (activity == null || !preferences().getBoolean("auto_check_updates", true)) return;
    boolean channel = beta();
    long next = Math.max(lastCheckAttempt[channel ? 1 : 0], preferences().getLong(checkTimeKey(channel), 0)) + CHECK_INTERVAL;
    long delay = next - System.currentTimeMillis();
    if (delay <= 0 && canCheck() && offer == null) check(false);
    else main.postDelayed(checkAgain, delay > 0 ? delay : 60000);
  }

  void chooseChannel(Runnable onChanged) {
    if (activity == null) return;
    if (working() || phase == Phase.READY) {
      String hint = phase == Phase.READY ? "请安装或放弃已下载的更新后再切换通道" :
          phase == Phase.CHECKING ? "正在检查更新，请稍后切换通道" :
          phase == Phase.INSTALLING ? "正在安装更新，请稍后切换通道" :
          "正在下载或校验更新，请稍后切换通道";
      activity.showOperationError(hint);
      return;
    }
    new AlertDialog.Builder(activity).setTitle(Language.t("更新通道"))
        .setSingleChoiceItems(new String[] {Language.t("正式版"), Language.t("测试版")}, beta() ? 1 : 0,
            (dialog, which) -> {
              if (offer != null) offer.dismiss();
              available = null;
              showOfferOnResume = websiteFallback = retryDownload = migrationRequired = false;
              preferences().edit().putBoolean("update_beta", which == 1).apply();
              setStatus(Phase.IDLE, "");
              dialog.dismiss();
              onChanged.run();
            }).setNegativeButton(Language.t("取消"), null).show();
  }

  private static HttpURLConnection connect(String address, int connectTimeout, int readTimeout) throws IOException {
    URL url = new URL(address);
    // Follow HTTPS redirects explicitly so a mirror cannot downgrade a download to HTTP.
    for (int redirects = 0; redirects < 6; redirects++) {
      if (!"https".equals(url.getProtocol())) throw new IOException("HTTPS required");
      HttpURLConnection connection = (HttpURLConnection) url.openConnection();
      connection.setInstanceFollowRedirects(false);
      connection.setConnectTimeout(connectTimeout);
      connection.setReadTimeout(readTimeout);
      connection.setRequestProperty("User-Agent", "LanLink-Updater");
      try {
        int code = connection.getResponseCode();
        if (code == 200) return connection;
        String location = connection.getHeaderField("Location");
        if (code < 300 || code >= 400 || location == null) throw new IOException("HTTP " + code);
        url = new URL(url, location);
      } catch (IOException e) {
        connection.disconnect();
        throw e;
      }
      connection.disconnect();
    }
    throw new IOException("Too many redirects");
  }

  private static String metadata(String url, int limit) throws Exception {
    HttpURLConnection connection = connect(url, 8000, 8000);
    try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      for (int n; (n = in.read(buffer)) != -1;) {
        if (n > limit - out.size()) throw new IOException("Release metadata too large");
        out.write(buffer, 0, n);
      }
      return out.toString(StandardCharsets.UTF_8.name());
    } finally { connection.disconnect(); }
  }

  static boolean newer(String a, String b) {
    if (a == null || b == null || !a.matches("\\d{1,9}\\.\\d{1,9}\\.\\d{1,9}") ||
        !b.matches("\\d{1,9}\\.\\d{1,9}\\.\\d{1,9}")) return false;
    String[] x = a.split("\\."), y = b.split("\\.");
    for (int i = 0; i < 3; i++) {
      int n = Long.compare(Long.parseLong(x[i]), Long.parseLong(y[i]));
      if (n != 0) return n > 0;
    }
    return false;
  }

  private static final class Release {
    final JSONObject manifest;
    final List<JSONObject> mirrors = new ArrayList<>();
    final String page;
    String notes = "";
    Release(JSONObject manifest, String feed, String tag) {
      this.manifest = manifest;
      page = "https://" + (feed.contains("gitee.com") ? "gitee.com" : "github.com") +
          "/lokey-t/lanlink/releases/tag/" + tag;
    }
    String version() { return manifest.optString("version"); }
    JSONObject target() {
      JSONObject targets = manifest.optJSONObject("targets");
      return targets == null ? null : targets.optJSONObject("android");
    }
    JSONObject full() {
      JSONObject android = target();
      return android == null ? null : android.optJSONObject("full");
    }
    boolean hasApk() {
      JSONObject full = full();
      if (!validAsset(full, ".apk")) return false;
      for (JSONObject mirror : mirrors) {
        JSONArray assets = mirror.optJSONArray("assets");
        if (assets == null) continue;
        for (int i = 0; i < assets.length(); i++) {
          JSONObject asset = assets.optJSONObject(i);
          if (asset != null && full.optString("name").equals(asset.optString("name")) &&
              asset.optString("browser_download_url").startsWith("https://")) return true;
        }
      }
      return false;
    }
  }

  private static boolean validAsset(JSONObject spec, String suffix) {
    return spec != null && spec.optString("name").matches("[a-zA-Z0-9][a-zA-Z0-9._-]*") &&
        spec.optString("name").endsWith(suffix) && spec.optString("sha256").matches("[a-f0-9]{64}") &&
        spec.optLong("size") > 0 && spec.optLong("size") <= MAX_APK;
  }

  private Release findRelease(boolean beta) throws Exception {
    Release selected = null;
    Release latestListed = null;
    Map<String, String> notesByVersion = new HashMap<>();
    boolean failed = false;
    for (String feed : FEEDS) {
      try {
        String url = beta ? feed.replace("/latest", "?per_page=30") : feed;
        String response = metadata(url, 8 * 1024 * 1024);
        JSONArray candidates = beta ? new JSONArray(response) : new JSONArray().put(new JSONObject(response));
        for (int i = 0; i < candidates.length(); i++) {
          JSONObject entry = candidates.getJSONObject(i);
          String tag = entry.getString("tag_name");
          String version = tag.replaceFirst("^v", "");
          if (entry.optBoolean("draft") || entry.optBoolean("prerelease") != beta ||
              !version.matches("\\d{1,9}\\.\\d{1,9}\\.\\d{1,9}")) continue;
          Object body = entry.opt("body");
          if (body instanceof String) {
            String notes = ((String) body).trim();
            if (!notes.isEmpty()) notesByVersion.putIfAbsent(version, notes);
          }
          if (latestListed == null || newer(version, latestListed.version()))
            latestListed = new Release(new JSONObject().put("version", version), feed, tag);
          if (selected != null && version.equals(selected.version())) {
            selected.mirrors.add(entry);
            break;
          }
          if (selected != null && !newer(version, selected.version())) continue;
          JSONArray assets = entry.getJSONArray("assets");
          for (int j = 0; j < assets.length(); j++) {
            JSONObject asset = assets.getJSONObject(j);
            if (!"lanlink-update.json".equals(asset.optString("name"))) continue;
            JSONObject manifest = new JSONObject(metadata(asset.getString("browser_download_url"), 2 * 1024 * 1024));
            if (manifest.optInt("format") != 1 || !version.equals(manifest.optString("version"))) continue;
            selected = new Release(manifest, feed, tag);
            selected.mirrors.add(entry);
            break;
          }
        }
      } catch (Exception e) {
        failed = true;
        Log.w(TAG, "Reading release mirror", e);
      }
    }
    if (selected == null) selected = latestListed;
    if (selected == null) throw new UpdateFailure(failed ? "无法连接更新服务器，请稍后重试" :
        beta ? "暂无可用的测试版发行" : "暂无可用的正式版发行");
    selected.notes = notesByVersion.getOrDefault(selected.version(), "");
    return selected;
  }

  void check(boolean manual) {
    if (phase == Phase.READY) {
      return;
    }
    if (working() || offer != null) return;
    boolean channel = beta();
    lastCheckAttempt[channel ? 1 : 0] = System.currentTimeMillis();
    main.removeCallbacks(remindAgain);
    available = null;
    showOfferOnResume = websiteFallback = retryDownload = migrationRequired = false;
    setStatus(Phase.CHECKING, "正在检查更新");
    automatic();
    worker.execute(() -> {
      try {
        Release release = findRelease(channel);
        String current = installed().versionName;
        preferences().edit().putLong(checkTimeKey(channel), System.currentTimeMillis()).apply();
        main.post(() -> {
          if (!newer(release.version(), current)) {
            setStatus(Phase.IDLE, newer(current, release.version()) ? "当前版本高于所选通道的发行版" : "已是最新版本");
            return;
          }
          available = release;
          websiteFallback = !release.hasApk();
          setStatus(websiteFallback ? Phase.FAILED : Phase.IDLE,
              websiteFallback ? "此版本暂未提供适用于本机的安装包" : "发现新版本");
          manualOffer = manual;
          showOfferOnResume = true;
          showOffer();
        });
      } catch (Exception e) {
        Log.w(TAG, "Checking for updates", e);
        main.post(() -> {
          websiteFallback = manual;
          setStatus(Phase.FAILED, e instanceof UpdateFailure ? e.getMessage() : "无法读取更新信息，请稍后重试");
        });
      }
    });
  }

  boolean hasOffer() { return phase == Phase.IDLE && available != null; }

  private String reminderKey(Release release) {
    return (beta() ? "beta_update_reminder_" : "stable_update_reminder_") + release.version();
  }

  private void deferOffer(String key, boolean skip) {
    preferences().edit().putLong(key, skip ? -1 : System.currentTimeMillis() + CHECK_INTERVAL).apply();
    manualOffer = false;
  }

  void showOffer() {
    if (!showOfferOnResume || activity == null || available == null || offer != null || working()) return;
    if (!manualOffer && !preferences().getBoolean("auto_check_updates", true)) return;
    String key = reminderKey(available);
    long until = preferences().getLong(key, 0);
    if (until == -1) return;
    long delay = until - System.currentTimeMillis();
    if (!manualOffer && delay > 0) {
      main.removeCallbacks(remindAgain);
      main.postDelayed(remindAgain, delay);
      return;
    }
    String title = Language.t("发现新版本") + " " + available.version() + " · " + Language.t(beta() ? "测试版" : "正式版");
    String notes = available.notes;
    String details = notes.isEmpty() ? Language.t("此版本暂无更新说明") : notes;
    if (websiteFallback) details = Language.t("点击现在更新可打开此版本发布页。") + "\n\n" + details;
    AlertDialog dialog = new AlertDialog.Builder(activity)
        .setTitle(title)
        .setMessage(details)
        .setPositiveButton(Language.t("现在更新"), (d,w) -> {
          if (websiteFallback) { deferOffer(key, false); openWebsite(); }
          else download();
        })
        .setNegativeButton(Language.t("稍后提醒我"), (d,w) -> deferOffer(key, false))
        .setNeutralButton(Language.t("本版本不再提示"), (d,w) -> deferOffer(key, true))
        .setOnCancelListener(d -> deferOffer(key, false))
        .create();
    offer = dialog;
    dialog.setOnDismissListener(d -> {
      if (offer == dialog) { offer = null; showOffer(); }
    });
    dialog.show();
  }

  void download() {
    if (working() || available == null) return;
    showOfferOnResume = false;
    main.removeCallbacks(remindAgain);
    if (activity != null && observer == null) activity.showAbout();
    Release release = available;
    websiteFallback = retryDownload = migrationRequired = false;
    received = total = 0;
    speed = 0;
    setStatus(Phase.DOWNLOADING, "正在准备更新");
    worker.execute(() -> {
      File partial = new File(directory, "lanlink-update.part.apk");
      try {
        Files.createDirectories(directory.toPath());
        JSONObject full = release.full();
        PackageInfo candidate = downloadApk(release, full, partial);
        Files.move(partial.toPath(), apk.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        if (!updates.edit().putLong("versionCode", candidate.getLongVersionCode())
            .putLong("size", full.getLong("size")).putString("sha256", full.getString("sha256"))
            .putString("page", release.page).commit())
          throw new IOException("无法保存更新状态");
        main.post(() -> {
          setStatus(Phase.READY, "更新已下载");
          if (activity != null) new AlertDialog.Builder(activity).setMessage(Language.t(status))
              .setPositiveButton(Language.t("立即安装"), (d,w) -> install())
              .setNegativeButton(Language.t("稍后"), null).show();
        });
      } catch (Exception e) {
        Log.w(TAG, "Downloading or validating APK", e);
        main.post(() -> {
          websiteFallback = true;
          migrationRequired = e instanceof SigningMismatch;
          retryDownload = !(e instanceof IncompatibleApk);
          setStatus(Phase.FAILED, updateError(e, "无法写入更新目录，请手动安装"));
        });
      } finally {
        if (partial.exists() && !partial.delete()) Log.w(TAG, "Could not remove partial APK");
      }
    });
  }

  private PackageInfo downloadApk(Release release, JSONObject full, File partial) throws Exception {
    PackageInfo current = installed();
    JSONObject target = release.target();
    JSONObject delta = target.optJSONObject("delta");
    long size = full.getLong("size");
    File patch = new File(directory, "lanlink-update.part.gz");
    Files.deleteIfExists(patch.toPath());
    Files.deleteIfExists(partial.toPath());
    if (target.optString("deltaFrom").equals(current.versionName) && validAsset(delta, ".delta.apk.gz") &&
        delta.getLong("size") < size && directory.getUsableSpace() >= size + delta.getLong("size")) {
      try {
        fetchAsset(release, delta, patch, true);
        ApkDelta.apply(patch, new File(current.applicationInfo.sourceDir), partial, size, full.getString("sha256"));
        return validateApk(partial, release.version());
      } catch (Exception e) {
        Log.w(TAG, "Incremental update failed; trying the full APK", e);
        Files.deleteIfExists(partial.toPath());
      } finally {
        if (patch.exists() && !patch.delete()) Log.w(TAG, "Could not remove APK delta");
      }
    }
    if (directory.getUsableSpace() < size) throw new UpdateFailure("存储空间不足，释放空间后重试");
    fetchAsset(release, full, partial, false);
    return validateApk(partial, release.version());
  }

  private void fetchAsset(Release release, JSONObject spec, File partial, boolean incremental) throws Exception {
    String name = spec.getString("name"), sha = spec.getString("sha256");
    long size = spec.getLong("size");
    Exception failure = new UpdateFailure("当前下载源缺少安装包");
    HashSet<String> seen = new HashSet<>();
    for (JSONObject mirror : release.mirrors) {
      JSONArray assets = mirror.getJSONArray("assets");
      for (int i = 0; i < assets.length(); i++) {
        JSONObject asset = assets.getJSONObject(i);
        if (!name.equals(asset.optString("name"))) continue;
        String url = asset.getString("browser_download_url");
        if (!seen.add(url)) continue;
        try {
          boolean alternate = seen.size() > 1;
          report(Phase.DOWNLOADING, incremental, alternate, 0, size, 0);
          HttpURLConnection connection = connect(url, 10000, 30000);
          try (InputStream in = connection.getInputStream(); OutputStream out = openPartial(partial)) {
            String actual = copyApk(in, out, size, (count, rate) ->
                report(Phase.DOWNLOADING, incremental, alternate, count, size, rate));
            report(Phase.VERIFYING, incremental, false, size, size, 0);
            if (!actual.equals(sha)) throw new UpdateFailure("安装包校验失败，请重试");
            return;
          } finally { connection.disconnect(); }
        } catch (Exception e) {
          if (e instanceof WriteFailure) throw e;
          failure = e instanceof UpdateFailure ? e :
              new UpdateFailure(e.getMessage() != null && e.getMessage().equals("HTTP 404") ?
                  "当前下载源缺少安装包" : "下载连接中断，请重试", e);
        }
      }
    }
    throw failure;
  }

  private interface Progress { void report(long count, double rate); }

  private static OutputStream openPartial(File partial) throws WriteFailure {
    try { return new FileOutputStream(partial); }
    catch (IOException e) { throw new WriteFailure(e); }
  }

  private static String copyApk(InputStream in, OutputStream out, long size, Progress progress) throws Exception {
    MessageDigest hash = MessageDigest.getInstance("SHA-256");
    byte[] buffer = new byte[64 * 1024];
    long count = 0, lastCount = 0, lastTime = SystemClock.elapsedRealtime();
    double speed = 0;
    for (int n; (n = in.read(buffer)) != -1;) {
      if (n > size - count) throw new UpdateFailure("安装包校验失败，请重试");
      try { out.write(buffer, 0, n); }
      catch (IOException e) { throw new WriteFailure(e); }
      hash.update(buffer, 0, n);
      count += n;
      long now = SystemClock.elapsedRealtime(), elapsed = now - lastTime;
      if (progress != null && elapsed >= 500) {
        double rate = (count - lastCount) * 1000.0 / elapsed;
        speed = speed == 0 ? rate : speed * 0.65 + rate * 0.35;
        progress.report(count, speed);
        lastCount = count;
        lastTime = now;
      }
    }
    if (count != size) throw new UpdateFailure("安装包下载不完整，请重试");
    return ApkDelta.hex(hash.digest());
  }

  private void report(Phase phase, boolean incremental, boolean alternate, long count, long size, double rate) {
    main.post(() -> {
      received = count;
      total = size;
      speed = rate;
      setStatus(phase, phase == Phase.VERIFYING ? "正在校验" :
          alternate ? "正在从备用下载源下载" : incremental ? "正在下载增量包" : "正在下载全量包");
    });
  }

  private PackageInfo installed() throws PackageManager.NameNotFoundException {
    return app.getPackageManager().getPackageInfo(app.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
  }

  private static class UpdateFailure extends IOException {
    UpdateFailure(String message) { super(message); }
    UpdateFailure(String message, Throwable cause) { super(message, cause); }
  }
  private static final class WriteFailure extends IOException {
    WriteFailure(IOException cause) { super(cause); }
  }
  private static class IncompatibleApk extends UpdateFailure {
    IncompatibleApk(String message) { super(message); }
  }
  private static final class SigningMismatch extends IncompatibleApk {
    SigningMismatch() { super("当前安装使用旧签名，需要迁移安装"); }
  }
  private String updateError(Exception e, String fallback) {
    if (e instanceof UpdateFailure) return e.getMessage();
    if (String.valueOf(e.getMessage()).contains("ENOSPC") ||
        directory.getUsableSpace() == 0 && directory.isDirectory()) return "存储空间不足，释放空间后重试";
    return fallback;
  }

  private PackageInfo validateApk(File file, String expectedVersion) throws Exception {
    PackageInfo candidate = app.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(),
        PackageManager.GET_SIGNING_CERTIFICATES);
    PackageInfo current = installed();
    if (candidate == null || candidate.signingInfo == null || !current.packageName.equals(candidate.packageName))
      throw new IncompatibleApk("更新包无效");
    if ((expectedVersion != null && !expectedVersion.equals(candidate.versionName)) ||
        candidate.getLongVersionCode() <= current.getLongVersionCode() ||
        (candidate.applicationInfo != null && candidate.applicationInfo.minSdkVersion > Build.VERSION.SDK_INT))
      throw new IncompatibleApk("此更新与当前版本或设备不兼容");
    if (!compatibleSigners(current.signingInfo, candidate.signingInfo))
      throw new SigningMismatch();
    return candidate;
  }

  private static boolean compatibleSigners(SigningInfo installed, SigningInfo candidate) {
    if (installed == null) return false;
    HashSet<Signature> current = new HashSet<>(Arrays.asList(installed.getApkContentsSigners()));
    if (installed.hasMultipleSigners() || candidate.hasMultipleSigners())
      return current.equals(new HashSet<>(Arrays.asList(candidate.getApkContentsSigners())));
    Signature[] history = candidate.getSigningCertificateHistory();
    return history != null && Arrays.asList(history).containsAll(current);
  }

  void install() {
    if (activity == null || !canInstall()) return;
    if (confirmation != null) {
      showConfirmation();
      return;
    }
    if (!app.getPackageManager().canRequestPackageInstalls()) {
      new AlertDialog.Builder(activity).setMessage(Language.t("请允许 LanLink 安装未知应用，然后返回继续。"))
          .setPositiveButton(Language.t("前往授权"), (d,w) -> requestPermission())
          .setNegativeButton(Language.t("稍后"), null).show();
      return;
    }
    setStatus(Phase.INSTALLING, "正在准备安装");
    worker.execute(this::submitInstaller);
  }

  private void requestPermission() {
    if (activity == null) return;
    try {
      if (!updates.edit().putBoolean("awaitingInstallPermission", true).commit())
        throw new IllegalStateException("Cannot save pending installation permission");
      activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
          Uri.parse("package:" + app.getPackageName())));
    } catch (RuntimeException e) {
      updates.edit().remove("awaitingInstallPermission").apply();
      Log.w(TAG, "Opening install authorization", e);
      websiteFallback = true;
      setStatus(Phase.READY, "无法打开安装授权设置");
    }
  }

  private void submitInstaller() {
    PackageInstaller installer = app.getPackageManager().getPackageInstaller();
    int id = -1;
    try {
      validateApk(apk, null);
      int previous = updates.getInt("session", -1);
      if (previous != -1 && installer.getSessionInfo(previous) != null) installer.abandonSession(previous);
      PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
      params.setAppPackageName(app.getPackageName());
      long size = updates.getLong("size", -1);
      params.setSize(size);
      if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
      id = installer.createSession(params);
      try (PackageInstaller.Session session = installer.openSession(id)) {
        try (InputStream in = new FileInputStream(apk); OutputStream out = session.openWrite("base.apk", 0, size)) {
          if (!copyApk(in, out, size, null).equals(updates.getString("sha256", ""))) throw new IOException("APK checksum mismatch");
          session.fsync(out);
        }
        if (!updates.edit().putInt("session", id).commit()) throw new IOException("Cannot save install session");
        Intent callback = new Intent(app, InstallResultReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        session.commit(PendingIntent.getBroadcast(app, id, callback, flags).getIntentSender());
      }
    } catch (Exception e) {
      if (id != -1) {
        try { installer.abandonSession(id); } catch (RuntimeException ignored) {}
      }
      updates.edit().remove("session").apply();
      Log.w(TAG, "Preparing APK installation", e);
      main.post(() -> {
        websiteFallback = true;
        setStatus(Phase.READY, updateError(e, "无法启动安装，请重试"));
      });
    }
  }

  public static final class InstallResultReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent result) { get(context).installResult(result); }
  }

  private void installResult(Intent result) {
    int id = result.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);
    if (id == -1 || id != updates.getInt("session", -1)) return;
    int code = result.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
    if (code == PackageInstaller.STATUS_PENDING_USER_ACTION) {
      confirmation = result.getParcelableExtra(Intent.EXTRA_INTENT);
      if (confirmation != null) {
        setStatus(Phase.INSTALLING, "等待确认安装");
        showConfirmation();
        return;
      }
    }
    confirmation = null;
    updates.edit().remove("session").apply();
    if (code == PackageInstaller.STATUS_SUCCESS) {
      discardDownload();
      setStatus(Phase.IDLE, "更新已安装");
      return;
    }
    Log.w(TAG, "Installer status " + code + ": " + result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
    websiteFallback = code != PackageInstaller.STATUS_FAILURE_ABORTED;
    String message = code == PackageInstaller.STATUS_FAILURE_ABORTED ? "安装已取消，可以继续安装" :
        code == PackageInstaller.STATUS_FAILURE_STORAGE ? "存储空间不足，释放空间后重试" :
        code == PackageInstaller.STATUS_FAILURE_BLOCKED ? "系统阻止了此次安装，请检查设备的安装限制" :
        code == PackageInstaller.STATUS_FAILURE_CONFLICT ? "安装与设备上已有的应用冲突" : "安装失败，可以重试";
    setStatus(Phase.READY, message);
  }

  private void showConfirmation() {
    if (activity == null || confirmation == null) return;
    Intent intent = confirmation;
    confirmation = null;
    try {
      activity.startActivity(intent);
      changed();
    } catch (RuntimeException e) {
      Log.w(TAG, "Opening install confirmation", e);
      websiteFallback = true;
      setStatus(Phase.READY, "无法启动安装，请重试");
    }
  }

  void discardDownload() {
    int session = updates.getInt("session", -1);
    if (session != -1) {
      try { app.getPackageManager().getPackageInstaller().abandonSession(session); } catch (RuntimeException ignored) {}
    }
    updates.edit().clear().apply();
    if (apk.exists() && !apk.delete()) Log.w(TAG, "Could not remove saved APK");
    available = null;
    websiteFallback = retryDownload = migrationRequired = false;
    setStatus(Phase.IDLE, "");
  }

  void openWebsite() {
    if (activity == null) return;
    String address = downloadPage();
    try { activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(address))); }
    catch (RuntimeException e) { activity.showOperationError(Language.t("无法打开网页") + "\n" + address); }
  }

  void showMigration() {
    if (activity == null) return;
    new AlertDialog.Builder(activity).setTitle(Language.t("签名迁移说明"))
        .setMessage(Language.t("系统无法覆盖安装。请先保存需要的文件并确认可以重新配对设备。卸载旧版会清除本机应用数据。完成备份后，再卸载旧版并安装发布页上的 APK。"))
        .setPositiveButton(Language.t("查看此版本发布页"), (dialog, which) -> openWebsite())
        .setNegativeButton(Language.t("关闭"), null).show();
  }
}
