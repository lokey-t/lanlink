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
using System.Windows.Media.Animation;
using System.Windows.Shapes;
using System.Windows.Threading;
using Forms = System.Windows.Forms;

namespace LanLink.Desktop {
  public sealed class Device {
    public string Id, Name, Platform, Transport; public object Access; public bool Online, Local; public double Width = 1920, Height = 1080;
  }
  public partial class MainWindow : Window {
    public static string Root, DataDir; public static string[] Arguments;
    public Client Client { get; private set; }
    string serviceOptions = ""; bool serviceUnavailable, restartingService; Border notificationBanner; Button restartServiceButton, dismissNoticeButton;
    public object State;
    int remoteOpenVersion; Task remoteCleanup = Task.FromResult(0);
    public bool HandlingFallback { get { return showingFallback; } }
    readonly List<Device> devices = new List<Device>();
    StagingPage stagingPage;Button stagingNavigation;FileDragEdges fileDragEdges;
    readonly Dictionary<string, string> edges = new Dictionary<string, string>();
    readonly StackPanel bubbles = new StackPanel { Orientation = Orientation.Horizontal };
    ScrollViewer layoutScroll; SharedLayoutGeometry layoutGeometry; Button centerLayoutButton, localLayoutMasterButton; bool layoutInitiallyCentered; ContextMenu layoutDeviceMenu; Popup layoutActionsPopup;
    readonly Canvas canvas = new Canvas { Width = 1000, Height = 590, Background = Brushes.Transparent, AllowDrop = true };
    readonly TextBlock footer = LText("正在连接设备服务…", 12, "#70809C");
    string persistentNotice = "", shutdownNotice = "";
    readonly TextBlock notice = Text("", 12, "#506383");
    readonly DispatcherTimer poll = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1.5) };
    Grid devicePage, settingsPage, adbHub, informationPage, clipboardPage; StackPanel clipboardBody;string clipboardSignature="";readonly StackPanel clipboardQuick=new StackPanel{Orientation=Orientation.Horizontal};CheckBox clipboardUploadOption,clipboardDownloadOption,clipboardShortcutOption;Button clipboardUploadButton,clipboardDownloadButton,clipboardNavigation; Button permissionsNavigation, adbNavigation, informationNavigation; Border pageSurface, controlledBanner; Button homeNavigation, settingsNavigation, saveSettingsButton, stopInbound;
    readonly TextBlock controlledTitle = Text("", 12, "#456A63"); AdbPage adbPage; DevicePermissionsPage devicePermissionsPage;
    TextBox settingsName; CheckBox settingsEdgeGlow, settingsHideAway, adminStartupOption; ComboBox settingsMaster, settingsSwitchMode, settingsHotkey, settingsLanguage; bool restoringLanguage; Slider settingsDwell; TextBlock settingsDwellLabel, settingsZeroHint; StackPanel dwellOptions, hotkeyOptions; readonly Dictionary<string, CheckBox> settingsPermissions = new Dictionary<string, CheckBox>();
    TextBlock settingsStatus; bool settingsDirty, loadingSettings, savingSettings; Task immediateSettingsTask = Task.FromResult(0); int immediateSettingsPending; string settingsAccount = "";
    string lastAdbGuide=""; AdbGuideWindow adbGuideWindow; PermissionRequestWindow accessWindow; string lastAccess=""; Window consentWindow, permissionWindow; string consentRequestId = ""; CheckBox startupOption; bool showingFallback;
    Window androidDisconnectWindow; TextBlock androidDisconnectName, androidDisconnectMessage; string androidDisconnectRequestId="";
    Window adbAuthorizedWindow; string adbAuthorizedRequest = ""; readonly HashSet<string> seenAdbAuthorizations = new HashSet<string>();
    readonly HashSet<string> seenAndroidDisconnectRequests = new HashSet<string>();
    Forms.NotifyIcon tray; bool quitting, busy, dragging, testing; string signature = "", selected = "";
    public static Brush Brush(string color) { return Theme.Brush(color); }
    public static TextBlock Text(string text, double size = 14, string color = "#18243D") {
      return new TextBlock { Text = text, FontSize = size, Foreground = Brush(color), TextTrimming = TextTrimming.CharacterEllipsis, VerticalAlignment = VerticalAlignment.Center };
    }
    public static Button Button(string text, Action action) {
      var b = new Button { Content = text }; b.Click += delegate { action(); }; return b;
    }
    public static TextBlock LText(string source, double size = 14, string color = "#18243D") {
      var label=Text("", size, color); label.TextWrapping=TextWrapping.Wrap; label.TextTrimming=TextTrimming.None;
      return UiLocale.Bind(label, TextBlock.TextProperty, source);
    }
    public static Button LButton(string source, Action action) {
      return UiLocale.Bind(Button("", action), ContentControl.ContentProperty, source);
    }
    public static StackPanel Column() { return new StackPanel { Margin = new Thickness(28, 14, 28, 26) }; }
    public static TextBox Field(StackPanel p, string label, string value) {
      p.Children.Add(LText(label, 13, "#6C7B94")); var t = new TextBox { Text = value }; p.Children.Add(t); return t;
    }
    public static string DisplayErrorOrEmpty(string message) { return string.IsNullOrWhiteSpace(message)?"":DisplayError(message); }
    public static string DisplayError(string message) { return Locale.T(UiText.ErrorMessage(message)); }
    public void Notify(string message) {
      message = DisplayErrorOrEmpty(message); persistentNotice = message; UpdateNotificationBanner();
      if (settingsStatus != null && settingsPage.Visibility == Visibility.Visible) settingsStatus.Text = message;
    }
    // Operation acknowledgements float briefly; Notify is reserved for actionable errors.
    void UpdateNotificationBanner() {
      notice.Text = serviceUnavailable || restartingService || restartServiceButton.Visibility == Visibility.Visible ? persistentNotice : !string.IsNullOrEmpty(shutdownNotice) ? shutdownNotice : persistentNotice;
      dismissNoticeButton.Visibility = string.IsNullOrEmpty(shutdownNotice) && !serviceUnavailable && !restartingService && restartServiceButton.Visibility != Visibility.Visible ? Visibility.Visible : Visibility.Collapsed;
      notificationBanner.Visibility = string.IsNullOrEmpty(notice.Text) ? Visibility.Collapsed : Visibility.Visible;
    }
    public void NotifyTransient(string message) { Toast(DisplayErrorOrEmpty(message),3); }
    public string SettingsResult(object result,string success) {
      if(string.IsNullOrWhiteSpace(Json.Text(result,"warning")))return success;
      string message=string.IsNullOrWhiteSpace(Json.Text(result,"message"))?Locale.T("设置已保存，部分运行状态未能恢复，请重启设备服务。"):DisplayError(Json.Text(result,"message"));
      restartServiceButton.Visibility=Visibility.Visible;Notify(message);return message;
    }
    void ConnectClient() {
      var current = new Client(); Client = current;
      current.Failed = message => Dispatcher.BeginInvoke(new Action(delegate {
        if (Client != current || quitting) return;
        serviceUnavailable = true; poll.Stop();if(stageReceiveWindow!=null){stageReceiveWindow.Close();stageReceiveWindow=null;}stageReceiveSignature=""; restartServiceButton.Visibility = Visibility.Visible;
        EndAdbAuthorization();
        EdgeGlow.Feedback(new Dictionary<string,object>{{"phase","clear"}});
        Notify(Locale.T("设备服务已中断。点击“重启设备服务”恢复；未保存的设置会保留。"));
        foreach (var d in devices) if (!d.Local) d.Online = false; Draw();
      }));
      current.EdgeFeedback = feedback => Dispatcher.BeginInvoke(new Action(delegate { if (Client == current) EdgeGlow.Feedback(feedback); }));
    }
    async Task RestartService() {
      if (restartingService) return; restartingService = true; poll.Stop(); restartServiceButton.IsEnabled = false;
      Notify(Locale.T("正在重启设备服务…"));
      try {
        foreach(var remote in Application.Current.Windows.OfType<RemoteWindow>().ToArray()) remote.Close();
        if(adbGuideWindow!=null)adbGuideWindow.End(); if(accessWindow!=null)accessWindow.End();
        EndAndroidDisconnectNotice(); EndAdbAuthorization();
        if(consentWindow!=null)consentWindow.Close(); if(permissionWindow!=null)permissionWindow.Close();
        await Client.Stop(); await remoteCleanup; if(quitting)return; ConnectClient(); await Client.Start(Root, serviceOptions);
        State = await Client.Call("state"); serviceUnavailable = false; signature = ""; await Refresh();
        if(informationPage!=null && informationPage.Visibility==Visibility.Visible)ShowInformation();
        if(devicePermissionsPage!=null && devicePermissionsPage.Visibility==Visibility.Visible)await devicePermissionsPage.Refresh();
        restartServiceButton.Visibility = Visibility.Collapsed; poll.Start(); Notify(""); NotifyTransient(Locale.T("设备服务已恢复，未保存的设置已保留。"));if(!testing && fileDragEdges==null)InitializeFileFeatures();
      } catch(Exception ex) { serviceUnavailable = true; restartServiceButton.Visibility = Visibility.Visible; Notify(Locale.T("设备服务未能恢复：") + DisplayError(ex.Message) + Locale.T(" 请再次重试。")); }
      finally { restartingService = false; restartServiceButton.IsEnabled = true; }
    }
    public async void Run(Func<Task> action) { try { await action(); } catch (Exception ex) { Notify(ex.Message); } }
    public MainWindow() {
      Client = new Client();
      Theme.Initialize(DataDir);
      Locale.Changed += ApplyLanguage;
      testing = Arguments.Contains("--self-test");
      Title = "LanLink"; Width = 1024; Height = 720; MinWidth = Math.Min(900,SystemParameters.WorkArea.Width-20); MinHeight = Math.Min(640,SystemParameters.WorkArea.Height-20);
      if(!testing)WindowSize.Attach(this,System.IO.Path.Combine(DataDir,"window-size.json"));
      WindowStartupLocation = WindowStartupLocation.CenterScreen; FontFamily = new FontFamily("Microsoft YaHei UI"); FontSize = 14;
      Background = Brush("#F3F6FC");
      Resources.MergedDictionaries.Add(Theme.Load(System.IO.Path.Combine(Root, "desktop/windows/Theme.xaml")));
      var grid = new Grid(); grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(184) }); grid.ColumnDefinitions.Add(new ColumnDefinition());
      var side = new DockPanel { LastChildFill = true, Margin = new Thickness(14, 18, 14, 18) }; grid.Children.Add(side);
      var identity = new StackPanel(); DockPanel.SetDock(identity, Dock.Bottom); side.Children.Add(identity);
      var controlledContent = new StackPanel(); controlledTitle.TextWrapping = TextWrapping.Wrap; controlledTitle.TextTrimming = TextTrimming.None; controlledTitle.LineHeight = 19; controlledContent.Children.Add(controlledTitle);
      stopInbound = LButton("取消桌面控制", () => Run(async () => { await Client.Call("stop-inbound"); await Refresh(); })); stopInbound.FontSize = 12; stopInbound.Padding = new Thickness(8); stopInbound.Margin = new Thickness(0, 10, 0, 0); stopInbound.BorderThickness = new Thickness(0); controlledContent.Children.Add(stopInbound);
      controlledBanner = new Border { CornerRadius = new CornerRadius(16), Background = Brush("#E4F1EA"), BorderBrush = Brush("#D3E7DB"), BorderThickness = new Thickness(1), Padding = new Thickness(12), Child = controlledContent, Visibility = Visibility.Collapsed }; identity.Children.Add(controlledBanner);
      var account = Button("", ShowInformation); informationNavigation = account; account.Content = WindowShell.Label("我的信息", "account"); account.HorizontalContentAlignment = HorizontalAlignment.Left; account.BorderThickness = new Thickness(0); account.Background = Brushes.Transparent; account.Margin = new Thickness(0, 12, 0, 0); identity.Children.Add(account);
      var nav = new StackPanel(); side.Children.Add(nav);
      var home = Button("", ShowDevices); homeNavigation = home; home.Content = WindowShell.Label("设备", "devices"); home.HorizontalContentAlignment = HorizontalAlignment.Left; home.Height = 46; home.Margin = new Thickness(0, 0, 0, 8); home.Background = Brush("#E1EAFB"); home.BorderThickness = new Thickness(0); nav.Children.Add(home);
      permissionsNavigation = LButton("本组设备权限", ShowDevicePermissions); adbNavigation = LButton("安卓授权", ShowAdbHub);
      stagingNavigation=Button("",ShowStaging);stagingNavigation.Content=WindowShell.Label("暂存文件","files");
      permissionsNavigation.Content=WindowShell.Label("本组设备权限","permissions");adbNavigation.Content=WindowShell.Label("安卓授权","adb");
      foreach(var item in new[] { stagingNavigation, permissionsNavigation, adbNavigation }) { item.HorizontalContentAlignment=HorizontalAlignment.Left; item.Height=46; item.Margin=new Thickness(0,0,0,8); item.BorderThickness=new Thickness(0); item.Background=Brushes.Transparent; nav.Children.Add(item); }
      clipboardNavigation=Button("",ShowClipboardPage);clipboardNavigation.Content=WindowShell.Label("剪贴板","clipboard");clipboardNavigation.HorizontalContentAlignment=HorizontalAlignment.Left;clipboardNavigation.Height=46;clipboardNavigation.Background=Brushes.Transparent;clipboardNavigation.BorderThickness=new Thickness(0);clipboardNavigation.Margin=new Thickness(0,0,0,8);nav.Children.Add(clipboardNavigation);
      var settings = Button("", Settings); settingsNavigation = settings; settings.Content = WindowShell.Label("设置", "settings"); settings.HorizontalContentAlignment = HorizontalAlignment.Left; settings.Height = 46; settings.BorderThickness = new Thickness(0); settings.Background = Brushes.Transparent; nav.Children.Add(settings);
      foreach(var navigation in new[]{homeNavigation,stagingNavigation,permissionsNavigation,adbNavigation,clipboardNavigation,settingsNavigation,informationNavigation}){navigation.Height=double.NaN;navigation.MinHeight=46;navigation.Padding=new Thickness(8,6,8,6);}
      var bodySurface = new Border { CornerRadius = new CornerRadius(22), Background = Brush("#F9FBFE"), Padding = new Thickness(26, 25, 26, 12), Margin = new Thickness(0, 0, 16, 16) }; Grid.SetColumn(bodySurface, 1); grid.Children.Add(bodySurface); pageSurface = bodySurface;
      var pageLayout = new Grid(); pageLayout.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto }); pageLayout.RowDefinitions.Add(new RowDefinition()); bodySurface.Child = pageLayout;
      var notification = new DockPanel(); restartServiceButton = LButton("重启设备服务", () => Run(RestartService)); restartServiceButton.Visibility = Visibility.Collapsed; restartServiceButton.Margin = new Thickness(12,0,0,0); DockPanel.SetDock(restartServiceButton,Dock.Right); notification.Children.Add(restartServiceButton);
      dismissNoticeButton = LButton("关闭提示", () => Notify("")); DockPanel.SetDock(dismissNoticeButton, Dock.Right); notification.Children.Add(dismissNoticeButton);
      notice.TextWrapping = TextWrapping.Wrap; notice.TextTrimming = TextTrimming.None; System.Windows.Automation.AutomationProperties.SetLiveSetting(notice, System.Windows.Automation.AutomationLiveSetting.Assertive); notification.Children.Add(notice);
      notificationBanner = new Border { Child = notification, Padding = new Thickness(14), CornerRadius = new CornerRadius(12), Background = Brush("#EAF0FB"), Margin = new Thickness(0,0,0,14), Visibility = Visibility.Collapsed }; pageLayout.Children.Add(notificationBanner);
      var pages = new Grid(); pageSurface = new Border { Child = pages }; Grid.SetRow(pageSurface,1); pageLayout.Children.Add(pageSurface); var body = new Grid(); devicePage = body; pages.Children.Add(body);
      body.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto }); body.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
      body.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto }); body.RowDefinitions.Add(new RowDefinition());
      body.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto }); body.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
      var header = new Grid { Margin = new Thickness(0, 0, 0, 24) };
      header.ColumnDefinitions.Add(new ColumnDefinition());header.ColumnDefinitions.Add(new ColumnDefinition{Width=GridLength.Auto});header.ColumnDefinitions.Add(new ColumnDefinition{Width=GridLength.Auto});
      var add = Button("", () => Run(Pairing)); add.Name="AddDeviceButton";add.Content = WindowShell.Label("添加设备", "plus"); add.Style = (Style)FindResource("Primary"); Grid.SetColumn(add,2);header.Children.Add(add);
      clipboardQuick.Margin=new Thickness(16,0,12,0);clipboardQuick.VerticalAlignment=VerticalAlignment.Center;Grid.SetColumn(clipboardQuick,1);
      foreach(var entry in new[]{new[]{"clipboard-upload",Locale.T("上传文字剪贴板")},new[]{"clipboard-download",Locale.T("下载文字剪贴板")}}){string action=entry[0];var quick=Button("",()=>Run(()=>ManualClipboard(action)));quick.Content=WindowShell.Glyph(action,22);quick.ToolTip=entry[1];System.Windows.Automation.AutomationProperties.SetName(quick,entry[1]);quick.Style=(Style)FindResource("ClipboardQuickButton");quick.Margin=new Thickness(action=="clipboard-download"?8:0,0,0,0);clipboardQuick.Children.Add(quick);}clipboardQuick.Visibility=Visibility.Collapsed;header.Children.Add(clipboardQuick);
      var title = LText("设备与布局", 26); title.FontWeight = FontWeights.SemiBold; header.Children.Add(title); body.Children.Add(header);
      var scroll = new ScrollViewer { Content = bubbles, HorizontalScrollBarVisibility = ScrollBarVisibility.Auto, VerticalScrollBarVisibility = ScrollBarVisibility.Disabled, Padding = new Thickness(1, 1, 1, 6) }; Grid.SetRow(scroll, 1); body.Children.Add(scroll);
      var hint = LText("同组布局与主设备标识自动同步。点击设备查看操作，拖动调整位置。", 12, "#8B97AB"); hint.TextWrapping = TextWrapping.Wrap; hint.Margin = new Thickness(0, 16, 0, 16); Grid.SetRow(hint, 2); body.Children.Add(hint);
      layoutScroll = new ScrollViewer { Content = canvas, HorizontalScrollBarVisibility = ScrollBarVisibility.Auto, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
      var layoutSurface = new Grid(); layoutSurface.Children.Add(layoutScroll);
      var layoutActions = new WrapPanel { HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Top, Margin = new Thickness(8), MaxWidth = 300 };
      centerLayoutButton = LButton("居中", CenterLayout); centerLayoutButton.Padding = new Thickness(12, 7, 12, 7); centerLayoutButton.FontSize = 12;
      System.Windows.Automation.AutomationProperties.SetAutomationId(centerLayoutButton, "layoutCenter"); layoutActions.Children.Add(centerLayoutButton);
      localLayoutMasterButton = Button("", () => { var local = devices.FirstOrDefault(d => d.Local); if (local != null) Run(() => ChooseLayoutMaster(local)); }); localLayoutMasterButton.Content = UiLocale.Content("设置本机为键鼠主设备"); localLayoutMasterButton.MaxWidth = 240;
      localLayoutMasterButton.Padding = new Thickness(12, 7, 12, 7); localLayoutMasterButton.FontSize = 12; localLayoutMasterButton.Margin = new Thickness(6, 0, 0, 0); localLayoutMasterButton.Visibility = Visibility.Collapsed;
      System.Windows.Automation.AutomationProperties.SetAutomationId(localLayoutMasterButton, "layoutMakeLocalMaster"); layoutActions.Children.Add(localLayoutMasterButton); layoutSurface.Children.Add(layoutActions);
      var canvasSurface = new Border { CornerRadius = new CornerRadius(20), Background = Brush("#F1F5FC"), Child = layoutSurface, Padding = new Thickness(10) }; Grid.SetRow(canvasSurface, 3); body.Children.Add(canvasSurface);
      canvasSurface.SizeChanged += delegate { canvasSurface.Clip = new RectangleGeometry(new Rect(0, 0, canvasSurface.ActualWidth, canvasSurface.ActualHeight), 20, 20); if (devices.Count > 0 && !dragging) Draw(); };
      var bottom = new DockPanel { Margin = new Thickness(4, 12, 0, 0) }; var back = LButton("返回本机  ·  Ctrl + Alt + F12", () => Run(async () => { await Client.Call("release"); await Refresh(); })); back.Padding = new Thickness(10, 7, 10, 7); back.FontSize = 12; back.BorderThickness = new Thickness(0); back.Background = Brushes.Transparent; DockPanel.SetDock(back, Dock.Right); bottom.Children.Add(back); bottom.Children.Add(footer); Grid.SetRow(bottom, 5); body.Children.Add(bottom);
      WindowShell.Wrap(this, grid);
      Deactivated += delegate { if(clipboardToast!=null)clipboardToast.IsOpen=false; };
      Closed += delegate { if(stageReceiveWindow!=null)stageReceiveWindow.Close();if(clipboardToast!=null)clipboardToast.IsOpen=false; };
      canvas.Drop += Dropped; canvas.DragOver += delegate(object sender, DragEventArgs e) { e.Effects = e.Data.GetDataPresent("LanLinkDevice") ? DragDropEffects.Move : DragDropEffects.None; e.Handled = true; };
      SourceInitialized += delegate { ApplyGlass(); };
      poll.Tick += async delegate { await Refresh(); };
      Loaded += async delegate {
        try {
          MakeTray();
          string options = "--data \"" + DataDir + "\"";
          if (Arguments.Contains("--simulate") || (testing && !Arguments.Contains("--native"))) options += " --simulate";
          if (Arguments.Contains("--no-discovery") || testing) options += " --no-discovery";
          if (testing) options += " --port 0";
          serviceOptions = options; ConnectClient();
          await Client.Start(Root, options); await Refresh();
          poll.Start();
          if (testing) await SelfTest();else InitializeFileFeatures();
        } catch (Exception ex) { serviceUnavailable=State==null || !Client.IsRunning;restartServiceButton.Visibility=serviceUnavailable?Visibility.Visible:Visibility.Collapsed;Notify(ex.Message); if (testing) { File.WriteAllText(System.IO.Path.Combine(DataDir, "failure.txt"), ex.ToString()); Run(() => Quit(1)); } }
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
    void InitializeFileFeatures() {
      try {FileShareShell.Register();CheckShareInbox();if(fileDragEdges==null)fileDragEdges=new FileDragEdges(this);fileDragEdges.Update(State);}
      catch(Exception ex){Notify(Locale.T("文件分享入口未能设置：")+DisplayError(ex.Message)+Locale.T(" 仍可从设备菜单发送文件。"));}
    }
    void MakeTray() {
      tray = new Forms.NotifyIcon { Icon = System.Drawing.Icon.ExtractAssociatedIcon(System.Reflection.Assembly.GetExecutingAssembly().Location) ?? System.Drawing.SystemIcons.Application, Text = Locale.T("LanLink · 局域网设备协作"), Visible = true };
      var menu = new Forms.ContextMenuStrip(); menu.Items.Add(Locale.T("打开 LanLink"), null, delegate { Show(); WindowState = WindowState.Normal; Activate(); });
      menu.Items.Add(Locale.T("返回本机键鼠"), null, delegate { Run(async () => { await Client.Call("release"); }); });
      menu.Items.Add(Locale.T("结束对方控制"), null, delegate { Run(async () => { await Client.Call("stop-inbound"); }); });
      menu.Items.Add(Locale.T("退出 LanLink"), null, delegate { Run(async () => { await Quit(0); }); }); tray.ContextMenuStrip = menu;
      tray.DoubleClick += delegate { Show(); WindowState = WindowState.Normal; Activate(); };
    }
    public async Task Quit(int code) { if (quitting) return; quitting = true; poll.Stop(); if (tray != null) tray.Dispose(); await Client.Stop(); Application.Current.Shutdown(code); }
    string updateNoticeVersion="";
    async Task Refresh() {
      if (busy || quitting || dragging || serviceUnavailable) return; busy = true;
      try {
        State = await Client.Call("state"); Theme.Set(Json.Text(State,"theme")); Locale.Set(Json.Text(State,"language"));
var updateInfo=Json.Get(State,"update");if(Json.Text(updateInfo,"phase")=="available"&&Json.Text(updateInfo,"version")!=updateNoticeVersion){updateNoticeVersion=Json.Text(updateInfo,"version");Notify("发现新版本 "+updateNoticeVersion+"，请在设置中下载更新");}
RefreshRequests();clipboardQuick.Visibility=Json.Flag(Json.Get(State,"clipboardSettings"),"showShortcuts")?Visibility.Visible:Visibility.Collapsed;if(clipboardPage!=null && clipboardPage.Visibility==Visibility.Visible)RefreshClipboardPage();
        var shutdownAt = Json.Number(State, "shutdownAt", 0);
        shutdownNotice = shutdownAt > 0 ? Locale.T("收到关机请求，请点击本机设备菜单中的“取消关机”撤销。剩余 ") + Math.Max(0, Math.Ceiling((shutdownAt - (DateTime.UtcNow - new DateTime(1970, 1, 1)).TotalMilliseconds) / 1000)) + Locale.T(" 秒") : "";
        UpdateNotificationBanner();
        string controlled = Json.Text(State, "owner"); var viewers = Json.Get(State,"viewers") as IEnumerable; if(controlled == "" && viewers != null) foreach(var id in viewers) { controlled=Convert.ToString(id); break; }
        UpdateControlledBanner(controlled);
        string active = Json.Text(State, "active");
        footer.Text = Locale.T("键鼠控制：") + (active == "" ? Locale.T("本机") : devices.FirstOrDefault(d => d.Id == active) == null ? Locale.T("远端设备") : devices.First(d => d.Id == active).Name);
        if (Json.Flag(Json.Get(State, "capabilities"), "simulated")) footer.Text += Locale.T("  ·  模拟模式");
        var transfer = Json.Get(State, "transfer"); if (transfer != null) footer.Text += Locale.T("  ·  正在接收 ") + Json.Text(transfer, "name");
        string sig = new System.Web.Script.Serialization.JavaScriptSerializer().Serialize(new object[] { Json.Get(State, "peers"), Json.Get(State, "knownDevices"), Json.Get(State, "edges"), Json.Get(State, "layout"), Json.Get(State, "layoutDevices"), Json.Get(State, "name"), Json.Get(State, "dwell"), Json.Get(State, "master"), Json.Get(State,"masterId"), Json.Get(State,"masterDevices"), active });
        if(stagingPage!=null&&stagingPage.Visibility==Visibility.Visible)await stagingPage.Refresh();if(!testing){CheckShareInbox();if(fileDragEdges!=null)fileDragEdges.Update(State);}
        if (sig == signature) return; signature = sig;if(settingsPage!=null && settingsPage.Visibility==Visibility.Visible && !settingsDirty && !savingSettings && immediateSettingsPending==0)LoadSettingsDraft();
        devices.Clear(); edges.Clear(); foreach (var item in Json.Map(Json.Get(State, "edges"))) edges[item.Key] = Convert.ToString(item.Value);
        devices.Add(new Device { Id = Json.Text(State, "id"), Name = Json.Text(State, "name"), Platform = "win32", Online = true, Local = true });
        foreach (var item in Json.Map(Json.Get(State, "knownDevices"))) devices.Add(new Device { Id = item.Key, Name = Json.Text(item.Value, "name"), Platform = Json.Text(item.Value, "platform") });
        foreach (var item in Json.Get(State, "peers") as IEnumerable ?? new object[0]) {
          string id = Json.Text(item, "id"); var d = devices.FirstOrDefault(x => x.Id == id);
          if (d == null) { d = new Device { Id = id }; devices.Add(d); } d.Name = Json.Text(item, "name"); if (d.Name == "") d.Name = Locale.T("已配对设备"); d.Platform = Json.Text(item, "platform"); d.Online = true; d.Transport = Json.Text(item, "transport"); d.Access=Json.Get(item,"access"); d.Width = Json.Number(Json.Get(item, "capabilities"), "width", 1920); d.Height = Json.Number(Json.Get(item, "capabilities"), "height", 1080);
        }
        var members = Json.Get(State, "layoutDevices") as IEnumerable;
        if (members != null) {
          var group = new List<Device>();
          foreach (var member in members) {
            string id = Json.Text(member, "id"); if (id == "") continue;
            var d = devices.FirstOrDefault(x => x.Id == id) ?? new Device { Id = id };
            d.Name = Json.Text(member, "name"); d.Platform = Json.Text(member, "platform"); d.Local = id == Json.Text(State, "id"); d.Online = d.Local || Json.Flag(member, "online"); group.Add(d);
          }
          devices.Clear(); devices.AddRange(group);
        } else foreach (var id in LayoutCells().Keys) if (!devices.Any(d => d.Id == id)) devices.Add(new Device { Id = id, Name = Locale.T("已设置设备"), Platform = "" });
        Draw();
      } catch (Exception ex) { Notify(ex.Message); } finally { busy = false; }
    }
    public static FrameworkElement DeviceGlyph(Device d, double size) {
      var drawing = new Canvas { Width = size, Height = size, HorizontalAlignment = HorizontalAlignment.Center };
      var shape = new System.Windows.Shapes.Path { Stroke = Brush("#55709E"), StrokeThickness = 1.7, StrokeLineJoin = PenLineJoin.Round, Data = Geometry.Parse(d.Platform == "android" ? "M 9,2 L 23,2 Q 25,2 25,4 L 25,28 Q 25,30 23,30 L 9,30 Q 7,30 7,28 L 7,4 Q 7,2 9,2 M 13,26 L 19,26" : d.Platform == "darwin" ? "M 5,3 L 27,3 L 27,23 L 5,23 Z M 5,23 L 1,28 L 31,28 L 27,23" : "M 3,3 L 29,3 L 29,22 L 3,22 Z M 16,22 L 16,28 M 10,28 L 22,28"), RenderTransform = new ScaleTransform(size / 32, size / 32) }; drawing.Children.Add(shape); return drawing;
    }
    bool layoutMasterSaving;
    async Task ChooseLayoutMaster(Device d) {
      if (layoutMasterSaving || d.Id == MasterId || (d.Platform != "darwin" && d.Platform != "win32")) return;
      layoutMasterSaving = true;
      UpdateLayoutActions();
      try {
        var result = await Client.Call("settings", new { masterId = d.Id });
        await Refresh();
        if (Json.Text(result,"warning") != "") Notify(SettingsResult(result,Locale.T("主控设备已切换")));
        else Toast(Locale.T("主控设备已切换"), 4);
      } finally { layoutMasterSaving = false; UpdateLayoutActions(); }
    }
    void UpdateLayoutActions() {
      var local = devices.FirstOrDefault(d => d.Local);
      if (localLayoutMasterButton != null) { localLayoutMasterButton.Visibility = local != null && local.Id != MasterId ? Visibility.Visible : Visibility.Collapsed; localLayoutMasterButton.IsEnabled = !layoutMasterSaving && !serviceUnavailable; }
      if (centerLayoutButton != null) centerLayoutButton.IsEnabled = local != null && LayoutCells().ContainsKey(local.Id);
    }
    void CenterLayout() {
      var local = devices.FirstOrDefault(d => d.Local); SharedLayoutCell cell;
      if (local == null || layoutGeometry == null || !LayoutCells().TryGetValue(local.Id, out cell)) return;
      layoutScroll.UpdateLayout();
      layoutScroll.ScrollToHorizontalOffset(layoutGeometry.X(cell) - layoutScroll.ViewportWidth / 2);
      layoutScroll.ScrollToVerticalOffset(layoutGeometry.Y(cell) - layoutScroll.ViewportHeight / 2);
    }
    string DeviceType(Device d) { return d.Platform == "win32" ? "Windows" : d.Platform == "darwin" ? "Mac" : d.Platform == "android" ? "Android" : Locale.T("未知"); }
    string OnlineStatus(Device d) {
      if (!d.Online) return "离线";
      if (d.Local) return "在线";
      string status = Json.Text(d.Access, "status");
      return status == "在线" ? "在线" : status == "未授权" || status == "有限授权" ? "未授权" : "未就绪";
    }
    bool Fusion(Device d){return d.Transport=="USB + 局域网";}
    bool MouseHere(Device d){string active=Json.Text(State,"active");return active==""?d.Local:active==d.Id;}
    string MasterId { get { string id = Json.Text(State, "masterId"); return id != "" ? id : Json.Flag(State, "master") ? Json.Text(State, "id") : ""; } }
    string Status(Device d) { return d.Id == MasterId ? (d.Local ? Locale.T("本机 · 主设备") : d.Online ? Locale.T("主设备") : Locale.T("主设备 · 离线")) : d.Local ? Locale.T("本机") : Json.Text(State, "active") == d.Id ? (Fusion(d)?Locale.T("● 融合控制中"):Locale.T("● 正在控制")) : d.Online ? "● " + (Json.Text(d.Access,"status")==""?Locale.T("未就绪"):Locale.T(Json.Text(d.Access,"status"))) : Locale.T("● 离线"); }
    string StatusColor(Device d) {
      if(d.Local || !d.Online)return "#8290A7";
      if(Json.Text(State,"active")==d.Id)return "#487AC4";
      switch(Json.Text(d.Access,"status")) {
        case "在线":return "#368769";
        case "未授权":return "#BF6371";
        case "有限授权":return "#8270B9";
        default:return "#B58A39";
      }
    }
    Border DeviceView(Device d, bool compact) {
      var panel = new StackPanel { Orientation = compact ? Orientation.Horizontal : Orientation.Vertical, HorizontalAlignment = compact ? HorizontalAlignment.Left : HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
      var icon = DeviceGlyph(d, compact ? 28 : 34); icon.HorizontalAlignment = HorizontalAlignment.Center; icon.Margin = compact ? new Thickness(0, 0, 14, 0) : new Thickness(0, 0, 0, 6); panel.Children.Add(icon);
      var words = new StackPanel { VerticalAlignment = VerticalAlignment.Center }; var name = Text(d.Name, compact ? 14 : 16); name.MaxWidth = compact ? 136 : 206; name.FontWeight = FontWeights.SemiBold; name.HorizontalAlignment = compact ? HorizontalAlignment.Left : HorizontalAlignment.Center; words.Children.Add(name);
      var status = Text(Status(d), 12, StatusColor(d)); status.Margin = new Thickness(0, 6, 0, 0); status.HorizontalAlignment = compact ? HorizontalAlignment.Left : HorizontalAlignment.Center; words.Children.Add(status); panel.Children.Add(words);
      var border = new Border { Child = panel, Tag = d.Id, CornerRadius = new CornerRadius(18), BorderThickness = new Thickness(1), BorderBrush = Brush(d.Id == MasterId ? "#7B70C5" : selected == d.Id ? "#8DA7ED" : "#DFE6F1"), Background = Brush(d.Local ? "#E9F0FE" : "#FFFFFF"), Padding = new Thickness(compact ? 18 : 14), Cursor = Cursors.Hand, Focusable = true, ToolTip = Locale.T("点击操作；拖动到网格设置布局") };
      System.Windows.Automation.AutomationProperties.SetName(border, d.Name + " " + Status(d));
      System.Windows.Automation.AutomationProperties.SetAutomationId(border, "layoutDevice-" + d.Id);
      if (compact) { border.Width = 212; border.Height = 78; border.Margin = new Thickness(0, 0, 10, 0); }
      else { border.Width = d.Platform == "android" ? 164 : 248; border.Height = d.Platform == "android" ? 170 : 148; }
      if(!compact) {
        border.Child=null;var content=new Grid();content.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto }); content.RowDefinitions.Add(new RowDefinition()); Grid.SetRow(panel, 1); content.Children.Add(panel);
        var badges = new WrapPanel { MinHeight = 22, HorizontalAlignment = HorizontalAlignment.Center, IsHitTestVisible = false }; content.Children.Add(badges);
        if (d.Id == MasterId) {
          var masterBadge = new Border { Background=Brush("#EEEBFA"), CornerRadius=new CornerRadius(10), Padding=new Thickness(7,3,7,3), Margin=new Thickness(0,0,4,2), Child=LText("主设备",12,"#6554AC") };
          System.Windows.Automation.AutomationProperties.SetAutomationId(masterBadge,"layoutMasterBadge"); badges.Children.Add(masterBadge);
        }
        if (MouseHere(d)) { var badge=new Border{Background=Brush("#E6EAF9"),CornerRadius=new CornerRadius(10),Padding=new Thickness(7,3,7,3),Margin=new Thickness(0,0,0,2),Child=LText("↖ 鼠标在此",12,"#6174AA")}; System.Windows.Automation.AutomationProperties.SetAutomationId(badge,"layoutMouseBadge"); badges.Children.Add(badge); }
        border.Child=content;
      }
      Point start = new Point(); bool down = false;
      border.MouseLeftButtonDown += delegate(object sender, MouseButtonEventArgs e) { down = true; start = e.GetPosition(border); border.Focus(); };
      border.MouseMove += delegate(object sender, MouseEventArgs e) {
        if (!down || e.LeftButton != MouseButtonState.Pressed) return;
        if ((e.GetPosition(border) - start).Length < 7) return;
        down = false; dragging = true;
        try { DragDrop.DoDragDrop(border, new DataObject("LanLinkDevice", d.Id), DragDropEffects.Move); }
        finally { dragging = false; }
      };
      border.MouseLeftButtonUp += delegate { if (down) { down = false;
        selected = d.Id; OpenLayoutActions(d, border);
      } };
      border.MouseRightButtonUp += delegate { OpenMenu(d, border); };
      border.KeyDown += delegate(object sender, KeyEventArgs e) { if (e.Key == Key.Enter || e.Key == Key.Space) { OpenLayoutActions(d, border); e.Handled = true; } };
      if(!d.Local){border.AllowDrop=true;string fileSession=null;bool offered=false;var committed=new HashSet<string>();
        border.DragEnter+=delegate(object sender,DragEventArgs e){if(!e.Data.GetDataPresent(DataFormats.FileDrop)||!d.Online||fileSession!=null)return;fileSession=Guid.NewGuid().ToString();offered=true;string session=fileSession;var files=e.Data.GetData(DataFormats.FileDrop) as string[];if(files!=null)Run(async()=>{Exception failure=null;try{await Client.Call("stage-share",new{id=d.Id,files=files,start=false,session=session});NotifyTransient(Locale.T("待传输文件已放到 ")+d.Name+Locale.T(" 的暂存区"));}catch(Exception ex){failure=ex;}if(fileSession!=session&&!committed.Remove(session))await Client.Call("stage-drag-end",new{session=session});if(failure!=null)throw failure;});e.Effects=d.Online?DragDropEffects.Copy:DragDropEffects.None;e.Handled=true;};
        border.DragOver+=delegate(object sender,DragEventArgs e){if(e.Data.GetDataPresent(DataFormats.FileDrop)){e.Effects=d.Online?DragDropEffects.Copy:DragDropEffects.None;e.Handled=true;}};
        Action finishFileDrag=()=>{if(offered&&fileSession!=null){string session=fileSession;fileSession=null;offered=false;Run(async()=>{await Client.Call("stage-drag-end",new{session=session});});}};border.DragLeave+=delegate(object sender,DragEventArgs e){var point=e.GetPosition(border);if(point.X<0||point.Y<0||point.X>=border.ActualWidth||point.Y>=border.ActualHeight)finishFileDrag();e.Handled=true;};border.Drop+=delegate(object sender,DragEventArgs e){if(e.Data.GetDataPresent(DataFormats.FileDrop)){if(fileSession!=null){committed.Add(fileSession);fileSession=null;offered=false;}e.Effects=d.Online?DragDropEffects.Copy:DragDropEffects.None;e.Handled=true;}};
      }
      return border;
    }
    Dictionary<string, SharedLayoutCell> LayoutCells() {
      var result = new Dictionary<string, SharedLayoutCell>(); var layout = Json.Get(State, "layout");
      if (layout != null) {
        foreach (var item in Json.Map(Json.Get(layout, "positions"))) {
          double x = Json.Number(item.Value, "x", 100), y = Json.Number(item.Value, "y", 100);
          if (x >= -32 && x <= 32 && y >= -32 && y <= 32 && x == Math.Truncate(x) && y == Math.Truncate(y)) result[item.Key] = new SharedLayoutCell((int)x, (int)y);
        }
      } else {
        result[Json.Text(State, "id")] = new SharedLayoutCell(0, 0);
        foreach (var edge in edges) { var cell = new SharedLayoutCell(0, 0).Adjacent(edge.Key); if (edge.Value != "" && cell.HasValue) result[edge.Value] = cell.Value; }
      }
      return result;
    }
    void DrawConnection(Point from, Point to, Size fromSize, Size toSize, bool active, bool fusion, bool online) {
      Vector delta = to - from; double length = Math.Max(1, delta.Length); var normal = new Vector(-delta.Y / length, delta.X / length);
      bool horizontal = Math.Abs(delta.X) > Math.Abs(delta.Y);
      var direction = delta / length;
      from += direction * ((horizontal ? fromSize.Width : fromSize.Height) / 2 + 3);
      to -= direction * ((horizontal ? toSize.Width : toSize.Height) / 2 + 3);
      foreach (double offset in fusion ? new[] { -3.0, 3.0 } : new[] { 0.0 }) {
        var a = from + normal * offset; var b = to + normal * offset;
        canvas.Children.Add(new System.Windows.Shapes.Line { X1 = a.X, Y1 = a.Y, X2 = b.X, Y2 = b.Y, Stroke = Brush(online ? "#A9BBDD" : "#D8DDE7"), StrokeThickness = 1.5, IsHitTestVisible = false });
        if (active) {
          var flow = new System.Windows.Shapes.Line { X1 = a.X, Y1 = a.Y, X2 = b.X, Y2 = b.Y, Stroke = Brush("#7B89C9"), StrokeThickness = 2, StrokeDashArray = new DoubleCollection(new[] { 3.0, 8.0 }), IsHitTestVisible = false };
          if (SystemParameters.ClientAreaAnimation) flow.BeginAnimation(System.Windows.Shapes.Shape.StrokeDashOffsetProperty, new DoubleAnimation(0, -11, TimeSpan.FromSeconds(.9)) { RepeatBehavior = RepeatBehavior.Forever }); canvas.Children.Add(flow);
        }
      }
    }
    Size LayoutCardSize(Device d) { return new Size((d.Platform == "android" ? 164 : 248) * layoutGeometry.Scale, (d.Platform == "android" ? 170 : 148) * layoutGeometry.Scale); }
    void Draw() {
      bubbles.Children.Clear(); canvas.Children.Clear();
      UpdateLayoutActions();
      var positions = LayoutCells();
      double width = layoutScroll == null ? 720 : Math.Max(300, layoutScroll.ActualWidth - 18), height = layoutScroll == null ? 420 : Math.Max(260, layoutScroll.ActualHeight - 18);
      layoutGeometry = new SharedLayoutGeometry(width, height, positions.Values); var geometry = layoutGeometry;
      canvas.Width = geometry.Width; canvas.Height = geometry.Height;
      for (int x = geometry.MinX; x <= geometry.MaxX; x++) for (int y = geometry.MinY; y <= geometry.MaxY; y++) {
        var cell = new SharedLayoutCell(x, y); var outline = new Rectangle { Width = geometry.StepX - 8, Height = geometry.StepY - 8, RadiusX = 12, RadiusY = 12, Stroke = Brush("#D6E0F2"), StrokeThickness = 1, StrokeDashArray = new DoubleCollection(new[] { 3.0, 5.0 }), IsHitTestVisible = false };
        Canvas.SetLeft(outline, geometry.X(cell) - geometry.StepX / 2 + 4); Canvas.SetTop(outline, geometry.Y(cell) - geometry.StepY / 2 + 4); canvas.Children.Add(outline);
      }
      foreach (var d in devices) bubbles.Children.Add(DeviceView(d, true));
      var ids = positions.Keys.OrderBy(id => id).ToArray();
      for (int i = 0; i < ids.Length; i++) for (int j = i + 1; j < ids.Length; j++) {
        var a = positions[ids[i]]; var b = positions[ids[j]];
        if (Math.Abs(a.X - b.X) + Math.Abs(a.Y - b.Y) != 1) continue;
        var first = devices.FirstOrDefault(d => d.Id == ids[i]); var second = devices.FirstOrDefault(d => d.Id == ids[j]);
        if (first != null && second != null) DrawConnection(new Point(geometry.X(a), geometry.Y(a)), new Point(geometry.X(b), geometry.Y(b)), LayoutCardSize(first), LayoutCardSize(second), Json.Text(State, "active") == first.Id || Json.Text(State, "active") == second.Id, Fusion(first) || Fusion(second), first.Online && second.Online);
      }
      foreach (var d in devices) {
        SharedLayoutCell cell; if (!positions.TryGetValue(d.Id, out cell)) continue;
        var tile = DeviceView(d, false); tile.LayoutTransform = new ScaleTransform(geometry.Scale, geometry.Scale);
        Canvas.SetLeft(tile, geometry.X(cell) - tile.Width * geometry.Scale / 2); Canvas.SetTop(tile, geometry.Y(cell) - tile.Height * geometry.Scale / 2); canvas.Children.Add(tile);
      }
      if (positions.Count == 0) { var empty = LText("将上方设备拖入网格，或点击设备调整方位", 14, "#8393AF"); Canvas.SetLeft(empty, canvas.Width / 2 - 170); Canvas.SetTop(empty, canvas.Height / 2); canvas.Children.Add(empty); }
      if (!layoutInitiallyCentered && devices.Any(d => d.Local && positions.ContainsKey(d.Id))) { layoutInitiallyCentered = true; Dispatcher.BeginInvoke(new Action(CenterLayout), DispatcherPriority.Loaded); }
    }
    void Dropped(object sender, DragEventArgs e) {
      if (!e.Data.GetDataPresent("LanLinkDevice") || layoutGeometry == null) return;
      string id = Convert.ToString(e.Data.GetData("LanLinkDevice")); if (!devices.Any(d => d.Id == id)) return;
      var point = e.GetPosition(canvas); var cell = layoutGeometry.Cell(point.X, point.Y); if (!cell.HasValue) return; e.Handled = true;
      Run(async () => { await Client.Call("layout-place", new { id = id, x = cell.Value.X, y = cell.Value.Y }); signature = ""; dragging = false; await Refresh(); Toast(Locale.T("布局已保存"), 3); });
    }
    MenuItem Menu(string title, Func<Task> action, bool enabled) {
      string icon = title.Contains(Locale.T("关机")) ? "power" : title.Contains(Locale.T("文件")) ? "file" : title.Contains(Locale.T("粘贴")) ? "paste" : title.Contains(Locale.T("布局")) || title.Contains(Locale.T("侧")) || title.Contains(Locale.T("方")) ? "layout" : title.Contains(Locale.T("返回")) ? "return" : title.Contains(Locale.T("键鼠")) ? "pointer" : "devices";
      var item = new MenuItem { Header = title, IsEnabled = enabled, Icon = WindowShell.Glyph(icon, 18) }; item.Click += delegate { Run(action); }; return item;
    }
    string pendingFeatureId; Func<Task> pendingFeature;
    internal bool IsCurrentAccessRequest(string requestId) { return lastAccess==requestId; }
    public void CancelFeature(string id) { if(pendingFeatureId==id){pendingFeatureId=null;pendingFeature=null;} }
    public async Task ResumeFeature(string id) {if(pendingFeatureId==id && pendingFeature!=null)await pendingFeature();}
    async Task PrepareFeature(Device device,string[] keys,Func<Task> action) {
      if(pendingFeatureId!=null && pendingFeatureId!=device.Id){NotifyTransient(Locale.T("请先完成或取消当前设备的连接请求"));return;}
      pendingFeatureId=device.Id;pendingFeature=()=>PrepareFeature(device,keys,action);
      try {
        var result=await Client.Call("feature-prepare",new {id=device.Id,permissions=keys});
        if(!Json.Flag(result,"ready")){await Refresh();return;}
        if(!Json.Flag(result,"allowed")) {
          if(Confirm(this,Locale.T("需要设备授权"),Locale.T("此功能尚未获准使用，请在「")+device.Name+Locale.T("」上授权。点击发起授权后，对方将收到请求。"))) {
            await Client.Call("access-request",new {id=device.Id,permissions=keys});await Refresh();
          } else CancelFeature(device.Id);
          return;
        }
        CancelFeature(device.Id);await action();
      } catch {CancelFeature(device.Id);throw;}
    }
    MenuItem Feature(Device device,string title,string[] keys,Func<Task> action) {
      var permissions=Json.Get(device.Access,"permissions");
      bool restricted=keys.Any(k=>!Json.Flag(Json.Get(permissions,k),"peerAllowed") || !Json.Flag(Json.Get(permissions,k),"systemGranted") || !Json.Flag(Json.Get(permissions,k),"appEnabled"));
      var item=Menu(title,()=>PrepareFeature(device,keys,action),device.Online);
      if(restricted){item.Foreground=Brush("#98A1B1");item.ToolTip=UiLocale.Content("尚未就绪或授权 · 点击查看");}
      return item;
    }
    FrameworkElement PositionPicker(Device d, ContextMenu menu) {
      var panel = new StackPanel { Width = 250, Margin = new Thickness(4) }; var positions = LayoutCells();
      var anchors = devices.Where(device => device.Id != d.Id && positions.ContainsKey(device.Id)).ToArray();
      if (anchors.Length == 0) {
        panel.Children.Add(LButton("放到中心", () => { menu.IsOpen = false; Run(async () => { await Client.Call("layout-place", new { id = d.Id, x = 0, y = 0 }); await Refresh(); }); })); return panel;
      }
      panel.Children.Add(LText("相对设备", 12, "#7C879C"));
      var picker = new ComboBox { Margin = new Thickness(0, 6, 0, 8), MinWidth = 220 };
      foreach (var anchor in anchors) picker.Items.Add(new ComboBoxItem { Content = anchor.Name, Tag = anchor.Id });
      picker.SelectedIndex = Math.Max(0, Array.FindIndex(anchors, anchor => anchor.Id == MasterId)); panel.Children.Add(picker);
      var grid = new Grid { Height = 150 };
      for (int i = 0; i < 3; i++) { grid.RowDefinitions.Add(new RowDefinition()); grid.ColumnDefinitions.Add(new ColumnDefinition()); }
      string[] keys = { "top", "left", "right", "bottom" }; string[] labels = { Locale.T("↑ 上方"), Locale.T("← 左侧"), Locale.T("右侧 →"), Locale.T("↓ 下方") }; int[] rows = { 0, 1, 1, 2 }, cols = { 1, 0, 2, 1 };
      var buttons = new List<Button>();
      for (int i = 0; i < 4; i++) {
        string edge = keys[i]; var button = Button(labels[i], () => {
          var selected = picker.SelectedItem as ComboBoxItem; if (selected == null) return;
          var cell = positions[Convert.ToString(selected.Tag)].Adjacent(edge); if (!cell.HasValue) return;
          menu.IsOpen = false; Run(async () => { await Client.Call("layout-place", new { id = d.Id, x = cell.Value.X, y = cell.Value.Y }); await Refresh(); });
        });
        button.Tag = edge; button.Padding = new Thickness(4); button.Margin = new Thickness(2); button.FontSize = 12; Grid.SetRow(button, rows[i]); Grid.SetColumn(button, cols[i]); grid.Children.Add(button); buttons.Add(button);
      }
      Action update = () => { var selected = picker.SelectedItem as ComboBoxItem; foreach (var button in buttons) button.IsEnabled = selected != null && positions[Convert.ToString(selected.Tag)].Adjacent(Convert.ToString(button.Tag)).HasValue; };
      picker.SelectionChanged += delegate { update(); }; update(); panel.Children.Add(grid); return panel;
    }
    internal void RemoteClosing(Task completion) { remoteCleanup = Task.WhenAll(remoteCleanup, completion); }
    async Task OpenRemote(Device device) {
      int version = ++remoteOpenVersion;
      var remote = Application.Current.Windows.OfType<RemoteWindow>().FirstOrDefault();
      if (remote != null && remote.DeviceId == device.Id) { if (remote.WindowState == WindowState.Minimized) remote.WindowState = WindowState.Normal; remote.Activate(); return; }
      if (remote != null) { remote.Close(); RemoteClosing(remote.Completion); }
      await remoteCleanup;
      if (version != remoteOpenVersion || quitting) return;
      new RemoteWindow(this, device).Show();
    }
    MenuItem MouseControlAction(Device d) {
      if (d.Local) return Menu(Locale.T("返回本机键鼠"), async () => { await Client.Call("release"); await Refresh(); }, true);
      return Feature(d,Locale.T("切换键鼠到此设备"),new[]{"input"}, async () => { if (!ConfirmInputMode(d, this)) return; await Client.Call("control", new { id = d.Id, capture = true, newSession = true, fallbackAccepted = InputMode(d.Id) == "accessibility-limited" }); });
    }
    MenuItem SendFilesAction(Device d) {
      if (d.Local) { var localFiles = Menu(Locale.T("发送文件…"), () => Task.FromResult(0), false); localFiles.ToolTip = Locale.T("请选择远程设备发送文件"); return localFiles; }
      return Feature(d,Locale.T("发送文件…"),new[]{"clipboard"}, async () => {
        var dialog = new Microsoft.Win32.OpenFileDialog { Multiselect = true, Title = Locale.T("选择文件：发送到对方暂存区") };
        if (dialog.ShowDialog(this) != true) return;
        var result=await Client.Call("stage-share", new { id = d.Id, files = dialog.FileNames }); NotifyTransient(Json.Text(result,"message"));
      });
    }
    Button LayoutActionButton(MenuItem action, string title, string icon, string id, string subtitle = "", bool primary = false) {
      var button = Button("", () => { if (layoutActionsPopup != null) layoutActionsPopup.IsOpen = false; action.RaiseEvent(new RoutedEventArgs(MenuItem.ClickEvent)); });
      button.IsEnabled = action.IsEnabled; button.ToolTip = action.ToolTip; ToolTipService.SetShowOnDisabled(button, true);
      button.HorizontalContentAlignment = HorizontalAlignment.Stretch; button.Padding = new Thickness(10); button.Margin = new Thickness(0, 4, 0, 4); button.Background = Brush(primary ? "#E8EFFE" : "#FFFFFF");
      var row = new Grid(); row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto }); row.ColumnDefinitions.Add(new ColumnDefinition()); row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
      var image = new Border { Child = WindowShell.Glyph(icon, 20), CornerRadius = new CornerRadius(9), Background = Brush("#EDF2FC"), Padding = new Thickness(8), Margin = new Thickness(0, 0, 10, 0) }; row.Children.Add(image);
      var words = new StackPanel { VerticalAlignment = VerticalAlignment.Center }; var heading = LText(title, 13, primary ? "#4169CE" : "#233957"); heading.FontWeight = FontWeights.SemiBold; words.Children.Add(heading);
      if (subtitle != "") { var detail = LText(subtitle, 11, "#8290A7"); detail.Margin = new Thickness(0, 3, 0, 0); words.Children.Add(detail); }
      Grid.SetColumn(words, 1); row.Children.Add(words); var arrow = Text("›", 18, "#8B97AB"); arrow.Margin = new Thickness(8, 0, 0, 0); Grid.SetColumn(arrow, 2); row.Children.Add(arrow); button.Content = row;
      System.Windows.Automation.AutomationProperties.SetAutomationId(button, id); System.Windows.Automation.AutomationProperties.SetName(button, Locale.T(title) + " " + Locale.T(subtitle)); return button;
    }
    Border BuildLayoutActions(Device d) {
      var body = new StackPanel(); var info = new Grid { Margin = new Thickness(2, 2, 2, 12) }; info.ColumnDefinitions.Add(new ColumnDefinition()); info.ColumnDefinitions.Add(new ColumnDefinition());
      var type = new StackPanel(); type.Children.Add(LText("设备类型：", 10, "#8290A7")); var typeRow = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 6, 0, 0) }; var deviceIcon = DeviceGlyph(d, 18); deviceIcon.Margin = new Thickness(0, 0, 7, 0); typeRow.Children.Add(deviceIcon);
      var typeText = Text(DeviceType(d), 14); typeText.FontWeight = FontWeights.SemiBold; System.Windows.Automation.AutomationProperties.SetAutomationId(typeText, "layoutDeviceType"); typeRow.Children.Add(typeText); type.Children.Add(typeRow); info.Children.Add(type);
      string status = OnlineStatus(d), color = status == "在线" ? "#368769" : status == "未授权" ? "#BF6371" : status == "离线" ? "#8290A7" : "#B58A39";
      var online = new StackPanel { HorizontalAlignment = HorizontalAlignment.Right }; online.Children.Add(LText("在线状态：", 10, "#8290A7")); var statusRow = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 6, 0, 0) }; statusRow.Children.Add(new Ellipse { Width = 6, Height = 6, Fill = Brush(color), Margin = new Thickness(0, 0, 6, 0), VerticalAlignment = VerticalAlignment.Center }); var statusText = LText(status, 13, color); System.Windows.Automation.AutomationProperties.SetAutomationId(statusText, "layoutDeviceStatus"); statusRow.Children.Add(statusText); online.Children.Add(statusRow); Grid.SetColumn(online, 1); info.Children.Add(online); body.Children.Add(info);
      body.Children.Add(new Border { Height = 1, Background = Brush("#E3E8F3"), Margin = new Thickness(0, 0, 0, 8) });
      body.Children.Add(LayoutActionButton(MouseControlAction(d), "控制鼠标：", "pointer", "layoutMouseAction", d.Local ? "设备鼠标" : "远程鼠标控制"));
      body.Children.Add(LayoutActionButton(SendFilesAction(d), "发送文件…", "file", "layoutFileAction"));
      if (d.Id != MasterId) { var master = Menu(Locale.T("设置此设备为键鼠主设备"), () => ChooseLayoutMaster(d), (d.Platform == "darwin" || d.Platform == "win32") && !layoutMasterSaving); if (d.Platform == "android") master.ToolTip = Locale.T("Android 暂不支持作为键鼠主设备"); body.Children.Add(LayoutActionButton(master, "设置此设备为键鼠主设备", "keyboard", "layoutMasterAction", "", true)); }
      var card = new Border { Child = body, Width = 280, Padding = new Thickness(16), CornerRadius = new CornerRadius(16), BorderThickness = new Thickness(1), BorderBrush = Brush("#DFE6F1"), Background = Brush("#F8FAFF"), Resources = Resources }; System.Windows.Documents.TextElement.SetFontFamily(card, FontFamily); return card;
    }
    void OpenLayoutActions(Device d, FrameworkElement anchor) {
      if (layoutDeviceMenu != null) layoutDeviceMenu.IsOpen = false; if (layoutActionsPopup != null) layoutActionsPopup.IsOpen = false;
      selected = d.Id; layoutActionsPopup = new Popup { Child = BuildLayoutActions(d), PlacementTarget = anchor, Placement = PlacementMode.Bottom, VerticalOffset = 8, AllowsTransparency = true, StaysOpen = false, PopupAnimation = PopupAnimation.Fade }; layoutActionsPopup.IsOpen = true;
    }
    public ContextMenu BuildMenu(Device d) {
      var menu = new ContextMenu { Resources = Resources }; WindowShell.GlassMenu(menu, this); menu.Items.Add(new MenuItem { Header = d.Name, FontWeight = FontWeights.SemiBold, FontSize = 15, IsHitTestVisible = false, Focusable = false, Icon = DeviceGlyph(d, 19) }); menu.Items.Add(new Separator());
      menu.Items.Add(Menu(Locale.T("设备详情与权限"), () => { ShowDevicePermissions(); return Task.FromResult(0); }, true));
      if (d.Platform == "darwin" || d.Platform == "win32") menu.Items.Add(Menu(Locale.T(d.Id == MasterId ? "主控电脑" : "设为主控电脑"), () => ChooseLayoutMaster(d), d.Id != MasterId && !layoutMasterSaving));
      if (d.Local) {
        menu.Items.Add(MouseControlAction(d));
        menu.Items.Add(Menu(Locale.T("取消关机"), async () => { await Client.Call("cancel-shutdown"); NotifyTransient(Locale.T("关机已取消")); }, Json.Number(State, "shutdownAt", 0) > 0));
        menu.Items.Add(Menu(Locale.T("组与配对"), Pairing, true));
      } else {
        menu.Items.Add(Feature(d,Locale.T("桌面控制"),new[]{"input","screen"}, () => OpenRemote(d)));
        menu.Items.Add(MouseControlAction(d));

        menu.Items.Add(Feature(d,Locale.T("粘贴共享内容"),new[]{"input","clipboard"}, async () => { await Client.Call("device-paste", new { id = d.Id }); NotifyTransient(Locale.T("已向 ") + d.Name + Locale.T(" 粘贴共享内容")); }));
        menu.Items.Add(SendFilesAction(d));
        menu.Items.Add(new Separator());
        menu.Items.Add(Menu(Locale.T("移出设备…"), async () => { if (!Confirm(this,Locale.T("移出设备"),Locale.T("将 ")+d.Name+Locale.T(" 移出组？该设备需要重新配对，其他离线成员会保留身份。"))) return; await Client.Call("device-remove",new { id=d.Id }); await Refresh(); NotifyTransient(Locale.T("设备已移出组")); },true));
        if (d.Platform == "win32" || d.Platform == "darwin") {
          menu.Items.Add(new Separator()); menu.Items.Add(Feature(d,Locale.T("关机…"),new[]{"shutdown"}, async () => { if (!Confirm(this, Locale.T("确认关机"), Locale.T("请求 ") + d.Name + Locale.T(" 在 30 秒后关机？请先保存目标设备中的工作。"))) return; await Client.Call("shutdown", new { id = d.Id }); NotifyTransient(Locale.T("已发送关机请求，可在设备菜单中取消")); }));
          menu.Items.Add(Menu(Locale.T("取消关机"), async () => { await Client.Call("cancel-shutdown", new { id = d.Id }); NotifyTransient(Locale.T("已取消关机请求")); }, d.Online));
        }
      }
      menu.Items.Add(new Separator());
      var layout = new MenuItem { Header = Locale.T("调整方位"), Icon = WindowShell.Glyph("layout", 18) }; layout.Items.Add(new MenuItem { Header = PositionPicker(d, menu), StaysOpenOnClick = true, Padding = new Thickness(4) }); menu.Items.Add(layout);
      menu.Items.Add(Menu(Locale.T("移出布局"), async () => { await Client.Call("layout-remove", new { id = d.Id }); await Refresh(); }, LayoutCells().ContainsKey(d.Id)));
      return menu;
    }
    void OpenMenu(Device d, FrameworkElement anchor) { if (layoutActionsPopup != null) layoutActionsPopup.IsOpen = false; selected = d.Id; foreach (var card in bubbles.Children.OfType<Border>()) card.BorderBrush = Brush(Convert.ToString(card.Tag) == selected ? "#8DA7ED" : "#E1E7F0"); if (layoutDeviceMenu != null) layoutDeviceMenu.IsOpen = false; var menu = BuildMenu(d); layoutDeviceMenu = menu; menu.VerticalOffset = 8; menu.PlacementTarget = anchor; menu.Placement = PlacementMode.Bottom; menu.IsOpen = true; }
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
      var cancel = LButton("取消", () => { window.DialogResult = false; }); cancel.Width = 104; cancel.Margin = new Thickness(0, 0, 10, 0); cancel.IsCancel = true; buttons.Children.Add(cancel);
      var accept = Button(title==Locale.T("需要设备授权")?Locale.T("发起授权"):Locale.T("确认"), () => { window.DialogResult = true; }); accept.Width = 104; accept.Style = (Style)FindResource("Primary"); buttons.Children.Add(accept); panel.Children.Add(buttons); return window;
    }
    internal bool Confirm(Window owner, string title, string message) { return ConfirmationWindow(owner, title, message).ShowDialog() == true; }
    void UpdateControlledBanner(string id) {
      controlledBanner.Visibility = string.IsNullOrEmpty(id) ? Visibility.Collapsed : Visibility.Visible;
      var peer = (Json.Get(State, "peers") as IEnumerable ?? new object[0]).Cast<object>().FirstOrDefault(p => Json.Text(p, "id") == id);
      string name = Json.Text(peer, "name"); if (name == "") name = Locale.T("其他设备");
      controlledTitle.Text = Locale.T("正在被控制\n") + name + Locale.T(" 正在操作或查看本机");
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
      return Confirm(owner, Locale.T("使用无障碍备用控制？"), Locale.T("此手机尚未启用 Root / ADB 增强控制。无障碍备用模式支持点击、长按和滑动；拖动会在松开鼠标后执行，悬停、游戏和复杂快捷键受限。此旧版手机请升级到 0.3.9 并启用 ADB / Root 输入助手。\n\n可在“安卓授权”页面开启增强控制。继续使用备用模式？"));
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
      foreach(var item in new[] {homeNavigation,permissionsNavigation,adbNavigation,settingsNavigation,informationNavigation,clipboardNavigation,stagingNavigation}) if(item!=null) item.Background=item==active?Brush("#E1EAFB"):Brushes.Transparent;
      if(stagingPage!=null)stagingPage.Visibility=Visibility.Collapsed;
      if(clipboardPage!=null)clipboardPage.Visibility=Visibility.Collapsed;
      if(adbHub!=null) adbHub.Visibility=Visibility.Collapsed;
      if(informationPage!=null) informationPage.Visibility=Visibility.Collapsed;
    }
    Grid SimplePage(string title, out StackPanel body) {
      var page=new Grid(); page.RowDefinitions.Add(new RowDefinition{Height=GridLength.Auto});page.RowDefinitions.Add(new RowDefinition());
      var heading=LText(title,26);heading.Margin=new Thickness(0,0,0,22);page.Children.Add(heading);
      body=new StackPanel();var scroll=new ScrollViewer{Content=body,VerticalScrollBarVisibility=ScrollBarVisibility.Auto};Grid.SetRow(scroll,1);page.Children.Add(scroll);return page;
    }
    void HideContent() { devicePage.Visibility=Visibility.Collapsed;if(stagingPage!=null)stagingPage.Visibility=Visibility.Collapsed;if(settingsPage!=null)settingsPage.Visibility=Visibility.Collapsed;if(adbPage!=null)adbPage.Visibility=Visibility.Collapsed;if(devicePermissionsPage!=null)devicePermissionsPage.Visibility=Visibility.Collapsed; }
    public void ShowStaging(){Navigate(stagingNavigation);HideContent();if(stagingPage==null){stagingPage=new StagingPage(this);((Grid)pageSurface.Child).Children.Add(stagingPage);}stagingPage.Visibility=Visibility.Visible;Run(stagingPage.Refresh);}
    public Task PrepareFileShare(Device d,string[] files){return PrepareFeature(d,new[]{"clipboard"},async()=>{var r=await Client.Call("stage-share",new{id=d.Id,files=files});NotifyTransient(Json.Text(r,"message"));});}
    void CheckShareInbox(){foreach(var files in FileShareShell.Drain()){Show();Activate();new FileShareWindow(this,files,devices).Show();}}
    public void ShowAdbHub() {
      Navigate(adbNavigation);HideContent();var pages=(Grid)pageSurface.Child;if(adbHub!=null)pages.Children.Remove(adbHub);
      StackPanel body;adbHub=SimplePage("安卓授权",out body);pages.Children.Add(adbHub);
      var hint=LText("选择本组手机，为其启用键鼠操作与自动恢复。",13,"#7B879D");hint.Margin=new Thickness(0,0,0,18);body.Children.Add(hint);
      var list = new StackPanel(); body.Children.Add(list);
      Run(async () => {
        var result = await Client.Call("adb-group");
        foreach (var entry in Json.Get(result,"devices") as System.Collections.IEnumerable ?? new object[0]) {
          string id = Json.Text(entry,"id"); var phone = devices.FirstOrDefault(x=>x.Id==id); if(phone==null) continue;
          var card = new StackPanel(); card.Children.Add(Text(phone.Name + (Json.Flag(entry,"online") ? Locale.T(" · 在线") : Locale.T(" · 离线")),16));
          var state = Text(Json.Text(entry,"status"),13, Json.Flag(entry,"authorized") ? "#45745F" : "#7B879D"); state.Margin=new Thickness(0,10,0,0); card.Children.Add(state);
          string setupMessage=Json.Text(entry,"message");if(setupMessage!=""){var setupHint=Text(setupMessage,13,"#71819A");setupHint.TextWrapping=TextWrapping.Wrap;setupHint.Margin=new Thickness(0,8,0,0);card.Children.Add(setupHint);}
          if (!Json.Flag(entry,"ready")) { var button=LButton("连接授权",()=>Run(async()=>{await Client.Call("adb-guide-open",new {id=phone.Id});await Refresh();})); button.IsEnabled=phone.Online; button.HorizontalAlignment=HorizontalAlignment.Left; button.Margin=new Thickness(0,14,0,0); card.Children.Add(button); }
          list.Children.Add(SettingsCard("安卓设备",card));
        }
        if(list.Children.Count==0)list.Children.Add(LText("本组暂无安卓设备，请在我的信息中添加手机。",14,"#7B879D"));
      });
      var actions = new StackPanel { Orientation=Orientation.Horizontal, Margin=new Thickness(0,16,0,0) };
      var help=Button("",()=>ShowAdb(null));var helpText=LText("帮助其他组的设备ADB授权",12,"#7B879D");helpText.TextDecorations=TextDecorations.Underline;help.Content=helpText;help.Background=Brushes.Transparent;help.BorderThickness=new Thickness(0);help.Padding=new Thickness(4);help.HorizontalAlignment=HorizontalAlignment.Right;help.Margin=new Thickness(0,12,0,0);adbHub.RowDefinitions.Add(new RowDefinition{Height=GridLength.Auto});Grid.SetRow(help,2);adbHub.Children.Add(help);
      actions.Children.Add(LButton("刷新状态",()=>Run(async()=>{await Refresh();ShowAdbHub();}))); body.Children.Add(actions);
    }
    void ShowInformation() {
      Navigate(informationNavigation);HideContent();var pages=(Grid)pageSurface.Child;if(informationPage!=null)pages.Children.Remove(informationPage);
      StackPanel body;informationPage=SimplePage("我的信息",out body);pages.Children.Add(informationPage);
      var info=new StackPanel(); var identityRow=new Grid();
      var entries=new[]{new[]{Locale.T("设备名称"),Json.Text(State,"name")},new[]{Locale.T("所属组"),Json.Text(State,"groupId")}};
      for(int i=0;i<entries.Length;i++){identityRow.ColumnDefinitions.Add(new ColumnDefinition());var column=new StackPanel{Margin=new Thickness(0,0,18,0)};column.Children.Add(Text(entries[i][0],12,"#7B879D"));var value=Text(entries[i][1],i==0?18:14);value.TextWrapping=TextWrapping.Wrap;value.TextTrimming=TextTrimming.None;value.Margin=new Thickness(0,8,0,0);column.Children.Add(value);Grid.SetColumn(column,i);identityRow.Children.Add(column);}
      info.Children.Add(identityRow);
      var build=Json.Get(State,"buildInfo");var buildText=Text(Locale.T("版本 ")+Json.Text(build,"version")+Locale.T(" · 构建 ")+Json.Text(build,"revision"),12,"#7B879D");buildText.Margin=new Thickness(0,14,0,0);buildText.TextWrapping=TextWrapping.Wrap;info.Children.Add(buildText);
      var fingerprint=Text(Locale.T("源码指纹：")+Json.Text(build,"sourceFingerprint")+Locale.T("\n构建时间：")+Json.Text(build,"builtAt"),12,"#7B879D");fingerprint.TextWrapping=TextWrapping.Wrap;fingerprint.TextTrimming=TextTrimming.None;info.Children.Add(new Expander{Header=UiLocale.Content("构建详情"),Content=fingerprint,FontSize=12,Margin=new Thickness(0,8,0,0)});
      var details=new Expander{Header=UiLocale.Content("设备标识"),FontSize=12,Foreground=Brush("#7B879D"),Margin=new Thickness(0,14,0,0),Content=Text(Json.Text(State,"id"),12,"#7B879D")};info.Children.Add(details);body.Children.Add(SettingsCard("本机身份",info));
      informationPairing = PairingPanel(); body.Children.Add(informationPairing);
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
      var panel = Column(); panel.Children.Add(LText("边缘切换", 22)); var value = Field(panel, "停留时间（毫秒，所有边缘共用）", Json.Text(State, "dwell")); var window = Dialog(Locale.T("切换时间"), panel);
      panel.Children.Add(LButton("保存", () => Run(async () => { int ms; if (!int.TryParse(value.Text, out ms) || ms < 100 || ms > 3000) throw new Exception(Locale.T("请输入 100–3000 毫秒")); await Client.Call("settings", new { dwell = ms }); window.Close(); await Refresh(); NotifyTransient(Locale.T("切换时间已保存")); }))); window.ShowDialog();
    }
    static readonly string[] PermissionKeys = { "input", "screen", "clipboard", "shutdown" };
    static readonly string[] PermissionLabels = { "键鼠标操作", "屏幕共享", "剪贴板文件共享", "远程关机" };
    string StartupCommand() { return "\"" + System.Reflection.Assembly.GetExecutingAssembly().Location + "\" --data \"" + (DataDir.EndsWith("\\") ? DataDir + "\\" : DataDir) + "\""; }
    bool StartupEnabled() { using (var key = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run")) return key != null && String.Equals(Convert.ToString(key.GetValue("LanLink")), StartupCommand(), StringComparison.OrdinalIgnoreCase); }
    void SetStartup(bool enabled) {
      using (var key = Microsoft.Win32.Registry.CurrentUser.CreateSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run")) {
        if (enabled) key.SetValue("LanLink", StartupCommand(), Microsoft.Win32.RegistryValueKind.String);
        else key.DeleteValue("LanLink", false);
      }
    }
    async Task ChangeStartup(bool adminChanged){
      startupOption.IsEnabled=false;adminStartupOption.IsEnabled=false;
      try{
        bool elevated=Startup.ElevatedEnabled();bool enabled=startupOption.IsChecked==true;bool admin=adminStartupOption.IsChecked==true;
        if(adminChanged && admin)enabled=true;
        if(!enabled)admin=false;
        if(elevated!=admin)await Startup.SetElevated(admin);
        SetStartup(enabled && !admin);
        settingsStatus.Text=admin?Locale.T("已开启管理员登录启动"):enabled?Locale.T("已开启登录自启动"):Locale.T("已关闭自启动");
      }catch(Exception ex){settingsStatus.Text=DisplayError(ex.Message);}
      finally{bool elevated=Startup.ElevatedEnabled();startupOption.IsChecked=StartupEnabled()||elevated;adminStartupOption.IsChecked=elevated;startupOption.IsEnabled=true;adminStartupOption.IsEnabled=true;}
    }
    Window stageReceiveWindow; string stageReceiveSignature="";
    void RefreshStageReceive(){
      var rows=(Json.Get(State,"stageIncoming") as IEnumerable??new object[0]).Cast<object>().ToArray();
      string signature=String.Join("|",rows.Select(r=>Json.Text(r,"id")).OrderBy(id=>id));
      if(signature==stageReceiveSignature)return;stageReceiveSignature=signature;
      if(stageReceiveWindow!=null){stageReceiveWindow.Close();stageReceiveWindow=null;}
      if(rows.Length==0)return;
      var panel=new StackPanel();panel.Children.Add(LText("收到文件",19));
      var detail=Text(Json.Text(rows[0],"sourceName")+" · "+String.Join("、",rows.Select(r=>Json.Text(r,"name"))),14,"#64748B");detail.TextWrapping=TextWrapping.Wrap;detail.MaxHeight=100;detail.Margin=new Thickness(0,12,0,18);panel.Children.Add(detail);
      var actions=new StackPanel{Orientation=Orientation.Horizontal};panel.Children.Add(actions);
      var window=new Window{Title=Locale.T("收到文件"),Resources=Resources,FontFamily=FontFamily,Content=new Border{Child=panel,Padding=new Thickness(22),Background=Brush("#FFFFFF"),BorderBrush=Brush("#DFE5F1"),BorderThickness=new Thickness(1)},Width=370,SizeToContent=SizeToContent.Height,WindowStyle=WindowStyle.None,ResizeMode=ResizeMode.NoResize,ShowInTaskbar=false,ShowActivated=false,Topmost=true};
      Action<bool> decide=accepted=>Run(async()=>{foreach(string token in rows.Select(r=>Json.Text(r,"token")).Distinct())await Client.Call("stage-preview-decide",new{token=token,accepted=accepted});await Refresh();});
      actions.Children.Add(LButton("接受",()=>decide(true)));var decline=LButton("拒绝",()=>decide(false));decline.Margin=new Thickness(12,0,0,0);actions.Children.Add(decline);
      window.SizeChanged+=delegate{var area=SystemParameters.WorkArea;window.Left=area.Right-window.Width-18;window.Top=area.Bottom-window.ActualHeight-18;};stageReceiveWindow=window;window.Show();
    }
    void RefreshRequests() {
      RefreshStageReceive();
      RefreshAdbAuthorization();
      var access=Json.Get(State,"accessNotice");
      if(accessWindow!=null && (access==null || accessWindow.RequestId!=Json.Text(access,"requestId"))) {accessWindow.End();accessWindow=null;}
      if(access!=null && accessWindow==null && lastAccess!=Json.Text(access,"requestId")) {lastAccess=Json.Text(access,"requestId");var w=new PermissionRequestWindow(this,access);accessWindow=w;w.Closed+=delegate{if(accessWindow==w)accessWindow=null;};w.Show();w.Activate();}
      var guide = Json.Get(State, "adbGuide");
      if (adbGuideWindow != null && (guide == null || adbGuideWindow.RequestId != Json.Text(guide,"requestId"))) { adbGuideWindow.End(); adbGuideWindow = null; }
      if (guide != null && adbGuideWindow == null && lastAdbGuide != Json.Text(guide,"requestId")) { lastAdbGuide=Json.Text(guide,"requestId"); var window = new AdbGuideWindow(this,guide); adbGuideWindow=window; window.Closed += delegate { if(adbGuideWindow==window)adbGuideWindow=null; }; window.Show(); window.Activate(); }
      var fallback = Json.Get(State, "fallbackNotice"); if (fallback != null && !showingFallback) Run(() => HandleFallbackNotice(fallback));
      RefreshAndroidDisconnectNotice();
      var requests = (Json.Get(State, "consentRequests") as IEnumerable ?? new object[0]).Cast<object>().ToList();
      if (consentWindow != null && !requests.Any(r => Json.Text(r, "requestId") == consentRequestId)) { consentWindow.Close(); consentWindow = null; }
      if (consentWindow == null && requests.Count > 0) {
        var request = requests[0]; consentRequestId = Json.Text(request, "requestId");
        var panel = Column(); var message = Text(Json.Text(request, "name") + Locale.T(" 希望控制这台电脑"), 20); message.TextWrapping = TextWrapping.Wrap; panel.Children.Add(message);
        var hint = LText("默认允许全部功能；可在高级选项中调整。同意后记住此设备。", 13, "#70809C"); hint.TextWrapping = TextWrapping.Wrap; hint.Margin = new Thickness(0, 14, 0, 14); panel.Children.Add(hint);
        var checks = new Dictionary<string, CheckBox>(); var advanced=new StackPanel();
        for (int i = 0; i < PermissionKeys.Length; i++) { var check = new CheckBox { Content = UiLocale.Content(PermissionLabels[i]), IsChecked = true, Margin = new Thickness(0, 9, 0, 9) }; checks[PermissionKeys[i]] = check; advanced.Children.Add(check); }
        panel.Children.Add(new Expander {Header=UiLocale.Content("高级选项"),Content=advanced,IsExpanded=false,Margin=new Thickness(0,8,0,8)});
        var window = new Window { Title = Locale.T("设备授权确认"), Width = 460, SizeToContent = SizeToContent.Height, Resources = Resources, FontFamily = FontFamily, WindowStartupLocation = WindowStartupLocation.CenterScreen }; WindowShell.Wrap(window, panel, false); consentWindow = window;
        var feedback=Text("",12,"#B76370");feedback.TextWrapping=TextWrapping.Wrap;feedback.Margin=new Thickness(0,12,0,0);panel.Children.Add(feedback);
        bool decided = false, deciding=false;
        Action<bool> decide = accept => Run(async () => { if(deciding)return;deciding=true;panel.IsEnabled = false; try { await Client.Call("consent-decide", new { id = Json.Text(request, "id"), requestId = Json.Text(request, "requestId"), accepted = accept, permissions = checks.ToDictionary(p => p.Key, p => p.Value.IsChecked == true) }); decided = true; window.Close(); } catch(Exception ex) { feedback.Text=DisplayError(ex.Message); } finally { deciding=false;panel.IsEnabled = true; } });
        var row = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 20, 0, 0) }; row.Children.Add(LButton("暂不允许", () => decide(false))); var yes = LButton("同意并记住", () => decide(true)); yes.Style = (Style)FindResource("Primary"); yes.Margin = new Thickness(12, 0, 0, 0); row.Children.Add(yes); panel.Children.Add(row);
        window.Closed += delegate { if (consentWindow == window) consentWindow = null; if (!decided) Run(async () => { try { await Client.Call("consent-decide", new { id = Json.Text(request, "id"), requestId = Json.Text(request, "requestId"), accepted = false }); } catch {} }); };
        window.Show(); window.Activate();
      }
      RefreshPermissionGuide();
      var notice = Json.Get(State, "permissionNotice");
      if (notice != null && !Json.Flag(notice,"explicit") && permissionWindow == null) {
        var panel = Column(); panel.Children.Add(LText("协作权限", 22)); var intro = Text(Json.Text(notice, "name") + Locale.T(" 请求协作，请开启缺少的权限。"), 13, "#70809C"); intro.TextWrapping = TextWrapping.Wrap; panel.Children.Add(intro);
        var rows = new StackPanel(); panel.Children.Add(rows);
        var window = new Window { Title = Locale.T("开启协作权限"), Width = 490, SizeToContent = SizeToContent.Height, Resources = Resources, FontFamily = FontFamily, WindowStartupLocation = WindowStartupLocation.CenterScreen }; WindowShell.Wrap(window, panel, false); permissionWindow = window;
        var timer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
        var feedback=Text("",12,"#B76370");feedback.TextWrapping=TextWrapping.Wrap;feedback.Margin=new Thickness(0,12,0,0);panel.Children.Add(feedback);
        var buttons=new Dictionary<string,Button>();var labels=new Dictionary<string,TextBlock>();var changing=new HashSet<string>();
        for(int i=0;i<PermissionKeys.Length;i++){
          string key=PermissionKeys[i];var row=new DockPanel{Margin=new Thickness(0,12,0,0)};
          var button=LButton("开启权限",()=>Run(async()=>{if(changing.Contains(key))return;changing.Add(key);buttons[key].IsEnabled=false;try{await Client.Call("settings",new{permissions=new Dictionary<string,bool>{{key,true}}});await Refresh();feedback.Text=Locale.T("权限已开启。");}catch(Exception ex){feedback.Text=DisplayError(ex.Message);}finally{changing.Remove(key);}}));
          buttons[key]=button;DockPanel.SetDock(button,Dock.Right);row.Children.Add(button);var label=LText(PermissionLabels[i]);labels[key]=label;row.Children.Add(label);rows.Children.Add(row);
        }
        Action render=delegate{for(int i=0;i<PermissionKeys.Length;i++){string key=PermissionKeys[i];bool allowed=Json.Flag(Json.Get(State,"permissions"),key),osAllowed=!(Json.Get(Json.Get(State,"capabilities"),key) is bool)||Json.Flag(Json.Get(State,"capabilities"),key);buttons[key].Content=allowed?(osAllowed?Locale.T("已授权"):Locale.T("系统未就绪")):Locale.T("开启权限");buttons[key].IsEnabled=!allowed&&!changing.Contains(key);labels[key].Text=Locale.T(PermissionLabels[i])+(allowed&&!osAllowed?Locale.T(" · 请检查系统权限"):"");}};
        timer.Tick += delegate { render(); }; render(); timer.Start();
        window.Closed += delegate { timer.Stop(); permissionWindow = null; Run(async () => { await Client.Call("permission-dismiss",new{requestId=Json.Text(notice,"requestId")}); }); }; window.Show();
      }
    }
    void EndAndroidDisconnectNotice() {
      var window=androidDisconnectWindow;androidDisconnectWindow=null;androidDisconnectRequestId="";
      if(window!=null)window.Close();
    }
    void RefreshAndroidDisconnectNotice() {
      var request=Json.Get(State,"androidDisconnectNotice");string requestId=Json.Text(request,"requestId");
      if(androidDisconnectWindow!=null && androidDisconnectRequestId!=requestId)EndAndroidDisconnectNotice();
      if(request==null || string.IsNullOrEmpty(requestId))return;
      string name=Json.Text(request,"name"),message=Locale.T(Json.Text(request,"message"));
      if(string.IsNullOrEmpty(name))name=Locale.T("设备");
      if(androidDisconnectWindow!=null){androidDisconnectWindow.Title=Locale.T("安卓设备连接中断");androidDisconnectName.Text=name;androidDisconnectMessage.Text=message;return;}
      if(seenAndroidDisconnectRequests.Contains(requestId))return;
      var panel=Column();var heading=LText("安卓设备连接中断",22);heading.Margin=new Thickness(0,0,0,16);panel.Children.Add(heading);
      androidDisconnectName=Text(name);androidDisconnectName.TextWrapping=TextWrapping.Wrap;androidDisconnectName.TextTrimming=TextTrimming.None;panel.Children.Add(androidDisconnectName);
      androidDisconnectMessage=Text(message,14,"#71819A");androidDisconnectMessage.TextWrapping=TextWrapping.Wrap;androidDisconnectMessage.TextTrimming=TextTrimming.None;androidDisconnectMessage.Margin=new Thickness(0,12,0,24);panel.Children.Add(androidDisconnectMessage);
      var window=Dialog(Locale.T("安卓设备连接中断"),panel,500);androidDisconnectWindow=window;androidDisconnectRequestId=requestId;seenAndroidDisconnectRequests.Add(requestId);
      var accept=LButton("确认",()=>window.Close());accept.Style=(Style)FindResource("Primary");accept.HorizontalAlignment=HorizontalAlignment.Right;accept.MinWidth=104;accept.IsDefault=true;accept.IsCancel=true;panel.Children.Add(accept);
      window.Closed+=delegate {if(androidDisconnectWindow!=window)return;androidDisconnectWindow=null;androidDisconnectRequestId="";if(!quitting && !restartingService)Run(async()=>{await Client.Call("android-disconnect-dismiss",new {requestId=requestId});});};
      window.Show();window.Activate();
    }
    void EndAdbAuthorization() { var window = adbAuthorizedWindow; adbAuthorizedWindow = null; adbAuthorizedRequest = ""; if (window != null) window.Close(); }
    void RefreshAdbAuthorization() {
      var notice = Json.Get(State, "adbAuthorization"); string id = Json.Text(notice, "requestId");
      if (adbAuthorizedWindow != null && id != adbAuthorizedRequest) EndAdbAuthorization();
      if (id == "" || adbAuthorizedWindow != null || seenAdbAuthorizations.Contains(id)) return;
      var panel = Column(); var title = LText("设备已授权", 22); title.FontWeight = FontWeights.SemiBold; panel.Children.Add(title);
      var name = Text(Json.Text(notice, "name"), 15); name.Margin = new Thickness(0, 16, 0, 12); name.TextWrapping = TextWrapping.Wrap; panel.Children.Add(name);
      var message = LText("设备已授权，已经可以正常使用。", 14, "#368769"); message.Margin = new Thickness(0, 0, 0, 24); panel.Children.Add(message);
      var window = Dialog(Locale.T("设备已授权"), panel, 460); adbAuthorizedWindow = window; adbAuthorizedRequest = id; seenAdbAuthorizations.Add(id);
      var accept = LButton("确认", () => window.Close()); accept.IsDefault = true; accept.IsCancel = true; accept.Style = (Style)FindResource("Primary"); accept.HorizontalAlignment = HorizontalAlignment.Right; accept.MinWidth = 104; panel.Children.Add(accept);
      window.Closed += delegate { if (adbAuthorizedWindow != window) return; adbAuthorizedWindow = null; adbAuthorizedRequest = ""; if (!quitting && !restartingService) Run(async () => { await Client.Call("adb-authorization-dismiss", new { requestId = id }); }); };
      window.Show(); window.Activate();
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
      if (State == null) { NotifyTransient(Locale.T("正在连接设备服务，请稍后打开设置")); return; }
      if (settingsPage == null) BuildSettingsPage();
      if ((!settingsDirty || settingsAccount != Json.Text(State, "groupId")) && !savingSettings && immediateSettingsPending==0) LoadSettingsDraft();
      devicePage.Visibility = Visibility.Collapsed; settingsPage.Visibility = Visibility.Visible;
      if (adbPage != null) adbPage.Visibility = Visibility.Collapsed;
      homeNavigation.Background = Brushes.Transparent; settingsNavigation.Background = Brush("#E1EAFB");
    }
    Border SettingsCard(string title, StackPanel content) {
      var heading = LText(title, 16); heading.FontWeight = FontWeights.SemiBold; heading.Margin = new Thickness(0, 0, 0, 14);
      content.Children.Insert(0, heading);
      return new Border { CornerRadius = new CornerRadius(18), Background = MainWindow.Brush("#FFFFFF"), BorderBrush = Brush("#E5EBF5"), BorderThickness = new Thickness(1), Padding = new Thickness(22, 18, 22, 18), Margin = new Thickness(0, 0, 0, 16), Child = content };
    }
    void UpdateClipboardOptions(){if(clipboardUploadButton!=null)clipboardUploadButton.Visibility=clipboardUploadOption.IsChecked==true?Visibility.Collapsed:Visibility.Visible;if(clipboardDownloadButton!=null)clipboardDownloadButton.Visibility=clipboardDownloadOption.IsChecked==true?Visibility.Collapsed:Visibility.Visible;if(clipboardShortcutOption!=null)clipboardShortcutOption.Visibility=clipboardUploadOption.IsChecked==true && clipboardDownloadOption.IsChecked==true?Visibility.Collapsed:Visibility.Visible;}
    async Task ManualClipboard(string action){var result=await Client.Call(action);Toast(Json.Text(result,"message"));await Refresh();}
    Popup clipboardToast;
    void Toast(string message,double seconds=3){if(string.IsNullOrWhiteSpace(message))return;if(clipboardToast!=null)clipboardToast.IsOpen=false;var label=Text(message,13,"#435277");label.TextWrapping=TextWrapping.Wrap;var popup=new Popup{AllowsTransparency=true,Placement=PlacementMode.Relative,PlacementTarget=this,HorizontalOffset=Math.Max(20,ActualWidth-360),VerticalOffset=80,StaysOpen=true,Child=new Border{Width=320,Padding=new Thickness(18,14,18,14),CornerRadius=new CornerRadius(16),Background=Brush("#EEF1FC"),BorderBrush=Brush("#DDE4F2"),BorderThickness=new Thickness(1),Child=label}};clipboardToast=popup;popup.IsOpen=true;var timer=new DispatcherTimer{Interval=TimeSpan.FromSeconds(seconds)};timer.Tick+=delegate{timer.Stop();popup.IsOpen=false;};timer.Start();}
    void ShowClipboardPage(){Navigate(clipboardNavigation);HideContent();if(clipboardPage==null){clipboardPage=SimplePage("剪贴板",out clipboardBody);((Grid)pageSurface.Child).Children.Add(clipboardPage);}clipboardPage.Visibility=Visibility.Visible;clipboardSignature="";RefreshClipboardPage();}
    void RefreshClipboardPage(){var history=Json.Get(Json.Get(State,"clipboardSettings"),"history") as IEnumerable ?? new object[0];string sig=new System.Web.Script.Serialization.JavaScriptSerializer().Serialize(history);if(sig==clipboardSignature)return;clipboardSignature=sig;clipboardBody.Children.Clear();var hint=LText("最近 10 分钟 · 最多 30 条文字 · 新内容在前",13,"#8B97AB");hint.Margin=new Thickness(0,0,0,18);clipboardBody.Children.Add(hint);int count=0;foreach(var entry in history){count++;string id=Json.Text(entry,"id");var card=new StackPanel();var meta=new DockPanel();var copy=LButton("复制",()=>Run(async()=>{var result=await Client.Call("clipboard-copy",new{id=id});Toast(Json.Text(result,"message"));}));DockPanel.SetDock(copy,Dock.Right);meta.Children.Add(copy);double at=Json.Number(entry,"at",0);meta.Children.Add(Text(Json.Text(entry,"name")+" · "+new DateTime(1970,1,1,0,0,0,DateTimeKind.Utc).AddMilliseconds(at).ToLocalTime().ToString("HH:mm:ss"),12,"#8B97AB"));card.Children.Add(meta);var content=Text(Json.Text(entry,"text"),14);content.TextWrapping=TextWrapping.Wrap;content.MaxHeight=120;content.Margin=new Thickness(0,14,0,4);card.Children.Add(content);if(Json.Number(entry,"length",0)>2000)card.Children.Add(LText("长文本预览 · 复制可获取完整内容",12,"#8B97AB"));clipboardBody.Children.Add(new Border{CornerRadius=new CornerRadius(18),Background=MainWindow.Brush("#FFFFFF"),BorderBrush=Brush("#E5EBF5"),BorderThickness=new Thickness(1),Padding=new Thickness(20),Margin=new Thickness(0,0,0,12),Child=card});}if(count==0)clipboardBody.Children.Add(LText("暂无同步记录，复制文字后将在这里出现。",14,"#8B97AB"));}
    void MarkSettingsDirty() { if (loadingSettings) return; settingsDirty = true; settingsStatus.Text = Locale.T("有未保存的更改 · 切换页面会保留草稿"); }
    void LoadSettingsDraft() {
      loadingSettings = true; UpdateLanguageChoice();
      var clipSettings=Json.Get(State,"clipboardSettings");clipboardUploadOption.IsChecked=Json.Get(clipSettings,"upload")==null || Json.Flag(clipSettings,"upload");clipboardDownloadOption.IsChecked=Json.Get(clipSettings,"download")==null || Json.Flag(clipSettings,"download");clipboardShortcutOption.IsChecked=Json.Flag(clipSettings,"shortcuts");UpdateClipboardOptions();
      settingsHideAway.IsChecked=Json.Flag(State,"edgeGlowHideAway"); settingsName.Text = Json.Text(State, "name"); settingsMaster.Items.Clear();foreach(var item in Json.Get(State,"masterDevices") as IEnumerable ?? new object[0]){var choice=new ComboBoxItem{Content=Json.Text(item,"name")+(Json.Text(item,"id")==Json.Text(State,"id")?Locale.T(" · 本机"):Json.Flag(item,"online")?"":Locale.T(" · 离线")),Tag=Json.Text(item,"id")};settingsMaster.Items.Add(choice);if(Json.Text(item,"id")==Json.Text(State,"masterId"))settingsMaster.SelectedItem=choice;}if(settingsMaster.SelectedIndex<0 && settingsMaster.Items.Count>0)settingsMaster.SelectedIndex=0; settingsEdgeGlow.IsChecked=Json.Get(State,"edgeGlowEnabled")==null || Json.Flag(State,"edgeGlowEnabled");
      settingsSwitchMode.SelectedIndex=Json.Text(State,"switchMode")=="hotkey"?1:0;settingsDwell.Value=Json.Number(State,"dwell",100);settingsHotkey.SelectedIndex=Array.IndexOf(new[]{"ctrl-alt","ctrl-shift","alt-shift","meta-shift"},Json.Text(State,"edgeHotkey"));if(settingsHotkey.SelectedIndex<0)settingsHotkey.SelectedIndex=0;UpdateSwitchOptions();
      foreach (var pair in settingsPermissions) pair.Value.IsChecked = Json.Flag(Json.Get(State, "permissions"), pair.Key);
      settingsAccount = Json.Text(State, "groupId"); settingsDirty = false; loadingSettings = false;
      settingsStatus.Text = Locale.T("名称、主设备和切换方式需保存；语言、外观、启动、光效和剪贴板开关立即生效。");
    }
    async Task ApplyImmediateSetting(CheckBox toggle, string key) {
      if (loadingSettings) return;
      bool value = toggle.IsChecked == true, committed=false; var previous = immediateSettingsTask;
      var complete = new TaskCompletionSource<object>(); immediateSettingsTask = complete.Task; immediateSettingsPending++; toggle.IsEnabled = false;
      try {
        await previous;
        var result=await Client.Call("settings", new Dictionary<string,object>{{key,value}});committed=true;
        State = await Client.Call("state"); clipboardQuick.Visibility=Json.Flag(Json.Get(State,"clipboardSettings"),"showShortcuts")?Visibility.Visible:Visibility.Collapsed;
        settingsStatus.Text = SettingsResult(result,Locale.T("开关已立即生效") + (settingsDirty ? Locale.T("；名称或切换设置仍有未保存的更改。") : "。"));
      } catch(Exception ex) {
        if(!committed){loadingSettings=true;try{toggle.IsChecked=!value;UpdateClipboardOptions();}finally{loadingSettings=false;}settingsStatus.Text=DisplayError(ex.Message)+Locale.T(" 开关未更改，请重试。");}
        else{restartServiceButton.Visibility=Visibility.Visible;Notify(Locale.T("开关已保存，但设备服务状态无法刷新，请重启设备服务。"));}
      } finally { immediateSettingsPending--; toggle.IsEnabled=true; complete.TrySetResult(null); }
    }
    void BindImmediateSetting(CheckBox toggle, string key) {
      RoutedEventHandler changed = delegate { if(!loadingSettings)Run(()=>ApplyImmediateSetting(toggle,key)); };
      toggle.Checked += changed; toggle.Unchecked += changed;
    }
    async Task SaveSettings() {
      if(savingSettings)return; bool committed=false; savingSettings=true; settingsPage.IsEnabled=false; settingsStatus.Text=Locale.T("正在保存设置…");
      try {
        await immediateSettingsTask;
        var permissions = settingsPermissions.ToDictionary(p => p.Key, p => p.Value.IsChecked == true);
        var result=await Client.Call("settings", new { name = settingsName.Text, masterId = settingsMaster.SelectedItem==null?Json.Text(State,"id"):Convert.ToString(((ComboBoxItem)settingsMaster.SelectedItem).Tag), switchMode=settingsSwitchMode.SelectedIndex==1?"hotkey":"dwell", dwell=(int)settingsDwell.Value, edgeHotkey=new[]{"ctrl-alt","ctrl-shift","alt-shift","meta-shift"}[Math.Max(0,settingsHotkey.SelectedIndex)], permissions = permissions });committed=true;
        State = await Client.Call("state"); LoadSettingsDraft(); settingsStatus.Text = SettingsResult(result,Locale.T("本机设置已保存")); signature = ""; await Refresh();
      } catch (Exception ex) { settingsStatus.Text = committed?Locale.T("设置已保存，但设备服务状态无法刷新，请重启设备服务。"):DisplayError(ex.Message);if(committed){restartServiceButton.Visibility=Visibility.Visible;Notify(settingsStatus.Text);} }
      finally { savingSettings=false; settingsPage.IsEnabled=true; }
    }
    void UpdateSwitchOptions(){if(dwellOptions==null)return;bool hotkey=settingsSwitchMode.SelectedIndex==1;dwellOptions.Visibility=hotkey?Visibility.Collapsed:Visibility.Visible;hotkeyOptions.Visibility=hotkey?Visibility.Visible:Visibility.Collapsed;if(settingsZeroHint!=null)settingsZeroHint.Visibility=settingsDwell.Value==0?Visibility.Visible:Visibility.Collapsed;settingsDwellLabel.Text=Locale.Format("边缘停留时间 · {0} 毫秒", (int)settingsDwell.Value);}
    void UpdateLanguageChoice() {
      if(settingsLanguage==null)return;
      restoringLanguage=true;
      foreach(ComboBoxItem item in settingsLanguage.Items)if(Convert.ToString(item.Tag)==Locale.Preference){settingsLanguage.SelectedItem=item;break;}
      restoringLanguage=false;
    }
    async Task ChangeLanguage() {
      var selectedLanguage=settingsLanguage.SelectedItem as ComboBoxItem;
      if(selectedLanguage==null)return;string value=Convert.ToString(selectedLanguage.Tag);
      if(value==Locale.Preference)return;
      var previous=immediateSettingsTask;var complete=new TaskCompletionSource<object>();immediateSettingsTask=complete.Task;immediateSettingsPending++;settingsLanguage.IsEnabled=false;
      try {
        await previous;
        var result=await Client.Call("settings",new{language=value});
        Locale.Set(value);
        settingsStatus.Text=SettingsResult(result,Locale.T("语言已立即生效。"));
      } catch(Exception ex) {
        UpdateLanguageChoice();settingsStatus.Text=DisplayError(ex.Message)+" "+Locale.T("语言未更改，请重试。");
      } finally { immediateSettingsPending--;settingsLanguage.IsEnabled=true;complete.TrySetResult(null); }
    }
    void ApplyLanguage() {
      // Update labels in place: never reconstruct settings or lose an unsaved draft.
      UpdateLanguageChoice();if(settingsDwell!=null)UpdateSwitchOptions();
      if(settingsMaster!=null)foreach(ComboBoxItem choice in settingsMaster.Items)foreach(var item in Json.Get(State,"masterDevices") as IEnumerable ?? new object[0])if(Json.Text(item,"id")==Convert.ToString(choice.Tag))choice.Content=Json.Text(item,"name")+(Json.Text(item,"id")==Json.Text(State,"id")?Locale.T(" · 本机"):Json.Flag(item,"online")?"":Locale.T(" · 离线"));
      if(tray!=null){tray.Dispose();MakeTray();}
      signature="";clipboardSignature="";Draw();
    }
    void BuildSettingsPage() {
      settingsPage = new Grid { Visibility = Visibility.Collapsed }; ((Grid)pageSurface.Child).Children.Add(settingsPage);
      settingsPage.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto }); settingsPage.RowDefinitions.Add(new RowDefinition()); settingsPage.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
      var header = new DockPanel { Margin = new Thickness(0, 0, 0, 22) };
      saveSettingsButton = LButton("保存设置", () => Run(SaveSettings)); saveSettingsButton.Style = (Style)FindResource("Primary"); saveSettingsButton.VerticalAlignment = VerticalAlignment.Top; DockPanel.SetDock(saveSettingsButton, Dock.Right); header.Children.Add(saveSettingsButton);
      var titles = new StackPanel(); var heading = LText("设置", 26); heading.FontWeight = FontWeights.SemiBold; titles.Children.Add(heading);
      var intro = LText("管理设备名称、主设备与启动选项", 12, "#8B97AB"); intro.Margin = new Thickness(0, 8, 0, 0); titles.Children.Add(intro); header.Children.Add(titles); settingsPage.Children.Add(header);
      var sections = new StackPanel { MaxWidth = 820, HorizontalAlignment = HorizontalAlignment.Stretch, Margin = new Thickness(0, 0, 12, 0) };
      var basic = new StackPanel(); settingsName = Field(basic, "设备名称", ""); settingsName.Margin = new Thickness(0, 8, 0, 14); settingsName.MaxWidth = 450; settingsName.HorizontalAlignment = HorizontalAlignment.Left; settingsName.MinWidth = 280; settingsName.TextChanged += delegate { MarkSettingsDirty(); };
      basic.Children.Add(LText("语言",13));
      settingsLanguage = new ComboBox { Margin = new Thickness(0,8,0,10), MinWidth=280, HorizontalAlignment=HorizontalAlignment.Left, Style=(Style)FindResource("SoftChoice") };
      settingsLanguage.Items.Add(UiLocale.Choice("跟随系统", "system"));
      for(int index=0;index<LanguageRules.Supported.Length;index++) settingsLanguage.Items.Add(new ComboBoxItem { Content=Locale.NativeNames[index], Tag=LanguageRules.Supported[index] });
      UpdateLanguageChoice();
      settingsLanguage.SelectionChanged += delegate { if(!restoringLanguage)Run(ChangeLanguage); };
      basic.Children.Add(settingsLanguage);
      var languageHint=LText("仅影响本机界面，首次使用自动匹配系统语言。",12,"#8390A6"); languageHint.TextWrapping=TextWrapping.Wrap; languageHint.Margin=new Thickness(0,0,0,14); basic.Children.Add(languageHint);
      basic.Children.Add(LText("外观",13));
      var appearance=new ComboBox{Margin=new Thickness(0,8,0,14),MinWidth=280,HorizontalAlignment=HorizontalAlignment.Left,Style=(Style)FindResource("SoftChoice")};
      foreach(var label in new[]{"跟随系统","深色","浅色"})appearance.Items.Add(UiLocale.Choice(label,label));
      appearance.SelectedIndex=Theme.Mode=="dark"?1:Theme.Mode=="light"?2:0;
      bool restoringAppearance=false;
      appearance.SelectionChanged+=async delegate{
        if(restoringAppearance || appearance.SelectedIndex<0)return;string value=new[]{"system","dark","light"}[appearance.SelectedIndex],previous=Theme.Mode;appearance.IsEnabled=false;
        try{var result=await Client.Call("settings",new{theme=value});Theme.Set(value);settingsStatus.Text=SettingsResult(result,Locale.T("外观已立即生效。"));}
        catch(Exception ex){restoringAppearance=true;appearance.SelectedIndex=previous=="dark"?1:previous=="light"?2:0;restoringAppearance=false;settingsStatus.Text=DisplayError(ex.Message)+Locale.T(" 外观未更改，请重试。");}
        finally{appearance.IsEnabled=true;}
      };
      basic.Children.Add(appearance);basic.Children.Add(LText("仅对本设备生效，切换后立即应用。",12,"#8390A6"));
      startupOption=new CheckBox{Content=UiLocale.Content("登录 Windows 后自动启动 LanLink"),IsChecked=StartupEnabled() || Startup.ElevatedEnabled(),Margin=new Thickness(0,8,0,8)};
      adminStartupOption=new CheckBox{Content=UiLocale.Content("开机以管理员权限启动"),IsChecked=Startup.ElevatedEnabled(),Margin=new Thickness(0,8,0,8)};
      startupOption.Click+=async delegate{await ChangeStartup(false);};adminStartupOption.Click+=async delegate{await ChangeStartup(true);};basic.Children.Add(startupOption);basic.Children.Add(adminStartupOption);
      var startupHint=LText("管理员模式在登录后直接启动；首次开启需要系统确认。",12,"#8B97AB");startupHint.TextWrapping=TextWrapping.Wrap;basic.Children.Add(startupHint);var updateAuto=new CheckBox{Content=UiLocale.Content("自动检测更新"),IsChecked=Json.Get(State,"autoCheckUpdates")==null||Json.Flag(State,"autoCheckUpdates"),Margin=new Thickness(0,8,0,8)};BindImmediateSetting(updateAuto,"autoCheckUpdates");basic.Children.Add(updateAuto);
      var updateStatus=LText("",12,"#8B97AB");basic.Children.Add(updateStatus);
      var updateCheck=LButton("检查更新",async delegate{try{var result=await Client.Call("update-check");updateStatus.Text=Json.Text(result,"phase")=="available"?"发现新版本 "+Json.Text(result,"version"):"已是最新版本";}catch(Exception e){updateStatus.Text=DisplayError(e.Message);}});basic.Children.Add(updateCheck);
      var updateInstall=LButton("下载并准备更新",async delegate{try{var result=await Client.Call("update-install");updateStatus.Text=Json.Text(result,"message");}catch(Exception e){updateStatus.Text=DisplayError(e.Message);}});basic.Children.Add(updateInstall);
      sections.Children.Add(SettingsCard("通用",basic));
      var switching=new StackPanel();switching.Children.Add(LText("键鼠主设备",13));settingsMaster=new ComboBox{Margin=new Thickness(0,10,0,14),MaxWidth=440,HorizontalAlignment=HorizontalAlignment.Left,MinWidth=280};settingsMaster.Style=(Style)FindResource("SoftChoice");settingsMaster.SelectionChanged+=delegate{MarkSettingsDirty();};switching.Children.Add(settingsMaster);
      switching.Children.Add(LText("使用所选电脑的键盘和鼠标；离线电脑将在上线后应用。",12,"#8B97AB"));
      settingsEdgeGlow=new CheckBox{Content=UiLocale.Content("键鼠切换时显示边缘光效"),Margin=new Thickness(0,16,0,8)};BindImmediateSetting(settingsEdgeGlow,"edgeGlow");switching.Children.Add(settingsEdgeGlow);
      settingsHideAway=new CheckBox{Content=UiLocale.Content("鼠标移走后隐藏光效"),Margin=new Thickness(0,8,0,18)};BindImmediateSetting(settingsHideAway,"edgeGlowHideAway");switching.Children.Add(settingsHideAway);switching.Children.Add(LText("光效开关立即生效，无需点击保存。",12,"#8B97AB"));
      switching.Children.Add(LText("键鼠切换设备方式",13));switching.Children.Add(LText("以下切换设置仅对本机生效",12,"#8B97AB"));
      settingsSwitchMode=new ComboBox{ItemsSource=new[]{UiLocale.Choice("到达边缘后停留切换","dwell"),UiLocale.Choice("按住快捷键到达边缘切换","hotkey")},SelectedIndex=0,Margin=new Thickness(0,12,0,14),Padding=new Thickness(12,8,12,8),MaxWidth=440,HorizontalAlignment=HorizontalAlignment.Left};settingsSwitchMode.Style=(Style)FindResource("SoftChoice");switching.Children.Add(settingsSwitchMode);
      dwellOptions=new StackPanel();settingsDwellLabel=Text("",13);dwellOptions.Children.Add(settingsDwellLabel);settingsDwell=new Slider{Minimum=0,Maximum=3000,TickFrequency=50,IsSnapToTickEnabled=true,Value=100,Margin=new Thickness(0,12,0,10),MaxWidth=440,HorizontalAlignment=HorizontalAlignment.Left,MinWidth=280};settingsDwell.Style=(Style)FindResource("EdgeDelaySlider");dwellOptions.Children.Add(settingsDwell);settingsZeroHint=LText("即使停留时间为零，创建连接仍需要少量等待时间。",12,"#8B97AB");settingsZeroHint.TextWrapping=TextWrapping.Wrap;dwellOptions.Children.Add(settingsZeroHint);switching.Children.Add(dwellOptions);
      hotkeyOptions=new StackPanel();hotkeyOptions.Children.Add(LText("按住以下组合键，再将鼠标移到边缘",13));settingsHotkey=new ComboBox{ItemsSource=new[]{"Ctrl + Alt","Ctrl + Shift","Alt + Shift","Win + Shift"},SelectedIndex=0,Margin=new Thickness(0,10,0,0),Padding=new Thickness(12,8,12,8),MaxWidth=280,HorizontalAlignment=HorizontalAlignment.Left};settingsHotkey.Style=(Style)FindResource("SoftChoice");hotkeyOptions.Children.Add(settingsHotkey);switching.Children.Add(hotkeyOptions);
      settingsSwitchMode.SelectionChanged+=delegate{UpdateSwitchOptions();MarkSettingsDirty();};settingsDwell.ValueChanged+=delegate{UpdateSwitchOptions();MarkSettingsDirty();};settingsHotkey.SelectionChanged+=delegate{MarkSettingsDirty();};UpdateSwitchOptions();sections.Children.Add(SettingsCard("无界鼠标",switching));
      var clipOptions=new StackPanel();clipboardUploadOption=new CheckBox{Content=UiLocale.Content("允许自动上传剪贴板信息到组"),Margin=new Thickness(0,8,0,8)};clipboardDownloadOption=new CheckBox{Content=UiLocale.Content("允许自动获取组剪贴板到本机"),Margin=new Thickness(0,8,0,8)};clipboardShortcutOption=new CheckBox{Content=UiLocale.Content("在首页显示快捷同步剪贴板按钮"),Margin=new Thickness(0,16,0,8)};
      clipboardUploadButton=LButton("上传剪贴板",()=>Run(()=>ManualClipboard("clipboard-upload")));clipboardDownloadButton=LButton("下载剪贴板",()=>Run(()=>ManualClipboard("clipboard-download")));
      foreach(var row in new[]{new object[]{clipboardUploadOption,clipboardUploadButton},new object[]{clipboardDownloadOption,clipboardDownloadButton}}){var line=new DockPanel{LastChildFill=true};var option=(CheckBox)row[0];var action=(Button)row[1];DockPanel.SetDock(action,Dock.Right);action.Margin=new Thickness(12,4,0,4);line.Children.Add(action);line.Children.Add(option);clipOptions.Children.Add(line);option.Checked+=delegate{UpdateClipboardOptions();};option.Unchecked+=delegate{UpdateClipboardOptions();};BindImmediateSetting(option,option==clipboardUploadOption?"clipboardUpload":"clipboardDownload");}
      BindImmediateSetting(clipboardShortcutOption,"clipboardShortcuts");clipOptions.Children.Add(clipboardShortcutOption);clipOptions.Children.Add(LText("开关立即生效。同步到组内在线设备，保留单独设置的设备授权限制。",12,"#8B97AB"));sections.Children.Add(SettingsCard("共享剪贴板",clipOptions));
      var tools = new StackPanel(); var log = new StackPanel { Visibility = Visibility.Collapsed, Margin = new Thickness(0, 12, 0, 0) };
      var actions = new StackPanel { Orientation = Orientation.Horizontal };
      var logs = LButton("运行日志", delegate {
        log.Visibility = log.Visibility == Visibility.Visible ? Visibility.Collapsed : Visibility.Visible; log.Children.Clear();
        foreach (var item in Json.Get(State, "logs") as IEnumerable ?? new object[0]) { var line = Text(Json.Text(item, "message"), 12, "#71819A"); line.TextWrapping = TextWrapping.Wrap; line.Margin = new Thickness(0, 5, 0, 5); log.Children.Add(line); }
        if (log.Children.Count == 0) log.Children.Add(LText("暂无运行记录", 12, "#8B97AB"));
      }); logs.Margin = new Thickness(0, 0, 10, 0); actions.Children.Add(logs); actions.Children.Add(LButton("重启设备服务",()=>Run(RestartService))); actions.Children.Add(LButton("退出应用", () => Run(() => Quit(0)))); tools.Children.Add(actions); tools.Children.Add(log); sections.Children.Add(SettingsCard("其他", tools));
      var scroll = new ScrollViewer { Content = sections, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled }; Grid.SetRow(scroll, 1); settingsPage.Children.Add(scroll);
      settingsStatus = Text("", 12, "#71819A"); settingsStatus.TextWrapping = TextWrapping.Wrap; settingsStatus.Margin = new Thickness(4, 14, 4, 8); Grid.SetRow(settingsStatus, 2); settingsPage.Children.Add(settingsStatus);
    }
    PairingPanel informationPairing;
    Task Pairing() { if (State != null) ShowInformation(); return Task.FromResult(0); }
    PairingPanel PairingPanel() {
      PairingPanel window = null;
      window = new PairingPanel(this, Client, formed => Confirm(this, formed ? Locale.T("配对并合并设备组？") : Locale.T("加入其他组？"), formed ? Locale.T("配对成功后，双方组内的所有设备将合并为一组，保留已有设备授权。离线设备重新上线后自动同步，无需逐台配对。") : Locale.T("本机将加入对方的组，在同一局域网内自动连接。")), async () => { signature = ""; await Refresh(); ShowDevices(); NotifyTransient(Locale.T("设备已连接")); });
      return window;
    }
    public static BitmapImage DecodeImage(string base64) { var bitmap = new BitmapImage(); using (var stream = new MemoryStream(Convert.FromBase64String(base64))) { bitmap.BeginInit(); bitmap.CacheOption = BitmapCacheOption.OnLoad; bitmap.StreamSource = stream; bitmap.EndInit(); bitmap.Freeze(); } return bitmap; }
    static FrameworkElement FindLayoutElement(FrameworkElement root, string id) {
      if (System.Windows.Automation.AutomationProperties.GetAutomationId(root) == id) return root;
      foreach (var child in LogicalTreeHelper.GetChildren(root).OfType<FrameworkElement>()) { var match = FindLayoutElement(child, id); if (match != null) return match; }
      return null;
    }
    async Task VerifyLayoutActions() {
      var local = devices.First(d => d.Local); var remote = devices.First(d => d.Platform == "darwin");
      var originalMaster = Json.Get(State, "masterId"); var originalLayout = new System.Web.Script.Serialization.JavaScriptSerializer().Serialize(Json.Get(State, "layout"));
      Json.Map(State)["masterId"] = local.Id; Draw();
      await Dispatcher.InvokeAsync(delegate { UpdateLayout(); }, DispatcherPriority.Render);
      var card = canvas.Children.OfType<Border>().First(b => Convert.ToString(b.Tag) == local.Id);
      var badges = ((Grid)card.Child).Children.OfType<WrapPanel>().Single().Children.OfType<Border>().ToArray();
      if (badges.Length != 2) throw new Exception("Master and mouse location must both have complete badges");
      foreach (var badge in badges) {
        var point = badge.TranslatePoint(new Point(0, 0), card); var label = (TextBlock)badge.Child;
        if (point.X < 0 || point.Y < 0 || point.X + badge.ActualWidth > card.ActualWidth + .5 || point.Y + badge.ActualHeight > card.ActualHeight + .5 || label.ActualHeight + .5 < label.DesiredSize.Height) throw new Exception("Layout badges must fit fully inside their scaled card");
      }
      if (localLayoutMasterButton.Visibility != Visibility.Collapsed || FindLayoutElement(BuildLayoutActions(local), "layoutMasterAction") != null) throw new Exception("Current master must hide redundant master actions");
      Json.Map(State)["masterId"] = remote.Id; Draw();
      await Dispatcher.InvokeAsync(delegate { UpdateLayout(); }, DispatcherPriority.Render);
      if (localLayoutMasterButton.Visibility != Visibility.Visible || !localLayoutMasterButton.IsEnabled) throw new Exception("A remote master must expose the local master shortcut");
      layoutScroll.ScrollToRightEnd(); layoutScroll.ScrollToBottom(); CenterLayout();
      await Dispatcher.InvokeAsync(delegate { UpdateLayout(); }, DispatcherPriority.Render);
      var localCell = LayoutCells()[local.Id];
      if (Math.Abs(layoutGeometry.X(localCell) - layoutScroll.HorizontalOffset - layoutScroll.ViewportWidth / 2) > 1 || Math.Abs(layoutGeometry.Y(localCell) - layoutScroll.VerticalOffset - layoutScroll.ViewportHeight / 2) > 1) throw new Exception("Center must place this computer at the viewport center");
      if (Json.Text(State, "masterId") != remote.Id || new System.Web.Script.Serialization.JavaScriptSerializer().Serialize(Json.Get(State, "layout")) != originalLayout) throw new Exception("Center must preserve shared coordinates and the selected master");
      SavePreview((FrameworkElement)Content, "native-layout-actions.png");
      var remoteCard = canvas.Children.OfType<Border>().First(b => Convert.ToString(b.Tag) == remote.Id);
      remoteCard.RaiseEvent(new MouseButtonEventArgs(Mouse.PrimaryDevice, Environment.TickCount, MouseButton.Left) { RoutedEvent = UIElement.MouseLeftButtonDownEvent });
      remoteCard.RaiseEvent(new MouseButtonEventArgs(Mouse.PrimaryDevice, Environment.TickCount, MouseButton.Left) { RoutedEvent = UIElement.MouseLeftButtonUpEvent });
      await Dispatcher.InvokeAsync(delegate { UpdateLayout(); }, DispatcherPriority.Render);
      if (layoutActionsPopup == null || !layoutActionsPopup.IsOpen || Json.Text(State, "masterId") != remote.Id) throw new Exception("Left-click must open device actions without switching the master");
      var actionCard = (Border)layoutActionsPopup.Child; actionCard.UpdateLayout();
      if (((TextBlock)FindLayoutElement(actionCard, "layoutDeviceType")).Text != "Mac" || ((TextBlock)FindLayoutElement(actionCard, "layoutDeviceStatus")).Text != "未就绪") throw new Exception("Left-click card must identify device type and readiness");
      var actions = ((StackPanel)actionCard.Child).Children.OfType<Button>().Select(b => System.Windows.Automation.AutomationProperties.GetAutomationId(b)).ToArray();
      if (actions.Length != 2 || !actions.Contains("layoutMouseAction") || !actions.Contains("layoutFileAction")) throw new Exception("A master device's left-click card must contain only mouse and file actions");
      SavePreview(actionCard, "native-layout-menu.png"); layoutActionsPopup.IsOpen = false;
      card = canvas.Children.OfType<Border>().First(b => Convert.ToString(b.Tag) == local.Id);
      card.RaiseEvent(new MouseButtonEventArgs(Mouse.PrimaryDevice, Environment.TickCount, MouseButton.Left) { RoutedEvent = UIElement.MouseLeftButtonDownEvent });
      card.RaiseEvent(new MouseButtonEventArgs(Mouse.PrimaryDevice, Environment.TickCount, MouseButton.Left) { RoutedEvent = UIElement.MouseLeftButtonUpEvent });
      var masterAction = FindLayoutElement((Border)layoutActionsPopup.Child, "layoutMasterAction") as Button;
      if (!layoutActionsPopup.IsOpen || Json.Text(State, "masterId") != remote.Id || masterAction == null || !masterAction.IsEnabled || ((StackPanel)((Border)layoutActionsPopup.Child).Child).Children.OfType<Button>().Count() != 3) throw new Exception("A non-master must show only mouse, file and explicit master actions without changing the master");
      ((Border)layoutActionsPopup.Child).UpdateLayout(); SavePreview((Border)layoutActionsPopup.Child, "native-layout-local-menu.png"); layoutActionsPopup.IsOpen = false;
      remoteCard.RaiseEvent(new MouseButtonEventArgs(Mouse.PrimaryDevice, Environment.TickCount, MouseButton.Right) { RoutedEvent = UIElement.MouseRightButtonUpEvent });
      if (layoutDeviceMenu == null || !layoutDeviceMenu.IsOpen || !layoutDeviceMenu.Items.OfType<MenuItem>().Any(i => Convert.ToString(i.Header) == "调整方位") || !layoutDeviceMenu.Items.OfType<MenuItem>().Any(i => Convert.ToString(i.Header) == "桌面控制")) throw new Exception("Right-click must retain the full device menu");
      layoutDeviceMenu.IsOpen = false;
      var statusFixture = new Device { Online = true, Access = new Dictionary<string, object> { { "status", "有限授权" } } };
      if (OnlineStatus(statusFixture) != "未授权") throw new Exception("Partial authorization must not report fully online");
      statusFixture.Online = false; if (OnlineStatus(statusFixture) != "离线") throw new Exception("Offline state must override cached authorization");
      Json.Map(State)["masterId"] = originalMaster; Draw(); CenterLayout();
      await Dispatcher.InvokeAsync(delegate { UpdateLayout(); }, DispatcherPriority.Render);
    }
    async Task VerifyAdbAuthorizationNotification() {
      var original = Json.Get(State, "adbAuthorization");
      Json.Map(State)["adbAuthorization"] = new Dictionary<string, object> { { "requestId", "adb-auto-ui-fixture" }, { "name", "组内手机 · 界面示例" } };
      RefreshAdbAuthorization(); var window = adbAuthorizedWindow;
      await Dispatcher.InvokeAsync(delegate { window.UpdateLayout(); }, DispatcherPriority.Render);
      if (window == null || !window.IsVisible || window.Title != Locale.T("设备已授权")) throw new Exception("ADB completion must display its own ready confirmation");
      RefreshAdbAuthorization(); if (adbAuthorizedWindow != window) throw new Exception("ADB completion polling must not create duplicate popups");
      SavePreview((FrameworkElement)window.Content, "native-adb-authorized.png"); window.Close(); RefreshAdbAuthorization();
      if (adbAuthorizedWindow != null) throw new Exception("Acknowledged ADB completion must not reopen");
      Json.Map(State)["adbAuthorization"] = original;
    }
    async Task VerifyStageReceiveNotification(){
      var original=Json.Get(State,"stageIncoming");
      Func<string,object> fixture=id=>new object[]{new Dictionary<string,object>{{"id",id},{"token",id},{"name","设备布局说明.pdf"},{"sourceName","MacBook · 接收测试"}}};
      Json.Map(State)["stageIncoming"]=fixture("old-preview");RefreshStageReceive();var old=stageReceiveWindow;
      await Dispatcher.InvokeAsync(delegate{old.UpdateLayout();},DispatcherPriority.Render);
      if(old==null||!old.IsVisible||old.ShowInTaskbar||!old.Topmost)throw new Exception("Incoming files must have an independent floating notice");
      if(Math.Abs(old.Top+old.ActualHeight-SystemParameters.WorkArea.Bottom+18)>2)throw new Exception("File notice must be bottom-right");
      RefreshStageReceive();if(old!=stageReceiveWindow)throw new Exception("Polling must preserve the current receive notice");
      SavePreview((FrameworkElement)old.Content,"native-file-receive.png");
      Json.Map(State)["stageIncoming"]=fixture("new-preview");RefreshStageReceive();if(old.IsVisible||stageReceiveWindow==old)throw new Exception("New preview must close the old receive notice");
      var replacement=stageReceiveWindow;Json.Map(State)["stageIncoming"]=new object[0];RefreshStageReceive();if(replacement.IsVisible||stageReceiveWindow!=null)throw new Exception("Retracting a preview must close its receive notice");
      Json.Map(State)["stageIncoming"]=original;RefreshStageReceive();
    }
    async Task SelfTest() {
      await Client.Call("settings",new{language="zh-Hans"});Locale.Set("zh-Hans");
      poll.Stop();
      foreach(var pair in new[]{new[]{"00123456","00123456"},new[]{"0012 3456","00123456"},new[]{"0012\u00A03456\r\n","00123456"}})if(LanLink.Desktop.PairingPanel.NormalizeCode(pair[0])!=pair[1])throw new Exception("Grouped pairing code normalization failed");
      if(LanLink.Desktop.PairingPanel.NormalizeCode("1234AB56")!=null || LanLink.Desktop.PairingPanel.NormalizeCode("123456789")!=null)throw new Exception("Pairing must reject non-digits and oversized codes");
      foreach(var error in new[]{"PERMISSION_REQUIRED: 请允许屏幕共享","CONTROL_CONFLICT: 另一台电脑正在控制","VIDEO_H264_REQUIRED: 请开启视频助手"})if(DisplayError(error).Contains(":"))throw new Exception("Internal error code leaked into user feedback");
      await Client.Call("settings", new { name = "工作电脑", dwell = 500, master = true }); State = await Client.Call("state");
      if (Arguments.Contains("--native") && !Json.Flag(Json.Get(State, "capabilities"), "host")) throw new Exception("Native host capability unavailable");
      if (Json.Text(State, "name") != "工作电脑" || Json.Number(State, "dwell", 0) != 500) throw new Exception("Native settings integration failed");
      var qr = await Client.Call("pairing", new { reveal = true }); if (!System.Text.RegularExpressions.Regex.IsMatch(Json.Text(qr, "code"), "^[0-9]{8}$")) throw new Exception("Pairing failed");
      string image = Json.Text(qr, "qr"); if (DecodeImage(image.Substring(image.IndexOf(',') + 1)).PixelWidth < 100) throw new Exception("QR decode failed");
      var sample = new SharedLayoutGeometry(840, 520, new[] { new SharedLayoutCell(0, 0), new SharedLayoutCell(1, 0), new SharedLayoutCell(2, 0) }); var tip = new SharedLayoutCell(2, 0);
      var roundTrip = sample.Cell(sample.X(tip), sample.Y(tip)); if (!roundTrip.HasValue || roundTrip.Value.X != 2 || tip.Adjacent("bottom").Value.Y != 1) throw new Exception("Global chain/branch layout mapping failed");
      devices.Clear(); devices.Add(new Device { Id = Json.Text(State, "id"), Name = "工作电脑", Platform = "win32", Local = true, Online = true });
      devices.Add(new Device { Id = "preview-mac", Name = "MacBook Pro", Platform = "darwin", Online = true }); devices.Add(new Device { Id = "preview-phone", Name = "Pixel 9", Platform = "android", Online = true }); devices.Add(new Device { Id = "preview-offline", Name = "客厅电脑", Platform = "win32" });
      if (BuildMenu(devices[3]).Items.OfType<MenuItem>().Any(m => Convert.ToString(m.Header) == "桌面控制" && m.IsEnabled)) throw new Exception("Offline actions must be disabled");
      Json.Map(State)["layout"] = new Dictionary<string, object> { { "positions", new Dictionary<string, object> { { Json.Text(State, "id"), new Dictionary<string, object> { { "x", 0 }, { "y", 0 } } }, { "preview-mac", new Dictionary<string, object> { { "x", 1 }, { "y", 0 } } }, { "preview-phone", new Dictionary<string, object> { { "x", 2 }, { "y", 0 } } }, { "preview-offline", new Dictionary<string, object> { { "x", 1 }, { "y", 1 } } } } } }; selected = "preview-mac"; Draw(); Notify("界面预览 · 示例设备，不代表真实连接");
      await Dispatcher.InvokeAsync(delegate { UpdateLayout(); }, DispatcherPriority.Render);
      await VerifyLayoutActions();
      await VerifyAdbAuthorizationNotification();
      await VerifyStageReceiveNotification();
      SavePreview((FrameworkElement)Content, "native-preview.png");
      if (Arguments.Contains("--layout-self-test")) { File.WriteAllText(System.IO.Path.Combine(DataDir, "passed.txt"), "Layout UI: complete scaled badges, left-click quick actions only, right-click full menu, platform/readiness, mouse/file actions, conditional master controls, non-mutating viewport centering and explicit master selection passed. Preview uses labeled fixtures."); await Quit(0); return; }
      controlledTitle.Text = "正在被控制\nPixel 9（界面示例）正在操作或查看本机"; controlledBanner.Visibility = Visibility.Visible;
      await Dispatcher.InvokeAsync(delegate { UpdateLayout(); }, DispatcherPriority.Render); SavePreview((FrameworkElement)Content, "native-controlled.png");
      if (controlledBanner.TranslatePoint(new Point(0, 0), this).Y < Height / 2) throw new Exception("Controlled banner must be near the identity area at bottom of sidebar");
      controlledBanner.Visibility = Visibility.Collapsed;
      stagingNavigation.RaiseEvent(new RoutedEventArgs(System.Windows.Controls.Button.ClickEvent));await stagingPage.Refresh();
      if(stagingPage.Visibility!=Visibility.Visible||devicePage.Visibility!=Visibility.Collapsed)throw new Exception("Staging must be an embedded sidebar page");
      var stageFixture=new {files=new[]{new{id="preview-ready",name="季度汇总与项目资料.zip",state="ready",type="file",sourceName="小米 15 · 界面示例",size=5200011,created=(DateTime.UtcNow-new DateTime(1970,1,1)).TotalMilliseconds,error="",received=5200011,count=1},new{id="preview-pending",name="待传输文件夹",state="pending",type="folder",sourceName="MacBook Pro · 界面示例",size=1800011,created=(DateTime.UtcNow-new DateTime(1970,1,1)).TotalMilliseconds,error="",received=0,count=3}}};stagingPage.SetPreview(new System.Web.Script.Serialization.JavaScriptSerializer().DeserializeObject(new System.Web.Script.Serialization.JavaScriptSerializer().Serialize(stageFixture)));
      await Dispatcher.InvokeAsync(delegate{UpdateLayout();},DispatcherPriority.Render);if(stagingPage.RowCount!=2)throw new Exception("Ready and pending staging cards must be visible");SavePreview((FrameworkElement)Content,"native-staging.png");
      FileShareShell.Enqueue(new[]{System.IO.Path.Combine(DataDir,"文件 测试.txt"),System.IO.Path.Combine(DataDir,"资料文件夹")});var queued=FileShareShell.Drain().ToArray();if(queued.Length!=1||queued[0].Length!=2||FileShareShell.Drain().Any())throw new Exception("Shell share IPC must preserve multi-path Unicode payloads and drain once");
      var sharePreview=new FileShareWindow(this,new[]{System.IO.Path.Combine(DataDir,"季度汇总.zip")},devices);sharePreview.Show();await Dispatcher.InvokeAsync(delegate{sharePreview.UpdateLayout();},DispatcherPriority.Render);SavePreview((FrameworkElement)sharePreview.Content,"native-file-share.png");sharePreview.Close();
      informationNavigation.RaiseEvent(new RoutedEventArgs(System.Windows.Controls.Button.ClickEvent));
      if(informationPage==null||informationPage.Visibility!=Visibility.Visible||devicePage.Visibility!=Visibility.Collapsed)throw new Exception("My information must be a page");
      await Dispatcher.InvokeAsync(delegate{UpdateLayout();},DispatcherPriority.Render);SavePreview((FrameworkElement)Content,"native-information.png");
      adbNavigation.RaiseEvent(new RoutedEventArgs(System.Windows.Controls.Button.ClickEvent));
      if(adbHub==null||adbHub.Visibility!=Visibility.Visible||informationPage.Visibility!=Visibility.Collapsed)throw new Exception("ADB navigation must show group phone selector");
      await Dispatcher.InvokeAsync(delegate{UpdateLayout();},DispatcherPriority.Render);SavePreview((FrameworkElement)Content,"native-adb-hub.png");
      var guidePreview = new AdbGuideWindow(this, new System.Web.Script.Serialization.JavaScriptSerializer().DeserializeObject("{\"id\":\"preview-phone\",\"requestId\":\"preview\",\"name\":\"小米 15\",\"capture\":true}"),true); guidePreview.Show();
      await Dispatcher.InvokeAsync(delegate { guidePreview.UpdateLayout(); },DispatcherPriority.Render); SavePreview((FrameworkElement)guidePreview.Content,"native-adb-guide.png"); guidePreview.End();
      var revokedPreview=new AdbGuideWindow(this,new System.Web.Script.Serialization.JavaScriptSerializer().DeserializeObject("{\"id\":\"preview-phone\",\"requestId\":\"revoked-fixture\",\"name\":\"小米 15\",\"revoked\":true}"),true);revokedPreview.Show();await Dispatcher.InvokeAsync(delegate{revokedPreview.UpdateLayout();},DispatcherPriority.Render);SavePreview((FrameworkElement)revokedPreview.Content,"native-adb-allow.png");revokedPreview.End();
      ShowAdb(devices[2], false);
      adbPage.SetPreview(new { available = true, devices = new[] { new { serial = "USB-DEMO-01", status = "device", model = "Pixel_9（界面示例）" }, new { serial = "USB-DEMO-02", status = "unauthorized", model = "Android 手机（界面示例）" }, new { serial = "USB-DEMO-03", status = "offline", model = "Android 手机（界面示例）" } }, message = "界面示例 · 请手动选择对应手机，未执行任何授权。" });
      await Dispatcher.InvokeAsync(delegate { UpdateLayout(); }, DispatcherPriority.Render);
      if (adbPage.TransportCount != 3 || adbPage.CanAuthorize || devicePage.Visibility != Visibility.Collapsed) throw new Exception("ADB must use embedded page, show automatic matching without transport selection");
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
      var focusedPermission=devicePermissionsPage.InputButton;focusedPermission.Focus();await devicePermissionsPage.Refresh();
      if(devicePermissionsPage.InputButton!=focusedPermission || !focusedPermission.IsKeyboardFocusWithin)throw new Exception("Permission refresh must preserve the exact focused toggle");
      devicePermissionsPage.Display(new System.Web.Script.Serialization.JavaScriptSerializer().DeserializeObject("{\"devices\":[{\"id\":\"preview-local\",\"name\":\"我的电脑 · 界面示例\",\"platform\":\"win32\",\"local\":true,\"online\":true,\"permissions\":[{\"key\":\"input\",\"label\":\"键鼠控制\",\"supported\":true,\"app\":\"已开启\",\"system\":\"已就绪\",\"consent\":\"已允许\",\"status\":\"已就绪\",\"state\":\"ready\"},{\"key\":\"screen\",\"label\":\"屏幕查看\",\"supported\":true,\"app\":\"已开启\",\"system\":\"已就绪\",\"consent\":\"已允许\",\"status\":\"已就绪\",\"state\":\"ready\"},{\"key\":\"clipboard\",\"label\":\"剪贴板与文件\",\"supported\":true,\"app\":\"已开启\",\"system\":\"已就绪\",\"consent\":\"已允许\",\"status\":\"已就绪\",\"state\":\"ready\"},{\"key\":\"shutdown\",\"label\":\"远程关机\",\"supported\":true,\"app\":\"未开启\",\"system\":\"已就绪\",\"consent\":\"已允许\",\"status\":\"应用未开启\",\"state\":\"disabled\"}]},{\"id\":\"preview-mac\",\"name\":\"MacBook Pro · 界面示例\",\"platform\":\"darwin\",\"local\":false,\"online\":true,\"permissions\":[{\"key\":\"input\",\"label\":\"键鼠控制\",\"supported\":true,\"app\":\"已开启\",\"system\":\"未就绪\",\"consent\":\"已允许\",\"status\":\"系统未就绪\",\"state\":\"unready\"},{\"key\":\"screen\",\"label\":\"屏幕查看\",\"supported\":true,\"app\":\"已开启\",\"system\":\"已就绪\",\"consent\":\"已允许\",\"status\":\"已就绪\",\"state\":\"ready\"},{\"key\":\"clipboard\",\"label\":\"剪贴板与文件\",\"supported\":true,\"app\":\"未开启\",\"system\":\"已就绪\",\"consent\":\"已允许\",\"status\":\"应用未开启\",\"state\":\"disabled\"},{\"key\":\"shutdown\",\"label\":\"远程关机\",\"supported\":true,\"app\":\"已开启\",\"system\":\"已就绪\",\"consent\":\"已允许\",\"status\":\"已就绪\",\"state\":\"ready\"}]},{\"id\":\"preview-phone\",\"name\":\"Pixel 9 · 界面示例\",\"platform\":\"android\",\"local\":false,\"online\":true,\"permissions\":[{\"key\":\"input\",\"label\":\"键鼠控制\",\"supported\":true,\"app\":\"已开启\",\"system\":\"未就绪\",\"consent\":\"已允许\",\"status\":\"系统未就绪\",\"state\":\"unready\"},{\"key\":\"screen\",\"label\":\"屏幕查看\",\"supported\":true,\"app\":\"已开启\",\"system\":\"未就绪\",\"consent\":\"已允许\",\"status\":\"系统未就绪\",\"state\":\"unready\"},{\"key\":\"clipboard\",\"label\":\"剪贴板与文件\",\"supported\":true,\"app\":\"已开启\",\"system\":\"已就绪\",\"consent\":\"已允许\",\"status\":\"已就绪\",\"state\":\"ready\"},{\"key\":\"shutdown\",\"label\":\"远程关机\",\"supported\":true,\"app\":\"未开启\",\"system\":\"已就绪\",\"consent\":\"已允许\",\"status\":\"应用未开启\",\"state\":\"disabled\"}]}]}"));
      await Dispatcher.InvokeAsync(delegate { UpdateLayout(); }, DispatcherPriority.Render);
      if (devicePage.Visibility != Visibility.Collapsed || devicePermissionsPage.Visibility != Visibility.Visible) throw new Exception("Permission details must be embedded");
      SavePreview((FrameworkElement)Content,"native-permissions.png");
      var permissionIcon=devicePermissionsPage.PermissionButton("preview-local","input");
      permissionIcon.RaiseEvent(new RoutedEventArgs(System.Windows.Controls.Primitives.ButtonBase.ClickEvent));
      await Dispatcher.InvokeAsync(delegate{permissionIcon.ContextMenu.UpdateLayout();},DispatcherPriority.ContextIdle);
      if(!permissionIcon.ContextMenu.IsOpen || !devicePermissionsPage.InputToggle.IsVisible)throw new Exception("Left click must open the ready permission menu with a switch");
      SavePreview(permissionIcon.ContextMenu,"native-permission-menu-ready.png");permissionIcon.ContextMenu.IsOpen=false;
      var unavailableIcon=devicePermissionsPage.PermissionButton("preview-mac","input");
      unavailableIcon.RaiseEvent(new MouseButtonEventArgs(Mouse.PrimaryDevice,Environment.TickCount,MouseButton.Right){RoutedEvent=UIElement.PreviewMouseRightButtonUpEvent});
      await Dispatcher.InvokeAsync(delegate{unavailableIcon.ContextMenu.UpdateLayout();},DispatcherPriority.ContextIdle);
      if(!unavailableIcon.ContextMenu.IsOpen || !Convert.ToString(unavailableIcon.ToolTip).Contains(Locale.T("未就绪")))throw new Exception("Right click must open the selected unready permission menu");
      SavePreview(unavailableIcon.ContextMenu,"native-permission-menu-unready.png");unavailableIcon.ContextMenu.IsOpen=false;
      var permissionPreviewDevices=devices.ToArray();var permissionPreviewState=State;string permissionPreviewSignature=signature;
      await OpenPermissionGuide(Json.Text(State,"id"),"input",Json.Text(State,"name"));
      await Dispatcher.InvokeAsync(delegate { if(permissionGuideWindow!=null)permissionGuideWindow.UpdateLayout(); },DispatcherPriority.Render);
      if(permissionGuideWindow==null)throw new Exception("Selecting this device must open its focused permission guide");
      SavePreview((FrameworkElement)permissionGuideWindow.Content,"native-permission-guide-local.png");
      await Client.Call("permission-dismiss",new{requestId=permissionGuideId});permissionGuideWindow.Close();await Refresh();
      ShowPermissionTargetReminder("MacBook Pro · 界面示例");
      var switchGuide=Application.Current.Windows.OfType<Window>().First(w=>w.Title==Locale.T("切换观看设备"));
      await Dispatcher.InvokeAsync(delegate{switchGuide.UpdateLayout();},DispatcherPriority.Render);
      SavePreview((FrameworkElement)switchGuide.Content,"native-permission-switch-device.png");switchGuide.Close();
      devices.Clear();devices.AddRange(permissionPreviewDevices);State=permissionPreviewState;signature=permissionPreviewSignature;
      ShowDevices();
      var menu = BuildMenu(devices[1]); menu.PlacementTarget = this; menu.Placement = PlacementMode.Center; menu.IsOpen = true;
      await Dispatcher.InvokeAsync(delegate { menu.UpdateLayout(); }, DispatcherPriority.ContextIdle);
      var glassSample = menu.Template.FindName("GlassSample", menu) as Rectangle;
      if (!SystemParameters.HighContrast && (glassSample == null || !(glassSample.Fill is VisualBrush) || !(glassSample.Effect is System.Windows.Media.Effects.BlurEffect))) throw new Exception("Menu background must use Gaussian blur");
      SavePreview(menu, "native-menu.png");
      var submenu = menu.Items.OfType<MenuItem>().First(x => Convert.ToString(x.Header) == "调整方位"); submenu.IsSubmenuOpen = true;
      await Dispatcher.InvokeAsync(delegate { submenu.UpdateLayout(); }, DispatcherPriority.Render);
      var popup = submenu.Template.FindName("PART_Popup", submenu) as Popup;
      if (popup == null || !popup.IsOpen || popup.Child == null) throw new Exception("Rounded submenu navigation failed");
      SavePreview((FrameworkElement)popup.Child,"native-layout-picker.png");
      submenu.IsSubmenuOpen = false; menu.IsOpen = false;
      int windowsBefore = Application.Current.Windows.Count;
      settingsNavigation.RaiseEvent(new RoutedEventArgs(System.Windows.Controls.Button.ClickEvent));
      if (settingsPage.Visibility != Visibility.Visible || devicePage.Visibility != Visibility.Collapsed || Application.Current.Windows.Count != windowsBefore) throw new Exception("Settings must navigate within the main window");
      settingsSwitchMode.SelectedIndex=1;settingsHotkey.SelectedIndex=1;settingsDwell.Value=0;if(settingsZeroHint.Visibility!=Visibility.Visible)throw new Exception("Zero delay connection hint missing");await SaveSettings();if(Json.Text(State,"switchMode")!="hotkey" || Json.Number(State,"dwell",-1)!=0 || Json.Text(State,"edgeHotkey")!="ctrl-shift")throw new Exception("Switch mode / zero slider must persist locally");settingsSwitchMode.SelectedIndex=0;settingsDwell.Value=100;await SaveSettings();
      settingsName.Text="保存期间锁定验证";var savingDraft=SaveSettings();
      if(settingsPage.IsEnabled || settingsName.IsEnabled || settingsDwell.IsEnabled || clipboardUploadOption.IsEnabled)throw new Exception("Every settings field must be disabled while a save is pending");
      await savingDraft;
      clipboardUploadOption.IsChecked=false;await immediateSettingsTask;
      if(Json.Flag(Json.Get(await Client.Call("state"),"clipboardSettings"),"upload"))throw new Exception("Disabling automatic clipboard upload must take effect before pressing Save");
      clipboardUploadOption.IsChecked=true;await immediateSettingsTask;
      settingsName.Text = "工作电脑 · 草稿";
      homeNavigation.RaiseEvent(new RoutedEventArgs(System.Windows.Controls.Button.ClickEvent));
      if (devicePage.Visibility != Visibility.Visible) throw new Exception("Device navigation failed");
      settingsNavigation.RaiseEvent(new RoutedEventArgs(System.Windows.Controls.Button.ClickEvent));
      if (settingsName.Text != "工作电脑 · 草稿") throw new Exception("Settings draft lost on navigation");
      string masterDraft=Convert.ToString(((ComboBoxItem)settingsMaster.SelectedItem).Tag);double dwellDraft=settingsDwell.Value;var literalUserText=Text("设置");
      restoringLanguage=true;settingsLanguage.SelectedItem=settingsLanguage.Items.Cast<ComboBoxItem>().First(item=>Convert.ToString(item.Tag)=="en");restoringLanguage=false;await ChangeLanguage();
      if(Locale.Language!="en" || Json.Text(await Client.Call("state"),"language")!="en" || Convert.ToString(saveSettingsButton.Content)=="保存设置")throw new Exception("Saved language must update visible settings immediately");
      if(literalUserText.Text!="设置" || settingsName.Text!="工作电脑 · 草稿" || !settingsDirty || settingsDwell.Value!=dwellDraft || Convert.ToString(((ComboBoxItem)settingsMaster.SelectedItem).Tag)!=masterDraft)throw new Exception("Changing language must preserve the unsaved settings draft");
      restoringLanguage=true;settingsLanguage.SelectedItem=settingsLanguage.Items.Cast<ComboBoxItem>().First(item=>Convert.ToString(item.Tag)=="zh-Hans");restoringLanguage=false;await ChangeLanguage();
      if(Convert.ToString(saveSettingsButton.Content)!="保存设置")throw new Exception("Switching back must restore Chinese labels without recreating settings");
      await SaveSettings(); if (Json.Text(await Client.Call("state"), "name") != "工作电脑 · 草稿") throw new Exception("Embedded settings save failed");
      settingsName.Text = ""; await SaveSettings();
      if (!settingsStatus.Text.Contains("设备名称") || !settingsDirty || !saveSettingsButton.IsEnabled) throw new Exception("Invalid settings must retain the draft and show an inline error");
      settingsName.Text = "工作电脑"; await SaveSettings();
      if(clipboardUploadOption.IsChecked!=true || clipboardDownloadOption.IsChecked!=true)throw new Exception("Clipboard automatic options must default enabled");
      if(clipboardShortcutOption.Visibility!=Visibility.Collapsed)throw new Exception("Shortcut preference must be hidden while both directions are automatic");
      clipboardUploadOption.IsChecked=false;clipboardDownloadOption.IsChecked=false;clipboardShortcutOption.IsChecked=true;await SaveSettings();
      if(clipboardUploadButton.Visibility!=Visibility.Visible || clipboardDownloadButton.Visibility!=Visibility.Visible || clipboardQuick.Visibility!=Visibility.Visible)throw new Exception("Manual clipboard controls and home shortcuts must follow saved preferences");
      ShowDevices();await Dispatcher.InvokeAsync(delegate{UpdateLayout();},DispatcherPriority.Render);
      var uploadQuick=(Button)clipboardQuick.Children[0];var downloadQuick=(Button)clipboardQuick.Children[1];var quickHeader=(Grid)clipboardQuick.Parent;var addQuick=quickHeader.Children.OfType<Button>().First(b=>b.Name=="AddDeviceButton");
      var uploadOrigin=uploadQuick.TranslatePoint(new Point(0,0),devicePage);var downloadOrigin=downloadQuick.TranslatePoint(new Point(0,0),devicePage);var addOrigin=addQuick.TranslatePoint(new Point(0,0),devicePage);
      if(!uploadQuick.IsVisible || uploadQuick.ActualWidth!=42 || uploadQuick.ActualHeight!=42 || downloadOrigin.X-uploadOrigin.X-uploadQuick.ActualWidth<7 || addOrigin.X-downloadOrigin.X-downloadQuick.ActualWidth<11)throw new Exception("Circular clipboard buttons must render with spacing before Add device");
      SavePreview((FrameworkElement)Content,"native-clipboard-home.png");
      Settings();clipboardUploadOption.IsChecked=true;clipboardDownloadOption.IsChecked=true;await SaveSettings();
      if(clipboardShortcutOption.Visibility!=Visibility.Collapsed || clipboardQuick.Visibility!=Visibility.Collapsed)throw new Exception("Both automatic directions must hide the shortcut preference and home actions");
      clipboardUploadOption.IsChecked=false;await SaveSettings();if(clipboardQuick.Visibility!=Visibility.Visible || clipboardShortcutOption.IsChecked!=true)throw new Exception("A saved shortcut choice must return when an automatic direction is disabled");
      await Client.Call("clipboard",new{text="界面示例 · 已同步的文字可以一键复制。"});State=await Client.Call("state");ShowClipboardPage();
      if(clipboardBody.Children.Count<2 || clipboardPage.Visibility!=Visibility.Visible || devicePage.Visibility!=Visibility.Collapsed)throw new Exception("Clipboard history must have its own navigation page");
      await Dispatcher.InvokeAsync(delegate{UpdateLayout();},DispatcherPriority.Render);SavePreview((FrameworkElement)Content,"native-clipboard.png");
      var records=Json.Get(Json.Get(State,"clipboardSettings"),"history") as IEnumerable;string clipboardRecord="";foreach(var record in records){clipboardRecord=Json.Text(record,"id");break;}var copied=await Client.Call("clipboard-copy",new{id=clipboardRecord});if(Json.Text(copied,"message")=="")throw new Exception("Copy history must provide a Toast result");
      await ManualClipboard("clipboard-download");await Dispatcher.InvokeAsync(delegate{UpdateLayout();},DispatcherPriority.Render);SavePreview((FrameworkElement)Content,"native-clipboard-toast.png");if(clipboardToast==null || !clipboardToast.IsOpen)throw new Exception("Manual sync Toast missing");clipboardToast.IsOpen=false;
      Settings();clipboardUploadOption.IsChecked=true;clipboardDownloadOption.IsChecked=true;clipboardShortcutOption.IsChecked=false;await SaveSettings();
      await Dispatcher.InvokeAsync(delegate { UpdateLayout(); }, DispatcherPriority.Render);
      SavePreview((FrameworkElement)Content, "native-settings.png");var settingsScroll=(ScrollViewer)settingsPage.Children[1];settingsScroll.ScrollToEnd();await Dispatcher.InvokeAsync(delegate{UpdateLayout();},DispatcherPriority.Render);SavePreview((FrameworkElement)Content,"native-settings-bottom.png");settingsScroll.ScrollToTop();
      var settingsWidth = Width; var settingsHeight = Height; Width = MinWidth; Height = MinHeight;
      await Dispatcher.InvokeAsync(delegate { UpdateLayout(); }, DispatcherPriority.Render);
      SavePreview((FrameworkElement)Content, "native-settings-compact.png"); Width = settingsWidth; Height = settingsHeight; ShowDevices();
      Notify(""); ShowDevices(); UpdateLayout(); double pageHeight = pageSurface.ActualHeight;
      NotifyTransient("待传输文件已添加到对方暂存区"); UpdateLayout();
      if(notificationBanner.Visibility!=Visibility.Collapsed || pageSurface.ActualHeight!=pageHeight || clipboardToast==null || !clipboardToast.IsOpen)throw new Exception("Transient feedback must float without resizing pages");
      Toast("旧反馈",0.05); Toast("新反馈",0.4); await Task.Delay(150);
      if(!clipboardToast.IsOpen)throw new Exception("An old timer dismissed the replacement Toast");
      Notify("需要处理的错误"); await Task.Delay(400); await Dispatcher.InvokeAsync(delegate { UpdateLayout(); }, DispatcherPriority.ApplicationIdle);
      if(clipboardToast.IsOpen || !notificationBanner.IsVisible)throw new Exception("Toast must expire without dismissing persistent errors: open="+clipboardToast.IsOpen+", banner="+notificationBanner.Visibility+", visible="+notificationBanner.IsVisible+", text="+notice.Text);
      shutdownNotice="关机倒计时"; UpdateNotificationBanner(); NotifyTransient("操作已完成");
      if(notice.Text!=shutdownNotice)throw new Exception("Transient feedback must preserve an ongoing countdown");
      shutdownNotice=""; Notify("");
      if(notificationBanner.Visibility!=Visibility.Collapsed)throw new Exception("Ended states must release banner space");
      foreach(Action navigate in new Action[]{ShowInformation,Settings,ShowClipboardPage,ShowStaging,ShowDevicePermissions,ShowDevices}){navigate();Notify("当前页错误反馈验证");await Dispatcher.InvokeAsync(delegate{UpdateLayout();},DispatcherPriority.Render);if(!notice.IsVisible || !notificationBanner.IsVisible)throw new Exception("Global errors must remain visible on every page");}
      ShowInformation(); var pairing = informationPairing; await Dispatcher.InvokeAsync(delegate { UpdateLayout(); }, DispatcherPriority.Render); await pairing.InitialLoad; pairing.UpdateLayout();
      if (pairing.QRVisible || pairing.CurrentCode == null) throw new Exception("Pairing must initially show code with QR skeleton");
      await Dispatcher.InvokeAsync(delegate { pairing.UpdateLayout(); }, DispatcherPriority.Render);
      SavePreview((FrameworkElement)Content, "native-pairing.png");
      await pairing.ToggleQR(); if (!pairing.QRVisible) throw new Exception("Reveal QR failed");
      pairing.UpdateLayout(); SavePreview((FrameworkElement)Content, "native-pairing-qr.png");
      pairing.ToggleJoin(); if (!pairing.JoiningVisible) throw new Exception("Inline join input failed");
      await pairing.Join(); // Empty input must fail locally, without changing accounts.
      pairing.UpdateLayout(); SavePreview((FrameworkElement)Content, "native-pairing-join.png");
      await pairing.ToggleQR(); if (pairing.QRVisible) throw new Exception("Hide QR failed");
      await pairing.ObserveConnections(new System.Web.Script.Serialization.JavaScriptSerializer().DeserializeObject("{\"peers\":[{\"id\":\"new-pairing-device\"}]}"));
      if (devicePage.Visibility != Visibility.Visible || informationPage.Visibility != Visibility.Collapsed) throw new Exception("Authenticated pairing must return to devices automatically");
      var sizeFile=System.IO.Path.Combine(DataDir,"size-fixture.json");var sizeSource=new Window{Width=960,Height=680};WindowSize.Save(sizeSource,sizeFile);var sizeRestored=new Window();WindowSize.Restore(sizeRestored,sizeFile);if(Math.Abs(sizeRestored.Width/sizeRestored.Height-960.0/680)>0.001)throw new Exception("Window size ratio must survive restart");
      var originalWidth = Width; var originalHeight = Height; Width = MinWidth; Height = MinHeight;
      await Dispatcher.InvokeAsync(delegate { UpdateLayout(); }, DispatcherPriority.Render);
      SavePreview((FrameworkElement)Content, "native-compact.png"); Width = originalWidth; Height = originalHeight;
      WindowState = WindowState.Maximized; await Dispatcher.InvokeAsync(delegate { UpdateLayout(); }, DispatcherPriority.Render);
      if (WindowState != WindowState.Maximized) throw new Exception("Native frame maximize failed");
      WindowState = WindowState.Normal;
      Hide(); if (IsVisible) throw new Exception("Tray hide failed"); Show(); if (!IsVisible) throw new Exception("Tray restore failed");
      if (startupOption == null || adminStartupOption==null) throw new Exception("Startup option missing");Startup.Verify();
      var phoneRemote = new RemoteWindow(this, new Device { Id = "preview-phone", Name = "示例手机（未连接）", Platform = "android", Width = 1080, Height = 2340 }); phoneRemote.Show();
      await Dispatcher.InvokeAsync(delegate { phoneRemote.UpdateLayout(); }, DispatcherPriority.Render); await Task.Delay(150);
      if (!phoneRemote.ControlRequested || !phoneRemote.HasRightToolbar || phoneRemote.NavigationKeyCount != 3 || phoneRemote.Width >= phoneRemote.Height || phoneRemote.Height > SystemParameters.WorkArea.Height) throw new Exception("Phone viewer must auto request control, fit screen and offer three navigation keys below the screen");
      if(!phoneRemote.VisibleStatusForTest)throw new Exception("Remote connection failures must be visible with the sidebar collapsed");
      if(!phoneRemote.SidebarCollapsed)throw new Exception("First remote opening must hide the sidebar");phoneRemote.VerifyFooter();phoneRemote.ExpandForTest();await phoneRemote.WaitSidebarForTest();phoneRemote.VerifyPhoneSizing();phoneRemote.ShowMetricsPreview();await Task.Delay(260);phoneRemote.UpdateLayout();SavePreview((FrameworkElement)phoneRemote.Content,"native-remote-phone.png");
      await phoneRemote.VerifySidebarAnimation();phoneRemote.CollapseForTest();phoneRemote.Left=SystemParameters.WorkArea.Left+80;phoneRemote.Top=SystemParameters.WorkArea.Top+20;
      var savedRemoteBounds=new Rect(phoneRemote.Left,phoneRemote.Top,phoneRemote.Width,phoneRemote.Height);SavePreview((FrameworkElement)phoneRemote.Content,"native-remote-collapsed.png");phoneRemote.Close();
      var reopened=new RemoteWindow(this,new Device{Id="preview-phone",Name="示例手机（未连接）",Platform="android",Width=720,Height=1600});reopened.Show();await Dispatcher.InvokeAsync(delegate{reopened.UpdateLayout();},DispatcherPriority.Loaded);
      if(!reopened.SidebarCollapsed || Math.Abs(reopened.Left-savedRemoteBounds.Left)>1 || Math.Abs(reopened.Top-savedRemoteBounds.Top)>1 || Math.Abs(reopened.Width-savedRemoteBounds.Width)>1 || Math.Abs(reopened.Height-savedRemoteBounds.Height)>1)throw new Exception("Per-device remote bounds and collapsed state must survive reopening");
      reopened.ExpandForTest();await reopened.WaitSidebarForTest();reopened.Close();
      var expandedAgain=new RemoteWindow(this,new Device{Id="preview-phone",Name="示例手机",Platform="android",Width=720,Height=1600});expandedAgain.Show();await Dispatcher.InvokeAsync(delegate{expandedAgain.UpdateLayout();},DispatcherPriority.Loaded);if(expandedAgain.SidebarCollapsed)throw new Exception("Saved expanded state must override the initial default");expandedAgain.VerifyFooter();expandedAgain.Close();await EdgeGlow.Verify();
      if(WindowSize.RemoteFile(DataDir,"phone/a")==WindowSize.RemoteFile(DataDir,"phone/b"))throw new Exception("Remote geometry must be isolated by device identity");
      ShowDevices();devices.Clear();devices.Add(new Device{Id=Json.Text(State,"id"),Name="工作电脑",Platform="win32",Local=true,Online=true});devices.Add(new Device{Id="preview-phone",Name="小米 15 · 界面示例",Platform="android",Online=true});edges.Clear();Json.Map(State)["layout"]=new Dictionary<string,object>{{"positions",new Dictionary<string,object>{{Json.Text(State,"id"),new Dictionary<string,object>{{"x",0},{"y",0}}},{"preview-phone",new Dictionary<string,object>{{"x",1},{"y",0}}}}}};var phoneTile=devices.First(d=>d.Platform=="android");phoneTile.Transport="USB + 局域网";var savedActive=Json.Get(State,"active");Json.Map(State)["active"]=phoneTile.Id;Draw();await Task.Delay(350);await Dispatcher.InvokeAsync(delegate{UpdateLayout();},DispatcherPriority.Render);
      if(Status(phoneTile)!="● 融合控制中" || canvas.Children.OfType<System.Windows.Shapes.Line>().Count(l=>l.StrokeDashArray.Count>0)!=2)throw new Exception("Active USB + LAN must show fusion state and twin transfer animations");
      var androidMenu=BuildMenu(phoneTile);if(androidMenu.Items.OfType<MenuItem>().Any(i=>Convert.ToString(i.Header)=="管理此设备对本机的权限" || Convert.ToString(i.Header)=="撤销此设备的本机权限"))throw new Exception("Layout menus must keep permission management on its dedicated page");
      SavePreview((FrameworkElement)Content,"native-fusion-layout.png");Json.Map(State)["active"]=savedActive;phoneTile.Transport="";Draw();
      var realState = State;
      State = new System.Web.Script.Serialization.JavaScriptSerializer().DeserializeObject("{\"consentRequests\":[{\"id\":\"preview-device\",\"requestId\":\"fixture\",\"name\":\"示例设备（界面测试）\"}]}"); RefreshRequests();clipboardQuick.Visibility=Json.Flag(Json.Get(State,"clipboardSettings"),"showShortcuts")?Visibility.Visible:Visibility.Collapsed;if(clipboardPage!=null && clipboardPage.Visibility==Visibility.Visible)RefreshClipboardPage();
      await Dispatcher.InvokeAsync(delegate { consentWindow.UpdateLayout(); }, DispatcherPriority.Render);
      SavePreview((FrameworkElement)consentWindow.Content, "native-consent.png"); consentWindow.Close();
      State = realState;
      File.WriteAllText(System.IO.Path.Combine(DataDir, "passed.txt"), "Native WPF: settings IPC, pairing QR decode, global chain/branch grid, offline menu, rounded menu/submenu, embedded settings navigation/draft/save, Gaussian popup background, dynamic code + skeleton/reveal/hide QR + inline join validation, compact window, maximize/restore, tray hide/restore, controlled banner above identity, embedded ADB transport states and explicit selection, compact Android viewer with locked aspect at all eight resize edges and persistent bottom navigation, four-line metrics, sidebar folding and per-device window restore, staging cards, file share chooser and shell share inbox passed. Preview uses labeled fixtures."); await Quit(0);
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
      Locale.Initialize(Root,DataDir);
      int startupIndex=Array.IndexOf(args,"--startup-admin");if(startupIndex>=0){try{int userIndex=Array.IndexOf(args,"--startup-user");Startup.Configure(args[startupIndex+1]=="on",args[userIndex+1]);return 0;}catch(Exception ex){MessageBox.Show(ex.Message,Locale.T("LanLink 启动设置"));return 1;}}
      int shareIndex=Array.IndexOf(args,"--share");if(shareIndex>=0&&shareIndex+1<args.Length)FileShareShell.Enqueue(new[]{args[shareIndex+1]});
      string hash; using (var sha = System.Security.Cryptography.SHA256.Create()) hash = BitConverter.ToString(sha.ComputeHash(System.Text.Encoding.UTF8.GetBytes(DataDir.ToLowerInvariant()))).Replace("-", "");
      bool first; using (var mutex = new Mutex(true, "Local\\LanLinkNative-" + hash, out first)) {
        if (!first) { if(shareIndex<0)MessageBox.Show(Locale.T("LanLink 已在运行，请从系统托盘打开。"), "LanLink"); return 0; }
        var app = new Application { ShutdownMode = ShutdownMode.OnExplicitShutdown };
        try { return app.Run(new MainWindow()); } catch (Exception ex) { File.WriteAllText(System.IO.Path.Combine(DataDir, "startup-error.txt"), ex.ToString()); if (!args.Contains("--self-test")) MessageBox.Show(ex.Message, Locale.T("LanLink 启动失败")); return 1; }
      }
    }
  }
}
