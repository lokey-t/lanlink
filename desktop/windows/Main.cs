using System;
using System.Collections;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Markup;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Shapes;
using System.Windows.Threading;
using Forms = System.Windows.Forms;

namespace LanLink.Desktop {
  public sealed class Device {
    public string Id, Name, Platform; public bool Online, Local; public double Width = 1920, Height = 1080;
  }
  public class MainWindow : Window {
    public static string Root, DataDir; public static string[] Arguments;
    public readonly Client Client = new Client();
    public object State;
    public bool HandlingFallback { get { return showingFallback; } }
    readonly List<Device> devices = new List<Device>();
    readonly Dictionary<string, string> edges = new Dictionary<string, string>();
    readonly StackPanel bubbles = new StackPanel { Orientation = Orientation.Horizontal };
    readonly Canvas canvas = new Canvas { Width = 1000, Height = 590, Background = Brushes.Transparent, AllowDrop = true };
    readonly TextBlock footer = Text("正在连接设备服务…", 12, "#70809C");
    readonly TextBlock notice = Text("", 12, "#506383");
    readonly DispatcherTimer poll = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1.5) };
    Grid devicePage, settingsPage, adbHub, informationPage; Button permissionsNavigation, adbNavigation, informationNavigation; Border pageSurface, controlledBanner; Button homeNavigation, settingsNavigation, saveSettingsButton, stopInbound;
    readonly TextBlock controlledTitle = Text("", 12, "#456A63"); AdbPage adbPage; DevicePermissionsPage devicePermissionsPage;
    TextBox settingsName; CheckBox settingsMaster; readonly Dictionary<string, CheckBox> settingsPermissions = new Dictionary<string, CheckBox>();
    TextBlock settingsStatus; bool settingsDirty, loadingSettings; string settingsAccount = "";
    Window consentWindow, permissionWindow; string consentRequestId = ""; CheckBox startupOption; bool showingFallback;
    Forms.NotifyIcon tray; bool quitting, busy, dragging, testing; Point canvasOffset; string signature = "", selected = "";
    public static Brush Brush(string color) { return (Brush)new BrushConverter().ConvertFromString(color); }
    public static TextBlock Text(string text, double size = 14, string color = "#18243D") {
      return new TextBlock { Text = text, FontSize = size, Foreground = Brush(color), TextTrimming = TextTrimming.CharacterEllipsis, VerticalAlignment = VerticalAlignment.Center };
    }
    public static Button Button(string text, Action action) {
      var b = new Button { Content = text }; b.Click += delegate { action(); }; return b;
    }
    public static StackPanel Column() { return new StackPanel { Margin = new Thickness(28, 14, 28, 26) }; }
    public static TextBox Field(StackPanel p, string label, string value) {
      p.Children.Add(Text(label, 13, "#6C7B94")); var t = new TextBox { Text = value }; p.Children.Add(t); return t;
    }
    public static string DisplayError(string message) { return (message ?? "操作失败").Replace("CONTROL_CONFLICT: ", "").Replace("CONTROL_ENDED: ", "").Replace("CONSENT_PENDING: ", "").Replace("CONSENT_DENIED: ", "").Replace("PERMISSION_REQUIRED: ", ""); }
    public void Notify(string message) { message = DisplayError(message); notice.Text = message; notice.Visibility = string.IsNullOrEmpty(message) ? Visibility.Collapsed : Visibility.Visible; if (settingsStatus != null && settingsPage.Visibility == Visibility.Visible) settingsStatus.Text = message; }
    public async void Run(Func<Task> action) { try { await action(); } catch (Exception ex) { Notify(ex.Message); } }
    public MainWindow() {
      testing = Arguments.Contains("--self-test");
      Title = "LanLink"; Width = 1250; Height = 850; MinWidth = 980; MinHeight = 740;
      WindowStartupLocation = WindowStartupLocation.CenterScreen; FontFamily = new FontFamily("Microsoft YaHei UI"); FontSize = 14;
      Background = Brush("#F3F6FC");
      using (var theme = File.OpenRead(System.IO.Path.Combine(Root, "desktop/windows/Theme.xaml"))) Resources.MergedDictionaries.Add((ResourceDictionary)XamlReader.Load(theme));
      var grid = new Grid(); grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(184) }); grid.ColumnDefinitions.Add(new ColumnDefinition());
      var side = new DockPanel { LastChildFill = true, Margin = new Thickness(14, 18, 14, 18) }; grid.Children.Add(side);
      var identity = new StackPanel(); DockPanel.SetDock(identity, Dock.Bottom); side.Children.Add(identity);
      var controlledContent = new StackPanel(); controlledTitle.TextWrapping = TextWrapping.Wrap; controlledTitle.TextTrimming = TextTrimming.None; controlledTitle.LineHeight = 19; controlledContent.Children.Add(controlledTitle);
      stopInbound = Button("取消远程控制", () => Run(async () => { await Client.Call("stop-inbound"); await Refresh(); })); stopInbound.FontSize = 12; stopInbound.Padding = new Thickness(8); stopInbound.Margin = new Thickness(0, 10, 0, 0); stopInbound.BorderThickness = new Thickness(0); controlledContent.Children.Add(stopInbound);
      controlledBanner = new Border { CornerRadius = new CornerRadius(16), Background = Brush("#E4F1EA"), BorderBrush = Brush("#D3E7DB"), BorderThickness = new Thickness(1), Padding = new Thickness(12), Child = controlledContent, Visibility = Visibility.Collapsed }; identity.Children.Add(controlledBanner);
      var account = Button("", ShowInformation); informationNavigation = account; account.Content = WindowShell.Label("我的信息", "account"); account.HorizontalContentAlignment = HorizontalAlignment.Left; account.BorderThickness = new Thickness(0); account.Background = Brushes.Transparent; account.Margin = new Thickness(0, 12, 0, 0); identity.Children.Add(account);
      var nav = new StackPanel(); side.Children.Add(nav);
      var home = Button("", ShowDevices); homeNavigation = home; home.Content = WindowShell.Label("设备", "devices"); home.HorizontalContentAlignment = HorizontalAlignment.Left; home.Height = 46; home.Margin = new Thickness(0, 0, 0, 8); home.Background = Brush("#E1EAFB"); home.BorderThickness = new Thickness(0); nav.Children.Add(home);
      permissionsNavigation = Button("本组设备权限", ShowDevicePermissions); adbNavigation = Button("安卓 ADB 授权", ShowAdbHub);
      permissionsNavigation.Content=WindowShell.Label("本组设备权限","permissions");adbNavigation.Content=WindowShell.Label("安卓 ADB 授权","adb");
      foreach(var item in new[] { permissionsNavigation, adbNavigation }) { item.HorizontalContentAlignment=HorizontalAlignment.Left; item.Height=46; item.Margin=new Thickness(0,0,0,8); item.BorderThickness=new Thickness(0); item.Background=Brushes.Transparent; nav.Children.Add(item); }
      var settings = Button("", Settings); settingsNavigation = settings; settings.Content = WindowShell.Label("设置", "settings"); settings.HorizontalContentAlignment = HorizontalAlignment.Left; settings.Height = 46; settings.BorderThickness = new Thickness(0); settings.Background = Brushes.Transparent; nav.Children.Add(settings);
      var bodySurface = new Border { CornerRadius = new CornerRadius(22), Background = Brush("#F9FBFE"), Padding = new Thickness(26, 25, 26, 12), Margin = new Thickness(0, 0, 16, 16) }; Grid.SetColumn(bodySurface, 1); grid.Children.Add(bodySurface); pageSurface = bodySurface;
      var pages = new Grid(); bodySurface.Child = pages; var body = new Grid(); devicePage = body; pages.Children.Add(body);
      body.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto }); body.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
      body.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto }); body.RowDefinitions.Add(new RowDefinition());
      body.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto }); body.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
      var header = new DockPanel { Margin = new Thickness(0, 0, 0, 24) };
      var add = Button("", () => Run(Pairing)); add.Content = WindowShell.Label("添加设备", "plus"); add.Style = (Style)FindResource("Primary"); DockPanel.SetDock(add, Dock.Right); header.Children.Add(add);
      var title = Text("设备与布局", 26); title.FontWeight = FontWeights.SemiBold; header.Children.Add(title); body.Children.Add(header);
      var scroll = new ScrollViewer { Content = bubbles, HorizontalScrollBarVisibility = ScrollBarVisibility.Auto, VerticalScrollBarVisibility = ScrollBarVisibility.Disabled, Padding = new Thickness(1, 1, 1, 6) }; Grid.SetRow(scroll, 1); body.Children.Add(scroll);
      var hint = Text("拖动调整方位，点击设备进行操作", 12, "#8B97AB"); hint.Margin = new Thickness(0, 16, 0, 16); Grid.SetRow(hint, 2); body.Children.Add(hint);
      var viewbox = new Viewbox { Child = canvas, Stretch = Stretch.Uniform, HorizontalAlignment = HorizontalAlignment.Stretch, VerticalAlignment = VerticalAlignment.Center };
      var canvasSurface = new Border { CornerRadius = new CornerRadius(20), Background = Brush("#F1F5FC"), Child = viewbox, Padding = new Thickness(10) }; Grid.SetRow(canvasSurface, 3); body.Children.Add(canvasSurface);
      canvasSurface.SizeChanged += delegate { canvasSurface.Clip = new RectangleGeometry(new Rect(0, 0, canvasSurface.ActualWidth, canvasSurface.ActualHeight), 20, 20); };
      notice.Margin = new Thickness(4, 10, 4, 0); notice.TextWrapping = TextWrapping.Wrap; notice.Visibility = Visibility.Collapsed; Grid.SetRow(notice, 4); body.Children.Add(notice);
      var bottom = new DockPanel { Margin = new Thickness(4, 12, 0, 0) }; var back = Button("返回本机  ·  Ctrl + Alt + F12", () => Run(async () => { await Client.Call("release"); await Refresh(); })); back.Padding = new Thickness(10, 7, 10, 7); back.FontSize = 12; back.BorderThickness = new Thickness(0); back.Background = Brushes.Transparent; DockPanel.SetDock(back, Dock.Right); bottom.Children.Add(back); bottom.Children.Add(footer); Grid.SetRow(bottom, 5); body.Children.Add(bottom);
      WindowShell.Wrap(this, grid);
      canvas.Drop += Dropped; canvas.DragOver += delegate(object sender, DragEventArgs e) { e.Effects = e.Data.GetDataPresent("LanLinkDevice") ? DragDropEffects.Move : DragDropEffects.None; e.Handled = true; };
      SourceInitialized += delegate { ApplyGlass(); };
      Loaded += async delegate {
        try {
          MakeTray();
          string options = "--data \"" + DataDir + "\"";
          if (Arguments.Contains("--simulate") || (testing && !Arguments.Contains("--native"))) options += " --simulate";
          if (Arguments.Contains("--no-discovery") || testing) options += " --no-discovery";
          if (testing) options += " --port 0";
          Client.Failed = message => Dispatcher.BeginInvoke(new Action(delegate { poll.Stop(); Notify(message); foreach (var d in devices) if (!d.Local) d.Online = false; Draw(); }));
          await Client.Start(Root, options); await Refresh();
          poll.Tick += async delegate { await Refresh(); }; poll.Start();
          if (testing) await SelfTest();
        } catch (Exception ex) { Notify(ex.Message); if (testing) { File.WriteAllText(System.IO.Path.Combine(DataDir, "failure.txt"), ex.ToString()); Run(() => Quit(1)); } }
      };
      Closing += delegate(object sender, System.ComponentModel.CancelEventArgs e) { if (!quitting) { e.Cancel = true; Hide(); Run(async () => { await Client.Call("release"); }); } };
    }
    [DllImport("dwmapi.dll")] static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int size);
    [DllImport("dwmapi.dll")] static extern int DwmExtendFrameIntoClientArea(IntPtr hwnd, ref Margins value);
    [StructLayout(LayoutKind.Sequential)] struct Margins { public int Left, Right, Top, Bottom; }
    void ApplyGlass() {
      try {
        if (SystemParameters.HighContrast) return;
        var handle = new WindowInteropHelper(this).Handle; int corner = 2; DwmSetWindowAttribute(handle, 33, ref corner, 4);
        int backdrop = 3;
        if (DwmSetWindowAttribute(handle, 38, ref backdrop, 4) == 0) {
          var margins = new Margins { Left = -1, Right = -1, Top = -1, Bottom = -1 }; DwmExtendFrameIntoClientArea(handle, ref margins);
          HwndSource.FromHwnd(handle).CompositionTarget.BackgroundColor = Colors.Transparent; Background = Brushes.Transparent;
          ((Border)Content).Background = Brush("#98F1F5FB");
        }
      } catch { /* Older Windows retains an opaque readable surface. */ }
    }
    void MakeTray() {
      tray = new Forms.NotifyIcon { Icon = System.Drawing.SystemIcons.Application, Text = "LanLink · 局域网设备协作", Visible = true };
      var menu = new Forms.ContextMenuStrip(); menu.Items.Add("打开 LanLink", null, delegate { Show(); WindowState = WindowState.Normal; Activate(); });
      menu.Items.Add("返回本机键鼠", null, delegate { Run(async () => { await Client.Call("release"); }); });
      menu.Items.Add("结束对方控制", null, delegate { Run(async () => { await Client.Call("stop-inbound"); }); });
      menu.Items.Add("退出 LanLink", null, delegate { Run(async () => { await Quit(0); }); }); tray.ContextMenuStrip = menu;
      tray.DoubleClick += delegate { Show(); WindowState = WindowState.Normal; Activate(); };
    }
    public async Task Quit(int code) { if (quitting) return; quitting = true; poll.Stop(); if (tray != null) tray.Dispose(); await Client.Stop(); Application.Current.Shutdown(code); }
    async Task Refresh() {
      if (busy || quitting || dragging) return; busy = true;
      try {
        State = await Client.Call("state");
        RefreshRequests();
        var shutdownAt = Json.Number(State, "shutdownAt", 0);
        if (shutdownAt > 0) Notify("收到关机请求，请点击本机设备菜单中的“取消关机”撤销。剩余 " + Math.Max(0, Math.Ceiling((shutdownAt - (DateTime.UtcNow - new DateTime(1970, 1, 1)).TotalMilliseconds) / 1000)) + " 秒");
        string controlled = Json.Text(State, "owner"); var viewers = Json.Get(State,"viewers") as IEnumerable; if(controlled == "" && viewers != null) foreach(var id in viewers) { controlled=Convert.ToString(id); break; }
        UpdateControlledBanner(controlled);
        string active = Json.Text(State, "active");
        footer.Text = "键鼠控制：" + (active == "" ? "本机" : devices.FirstOrDefault(d => d.Id == active) == null ? "远端设备" : devices.First(d => d.Id == active).Name);
        if (Json.Flag(Json.Get(State, "capabilities"), "simulated")) footer.Text += "  ·  模拟模式";
        var transfer = Json.Get(State, "transfer"); if (transfer != null) footer.Text += "  ·  正在接收 " + Json.Text(transfer, "name");
        string sig = new System.Web.Script.Serialization.JavaScriptSerializer().Serialize(new object[] { Json.Get(State, "peers"), Json.Get(State, "knownDevices"), Json.Get(State, "edges"), Json.Get(State, "name"), Json.Get(State, "dwell"), Json.Get(State, "master"), active });
        if (sig == signature) return; signature = sig;
        devices.Clear(); edges.Clear(); foreach (var item in Json.Map(Json.Get(State, "edges"))) edges[item.Key] = Convert.ToString(item.Value);
        devices.Add(new Device { Id = Json.Text(State, "id"), Name = Json.Text(State, "name"), Platform = "win32", Online = true, Local = true });
        foreach (var item in Json.Map(Json.Get(State, "knownDevices"))) devices.Add(new Device { Id = item.Key, Name = Json.Text(item.Value, "name"), Platform = Json.Text(item.Value, "platform") });
        foreach (var item in Json.Get(State, "peers") as IEnumerable ?? new object[0]) {
          string id = Json.Text(item, "id"); var d = devices.FirstOrDefault(x => x.Id == id);
          if (d == null) { d = new Device { Id = id }; devices.Add(d); } d.Name = Json.Text(item, "name"); if (d.Name == "") d.Name = "已配对设备"; d.Platform = Json.Text(item, "platform"); d.Online = true; d.Width = Json.Number(Json.Get(item, "capabilities"), "width", 1920); d.Height = Json.Number(Json.Get(item, "capabilities"), "height", 1080);
        }
        foreach (var id in edges.Values.Where(x => x != "")) if (!devices.Any(d => d.Id == id)) devices.Add(new Device { Id = id, Name = "已设置设备", Platform = "" });
        Draw();
      } catch (Exception ex) { Notify(ex.Message); } finally { busy = false; }
    }
    public static FrameworkElement DeviceGlyph(Device d, double size) {
      var drawing = new Canvas { Width = size, Height = size, HorizontalAlignment = HorizontalAlignment.Center };
      var shape = new System.Windows.Shapes.Path { Stroke = Brush("#55709E"), StrokeThickness = 1.7, StrokeLineJoin = PenLineJoin.Round, Data = Geometry.Parse(d.Platform == "android" ? "M 9,2 L 23,2 Q 25,2 25,4 L 25,28 Q 25,30 23,30 L 9,30 Q 7,30 7,28 L 7,4 Q 7,2 9,2 M 13,26 L 19,26" : d.Platform == "darwin" ? "M 5,3 L 27,3 L 27,23 L 5,23 Z M 5,23 L 1,28 L 31,28 L 27,23" : "M 3,3 L 29,3 L 29,22 L 3,22 Z M 16,22 L 16,28 M 10,28 L 22,28"), RenderTransform = new ScaleTransform(size / 32, size / 32) }; drawing.Children.Add(shape); return drawing;
    }
    string Status(Device d) { return d.Local ? (Json.Flag(State, "master") ? "本机 · 主设备" : "本机") : Json.Text(State, "active") == d.Id ? "● 正在控制" : d.Online ? "● 在线" : "● 离线"; }
    Border DeviceView(Device d, bool compact) {
      var panel = new StackPanel { Orientation = compact ? Orientation.Horizontal : Orientation.Vertical, HorizontalAlignment = compact ? HorizontalAlignment.Left : HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
      var icon = DeviceGlyph(d, compact ? 28 : 42); icon.HorizontalAlignment = HorizontalAlignment.Center; icon.Margin = compact ? new Thickness(0, 0, 14, 0) : new Thickness(0, 0, 0, 8); panel.Children.Add(icon);
      var words = new StackPanel { VerticalAlignment = VerticalAlignment.Center }; var name = Text(d.Name, compact ? 14 : 16); name.MaxWidth = compact ? 136 : 206; name.FontWeight = FontWeights.SemiBold; name.HorizontalAlignment = compact ? HorizontalAlignment.Left : HorizontalAlignment.Center; words.Children.Add(name);
      var status = Text(Status(d), 12, d.Online && !d.Local ? "#399F80" : "#8290A7"); status.Margin = new Thickness(0, 6, 0, 0); status.HorizontalAlignment = compact ? HorizontalAlignment.Left : HorizontalAlignment.Center; words.Children.Add(status); panel.Children.Add(words);
      var border = new Border { Child = panel, Tag = d.Id, CornerRadius = new CornerRadius(18), BorderThickness = new Thickness(1), BorderBrush = Brush(selected == d.Id ? "#8DA7ED" : "#DFE6F1"), Background = Brush(d.Local ? "#E9F0FE" : "#FFFFFF"), Padding = new Thickness(compact ? 18 : 14), Cursor = Cursors.Hand, Focusable = true, ToolTip = d.Local ? "点击管理本机" : "点击操作；拖动到主设备四周设置布局" };
      System.Windows.Automation.AutomationProperties.SetName(border, d.Name + " " + Status(d));
      if (compact) { border.Width = 212; border.Height = 78; border.Margin = new Thickness(0, 0, 10, 0); }
      else { border.Width = d.Platform == "android" ? 164 : 248; border.Height = d.Platform == "android" ? 170 : 148; }
      Point start = new Point(); bool down = false;
      border.MouseLeftButtonDown += delegate(object sender, MouseButtonEventArgs e) { down = true; start = e.GetPosition(border); border.Focus(); };
      border.MouseMove += delegate(object sender, MouseEventArgs e) {
        if (!down || d.Local || e.LeftButton != MouseButtonState.Pressed) return;
        if ((e.GetPosition(border) - start).Length < 7) return;
        down = false; dragging = true;
        try { DragDrop.DoDragDrop(border, new DataObject("LanLinkDevice", d.Id), DragDropEffects.Move); }
        finally { dragging = false; }
      };
      border.MouseLeftButtonUp += delegate { if (down) { down = false; selected = d.Id; OpenMenu(d, border); } };
      border.MouseRightButtonUp += delegate { OpenMenu(d, border); };
      border.KeyDown += delegate(object sender, KeyEventArgs e) { if (e.Key == Key.Enter || e.Key == Key.Space) { OpenMenu(d, border); e.Handled = true; } };
      return border;
    }
    static readonly Dictionary<string, Point> positions = new Dictionary<string, Point> { { "local", new Point(376, 214) }, { "left", new Point(41, 214) }, { "right", new Point(711, 214) }, { "top", new Point(376, 12) }, { "bottom", new Point(376, 416) } };
    public static string Direction(Point point) { var delta = point - new Point(500, 288); return Math.Abs(delta.X) > Math.Abs(delta.Y) ? delta.X < 0 ? "left" : "right" : delta.Y < 0 ? "top" : "bottom"; }
    void Draw() {
      bubbles.Children.Clear(); canvas.Children.Clear();
      var used = new List<Point> { positions["local"] }; foreach (var edge in edges) if (edge.Value != "" && positions.ContainsKey(edge.Key)) used.Add(positions[edge.Key]);
      double layoutWidth = used.Max(p => p.X) - used.Min(p => p.X) + 248, layoutHeight = used.Max(p => p.Y) - used.Min(p => p.Y) + 148;
      canvas.Width = Math.Max(720, layoutWidth + 112); canvas.Height = Math.Max(380, layoutHeight + 96);
      canvasOffset = new Point((canvas.Width - layoutWidth) / 2 - used.Min(p => p.X), (canvas.Height - layoutHeight) / 2 - used.Min(p => p.Y));
      for (int x = 15; x < canvas.Width; x += 30) for (int y = 10; y < canvas.Height; y += 30) { var dot = new Ellipse { Width = 1.4, Height = 1.4, Fill = Brush("#D6E0F2"), IsHitTestVisible = false }; Canvas.SetLeft(dot, x); Canvas.SetTop(dot, y); canvas.Children.Add(dot); }
      foreach (var d in devices) bubbles.Children.Add(DeviceView(d, true));
      foreach (string edge in new[] { "left", "right", "top", "bottom" }) {
        string id; edges.TryGetValue(edge, out id); var d = devices.FirstOrDefault(x => x.Id == id && !x.Local);
        if (d == null) continue; var p = positions[edge];
        var line = new System.Windows.Shapes.Line { X1 = 500, Y1 = 288, X2 = p.X + 124, Y2 = p.Y + 74, Stroke = Brush(d.Online ? "#8CA7FF" : "#C7CFDC"), StrokeThickness = 1.4, IsHitTestVisible = false }; line.RenderTransform = new TranslateTransform(canvasOffset.X, canvasOffset.Y); canvas.Children.Add(line);
        var time = Button(Json.Text(State, "dwell") + " ms", Dwell); time.FontSize = 11; time.Padding = new Thickness(12, 5, 12, 5); time.Width = 78; time.Background = Brush("#FFFFFF"); time.BorderThickness = new Thickness(0);
        Canvas.SetLeft(time, (500 + p.X + 124) / 2 - 40); Canvas.SetTop(time, (288 + p.Y + 74) / 2 - 14); time.RenderTransform = new TranslateTransform(canvasOffset.X, canvasOffset.Y); canvas.Children.Add(time);
      }
      foreach (var d in devices) {
        string edge = d.Local ? "local" : edges.FirstOrDefault(x => x.Value == d.Id).Key;
        if (string.IsNullOrEmpty(edge) || !positions.ContainsKey(edge)) continue;
        var tile = DeviceView(d, false); Canvas.SetLeft(tile, positions[edge].X + (248 - tile.Width) / 2); Canvas.SetTop(tile, positions[edge].Y + (148 - tile.Height) / 2); tile.RenderTransform = new TranslateTransform(canvasOffset.X, canvasOffset.Y); canvas.Children.Add(tile);
      }
      if (devices.Count == 1) { var empty = Text("添加设备后，将顶部气泡拖至本机四周", 14, "#8393AF"); Canvas.SetLeft(empty, canvas.Width / 2 - 160); Canvas.SetTop(empty, canvas.Height / 2 + 115); canvas.Children.Add(empty); }
    }
    public static Dictionary<string, string> Place(Dictionary<string, string> current, string id, string edge) {
      var next = new Dictionary<string, string>(current); string old = next.FirstOrDefault(x => x.Value == id).Key, displaced; next.TryGetValue(edge, out displaced);
      foreach (var key in next.Keys.ToArray()) if (next[key] == id) next[key] = "";
      if (!string.IsNullOrEmpty(old) && old != edge) next[old] = displaced ?? ""; next[edge] = id; return next;
    }
    void Dropped(object sender, DragEventArgs e) {
      if (!e.Data.GetDataPresent("LanLinkDevice")) return;
      string id = Convert.ToString(e.Data.GetData("LanLinkDevice")); if (!devices.Any(d => d.Id == id && !d.Local)) return;
      var next = Place(edges, id, Direction(new Point(e.GetPosition(canvas).X - canvasOffset.X, e.GetPosition(canvas).Y - canvasOffset.Y))); e.Handled = true;
      Run(async () => { await Client.Call("settings", new { edges = next }); signature = ""; dragging = false; await Refresh(); Notify(Json.Flag(State, "master") ? "布局已保存" : "布局已保存；点击本机设备可启用键鼠主设备"); });
    }
    MenuItem Menu(string title, Func<Task> action, bool enabled) {
      string icon = title.Contains("关机") ? "power" : title.Contains("文件") ? "file" : title.Contains("粘贴") ? "paste" : title.Contains("布局") || title.Contains("侧") || title.Contains("方") ? "layout" : title.Contains("返回") ? "return" : title.Contains("键鼠") ? "pointer" : "devices";
      var item = new MenuItem { Header = title, IsEnabled = enabled, Icon = WindowShell.Glyph(icon, 18) }; item.Click += delegate { Run(action); }; return item;
    }
    public ContextMenu BuildMenu(Device d) {
      var menu = new ContextMenu { Resources = Resources }; WindowShell.GlassMenu(menu, this); menu.Items.Add(new MenuItem { Header = d.Name, FontWeight = FontWeights.SemiBold, FontSize = 15, IsHitTestVisible = false, Focusable = false, Icon = DeviceGlyph(d, 19) }); menu.Items.Add(new Separator());
      menu.Items.Add(Menu("设备详情与权限", () => { ShowDevicePermissions(); return Task.FromResult(0); }, true));
      if (d.Local) {
        menu.Items.Add(Menu(Json.Flag(State, "master") ? "停用键鼠主设备" : "设为键鼠主设备", async () => { await Client.Call("settings", new { master = !Json.Flag(State, "master") }); await Refresh(); }, true));
        menu.Items.Add(Menu("返回本机键鼠", async () => { await Client.Call("release"); }, true));
        menu.Items.Add(Menu("取消关机", async () => { await Client.Call("cancel-shutdown"); Notify("关机已取消"); }, Json.Number(State, "shutdownAt", 0) > 0));
        menu.Items.Add(Menu("组与配对", Pairing, true));
      } else {
        menu.Items.Add(Menu("重置此设备的本机授权", async () => { await Client.Call("consent-revoke", new { id = d.Id }); Notify("授权已重置，对方下次控制本机时需重新同意"); }, Json.Get(Json.Get(State, "deviceGrants"), d.Id) != null));
        menu.Items.Add(Menu("远程控制", async () => { var remote = Application.Current.Windows.OfType<RemoteWindow>().FirstOrDefault(); if (remote == null) { remote = new RemoteWindow(this, d); remote.Show(); } else remote.Activate(); await Task.FromResult(0); }, d.Online));
        menu.Items.Add(Menu("切换键鼠到此设备", async () => { if (!ConfirmInputMode(d, this)) return; await Client.Call("control", new { id = d.Id, capture = true, newSession = true, fallbackAccepted = InputMode(d.Id) == "accessibility-limited" }); }, d.Online));
        if (d.Platform == "android") menu.Items.Add(Menu("ADB 键鼠授权", () => { ShowAdb(d); return Task.FromResult(0); }, d.Online));
        menu.Items.Add(Menu("粘贴共享内容", async () => { await Client.Call("device-paste", new { id = d.Id }); Notify("已向 " + d.Name + " 粘贴共享内容"); }, d.Online));
        menu.Items.Add(Menu("发送文件…", async () => {
          var dialog = new Microsoft.Win32.OpenFileDialog { Multiselect = true, Title = "选择文件：将通过剪贴板粘贴到目标应用" };
          if (dialog.ShowDialog(this) != true) return;
          Notify("正在传送文件，请确保目标应用支持文件粘贴…"); await Client.Call("send-files", new { id = d.Id, files = dialog.FileNames }); Notify("文件已送达；目标应用需支持文件粘贴");
        }, d.Online));
        menu.Items.Add(new Separator());
        var layout = new MenuItem { Header = "调整方位", Icon = WindowShell.Glyph("layout", 18) }; foreach (var pair in new[] { new[] { "left", "左侧" }, new[] { "right", "右侧" }, new[] { "top", "上方" }, new[] { "bottom", "下方" } }) { string edge = pair[0]; layout.Items.Add(Menu(pair[1], async () => { await Client.Call("settings", new { edges = Place(edges, d.Id, edge) }); await Refresh(); Notify("布局已保存"); }, true)); } menu.Items.Add(layout);
        menu.Items.Add(Menu("移出设备…", async () => { if (!Confirm(this,"移出设备","将 "+d.Name+" 移出组？该设备需要重新配对，其他离线成员会保留身份。")) return; await Client.Call("device-remove",new { id=d.Id }); await Refresh(); Notify("设备已移出组"); },true));
        menu.Items.Add(Menu("移出布局", async () => { var next = new Dictionary<string, string>(edges); foreach (var key in next.Keys.ToArray()) if (next[key] == d.Id) next[key] = ""; await Client.Call("settings", new { edges = next }); await Refresh(); }, edges.ContainsValue(d.Id)));
        if (d.Platform == "win32" || d.Platform == "darwin") {
          menu.Items.Add(new Separator()); menu.Items.Add(Menu("关机…", async () => { if (!Confirm(this, "确认关机", "请求 " + d.Name + " 在 30 秒后关机？请先保存目标设备中的工作。")) return; await Client.Call("shutdown", new { id = d.Id }); Notify("已发送关机请求，可在设备菜单中取消"); }, d.Online));
          menu.Items.Add(Menu("取消关机", async () => { await Client.Call("cancel-shutdown", new { id = d.Id }); Notify("已取消关机请求"); }, d.Online));
        }
      }
      return menu;
    }
    void OpenMenu(Device d, FrameworkElement anchor) { selected = d.Id; foreach (var card in bubbles.Children.OfType<Border>()) card.BorderBrush = Brush(Convert.ToString(card.Tag) == selected ? "#8DA7ED" : "#E1E7F0"); var menu = BuildMenu(d); menu.VerticalOffset = 8; menu.PlacementTarget = anchor; menu.Placement = PlacementMode.Bottom; menu.IsOpen = true; }
    Window Dialog(string title, StackPanel panel, double width = 500) {
      var window = new Window { Title = title, Owner = this, Width = width, SizeToContent = SizeToContent.Height, MaxHeight = Math.Max(500, SystemParameters.WorkArea.Height - 100), WindowStartupLocation = WindowStartupLocation.CenterOwner, FontFamily = FontFamily, Resources = Resources };
      var content = new Border { CornerRadius = new CornerRadius(16), Background = Brush("#F9FBFE"), Margin = new Thickness(12, 0, 12, 12), Child = new ScrollViewer { Content = panel, VerticalScrollBarVisibility = ScrollBarVisibility.Auto } };
      WindowShell.Wrap(window, content, false); return window;
    }
    Window ConfirmationWindow(Window owner, string title, string message) {
      var panel = Column(); var heading = Text(title, 22); heading.Margin = new Thickness(0, 0, 0, 16); panel.Children.Add(heading);
      var text = Text(message, 14, "#71819A"); text.TextWrapping = TextWrapping.Wrap; text.Margin = new Thickness(0, 0, 0, 26); panel.Children.Add(text);
      var window = Dialog(title, panel, 460); window.Owner = owner;
      var buttons = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right };
      var cancel = Button("取消", () => { window.DialogResult = false; }); cancel.Width = 104; cancel.Margin = new Thickness(0, 0, 10, 0); cancel.IsCancel = true; buttons.Children.Add(cancel);
      var accept = Button("确认", () => { window.DialogResult = true; }); accept.Width = 104; accept.Style = (Style)FindResource("Primary"); buttons.Children.Add(accept); panel.Children.Add(buttons); return window;
    }
    bool Confirm(Window owner, string title, string message) { return ConfirmationWindow(owner, title, message).ShowDialog() == true; }
    void UpdateControlledBanner(string id) {
      controlledBanner.Visibility = string.IsNullOrEmpty(id) ? Visibility.Collapsed : Visibility.Visible;
      var peer = (Json.Get(State, "peers") as IEnumerable ?? new object[0]).Cast<object>().FirstOrDefault(p => Json.Text(p, "id") == id);
      string name = Json.Text(peer, "name"); if (name == "") name = "其他设备";
      controlledTitle.Text = "正在被控制\n" + name + " 正在操作或查看本机";
    }
    public string InputMode(string id) {
      var peer = (Json.Get(State, "peers") as IEnumerable ?? new object[0]).Cast<object>().FirstOrDefault(p => Json.Text(p, "id") == id);
      return Json.Text(Json.Get(peer, "capabilities"), "inputMode");
    }
    public bool ConfirmInputMode(Device d, Window owner) {
      if (d.Platform != "android" || InputMode(d.Id) != "accessibility-limited") return true;
      return ConfirmFallback(owner);
    }
    public bool ConfirmFallback(Window owner) {
      return Confirm(owner, "使用无障碍备用控制？", "此手机尚未启用 Root / ADB 增强控制。无障碍备用模式支持点击、长按和滑动；拖动会在松开鼠标后执行，悬停、游戏和复杂快捷键受限。此旧版手机请升级到 0.3.9 并启用 ADB / Root 输入助手。\n\n可在设备菜单中选择“ADB 键鼠授权”开启增强控制。继续使用备用模式？");
    }
    async Task HandleFallbackNotice(object fallback) {
      if (showingFallback) return; showingFallback = true;
      try {
        var remote = Application.Current.Windows.OfType<RemoteWindow>().FirstOrDefault(w => w.DeviceId == Json.Text(fallback, "id"));
        Show(); Activate(); bool accepted = ConfirmFallback(this);
        await Client.Call("fallback-dismiss");
        if (remote != null) { remote.ResumeFallback(accepted); if (accepted) remote.Activate(); }
        else if (accepted) await Client.Call("control", new { id = Json.Text(fallback, "id"), edge = Json.Text(fallback, "edge"), capture = Json.Flag(fallback, "capture"), newSession = true, fallbackAccepted = true });
      } finally { showingFallback = false; }
    }
    void Navigate(Button active) {
      foreach(var item in new[] {homeNavigation,permissionsNavigation,adbNavigation,settingsNavigation,informationNavigation}) if(item!=null) item.Background=item==active?Brush("#E1EAFB"):Brushes.Transparent;
      if(adbHub!=null) adbHub.Visibility=Visibility.Collapsed;
      if(informationPage!=null) informationPage.Visibility=Visibility.Collapsed;
    }
    Grid SimplePage(string title, out StackPanel body) {
      var page=new Grid(); page.RowDefinitions.Add(new RowDefinition{Height=GridLength.Auto});page.RowDefinitions.Add(new RowDefinition());
      var heading=Text(title,26);heading.Margin=new Thickness(0,0,0,22);page.Children.Add(heading);
      body=new StackPanel();var scroll=new ScrollViewer{Content=body,VerticalScrollBarVisibility=ScrollBarVisibility.Auto};Grid.SetRow(scroll,1);page.Children.Add(scroll);return page;
    }
    void HideContent() { devicePage.Visibility=Visibility.Collapsed;if(settingsPage!=null)settingsPage.Visibility=Visibility.Collapsed;if(adbPage!=null)adbPage.Visibility=Visibility.Collapsed;if(devicePermissionsPage!=null)devicePermissionsPage.Visibility=Visibility.Collapsed; }
    public void ShowAdbHub() {
      Navigate(adbNavigation);HideContent();var pages=(Grid)pageSurface.Child;if(adbHub!=null)pages.Children.Remove(adbHub);
      StackPanel body;adbHub=SimplePage("安卓 ADB 授权",out body);pages.Children.Add(adbHub);
      var hint=Text("选择本组手机，为其启用键鼠操作与自动恢复。",13,"#7B879D");hint.Margin=new Thickness(0,0,0,18);body.Children.Add(hint);
      foreach(var d in devices.Where(x=>x.Platform=="android")) { var phone=d;var card=new StackPanel();card.Children.Add(Text(phone.Name+(phone.Online?" · 在线":" · 离线"),16));var button=Button("管理授权",()=>ShowAdb(phone));button.IsEnabled=phone.Online;button.HorizontalAlignment=HorizontalAlignment.Left;button.Margin=new Thickness(0,16,0,0);card.Children.Add(button);body.Children.Add(SettingsCard("安卓设备",card)); }
      if(!devices.Any(x=>x.Platform=="android"))body.Children.Add(Text("本组暂无安卓设备，请先添加手机。",14,"#7B879D"));
      body.Children.Add(Button("刷新设备",()=>Run(async()=>{await Refresh();ShowAdbHub();})));
    }
    void ShowInformation() {
      Navigate(informationNavigation);HideContent();var pages=(Grid)pageSurface.Child;if(informationPage!=null)pages.Children.Remove(informationPage);
      StackPanel body;informationPage=SimplePage("我的信息",out body);pages.Children.Add(informationPage);
      var info=new StackPanel();foreach(var entry in new[]{new[]{"设备名称",Json.Text(State,"name")},new[]{"设备标识",Json.Text(State,"id")},new[]{"所属组",Json.Text(State,"groupId")}}){info.Children.Add(Text(entry[0],12,"#7B879D"));var value=Text(entry[1],16);value.TextWrapping=TextWrapping.Wrap;value.TextTrimming=TextTrimming.None;value.Margin=new Thickness(0,8,0,20);info.Children.Add(value);}body.Children.Add(SettingsCard("本机身份",info));
      var join=Button("添加设备 / 管理组",()=>Run(Pairing));join.HorizontalAlignment=HorizontalAlignment.Left;body.Children.Add(join);
    }
    public void ShowDevicePermissions() {
      Navigate(permissionsNavigation);
      if (devicePermissionsPage == null) { devicePermissionsPage = new DevicePermissionsPage(this, ShowDevices); ((Grid)pageSurface.Child).Children.Add(devicePermissionsPage); }
      devicePage.Visibility = Visibility.Collapsed;
      if(settingsPage != null) settingsPage.Visibility = Visibility.Collapsed;
      if(adbPage != null) adbPage.Visibility = Visibility.Collapsed;
      devicePermissionsPage.Visibility = Visibility.Visible;
      Run(devicePermissionsPage.Refresh);
    }
    public void ShowAdb(Device device, bool scan = true) {
      Navigate(adbNavigation);
      if (devicePermissionsPage != null) devicePermissionsPage.Visibility = Visibility.Collapsed;
      if (settingsPage == null) BuildSettingsPage();
      if (adbPage == null) { adbPage = new AdbPage(this, ShowAdbHub); ((Grid)pageSurface.Child).Children.Add(adbPage); }
      devicePage.Visibility = Visibility.Collapsed; settingsPage.Visibility = Visibility.Collapsed; adbPage.Visibility = Visibility.Visible;
      homeNavigation.Background = Brush("#E1EAFB"); settingsNavigation.Background = Brushes.Transparent;
      adbPage.Open(device, scan); Navigate(adbNavigation);
    }
    void Dwell() {
      var panel = Column(); panel.Children.Add(Text("边缘切换", 22)); var value = Field(panel, "停留时间（毫秒，所有边缘共用）", Json.Text(State, "dwell")); var window = Dialog("切换时间", panel);
      panel.Children.Add(Button("保存", () => Run(async () => { int ms; if (!int.TryParse(value.Text, out ms) || ms < 100 || ms > 3000) throw new Exception("请输入 100–3000 毫秒"); await Client.Call("settings", new { dwell = ms }); window.Close(); await Refresh(); Notify("切换时间已保存"); }))); window.ShowDialog();
    }
    static readonly string[] PermissionKeys = { "input", "screen", "clipboard", "shutdown" };
    static readonly string[] PermissionLabels = { "键鼠操作", "查看屏幕", "剪贴板与文件", "远程关机" };
    string StartupCommand() { return "\"" + System.Reflection.Assembly.GetExecutingAssembly().Location + "\" --data \"" + (DataDir.EndsWith("\\") ? DataDir + "\\" : DataDir) + "\""; }
    bool StartupEnabled() { using (var key = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run")) return key != null && String.Equals(Convert.ToString(key.GetValue("LanLink")), StartupCommand(), StringComparison.OrdinalIgnoreCase); }
    void SetStartup(bool enabled) {
      using (var key = Microsoft.Win32.Registry.CurrentUser.CreateSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run")) {
        if (enabled) key.SetValue("LanLink", StartupCommand(), Microsoft.Win32.RegistryValueKind.String);
        else key.DeleteValue("LanLink", false);
      }
    }
    void RefreshRequests() {
      var fallback = Json.Get(State, "fallbackNotice"); if (fallback != null && !showingFallback) Run(() => HandleFallbackNotice(fallback));
      var requests = (Json.Get(State, "consentRequests") as IEnumerable ?? new object[0]).Cast<object>().ToList();
      if (consentWindow != null && !requests.Any(r => Json.Text(r, "requestId") == consentRequestId)) { consentWindow.Close(); consentWindow = null; }
      if (consentWindow == null && requests.Count > 0) {
        var request = requests[0]; consentRequestId = Json.Text(request, "requestId");
        var panel = Column(); var message = Text(Json.Text(request, "name") + " 希望控制这台电脑", 20); message.TextWrapping = TextWrapping.Wrap; panel.Children.Add(message);
        var hint = Text("选择允许的功能。同意后记住此设备，以后无需重复确认。", 13, "#70809C"); hint.TextWrapping = TextWrapping.Wrap; hint.Margin = new Thickness(0, 14, 0, 14); panel.Children.Add(hint);
        var checks = new Dictionary<string, CheckBox>();
        for (int i = 0; i < PermissionKeys.Length; i++) { var check = new CheckBox { Content = PermissionLabels[i], IsChecked = true, Margin = new Thickness(0, 9, 0, 9) }; checks[PermissionKeys[i]] = check; panel.Children.Add(check); }
        var window = new Window { Title = "首次控制确认", Width = 460, SizeToContent = SizeToContent.Height, Resources = Resources, FontFamily = FontFamily, WindowStartupLocation = WindowStartupLocation.CenterScreen }; WindowShell.Wrap(window, panel, false); consentWindow = window;
        bool decided = false;
        Action<bool> decide = accept => Run(async () => { panel.IsEnabled = false; try { await Client.Call("consent-decide", new { id = Json.Text(request, "id"), requestId = Json.Text(request, "requestId"), accepted = accept, permissions = checks.ToDictionary(p => p.Key, p => p.Value.IsChecked == true) }); decided = true; window.Close(); } finally { panel.IsEnabled = true; } });
        var row = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 20, 0, 0) }; row.Children.Add(Button("暂不允许", () => decide(false))); var yes = Button("同意并记住", () => decide(true)); yes.Style = (Style)FindResource("Primary"); yes.Margin = new Thickness(12, 0, 0, 0); row.Children.Add(yes); panel.Children.Add(row);
        window.Closed += delegate { if (consentWindow == window) consentWindow = null; if (!decided) Run(async () => { try { await Client.Call("consent-decide", new { id = Json.Text(request, "id"), requestId = Json.Text(request, "requestId"), accepted = false }); } catch {} }); };
        window.Show(); window.Activate();
      }
      var notice = Json.Get(State, "permissionNotice");
      if (notice != null && permissionWindow == null) {
        var panel = Column(); panel.Children.Add(Text("协作权限", 22)); var intro = Text(Json.Text(notice, "name") + " 请求协作，请开启缺少的权限。", 13, "#70809C"); intro.TextWrapping = TextWrapping.Wrap; panel.Children.Add(intro);
        var rows = new StackPanel(); panel.Children.Add(rows);
        var window = new Window { Title = "开启协作权限", Width = 490, SizeToContent = SizeToContent.Height, Resources = Resources, FontFamily = FontFamily, WindowStartupLocation = WindowStartupLocation.CenterScreen }; WindowShell.Wrap(window, panel, false); permissionWindow = window;
        var timer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
        Action render = delegate { rows.Children.Clear(); for (int i = 0; i < PermissionKeys.Length; i++) { string key = PermissionKeys[i]; bool allowed = Json.Flag(Json.Get(State, "permissions"), key); bool osAllowed = !(Json.Get(Json.Get(State, "capabilities"), key) is bool) || Json.Flag(Json.Get(State, "capabilities"), key); var row = new DockPanel { Margin = new Thickness(0, 12, 0, 0) }; var button = Button(allowed && osAllowed ? "已授权" : "开启权限", () => Run(async () => { await Client.Call("settings", new { permissions = new Dictionary<string, bool> { { key, true } } }); await Refresh(); })); button.IsEnabled = !allowed; DockPanel.SetDock(button, Dock.Right); row.Children.Add(button); row.Children.Add(Text(PermissionLabels[i] + (allowed && !osAllowed ? " · 系统暂不可用" : ""))); rows.Children.Add(row); } };
        timer.Tick += delegate { render(); }; render(); timer.Start();
        window.Closed += delegate { timer.Stop(); permissionWindow = null; Run(async () => { await Client.Call("permission-dismiss"); }); }; window.Show();
      }
    }
    void ShowDevices() {
      Navigate(homeNavigation);
      if (devicePermissionsPage != null) devicePermissionsPage.Visibility = Visibility.Collapsed;
      devicePage.Visibility = Visibility.Visible;
      if (settingsPage != null) settingsPage.Visibility = Visibility.Collapsed;
      if (adbPage != null) adbPage.Visibility = Visibility.Collapsed;
      homeNavigation.Background = Brush("#E1EAFB"); settingsNavigation.Background = Brushes.Transparent;
    }
    void Settings() {
      Navigate(settingsNavigation);
      if (devicePermissionsPage != null) devicePermissionsPage.Visibility = Visibility.Collapsed;
      if (State == null) { Notify("正在连接设备服务，请稍后打开设置"); return; }
      if (settingsPage == null) BuildSettingsPage();
      if (!settingsDirty || settingsAccount != Json.Text(State, "groupId")) LoadSettingsDraft();
      devicePage.Visibility = Visibility.Collapsed; settingsPage.Visibility = Visibility.Visible;
      if (adbPage != null) adbPage.Visibility = Visibility.Collapsed;
      homeNavigation.Background = Brushes.Transparent; settingsNavigation.Background = Brush("#E1EAFB");
    }
    Border SettingsCard(string title, StackPanel content) {
      var heading = Text(title, 16); heading.FontWeight = FontWeights.SemiBold; heading.Margin = new Thickness(0, 0, 0, 14);
      content.Children.Insert(0, heading);
      return new Border { CornerRadius = new CornerRadius(18), Background = Brushes.White, BorderBrush = Brush("#E5EBF5"), BorderThickness = new Thickness(1), Padding = new Thickness(22, 18, 22, 18), Margin = new Thickness(0, 0, 0, 16), Child = content };
    }
    void MarkSettingsDirty() { if (loadingSettings) return; settingsDirty = true; settingsStatus.Text = "有未保存的更改 · 切换页面会保留草稿"; }
    void LoadSettingsDraft() {
      loadingSettings = true;
      settingsName.Text = Json.Text(State, "name"); settingsMaster.IsChecked = Json.Flag(State, "master");
      foreach (var pair in settingsPermissions) pair.Value.IsChecked = Json.Flag(Json.Get(State, "permissions"), pair.Key);
      settingsAccount = Json.Text(State, "groupId"); settingsDirty = false; loadingSettings = false;
      settingsStatus.Text = "修改后点击保存；切换设备页面不会丢失草稿";
    }
    async Task SaveSettings() {
      saveSettingsButton.IsEnabled = false; settingsName.IsEnabled = false; settingsMaster.IsEnabled = false; foreach (var field in settingsPermissions.Values) field.IsEnabled = false;
      try {
        var permissions = settingsPermissions.ToDictionary(p => p.Key, p => p.Value.IsChecked == true);
        await Client.Call("settings", new { name = settingsName.Text, master = settingsMaster.IsChecked == true, permissions = permissions });
        State = await Client.Call("state"); LoadSettingsDraft(); settingsStatus.Text = "本机设置已保存"; signature = ""; await Refresh();
      } catch (Exception ex) { settingsStatus.Text = ex.Message; }
      finally { saveSettingsButton.IsEnabled = true; settingsName.IsEnabled = true; settingsMaster.IsEnabled = true; foreach (var field in settingsPermissions.Values) field.IsEnabled = true; }
    }
    void BuildSettingsPage() {
      settingsPage = new Grid { Visibility = Visibility.Collapsed }; ((Grid)pageSurface.Child).Children.Add(settingsPage);
      settingsPage.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto }); settingsPage.RowDefinitions.Add(new RowDefinition()); settingsPage.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
      var header = new DockPanel { Margin = new Thickness(0, 0, 0, 22) };
      saveSettingsButton = Button("保存设置", () => Run(SaveSettings)); saveSettingsButton.Style = (Style)FindResource("Primary"); saveSettingsButton.VerticalAlignment = VerticalAlignment.Top; DockPanel.SetDock(saveSettingsButton, Dock.Right); header.Children.Add(saveSettingsButton);
      var titles = new StackPanel(); var heading = Text("设置", 26); heading.FontWeight = FontWeights.SemiBold; titles.Children.Add(heading);
      var intro = Text("管理设备名称、主设备与启动选项", 12, "#8B97AB"); intro.Margin = new Thickness(0, 8, 0, 0); titles.Children.Add(intro); header.Children.Add(titles); settingsPage.Children.Add(header);
      var sections = new StackPanel { MaxWidth = 820, HorizontalAlignment = HorizontalAlignment.Stretch, Margin = new Thickness(0, 0, 12, 0) };
      var basic = new StackPanel(); settingsName = Field(basic, "设备名称", ""); settingsName.Margin = new Thickness(0, 8, 0, 14); settingsName.MaxWidth = 450; settingsName.HorizontalAlignment = HorizontalAlignment.Left; settingsName.MinWidth = 280; settingsName.TextChanged += delegate { MarkSettingsDirty(); };
      settingsMaster = new CheckBox { Content = "本机作为键鼠主设备", Margin = new Thickness(0, 4, 0, 2) }; settingsMaster.Checked += delegate { MarkSettingsDirty(); }; settingsMaster.Unchecked += delegate { MarkSettingsDirty(); }; basic.Children.Add(settingsMaster); sections.Children.Add(SettingsCard("基本信息", basic));
      var startup = new StackPanel(); startupOption = new CheckBox { Content = "登录 Windows 后自动启动 LanLink", IsChecked = StartupEnabled(), Margin = new Thickness(0, 8, 0, 8) };
      startupOption.Click += delegate { try { SetStartup(startupOption.IsChecked == true); settingsStatus.Text = startupOption.IsChecked == true ? "已开启登录自启动" : "已关闭登录自启动"; } catch (Exception ex) { startupOption.IsChecked = StartupEnabled(); settingsStatus.Text = ex.Message; } };
      startup.Children.Add(startupOption); startup.Children.Add(Text("此项立即生效；移动软件目录后请重新开启。", 12, "#8B97AB")); sections.Children.Add(SettingsCard("启动", startup));
      var tools = new StackPanel(); var log = new StackPanel { Visibility = Visibility.Collapsed, Margin = new Thickness(0, 12, 0, 0) };
      var actions = new StackPanel { Orientation = Orientation.Horizontal };
      var logs = Button("运行记录", delegate {
        log.Visibility = log.Visibility == Visibility.Visible ? Visibility.Collapsed : Visibility.Visible; log.Children.Clear();
        foreach (var item in Json.Get(State, "logs") as IEnumerable ?? new object[0]) { var line = Text(Json.Text(item, "message"), 12, "#71819A"); line.TextWrapping = TextWrapping.Wrap; line.Margin = new Thickness(0, 5, 0, 5); log.Children.Add(line); }
        if (log.Children.Count == 0) log.Children.Add(Text("暂无运行记录", 12, "#8B97AB"));
      }); logs.Margin = new Thickness(0, 0, 10, 0); actions.Children.Add(logs); actions.Children.Add(Button("退出 LanLink", () => Run(() => Quit(0)))); tools.Children.Add(actions); tools.Children.Add(log); sections.Children.Add(SettingsCard("应用", tools));
      var scroll = new ScrollViewer { Content = sections, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled }; Grid.SetRow(scroll, 1); settingsPage.Children.Add(scroll);
      settingsStatus = Text("", 12, "#71819A"); settingsStatus.TextWrapping = TextWrapping.Wrap; settingsStatus.Margin = new Thickness(4, 14, 4, 8); Grid.SetRow(settingsStatus, 2); settingsPage.Children.Add(settingsStatus);
    }
    Task Pairing() { if (State != null) PairingWindow().ShowDialog(); return Task.FromResult(0); }
    PairingDialog PairingWindow() {
      PairingDialog window = null;
      window = new PairingDialog(this, Client, formed => Confirm(window, formed ? "解散当前组并加入其他组？" : "加入其他组？", formed ? "原组所有设备都会退出，并断开控制。离线设备收到解散通知后退出。先验证新组配对码，成功后才解散原组。" : "本机将加入对方的组，在同一局域网内自动连接。"), async () => { signature = ""; await Refresh(); Notify("已加入组，正在连接设备"); });
      return window;
    }
    public static BitmapImage DecodeImage(string base64) { var bitmap = new BitmapImage(); using (var stream = new MemoryStream(Convert.FromBase64String(base64))) { bitmap.BeginInit(); bitmap.CacheOption = BitmapCacheOption.OnLoad; bitmap.StreamSource = stream; bitmap.EndInit(); bitmap.Freeze(); } return bitmap; }
    async Task SelfTest() {
      poll.Stop();
      await Client.Call("settings", new { name = "工作电脑", dwell = 500, master = true }); State = await Client.Call("state");
      if (Arguments.Contains("--native") && !Json.Flag(Json.Get(State, "capabilities"), "host")) throw new Exception("Native host capability unavailable");
      if (Json.Text(State, "name") != "工作电脑" || Json.Number(State, "dwell", 0) != 500) throw new Exception("Native settings integration failed");
      var qr = await Client.Call("pairing", new { reveal = true }); if (!System.Text.RegularExpressions.Regex.IsMatch(Json.Text(qr, "code"), "^[0-9]{8}$")) throw new Exception("Pairing failed");
      string image = Json.Text(qr, "qr"); if (DecodeImage(image.Substring(image.IndexOf(',') + 1)).PixelWidth < 100) throw new Exception("QR decode failed");
      var sample = Place(new Dictionary<string, string> { { "left", "a" }, { "right", "b" } }, "a", "right"); if (sample["left"] != "b" || sample["right"] != "a") throw new Exception("Occupied edge swap failed");
      if (Direction(new Point(950, 288)) != "right" || Direction(new Point(500, 20)) != "top") throw new Exception("Direction mapping failed");
      devices.Clear(); devices.Add(new Device { Id = Json.Text(State, "id"), Name = "工作电脑", Platform = "win32", Local = true, Online = true });
      devices.Add(new Device { Id = "preview-mac", Name = "MacBook Pro", Platform = "darwin", Online = true }); devices.Add(new Device { Id = "preview-phone", Name = "Pixel 9", Platform = "android", Online = true }); devices.Add(new Device { Id = "preview-offline", Name = "客厅电脑", Platform = "win32" });
      if (BuildMenu(devices[3]).Items.OfType<MenuItem>().Any(m => Convert.ToString(m.Header) == "远程控制" && m.IsEnabled)) throw new Exception("Offline actions must be disabled");
      edges["right"] = "preview-mac"; edges["bottom"] = "preview-phone"; selected = "preview-mac"; Draw(); Notify("界面预览 · 示例设备，不代表真实连接");
      await Dispatcher.InvokeAsync(delegate { UpdateLayout(); }, DispatcherPriority.Render);
      SavePreview((FrameworkElement)Content, "native-preview.png");
      controlledTitle.Text = "正在被控制\nPixel 9（界面示例）正在操作或查看本机"; controlledBanner.Visibility = Visibility.Visible;
      await Dispatcher.InvokeAsync(delegate { UpdateLayout(); }, DispatcherPriority.Render); SavePreview((FrameworkElement)Content, "native-controlled.png");
      if (controlledBanner.TranslatePoint(new Point(0, 0), this).Y < Height / 2) throw new Exception("Controlled banner must be near the identity area at bottom of sidebar");
      controlledBanner.Visibility = Visibility.Collapsed;
      informationNavigation.RaiseEvent(new RoutedEventArgs(System.Windows.Controls.Button.ClickEvent));
      if(informationPage==null||informationPage.Visibility!=Visibility.Visible||devicePage.Visibility!=Visibility.Collapsed)throw new Exception("My information must be a page");
      await Dispatcher.InvokeAsync(delegate{UpdateLayout();},DispatcherPriority.Render);SavePreview((FrameworkElement)Content,"native-information.png");
      adbNavigation.RaiseEvent(new RoutedEventArgs(System.Windows.Controls.Button.ClickEvent));
      if(adbHub==null||adbHub.Visibility!=Visibility.Visible||informationPage.Visibility!=Visibility.Collapsed)throw new Exception("ADB navigation must show group phone selector");
      await Dispatcher.InvokeAsync(delegate{UpdateLayout();},DispatcherPriority.Render);SavePreview((FrameworkElement)Content,"native-adb-hub.png");
      ShowAdb(devices[2], false);
      adbPage.SetPreview(new { available = true, devices = new[] { new { serial = "USB-DEMO-01", status = "device", model = "Pixel_9（界面示例）" }, new { serial = "USB-DEMO-02", status = "unauthorized", model = "Android 手机（界面示例）" }, new { serial = "USB-DEMO-03", status = "offline", model = "Android 手机（界面示例）" } }, message = "界面示例 · 请手动选择对应手机，未执行任何授权。" });
      await Dispatcher.InvokeAsync(delegate { UpdateLayout(); }, DispatcherPriority.Render);
      if (adbPage.TransportCount != 3 || adbPage.CanAuthorize || devicePage.Visibility != Visibility.Collapsed) throw new Exception("ADB must use embedded page, show transport states, and require explicit selection");
      SavePreview((FrameworkElement)Content, "native-adb.png");
      var adbWidth = Width; var adbHeight = Height; Width = MinWidth; Height = MinHeight;
      await Dispatcher.InvokeAsync(delegate { UpdateLayout(); }, DispatcherPriority.Render); SavePreview((FrameworkElement)Content, "native-adb-compact.png");
      Width = adbWidth; Height = adbHeight; ShowDevices();
      ShowDevicePermissions(); await devicePermissionsPage.Refresh();
      var report = await Client.Call("group-permissions");
      if (!(Json.Get(report,"devices") is System.Collections.IEnumerable)) throw new Exception("Group permission IPC unavailable");
      devicePermissionsPage.Display(report);
      if(devicePermissionsPage.InputToggle==null)throw new Exception("Local collaboration settings must be in group permissions");
      devicePermissionsPage.InputToggle.IsChecked=false;devicePermissionsPage.InputToggle.RaiseEvent(new RoutedEventArgs(System.Windows.Controls.Primitives.ButtonBase.ClickEvent));
      for(int attempt=0;attempt<20 && Json.Flag(Json.Get(await Client.Call("state"),"permissions"),"input");attempt++)await Task.Delay(50);
      if(Json.Flag(Json.Get(await Client.Call("state"),"permissions"),"input"))throw new Exception("Permission switch did not persist");
      await Client.Call("settings",new{permissions=new{input=true}});await devicePermissionsPage.Refresh();
      await Dispatcher.InvokeAsync(delegate{UpdateLayout();},DispatcherPriority.Render);SavePreview((FrameworkElement)Content,"native-permissions-local.png");
      devicePermissionsPage.Display(new System.Web.Script.Serialization.JavaScriptSerializer().DeserializeObject("{\"devices\":[{\"id\":\"preview-phone\",\"name\":\"Pixel 9 · 界面示例\",\"platform\":\"android\",\"online\":true,\"permissions\":[{\"label\":\"键鼠操作\",\"supported\":true,\"app\":\"已开启\",\"system\":\"已就绪\",\"consent\":\"已允许\",\"status\":\"已就绪\"},{\"label\":\"屏幕查看\",\"supported\":true,\"app\":\"已开启\",\"system\":\"未就绪\",\"consent\":\"已允许\",\"status\":\"系统未就绪\"},{\"label\":\"远程关机\",\"supported\":false,\"status\":\"不支持\"}]},{\"name\":\"客厅电脑 · 界面示例\",\"online\":false,\"message\":\"设备离线，无法读取当前权限\",\"permissions\":[{\"label\":\"键鼠操作\",\"supported\":true,\"app\":\"未知\",\"system\":\"未知\",\"consent\":\"未知\",\"status\":\"状态未知\"}]}]}"));
      await Dispatcher.InvokeAsync(delegate { UpdateLayout(); }, DispatcherPriority.Render);
      if (devicePage.Visibility != Visibility.Collapsed || devicePermissionsPage.Visibility != Visibility.Visible) throw new Exception("Permission details must be embedded");
      SavePreview((FrameworkElement)Content,"native-permissions.png"); ShowDevices();
      var menu = BuildMenu(devices[1]); menu.PlacementTarget = this; menu.Placement = PlacementMode.Center; menu.IsOpen = true;
      await Dispatcher.InvokeAsync(delegate { menu.UpdateLayout(); }, DispatcherPriority.ContextIdle);
      var glassSample = menu.Template.FindName("GlassSample", menu) as Rectangle;
      if (!SystemParameters.HighContrast && (glassSample == null || !(glassSample.Fill is VisualBrush) || !(glassSample.Effect is System.Windows.Media.Effects.BlurEffect))) throw new Exception("Menu background must use Gaussian blur");
      SavePreview(menu, "native-menu.png");
      var submenu = menu.Items.OfType<MenuItem>().First(x => Convert.ToString(x.Header) == "调整方位"); submenu.IsSubmenuOpen = true;
      await Dispatcher.InvokeAsync(delegate { submenu.UpdateLayout(); }, DispatcherPriority.Render);
      var popup = submenu.Template.FindName("PART_Popup", submenu) as Popup;
      if (popup == null || !popup.IsOpen || popup.Child == null) throw new Exception("Rounded submenu navigation failed");
      submenu.IsSubmenuOpen = false; menu.IsOpen = false;
      int windowsBefore = Application.Current.Windows.Count;
      settingsNavigation.RaiseEvent(new RoutedEventArgs(System.Windows.Controls.Button.ClickEvent));
      if (settingsPage.Visibility != Visibility.Visible || devicePage.Visibility != Visibility.Collapsed || Application.Current.Windows.Count != windowsBefore) throw new Exception("Settings must navigate within the main window");
      settingsName.Text = "工作电脑 · 草稿";
      homeNavigation.RaiseEvent(new RoutedEventArgs(System.Windows.Controls.Button.ClickEvent));
      if (devicePage.Visibility != Visibility.Visible) throw new Exception("Device navigation failed");
      settingsNavigation.RaiseEvent(new RoutedEventArgs(System.Windows.Controls.Button.ClickEvent));
      if (settingsName.Text != "工作电脑 · 草稿") throw new Exception("Settings draft lost on navigation");
      await SaveSettings(); if (Json.Text(await Client.Call("state"), "name") != "工作电脑 · 草稿") throw new Exception("Embedded settings save failed");
      settingsName.Text = ""; await SaveSettings();
      if (!settingsStatus.Text.Contains("设备名称") || !settingsDirty || !saveSettingsButton.IsEnabled) throw new Exception("Invalid settings must retain the draft and show an inline error");
      settingsName.Text = "工作电脑"; await SaveSettings();
      await Dispatcher.InvokeAsync(delegate { UpdateLayout(); }, DispatcherPriority.Render);
      SavePreview((FrameworkElement)Content, "native-settings.png");
      var settingsWidth = Width; var settingsHeight = Height; Width = MinWidth; Height = MinHeight;
      await Dispatcher.InvokeAsync(delegate { UpdateLayout(); }, DispatcherPriority.Render);
      SavePreview((FrameworkElement)Content, "native-settings-compact.png"); Width = settingsWidth; Height = settingsHeight; ShowDevices();
      var pairing = PairingWindow(); pairing.Show(); await pairing.InitialLoad; pairing.UpdateLayout();
      if (pairing.QRVisible || pairing.CurrentCode == null) throw new Exception("Pairing must initially show code with QR skeleton");
      await Dispatcher.InvokeAsync(delegate { pairing.UpdateLayout(); }, DispatcherPriority.Render);
      SavePreview((FrameworkElement)pairing.Content, "native-pairing.png");
      await pairing.ToggleQR(); if (!pairing.QRVisible) throw new Exception("Reveal QR failed");
      pairing.UpdateLayout(); SavePreview((FrameworkElement)pairing.Content, "native-pairing-qr.png");
      pairing.ToggleJoin(); if (!pairing.JoiningVisible) throw new Exception("Inline join input failed");
      await pairing.Join(); // Empty input must fail locally, without changing accounts.
      pairing.UpdateLayout(); SavePreview((FrameworkElement)pairing.Content, "native-pairing-join.png");
      await pairing.ToggleQR(); if (pairing.QRVisible) throw new Exception("Hide QR failed"); pairing.Close();
      var originalWidth = Width; var originalHeight = Height; Width = MinWidth; Height = MinHeight;
      await Dispatcher.InvokeAsync(delegate { UpdateLayout(); }, DispatcherPriority.Render);
      SavePreview((FrameworkElement)Content, "native-compact.png"); Width = originalWidth; Height = originalHeight;
      WindowState = WindowState.Maximized; await Dispatcher.InvokeAsync(delegate { UpdateLayout(); }, DispatcherPriority.Render);
      if (WindowState != WindowState.Maximized) throw new Exception("Native frame maximize failed");
      WindowState = WindowState.Normal;
      Hide(); if (IsVisible) throw new Exception("Tray hide failed"); Show(); if (!IsVisible) throw new Exception("Tray restore failed");
      if (startupOption == null) throw new Exception("Startup option missing");
      var phoneRemote = new RemoteWindow(this, new Device { Id = "preview-phone", Name = "示例手机（未连接）", Platform = "android", Width = 1080, Height = 2340 }); phoneRemote.Show();
      await Dispatcher.InvokeAsync(delegate { phoneRemote.UpdateLayout(); }, DispatcherPriority.Render); await Task.Delay(150);
      if (!phoneRemote.ControlRequested || !phoneRemote.HasRightToolbar || phoneRemote.NavigationKeyCount != 3 || phoneRemote.Width >= phoneRemote.Height || phoneRemote.Height > SystemParameters.WorkArea.Height) throw new Exception("Phone viewer must auto request control, fit screen and offer three navigation keys in the right toolbar");
      SavePreview((FrameworkElement)phoneRemote.Content, "native-remote-phone.png"); phoneRemote.Close();
      var realState = State;
      State = new System.Web.Script.Serialization.JavaScriptSerializer().DeserializeObject("{\"consentRequests\":[{\"id\":\"preview-device\",\"requestId\":\"fixture\",\"name\":\"示例设备（界面测试）\"}]}"); RefreshRequests();
      await Dispatcher.InvokeAsync(delegate { consentWindow.UpdateLayout(); }, DispatcherPriority.Render);
      SavePreview((FrameworkElement)consentWindow.Content, "native-consent.png"); consentWindow.Close();
      State = realState;
      File.WriteAllText(System.IO.Path.Combine(DataDir, "passed.txt"), "Native WPF: settings IPC, pairing QR decode, direction/swap, offline menu, rounded menu/submenu, embedded settings navigation/draft/save, Gaussian popup background, dynamic code + skeleton/reveal/hide QR + inline join validation, compact window, maximize/restore, tray hide/restore, controlled banner above identity, embedded ADB transport states and explicit selection, compact Android viewer with right-side navigation passed. Preview uses labeled fixtures."); await Quit(0);
    }
    void SavePreview(FrameworkElement target, string name) {
      int width = Math.Max(1, (int)Math.Ceiling(target.ActualWidth)), height = Math.Max(1, (int)Math.Ceiling(target.ActualHeight));
      var bitmap = new RenderTargetBitmap(width, height, 96, 96, PixelFormats.Pbgra32);
      var visual = new DrawingVisual(); using (var context = visual.RenderOpen()) { context.DrawRectangle(Brush("#EDF1F7"), null, new Rect(0, 0, width, height)); context.DrawRectangle(new VisualBrush(target), null, new Rect(0, 0, width, height)); } bitmap.Render(visual);
      var png = new PngBitmapEncoder(); png.Frames.Add(BitmapFrame.Create(bitmap)); using (var stream = File.Create(System.IO.Path.Combine(DataDir, name))) png.Save(stream);
    }
    [STAThread] public static int Main(string[] args) {
      Arguments = args; Root = AppDomain.CurrentDomain.BaseDirectory; int rootIndex = Array.IndexOf(args, "--root"); if (rootIndex >= 0 && rootIndex + 1 < args.Length) Root = System.IO.Path.GetFullPath(args[rootIndex + 1]);
      int dataIndex = Array.IndexOf(args, "--data"); DataDir = dataIndex >= 0 && dataIndex + 1 < args.Length ? System.IO.Path.GetFullPath(args[dataIndex + 1]) : System.IO.Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".lanlink"); Directory.CreateDirectory(DataDir);
      string hash; using (var sha = System.Security.Cryptography.SHA256.Create()) hash = BitConverter.ToString(sha.ComputeHash(System.Text.Encoding.UTF8.GetBytes(DataDir.ToLowerInvariant()))).Replace("-", "");
      bool first; using (var mutex = new Mutex(true, "Local\\LanLinkNative-" + hash, out first)) {
        if (!first) { MessageBox.Show("LanLink 已在运行，请从系统托盘打开。", "LanLink"); return 0; }
        var app = new Application { ShutdownMode = ShutdownMode.OnExplicitShutdown };
        try { return app.Run(new MainWindow()); } catch (Exception ex) { File.WriteAllText(System.IO.Path.Combine(DataDir, "startup-error.txt"), ex.ToString()); if (!args.Contains("--self-test")) MessageBox.Show(ex.Message, "LanLink 启动失败"); return 1; }
      }
    }
  }
}
