using System;
using System.Collections;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

namespace LanLink.Desktop {
  // Local USB setup for a physically selected phone; group membership is unchanged.
  // No shell command is accepted from the UI.
  public sealed class AdbPage : Grid {
    readonly MainWindow main;

    readonly TextBlock target = MainWindow.Text("", 15);
    readonly TextBlock status = MainWindow.Text("", 13, "#71819A");
    readonly StackPanel transports = new StackPanel();
    readonly Button refresh, authorize;
    string serial = ""; bool busy; int generation;
    public int TransportCount { get { return transports.Children.Count; } }
    public bool CanAuthorize { get { return authorize.IsEnabled; } }
    public AdbPage(MainWindow owner, Action back) {
      main = owner;
      RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto }); RowDefinitions.Add(new RowDefinition()); RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
      var header = new DockPanel { Margin = new Thickness(0, 0, 0, 22) };
      var close = MainWindow.LButton("返回设备", back); DockPanel.SetDock(close, Dock.Right); header.Children.Add(close);
      var title = MainWindow.LText("安卓设备ADB授权", 26); title.FontWeight = FontWeights.SemiBold; header.Children.Add(title); Children.Add(header);
      var content = new StackPanel { MaxWidth = 800, HorizontalAlignment = HorizontalAlignment.Stretch };
      target.Margin = new Thickness(0, 0, 0, 18); content.Children.Add(target);
      var steps = new StackPanel();
      var lead = MainWindow.LText("连接手机，开启增强控制", 18); lead.FontWeight = FontWeights.SemiBold; steps.Children.Add(lead);
      var instructions = MainWindow.LText("1  用 USB 连接此手机，并在手机的开发者选项中开启「USB 调试」。\n2  解锁手机，确认「允许 USB 调试」并勾选始终允许此电脑，然后刷新下方设备。\n3  选择本机 ADB 连接的手机，点击「为选中的手机授权」。不会改变手机所属组或设备控制权限。", 14, "#657693"); instructions.TextWrapping = TextWrapping.Wrap; instructions.LineHeight = 26; instructions.Margin = new Thickness(0, 14, 0, 12); steps.Children.Add(instructions);
      var detail = MainWindow.LText("需要电脑已安装 Android SDK Platform-Tools（Android Studio 可安装）。增强控制可提供更完整的键鼠操作；部分手机还需开启厂商的「USB 调试（安全设置）」。其他组手机重启后，可打开手机 LanLink 并连接数据线，在此页面再次授权；本组手机使用连接引导建立绑定后支持自动恢复。首次 USB 调试确认仍需在手机完成。", 12, "#8592A6"); detail.TextWrapping = TextWrapping.Wrap; detail.LineHeight = 21; steps.Children.Add(detail);
      content.Children.Add(Card(steps));
      var selection = new StackPanel();
      var row = new DockPanel(); refresh = MainWindow.LButton("刷新设备", () => main.Run(Refresh)); DockPanel.SetDock(refresh, Dock.Right); row.Children.Add(refresh); row.Children.Add(MainWindow.LText("连接到这台电脑的手机", 16)); selection.Children.Add(row);
      transports.Margin = new Thickness(0, 14, 0, 8); selection.Children.Add(transports);
      status.TextWrapping = TextWrapping.Wrap; status.LineHeight = 21; status.Margin = new Thickness(0, 0, 18, 0);
      authorize = MainWindow.LButton("为选中的手机授权", () => main.Run(Authorize)); authorize.Style = (Style)main.FindResource("Primary"); authorize.IsEnabled = false; authorize.VerticalAlignment = VerticalAlignment.Center;
      content.Children.Add(Card(selection));
      var scroll = new ScrollViewer { Content = content, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled }; SetRow(scroll, 1); Children.Add(scroll);
      var actions = new DockPanel { Margin = new Thickness(0, 14, 0, 10) }; DockPanel.SetDock(authorize, Dock.Right); actions.Children.Add(authorize); actions.Children.Add(status); SetRow(actions, 2); Children.Add(actions);
    }
    Border Card(StackPanel content) { return new Border { Background = MainWindow.Brush("#FFFFFF"), CornerRadius = new CornerRadius(18), BorderBrush = MainWindow.Brush("#E5EBF5"), BorderThickness = new Thickness(1), Padding = new Thickness(22), Margin = new Thickness(0, 0, 0, 16), Child = content }; }
    public void Open(Device ignored, bool scan=true) { target.Text=Locale.T("帮助其他组的设备完成 ADB 配置，无需加入本组。请先在手机打开新版 LanLink。");if(scan)main.Run(Refresh); }
    public void SetPreview(object result) { var json = new System.Web.Script.Serialization.JavaScriptSerializer(); Display(json.DeserializeObject(json.Serialize(result))); }
    void Display(object result) {
      transports.Children.Clear(); serial = "";
      foreach(var item in Json.Get(result,"devices") as IEnumerable ?? new object[0]) {
        string candidate=Json.Text(item,"serial"),state=Json.Text(item,"status");bool ready=Json.Flag(item,"authorized");
        var option=new RadioButton { Content=Json.Text(item,"model")+" · "+candidate+" · "+(ready?Locale.T("已就绪"):state=="device"?Locale.T("可授权"):state=="unauthorized"?Locale.T("请允许 USB 调试"):Locale.T("未连接")),GroupName="local-adb",Margin=new Thickness(0,10,0,10),IsEnabled=state=="device"&&!ready };
        option.Checked+=delegate{serial=candidate;authorize.IsEnabled=!busy;};transports.Children.Add(option);
      }
      status.Text=Json.Text(result,"message");authorize.IsEnabled=false;
    }
    async Task Refresh() {
      if (busy) return; int version = ++generation; busy = true; refresh.IsEnabled = false; authorize.IsEnabled = false; status.Text = Locale.T("正在检测连接的手机…");
      try { var result = await main.Client.Call("adb-local-devices"); if (version == generation) Display(result); }
      catch (Exception ex) { status.Text = MainWindow.DisplayError(ex.Message); }
      finally { busy = false; refresh.IsEnabled = true; authorize.IsEnabled = serial != ""; }
    }
    async Task Authorize() {
      if (busy || serial == "") return; busy = true; refresh.IsEnabled = false; authorize.IsEnabled = false; transports.IsEnabled = false;
      status.Text = Locale.T("正在配置手机助手；如有系统 USB 调试提示，请在手机确认…");
      try {
        var result = await main.Client.Call("adb-local-authorize", new { serial=serial });
        Display(await main.Client.Call("adb-local-devices"));
        string message = Json.Text(result, "message"); status.Text = message == "" ? Locale.T("增强控制已启用，可以返回设备页开始控制。") : message;
      } catch (Exception ex) { status.Text = ex.Message.StartsWith("CONSENT_PENDING:") ? Locale.T("请在手机的 LanLink 中同意此电脑的请求，同意后再次点击授权。") : MainWindow.DisplayError(ex.Message); }
      finally { busy = false; refresh.IsEnabled = true; authorize.IsEnabled = serial != ""; transports.IsEnabled = true; }
    }
  }
}
