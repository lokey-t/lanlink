using System;
using System.Collections.Generic;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace LanLink.Desktop {
  public sealed class RemoteWindow : Window {
    readonly MainWindow main; readonly Device device;
    readonly Image image = new Image { Stretch = Stretch.Uniform, Focusable = true };
    readonly TextBlock status = MainWindow.Text("正在获取屏幕…", 12);
    bool closed, controlling, moveBusy, wantsControl = true, fitted, fallbackAccepted, showingModeDialog, conflict; int sessionVersion; object pendingMove; Task inputs = Task.FromResult(0); DateTime retryAt;
    readonly double openedAt = (DateTime.UtcNow-new DateTime(1970,1,1)).TotalMilliseconds;
    public bool ControlRequested { get; private set; }
    public int NavigationKeyCount { get; private set; }
    public bool HasRightToolbar { get; private set; }
    public string DeviceId { get { return device.Id; } }
    bool resumeOnActivate;
    string inputFailure;
    public RemoteWindow(MainWindow owner, Device d) {
      main = owner; device = d; Owner = owner; Title = "远程控制 · " + d.Name; MinWidth = device.Platform == "android" ? 280 : 320; MinHeight = 320; Resources = owner.Resources; FontFamily = owner.FontFamily; Background = MainWindow.Brush("#F5F8FE"); WindowStartupLocation = WindowStartupLocation.CenterScreen; FitScreen(d.Width, d.Height);
      bool phone = device.Platform == "android";
      var dock = new DockPanel { Margin = new Thickness(phone ? 4 : 16) }; WindowShell.Wrap(this, dock, true, phone);
      DockPanel.SetDock(status, Dock.Bottom); status.Margin = phone ? new Thickness(6, 4, 6, 3) : new Thickness(0, 12, 0, 0); status.FontSize = phone ? 10 : 12; status.TextWrapping = phone ? TextWrapping.NoWrap : TextWrapping.Wrap; dock.Children.Add(status);
      var bar = new StackPanel { Orientation = phone ? Orientation.Vertical : Orientation.Horizontal, Margin = phone ? new Thickness(4, 0, 0, 0) : new Thickness(0, 0, 0, 8) }; DockPanel.SetDock(bar, phone ? Dock.Right : Dock.Top); dock.Children.Add(bar); HasRightToolbar = phone;
      AddTool(bar, phone, "开始", "重新控制", () => Run(RestartControl));
      AddTool(bar, phone, "暂停", "停止键鼠控制，保留画面", () => Run(Release));
      if (phone) { bar.Children.Add(new Separator { Margin = new Thickness(6, 6, 6, 6) }); foreach (var key in new[] { new[] { "back", "‹", "返回" }, new[] { "home", "○", "主页" }, new[] { "recents", "□", "最近任务" } }) { string action = key[0]; AddTool(bar, true, key[1], key[2], () => Run(async () => { await Send(new { kind = "navigation", action = action }); })); NavigationKeyCount++; } bar.Children.Add(new Separator { Margin = new Thickness(6, 6, 6, 6) }); }
      AddTool(bar, phone, "粘贴", "粘贴共享剪贴板", () => Run(async () => { if (!controlling) throw new Exception("请先开始控制"); await main.Client.Call("paste", new { id = device.Id }); }));
      AddTool(bar, phone, "文字", "输入文本", TextDialog);
      if (phone) AddTool(bar, true, "ADB", "从电脑为手机启用 ADB 增强控制", () => { main.ShowAdb(device); main.Show(); main.Activate(); });
      var frame = new Border { Background = MainWindow.Brush("#182034"), CornerRadius = new CornerRadius(phone ? 6 : 12), Child = image, ClipToBounds = true }; dock.Children.Add(frame);
      Loaded += async delegate { await Frames(); };
      Closed += async delegate { closed = true; try { await main.Client.Call("stream-close", new { id = device.Id }); } catch { } await Release(); };
      Deactivated += async delegate { if (controlling && !showingModeDialog) { bool resume=wantsControl;await Release();resumeOnActivate=resume;if(IsActive)await ResumeAfterFocus(); } };
      Activated += async delegate { await ResumeAfterFocus(); };
      image.MouseMove += delegate(object sender, MouseEventArgs e) { if (controlling) { pendingMove = Coordinates(e.GetPosition(image)); FlushMove(); } };
      image.MouseDown += async delegate(object sender, MouseButtonEventArgs e) { if (!controlling) return; var position = Coordinates(e.GetPosition(image)); if (position == null) return; e.Handled = true; image.Focus(); image.CaptureMouse(); await Send(position); await Send(new { kind = "button", button = MouseButton(e.ChangedButton), down = true }); };
      image.MouseUp += async delegate(object sender, MouseButtonEventArgs e) { if (!controlling) return; e.Handled = true; await Send(new { kind = "button", button = MouseButton(e.ChangedButton), down = false }); image.ReleaseMouseCapture(); };
      image.MouseWheel += async delegate(object sender, MouseWheelEventArgs e) { if (!controlling) return; e.Handled = true; await Send(new { kind = "wheel", delta = Math.Max(-2400, Math.Min(2400, e.Delta)) }); };
      image.PreviewKeyDown += async delegate(object sender, KeyEventArgs e) {
        if (!controlling) return; e.Handled = true; var key = e.Key == Key.System ? e.SystemKey : e.Key;
        if (key == Key.V && (Keyboard.Modifiers & (ModifierKeys.Control | ModifierKeys.Windows)) != 0) { if (!e.IsRepeat) Run(async () => { await main.Client.Call("paste", new { id = device.Id }); }); return; }
        string code = KeyCode(key); if (code != null) await Send(new { kind = "key", code = code, down = true });
      };
      image.PreviewKeyUp += async delegate(object sender, KeyEventArgs e) { if (!controlling) return; e.Handled = true; string code = KeyCode(e.Key == Key.System ? e.SystemKey : e.Key); if (code != null) await Send(new { kind = "key", code = code, down = false }); };
    }
    void AddTool(StackPanel panel, bool compact, string label, string tooltip, Action action) {
      var button = MainWindow.Button(compact ? label : tooltip == "输入文本" ? tooltip : label == "开始" ? "重新控制" : label == "暂停" ? "停止控制" : "粘贴", action);
      button.ToolTip = tooltip; button.Padding = compact ? new Thickness(0) : new Thickness(14, 8, 14, 8); button.Margin = compact ? new Thickness(0, 0, 0, 4) : new Thickness(0, 0, 8, 0);
      if (compact) { button.Width = 44; button.Height = 40; button.FontSize = label.Length == 1 ? 23 : 11; button.Background = Brushes.Transparent; button.BorderThickness = new Thickness(0); }
      System.Windows.Automation.AutomationProperties.SetName(button, tooltip); panel.Children.Add(button);
    }
    async Task ResumeAfterFocus() { if(!resumeOnActivate||closed||conflict)return;resumeOnActivate=false;wantsControl=true;retryAt=DateTime.MinValue;await BeginControl(); }
    async Task RestartControl() { resumeOnActivate=false;wantsControl = true; ControlRequested = false; fallbackAccepted = false; conflict = false; retryAt = DateTime.MinValue; await BeginControl(); }
    public void ResumeFallback(bool accepted) { controlling = false; fallbackAccepted = accepted; wantsControl = accepted; ControlRequested = false; retryAt = DateTime.MinValue; }
    int MouseButton(System.Windows.Input.MouseButton button) { return button == System.Windows.Input.MouseButton.Right ? 2 : button == System.Windows.Input.MouseButton.Middle ? 1 : 0; }
    object Coordinates(Point p) {
      var bitmap = image.Source as BitmapSource; if (bitmap == null) return null;
      double scale = Math.Min(image.ActualWidth / bitmap.PixelWidth, image.ActualHeight / bitmap.PixelHeight);
      double width = bitmap.PixelWidth * scale, height = bitmap.PixelHeight * scale, left = (image.ActualWidth - width) / 2, top = (image.ActualHeight - height) / 2;
      if (width <= 0 || height <= 0 || (!image.IsMouseCaptured && (p.X < left || p.Y < top || p.X > left + width || p.Y > top + height))) return null;
      return new { kind = "move", x = Math.Max(0, Math.Min(1, (p.X - left) / width)), y = Math.Max(0, Math.Min(1, (p.Y - top) / height)) };
    }
    async void Run(Func<Task> action) { try { await action(); } catch (Exception ex) { status.Text = MainWindow.DisplayError(ex.Message); } }
    void FitScreen(double width, double height) {
      if (width < 1 || height < 1 || double.IsNaN(width) || double.IsNaN(height)) { width = 1280; height = 720; }
      var area = SystemParameters.WorkArea; bool phone = device.Platform == "android"; double chrome = phone ? 70 : 155, horizontal = phone ? 58 : 34;
      double scale = Math.Min(1, Math.Min((area.Width - horizontal - 24) / width, (area.Height - chrome - 24) / height));
      Width = Math.Max(MinWidth, width * scale + horizontal); Height = Math.Max(320, height * scale + chrome);
    }
    async Task BeginControl() {
      if (closed || controlling || !wantsControl || main.HandlingFallback || DateTime.UtcNow < retryAt) return;
      if (device.Platform == "android" && main.InputMode(device.Id) == "accessibility-limited" && !fallbackAccepted) {
        showingModeDialog = true; try { fallbackAccepted = main.ConfirmInputMode(device, this); } finally { showingModeDialog = false; }
        if (!fallbackAccepted) { wantsControl = false; status.Text = "备用控制未启用 · 可从右侧 ADB 开启增强控制"; return; }
      }
      int version = sessionVersion; retryAt = DateTime.UtcNow.AddMilliseconds(500);
      bool fresh = !ControlRequested; ControlRequested = true;
      try { await main.Client.Call("control", new { id = device.Id, capture = false, newSession = fresh, fallbackAccepted = fallbackAccepted }); if (closed || !wantsControl || version != sessionVersion) { await main.Client.Call("release"); return; } controlling = true; inputFailure=null; status.Text = "正在控制 · 点击画面后输入"; image.Focus(); }
      catch (Exception ex) { status.Text = MainWindow.DisplayError(ex.Message); if (ex.Message.StartsWith("CONTROL_CONFLICT:")) { conflict = true; wantsControl = false; } else if (!ex.Message.StartsWith("CONSENT_PENDING:") && !ex.Message.StartsWith("PERMISSION_REQUIRED:")) wantsControl = false; }
    }
    async Task Frames() {
      bool streaming = false, legacy = false, jpegOnly = false;
      while (!closed) {
        var ended=Json.Get(main.State,"controlEnded"); if(Json.Text(ended,"id")==device.Id && Json.Number(ended,"at",0)>=openedAt) { main.Notify("对方已结束控制"); Close(); break; }
        await BeginControl();
        if (conflict) { await Task.Delay(500); continue; }
        bool retry = false;
        try {
          if (!legacy && !streaming) { await main.Client.Call("stream-open", new { id = device.Id, jpegOnly = jpegOnly }); streaming = true; }
          if (closed) { await main.Client.Call("stream-close", new { id = device.Id }); break; }
          var frame = await main.Client.Call(streaming ? "stream-next" : "screen", new { id = device.Id });
          var bitmap = await Task.Run(() => MainWindow.DecodeImage(Json.Text(frame, "data")));
          if (!closed) { image.Source = bitmap; if (!fitted && bitmap != null) { fitted = true; if (Math.Abs(device.Width / device.Height - (double)bitmap.PixelWidth / bitmap.PixelHeight) > .1) FitScreen(bitmap.PixelWidth, bitmap.PixelHeight); }
            status.Text = (controlling ? "正在控制" : "仅查看") + (device.Platform == "android" && main.InputMode(device.Id) == "accessibility-limited" ? " · 无障碍备用（拖动受限）" : "") + (streaming ? " · " + (Json.Text(frame, "codec") == "h264" ? "H.264" : "兼容串流") + " · " + Json.Text(frame, "fps") + " fps" : " · 截图兼容模式");
            status.ToolTip = status.Text + " · " + (Json.Number(frame,"bitrate",4000000)/1000000).ToString("0.0") + " Mbps";
            if(inputFailure!=null && !controlling) {status.Text=inputFailure;status.ToolTip=inputFailure;}
          }
          if (legacy) await Task.Delay(50);
        }
        catch (Exception ex) {
          status.Text = MainWindow.DisplayError(ex.Message);
          if(ex.Message.StartsWith("CONTROL_ENDED:")) { main.Notify("对方已结束控制"); Close(); break; }
          if(ex.Message.StartsWith("CONTROL_CONFLICT:")) { conflict = true; wantsControl = false; controlling = false; }
          if (ex.Message.Contains("不支持") || ex.Message.Contains("接口不存在")) legacy = true;
          else if (!ex.Message.StartsWith("CONSENT_") && !ex.Message.StartsWith("PERMISSION_")) { if (jpegOnly) legacy = true; jpegOnly = true; }
          retry = true;
        }
        if (retry) { if (streaming) { try { await main.Client.Call("stream-close", new { id = device.Id }); } catch { } streaming = false; } await Task.Delay(1500); }
      }
    }
    async Task Release() {
      resumeOnActivate=false;
      wantsControl = false; fallbackAccepted = false; sessionVersion++; if (!controlling) return; controlling = false; pendingMove = null; image.ReleaseMouseCapture();
      try { await inputs; var state = await main.Client.Call("state"); if (Json.Text(state, "active") == device.Id) await main.Client.Call("release"); } catch { }
      status.Text = "已返回本机 · 查看模式";
    }
    async Task Send(object input) {
      if (!controlling || input == null) return;
      var before = inputs; var completion = new TaskCompletionSource<object>(); inputs = completion.Task;
      try { await before; if (controlling) await main.Client.Call("input", new { id = device.Id, input = input }); }
      catch (Exception ex) { controlling = false; wantsControl=false; inputFailure=MainWindow.DisplayError(ex.Message); status.Text=inputFailure;status.ToolTip=inputFailure; Run(async () => { await main.Client.Call("release"); }); }
      finally { completion.TrySetResult(null); }
    }
    async void FlushMove() { if (moveBusy) return; moveBusy = true; try { while (pendingMove != null && controlling) { var next = pendingMove; pendingMove = null; await Send(next); } } finally { moveBusy = false; } }
    void TextDialog() {
      if (!controlling) { status.Text = "请先开始控制"; return; }
      var panel = MainWindow.Column(); var box = MainWindow.Field(panel, "发送到目标应用的文本", ""); box.AcceptsReturn = true; box.Height = 120;
      var dialog = new Window { Title = "输入文本", Width = 480, SizeToContent = SizeToContent.Height, Owner = this, Resources = Resources, FontFamily = FontFamily, WindowStartupLocation = WindowStartupLocation.CenterOwner, }; WindowShell.Wrap(dialog, panel, false);
      panel.Children.Add(MainWindow.Button("发送", () => Run(async () => { if (!controlling || !wantsControl || conflict) throw new Exception("控制已停止，请关闭文字窗口后重新开始控制"); await main.Client.Call("text", new { id = device.Id, text = box.Text }); dialog.Close(); image.Focus(); })));
      showingModeDialog = true; try { dialog.ShowDialog(); } finally { showingModeDialog = false; }
    }
    public static string KeyCode(Key key) {
      if (key >= Key.A && key <= Key.Z) return "Key" + key;
      if (key >= Key.D0 && key <= Key.D9) return "Digit" + ((int)key - (int)Key.D0);
      if (key >= Key.NumPad0 && key <= Key.NumPad9) return "Numpad" + ((int)key - (int)Key.NumPad0);
      if (key >= Key.F1 && key <= Key.F12) return key.ToString();
      var names = new Dictionary<Key, string> { { Key.LeftCtrl, "ControlLeft" }, { Key.RightCtrl, "ControlRight" }, { Key.LeftShift, "ShiftLeft" }, { Key.RightShift, "ShiftRight" }, { Key.LeftAlt, "AltLeft" }, { Key.RightAlt, "AltRight" }, { Key.LWin, "MetaLeft" }, { Key.RWin, "MetaRight" }, { Key.Left, "ArrowLeft" }, { Key.Right, "ArrowRight" }, { Key.Up, "ArrowUp" }, { Key.Down, "ArrowDown" }, { Key.Back, "Backspace" }, { Key.Return, "Enter" }, { Key.Escape, "Escape" }, { Key.Tab, "Tab" }, { Key.Space, "Space" }, { Key.Delete, "Delete" }, { Key.Insert, "Insert" }, { Key.Home, "Home" }, { Key.End, "End" }, { Key.PageUp, "PageUp" }, { Key.PageDown, "PageDown" }, { Key.CapsLock, "CapsLock" }, { Key.Oem1, "Semicolon" }, { Key.OemPlus, "Equal" }, { Key.OemComma, "Comma" }, { Key.OemMinus, "Minus" }, { Key.OemPeriod, "Period" }, { Key.Oem2, "Slash" }, { Key.Oem3, "Backquote" }, { Key.Oem4, "BracketLeft" }, { Key.Oem5, "Backslash" }, { Key.Oem6, "BracketRight" }, { Key.Oem7, "Quote" } };
      string value; return names.TryGetValue(key, out value) ? value : null;
    }
  }
}
