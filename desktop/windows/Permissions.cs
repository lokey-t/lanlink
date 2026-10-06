using System;
using System.Collections;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Threading;

namespace LanLink.Desktop {
  public sealed class DevicePermissionsPage : Grid {
    readonly MainWindow main;
    readonly StackPanel content = new StackPanel();
    readonly TextBlock status = MainWindow.Text("读取组内设备权限…", 12, "#7B879D");
    readonly DispatcherTimer timer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(4) };
    bool busy;
    public CheckBox InputToggle { get; private set; }
    public DevicePermissionsPage(MainWindow owner, Action back) {
      main = owner;
      RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto }); RowDefinitions.Add(new RowDefinition());
      var header = new DockPanel { Margin = new Thickness(0,0,0,18) };
      var close = MainWindow.Button("返回设备", back); DockPanel.SetDock(close,Dock.Right); header.Children.Add(close);
      var heading = new StackPanel(); heading.Children.Add(MainWindow.Text("本组设备权限", 25)); status.Margin = new Thickness(0,8,0,0); heading.Children.Add(status); header.Children.Add(heading); Children.Add(header);
      var scroll = new ScrollViewer { Content = content, VerticalScrollBarVisibility = ScrollBarVisibility.Auto }; Grid.SetRow(scroll,1); Children.Add(scroll);
      timer.Tick += async delegate { if (IsVisible) await Refresh(); };
      IsVisibleChanged += async delegate { if (IsVisible) { timer.Start(); await Refresh(); } else timer.Stop(); };
    }
    public async Task Refresh() {
      if (busy) return; busy = true;
      try { Display(await main.Client.Call("group-permissions")); }
      catch (Exception error) { status.Text = MainWindow.DisplayError(error.Message); }
      finally { busy = false; }
    }
    public void Display(object result) {
      content.Children.Clear();
      InputToggle=null;
      var devices = Json.Get(result,"devices") as IEnumerable;
      if (devices == null) return;
      foreach (var device in devices) {
        var card = new StackPanel();
        string name = Json.Text(device,"name");
        card.Children.Add(MainWindow.Text(name + (Json.Flag(device,"local") ? " · 本机" : Json.Flag(device,"online") ? " · 在线" : " · 离线"),16));
        string message = Json.Text(device,"message");
        if(message != "") { var text = MainWindow.Text(message,12,"#8B7897"); text.Margin = new Thickness(0,8,0,0); card.Children.Add(text); }
        var rows = Json.Get(device,"permissions") as IEnumerable;
        if(rows != null) foreach(var row in rows) {
          var line = new Grid { Margin = new Thickness(0,14,0,0) }; line.ColumnDefinitions.Add(new ColumnDefinition()); line.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(150) });
          var words = new StackPanel(); words.Children.Add(MainWindow.Text(Json.Text(row,"label"),14));
          if(Json.Flag(row,"supported")) { var detail = MainWindow.Text("应用：" + Json.Text(row,"app") + "   系统：" + Json.Text(row,"system") + "   对本机：" + Json.Text(row,"consent"),12,"#7B879D"); detail.Margin = new Thickness(0,5,0,0); detail.TextWrapping=TextWrapping.Wrap; words.Children.Add(detail); }
          line.Children.Add(words); string label = Json.Text(row,"status"); var badge=MainWindow.Text(label,12,label=="已就绪" ? "#38846B":"#887B90"); badge.HorizontalAlignment=HorizontalAlignment.Right;Grid.SetColumn(badge,1);line.Children.Add(badge); card.Children.Add(line);
          if(Json.Flag(device,"local") && Json.Flag(row,"supported")) {
            string permission=Json.Text(row,"key");var toggle=new CheckBox{Content="允许本组设备使用此功能",IsChecked=Json.Text(row,"app")=="已开启",Margin=new Thickness(0,8,0,0)};
            if(permission=="input")InputToggle=toggle;
            toggle.Click += delegate { main.Run(async()=>{toggle.IsEnabled=false;try{await main.Client.Call("settings",new{permissions=new System.Collections.Generic.Dictionary<string,bool>{{permission,toggle.IsChecked==true}}});await Refresh();}catch(Exception error){status.Text=MainWindow.DisplayError(error.Message);toggle.IsChecked=!(toggle.IsChecked==true);}finally{toggle.IsEnabled=true;}}); };card.Children.Add(toggle);
          }
        }
        var engine = Json.Get(device,"engine");
        if(engine != null) { var hint = MainWindow.Text(Json.Text(engine,"message"),12,"#7B879D");hint.Margin=new Thickness(0,16,0,0); hint.TextWrapping=TextWrapping.Wrap;card.Children.Add(hint); }
        if(Json.Text(device,"platform")=="android" && Json.Flag(device,"online")) {
          string id=Json.Text(device,"id");var button=MainWindow.Button("安卓设备连接授权",()=>main.Run(async()=>{await main.Client.Call("adb-guide-open",new {id=id});}));button.Margin=new Thickness(0,14,0,0);button.HorizontalAlignment=HorizontalAlignment.Left;card.Children.Add(button);
        }
        content.Children.Add(new Border { Child=card,Padding=new Thickness(22),CornerRadius=new CornerRadius(20),Background=MainWindow.Brush("#FFFFFF"),Margin=new Thickness(0,0,0,16) });
      }
      status.Text="自动刷新 · 分别显示应用开关、系统能力以及对本电脑的授权";
    }
  }
}
