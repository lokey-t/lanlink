using System;
using System.Collections;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

namespace LanLink.Desktop {
  // The native page only selects a transport; the service verifies the paired phone
  // before it starts the authenticated helper. No shell command is accepted here.
  public sealed class AdbPage : Grid {
    readonly MainWindow main;
    readonly TextBlock target = MainWindow.Text("", 15);
    readonly TextBlock status = MainWindow.Text("", 13, "#71819A");
    readonly StackPanel transports = new StackPanel();
    readonly Button refresh, authorize;
    Device device; string serial = ""; bool busy; int generation;
    public int TransportCount { get { return transports.Children.Count; } }
    public bool CanAuthorize { get { return authorize.IsEnabled; } }
    public AdbPage(MainWindow owner, Action back) {
      main = owner;
      RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto }); RowDefinitions.Add(new RowDefinition()); RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
      var header = new DockPanel { Margin = new Thickness(0, 0, 0, 22) };
      var close = MainWindow.Button("返回设备", back); DockPanel.SetDock(close, Dock.Right); header.Children.Add(close);
      var title = MainWindow.Text("ADB 键鼠授权", 26); title.FontWeight = FontWeights.SemiBold; header.Children.Add(title); Children.Add(header);
      var content = new StackPanel { MaxWidth = 800, HorizontalAlignment = HorizontalAlignment.Stretch };
      target.Margin = new Thickness(0, 0, 0, 18); content.Children.Add(target);
      var forget = MainWindow.Button("取消此设备的自动恢复", () => main.Run(async () => { if (device != null) { var result = await main.Client.Call("adb-forget", new { id = device.Id }); status.Text = Json.Text(result,"message"); } })); forget.HorizontalAlignment = HorizontalAlignment.Left; forget.Margin = new Thickness(0,0,0,16); content.Children.Add(forget);
      var steps = new StackPanel();
      var lead = MainWindow.Text("连接手机，开启增强控制", 18); lead.FontWeight = FontWeights.SemiBold; steps.Children.Add(lead);
      var instructions = MainWindow.Text("1  用 USB 连接此手机，并在手机的开发者选项中开启「USB 调试」。\n2  解锁手机，确认「允许 USB 调试」并勾选始终允许此电脑，然后刷新下方设备。\n3  选择对应手机，点击授权，并在手机的 LanLink 中确认。", 14, "#657693"); instructions.TextWrapping = TextWrapping.Wrap; instructions.LineHeight = 26; instructions.Margin = new Thickness(0, 14, 0, 12); steps.Children.Add(instructions);
      var detail = MainWindow.Text("需要电脑已安装 Android SDK Platform-Tools（Android Studio 可安装）。增强控制可提供更完整的键鼠操作；部分手机还需开启厂商的「USB 调试（安全设置）」。授权后保存设备绑定；应用重启会重连，手机重启后已信任的 USB / 无线调试可用时自动恢复。", 12, "#8592A6"); detail.TextWrapping = TextWrapping.Wrap; detail.LineHeight = 21; steps.Children.Add(detail);
      content.Children.Add(Card(steps));
      var selection = new StackPanel();
      var row = new DockPanel(); refresh = MainWindow.Button("刷新设备", () => main.Run(Refresh)); DockPanel.SetDock(refresh, Dock.Right); row.Children.Add(refresh); row.Children.Add(MainWindow.Text("连接到这台电脑的手机", 16)); selection.Children.Add(row);
      transports.Margin = new Thickness(0, 14, 0, 8); selection.Children.Add(transports);
      status.TextWrapping = TextWrapping.Wrap; status.LineHeight = 21; status.Margin = new Thickness(0, 0, 18, 0);
      authorize = MainWindow.Button("为选中手机授权", () => main.Run(Authorize)); authorize.Style = (Style)main.FindResource("Primary"); authorize.IsEnabled = false; authorize.VerticalAlignment = VerticalAlignment.Center;
      content.Children.Add(Card(selection));
      var scroll = new ScrollViewer { Content = content, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled }; SetRow(scroll, 1); Children.Add(scroll);
      var actions = new DockPanel { Margin = new Thickness(0, 14, 0, 10) }; DockPanel.SetDock(authorize, Dock.Right); actions.Children.Add(authorize); actions.Children.Add(status); SetRow(actions, 2); Children.Add(actions);
    }
    Border Card(StackPanel content) { return new Border { Background = Brushes.White, CornerRadius = new CornerRadius(18), BorderBrush = MainWindow.Brush("#E5EBF5"), BorderThickness = new Thickness(1), Padding = new Thickness(22), Margin = new Thickness(0, 0, 0, 16), Child = content }; }
    public void Open(Device targetDevice, bool scan = true) { device = targetDevice; target.Text = "为 " + device.Name + " 启用 ADB 增强控制"; if (scan) main.Run(Refresh); }
    public void SetPreview(object result) { var json = new System.Web.Script.Serialization.JavaScriptSerializer(); Display(json.DeserializeObject(json.Serialize(result))); }
    void Display(object result) {
      transports.Children.Clear(); serial = "";
      foreach (var item in Json.Get(result, "devices") as IEnumerable ?? new object[0]) {
        string candidate = Json.Text(item, "serial"), state = Json.Text(item, "status"), model = Json.Text(item, "model");
        string label = state == "device" ? "可授权" : state == "unauthorized" ? "等待手机允许 USB 调试" : state == "offline" ? "设备离线，请重新连接" : state;
        var words = new StackPanel(); words.Children.Add(MainWindow.Text((model == "" ? "Android 设备" : model.Replace('_', ' ')) + "  ·  " + label, 13, state == "device" ? "#45745F" : "#8A754D"));
        var identity = MainWindow.Text(candidate, 11, "#8592A6"); identity.Margin = new Thickness(0, 5, 0, 0); words.Children.Add(identity);
        var option = new RadioButton { Content = words, GroupName = "adb-device", IsEnabled = state == "device", Margin = new Thickness(0, 8, 0, 8), VerticalContentAlignment = VerticalAlignment.Center };
        option.Checked += delegate { serial = candidate; authorize.IsEnabled = !busy; }; transports.Children.Add(option);
      }
      status.Text = Json.Text(result, "message");
      if (!Json.Flag(result, "available") && status.Text == "") status.Text = "未找到 ADB。请在 Android Studio 的 SDK 管理器中安装 Android SDK Platform-Tools，然后刷新。";
      else if (transports.Children.Count == 0 && status.Text == "") status.Text = "尚未检测到手机。请确认数据线支持传输，并已开启 USB 调试。";
      else if (status.Text == "") status.Text = "请选择与上方设备对应的手机。启用时会核对应用，并等待目标手机确认助手已连接。";
      // Deliberate selection avoids picking an unrelated phone when several are connected.
      authorize.IsEnabled = false;
    }
    async Task Refresh() {
      if (busy) return; int version = ++generation; busy = true; refresh.IsEnabled = false; authorize.IsEnabled = false; status.Text = "正在检测连接的手机…";
      try { var result = await main.Client.Call("adb-devices"); if (version == generation) Display(result); }
      catch (Exception ex) { status.Text = MainWindow.DisplayError(ex.Message); }
      finally { busy = false; refresh.IsEnabled = true; authorize.IsEnabled = serial != ""; }
    }
    async Task Authorize() {
      if (busy || device == null || serial == "") return; busy = true; refresh.IsEnabled = false; authorize.IsEnabled = false; transports.IsEnabled = false;
      status.Text = "请留意手机上的 LanLink 授权提示，并确认允许此电脑启用增强控制…";
      try {
        var result = await main.Client.Call("adb-authorize", new { id = device.Id, serial = serial });
        string message = Json.Text(result, "message"); status.Text = message == "" ? "增强控制已启用，可以返回设备页开始控制。" : message;
      } catch (Exception ex) { status.Text = ex.Message.StartsWith("CONSENT_PENDING:") ? "请在手机的 LanLink 中同意此电脑的请求，同意后再次点击授权。" : MainWindow.DisplayError(ex.Message); }
      finally { busy = false; refresh.IsEnabled = true; authorize.IsEnabled = serial != ""; transports.IsEnabled = true; }
    }
  }
}
