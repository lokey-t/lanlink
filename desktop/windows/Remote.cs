using System;
using System.Collections.Generic;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Interop;
using System.Runtime.InteropServices;

namespace LanLink.Desktop {
  public sealed class RemoteWindow : Window {
    readonly MainWindow main; readonly Device device;
    readonly Image image = new Image { Stretch = Stretch.Uniform, Focusable = true };
    readonly TextBlock status = MainWindow.Text("正在获取屏幕…", 12);
    readonly TextBlock modeStat=MainWindow.Text("仅查看",11,"#D7DCE5"),codecStat=MainWindow.Text("--",11,"#A9B2C2"),fpsStat=MainWindow.Text("-- fps",11,"#A9B2C2"),bitrateStat=MainWindow.Text("-- Mbps",11,"#A9B2C2");
    readonly System.Diagnostics.Stopwatch frameClock=System.Diagnostics.Stopwatch.StartNew();
    readonly Queue<double> displayedFrames=new Queue<double>();bool picturePending;
    readonly System.Windows.Threading.DispatcherTimer fpsTimer=new System.Windows.Threading.DispatcherTimer{Interval=TimeSpan.FromMilliseconds(250)};
    void PicturePresented(object sender,EventArgs args){if(picturePending&&IsVisible&&WindowState!=WindowState.Minimized){picturePending=false;displayedFrames.Enqueue(frameClock.Elapsed.TotalMilliseconds);}}
    void UpdateFPS(){double now=frameClock.Elapsed.TotalMilliseconds;while(displayedFrames.Count>0&&displayedFrames.Peek()<=now-1000)displayedFrames.Dequeue();fpsStat.Text=displayedFrames.Count+" fps";}
    bool restoredGeometry, loadingGeometry=true;
    Grid sidebar;Button navigationExpand;Border sidebarClip;FrameworkElement remoteCaption;
    double sidebarExtent, sidebarBaseWidth, sidebarFrom;readonly System.Diagnostics.Stopwatch sidebarClock=new System.Diagnostics.Stopwatch();
    Action sidebarAnimationProbe;
    readonly System.Windows.Threading.DispatcherTimer sidebarTimer=new System.Windows.Threading.DispatcherTimer(System.Windows.Threading.DispatcherPriority.Render){Interval=TimeSpan.FromMilliseconds(16)};
    public bool SidebarCollapsed {get;private set;}
    public double SavedWindowWidth {get{return Width-sidebarExtent+(SidebarCollapsed?0:64);}}
    void ApplySidebarExtent(double extent){sidebarExtent=extent;sidebarClip.Width=extent;remoteCaption.Margin=new Thickness(18,0,10+extent,0);MinWidth=(device.Platform=="android"?270:320)+extent;Width=sidebarBaseWidth+extent;}
    void FinishSidebarAnimation(){if(!sidebarTimer.IsEnabled)return;sidebarTimer.Stop();ApplySidebarExtent(SidebarCollapsed?0:64);}
    void ToggleSidebar() {
      if(sidebar==null)return;
      SidebarCollapsed=!SidebarCollapsed;
      sidebar.IsHitTestVisible=!SidebarCollapsed;sidebar.IsEnabled=!SidebarCollapsed;
      sidebarTimer.Stop();sidebarBaseWidth=Width-sidebarExtent;sidebarFrom=sidebarExtent;
      if(loadingGeometry)ApplySidebarExtent(SidebarCollapsed?0:64);else{sidebarClock.Restart();sidebarTimer.Start();}
      navigationExpand.ToolTip=SidebarCollapsed?"展开侧栏":"收起侧栏";
      System.Windows.Automation.AutomationProperties.SetName(navigationExpand,(string)navigationExpand.ToolTip);
      navigationExpand.Content=WindowShell.Glyph(SidebarCollapsed?"expand-right":"collapse-left",18);((System.Windows.Shapes.Path)((Viewbox)navigationExpand.Content).Child).Stroke=MainWindow.Brush("#C7CDD8");
    }
    bool closed, controlling, moveBusy, wantsControl = true, fitted, fallbackAccepted, showingModeDialog, conflict; int sessionVersion; object pendingMove; Task inputs = Task.FromResult(0); DateTime retryAt;
    readonly double openedAt = (DateTime.UtcNow-new DateTime(1970,1,1)).TotalMilliseconds;
    public bool ControlRequested { get; private set; }
    public int NavigationKeyCount { get; private set; }
    public bool HasRightToolbar { get; private set; }
    public string DeviceId { get { return device.Id; } }
    bool resumeOnActivate;
    readonly HashSet<string> forwardedKeys = new HashSet<string>();
    string inputFailure;
    Border navigationHotspot, screenFrame; StackPanel navigationKeys;
    bool preparing, prepared, freshRequest=true; double screenRatio = .5; DateTime acquiredAt;
    [StructLayout(LayoutKind.Sequential)] struct SizingRect { public int Left, Top, Right, Bottom; }
    [DllImport("user32.dll")] static extern IntPtr SendMessage(IntPtr window, int message, IntPtr wParam, IntPtr lParam);
    internal void ShowNavigationPreview() { navigationHotspot.RaiseEvent(new MouseEventArgs(Mouse.PrimaryDevice,0) {RoutedEvent=Mouse.MouseEnterEvent}); }
    internal void ShowMetricsPreview(){modeStat.Text="正在控制";codecStat.Text="H.264";fpsStat.Text="60 fps";bitrateStat.Text="8.0 Mbps";}
    internal void VerifyFooter(){UpdateLayout();if(Math.Abs(navigationKeys.TranslatePoint(new Point(navigationKeys.ActualWidth/2,0),screenFrame).X-screenFrame.ActualWidth/2)>1)throw new Exception("Navigation must remain centered on the phone screen");if(navigationExpand.TranslatePoint(new Point(),navigationHotspot).Y<0 || Math.Abs(navigationExpand.TranslatePoint(new Point(navigationExpand.ActualWidth,0),navigationHotspot).X-navigationHotspot.ActualWidth)>1)throw new Exception("Sidebar toggle must stay at the bottom right");}
    double[] LayoutSnapshot(){UpdateLayout();var picture=screenFrame.PointToScreen(new Point());var keys=navigationKeys.PointToScreen(new Point());var toggle=navigationExpand.PointToScreen(new Point());var caption=remoteCaption.PointToScreen(new Point(remoteCaption.ActualWidth,0));return new[]{Left,Top,Width-sidebarExtent,Height,picture.X,picture.Y,screenFrame.ActualWidth,screenFrame.ActualHeight,keys.X,keys.Y,toggle.X,toggle.Y,caption.X,caption.Y};}
    void VerifyStableLayout(double[] before){var after=LayoutSnapshot();for(int i=0;i<before.Length;i++)if(Math.Abs(before[i]-after[i])>1)throw new Exception("Sidebar moved the window, picture or navigation: "+i+" before="+before[i]+" after="+after[i]);VerifyFooter();if(sidebarClip.PointToScreen(new Point()).X<screenFrame.PointToScreen(new Point(screenFrame.ActualWidth,0)).X-1)throw new Exception("Sidebar must never overlap the picture");}
    internal void CollapseForTest(){var before=LayoutSnapshot();if(!SidebarCollapsed)ToggleSidebar();if(sidebar.IsHitTestVisible || navigationExpand.Visibility!=Visibility.Visible)throw new Exception("Hidden sidebar must not receive input");VerifyStableLayout(before);}
    internal void ExpandForTest(){var before=LayoutSnapshot();navigationExpand.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));if(SidebarCollapsed || !sidebar.IsHitTestVisible)throw new Exception("Expanded sidebar must receive input");VerifyStableLayout(before);}
    internal async Task WaitSidebarForTest(){for(int i=0;i<150 && sidebarTimer.IsEnabled;i++)await Task.Delay(20);if(sidebarTimer.IsEnabled)throw new Exception("Sidebar animation timed out");UpdateLayout();}
    internal async Task VerifySidebarAnimation(){var before=LayoutSnapshot();if(SidebarCollapsed)ExpandForTest();await WaitSidebarForTest();var middle=new TaskCompletionSource<bool>();sidebarAnimationProbe=delegate{if(sidebarExtent>0 && sidebarExtent<64){try{VerifyStableLayout(before);middle.TrySetResult(true);}catch(Exception ex){middle.TrySetException(ex);}}};CollapseForTest();if(await Task.WhenAny(middle.Task,Task.Delay(3000))!=middle.Task)throw new Exception("Sidebar must expose intermediate widths");await middle.Task;sidebarAnimationProbe=null;await Task.Delay(1);ExpandForTest();await WaitSidebarForTest();if(Math.Abs(sidebarExtent-64)>.1)throw new Exception("Interrupted animation must reach expanded position");VerifyStableLayout(before);CollapseForTest();await WaitSidebarForTest();if(Math.Abs(sidebarExtent)>.1)throw new Exception("Sidebar must retract completely");VerifyStableLayout(before);}
    internal void VerifyPhoneSizing() {
      UpdateLayout();
      // Exercise real picture layout: an empty viewer does not expose Image's desired size.
      var sample = BitmapSource.Create(720,1600,96,144,PixelFormats.Bgr32,null,new byte[720*1600*4],720*4);sample.Freeze();
      image.Source=sample; FitScreen(720,1600); UpdateLayout();
      if(Math.Abs(screenFrame.ActualWidth/screenFrame.ActualHeight-720.0/1600)>.001 || Math.Abs(image.ActualHeight-screenFrame.ActualHeight)>1 || Math.Abs(image.ActualWidth-screenFrame.ActualWidth)>1) throw new Exception("Phone frame must fill the picture without letterboxing: frame="+screenFrame.ActualWidth+"x"+screenFrame.ActualHeight+" image="+image.ActualWidth+"x"+image.ActualHeight);
      if(navigationHotspot==null || navigationKeys.Opacity!=1 || navigationHotspot.TranslatePoint(new Point(),screenFrame).Y < screenFrame.ActualHeight-1) throw new Exception("Phone navigation must remain visible below the screen");
      if(navigationHotspot.ActualHeight != 48 || navigationKeys.Children.Count != 3 || ((Button)navigationKeys.Children[0]).ActualWidth < 64 || modeStat.ActualWidth>64) throw new Exception("Navigation and four-line metrics must fit their own areas");
      var scale=PresentationSource.FromVisual(this).CompositionTarget.TransformToDevice;
      double extraW=(ActualWidth-screenFrame.ActualWidth)*scale.M11, extraH=(ActualHeight-screenFrame.ActualHeight)*scale.M22;
      IntPtr memory=Marshal.AllocHGlobal(Marshal.SizeOf(typeof(SizingRect)));
      try { for(int edge=1;edge<=8;edge++) {
        Marshal.StructureToPtr(new SizingRect {Left=50,Top=50,Right=750,Bottom=1050},memory,false);
        SendMessage(new WindowInteropHelper(this).Handle,0x0214,new IntPtr(edge),memory);
        var r=(SizingRect)Marshal.PtrToStructure(memory,typeof(SizingRect));
        if(Math.Abs((r.Right-r.Left-extraW)/(r.Bottom-r.Top-extraH)-screenRatio)>.003) throw new Exception("Phone resize edge="+edge+" ratio="+((r.Right-r.Left-extraW)/(r.Bottom-r.Top-extraH))+" expected="+screenRatio+" extra="+extraW+","+extraH);
      } } finally {Marshal.FreeHGlobal(memory);}
      navigationHotspot.RaiseEvent(new MouseEventArgs(Mouse.PrimaryDevice,0) {RoutedEvent=Mouse.MouseEnterEvent});
      if(navigationKeys.Opacity!=1) throw new Exception("Phone navigation must stay visible on hover");
      navigationHotspot.RaiseEvent(new MouseEventArgs(Mouse.PrimaryDevice,0) {RoutedEvent=Mouse.MouseLeaveEvent});
      if(navigationKeys.Opacity!=1)throw new Exception("Phone navigation must stay visible without hover");
    }
    IntPtr ConstrainSize(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled) {
      if (msg != 0x0214 || device.Platform != "android" || screenFrame == null || screenFrame.ActualWidth < 1) return IntPtr.Zero;
      var r = (SizingRect)Marshal.PtrToStructure(lParam, typeof(SizingRect));
      var scale = PresentationSource.FromVisual(this).CompositionTarget.TransformToDevice;
      double extraW = (ActualWidth - screenFrame.ActualWidth) * scale.M11, extraH = (ActualHeight - screenFrame.ActualHeight) * scale.M22;
      int edge = wParam.ToInt32();
      double w = r.Right-r.Left, h = r.Bottom-r.Top;
      if (edge == 3 || edge == 6) w = (h-extraH)*screenRatio+extraW; else h = (w-extraW)/screenRatio+extraH;
      if (w < MinWidth*scale.M11) { w=MinWidth*scale.M11; h=(w-extraW)/screenRatio+extraH; }
      if (h < MinHeight*scale.M22) { h=MinHeight*scale.M22; w=(h-extraH)*screenRatio+extraW; }
      if (edge == 1 || edge == 4 || edge == 7) r.Left=r.Right-(int)Math.Round(w); else r.Right=r.Left+(int)Math.Round(w);
      if (edge == 3 || edge == 4 || edge == 5) r.Top=r.Bottom-(int)Math.Round(h); else r.Bottom=r.Top+(int)Math.Round(h);
      Marshal.StructureToPtr(r,lParam,false); handled=true; return new IntPtr(1);
    }
    static bool Terminal(string error) { return error.StartsWith("CONSENT_DENIED:") || error.StartsWith("CONTROL_ENDED:") || (error.StartsWith("CONTROL_CONFLICT:") && !error.Contains("正在切换控制") && !error.Contains("正在连接该设备的画面")); }
    public RemoteWindow(MainWindow owner, Device d) {
      CompositionTarget.Rendering+=PicturePresented;fpsTimer.Tick+=delegate{UpdateFPS();};fpsTimer.Start();Closed+=delegate{fpsTimer.Stop();CompositionTarget.Rendering-=PicturePresented;};
      SidebarCollapsed=true;main = owner; device = d; Owner = owner; Title = "桌面控制 · " + d.Name; MinWidth = d.Platform=="android"?270:320; MinHeight = 360; Resources = owner.Resources; FontFamily = owner.FontFamily; Background = MainWindow.Brush("#F5F8FE"); WindowStartupLocation = WindowStartupLocation.CenterScreen; FitScreen(d.Width, d.Height);
      bool phone = device.Platform == "android";
      // The phone viewport is already constrained to the encoded pixel aspect.
      // Ignore JPEG DPI/SAR metadata, which otherwise adds false letterboxing.
      if(phone) image.Stretch=Stretch.Fill;
      InputMethod.SetIsInputMethodEnabled(image,false); // Physical keys are composed by the phone's IME.
      var shellRoot=new Grid{Margin=new Thickness(4)};shellRoot.ColumnDefinitions.Add(new ColumnDefinition());shellRoot.ColumnDefinitions.Add(new ColumnDefinition{Width=GridLength.Auto});var root=new DockPanel();shellRoot.Children.Add(root);
      var dock = new Grid{ClipToBounds=true}; WindowShell.Wrap(this, shellRoot, true, true,true,null,caption=>remoteCaption=caption);
      var footer=new Grid();
      var hotspot=new Border{Height=phone?48:40,Background=Brushes.Black,Child=footer};navigationHotspot=hotspot;DockPanel.SetDock(hotspot,Dock.Bottom);root.Children.Add(hotspot);root.Children.Add(dock);
      navigationExpand=WindowShell.CaptionButton("collapse-left","收起侧栏",ToggleSidebar,true);navigationExpand.Width=28;navigationExpand.Height=40;navigationExpand.Margin=new Thickness(0);navigationExpand.Padding=new Thickness(0);navigationExpand.Background=Brushes.Transparent;navigationExpand.HorizontalAlignment=HorizontalAlignment.Right;navigationExpand.VerticalAlignment=VerticalAlignment.Center;Grid.SetColumnSpan(navigationExpand,2);Panel.SetZIndex(navigationExpand,1);footer.Children.Add(navigationExpand);
      var stats=new StackPanel{Width=58,HorizontalAlignment=HorizontalAlignment.Right,VerticalAlignment=VerticalAlignment.Bottom,Margin=new Thickness(3,8,3,6),ClipToBounds=true};
      foreach(var line in new[]{modeStat,codecStat,fpsStat,bitrateStat}){line.FontSize=10;line.TextAlignment=TextAlignment.Right;line.TextWrapping=TextWrapping.NoWrap;line.TextTrimming=TextTrimming.CharacterEllipsis;line.Margin=new Thickness(0,2,0,2);stats.Children.Add(line);}
      var statusProperty=System.ComponentModel.DependencyPropertyDescriptor.FromProperty(TextBlock.TextProperty,typeof(TextBlock));EventHandler statusChanged=delegate{modeStat.Text=controlling?"正在控制":"仅查看";stats.ToolTip=status.Text;};statusProperty.AddValueChanged(status,statusChanged);Closed+=delegate{statusProperty.RemoveValueChanged(status,statusChanged);};
      var bar = new StackPanel { Orientation = Orientation.Vertical, HorizontalAlignment=HorizontalAlignment.Center, Margin = new Thickness(4,0,0,0) };HasRightToolbar = true;
      sidebar=new Grid{Width=64,HorizontalAlignment=HorizontalAlignment.Right,Background=Brushes.Black};Panel.SetZIndex(sidebar,1);sidebar.RowDefinitions.Add(new RowDefinition{Height=GridLength.Auto});sidebar.RowDefinitions.Add(new RowDefinition());sidebar.Children.Add(bar);Grid.SetRow(stats,1);sidebar.Children.Add(stats);sidebar.IsHitTestVisible=false;sidebar.IsEnabled=false;sidebarClip=new Border{Width=0,ClipToBounds=true,Child=sidebar,Margin=new Thickness(0,0,0,phone?48:40)};sidebar.HorizontalAlignment=HorizontalAlignment.Left;Grid.SetColumn(sidebarClip,1);shellRoot.Children.Add(sidebarClip);
      AddTool(bar, phone, "开始", "重新控制", () => Run(RestartControl));
      AddTool(bar, phone, "暂停", "停止键鼠控制，保留画面", () => Run(Release));
      AddTool(bar, phone, "粘贴", "粘贴共享剪贴板", () => Run(async () => { if (!controlling) throw new Exception("请先开始控制"); await main.Client.Call("paste", new { id = device.Id }); }));
      AddTool(bar, phone, "文字", "输入文本", TextDialog);
      if (phone) AddTool(bar, true, "ADB", "从电脑为手机启用 ADB 增强控制", () => main.Run(async()=>{await main.Client.Call("adb-guide-open",new{id=device.Id});main.Show();main.Activate();}));
      var screen = new DockPanel();
      if (phone) {
        var keys = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Center }; navigationKeys=keys;
        footer.Children.Add(keys);
        foreach (var key in new[] { new[] { "back", "‹", "返回" }, new[] { "home", "○", "主页" }, new[] { "recents", "□", "最近任务" } }) {
          string action = key[0]; var button=MainWindow.Button(key[1],()=>Run(async()=>{await Send(new {kind="navigation",action=action});image.Focus();}));
          var icon = new System.Windows.Shapes.Path { Width=26, Height=26, Stretch=Stretch.Uniform, Stroke=MainWindow.Brush("#C7CDD8"), StrokeThickness=2.4, StrokeLineJoin=PenLineJoin.Round, StrokeStartLineCap=PenLineCap.Round, StrokeEndLineCap=PenLineCap.Round };
          icon.Data = action=="back" ? Geometry.Parse("M 20,2 L 6,14 L 20,26") : action=="home" ? (Geometry)new EllipseGeometry(new Point(14,14),11,11) : new RectangleGeometry(new Rect(3,3,22,22),3,3);
          button.Content=icon;button.Width=64;button.Height=46;button.Padding=new Thickness(0);button.Margin=new Thickness(2,0,2,0);button.Background=Brushes.Transparent;button.BorderThickness=new Thickness(0);button.ToolTip=key[2];
          System.Windows.Automation.AutomationProperties.SetName(button,key[2]);keys.Children.Add(button);NavigationKeyCount++;
        }
        SourceInitialized += delegate { HwndSource.FromHwnd(new WindowInteropHelper(this).Handle).AddHook(ConstrainSize); };
        StateChanged += delegate { if (WindowState == WindowState.Maximized) { WindowState = WindowState.Normal; FitScreen(screenRatio*1000,1000); } };
      }
      var frame = new Border { Background = Brushes.Black, CornerRadius = new CornerRadius(phone ? 6 : 12), Child = image, ClipToBounds = true }; screenFrame=frame; screen.Children.Add(frame); dock.Children.Add(screen);
      sidebarTimer.Tick+=delegate{double t=Math.Min(1,sidebarClock.Elapsed.TotalMilliseconds/220);double eased=t*t*(3-2*t);ApplySidebarExtent(Math.Round(sidebarFrom+((SidebarCollapsed?0:64)-sidebarFrom)*eased));if(sidebarAnimationProbe!=null)sidebarAnimationProbe();if(t>=1){sidebarTimer.Stop();WindowSize.SaveRemote(this,WindowSize.RemoteFile(MainWindow.DataDir,device.Id));}};
      Closing+=delegate{FinishSidebarAnimation();};
      Loaded += async delegate { UpdateLayout();FitScreen(d.Width,d.Height);string file=WindowSize.RemoteFile(MainWindow.DataDir,device.Id);if(!WindowSize.RemoteCollapsed(file))ToggleSidebar();restoredGeometry=WindowSize.RestoreRemote(this,file);loadingGeometry=false;WindowSize.AttachRemote(this,file); await Frames(); };
      Closed += async delegate { closed = true; try { await main.Client.Call("adb-guide-cancel",new {id=device.Id}); await main.Client.Call("access-cancel",new {id=device.Id}); } catch {} try { await main.Client.Call("stream-close", new { id = device.Id }); } catch { } await Release(); };
      Deactivated += async delegate { if (controlling && !showingModeDialog) { bool resume=wantsControl;await Release();resumeOnActivate=resume;if(IsActive)await ResumeAfterFocus(); } };
      Activated += async delegate { await ResumeAfterFocus(); };
      image.MouseMove += delegate(object sender, MouseEventArgs e) { if (controlling) { pendingMove = Coordinates(e.GetPosition(image)); FlushMove(); } };
      image.MouseDown += async delegate(object sender, MouseButtonEventArgs e) { if (!controlling) return; var position = Coordinates(e.GetPosition(image)); if (position == null) return; e.Handled = true; image.Focus(); image.CaptureMouse(); await Send(position); await Send(new { kind = "button", button = MouseButton(e.ChangedButton), down = true }); };
      image.MouseUp += async delegate(object sender, MouseButtonEventArgs e) { if (!controlling) return; e.Handled = true; await Send(new { kind = "button", button = MouseButton(e.ChangedButton), down = false }); image.ReleaseMouseCapture(); };
      image.MouseWheel += async delegate(object sender, MouseWheelEventArgs e) { if (!controlling) return; e.Handled = true; await Send(new { kind = "wheel", delta = Math.Max(-2400, Math.Min(2400, e.Delta)) }); };
      image.PreviewKeyDown += async delegate(object sender, KeyEventArgs e) {
        if (!controlling) return; e.Handled = true; var key = PhysicalKey(e);
        if (key == Key.V && (Keyboard.Modifiers & (ModifierKeys.Control | ModifierKeys.Windows)) != 0) { if (!e.IsRepeat) Run(async () => { await main.Client.Call("paste", new { id = device.Id }); }); return; }
        string code = KeyCode(key); if (code != null) {forwardedKeys.Add(code);await Send(new { kind = "key", code = code, down = true });}
      };
      image.PreviewKeyUp += async delegate(object sender, KeyEventArgs e) { e.Handled = true; string code = KeyCode(PhysicalKey(e)); if (code != null && forwardedKeys.Remove(code)) await Send(new { kind = "key", code = code, down = false }); };
      image.LostKeyboardFocus += async delegate { var keys=new List<string>(forwardedKeys);forwardedKeys.Clear();foreach(var code in keys)await Send(new {kind="key",code=code,down=false}); };
    }
    static Key PhysicalKey(KeyEventArgs e) { return e.Key==Key.System?e.SystemKey:e.Key==Key.ImeProcessed?e.ImeProcessedKey:e.Key==Key.DeadCharProcessed?e.DeadCharProcessedKey:e.Key; }
    void AddTool(StackPanel panel, bool compact, string label, string tooltip, Action action) {
      var button = MainWindow.Button(compact ? label : tooltip == "输入文本" ? tooltip : label == "开始" ? "重新控制" : label == "暂停" ? "停止控制" : "粘贴", action);
      button.ToolTip = tooltip; button.Padding = compact ? new Thickness(0) : new Thickness(14, 8, 14, 8); button.Margin = compact ? new Thickness(0, 0, 0, 4) : new Thickness(0, 0, 8, 0);
      button.Foreground=MainWindow.Brush("#CDD3DE");button.Background=MainWindow.Brush("#171A20");
      if (compact) { button.Width = 44; button.Height = 40; button.FontSize = label.Length == 1 ? 23 : 11; button.Background = Brushes.Transparent; button.BorderThickness = new Thickness(0); }
      System.Windows.Automation.AutomationProperties.SetName(button, tooltip); panel.Children.Add(button);
    }
    async Task ResumeAfterFocus() { if(!resumeOnActivate||closed||conflict)return;resumeOnActivate=false;wantsControl=true;retryAt=DateTime.MinValue;await BeginControl(); }
    async Task RestartControl() { resumeOnActivate=false;freshRequest=true;wantsControl = true; ControlRequested = false; fallbackAccepted = false; conflict = false; retryAt = DateTime.MinValue; await BeginControl(); }
    public void ResumeFallback(bool accepted) { controlling = false; fallbackAccepted = accepted; wantsControl = accepted; ControlRequested = false; retryAt = DateTime.MinValue; }
    public void CancelAdbGuide() { wantsControl=false; prepared=false; controlling=false; resumeOnActivate=false; sessionVersion++; status.Text="已取消连接 · 点击重新控制可再次连接"; }
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
      screenRatio = width/height;
      var monitor=System.Windows.Forms.Screen.FromHandle(new WindowInteropHelper(IsLoaded?(Window)this:main).Handle);
      var source=PresentationSource.FromVisual(IsLoaded?(System.Windows.Media.Visual)this:main);var dpi=source==null?Matrix.Identity:source.CompositionTarget.TransformFromDevice;
      var work=monitor.WorkingArea;var area=new Rect(work.Left*dpi.M11,work.Top*dpi.M22,work.Width*dpi.M11,work.Height*dpi.M22);
      double defaultExtent=.8*Math.Min(monitor.Bounds.Width*dpi.M11,monitor.Bounds.Height*dpi.M22);
      bool phone = device.Platform == "android"; double chrome = phone ? 96 : 88, horizontal = 10;
      if(screenFrame!=null && screenFrame.ActualWidth>0) {horizontal=ActualWidth-screenFrame.ActualWidth;chrome=ActualHeight-screenFrame.ActualHeight;}
      double desiredHeight=(restoredGeometry||fitted)?Height-chrome:Math.Min(defaultExtent,area.Height-24)-chrome;
      double widthLimit=(restoredGeometry||fitted)?area.Width-24:Math.Min(area.Width-24,defaultExtent);
      double scale = Math.Min((widthLimit-horizontal) / width, Math.Min(area.Height-chrome-24,desiredHeight) / height);
      if(phone) scale=Math.Max(scale,Math.Max((MinWidth-horizontal)/width,(MinHeight-chrome)/height));
      Width = Math.Max(MinWidth, width * scale + horizontal); Height = Math.Max(320, height * scale + chrome);
    }
    async Task BeginControl() {
      if (closed || preparing || !inputs.IsCompleted || controlling || !wantsControl || main.HandlingFallback || DateTime.UtcNow < retryAt) return;
      if (device.Platform == "android" && main.InputMode(device.Id) == "accessibility-limited" && !fallbackAccepted) {
        showingModeDialog = true; try { fallbackAccepted = main.ConfirmInputMode(device, this); } finally { showingModeDialog = false; }
        if (!fallbackAccepted) { wantsControl = false; status.Text = "备用控制未启用 · 可从右侧 ADB 开启增强控制"; return; }
      }
      int version = sessionVersion; retryAt = DateTime.UtcNow.AddMilliseconds(500);
      bool fresh = freshRequest; ControlRequested = true;
      preparing=true;
      try { await main.Client.Call("control", new { id = device.Id, capture = false, screen = true, newSession = fresh, fallbackAccepted = fallbackAccepted }); if (closed || !wantsControl || version != sessionVersion) { await main.Client.Call("release"); return; } freshRequest=false; controlling = true; acquiredAt=DateTime.UtcNow; prepared=true; inputFailure=null; status.Text = "正在控制 · 点击画面后输入"; image.Focus(); }
      catch (Exception ex) { if(ex.Message.StartsWith("CONSENT_") || ex.Message.StartsWith("PERMISSION_")) freshRequest=false; prepared=false; status.Text = MainWindow.DisplayError(ex.Message); if(!Terminal(ex.Message) && !ex.Message.StartsWith("CONSENT_") && !ex.Message.StartsWith("PERMISSION_") && !ex.Message.StartsWith("ADB_SETUP_REQUIRED:")) status.Text="正在重新连接 · "+status.Text; if (Terminal(ex.Message)) { conflict = true; wantsControl = false; } }
      finally { preparing=false; }
    }
    async Task Frames() {
      bool streaming = false, legacy = false, jpegOnly = false, h264Only = false;
      while (!closed) {
        var ended=Json.Get(main.State,"controlEnded"); if(Json.Text(ended,"id")==device.Id && Json.Number(ended,"at",0)>=openedAt) { main.Notify(string.IsNullOrEmpty(Json.Text(ended,"message"))?"对方已结束控制":Json.Text(ended,"message")); Close(); break; }
        if(controlling && (DateTime.UtcNow-acquiredAt).TotalSeconds>2 && Json.Text(main.State,"active") != device.Id) controlling=false;
        await BeginControl();
        if (conflict || !prepared || (wantsControl && !controlling)) { await Task.Delay(500); continue; }
        bool retry = false;
        try {
          if (!legacy && !streaming) { var opened=await main.Client.Call("stream-open", new { id = device.Id, jpegOnly = jpegOnly }); h264Only=Json.Flag(opened,"h264Only"); streaming = true; }
          if (closed) { await main.Client.Call("stream-close", new { id = device.Id }); break; }
          var frame = await main.Client.Call(streaming ? "stream-next" : "screen", new { id = device.Id });
          var bitmap = await Task.Run(() => MainWindow.DecodeImage(Json.Text(frame, "data")));
          if (!closed) { if(!Json.Flag(frame,"unchanged")){image.Source = bitmap;picturePending=bitmap!=null;} if (bitmap != null && (!fitted || Math.Abs(screenRatio - (double)bitmap.PixelWidth/bitmap.PixelHeight) > .0001)) { bool ratioChanged=Math.Abs(screenRatio-(double)bitmap.PixelWidth/bitmap.PixelHeight)>.0001;fitted=true; UpdateLayout();if(!restoredGeometry || ratioChanged)FitScreen(bitmap.PixelWidth,bitmap.PixelHeight); }
            modeStat.Text=controlling?"正在控制":"仅查看";codecStat.Text=streaming?(Json.Text(frame,"codec")=="h264"?"H.264":"JPEG"):"截图";bitrateStat.Text=Json.Get(frame,"bitrate")==null?"-- Mbps":(Json.Number(frame,"bitrate",0)/1000000).ToString("0.0")+" Mbps";
            status.Text = modeStat.Text+" · "+codecStat.Text+" · "+fpsStat.Text+" · "+bitrateStat.Text;
            status.ToolTip = status.Text + " · " + (Json.Number(frame,"bitrate",4000000)/1000000).ToString("0.0") + " Mbps";
            if(inputFailure!=null && !controlling) {status.Text=inputFailure;status.ToolTip=inputFailure;}
          }
          if (legacy) await Task.Delay(50);
        }
        catch (Exception ex) {
          status.Text = MainWindow.DisplayError(ex.Message);
          if(ex.Message.StartsWith("CONTROL_ENDED:")) { main.Notify("对方已结束控制"); Close(); break; }
          controlling=false; if(wantsControl) prepared=false;
          if(Terminal(ex.Message)) { conflict = true; wantsControl = false; }
          if (ex.Message.StartsWith("VIDEO_H264_REQUIRED:") || ex.Message.Contains("Root 投屏需要 H.264")) { h264Only=true; jpegOnly=false;legacy=false; }
          else if (!h264Only && (ex.Message.Contains("不支持") || ex.Message.Contains("接口不存在"))) legacy = true;
          else if (!h264Only && (ex.Message.Contains("解码") || ex.Message.Contains("视频帧"))) jpegOnly = true;
          retry = true;
        }
        if (retry) { if (streaming) { try { await main.Client.Call("stream-close", new { id = device.Id }); } catch { } streaming = false; } await Task.Delay(1500); }
      }
    }
    async Task Release() {
      resumeOnActivate=false;
      wantsControl = false; fallbackAccepted = false; sessionVersion++; forwardedKeys.Clear(); if (!controlling) return; controlling = false; pendingMove = null; image.ReleaseMouseCapture();
      try { await inputs; var state = await main.Client.Call("state"); if (Json.Text(state, "active") == device.Id) await main.Client.Call("release"); } catch { }
      status.Text = "已返回本机 · 查看模式";
    }
    async Task Send(object input) {
      if (!controlling || input == null) return;
      var before = inputs; var completion = new TaskCompletionSource<object>(); inputs = completion.Task; Task recovery=Task.FromResult(0);
      try { await before; if (controlling) await main.Client.Call("input", new { id = device.Id, input = input }); }
      catch (Exception ex) { controlling = false; prepared=false; pendingMove=null; image.ReleaseMouseCapture(); if(Terminal(ex.Message)) {wantsControl=false;conflict=true;} inputFailure=MainWindow.DisplayError(ex.Message); status.Text=inputFailure;status.ToolTip=inputFailure; recovery=main.Client.Call("release").ContinueWith(t => { }); }
      await recovery; completion.TrySetResult(null);
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
