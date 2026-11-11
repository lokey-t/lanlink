using System;
using System.Collections;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

namespace LanLink.Desktop {
  public partial class MainWindow {
    static Button AdbHelpLink(string label, Action action) {
      var link = Button("", action);
      var text = LText(label, 12, "#7B879D");
      text.TextDecorations = TextDecorations.Underline;
      link.Content = text;
      link.Background = Brushes.Transparent;
      link.BorderThickness = new Thickness(0);
      link.Padding = new Thickness(4);
      return link;
    }

    void ShowManualAdbGuide() {
      const string detect = "adb devices -l";
      var panel = Column();
      var window = Dialog(Locale.T("手动 ADB 授权"), panel, 580);

      AddManualAdbStep(panel, "1. 准备 USB 调试",
        "在电脑安装 Android SDK Platform-Tools，并将其目录加入 PATH。在手机开发者选项中开启 USB 调试，用数据线连接电脑，并在手机上允许 USB 调试。");
      AddManualAdbStep(panel, "2. 检查连接",
        "打开电脑终端并运行下方命令。状态为 device 时可继续，显示 unauthorized 时请解锁手机并确认调试授权。若列表为空，请检查数据线是否支持数据传输。");

      var command = Text(detect, 13);
      command.FontFamily = new FontFamily("Consolas");
      command.VerticalAlignment = VerticalAlignment.Center;
      var copy = LButton("复制命令", () => Clipboard.SetText(detect));
      copy.Click += delegate { copy.Content = UiLocale.Content("已复制到本机剪贴板"); };
      var commandRow = new DockPanel { Margin = new Thickness(0,6,0,16) };
      DockPanel.SetDock(copy, Dock.Right);
      commandRow.Children.Add(copy);
      commandRow.Children.Add(new Border {
        Background = Brush("#F1F5FD"), CornerRadius = new CornerRadius(10),
        Padding = new Thickness(12,8,12,8), Margin = new Thickness(0,0,10,0), Child = command
      });
      panel.Children.Add(commandRow);

      AddManualAdbStep(panel, "3. 启动 LanLink 助手",
        "在手机 LanLink 打开「设置 → 使用说明 → 连接与故障排查 → 显示 ADB 启动命令」，将手机生成的完整 adb shell 命令粘贴到电脑终端执行。若连接了多台调试设备，请先断开其他设备。下方是命令格式，参数以手机显示为准。");
      var example = Text("adb shell \"CLASSPATH='APK_PATH' app_process /system/bin com.lanlink.app.ShellAgent PORT SECRET\"", 13);
      example.FontFamily = new FontFamily("Consolas");
      example.TextWrapping = TextWrapping.Wrap;
      example.TextTrimming = TextTrimming.None;
      panel.Children.Add(new Border {
        Background = Brush("#F1F5FD"), CornerRadius = new CornerRadius(10),
        Padding = new Thickness(12,8,12,8), Margin = new Thickness(0,0,0,16), Child = example
      });
      AddManualAdbStep(panel, "4. 确认授权",
        "让命令保持运行，回到「安卓授权」点击「刷新状态」。手机服务重启后，请在手机上重新获取命令。若曾解除 ADB 授权，请先在手机 LanLink 的「ADB 授权」中重新允许。");

      var close = LButton("关闭", window.Close);
      close.IsCancel = true;
      close.HorizontalAlignment = HorizontalAlignment.Right;
      close.Margin = new Thickness(0,12,0,0);
      close.Style = (Style)FindResource("Primary");
      panel.Children.Add(close);
      window.ShowDialog();
    }

    static void AddManualAdbStep(StackPanel panel, string heading, string instructions) {
      var title = LText(heading, 15);
      title.FontWeight = FontWeights.SemiBold;
      panel.Children.Add(title);
      var body = LText(instructions, 13, "#657693");
      body.TextWrapping = TextWrapping.Wrap;
      body.Margin = new Thickness(0,5,0,12);
      panel.Children.Add(body);
    }
  }

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
