using System;
using System.Linq;
using System.Runtime.InteropServices;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Shapes;
using Forms = System.Windows.Forms;

namespace LanLink.Desktop {
  // Quick actions shown from the notification-area icon: online devices and their common actions.
  public partial class MainWindow {
    Window quickPanel; DateTime quickPanelClosedAt = DateTime.MinValue;
    [DllImport("dwmapi.dll", EntryPoint = "DwmSetWindowAttribute")] static extern int QuickPanelAttribute(IntPtr hwnd, int attribute, ref int value, int size);
    [DllImport("dwmapi.dll", EntryPoint = "DwmExtendFrameIntoClientArea")] static extern int QuickPanelExtend(IntPtr hwnd, ref Margins value);

    void ToggleQuickPanel(Forms.Screen screen) {
      if (quickPanel != null) { quickPanel.Close(); return; }
      // The tray click itself deactivates an open panel; treat that click as "close".
      if ((DateTime.Now - quickPanelClosedAt).TotalMilliseconds < 350) return;
      ShowQuickPanel(screen);
    }

    void ShowQuickPanel(Forms.Screen screen) {
      var root = new Border { CornerRadius = new CornerRadius(12), Background = Brush("#F9FBFE"), Padding = new Thickness(16, 14, 16, 14) };
      var layout = new DockPanel(); root.Child = layout;
      var window = new Window {
        Title = "LanLink", Width = 360, SizeToContent = SizeToContent.Height, WindowStyle = WindowStyle.None, ResizeMode = ResizeMode.NoResize,
        ShowInTaskbar = false, Topmost = true, ShowActivated = true, Resources = Resources, FontFamily = FontFamily, FontSize = 14,
        Background = Brush("#F9FBFE"), Content = root, Opacity = 0
      };
      quickPanel = window;
      layout.Children.Add(QuickPanelHeader(window));
      var footer = QuickPanelFooter(window); DockPanel.SetDock(footer, Dock.Bottom); layout.Children.Add(footer);
      var list = new StackPanel();
      layout.Children.Add(new ScrollViewer { Content = list, MaxHeight = 430, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Margin = new Thickness(0, 4, 0, 4) });
      var online = devices.Where(d => !d.Local && d.Online).OrderBy(d => d.Name).ToList();
      var count = Text(online.Count.ToString(), 12, "#65748B"); count.Margin = new Thickness(6, 0, 0, 0);
      var section = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(2, 0, 0, 8) };
      var sectionTitle = LText("在线设备", 12, "#65748B"); sectionTitle.FontWeight = FontWeights.SemiBold; section.Children.Add(sectionTitle); section.Children.Add(count);
      list.Children.Add(section);
      if (State == null) list.Children.Add(QuickPanelEmpty("正在连接设备服务…"));
      else if (online.Count == 0) list.Children.Add(QuickPanelEmpty("暂无在线设备。请打开对方 LanLink，并保持同一网络。"));
      foreach (var device in online) list.Children.Add(QuickPanelDevice(device, window));

      window.SourceInitialized += delegate { var area = screen.WorkingArea;
        MoveQuickPanel(window, area.Left + area.Width / 2, area.Top + area.Height / 2);
        QuickPanelGlass(window, root); };
      // Closing deactivates the window; never re-enter Close from that deactivation.
      bool closing = false; window.Closing += delegate { closing = true; };
      window.Deactivated += delegate { if (!closing) window.Close(); };
      window.PreviewKeyDown += (sender, e) => { if (e.Key == Key.Escape && !closing) window.Close(); };
      window.Closed += delegate { if (quickPanel == window) quickPanel = null; quickPanelClosedAt = DateTime.Now; };
      window.ContentRendered += delegate { if (closing || quickPanel != window) return; PlaceQuickPanel(window, screen); window.Opacity = 1; window.Activate(); };
      window.Show(); window.Activate();
    }

    FrameworkElement QuickPanelHeader(Window window) {
      var header = new Grid { Margin = new Thickness(0, 0, 0, 12) }; DockPanel.SetDock(header, Dock.Top);
      header.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto }); header.ColumnDefinitions.Add(new ColumnDefinition()); header.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
      var brand = new Border { Width = 36, Height = 36, CornerRadius = new CornerRadius(10), Background = Brush("#ECEEFC"), Child = QuickGlyph("brand", 20, "#5B6BD5"), Margin = new Thickness(0, 0, 10, 0) }; header.Children.Add(brand);
      var words = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
      var title = Text("LanLink", 15); title.FontWeight = FontWeights.SemiBold; words.Children.Add(title);
      string local = Json.Text(State, "name");
      var subtitle = Text(local == "" ? Locale.T("本机") : local + " · " + Locale.T("本机"), 12, "#65748B"); subtitle.Margin = new Thickness(0, 2, 0, 0); words.Children.Add(subtitle);
      Grid.SetColumn(words, 1); header.Children.Add(words);
      var close = QuickIconButton("close", "关闭", "#65748B", () => window.Close()); Grid.SetColumn(close, 2); header.Children.Add(close);
      return header;
    }

    FrameworkElement QuickPanelFooter(Window window) {
      var footer = new Grid { Margin = new Thickness(0, 10, 0, 0) };
      footer.ColumnDefinitions.Add(new ColumnDefinition()); footer.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto }); footer.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
      var open = LButton("打开 LanLink", () => { window.Close(); OpenMainWindow(); }); open.Style = (Style)FindResource("Primary"); open.Padding = new Thickness(12, 8, 12, 8);
      System.Windows.Automation.AutomationProperties.SetAutomationId(open, "quickPanelOpen"); footer.Children.Add(open);
      var release = QuickIconButton("return", "返回本机键鼠", "#5B6BD5", () => { window.Close(); Run(async () => { await Client.Call("release"); await Refresh(); }); });
      release.IsEnabled = State != null && Json.Text(State, "active") != ""; release.Margin = new Thickness(8, 0, 0, 0); Grid.SetColumn(release, 1); footer.Children.Add(release);
      var quit = QuickIconButton("power", "退出 LanLink", "#C04A5C", () => { window.Close(); Run(async () => { await Quit(0); }); }); quit.Margin = new Thickness(8, 0, 0, 0); Grid.SetColumn(quit, 2); footer.Children.Add(quit);
      return footer;
    }

    FrameworkElement QuickPanelEmpty(string message) {
      var box = new StackPanel { Margin = new Thickness(0, 10, 0, 14), HorizontalAlignment = HorizontalAlignment.Center };
      var badge = new Border { Width = 48, Height = 48, CornerRadius = new CornerRadius(24), Background = Brush("#ECEEFC"), Child = QuickGlyph("devices", 22, "#5B6BD5"), HorizontalAlignment = HorizontalAlignment.Center };
      box.Children.Add(badge);
      var text = LText(message, 12, "#65748B"); text.TextAlignment = TextAlignment.Center; text.MaxWidth = 280; text.Margin = new Thickness(0, 10, 0, 0); box.Children.Add(text);
      return box;
    }

    FrameworkElement QuickPanelDevice(Device d, Window window) {
      var body = new StackPanel();
      var head = new Grid(); head.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto }); head.ColumnDefinitions.Add(new ColumnDefinition()); head.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
      var icon = new Border { Width = 34, Height = 34, CornerRadius = new CornerRadius(9), Background = Brush("#ECEEFC"), Margin = new Thickness(0, 0, 10, 0), Child = DeviceGlyph(d, 18) };
      head.Children.Add(icon);
      var words = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
      var name = Text(d.Name, 14); name.FontWeight = FontWeights.SemiBold; words.Children.Add(name);
      var type = Text(DeviceType(d) + (d.Id == MasterId ? Locale.T(" · 主设备") : ""), 11, "#65748B"); type.Margin = new Thickness(0, 2, 0, 0); words.Children.Add(type);
      Grid.SetColumn(words, 1); head.Children.Add(words);
      string status = OnlineStatus(d), color = StatusColor(d);
      var badge = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
      badge.Children.Add(new Ellipse { Width = 6, Height = 6, Fill = Brush(color), Margin = new Thickness(0, 0, 5, 0), VerticalAlignment = VerticalAlignment.Center });
      badge.Children.Add(Text(Locale.T(status), 11, color));
      Grid.SetColumn(badge, 2); head.Children.Add(badge); body.Children.Add(head);
      var actions = new UniformGrid { Columns = 2, Margin = new Thickness(0, 10, 0, 0) };
      actions.Children.Add(QuickAction(Feature(d, Locale.T("桌面控制"), new[] { "input", "screen" }, () => OpenRemote(d)), "桌面控制", "default", window));
      var mouse = MouseHere(d) ? Menu(Locale.T("返回本机键鼠"), async () => { await Client.Call("release"); await Refresh(); }, true) : MouseControlAction(d);
      actions.Children.Add(QuickAction(mouse, MouseHere(d) ? "返回本机键鼠" : "切换键鼠到此设备", MouseHere(d) ? "return" : "pointer", window));
      actions.Children.Add(QuickAction(Feature(d, Locale.T("粘贴共享内容"), new[] { "input", "clipboard" }, async () => { await Client.Call("device-paste", new { id = d.Id }); NotifyTransient(Locale.T("已向 ") + d.Name + Locale.T(" 粘贴共享内容")); }), "粘贴共享内容", "paste", window));
      actions.Children.Add(QuickAction(SendFilesAction(d), "发送文件…", "file", window));
      body.Children.Add(actions);
      var card = new Border { Child = body, CornerRadius = new CornerRadius(12), Background = Brush("#FFFFFF"), BorderBrush = Brush("#DFE5EF"), BorderThickness = new Thickness(1), Padding = new Thickness(12), Margin = new Thickness(0, 0, 0, 8) };
      System.Windows.Automation.AutomationProperties.SetName(card, d.Name);
      return card;
    }

    // Reuses the device-menu actions so permissions, consent and fallbacks stay identical.
    Button QuickAction(MenuItem action, string title, string icon, Window window) {
      var button = Button("", () => { window.Close(); if (!IsLoaded) OpenMainWindow(); action.RaiseEvent(new RoutedEventArgs(MenuItem.ClickEvent)); });
      bool restricted = action.ReadLocalValue(Control.ForegroundProperty) != DependencyProperty.UnsetValue;
      var row = new StackPanel { Orientation = Orientation.Horizontal };
      row.Children.Add(QuickGlyph(icon, 15, restricted ? "#65748B" : "#5B6BD5"));
      var label = LText(title, 12, restricted ? "#65748B" : "#202C43"); label.Margin = new Thickness(7, 0, 0, 0); label.TextWrapping = TextWrapping.NoWrap; label.TextTrimming = TextTrimming.CharacterEllipsis; label.VerticalAlignment = VerticalAlignment.Center; row.Children.Add(label);
      button.Content = row; button.HorizontalContentAlignment = HorizontalAlignment.Left; button.Padding = new Thickness(9, 7, 9, 7); button.Margin = new Thickness(0, 0, 6, 6);
      button.Background = Brush("#F9FBFE"); button.IsEnabled = action.IsEnabled; button.ToolTip = action.ToolTip ?? Locale.T(title); ToolTipService.SetShowOnDisabled(button, true);
      System.Windows.Automation.AutomationProperties.SetName(button, Locale.T(title));
      return button;
    }
    void OpenMainWindow() { Show(); if (WindowState == WindowState.Minimized) WindowState = WindowState.Normal; Activate(); }

    Button QuickIconButton(string icon, string tip, string color, Action action) {
      var button = Button("", action); button.Content = QuickGlyph(icon, 17, color); button.Width = 38; button.Height = 38; button.Padding = new Thickness(0);
      button.Background = Brush("#FFFFFF"); UiLocale.Bind(button, FrameworkElement.ToolTipProperty, tip); UiLocale.Bind(button, System.Windows.Automation.AutomationProperties.NameProperty, tip);
      return button;
    }

    static FrameworkElement QuickGlyph(string name, double size, string color) {
      var glyph = WindowShell.Glyph(name, size) as Viewbox; var path = glyph == null ? null : glyph.Child as Path;
      if (path != null) path.Stroke = Brush(color);
      return glyph;
    }

    [DllImport("user32.dll")]
    static extern bool SetWindowPos(IntPtr hwnd, IntPtr after, int x, int y, int width, int height, uint flags);
    static void MoveQuickPanel(Window window, int x, int y) {
      const uint noSizeOrZOrderOrActivation = 0x0001 | 0x0004 | 0x0010;
      SetWindowPos(new WindowInteropHelper(window).Handle, IntPtr.Zero, x, y, 0, 0, noSizeOrZOrderOrActivation);
    }

    void PlaceQuickPanel(Window window, Forms.Screen screen) {
      var area = screen.WorkingArea;
      var scale = PresentationSource.FromVisual(window).CompositionTarget.TransformToDevice;
      int gap = (int)Math.Round(12 * scale.M11);
      int width = (int)Math.Ceiling(window.ActualWidth * scale.M11);
      int height = (int)Math.Ceiling(window.ActualHeight * scale.M22);
      int insetX = Math.Min(gap, Math.Max(0, area.Width - width));
      int insetY = Math.Min(gap, Math.Max(0, area.Height - height));
      int x = area.Left > screen.Bounds.Left ? area.Left + insetX : Math.Max(area.Left, area.Right - width - insetX);
      int y = area.Top > screen.Bounds.Top ? area.Top + insetY : Math.Max(area.Top, area.Bottom - height - insetY);
      MoveQuickPanel(window, x, y);
    }
    void QuickPanelGlass(Window window, Border root) {
      try {
        if (SystemParameters.HighContrast) return;
        var handle = new WindowInteropHelper(window).Handle;
        int corner = 2; QuickPanelAttribute(handle, 33, ref corner, 4);
        int darkFrame = Theme.Dark ? 1 : 0; QuickPanelAttribute(handle, 20, ref darkFrame, 4);
        int backdrop = 3;
        if (QuickPanelAttribute(handle, 38, ref backdrop, 4) == 0) {
          var margins = new Margins { Left = -1, Right = -1, Top = -1, Bottom = -1 }; QuickPanelExtend(handle, ref margins);
          HwndSource.FromHwnd(handle).CompositionTarget.BackgroundColor = Colors.Transparent; window.Background = Brushes.Transparent;
          root.Background = Brush("#8CF9FBFE");
        }
      } catch { /* Older Windows keeps the opaque panel. */ }
    }
  }
}
