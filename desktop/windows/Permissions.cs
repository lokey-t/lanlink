using System;
using System.Collections;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Threading;

namespace LanLink.Desktop {
  public sealed class DevicePermissionsPage : Grid {
    readonly MainWindow main;
    readonly StackPanel content = new StackPanel();
    readonly TextBlock status = MainWindow.LText("读取组内设备权限…", 12, "#7B879D");
    readonly DispatcherTimer timer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(4) };
    readonly Dictionary<string, DeviceCard> cards = new Dictionary<string, DeviceCard>();
    bool busy, hasError; int revision; string focusDevice;
    public CheckBox InputToggle { get; private set; }
    sealed class PermissionLine {
      public Grid View; public TextBlock Label, Detail, Badge; public CheckBox Toggle; public bool Pending;
    }
    sealed class DeviceCard {
      public Border View; public StackPanel Body, Rows, Inbound; public TextBlock Heading, Message, Engine, Direction, LocalStatus;
      public Button Adb; public Dictionary<string,PermissionLine> Permissions=new Dictionary<string,PermissionLine>();
      public Dictionary<string,CheckBox> Grants=new Dictionary<string,CheckBox>(); public HashSet<string> Pending=new HashSet<string>();
      public string Id, Name; public bool Local;
    }
    public DevicePermissionsPage(MainWindow owner, Action back) {
      main = owner;
      RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto }); RowDefinitions.Add(new RowDefinition());
      var header = new DockPanel { Margin = new Thickness(0,0,0,18) };
      var close = MainWindow.LButton("返回设备", back); DockPanel.SetDock(close,Dock.Right); header.Children.Add(close);
      var refresh=MainWindow.LButton("刷新",()=>main.Run(()=>Refresh(true)));refresh.Margin=new Thickness(0,0,10,0);DockPanel.SetDock(refresh,Dock.Right);header.Children.Add(refresh);
      var heading = new StackPanel(); heading.Children.Add(MainWindow.LText("本组设备权限", 25)); status.Margin = new Thickness(0,8,0,0);status.TextWrapping=TextWrapping.Wrap; heading.Children.Add(status); header.Children.Add(heading); Children.Add(header);
      var scroll = new ScrollViewer { Content = content, VerticalScrollBarVisibility = ScrollBarVisibility.Auto }; Grid.SetRow(scroll,1); Children.Add(scroll);
      timer.Tick += async delegate { if (IsVisible) await Refresh(); };
      IsVisibleChanged += async delegate { if (IsVisible) { timer.Start(); await Refresh(); } else timer.Stop(); };
    }
    public Task Refresh() { return Refresh(false); }
    async Task Refresh(bool explicitRetry) {
      if (busy) return; busy = true; int version=revision;
      try { var result=await main.Client.Call("group-permissions"); if(version==revision)Display(result); if(explicitRetry){hasError=false;status.Text=Locale.T("权限已刷新。开关立即保存；此设备 → 本机表示对方可使用这台电脑。");} }
      catch (Exception error) { hasError=true;status.Text = MainWindow.DisplayError(error.Message)+Locale.T(" 点击刷新重试。"); }
      finally { busy = false; }
    }
    public void FocusDevice(string id) { focusDevice=id;BringRequestedIntoView(); }
    void BringRequestedIntoView() { DeviceCard card;if(focusDevice!=null && cards.TryGetValue(focusDevice,out card)){card.View.BringIntoView();var toggle=card.Grants.Values.FirstOrDefault();if(toggle!=null)toggle.Focus();focusDevice=null;} }
    DeviceCard CreateCard(string id, bool local) {
      var card=new DeviceCard {Id=id,Local=local,Body=new StackPanel(),Rows=new StackPanel(),Inbound=new StackPanel(),Heading=MainWindow.Text("",16),Message=MainWindow.Text("",12,"#8B7897"),Engine=MainWindow.Text("",12,"#7B879D"),Direction=MainWindow.Text("",13,"#7B879D"),LocalStatus=MainWindow.Text("",12,"#7B879D")};
      card.Body.Children.Add(card.Heading);card.Message.TextWrapping=TextWrapping.Wrap;card.Message.Margin=new Thickness(0,8,0,0);card.Body.Children.Add(card.Message);
      card.Direction.TextWrapping=TextWrapping.Wrap;card.Direction.Margin=new Thickness(0,16,0,0);card.Body.Children.Add(card.Direction);card.Body.Children.Add(card.Rows);
      card.Engine.Margin=new Thickness(0,12,0,0);card.Engine.TextWrapping=TextWrapping.Wrap;card.Body.Children.Add(card.Engine);
      if(!local){
        var heading=MainWindow.LText("此设备 → 本机 · 允许对方使用这台电脑",14);heading.TextWrapping=TextWrapping.Wrap;heading.Margin=new Thickness(0,22,0,8);card.Inbound.Children.Add(heading);
        card.Inbound.Children.Add(MainWindow.LText("每项立即保存。关闭后重连不会恢复，其他成员不受影响。",12,"#7B879D"));card.Body.Children.Add(card.Inbound);
      }
      card.Adb=MainWindow.LButton("本机 → 安卓设备连接授权",()=>main.Run(async()=>{card.Adb.IsEnabled=false;try{await main.Client.Call("adb-guide-open",new{id=card.Id});card.LocalStatus.Text=Locale.T("授权引导已打开，请按提示操作手机。");}catch(Exception error){card.LocalStatus.Text=MainWindow.DisplayError(error.Message);}finally{card.Adb.IsEnabled=true;}}));card.Adb.Margin=new Thickness(0,14,0,0);card.Adb.HorizontalAlignment=HorizontalAlignment.Left;card.Body.Children.Add(card.Adb);
      card.LocalStatus.TextWrapping=TextWrapping.Wrap;card.LocalStatus.Margin=new Thickness(0,12,0,0);card.Body.Children.Add(card.LocalStatus);
      card.View=new Border {Child=card.Body,Padding=new Thickness(22),CornerRadius=new CornerRadius(20),Background=MainWindow.Brush("#FFFFFF"),Margin=new Thickness(0,0,0,16)};cards.Add(id,card);content.Children.Add(card.View);return card;
    }
    PermissionLine CreatePermission(DeviceCard card,string key) {
      var row=new PermissionLine{View=new Grid{Margin=new Thickness(0,14,0,0)},Label=MainWindow.Text("",14),Detail=MainWindow.Text("",12,"#7B879D"),Badge=MainWindow.Text("",12,"#887B90")};
      row.View.ColumnDefinitions.Add(new ColumnDefinition());row.View.ColumnDefinitions.Add(new ColumnDefinition{Width=new GridLength(150)});
      var words=new StackPanel();words.Children.Add(row.Label);row.Detail.Margin=new Thickness(0,5,0,0);row.Detail.TextWrapping=TextWrapping.Wrap;words.Children.Add(row.Detail);row.View.Children.Add(words);row.Badge.HorizontalAlignment=HorizontalAlignment.Right;Grid.SetColumn(row.Badge,1);row.View.Children.Add(row.Badge);
      if(card.Local){row.Toggle=new CheckBox{Content=UiLocale.Content("允许本组设备使用此功能 · 立即生效"),Margin=new Thickness(0,8,0,0)};words.Children.Add(row.Toggle);row.Toggle.Click+=delegate{main.Run(()=>ChangePermission(card,row,key));};if(key=="input")InputToggle=row.Toggle;}
      card.Permissions.Add(key,row);card.Rows.Children.Add(row.View);return row;
    }
    async Task ChangePermission(DeviceCard card,PermissionLine row,string key) {
      if(row.Pending)return; bool allowed=row.Toggle.IsChecked==true;row.Pending=true;row.Toggle.IsEnabled=false;revision++;
      try{var result=await main.Client.Call("settings",new{permissions=new Dictionary<string,bool>{{key,allowed}}});card.LocalStatus.Text=main.SettingsResult(result,Locale.T("本机功能开关已立即保存。"));hasError=false;}
      catch(Exception error){row.Toggle.IsChecked=!allowed;card.LocalStatus.Text=MainWindow.DisplayError(error.Message)+Locale.T(" 开关未更改，请重试。");hasError=true;}
      finally{row.Pending=false;row.Toggle.IsEnabled=true;revision++;}
      await Refresh();
    }
    async Task ChangeGrant(DeviceCard card,string key,CheckBox toggle) {
      if(card.Pending.Contains(key))return;bool allowed=toggle.IsChecked==true;card.Pending.Add(key);toggle.IsEnabled=false;revision++;
      try{var result=await main.Client.Call("consent-set",new{id=card.Id,permissions=new Dictionary<string,bool>{{key,allowed}}});card.LocalStatus.Text=main.SettingsResult(result,(allowed?Locale.T("已允许“"):Locale.T("已禁止“"))+card.Name+Locale.T("”使用本机的此项能力。"));hasError=false;}
      catch(Exception error){toggle.IsChecked=!allowed;card.LocalStatus.Text=MainWindow.DisplayError(error.Message)+Locale.T(" 权限未更改，请重试。");hasError=true;}
      finally{card.Pending.Remove(key);toggle.IsEnabled=true;revision++;}
      await Refresh();
    }
    public void Display(object result) {
      var seen=new HashSet<string>();var devices=Json.Get(result,"devices") as IEnumerable ?? new object[0];
      foreach(var device in devices){
        string id=Json.Text(device,"id");if(id=="")id="preview:"+Json.Text(device,"name");seen.Add(id);bool local=Json.Flag(device,"local");DeviceCard card;if(!cards.TryGetValue(id,out card))card=CreateCard(id,local);
        card.Name=Json.Text(device,"name");card.Heading.Text=card.Name+(local?Locale.T(" · 本机"):Json.Flag(device,"online")?Locale.T(" · 在线"):Locale.T(" · 离线"));card.Message.Text=Json.Text(device,"message");card.Message.Visibility=card.Message.Text==""?Visibility.Collapsed:Visibility.Visible;
        card.Direction.Text=local?Locale.T("本机功能总开关 · 影响所有组成员"):Locale.T("本机 → 此设备 · 查看对方允许这台电脑使用的功能");
        foreach(var permission in Json.Get(device,"permissions") as IEnumerable ?? new object[0]){
          string key=Json.Text(permission,"key");if(key=="")key=Json.Text(permission,"label");PermissionLine row;if(!card.Permissions.TryGetValue(key,out row))row=CreatePermission(card,key);
          row.Label.Text=Locale.T(Json.Text(permission,"label"));bool supported=Json.Flag(permission,"supported");row.Detail.Visibility=supported?Visibility.Visible:Visibility.Collapsed;
          row.Detail.Text=Locale.T("应用：")+Locale.T(Json.Text(permission,"app"))+Locale.T("   系统：")+Locale.T(Json.Text(permission,"system"))+(local?"":Locale.T("   对本机：")+Locale.T(Json.Text(permission,"consent")));row.Badge.Text=Locale.T(Json.Text(permission,"status"));
          if(row.Toggle!=null){row.Toggle.Visibility=supported?Visibility.Visible:Visibility.Collapsed;if(!row.Pending)row.Toggle.IsChecked=Json.Text(permission,"app")=="已开启";System.Windows.Automation.AutomationProperties.SetName(row.Toggle,Locale.T("本机允许本组设备使用")+row.Label.Text);}
        }
        foreach(var grant in Json.Get(device,"inboundPermissions") as IEnumerable ?? new object[0]){
          string key=Json.Text(grant,"key");CheckBox toggle;if(!card.Grants.TryGetValue(key,out toggle)){toggle=new CheckBox{Margin=new Thickness(0,8,0,0)};card.Grants.Add(key,toggle);card.Inbound.Children.Add(toggle);var target=toggle;toggle.Click+=delegate{main.Run(()=>ChangeGrant(card,key,target));};}
          toggle.Content=Locale.T("允许此设备使用本机的")+Locale.T(Json.Text(grant,"label"));System.Windows.Automation.AutomationProperties.SetName(toggle,card.Name+Locale.T(" → 本机 · ")+Locale.T(Json.Text(grant,"label")));if(!card.Pending.Contains(key))toggle.IsChecked=Json.Flag(grant,"allowed");
        }
        card.Engine.Text=Json.Text(Json.Get(device,"engine"),"message");card.Engine.Visibility=card.Engine.Text==""?Visibility.Collapsed:Visibility.Visible;
        card.Adb.Visibility=!local && Json.Text(device,"platform")=="android" && Json.Flag(device,"online")?Visibility.Visible:Visibility.Collapsed;
      }
      foreach(string id in cards.Keys.Where(id=>!seen.Contains(id)).ToArray()){content.Children.Remove(cards[id].View);cards.Remove(id);}
      if(!hasError)status.Text=Locale.T("自动刷新 · 开关立即保存 · 分别显示本机访问对方与对方访问本机的权限");
      BringRequestedIntoView();
    }
  }
}
