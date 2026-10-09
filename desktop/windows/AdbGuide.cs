using System;
using System.Collections;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Threading;
using System.Xml;

namespace LanLink.Desktop {
  // The bundled SVG paths are rendered and animated by WPF, without a browser.
  public sealed class AdbGuideWindow : Window {
    readonly MainWindow main; readonly object request;
    readonly DispatcherTimer timer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(2) };
    readonly TextBlock status = MainWindow.LText("等待手机连接…", 13, "#677C98");
    readonly ListBox phones = new ListBox { MinWidth=210, MinHeight=42, MaxHeight=110, BorderThickness=new Thickness(0), Background=MainWindow.Brush("#FFFFFF"), DisplayMemberPath="Label", SelectedValuePath="Serial" };
    readonly TextBlock empty = MainWindow.LText("等待已信任的 USB 设备",12,"#8493AA");
    readonly Button scan, authorize;
    readonly StackPanel normalGuide, revokedGuide;
    bool revoked, restorePending;
    bool checking, busy, completed, closed, matched; int checks; public string RequestId { get { return Json.Text(request,"requestId"); } }
    public string DeviceId { get { return Json.Text(request,"id"); } }
    sealed class Phone { public string Label { get; set; } public string Serial { get; set; } }
    public AdbGuideWindow(MainWindow owner, object notice, bool preview=false) {
      main=owner; request=notice; Title=Locale.T("连接手机 · ADB 引导"); Width=570; SizeToContent=SizeToContent.Height;
      MaxHeight=SystemParameters.WorkArea.Height-40; WindowStartupLocation=WindowStartupLocation.CenterScreen;
      Resources=owner.Resources; FontFamily=owner.FontFamily; ResizeMode=ResizeMode.NoResize;
      var container=new StackPanel { Margin=new Thickness(28,12,28,26) };
      var panel=new StackPanel();normalGuide=panel;container.Children.Add(panel);
      var title=MainWindow.LText("连接一次，自由控制",24); title.FontWeight=FontWeights.SemiBold; panel.Children.Add(title);
      AddText(panel,Json.Flag(notice,"setupOnly")?Locale.T("设备未就绪，请连接手机完成 ADB 配置。"):Locale.T("为「")+Json.Text(notice,"name")+Locale.T("」启用 ADB 后，自动继续")+(Json.Flag(notice,"capture")?Locale.T("键鼠切换"):Locale.T("桌面控制"))+"。",13);
      panel.Children.Add(Motion());
      AddText(panel,Locale.T("01   开启 USB 调试"),15);
      AddText(panel,Locale.T("手机设置 → 关于手机，连续点击系统版本号开启开发者选项，再进入开发者选项打开「USB 调试」。"),13);
      AddText(panel,Locale.T("02   用数据线连接电脑"),15);
      AddText(panel,Locale.T("解锁手机，允许「USB 调试」，勾选「始终允许此电脑」。保持手机 LanLink 在线；同组设备默认可直接使用。"),13);
      AddText(panel,Locale.T("03   自动识别，点击连接"),15);
      AddText(panel,Locale.T("小米等机型如提示系统输入失败，还需开启「USB 调试（安全设置）」。"),12);
      var row=new DockPanel { Margin=new Thickness(0,12,0,12) };
      scan=MainWindow.LButton("检测连接",()=>main.Run(Scan)); DockPanel.SetDock(scan,Dock.Right); row.Children.Add(scan);
      var list=new Grid {MinHeight=42};empty.Margin=new Thickness(12,0,12,0);empty.IsHitTestVisible=false;list.Children.Add(empty);
      row.Children.Add(new Border { Child=list,CornerRadius=new CornerRadius(10),Background=MainWindow.Brush("#FFFFFF"),Padding=new Thickness(4),Margin=new Thickness(0,0,10,0) });panel.Children.Add(row);
      status.TextWrapping=TextWrapping.Wrap; status.LineHeight=21;
      var tip=new StackPanel(); AddText(tip,Locale.T("每次开机，只需连接一次"),14);
      AddText(tip,Locale.T("助手就绪后，同组其他电脑可直接使用，无需重复连接数据线。手机重启后，连接已信任的电脑恢复助手即可。系统 USB 调试信任按电脑分别保存，新电脑恢复助手时仍需手机确认。"),12);
      panel.Children.Add(new Border { Background=MainWindow.Brush("#EDF4FF"),CornerRadius=new CornerRadius(14),Padding=new Thickness(16,6,16,12),Margin=new Thickness(0,16,0,18),Child=tip });
      revokedGuide=new StackPanel {Visibility=Visibility.Collapsed};
      AddText(revokedGuide,Locale.T("请在手机上允许 ADB 授权"),24);
      revokedGuide.Children.Add(Motion("adb-allow.svg"));
      AddText(revokedGuide,Locale.T("之前的 ADB 授权已被手机解除。请在手机 LanLink 弹窗中点击「允许 ADB 授权」。"),15);
      AddText(revokedGuide,Locale.T("保持数据线连接。允许后将重新检查并恢复连接，无需重复开启系统 USB 调试。"),13);
      container.Children.Add(revokedGuide);container.Children.Add(status);
      var buttons=new StackPanel { Orientation=Orientation.Horizontal,HorizontalAlignment=HorizontalAlignment.Right };
      buttons.Children.Add(MainWindow.LButton("暂不连接",()=>Close()));
      authorize=MainWindow.LButton("连接并继续",()=>main.Run(Authorize)); authorize.Style=(Style)FindResource("Primary"); authorize.Margin=new Thickness(12,12,0,0); authorize.IsEnabled=false; buttons.Children.Add(authorize); container.Children.Add(buttons);
      phones.SelectionChanged+=delegate { authorize.IsEnabled=!busy && phones.SelectedItem!=null; };
      WindowShell.Wrap(this,new ScrollViewer { Content=container,VerticalScrollBarVisibility=ScrollBarVisibility.Auto },false);
      SetRevoked(Json.Flag(notice,"revoked"));
      timer.Tick+=async delegate { await Check(); };
      Loaded+=async delegate { if(!preview) { timer.Start(); await Scan(); await Check(); } };
      Closed+=delegate { closed=true; timer.Stop(); if(!completed && !preview) { main.CancelFeature(DeviceId); foreach(var remote in Application.Current.Windows.OfType<RemoteWindow>().Where(w=>w.DeviceId==DeviceId)) remote.CancelAdbGuide(); main.Run(async()=> { await main.Client.Call("adb-guide-cancel",new { requestId=RequestId }); }); } };
    }
    static void AddText(StackPanel panel,string value,int size) { var t=MainWindow.Text(value,size,size>=15?"#233957":"#657895"); t.TextWrapping=TextWrapping.Wrap;t.LineHeight=size+8;t.Margin=new Thickness(0,8,0,0);panel.Children.Add(t); }
    void SetRevoked(bool value){revoked=value;if(value){restorePending=true;status.Text=Locale.T("等待手机允许 ADB 授权…");}normalGuide.Visibility=value?Visibility.Collapsed:Visibility.Visible;revokedGuide.Visibility=value?Visibility.Visible:Visibility.Collapsed;authorize.Visibility=value?Visibility.Collapsed:Visibility.Visible;}
    public static FrameworkElement Motion(string asset="adb-connect.svg") {
      var canvas=new Canvas { Width=400,Height=110 };
      var doc=new XmlDocument { XmlResolver=null }; doc.Load(Path.Combine(MainWindow.Root,"src/assets/"+asset));
      foreach(XmlElement element in doc.GetElementsByTagName("path")) {
        var shape=new System.Windows.Shapes.Path { Data=Geometry.Parse(element.GetAttribute("d")),Stroke=MainWindow.Brush(element.GetAttribute("stroke")),StrokeThickness=3,StrokeStartLineCap=PenLineCap.Round,StrokeEndLineCap=PenLineCap.Round,StrokeLineJoin=PenLineJoin.Round };
        canvas.Children.Add(shape);
        if(element.GetAttribute("id")=="signal" && SystemParameters.ClientAreaAnimation) { var move=new TranslateTransform();shape.RenderTransform=move;move.BeginAnimation(TranslateTransform.XProperty,new DoubleAnimation(0,80,TimeSpan.FromSeconds(1.8)){RepeatBehavior=RepeatBehavior.Forever});shape.BeginAnimation(OpacityProperty,new DoubleAnimation(.25,1,TimeSpan.FromSeconds(.9)){AutoReverse=true,RepeatBehavior=RepeatBehavior.Forever}); }
      }
      return new Viewbox { Child=canvas,Height=110,Margin=new Thickness(0,12,0,0),IsHitTestVisible=false };
    }
    public void End() { completed=true;Close(); }
    async Task Scan() {
      if(busy || closed || revoked)return; busy=true; scan.IsEnabled=authorize.IsEnabled=false;
      try { var result=await main.Client.Call("adb-match",new {id=DeviceId}); if(closed)return;matched=Json.Flag(result,"connected");empty.Text=matched?Locale.T("已识别组内手机 · 身份核对通过"):Locale.T("等待手机通过数据线连接");status.Text=Json.Text(result,"message");
      } catch(Exception ex) { status.Text=MainWindow.DisplayError(ex.Message); }
      finally {busy=false;scan.IsEnabled=true;authorize.IsEnabled=matched;}
    }
    async Task Authorize() {
      if(busy || !matched || closed)return; busy=true;scan.IsEnabled=authorize.IsEnabled=phones.IsEnabled=false;
      status.Text=Locale.T("正在检查手机助手并配置连接…");
      try {
        for(int attempt=0; ; attempt++) {
          try { await main.Client.Call("adb-connect",new {id=DeviceId});break; }
          catch(Exception ex) { if(!ex.Message.StartsWith("CONSENT_PENDING:") || attempt>=59)throw;status.Text=Locale.T("请在手机 LanLink 中同意此电脑的请求，同意后会自动继续…"); }
          await Task.Delay(2000);if(closed)return;
        }
        status.Text=Locale.T("授权完成，正在检查连接…");
      }
      catch(Exception ex) { status.Text=ex.Message.StartsWith("CONSENT_PENDING:")?Locale.T("请先在手机 LanLink 中同意请求，然后再次点击「连接并继续」。"):MainWindow.DisplayError(ex.Message); }
      finally {busy=false;scan.IsEnabled=phones.IsEnabled=true;authorize.IsEnabled=matched;}
      await Check();
    }
    async Task Check() {
      if(checking || busy || closed)return;checking=true;
      try { var result=await main.Client.Call("adb-guide-status",new { requestId=RequestId });if(closed)return;
        if(Json.Flag(result,"ended")){main.NotifyTransient(string.IsNullOrWhiteSpace(Json.Text(result,"message"))?Locale.T("连接授权已结束，可在安卓授权页重新发起。"):Json.Text(result,"message"));End();return;}
        SetRevoked(Json.Flag(result,"revoked"));
        if(revoked){status.Text=Json.Text(result,"message");return;}
        if(restorePending && !Json.Flag(result,"ready")){await Scan();if(matched){restorePending=false;await Authorize();}return;}
        if(!Json.Flag(result,"ready")) { if(Json.Text(result,"message").Contains("暂时离线"))status.Text=Json.Text(result,"message");else if(++checks%3==0)await Scan(); }
        if(Json.Flag(result,"ready")) {
          var intent=await main.Client.Call("adb-guide-complete",new {requestId=RequestId});if(closed && !completed)return; End();
          if(Json.Flag(intent,"capture")) await main.Client.Call("control",new {id=DeviceId,edge=Json.Text(intent,"edge"),capture=true,newSession=Json.Flag(intent,"newSession"),screen=Json.Flag(intent,"screen")});
          else if(Json.Flag(intent,"setupOnly"))main.ShowAdbHub();
          else if(Json.Flag(intent,"feature"))await main.ResumeFeature(DeviceId);
          else { var remote=Application.Current.Windows.OfType<RemoteWindow>().FirstOrDefault(w=>w.DeviceId==DeviceId);if(remote!=null)remote.Activate(); }
        }
      } catch(Exception ex) { if(!closed)status.Text=MainWindow.DisplayError(ex.Message);else main.Notify(ex.Message); }
      finally {checking=false;}
    }
  }
}
