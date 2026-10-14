package com.lanlink.app;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

// ADB/root is an explicit, local-only capability. The helper gets no LAN socket.
public final class ShellChannel implements AutoCloseable {
  private final LinkService service;
  private final String secret;
  private ServerSocket server;
  private Socket socket;
  private BufferedReader input;
  private BufferedWriter output;
  private boolean closed;
  private int agentVersion;
  public volatile String lastInputError;
  public volatile boolean inputVerified;
  private volatile long lastSuccess;
  private volatile long disconnectedAt;
  private volatile int uid = -1;
  private volatile boolean rootWanted;
  private Process rootProcess;

  public boolean rootReady() {
    return rootWanted && uid == 0 && ready();
  }

  public boolean recovering() {
    return !ready()
        && lastInputError == null
        && lastSuccess != 0
        && android.os.SystemClock.elapsedRealtime()
                - (disconnectedAt != 0 ? disconnectedAt : lastSuccess)
            < 12000;
  }

  private static final class InputRejected extends IOException {
    InputRejected(String message) {
      super(message);
    }
  }

  public ShellChannel(LinkService service) {
    this.service = service;
    android.content.SharedPreferences prefs = service.getSharedPreferences("adb-helper", 0);
    if (prefs.getBoolean("authorized", false))
      lastSuccess = disconnectedAt = android.os.SystemClock.elapsedRealtime();
    secret = prefs.getString("secret", Wire.token());
    try {
      int port = prefs.getInt("port", 0);
      server = new ServerSocket();
      server.setReuseAddress(true);
      server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 2);
      if (!prefs.edit().putString("secret", secret).putInt("port", server.getLocalPort()).commit())
        throw new IOException("助手绑定保存失败");
      new Thread(this::accept, "lanlink-shell").start();
    } catch (Exception e) {
      try {
        if (server != null) server.close();
      } catch (IOException ignored) {
      }
      service.status = "无法创建 ADB 助手通道";
    }
  }

  private void accept() {
    while (!closed)
      try {
        Socket s = server.accept();
        s.setSoTimeout(4000);
        BufferedReader in =
            new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        BufferedWriter out =
            new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
        int incomingUid,incomingVersion;
        try {
          if ((incomingVersion=ShellAuth.server(secret, in, out)) < 10) {
            // Authentication is complete; retire only this bound, obsolete helper.
            out.write("{\"method\":\"exit\"}\n");
            out.flush();
            throw new IOException("请恢复连接以升级输入助手");
          }
          out.write("{\"method\":\"heartbeat\"}\n");
          out.flush();
          incomingUid = new JSONObject(limitedLine(in)).optInt("uid", -1);
          if (incomingUid == 2000 && service.adbRevoked()) {
            out.write("{\"method\":\"exit\"}\n");
            out.flush();
            throw new IOException("ADB 授权已解除");
          }
          if (incomingUid != (rootWanted ? 0 : 2000)) throw new IOException("输入助手运行身份不匹配");
        } catch (Exception invalid) {
          s.close();
          continue;
        }
        synchronized (this) {
          if (incomingUid != (rootWanted ? 0 : 2000)
              || incomingUid == 2000 && service.adbRevoked()) {
            s.close();
            continue;
          }
          if (closed) {
            s.close();
            return;
          }
          if (socket != null) socket.close();
          socket = s;agentVersion=incomingVersion;
          input = in;
          output = out;
          uid = rootWanted ? 0 : 2000;
          if (incomingUid == 2000)
            service
                .getSharedPreferences("adb-helper", 0)
                .edit()
                .putBoolean("adb_authorized", true)
                .apply();
          lastSuccess = android.os.SystemClock.elapsedRealtime();
          disconnectedAt = 0;
          service
              .getSharedPreferences("adb-helper", 0)
              .edit()
              .putBoolean("authorized", true)
              .apply();
          lastInputError = null;
          inputVerified = false;
        }
        service.status = "ADB / Root 输入助手已连接";
      } catch (Exception e) {
        if (!closed) service.status = "ADB 助手连接失败";
      }
  }

  private static String limitedLine(Reader in) throws IOException {
    StringBuilder b = new StringBuilder();
    int c;
    while ((c = in.read()) != -1 && c != '\n') {
      if (b.length() > 6000000) throw new IOException("消息过大");
      b.append((char) c);
    }
    if (c == -1) throw new EOFException();
    return b.toString();
  }

  public synchronized boolean ready() {
    return socket != null && !socket.isClosed();
  }

  synchronized boolean supportsInputBatch(){return agentVersion>=12;}
  static String motionLine(JSONObject message){
    JSONObject a=message.optJSONObject("args");String kind=a==null?"":a.optString("kind");
    if(!message.optString("method").equals("input")||!kind.equals("move")&&!kind.equals("relative"))return null;
    String mode=kind.equals("relative")?"r":a.has("dx")&&a.has("dy")?"m":"a";
    return "@M "+mode+" "+a.optDouble("x",0)+" "+a.optDouble("y",0)+" "+a.optDouble("dx",0)+" "+a.optDouble("dy",0)+" "+message.optInt("width")+" "+message.optInt("height")+" "+(message.optBoolean("pointer",true)?1:0);
  }
  public synchronized JSONObject request(JSONObject message) throws Exception {
    if (!ready()) throw new IOException("INPUT_RECOVERING: 输入助手正在恢复连接");
    try {
      if ((message.optString("method").equals("input")||message.optString("method").equals("inputBatch"))) {
        android.util.DisplayMetrics m = service.displayMetrics();
        message.put("width", m.widthPixels).put("height", m.heightPixels);
      }
      String compact=agentVersion>=11?motionLine(message):null;
      output.write(compact!=null?compact:message.toString());
      output.write('\n');
      output.flush();
      JSONObject reply = new JSONObject(limitedLine(input));
      if (reply.has("error")) {
        String reason = reply.getString("error");
        if(message.optString("method").startsWith("clipboard-"))throw new InputRejected(reason);
        boolean denied = reason.startsWith("PERMISSION_REQUIRED:");
        String maker = android.os.Build.MANUFACTURER.toLowerCase(java.util.Locale.ROOT);
        if (denied && (maker.contains("xiaomi") || maker.contains("redmi")))
          reason += "。小米 / HyperOS：请在开发者选项开启「USB 调试（安全设置）」；普通 USB 调试不足以允许模拟输入。";
        if (denied) {
          lastInputError = reason;
          inputVerified = false;
        }
        throw new InputRejected(denied ? reason : "INPUT_RECOVERING: " + reason);
      }
      if (message.optString("method").equals("input")||message.optString("method").equals("inputBatch")
          || message.optString("method").equals("paste")
          || message.optString("method").equals("probe")) {
        lastInputError = null;
        inputVerified = true;
      }
      lastSuccess = android.os.SystemClock.elapsedRealtime();
      return reply;
    } catch (InputRejected rejected) {
      // A live transport with denied injection must not reconnect endlessly.
      throw rejected;
    } catch (Exception e) {
      disconnectedAt = android.os.SystemClock.elapsedRealtime();
      if (socket != null) socket.close();
      socket = null;
      throw e;
    }
  }

  /** Only sent over an authenticated channel after this device has approved remote input. */
  public synchronized JSONObject bootstrap() throws IOException {
    if (closed || server == null || server.isClosed()) throw new IOException("ADB 助手不可用");
    return Wire.obj(
        "apkPath",
        service.getApplicationInfo().sourceDir,
        "port",
        server.getLocalPort(),
        "secret",
        secret,
        "protocol",
        2,
        "agentVersion",
        10,
        "persistent",
        true);
  }

  public synchronized boolean healthy() {
    if (!ready()) return false;
    if (android.os.SystemClock.elapsedRealtime() - lastSuccess < 2000) return true;
    try {
      request(Wire.obj("method", "heartbeat"));
      return true;
    } catch (Exception ignored) {
      return false;
    }
  }

  public String command() {
    if (server == null) return "ADB 助手不可用";
    return "CLASSPATH="
        + "'"
        + service.getApplicationInfo().sourceDir.replace("'", "'\\''")
        + "'"
        + " app_process /system/bin com.lanlink.app.ShellAgent "
        + server.getLocalPort()
        + " "
        + secret;
  }

  public void root() throws Exception {
    if (rootReady()) return;
    synchronized (this) {
      rootWanted = true;
      if (socket != null) socket.close();
      socket = null;
    }
    rootProcess = new ProcessBuilder("su", "-c", command()).redirectErrorStream(true).start();
    // Root managers may require the user's first approval. Never report success before UID
    // verification.
    for (int i = 0; i < 300; i++) {
      if (rootReady()) return;
      if (!rootProcess.isAlive() && rootProcess.exitValue() != 0) break;
      Thread.sleep(100);
    }
    disableRoot();
    throw new IOException("未获得 Root 授权，请在 Root 管理器允许 LanLink 后重试");
  }

  public synchronized void disableRoot() {
    try {
      if (uid == 0 && output != null) {
        output.write("{\"method\":\"exit\"}\n");
        output.flush();
      }
    } catch (Exception ignored) {
    }
    rootWanted = false;
    if (uid == 0) {
      try {
        if (socket != null) socket.close();
      } catch (Exception ignored) {
      }
      socket = null;
      uid = -1;
    }
    if (rootProcess != null) {
      rootProcess.destroy();
      rootProcess = null;
    }
  }

  public synchronized void close() {
    closed = true;
    try {
      if (socket != null) socket.close();
      if (server != null) server.close();
    } catch (Exception ignored) {
    }
  }

  public synchronized void revokeAdb() {
    service
        .getSharedPreferences("adb-helper", 0)
        .edit()
        .putBoolean("adb_authorized", false)
        .putBoolean("authorized", false)
        .apply();
    if (uid != 0) {
      try {
        if (output != null) {
          output.write("{\"method\":\"exit\"}\n");
          output.flush();
        }
      } catch (IOException ignored) {
      }
      try {
        if (socket != null) socket.close();
      } catch (IOException ignored) {
      }
      socket = null;
      uid = -1;
      lastSuccess = 0;
      lastInputError = null;
      inputVerified = false;
    }
  }
}
