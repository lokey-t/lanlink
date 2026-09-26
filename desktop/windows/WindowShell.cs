using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Shapes;
using System.Windows.Shell;
using System.Windows.Media.Effects;
using System.Windows.Threading;

namespace LanLink.Desktop {
  // A shared native window frame keeps the main window, dialogs and remote view consistent.
  public static class WindowShell {
    // Blur only the application surface behind a popup; never blur its foreground labels.
    // The popup lives in another HWND, so sampling the owner does not recurse into itself.
    public static void GlassMenu(ContextMenu menu, Window owner) {
      Action update = delegate {
        var source = owner.Content as FrameworkElement;
        var root = menu.Template.FindName("GlassRoot", menu) as Grid;
        var sample = menu.Template.FindName("GlassSample", menu) as Rectangle;
        var tint = menu.Template.FindName("GlassTint", menu) as Border;
        if (source == null || root == null || sample == null || tint == null || menu.ActualWidth <= 0 || menu.ActualHeight <= 0) return;
        root.Clip = new RectangleGeometry(new Rect(0, 0, menu.ActualWidth, menu.ActualHeight), 16, 16);
        if (SystemParameters.HighContrast) { sample.Fill = null; tint.Background = Brushes.White; return; }
        Point origin = source.PointFromScreen(menu.PointToScreen(new Point(0, 0)));
        sample.Fill = new VisualBrush(source) { ViewboxUnits = BrushMappingMode.Absolute, Viewbox = new Rect(origin.X, origin.Y, menu.ActualWidth, menu.ActualHeight), Stretch = Stretch.Fill, AlignmentX = AlignmentX.Left, AlignmentY = AlignmentY.Top };
        sample.Effect = new BlurEffect { KernelType = KernelType.Gaussian, Radius = 18, RenderingBias = RenderingBias.Performance };
        tint.Background = MainWindow.Brush("#C7F8FAFF");
      };
      menu.Opened += delegate { menu.Dispatcher.BeginInvoke(update, DispatcherPriority.Loaded); };
      menu.SizeChanged += delegate { if (menu.IsOpen) menu.Dispatcher.BeginInvoke(update, DispatcherPriority.Loaded); };
      menu.Closed += delegate { var sample = menu.Template.FindName("GlassSample", menu) as Rectangle; if (sample != null) sample.Fill = null; };
    }
    public static FrameworkElement Glyph(string name, double size = 20) {
      string geometry;
      switch (name) {
        case "brand": geometry = "M4,3 L14,3 Q16,3 16,5 L16,15 L6,15 Q4,15 4,13 Z M9,9 L20,9 L20,21 L9,21 Z"; break;
        case "settings": geometry = "M9,3 L15,3 L16,6 L19,7 L21,10 L19,13 L19,17 L16,19 L13,19 L10,21 L7,19 L6,16 L3,14 L3,10 L6,8 L7,5 Z M16,12 A4,4 0 1 1 8,12 A4,4 0 1 1 16,12"; break;
        case "account": geometry = "M16,7 A4,4 0 1 1 8,7 A4,4 0 1 1 16,7 M4,21 L4,19 C4,12 20,12 20,19 L20,21"; break;
        case "permissions": geometry = "M12,2 L21,6 L20,14 Q18,20 12,23 Q6,20 4,14 L3,6 Z M7,12 L11,16 L17,9"; break;
        case "adb": geometry = "M8,3 L16,3 L16,18 L8,18 Z M10,21 L14,21 M12,18 L12,21 M10,6 L14,6"; break;
        case "plus": geometry = "M12,5 L12,19 M5,12 L19,12"; break;
        case "close": geometry = "M6,6 L18,18 M6,18 L18,6"; break;
        case "minimize": geometry = "M5,12 L19,12"; break;
        case "maximize": geometry = "M6,6 L18,6 L18,18 L6,18 Z"; break;
        case "return": geometry = "M9,7 L4,12 L9,17 M4,12 L15,12 C22,12 22,20 15,20"; break;
        case "file": geometry = "M6,3 L14,3 L19,8 L19,21 L6,21 Z M14,3 L14,8 L19,8"; break;
        case "paste": geometry = "M8,6 L5,6 L5,21 L19,21 L19,6 L16,6 M9,3 L15,3 L15,8 L9,8 Z"; break;
        case "power": geometry = "M12,2 L12,12 M7,5 A8,8 0 1 0 17,5"; break;
        case "layout": geometry = "M3,4 L10,4 L10,11 L3,11 Z M14,4 L21,4 L21,11 L14,11 Z M3,15 L10,15 L10,22 L3,22 Z M14,15 L21,15 L21,22 L14,22 Z"; break;
        case "pointer": geometry = "M6,3 L6,20 L11,15 L15,21 L18,19 L14,13 L21,12 Z"; break;
        default: geometry = "M3,4 L21,4 L21,17 L3,17 Z M12,17 L12,21 M7,21 L17,21"; break;
      }
      return new Viewbox { Width = size, Height = size, Child = new System.Windows.Shapes.Path { Data = Geometry.Parse(geometry), Stroke = MainWindow.Brush("#637596"), StrokeThickness = 1.55, StrokeLineJoin = PenLineJoin.Round, StrokeStartLineCap = PenLineCap.Round, StrokeEndLineCap = PenLineCap.Round, Width = 24, Height = 24 } };
    }
    public static object Label(string text, string icon) {
      var row = new StackPanel { Orientation = Orientation.Horizontal };
      var glyph = Glyph(icon); glyph.Margin = new Thickness(0, 0, 12, 0); row.Children.Add(glyph);
      row.Children.Add(MainWindow.Text(text, 14)); return row;
    }
    public static Border Wrap(Window window, UIElement content, bool resize = true, bool compact = false) {
      window.WindowStyle = WindowStyle.None;
      window.ResizeMode = resize ? ResizeMode.CanResize : ResizeMode.NoResize;
      window.Background = MainWindow.Brush("#EFF3FA");
      window.UseLayoutRounding = true; window.SnapsToDevicePixels = true;
      TextOptions.SetTextFormattingMode(window, TextFormattingMode.Display);
      WindowChrome.SetWindowChrome(window, new WindowChrome { CaptionHeight = 0, ResizeBorderThickness = new Thickness(resize ? 6 : 0), GlassFrameThickness = new Thickness(0), CornerRadius = new CornerRadius(20), UseAeroCaptionButtons = false });
      var frame = new Border { CornerRadius = new CornerRadius(20), BorderThickness = new Thickness(1), BorderBrush = MainWindow.Brush("#E0E6F0"), Background = MainWindow.Brush("#F1F5FB") };
      var root = new Grid(); root.RowDefinitions.Add(new RowDefinition { Height = new GridLength(compact ? 38 : 52) }); root.RowDefinitions.Add(new RowDefinition()); frame.Child = root;
      var caption = new Grid { Background = Brushes.Transparent, Margin = new Thickness(18, 0, 10, 0) };
      caption.ColumnDefinitions.Add(new ColumnDefinition()); caption.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto }); root.Children.Add(caption);
      var label = new DockPanel { VerticalAlignment = VerticalAlignment.Center };
      if (!compact) { var brand = Glyph("brand", 23); brand.Margin = new Thickness(0, 0, 10, 0); DockPanel.SetDock(brand, Dock.Left); label.Children.Add(brand); } label.Children.Add(MainWindow.Text(window.Title, compact ? 12 : 14)); caption.Children.Add(label);
      caption.MouseLeftButtonDown += delegate(object sender, MouseButtonEventArgs e) {
        if (e.ClickCount == 2 && resize) window.WindowState = window.WindowState == WindowState.Maximized ? WindowState.Normal : WindowState.Maximized;
        else if (e.LeftButton == MouseButtonState.Pressed) window.DragMove();
      };
      caption.MouseRightButtonUp += delegate(object sender, MouseButtonEventArgs e) { SystemCommands.ShowSystemMenu(window, window.PointToScreen(e.GetPosition(window))); };
      var controls = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center }; Grid.SetColumn(controls, 1); caption.Children.Add(controls);
      Action<string, string, Action> add = (icon, tip, action) => {
        var button = MainWindow.Button("", action); button.Content = Glyph(icon, 18); button.Width = 36; button.Height = 32; button.Padding = new Thickness(0); button.Margin = new Thickness(2, 0, 2, 0); button.Background = Brushes.Transparent; button.BorderThickness = new Thickness(0); button.ToolTip = tip;
        System.Windows.Automation.AutomationProperties.SetName(button, tip); controls.Children.Add(button);
      };
      if (resize) { add("minimize", "最小化", () => window.WindowState = WindowState.Minimized); add("maximize", "最大化 / 还原", () => window.WindowState = window.WindowState == WindowState.Maximized ? WindowState.Normal : WindowState.Maximized); }
      add("close", "关闭", window.Close);
      Grid.SetRow(content, 1); root.Children.Add(content);
      frame.SizeChanged += delegate { frame.Clip = new RectangleGeometry(new Rect(0, 0, frame.ActualWidth, frame.ActualHeight), window.WindowState == WindowState.Maximized ? 0 : 20, window.WindowState == WindowState.Maximized ? 0 : 20); };
      window.StateChanged += delegate { frame.Margin = new Thickness(window.WindowState == WindowState.Maximized ? 7 : 0); frame.CornerRadius = new CornerRadius(window.WindowState == WindowState.Maximized ? 0 : 20); };
      window.Content = frame; return frame;
    }
  }
}
