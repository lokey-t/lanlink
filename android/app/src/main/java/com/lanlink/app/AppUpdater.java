package com.lanlink.app;

import android.app.*;
import android.content.*;
import android.net.Uri;
import org.json.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Release assets are identical on both mirrors. No APK is installed before checksum verification. */
final class AppUpdater {
  private final MainActivity activity;
  private volatile boolean busy;
  private File pending;
  private AlertDialog offer;
  private final String[] feeds={"https://api.github.com/repos/lokey-t/lanlink/releases/latest","https://gitee.com/api/v5/repos/lokey-t/lanlink/releases/latest"};
  AppUpdater(MainActivity activity){this.activity=activity;}
  private SharedPreferences preferences() { return activity.getSharedPreferences("identity", 0); }
  boolean beta() { return preferences().getBoolean("update_beta", false); }
  void setBeta(boolean beta) {
    if (offer != null) offer.dismiss();
    pending = null;
    preferences().edit().putBoolean("update_beta", beta).apply();
  }
  private String checkTimeKey(boolean beta) { return beta ? "beta_update_check_time" : "update_check_time"; }
  void automatic() {
    SharedPreferences p = preferences();
    if (p.getBoolean("auto_check_updates", true) &&
        System.currentTimeMillis() - p.getLong(checkTimeKey(beta()), 0) > 21600000) check(false);
  }
  void chooseChannel(Runnable onChanged) {
    if (pending != null) {
      message(Language.t("当前更新正在安装或已准备好，请完成后再切换通道"));
      return;
    }
    new AlertDialog.Builder(activity).setTitle(Language.t("更新通道"))
        .setSingleChoiceItems(new String[] {Language.t("正式版"), Language.t("测试版")}, beta() ? 1 : 0,
            (dialog, which) -> { setBeta(which == 1); dialog.dismiss(); onChanged.run(); })
        .setNegativeButton(Language.t("取消"), null).show();
  }
  static byte[] read(InputStream in,int limit)throws Exception{try(InputStream stream=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[65536];int n,total=0;while((n=stream.read(b))!=-1){total+=n;if(total>limit)throw new IOException("Update too large");out.write(b,0,n);}return out.toByteArray();}}
  static byte[] download(String url,int limit)throws Exception{if(!url.startsWith("https://"))throw new IOException("HTTPS required");HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(15000);c.setReadTimeout(60000);c.setRequestProperty("User-Agent","LanLink-Updater");try{return read(c.getInputStream(),limit);}finally{c.disconnect();}}
  static JSONObject json(byte[] b)throws Exception{return new JSONObject(new String(b,java.nio.charset.StandardCharsets.UTF_8));}
  static String hash(byte[] bytes)throws Exception{StringBuilder s=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
  static boolean newer(String a,String b){if(!a.matches("\\d+\\.\\d+\\.\\d+")||!b.matches("\\d+\\.\\d+\\.\\d+"))return false;String[] x=a.split("\\."),y=b.split("\\.");for(int i=0;i<3;i++){int n=Integer.compare(Integer.parseInt(x[i]),Integer.parseInt(y[i]));if(n!=0)return n>0;}return false;}
  static byte[] asset(List<JSONObject> releases,JSONObject spec)throws Exception{Exception last=new IOException("Missing asset");for(JSONObject release:releases){JSONArray assets=release.getJSONArray("assets");for(int i=0;i<assets.length();i++){JSONObject a=assets.getJSONObject(i);if(!spec.getString("name").equals(a.optString("name")))continue;try{byte[] bytes=download(a.getString("browser_download_url"),536870912);if(bytes.length!=spec.getLong("size")||!hash(bytes).equals(spec.getString("sha256")))throw new IOException("Checksum mismatch");return bytes;}catch(Exception e){last=e;}}}throw last;}
  void check(boolean manual) {
    if (busy) return;
    busy = true;
    boolean beta = beta();
    new Thread(() -> {
      try {
        List<JSONObject> releases = new ArrayList<>();
        JSONObject manifest = null;
        for (String feed : feeds) {
          try {
            String url = beta ? feed.replace("/latest", "?per_page=30") : feed;
            byte[] response = download(url, 8388608);
            JSONArray candidates = beta
                ? new JSONArray(new String(response, java.nio.charset.StandardCharsets.UTF_8))
                : new JSONArray().put(json(response));
            for (int i = 0; i < candidates.length(); i++) {
              JSONObject release = candidates.getJSONObject(i);
              String version = release.getString("tag_name").replaceFirst("^v", "");
              if (release.optBoolean("draft") || release.optBoolean("prerelease") != beta
                  || !version.matches("\\d+\\.\\d+\\.\\d+")) continue;
              JSONArray assets = release.getJSONArray("assets");
              if (manifest != null) {
                if (version.equals(manifest.getString("version"))) { releases.add(release); break; }
                continue;
              }
              for (int j = 0; j < assets.length(); j++) {
                JSONObject asset = assets.getJSONObject(j);
                if (!asset.optString("name").equals("lanlink-update.json")) continue;
                JSONObject candidate = json(download(asset.getString("browser_download_url"), 2097152));
                if (candidate.getInt("format") == 1 && version.equals(candidate.getString("version"))) {
                  manifest = candidate;
                  releases.add(release);
                }
                break;
              }
              if (manifest != null) break;
            }
          } catch (Exception ignored) {}
        }
        if (manifest == null) throw new IOException(Language.t(beta ? "暂无可用的测试版发行" : "暂无可用的正式版发行"));
        preferences().edit().putLong(checkTimeKey(beta), System.currentTimeMillis()).apply();
        String installed = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
        JSONObject selected = manifest;
        activity.runOnUiThread(() -> {
          if (beta != beta() || activity.isFinishing() || activity.isDestroyed()) return;
          if (!newer(selected.optString("version"), installed)) {
            if (manual) message(Language.t(newer(installed, selected.optString("version"))
                ? "当前版本高于所选通道的发行版" : "已是最新版本"));
            return;
          }
          if (!manual && !preferences().getBoolean("auto_check_updates", true)) return;
          if (offer != null) offer.dismiss();
          offer = new AlertDialog.Builder(activity)
              .setMessage(Language.t("发现新版本") + " " + selected.optString("version"))
              .setPositiveButton(Language.t("更新"), (d,w) -> {
                if (beta == beta()) install(selected, releases, installed, beta);
              })
              .setNegativeButton(Language.t("稍后"), null).show();
        });
      } catch (Exception e) { if (manual && beta == beta()) message(e.getMessage()); }
      finally { busy = false; }
    }, "update-check").start();
  }
  void message(String text){activity.runOnUiThread(()->android.widget.Toast.makeText(activity,text,android.widget.Toast.LENGTH_LONG).show());}
  void install(JSONObject manifest,List<JSONObject> releases,String installed,boolean beta){if(busy)return;busy=true;message("正在下载更新…");new Thread(()->{try{
    byte[] apk;
    try{JSONObject bundle=json(read(new GZIPInputStream(new ByteArrayInputStream(asset(releases,manifest.getJSONObject("delta")))),1073741824));JSONObject patch=bundle.getJSONObject("targets").getJSONObject("android");if(bundle.getInt("format")!=1||!bundle.getString("version").equals(manifest.getString("version"))||!patch.getString("from").equals(installed))throw new IOException("Base version mismatch");JSONObject entry=patch.getJSONObject("files").getJSONObject("app.apk");byte[] base=read(new FileInputStream(activity.getApplicationInfo().sourceDir),536870912);if(entry.has("base")&&!hash(base).equals(entry.getString("base")))throw new IOException("Base checksum mismatch");ByteArrayOutputStream out=new ByteArrayOutputStream();JSONArray ops=entry.getJSONArray("ops");for(int i=0;i<ops.length();i++){Object op=ops.get(i);if(op instanceof String){byte[] bytes=android.util.Base64.decode((String)op,android.util.Base64.DEFAULT);out.write(bytes);}else{JSONArray copy=(JSONArray)op;int offset=copy.getInt(0),length=copy.getInt(1);if(offset<0||length<0||offset>base.length-length)throw new IOException("Invalid copy range");out.write(base,offset,length);}if(out.size()>536870912)throw new IOException("APK too large");}apk=out.toByteArray();if(!hash(apk).equals(entry.getString("sha256")))throw new IOException("APK checksum mismatch");}
    catch(Exception deltaFailure){message("增量更新失败，正在下载全量更新…");apk=asset(releases,manifest.getJSONObject("targets").getJSONObject("android").getJSONObject("full"));}
    File dir=new File(activity.getFilesDir(),"received");dir.mkdirs();File file=new File(dir,"lanlink-update.apk");try(FileOutputStream out=new FileOutputStream(file)){out.write(apk);}activity.runOnUiThread(()->{if(beta==beta()&&!activity.isFinishing()&&!activity.isDestroyed()){pending=file;openInstaller();}});
  }catch(Exception e){message("更新失败："+e.getMessage());}finally{busy=false;}},"update-install").start();}
  void openInstaller(){if(pending==null)return;if(android.os.Build.VERSION.SDK_INT>=26&&!activity.getPackageManager().canRequestPackageInstalls()){activity.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+activity.getPackageName())));return;}File file=pending;pending=null;try{Intent intent=new Intent(Intent.ACTION_VIEW);intent.setDataAndType(Uri.parse("content://com.lanlink.app.received/"+file.getName()),"application/vnd.android.package-archive");intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);activity.startActivity(intent);}catch(Exception e){message(e.getMessage());}}
}
