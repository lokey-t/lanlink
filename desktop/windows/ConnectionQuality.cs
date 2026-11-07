using System;
using System.Collections;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Media;
using System.Windows.Shapes;
using Microsoft.Win32;

namespace LanLink.Desktop {
  sealed class ConnectionQualityPage : Grid {
    readonly MainWindow main;
    readonly StackPanel tabs = new StackPanel { Orientation = Orientation.Horizontal };
    readonly StackPanel content = new StackPanel();
    readonly TextBlock status = new TextBlock { TextWrapping=TextWrapping.Wrap, FontSize=12 };
    readonly TextBlock time = new TextBlock { FontSize=12, Foreground=Theme.Brush("#65748B") };
    readonly Button test, export;
    readonly TextBlock testLabel = new TextBlock { FontSize=12 };
    readonly FrameworkElement playIcon = WindowShell.Glyph("play",16), stopIcon = WindowShell.Glyph("stop",16);
    readonly Dictionary<string,bool> expanded = new Dictionary<string,bool>();
    readonly JavaScriptSerializer serializer = new JavaScriptSerializer();
    string selected = "", signature = "";
    object state;
    bool polling, commandPending;
    int generation;

    public ConnectionQualityPage(MainWindow main) {
      this.main = main;
      RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
      RowDefinitions.Add(new RowDefinition());
      var header = new StackPanel();
      var toolbar = new DockPanel();
      var buttons = new StackPanel { Orientation=Orientation.Horizontal, Margin=new Thickness(8,0,0,0) };
      export = Action("另存为文本",SaveReport);
      var exportLabel=new StackPanel { Orientation=Orientation.Horizontal };
      var exportIcon=WindowShell.Glyph("download",16);exportIcon.Margin=new Thickness(0,0,6,0);
      exportLabel.Children.Add(exportIcon);exportLabel.Children.Add(UiLocale.Bind(Text(""),TextBlock.TextProperty,"另存为文本"));
      export.Content=exportLabel;export.Height=38;export.VerticalAlignment=VerticalAlignment.Top;
      export.Margin=new Thickness(0,0,8,0);
      test = Action("",TestSelection,false);
      var testContent=new StackPanel { Orientation=Orientation.Horizontal };
      playIcon.Margin=stopIcon.Margin=new Thickness(0,0,6,0);
      testContent.Children.Add(playIcon);testContent.Children.Add(stopIcon);testContent.Children.Add(testLabel);
      test.Content=testContent;test.Height=38;test.VerticalAlignment=VerticalAlignment.Top;test.Margin=new Thickness(0);
      test.SetResourceReference(StyleProperty,"Primary");
      buttons.Children.Add(export); buttons.Children.Add(test);
      DockPanel.SetDock(buttons,Dock.Right); toolbar.Children.Add(buttons);
      var title = new StackPanel();
      title.Children.Add(UiLocale.Bind(new TextBlock { FontSize=26, FontWeight=FontWeights.SemiBold },TextBlock.TextProperty,"连接质量分析"));
      var subtitle=UiLocale.Bind(new TextBlock { FontSize=12,Foreground=Theme.Brush("#65748B"),Margin=new Thickness(0,4,0,0) },TextBlock.TextProperty,"从本机到本组设备");
      title.Children.Add(subtitle); toolbar.Children.Add(title); header.Children.Add(toolbar);
      header.Children.Add(new ScrollViewer { Content=tabs, HorizontalScrollBarVisibility=ScrollBarVisibility.Auto,
        VerticalScrollBarVisibility=ScrollBarVisibility.Disabled, Margin=new Thickness(0,16,0,8) });
      var statusRow=new DockPanel();
      var statusIcon=WindowShell.Glyph("quality",14);
      statusIcon.Margin=new Thickness(0,0,7,0);
      DockPanel.SetDock(statusIcon,Dock.Left);statusRow.Children.Add(statusIcon);
      time.Margin=new Thickness(10,0,0,0);
      DockPanel.SetDock(time,Dock.Right); statusRow.Children.Add(time); statusRow.Children.Add(status);
      header.Children.Add(new Border { Child=statusRow, Background=Theme.Brush("#EAF0FB"), CornerRadius=new CornerRadius(10),
        Padding=new Thickness(11,8,11,8), Margin=new Thickness(0,0,0,12) });
      Children.Add(header);
      var scroll = new ScrollViewer { Content=content, VerticalScrollBarVisibility=ScrollBarVisibility.Auto };
      SetRow(scroll,1); Children.Add(scroll);
      Render();
    }

    Button Action(string name, Func<Task> work, bool translate=true) {
      var button = new Button { Content=translate?UiLocale.Content(name):(object)name, Padding=new Thickness(10,5,10,5), Margin=new Thickness(0,0,8,6) };
      button.Click += delegate { main.Run(work); };
      return button;
    }

    Task TestSelection() {
      if (Json.Text(Json.Get(state,"session"),"status")=="running") return Call("quality-stop");
      return selected=="" ? Call("quality-start") : Call("quality-start",new {id=selected});
    }

    async Task Call(string method, object args = null) {
      if (commandPending) return;
      commandPending=true;
      generation++;
      Render();
      try { Accept(await main.Client.Call(method,args)); }
      finally { commandPending=false; Render(); }
    }

    public async Task Refresh() {
      if (polling || commandPending) return;
      int version=generation;
      polling=true;
      try {
        var next=await main.Client.Call("quality-state");
        if (!commandPending && generation==version) Accept(next);
      }
      finally { polling=false; }
    }

    async Task SaveReport() {
      var report=await main.Client.Call("quality-export");
      var picker=new SaveFileDialog { FileName="LanLink-connection-quality-"+DateTime.Now.ToString("yyyyMMdd-HHmmss")+".txt",
        DefaultExt=".txt", Filter="Text (*.txt)|*.txt", AddExtension=true, OverwritePrompt=true };
      if(picker.ShowDialog(main)==true) File.WriteAllText(picker.FileName,Json.Text(report,"text"),new UTF8Encoding(true));
    }

    void Accept(object value) {
      string next=serializer.Serialize(value);
      if (next==signature) return;
      state=value; signature=next; Render();
    }

    static object[] List(object value,string key) {
      return (Json.Get(value,key) as IEnumerable ?? new object[0]).Cast<object>().ToArray();
    }

    static string Metric(object value,string key,string units) {
      return Json.Get(value,key)==null ? "—" : Json.Number(value,key,0).ToString("0.0")+" "+units;
    }

    static string StateLabel(string value) {
      switch(value) {
        case "pending": return Locale.T("等待测试");
        case "testing": return Locale.T("测试中");
        case "completed": return Locale.T("已完成");
        case "offline": return Locale.T("离线");
        case "unreachable": return Locale.T("无法连接");
        case "cancelled": return Locale.T("已取消");
        case "failed": return Locale.T("测试失败");
        default: return value;
      }
    }

    static string StageLabel(string stage) {
      switch(stage) {
        case "usb": return Locale.T("验证 USB 通道");
        case "latency": return Locale.T("延迟与稳定性");
        case "mouse": return Locale.T("鼠标通道延迟");
        case "upload": return Locale.T("上传速度");
        case "download": return Locale.T("下载速度");
        default: return "";
      }
    }

    public void Render() {
      var devices=List(state,"devices");
      if (!devices.Any(d=>Json.Text(d,"id")==selected)) selected="";
      var session=Json.Get(state,"session");
      var results=List(session,"devices");
      bool running=Json.Text(session,"status")=="running";
      test.IsEnabled=!commandPending && (running ? !Json.Flag(session,"cancelling") : devices.Length>0);
      playIcon.Visibility=running?Visibility.Collapsed:Visibility.Visible;
      stopIcon.Visibility=running?Visibility.Visible:Visibility.Collapsed;
      testLabel.Text=Locale.T(running ? "取消测试" : selected=="" ? "开始测试全组" : "测试此设备");
      export.IsEnabled=session!=null && !running && !commandPending;
      tabs.Children.Clear();
      AddTab("",Locale.T("全部"),null);
      foreach(var device in devices)
        AddTab(Json.Text(device,"id"),Json.Text(device,"name"),Json.Flag(device,"online"));
      var current=results.FirstOrDefault(d=>Json.Text(d,"id")==Json.Text(session,"currentDevice"));
      string sessionStatus=Json.Text(session,"status");
      int offline=results.Count(d=>Json.Text(d,"status")=="offline");
      string scope=sessionStatus=="completed" && Json.Text(session,"scope")=="group"
        ? Locale.Format("{0} 台在线 · {1} 台离线",results.Length-offline,offline)
        : Locale.T(Json.Text(session,"scope")=="device"?"单台设备":"全部");
      status.Text=running ? Locale.T(Json.Flag(session,"cancelling")?"正在取消测试":"测试中")+" · "+Json.Text(current,"name")+" · "+StageLabel(Json.Text(session,"stage"))
        : session==null ? Locale.T("选择设备或测试全组，获取从本机到目标设备的连接数据。")
        : StateLabel(sessionStatus)+" · "+scope;
      status.Foreground=Theme.Brush(sessionStatus=="completed"?"#286954":"#476389");
      time.Text=session==null || running ? "" : Locale.T("最近一次")+" · "+LocalTime(session);
      content.Children.Clear();
      if (session==null) return;
      if (selected=="") DrawOverview(results);
      else {
        var device=results.FirstOrDefault(d=>Json.Text(d,"id")==selected);
        if (device==null) AddText(content,Locale.T("最近一次测试未包含此设备。"));
        else DrawDevice(device);
      }
    }

    static string LocalTime(object session) {
      DateTime time;
      return DateTime.TryParse(Json.Text(session,"startedAt"),out time)?time.ToLocalTime().ToString("g"):"";
    }

    void SelectDevice(string id) { selected=id; Render(); }

    void AddTab(string id,string title,bool? online) {
      var button=Action(title,() => { SelectDevice(id); return Task.FromResult(0); },false);
      button.Padding=new Thickness(10,7,10,7);
      var label=new StackPanel { Orientation=Orientation.Horizontal };
      if(online.HasValue) label.Children.Add(new Ellipse { Width=6,Height=6,Fill=Theme.Brush(online.Value?"#3D8B73":"#8390A6"),
        Margin=new Thickness(0,0,7,0),VerticalAlignment=VerticalAlignment.Center });
      label.Children.Add(new TextBlock { Text=title,FontSize=12 });
      button.Content=label;
      if(id==selected) button.SetResourceReference(StyleProperty,"Primary");
      if(online==false) System.Windows.Automation.AutomationProperties.SetName(button,title+" · "+Locale.T("离线"));
      tabs.Children.Add(button);
    }

    static TextBlock Text(string text,bool muted=false) {
      var block=new TextBlock { Text=text,TextWrapping=TextWrapping.Wrap,FontSize=12 };
      if(muted) block.Foreground=Theme.Brush("#65748B");
      return block;
    }

    static void AddText(Panel panel,string text,bool muted=false) {
      var block=Text(text,muted); block.Margin=new Thickness(0,3,0,3); panel.Children.Add(block);
    }

    static Border Card(UIElement child) {
      return new Border { Child=child,Background=Theme.Brush("#FFFFFF"),BorderBrush=Theme.Brush("#DCE5F2"),
        BorderThickness=new Thickness(1),CornerRadius=new CornerRadius(16),Padding=new Thickness(14),Margin=new Thickness(0,0,0,12) };
    }

    static StackPanel PanelContent(string title,string note) {
      var body=new StackPanel();
      var heading=new DockPanel { Margin=new Thickness(0,0,0,8) };
      var caption=Text(note,true);
      DockPanel.SetDock(caption,Dock.Right); caption.Margin=new Thickness(8,0,0,0); heading.Children.Add(caption);
      var label=Text(title);label.FontSize=15;label.FontWeight=FontWeights.SemiBold;heading.Children.Add(label);
      body.Children.Add(heading); return body;
    }

    void ChartPair(StackPanel first,StackPanel second) {
      var pair=new UniformGrid { Columns=2 };
      var left=Card(first); left.Margin=new Thickness(0,0,6,12);
      var right=Card(second); right.Margin=new Thickness(6,0,0,12);
      pair.Children.Add(left);pair.Children.Add(right);content.Children.Add(pair);
    }

    static object Route(object device) {
      var routes=List(device,"routes");
      return routes.FirstOrDefault(r=>Json.Flag(r,"mouseRoute")) ?? routes.FirstOrDefault();
    }

    static string Transport(object route) {
      if(route==null)return "—";
      string protocol=Json.Text(route,"protocol");
      if(Json.Text(route,"kind")=="usb")return "USB · ADB / TCP";
      return Locale.T("局域网")+" · "+(protocol.StartsWith("QUIC",StringComparison.Ordinal)?"QUIC":"TCP");
    }

    static double? Number(object value,string key) {
      return Json.Get(value,key)==null?(double?)null:Json.Number(value,key,0);
    }

    sealed class Reading {
      public readonly string Label, Color;
      public readonly double? Value;
      public Reading(string label,double? value,string color="#557CCE"){Label=label;Value=value;Color=color;}
    }

    static IEnumerable<Reading> Rates(string label,object route) {
      yield return new Reading(label+" ↑",Number(Json.Get(route,"uploadSummary"),"mbps"),"#3D8B73");
      yield return new Reading(label+" ↓",Number(Json.Get(route,"downloadSummary"),"mbps"));
    }

    static void Bars(Panel body,IEnumerable<Reading> readings,string unit) {
      var rows=readings.Where(r=>r.Value.HasValue).ToArray();
      if(rows.Length==0) { AddText(body,Locale.T("暂无测量数据"),true); return; }
      double max=ScaleMax(rows.Max(r=>r.Value.Value));
      foreach(var entry in rows) {
        var row=new Grid { Margin=new Thickness(0,3,0,4) };
        row.ColumnDefinitions.Add(new ColumnDefinition { Width=new GridLength(85) });
        row.ColumnDefinitions.Add(new ColumnDefinition());
        row.ColumnDefinitions.Add(new ColumnDefinition { Width=new GridLength(46) });
        var label=Text(entry.Label);label.TextWrapping=TextWrapping.NoWrap;label.TextTrimming=TextTrimming.CharacterEllipsis;
        label.ToolTip=entry.Label; row.Children.Add(label);
        double fraction=entry.Value.Value/max;
        var track=new Grid { Height=7,Margin=new Thickness(5,0,5,0),VerticalAlignment=VerticalAlignment.Center };
        track.ColumnDefinitions.Add(new ColumnDefinition { Width=new GridLength(fraction,GridUnitType.Star) });
        track.ColumnDefinitions.Add(new ColumnDefinition { Width=new GridLength(1-fraction,GridUnitType.Star) });
        var background=new Border { Background=Theme.Brush("#EDF2FA"),CornerRadius=new CornerRadius(4) };
        SetColumnSpan(background,2);track.Children.Add(background);
        track.Children.Add(new Border { Background=Theme.Brush(entry.Color),CornerRadius=new CornerRadius(4) });
        SetColumn(track,1);row.Children.Add(track);
        var number=Text(entry.Value.Value.ToString("0.0"));number.TextAlignment=TextAlignment.Right;
        number.ToolTip=entry.Label+" · "+entry.Value.Value.ToString("0.0")+" "+unit;
        SetColumn(number,2);row.Children.Add(number);body.Children.Add(row);
      }
      var axis=new DockPanel { Margin=new Thickness(90,3,51,0) };
      var start=Text("0",true);DockPanel.SetDock(start,Dock.Left);axis.Children.Add(start);
      var end=Text(max.ToString("0.##"),true);DockPanel.SetDock(end,Dock.Right);axis.Children.Add(end);
      var middle=Text((max/2).ToString("0.##"),true);middle.TextAlignment=TextAlignment.Center;axis.Children.Add(middle);
      body.Children.Add(axis);
    }

    static double ScaleMax(double value) {
      if(value<=0)return 1;
      double step=Math.Pow(10,Math.Floor(Math.Log10(value)));
      return Math.Ceiling(value/step)*step;
    }

    static Grid TableRow(params UIElement[] cells) {
      var row=new Grid { Margin=new Thickness(0,6,0,6) };
      for(int i=0;i<cells.Length;i++) {
        row.ColumnDefinitions.Add(new ColumnDefinition { Width=new GridLength(i==0?1.65:1,GridUnitType.Star) });
        SetColumn(cells[i],i);row.Children.Add(cells[i]);
      }
      return row;
    }

    static void Divider(Panel panel) {
      panel.Children.Add(new Border { Height=1,Background=Theme.Brush("#DCE5F2") });
    }

    static Border Badge(string value) {
      var label=Text(StateLabel(value));
      label.Foreground=Theme.Brush(value=="completed"?"#286954":"#65748B");
      return new Border { Child=label,HorizontalAlignment=HorizontalAlignment.Left,VerticalAlignment=VerticalAlignment.Center,
        Background=Theme.Brush(value=="completed"?"#EAF6EF":"#EDF2FA"),CornerRadius=new CornerRadius(12),Padding=new Thickness(7,3,7,3) };
    }

    void DrawOverview(object[] results) {
      var latency=PanelContent(Locale.T("鼠标通道延迟"),"ms");
      Bars(latency,results.Select(d=>new Reading(Json.Text(d,"name"),Number(Json.Get(Route(d),"mouse"),"averageMs"))),"ms");
      var speed=PanelContent(Locale.T("传输速度"),"↑ "+Locale.T("上传")+"  ↓ "+Locale.T("下载")+" · Mb/s");
      Bars(speed,results.SelectMany(d=>Rates(Json.Text(d,"name"),Route(d))),"Mb/s");
      ChartPair(latency,speed);
      var table=PanelContent(Locale.T("设备明细"),Locale.T("显示当前通道"));
      table.Children.Add(TableRow(Text(Locale.T("设备"),true),Text(Locale.T("连接通道"),true),
        Text(Locale.T("延迟")+" · ms",true),Text(Locale.T("上传 / 下载")+" · Mb/s",true),Text(Locale.T("状态"),true)));
      foreach(var device in results) {
        Divider(table);
        var route=Route(device);
        var name=Action(Json.Text(device,"name"),()=>{SelectDevice(Json.Text(device,"id"));return Task.FromResult(0);},false);
        name.Padding=new Thickness(2);name.Margin=new Thickness(0);name.BorderThickness=new Thickness(0);name.Background=Brushes.Transparent;
        name.Foreground=Theme.Brush("#4169CE");name.HorizontalAlignment=HorizontalAlignment.Left;
        table.Children.Add(TableRow(name,Text(Transport(route)),Text(Metric(Json.Get(route,"mouse"),"averageMs","").Trim()),
          Text(Metric(Json.Get(route,"uploadSummary"),"mbps","").Trim()+" / "+Metric(Json.Get(route,"downloadSummary"),"mbps","").Trim()),
          Badge(Json.Text(device,"status"))));
      }
      content.Children.Add(Card(table));
    }

    void DrawDevice(object device) {
      var routes=List(device,"routes");
      var heading=new DockPanel { Margin=new Thickness(0,0,0,10) };
      var badge=Badge(Json.Text(device,"status"));
      DockPanel.SetDock(badge,Dock.Right);heading.Children.Add(badge);
      var identity=new StackPanel();
      var name=Text(Json.Text(device,"name"));name.FontSize=16;name.FontWeight=FontWeights.SemiBold;
      identity.Children.Add(name);
      if(Json.Text(device,"usbMessage")!="") AddText(identity,Locale.T(Json.Text(device,"usbMessage")),true);
      heading.Children.Add(identity);content.Children.Add(heading);
      if(routes.Length==0)return;
      var latency=PanelContent(Locale.T("往返延迟趋势"),"ms");
      DrawTrend(latency,routes);
      var speed=PanelContent(Locale.T("实测传输速度"),"↑ "+Locale.T("上传")+"  ↓ "+Locale.T("下载")+" · Mb/s");
      Bars(speed,routes.SelectMany(r=>Rates(Json.Text(r,"kind")=="usb"?"USB":Transport(r),r)),"Mb/s");
      ChartPair(latency,speed);
      var table=PanelContent(Locale.T("通道稳定性"),"");
      table.Children.Add(TableRow(Text(Locale.T("连接通道"),true),Text(Locale.T("平均")+" · ms",true),
        Text(Locale.T("峰值")+" · ms",true),Text(Locale.T("抖动")+" · ms",true),Text(Locale.T("超时"),true)));
      var diagnostics=new StackPanel { Margin=new Thickness(0,6,0,0) };
      if(Json.Text(device,"build")!="") AddText(diagnostics,Locale.T("对端构建")+" · "+Json.Text(device,"build"),true);
      foreach(var route in routes) {
        Divider(table);
        var stats=Json.Get(route,"latency");
        table.Children.Add(TableRow(Text(Transport(route)),Text(Metric(stats,"averageMs","").Trim()),Text(Metric(stats,"peakMs","").Trim()),
          Text(Metric(stats,"jitterMs","").Trim()),Text(Json.Text(stats,"timeouts")+" / "+Json.Text(stats,"attempts"))));
        AddText(diagnostics,Transport(route)+" · "+Json.Text(route,"protocol"),false);
        AddText(diagnostics,Locale.T("响应 / 总次数")+" "+Json.Text(stats,"received")+"/"+Json.Text(stats,"attempts")+" · "+
          Locale.T("鼠标 RTT")+" "+Metric(Json.Get(route,"mouse"),"averageMs","ms"),true);
        var errors=new[]{"samples","mouseSamples","upload","download"}.SelectMany(k=>List(route,k))
          .Select(s=>Json.Text(s,"error")).Where(e=>e!="").Distinct().ToArray();
        if(errors.Length>0) AddText(diagnostics,Locale.T("诊断代码")+" · "+String.Join(", ",errors),true);
        DrawDiagnosticTrend(diagnostics,route,"mouseSamples","鼠标通道延迟","ms");
        DrawDiagnosticTrend(diagnostics,route,"upload","上传速度","Mb/s");
        DrawDiagnosticTrend(diagnostics,route,"download","下载速度","Mb/s");
      }
      if(Json.Get(device,"capabilities")!=null && !Json.Flag(Json.Get(device,"capabilities"),"quality"))
        AddText(diagnostics,Locale.T("对端版本仅支持基础延迟测试。"),true);
      bool isOpen;expanded.TryGetValue(selected,out isOpen);
      var details=new Expander { Header=UiLocale.Content("查看诊断明细"),Content=diagnostics,IsExpanded=isOpen };
      var id=selected;
      details.Expanded+=delegate{expanded[id]=true;}; details.Collapsed+=delegate{expanded[id]=false;};
      table.Children.Add(details);content.Children.Add(Card(table));
    }

    static void DrawDiagnosticTrend(Panel panel,object route,string key,string title,string units) {
      if(!List(route,key).Any(s=>Number(s,"ms").HasValue))return;
      AddText(panel,Locale.T(title)+" · "+units,true);
      DrawTrend(panel,new[]{route},key,units);
    }

    static void DrawTrend(Panel panel,object[] routes,string key="samples",string units="ms") {
      var series=routes.Select(r=>new {Route=r,Values=List(r,key).Select(s=>{
        var ms=Number(s,"ms");
        return units=="ms" || !ms.HasValue ? ms : Number(s,"bytes")*.008/Math.Max(.01,ms.Value);
      }).ToArray()})
        .Where(r=>r.Values.Any(v=>v.HasValue)).ToArray();
      if(series.Length==0){AddText(panel,Locale.T("暂无测量数据"),true);return;}
      const double width=275,height=98,left=28,right=268;
      double max=ScaleMax(series.Max(r=>r.Values.Max().Value));
      int samples=series.Max(r=>r.Values.Length);
      var chart=new Canvas { Width=width,Height=height,ClipToBounds=false };
      for(int i=0;i<3;i++) {
        double y=8+i*34;
        chart.Children.Add(new Line { X1=left,X2=right,Y1=y,Y2=y,Stroke=Theme.Brush("#DCE5F2"),StrokeThickness=1 });
        var tick=Text((max*(2-i)/2).ToString("0.##"),true);Canvas.SetTop(tick,y-8);chart.Children.Add(tick);
      }
      foreach(var entry in series) {
        bool usb=Json.Text(entry.Route,"kind")=="usb";
        var stroke=Theme.Brush(usb?"#3D8B73":"#557CCE");
        Point? previous=null;
        for(int i=0;i<entry.Values.Length;i++) {
          if(!entry.Values[i].HasValue){previous=null;continue;}
          var point=new Point(left+i*(right-left)/Math.Max(1,samples-1),76-68*entry.Values[i].Value/max);
          if(previous.HasValue)chart.Children.Add(new Line { X1=previous.Value.X,Y1=previous.Value.Y,X2=point.X,Y2=point.Y,
            Stroke=stroke,StrokeThickness=2,StrokeDashArray=usb?new DoubleCollection{3,2}:null });
          var dot=new Ellipse { Width=5,Height=5,Fill=stroke,ToolTip=Transport(entry.Route)+" · "+entry.Values[i].Value.ToString("0.0")+" "+units };
          Canvas.SetLeft(dot,point.X-2.5);Canvas.SetTop(dot,point.Y-2.5);chart.Children.Add(dot);previous=point;
        }
      }
      var ticks=Text("1",true);Canvas.SetLeft(ticks,left);Canvas.SetTop(ticks,80);chart.Children.Add(ticks);
      if(samples>1) {
        var end=Text(samples.ToString(),true);Canvas.SetLeft(end,right-7);Canvas.SetTop(end,80);chart.Children.Add(end);
      }
      panel.Children.Add(new Viewbox { Child=chart,Height=102,Stretch=Stretch.Uniform,HorizontalAlignment=HorizontalAlignment.Stretch });
      AddText(panel,String.Join("   ",series.Select(entry=>(Json.Text(entry.Route,"kind")=="usb"?"┄ ":"━ ")+Transport(entry.Route)).Distinct()),true);
    }
  }
}
