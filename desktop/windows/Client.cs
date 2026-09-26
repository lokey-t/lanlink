using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Threading.Tasks;
using System.Web.Script.Serialization;

namespace LanLink.Desktop {
  public static class Json {
    public static Dictionary<string, object> Map(object value) { return value as Dictionary<string, object> ?? new Dictionary<string, object>(); }
    public static object Get(object value, string key) { object result; return Map(value).TryGetValue(key, out result) ? result : null; }
    public static string Text(object value, string key) { return Convert.ToString(Get(value, key)); }
    public static bool Flag(object value, string key) { return Get(value, key) is bool && (bool)Get(value, key); }
    public static double Number(object value, string key, double fallback) { double n; return double.TryParse(Text(value, key), out n) ? n : fallback; }
  }
  public sealed class Client {
    readonly JavaScriptSerializer json = new JavaScriptSerializer { MaxJsonLength = 12 * 1024 * 1024 };
    readonly Dictionary<string, TaskCompletionSource<object>> pending = new Dictionary<string, TaskCompletionSource<object>>();
    readonly object gate = new object();
    readonly TaskCompletionSource<object> ready = new TaskCompletionSource<object>();
    Process process; int sequence; bool stopping;
    public Action<string> Failed;
    public async Task Start(string root, string options) {
      string node = Path.Combine(root, "runtime", "node.exe");
      if (!File.Exists(node)) throw new Exception("缺少随软件附带的运行组件，请完整解压软件包。");
      var info = new ProcessStartInfo(node, "\"" + Path.Combine(root, "src", "native-host.js") + "\" " + options) {
        WorkingDirectory = root, UseShellExecute = false, CreateNoWindow = true,
        RedirectStandardInput = true, RedirectStandardOutput = true, RedirectStandardError = true,
        StandardOutputEncoding = Encoding.UTF8, StandardErrorEncoding = Encoding.UTF8
      };
      process = new Process { StartInfo = info, EnableRaisingEvents = true };
      process.OutputDataReceived += delegate(object sender, DataReceivedEventArgs e) {
        if (e.Data == null) return;
        try {
          var message = Json.Map(json.DeserializeObject(e.Data));
          if (Json.Flag(message, "ready")) { ready.TrySetResult(null); return; }
          if (message.ContainsKey("fatal")) { Fail(Json.Text(message, "fatal")); return; }
          TaskCompletionSource<object> promise;
          lock (gate) { string id = Json.Text(message, "id"); if (!pending.TryGetValue(id, out promise)) return; pending.Remove(id); }
          if (message.ContainsKey("error")) promise.TrySetException(new Exception(Json.Text(message, "error")));
          else promise.TrySetResult(Json.Get(message, "result"));
        } catch (Exception ex) { Fail("服务响应无法读取：" + ex.Message); }
      };
      process.ErrorDataReceived += delegate { /* stderr is not a UI protocol */ };
      process.Exited += delegate { Fail("设备服务已退出，请重新启动 LanLink。"); };
      process.Start(); process.BeginOutputReadLine(); process.BeginErrorReadLine();
      if (await Task.WhenAny(ready.Task, Task.Delay(35000)) != ready.Task) throw new Exception("设备服务启动超时");
      await ready.Task;
    }
    void Fail(string message) {
      ready.TrySetException(new Exception(message));
      lock (gate) { foreach (var p in pending.Values) p.TrySetException(new Exception(message)); pending.Clear(); }
      if (!stopping && Failed != null) Failed(message);
    }
    public async Task<object> Call(string method, object args = null) {
      var promise = new TaskCompletionSource<object>(); string id;
      lock (gate) {
        id = (++sequence).ToString(); pending[id] = promise;
        try { byte[] bytes = Encoding.UTF8.GetBytes(json.Serialize(new { id = id, method = method, args = args ?? new { } }) + "\n"); process.StandardInput.BaseStream.Write(bytes, 0, bytes.Length); process.StandardInput.BaseStream.Flush(); }
        catch { pending.Remove(id); throw new Exception("设备服务连接已断开"); }
      }
      int timeout = method == "send-files" || method == "device-paste" || method == "paste" || method == "adb-authorize" ? 130000 : 30000;
      if (await Task.WhenAny(promise.Task, Task.Delay(timeout)) != promise.Task) { lock (gate) pending.Remove(id); throw new Exception("操作超时，请检查目标设备"); }
      return await promise.Task;
    }
    public async Task Stop() {
      if (stopping) return; stopping = true;
      if (process == null) return;
      try { process.StandardInput.Close(); } catch { }
      await Task.Run(delegate { try { if (!process.WaitForExit(9000)) process.Kill(); } catch { } });
      process.Dispose();
    }
  }
}
