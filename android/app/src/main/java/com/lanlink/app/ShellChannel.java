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
  public volatile String lastInputError;

  private static final class InputRejected extends IOException {
    InputRejected(String message) {
      super(message);
    }
  }

  public ShellChannel(LinkService service) {
    this.service = service;
    android.content.SharedPreferences prefs = service.getSharedPreferences("adb-helper", 0);
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
        try {
          if (ShellAuth.server(secret, in, out) < 3) throw new IOException("请恢复连接以升级输入助手");
        } catch (Exception invalid) {
          s.close();
          continue;
        }
        synchronized (this) {
          if (closed) {
            s.close();
            return;
          }
          if (socket != null) socket.close();
          socket = s;
          input = in;
          output = out;
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
      if (b.length() > 65536) throw new IOException("消息过大");
      b.append((char) c);
    }
    if (c == -1) throw new EOFException();
    return b.toString();
  }

  public synchronized boolean ready() {
    return socket != null && !socket.isClosed();
  }

  public synchronized JSONObject request(JSONObject message) throws Exception {
    if (!ready()) throw new IOException("ADB 助手未连接");
    try {
      if (message.optString("method").equals("input")) {
        android.util.DisplayMetrics m = service.getResources().getDisplayMetrics();
        message.put("width", m.widthPixels).put("height", m.heightPixels);
      }
      output.write(message.toString());
      output.write('\n');
      output.flush();
      JSONObject reply = new JSONObject(limitedLine(input));
      if (reply.has("error")) {
        String reason = reply.getString("error");
        String maker = android.os.Build.MANUFACTURER.toLowerCase(java.util.Locale.ROOT);
        if (maker.contains("xiaomi") || maker.contains("redmi"))
          reason += "。小米 / HyperOS：请在开发者选项开启「USB 调试（安全设置）」；普通 USB 调试不足以允许模拟输入。";
        lastInputError = reason;
        throw new InputRejected("PERMISSION_REQUIRED: " + reason);
      }
      if (message.optString("method").equals("input")
          || message.optString("method").equals("paste")) lastInputError = null;
      return reply;
    } catch (InputRejected rejected) {
      // A live transport with denied injection must not reconnect endlessly.
      throw rejected;
    } catch (Exception e) {
      socket.close();
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
        3,
        "persistent",
        true);
  }

  public synchronized boolean healthy() {
    if (!ready()) return false;
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
        + service.getApplicationInfo().sourceDir
        + " app_process /system/bin com.lanlink.app.ShellAgent "
        + server.getLocalPort()
        + " "
        + secret;
  }

  public void root() throws IOException {
    new ProcessBuilder("su", "-c", command()).redirectErrorStream(true).start();
  }

  public synchronized void close() {
    closed = true;
    try {
      if (socket != null) socket.close();
      if (server != null) server.close();
    } catch (Exception ignored) {
    }
  }
}
