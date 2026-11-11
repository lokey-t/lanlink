using System;
using System.Collections;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Threading;

namespace LanLink.Desktop {
  public sealed class DevicePermissionsPage : Grid {
    readonly MainWindow main;
    readonly StackPanel content=new StackPanel();
    readonly TextBlock status=MainWindow.LText("读取组内设备权限…",12,"#7B879D");
    readonly DispatcherTimer timer=new DispatcherTimer{Interval=TimeSpan.FromSeconds(4)};
    readonly Dictionary<string,DeviceCard> cards=new Dictionary<string,DeviceCard>();
    static readonly string[] Keys={"input","screen","clipboard","shutdown"};
    static readonly string[] Names={"键鼠控制","屏幕查看","剪贴板与文件","远程关机"};
    bool busy; int revision; string focusDevice;
    public CheckBox InputToggle {get;private set;}
    public Button InputButton {get;private set;}
    sealed class Cell { public Button Button;public CheckBox Toggle;public object Data;public bool Pending; }
    sealed class DeviceCard { public Border View,MasterBadge;public Grid Row;public TextBlock Heading,Detail;public string Id,Name;public bool Local,Online;public Dictionary<string,Cell> Cells=new Dictionary<string,Cell>(); }
    public DevicePermissionsPage(MainWindow owner) {
      main=owner;RowDefinitions.Add(new RowDefinition{Height=GridLength.Auto});RowDefinitions.Add(new RowDefinition());
      var header=new StackPanel{Margin=new Thickness(0,0,0,18)};
      var title=new DockPanel();var refresh=MainWindow.LButton("刷新",()=>main.Run(Refresh));DockPanel.SetDock(refresh,Dock.Right);title.Children.Add(refresh);
      var hid=MainWindow.LButton("HID通道",main.OpenHidChannels);hid.Margin=new Thickness(0,0,12,0);DockPanel.SetDock(hid,Dock.Right);title.Children.Add(hid);
      title.Children.Add(MainWindow.LText("本组设备权限",25));header.Children.Add(title);
      status.Margin=new Thickness(0,10,0,14);status.TextWrapping=TextWrapping.Wrap;header.Children.Add(status);
      var legend=new WrapPanel();foreach(string state in new[]{"ready","unready","disabled"}){var label=MainWindow.Text("● "+StateName(state),12,Color(state));label.Margin=new Thickness(0,0,18,0);legend.Children.Add(label);}header.Children.Add(legend);Children.Add(header);
      var body=new StackPanel{MinWidth=560};var columns=MatrixRow();columns.Margin=new Thickness(18,0,18,12);columns.Children.Add(MainWindow.LText("设备",13,"#7B879D"));for(int i=0;i<Keys.Length;i++){var label=MainWindow.LText(Names[i],12,"#7B879D");label.HorizontalAlignment=HorizontalAlignment.Center;SetColumn(label,i+1);columns.Children.Add(label);}body.Children.Add(columns);body.Children.Add(content);
      var hint=MainWindow.LText("悬停查看含义 · 左键或右键打开菜单\n本机行：功能总开关；其他设备行：本机使用该设备的权限。",12,"#7B879D");hint.Margin=new Thickness(18,14,18,0);body.Children.Add(hint);
      var scroll=new ScrollViewer{Content=body,VerticalScrollBarVisibility=ScrollBarVisibility.Auto,HorizontalScrollBarVisibility=ScrollBarVisibility.Auto};SetRow(scroll,1);Children.Add(scroll);
      timer.Tick+=async delegate{if(IsVisible)await Refresh();};IsVisibleChanged+=async delegate{if(IsVisible){timer.Start();await Refresh();}else{timer.Stop();foreach(var card in cards.Values)foreach(var cell in card.Cells.Values)if(cell.Button.ContextMenu!=null)cell.Button.ContextMenu.IsOpen=false;}};
    }
    static Grid MatrixRow(){var row=new Grid();row.ColumnDefinitions.Add(new ColumnDefinition{Width=new GridLength(1.8,GridUnitType.Star),MinWidth=180});for(int i=0;i<4;i++)row.ColumnDefinitions.Add(new ColumnDefinition{Width=new GridLength(1,GridUnitType.Star),MinWidth=82});return row;}
    public async Task Refresh(){if(busy)return;busy=true;int version=revision;try{var result=await main.Client.Call("group-permissions");if(version==revision)Display(result);}catch(Exception e){status.Text=MainWindow.DisplayError(e.Message)+Locale.T(" 点击刷新重试。");}finally{busy=false;}}
    internal Button PermissionButton(string id,string key){return cards[id].Cells[key].Button;}
    public void FocusDevice(string id){focusDevice=id;FocusRequested();}
    void FocusRequested(){DeviceCard card;if(focusDevice!=null&&cards.TryGetValue(focusDevice,out card)){card.View.BringIntoView();card.Cells["input"].Button.Focus();focusDevice=null;}}
    public static string CellState(object data){string state=Json.Text(data,"state");if(state!="")return state;if(Json.Text(data,"status")=="不支持")return "unsupported";if(Json.Text(data,"app")=="未开启"||Json.Text(data,"consent")=="未允许")return "disabled";if(Json.Text(data,"status")=="已就绪")return "ready";return Json.Text(data,"system")=="未就绪"?"unready":"unknown";}
    static string StateName(string state){return Locale.T(state=="ready"?"已就绪":state=="disabled"?"已禁用":state=="unsupported"?"不支持":state=="unknown"?"状态未知":"未就绪");}
    static string Color(string state){return state=="ready"?"#16864B":state=="unready"?"#D73C49":"#7D879A";}
    public static FrameworkElement Icon(string key,string color,double size=25){var icon=WindowShell.Glyph(key=="input"?"keyboard":key=="screen"?"screen":key=="shutdown"?"power":"clipboard",size);((System.Windows.Shapes.Path)((Viewbox)icon).Child).Stroke=MainWindow.Brush(color);return icon;}
    DeviceCard CreateCard(string id,bool local){
      var card=new DeviceCard{Id=id,Local=local,Row=MatrixRow(),Heading=MainWindow.Text("",15),Detail=MainWindow.Text("",12,"#7B879D")};var identity=new StackPanel();
      card.MasterBadge=new Border { Background=MainWindow.Brush("#EEEBFA"),CornerRadius=new CornerRadius(10),
        Padding=new Thickness(7,3,7,3),Margin=new Thickness(0,0,0,5),HorizontalAlignment=HorizontalAlignment.Left,
        Child=MainWindow.LText("主设备",12,"#6554AC"),Visibility=Visibility.Collapsed };
      identity.Children.Add(card.MasterBadge);identity.Children.Add(card.Heading);card.Heading.TextWrapping=TextWrapping.Wrap;card.Detail.TextWrapping=TextWrapping.Wrap;card.Detail.Margin=new Thickness(0,7,10,0);identity.Children.Add(card.Detail);card.Row.Children.Add(identity);
      for(int i=0;i<Keys.Length;i++){string key=Keys[i];var cell=new Cell();cell.Button=MainWindow.Button("",()=>OpenMenu(card,key));cell.Button.Width=48;cell.Button.Height=48;cell.Button.Padding=new Thickness(0);cell.Button.Margin=new Thickness(4);cell.Button.BorderThickness=new Thickness(0);cell.Button.HorizontalAlignment=HorizontalAlignment.Center;cell.Button.ContextMenuOpening+=(s,e)=>{e.Handled=true;OpenMenu(card,key);};cell.Button.PreviewMouseRightButtonUp+=(s,e)=>{e.Handled=true;OpenMenu(card,key);};cell.Button.KeyDown+=(s,e)=>{if(e.Key==Key.Apps || e.Key==Key.F10 && Keyboard.Modifiers==ModifierKeys.Shift){e.Handled=true;OpenMenu(card,key);}};ToolTipService.SetInitialShowDelay(cell.Button,200);SetColumn(cell.Button,i+1);card.Row.Children.Add(cell.Button);
        cell.Toggle=new CheckBox{Content=UiLocale.Content(local?"允许本组设备使用":"允许本机使用此设备"),Margin=new Thickness(0,12,0,6)};cell.Toggle.Click+=delegate{main.Run(()=>Change(card,key,cell));};card.Cells.Add(key,cell);if(local&&key=="input"){InputToggle=cell.Toggle;InputButton=cell.Button;}}
      card.View=new Border{Child=card.Row,Padding=new Thickness(18,20,18,20),CornerRadius=new CornerRadius(16),Background=MainWindow.Brush("#FFFFFF"),BorderBrush=MainWindow.Brush("#E5EBF5"),BorderThickness=new Thickness(1),Margin=new Thickness(0,0,0,10)};cards.Add(id,card);content.Children.Add(card.View);return card;
    }
    async Task Change(DeviceCard card,string key,Cell cell){if(cell.Pending)return;cell.Pending=true;revision++;cell.Toggle.IsEnabled=false;bool enabled=cell.Toggle.IsChecked==true;try{var result=await main.Client.Call("permission-toggle",new{id=card.Id,permission=key,enabled=enabled});if(Json.Flag(result,"guideRequired"))main.ShowPermissionTargetReminder(card.Name);else if(Json.Text(result,"warning")!="")main.SettingsResult(result,Locale.T("权限已保存"));else main.NotifyTransient(Locale.T("权限已保存"));}catch(Exception e){cell.Toggle.IsChecked=!enabled;main.Notify(e.Message);}finally{cell.Pending=false;revision++;}if(cell.Button.ContextMenu!=null)cell.Button.ContextMenu.IsOpen=false;await Refresh();}
    void OpenMenu(DeviceCard card,string key){
      var cell=card.Cells[key];if(cell.Button.ContextMenu!=null)cell.Button.ContextMenu.IsOpen=false;
      var menu=new ContextMenu{PlacementTarget=cell.Button,Placement=PlacementMode.Bottom};cell.Button.ContextMenu=menu;WindowShell.GlassMenu(menu,main);
      var body=new StackPanel{Width=254};var title=new DockPanel();var icon=Icon(key,Color(CellState(cell.Data)),24);icon.Margin=new Thickness(0,0,12,0);DockPanel.SetDock(icon,Dock.Left);title.Children.Add(icon);title.Children.Add(MainWindow.LText(Names[Array.IndexOf(Keys,key)],18));body.Children.Add(title);
      var device=MainWindow.Text(card.Name,12,"#7B879D");device.Margin=new Thickness(0,10,0,10);body.Children.Add(device);var label=MainWindow.Text("● "+StateName(CellState(cell.Data)),13,Color(CellState(cell.Data)));body.Children.Add(label);body.Children.Add(new Separator{Margin=new Thickness(0,12,0,12)});
      var detail=MainWindow.Text(Locale.T("应用：")+Locale.T(Json.Text(cell.Data,"app"))+" · "+Locale.T("系统：")+Locale.T(Json.Text(cell.Data,"system"))+(card.Local?"":"\n"+Locale.T("本机访问此设备：")+Locale.T(Json.Text(cell.Data,"consent"))),12,"#7B879D");detail.TextWrapping=TextWrapping.Wrap;body.Children.Add(detail);
      string state=CellState(cell.Data);bool supported=state!="unsupported",known=state!="unknown";
      if(card.Online&&supported&&known){
        if(Json.Text(cell.Data,"system")=="已就绪") {if(cell.Toggle.Parent is Panel)((Panel)cell.Toggle.Parent).Children.Remove(cell.Toggle);cell.Toggle.IsEnabled=!cell.Pending;body.Children.Add(cell.Toggle);}
        if(state=="unready" || Json.Text(cell.Data,"app")=="未开启" || Json.Text(cell.Data,"consent")=="未允许" && Json.Text(cell.Data,"system")!="已就绪") {var enable=MainWindow.LButton("一键开启",()=>{menu.IsOpen=false;main.Run(()=>main.OpenPermissionGuide(card.Id,key,card.Name));});enable.Margin=new Thickness(0,14,0,0);enable.Style=(Style)main.FindResource("Primary");body.Children.Add(enable);body.Children.Add(MainWindow.LText(card.Local?"在本机打开权限引导":"在目标设备打开权限引导",12,"#7B879D"));}
      }else{body.Children.Add(MainWindow.LText(!card.Online?"设备离线，请等待设备重新连接":state=="unsupported"?"此设备不支持该功能":"请刷新状态，或升级目标设备后重试",12,"#7B879D"));}
      if(!card.Local){var manage=MainWindow.LButton("管理此设备使用本机的权限",()=>{menu.IsOpen=false;OpenInbound(card,key);});manage.Margin=new Thickness(0,14,0,0);body.Children.Add(manage);}
      menu.Items.Add(new MenuItem{Header=body,StaysOpenOnClick=true,Focusable=false});menu.IsOpen=true;
    }
    void OpenInbound(DeviceCard card,string key){var panel=MainWindow.Column();panel.Children.Add(MainWindow.LText("此设备使用本机的权限",20));panel.Children.Add(MainWindow.Text(card.Name+" → "+Locale.T("本机"),13));var toggle=new CheckBox{Content=UiLocale.Content(Names[Array.IndexOf(Keys,key)]),IsChecked=Json.Flag(Json.Get(Json.Get(main.State,"deviceGrants"),card.Id),key)};var grants=Json.Get(Json.Get(main.State,"deviceGrants"),card.Id);if(grants==null)toggle.IsChecked=true;panel.Children.Add(toggle);var window=new Window{Title=Locale.T("设备权限"),Owner=main,Width=410,SizeToContent=SizeToContent.Height,Resources=main.Resources,WindowStartupLocation=WindowStartupLocation.CenterOwner};panel.Children.Add(MainWindow.LButton("保存",()=>main.Run(async()=>{await main.Client.Call("consent-set",new{id=card.Id,permissions=new Dictionary<string,bool>{{key,toggle.IsChecked==true}}});window.Close();main.NotifyTransient(Locale.T("权限已保存"));})));WindowShell.Wrap(window,panel,false);window.Show();}
    public void Display(object result){
      var seen=new HashSet<string>();var order=new List<DeviceCard>();
      foreach(var device in Json.Get(result,"devices") as IEnumerable??new object[0]){string id=Json.Text(device,"id");if(id=="")id="preview:"+Json.Text(device,"name");if(!seen.Add(id))continue;
        DeviceCard card;if(!cards.TryGetValue(id,out card))card=CreateCard(id,Json.Flag(device,"local"));
        bool master=Json.Flag(device,"master");card.MasterBadge.Visibility=master?Visibility.Visible:Visibility.Collapsed;
        order.Add(card);
        card.Name=Json.Text(device,"name");card.Online=card.Local||Json.Flag(device,"online");card.Heading.Text=card.Name;string platform=Json.Text(device,"platform");card.Detail.Text=(platform=="win32"?"Windows":platform=="darwin"?"macOS":platform=="android"?"Android":Locale.T("设备"))+" · "+Locale.T(card.Local?"本机":card.Online?"在线":"离线");string message=Json.Text(device,"message");if(message!="")card.Detail.Text+="\n"+Locale.T(message);
        foreach(var cell in card.Cells.Values)cell.Data=null;
        foreach(var data in Json.Get(device,"permissions") as IEnumerable??new object[0]){string key=Json.Text(data,"key");if(key==""){string label=Json.Text(data,"label");key=label.Contains("键鼠")?"input":label.Contains("屏幕")?"screen":label.Contains("关机")?"shutdown":"clipboard";}if(card.Cells.ContainsKey(key))card.Cells[key].Data=data;}
        foreach(var pair in card.Cells){var cell=pair.Value;string state=CellState(cell.Data);var visual=new Grid{Width=48,Height=48};visual.Children.Add(Icon(pair.Key,Color(state)));var mark=MainWindow.Text(state=="ready"?"✓":state=="unready"?"!":state=="unknown"?"?":"−",10,Color(state));mark.HorizontalAlignment=HorizontalAlignment.Right;mark.VerticalAlignment=VerticalAlignment.Top;mark.Margin=new Thickness(0,3,4,0);visual.Children.Add(mark);cell.Button.Content=visual;cell.Button.Background=MainWindow.Brush(state=="ready"?"#E3F7EB":state=="unready"?"#FFF0F1":"#EFF2F6");string text=Locale.T(Names[Array.IndexOf(Keys,pair.Key)])+" · "+StateName(state);
          string channel=pair.Key=="input"&&!master?Json.Text(Json.Get(device,"inputTransport"),"selected"):"";
          if(channel=="bluetooth"||channel=="network"){
            var glyph=WindowShell.Glyph(channel=="bluetooth"?"bluetooth":"wifi",11);
            ((System.Windows.Shapes.Path)((Viewbox)glyph).Child).Stroke=MainWindow.Brush(Color(state));
            var badge=new Border{Width=16,Height=16,CornerRadius=new CornerRadius(8),Background=MainWindow.Brush("#FAFCFF"),
              HorizontalAlignment=HorizontalAlignment.Right,VerticalAlignment=VerticalAlignment.Bottom,
              Margin=new Thickness(0,0,3,3),Child=glyph,IsHitTestVisible=false};
            visual.Children.Add(badge);
            text+=" · "+(channel=="bluetooth"?Locale.T("蓝牙"):"Wi-Fi");
          }
          cell.Button.ToolTip=text;System.Windows.Automation.AutomationProperties.SetName(cell.Button,card.Name+" · "+text);if(!cell.Pending){cell.Toggle.IsChecked=Json.Text(cell.Data,"app")=="已开启" && (card.Local||Json.Text(cell.Data,"consent")=="已允许");cell.Toggle.IsEnabled=card.Online&&Json.Text(cell.Data,"system")=="已就绪";}}
      }
      foreach(string id in cards.Keys.Where(id=>!seen.Contains(id)).ToArray()){content.Children.Remove(cards[id].View);cards.Remove(id);}
      for(int index=0;index<order.Count;index++){
        var view=order[index].View;
        if(content.Children[index]==view)continue;
        content.Children.Remove(view);content.Children.Insert(index,view);
      }
      status.Text=Locale.T("查看设备能力与权限，点击图标进行管理");FocusRequested();
    }
  }

  public partial class MainWindow {
    Window hidGuideWindow;
    string hidGuideShown="";
    static void OpenBluetoothSettings() {
      System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo("ms-settings:bluetooth") { UseShellExecute=true });
    }
    public void OpenHidChannels() {
      var panel=Column();
      var window=Dialog(Locale.T("HID通道"),panel,490);
      var intro=new StackPanel{Margin=new Thickness(16,0,16,14)};
      var animation=AdbGuideWindow.Motion("hid-channel.svg");animation.Height=96;
      animation.Margin=new Thickness(0);intro.Children.Add(animation);
      var description=LText("HID 通过蓝牙传送键盘和鼠标输入。连接就绪时优先使用蓝牙。",13,"#657895");
      description.TextWrapping=TextWrapping.Wrap;description.TextAlignment=TextAlignment.Center;intro.Children.Add(description);
      panel.Children.Add(new Border{Child=intro,Background=Brush("#F1F5FD"),CornerRadius=new CornerRadius(14),Margin=new Thickness(0,0,0,14)});
      var enabled=new CheckBox{Content=UiLocale.Content("启用 HID 通道")};
      panel.Children.Add(new Border{Child=enabled,Background=Brush("#F6F8FC"),CornerRadius=new CornerRadius(10),
        Padding=new Thickness(12,10,12,10),Margin=new Thickness(0,0,0,14)});
      panel.Children.Add(LText("主设备",16));
      var master=new StackPanel();panel.Children.Add(master);
      panel.Children.Add(LText("被控设备",16));
      var targets=new StackPanel();
      panel.Children.Add(new ScrollViewer{Content=targets,MaxHeight=190,VerticalScrollBarVisibility=ScrollBarVisibility.Auto});
      var feedback=Text("",13,"#71819A");feedback.TextWrapping=TextWrapping.Wrap;panel.Children.Add(feedback);
      var done=LButton("完成",window.Close);done.HorizontalAlignment=HorizontalAlignment.Right;
      done.Margin=new Thickness(0,8,0,0);panel.Children.Add(done);
      bool busy=false,closed=false;
      string snapshot="";
      Func<Task> load=async()=>{
        var result=await Client.Call("group-hid-status");
        if(closed)return;
        var info=Json.Get(result,"master");
        var rows=(Json.Get(result,"targets") as IEnumerable??new object[0]).Cast<object>().ToArray();
        enabled.IsChecked=Json.Flag(info,"enabled");
        enabled.IsEnabled=Json.Flag(info,"local");
        string key=new System.Web.Script.Serialization.JavaScriptSerializer().Serialize(new { info, rows });
        if(key==snapshot)return;
        snapshot=key;
        master.Children.Clear();targets.Children.Clear();
        if(info==null)master.Children.Add(LText("请先选择组内主控设备"));
        else {
          bool local=Json.Flag(info,"local"),ready=Json.Flag(info,"ready");
          var host=HidDeviceRow(info,ready,ready?"已就绪":"蓝牙未就绪",()=>{
            if(!local){feedback.Text=Locale.T("请在主设备设置 HID 通道");return;}
            ShowHidMasterGuide(window);
          });
          master.Children.Add(host);
          foreach(var row in rows){
            string id=Json.Text(row,"id");
            bool connected=Json.Flag(row,"connected"),online=Json.Flag(row,"online");
            targets.Children.Add(HidDeviceRow(row,connected,connected?"已连接":online?"未连接":"离线",()=>Run(async()=>{
              if(connected)return;
              if(!local){feedback.Text=Locale.T("请在主设备设置 HID 通道");return;}
              try { await Client.Call("group-hid-guide-open",new{id});ShowPermissionTargetReminder(Json.Text(row,"name")); }
              catch(Exception error) { feedback.Text=DisplayError(error.Message); }
            })));
          }
        }
        if(targets.Children.Count==0) {
          var empty=LText("暂无组内设备",13,"#71819A");
          empty.Margin=new Thickness(0,6,0,3);targets.Children.Add(empty);
        }
      };
      Func<Task> poll=async()=>{if(busy||closed)return;busy=true;try{await load();}catch(Exception error){feedback.Text=DisplayError(error.Message);}finally{busy=false;}};
      enabled.Click+=delegate{Run(async()=>{
        if(busy)return;
        busy=true;try{await Client.Call("group-hid-enable",new{enabled=enabled.IsChecked==true});await load();}
        catch(Exception error){feedback.Text=DisplayError(error.Message);}
        finally{busy=false;}
      });};
      var timer=new DispatcherTimer{Interval=TimeSpan.FromSeconds(2)};
      timer.Tick+=async delegate{await poll();};
      window.Loaded+=delegate{timer.Start();Run(poll);};
      window.Closed+=delegate{closed=true;timer.Stop();};
      window.Show();
    }
    Button HidDeviceRow(object data,bool ready,string state,Action action) {
      var button=Button("",action);button.Margin=new Thickness(0,5,0,7);button.HorizontalContentAlignment=HorizontalAlignment.Stretch;
      var row=new DockPanel();var glyph=WindowShell.Glyph("bluetooth",18);
      ((System.Windows.Shapes.Path)((Viewbox)glyph).Child).Stroke=Brush(ready?"#16864B":"#7D879A");
      glyph.Margin=new Thickness(0,0,8,0);DockPanel.SetDock(glyph,Dock.Left);row.Children.Add(glyph);
      var label=LText(state,12,ready?"#16864B":"#7D879A");DockPanel.SetDock(label,Dock.Right);row.Children.Add(label);
      row.Children.Add(Text(Json.Text(data,"name")));button.Content=row;return button;
    }
    void ShowHidMasterGuide(Window owner) {
      var panel=Column();var window=Dialog(Locale.T("开启蓝牙权限"),panel,440);window.Owner=owner;
      var hint=LText("请开启蓝牙，并在系统设置中允许 LanLink 使用蓝牙。");
      hint.TextWrapping=TextWrapping.Wrap;panel.Children.Add(hint);
      var feedback=Text("",12,"#D73C49");feedback.TextWrapping=TextWrapping.Wrap;panel.Children.Add(feedback);
      panel.Children.Add(LButton("打开蓝牙设置",()=>Run(async()=>{
        try {
          var state=await Client.Call("group-hid-master-open");
          if(Json.Flag(state,"ready"))window.Close();
          else OpenBluetoothSettings();
        } catch(Exception error){feedback.Text=DisplayError(error.Message);}
      })));
      panel.Children.Add(LButton("完成",window.Close));window.Show();
    }
    void RefreshHidGuide() {
      var guide=Json.Get(Json.Get(State,"inputTransport"),"guide");
      string id=Json.Text(guide,"requestId");
      if(hidGuideWindow!=null && id!=hidGuideShown){hidGuideWindow.Close();hidGuideWindow=null;}
      if(id==""||id==hidGuideShown)return;
      hidGuideShown=id;
      var panel=Column();var window=Dialog(Locale.T("连接 HID 通道"),panel,460);hidGuideWindow=window;
      bool direct=Json.Flag(guide,"direct");
      var hint=Text(Locale.T(direct?"点击连接后，按系统提示确认与 {0} 的蓝牙配对。":"请在本机的系统蓝牙设置中连接 {0}。").Replace("{0}",Json.Text(guide,"name")));
      hint.TextWrapping=TextWrapping.Wrap;panel.Children.Add(hint);
      panel.Children.Add(LButton("打开蓝牙设置",OpenBluetoothSettings));
      var status=Text("",13,"#D73C49");status.TextWrapping=TextWrapping.Wrap;panel.Children.Add(status);
      bool complete=false;
      if(direct){
        var connect=LButton("连接",()=>{});
        connect.Click+=delegate{Run(async()=>{
          connect.IsEnabled=false;
          status.Text=Locale.T("正在连接蓝牙");
          try{await Client.Call("group-hid-guide-connect",new{requestId=id});complete=true;window.Close();}
          catch(Exception error){status.Text=DisplayError(error.Message);}
          finally{connect.IsEnabled=true;}
        });};
        panel.Children.Add(connect);
      }
      var confirm=LButton("已连接，确认",()=>{});
      confirm.Click+=delegate{Run(async()=>{
        confirm.IsEnabled=false;
        try{await Client.Call("group-hid-guide-finish",new{requestId=id});complete=true;window.Close();}
        catch(Exception error){status.Text=DisplayError(error.Message);}
        finally{confirm.IsEnabled=true;}
      });};
      panel.Children.Add(confirm);
      window.Closed+=delegate{if(hidGuideWindow==window)hidGuideWindow=null;if(!complete)Run(async()=>{try{await Client.Call("group-hid-guide-finish",new{requestId=id,cancel=true});}catch{}});};
      window.Show();window.Activate();
    }
    Window permissionGuideWindow;string permissionGuideId="";
    public async Task OpenPermissionGuide(string id,string key,string name){var result=await Client.Call("permission-guide-open",new{id=id,permission=key});await Refresh();if(Json.Flag(result,"remote"))ShowPermissionTargetReminder(name);}
    public void ShowPermissionTargetReminder(string name){var panel=Column();panel.Children.Add(LText("请到目标设备操作",22));panel.Children.Add(Text(name,16));var diagram=new Grid{Height=120,Margin=new Thickness(0,20,0,12)};diagram.ColumnDefinitions.Add(new ColumnDefinition());diagram.ColumnDefinitions.Add(new ColumnDefinition());diagram.ColumnDefinitions.Add(new ColumnDefinition());var local=WindowShell.Glyph("screen",46);local.HorizontalAlignment=HorizontalAlignment.Center;diagram.Children.Add(local);var target=WindowShell.Glyph("screen",46);target.HorizontalAlignment=HorizontalAlignment.Center;Grid.SetColumn(target,2);diagram.Children.Add(target);((System.Windows.Shapes.Path)((Viewbox)target).Child).Stroke=Brush("#4169CE");var arrow=WindowShell.Glyph("arrow-right",30);((System.Windows.Shapes.Path)((Viewbox)arrow).Child).Stroke=Brush("#4169CE");Grid.SetColumn(arrow,1);diagram.Children.Add(arrow);if(SystemParameters.ClientAreaAnimation){var motion=new TranslateTransform();arrow.RenderTransform=motion;motion.BeginAnimation(TranslateTransform.XProperty,new DoubleAnimation(-15,15,TimeSpan.FromSeconds(1.2)){RepeatBehavior=RepeatBehavior.Forever,AutoReverse=false});arrow.BeginAnimation(OpacityProperty,new DoubleAnimation(0.3,1,TimeSpan.FromSeconds(1.2)){RepeatBehavior=RepeatBehavior.Forever,AutoReverse=true});}panel.Children.Add(diagram);var text=Text(Locale.T("权限引导已在该设备打开，请切换观看设备并按提示完成授权。"),14,"#70809C");text.TextWrapping=TextWrapping.Wrap;panel.Children.Add(text);var window=Dialog(Locale.T("切换观看设备"),panel,460);panel.Children.Add(LButton("我知道了",()=>window.Close()));window.Closed+=delegate{arrow.BeginAnimation(OpacityProperty,null);if(arrow.RenderTransform is TranslateTransform)((TranslateTransform)arrow.RenderTransform).BeginAnimation(TranslateTransform.XProperty,null);};window.Show();window.Activate();}
    void RefreshPermissionGuide(){var request=Json.Get(State,"permissionNotice");string id=Json.Text(request,"requestId");if(permissionGuideWindow!=null && (id!=permissionGuideId || !Json.Flag(request,"explicit"))){var old=permissionGuideWindow;permissionGuideWindow=null;old.Close();}if(!Json.Flag(request,"explicit") || id=="" || permissionGuideWindow!=null || id==permissionGuideId)return;if(permissionWindow!=null)permissionWindow.Close();
      string key=Json.Text(request,"permission"),label=key=="input"?"键鼠控制":key=="screen"?"屏幕查看":key=="clipboard"?"剪贴板与文件":"远程关机";var panel=Column();panel.Children.Add(LText("开启"+label,22));var icon=DevicePermissionsPage.Icon(key,"#4169CE",42);icon.Margin=new Thickness(0,18,0,18);panel.Children.Add(icon);var intro=Text((Json.Text(request,"id")==Json.Text(State,"id")?Locale.T("请在本机完成权限设置"):Locale.T("来自 ")+Json.Text(request,"name")+Locale.T(" 的权限设置请求")),14);intro.TextWrapping=TextWrapping.Wrap;panel.Children.Add(intro);var hint=LText("开启后允许请求设备使用此功能。系统权限仍需在本机完成授权。",12,"#70809C");hint.Margin=new Thickness(0,12,0,18);panel.Children.Add(hint);var feedback=Text("",12,"#B76370");feedback.TextWrapping=TextWrapping.Wrap;panel.Children.Add(feedback);var window=Dialog(Locale.T("开启协作权限"),panel,460);permissionGuideWindow=window;permissionGuideId=id;var enable=LButton("开启权限",()=>{});enable.Click+=async delegate{enable.IsEnabled=false;try{var result=await Client.Call("permission-guide-enable",new{requestId=id});await Refresh();if(Json.Text(result,"warning")!=""){SettingsResult(result,Locale.T("权限已保存"));return;}if(!Json.Flag(Json.Get(State,"capabilities"),key)){feedback.Text=Locale.T("系统能力尚未就绪，请检查设备服务及系统权限后重试。");return;}window.Close();NotifyTransient(Locale.T("权限已开启"));}catch(Exception ex){feedback.Text=DisplayError(ex.Message);}finally{enable.IsEnabled=true;}};enable.Style=(Style)FindResource("Primary");panel.Children.Add(enable);panel.Children.Add(LButton("稍后",()=>window.Close()));window.Closed+=delegate{if(permissionGuideWindow==window){permissionGuideWindow=null;Run(async()=>{await Client.Call("permission-dismiss",new{requestId=id});});}};window.Show();window.Activate();
    }
  }
}
