using System;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Threading;
namespace LanLink.Desktop {
  public sealed class PermissionRequestWindow : Window {
    readonly MainWindow main; readonly object notice; bool done,closed,checking;
    readonly DispatcherTimer timer=new DispatcherTimer {Interval=TimeSpan.FromSeconds(1)};
    readonly TextBlock message=MainWindow.LText("请在被控设备允许本设备控制。",14,"#657895");
    public string RequestId {get{return Json.Text(notice,"requestId");}}
    public PermissionRequestWindow(MainWindow owner,object value) {
      main=owner;notice=value;Title=Locale.T("等待设备授权");Width=440;SizeToContent=SizeToContent.Height;Resources=owner.Resources;FontFamily=owner.FontFamily;WindowStartupLocation=WindowStartupLocation.CenterScreen;
      var panel=MainWindow.Column();panel.Children.Add(MainWindow.Text(Locale.T("请在「")+Json.Text(value,"name")+Locale.T("」上确认"),21));message.TextWrapping=TextWrapping.Wrap;message.Margin=new Thickness(0,18,0,24);panel.Children.Add(message);panel.Children.Add(MainWindow.LButton("取消",()=>Close()));WindowShell.Wrap(this,panel,false);
      timer.Tick+=async delegate{await Check();};Loaded+=delegate{timer.Start();};
      Closed+=delegate{closed=true;timer.Stop();if(!done){main.CancelFeature(Json.Text(notice,"id"));foreach(var remote in Application.Current.Windows.OfType<RemoteWindow>().Where(w=>w.DeviceId==Json.Text(notice,"id")))remote.CancelAdbGuide();main.Run(async()=>{await main.Client.Call("access-cancel",new {requestId=RequestId});});}};
    }
    public void End(){done=true;Close();}
    async Task Check(){if(checking||closed)return;checking=true;var client=main.Client;try{
      var state=await client.Call("access-status",new {requestId=RequestId});if(closed || main.Client!=client || !main.IsCurrentAccessRequest(RequestId))return;
      if(Json.Flag(state,"ended")){main.NotifyTransient(string.IsNullOrWhiteSpace(Json.Text(state,"message"))?Locale.T("授权请求已结束，请从设备操作重新发起。"):Json.Text(state,"message"));Close();return;}message.Text=Json.Text(state,"message");
      if(Json.Flag(state,"ready")){var intent=await client.Call("access-complete",new {requestId=RequestId});if((closed && !done) || main.Client!=client || !main.IsCurrentAccessRequest(RequestId))return;End();
        if(Json.Flag(intent,"capture"))await client.Call("control",new {id=Json.Text(intent,"id"),capture=true,edge=Json.Text(intent,"edge"),newSession=true});else {await main.ResumeFeature(Json.Text(notice,"id"));if(main.Client==client && main.IsCurrentAccessRequest(RequestId))main.NotifyTransient(Locale.T("授权已完成，可以使用获准的功能"));}
      }
    }catch(Exception e){if(!closed)message.Text=MainWindow.DisplayError(e.Message);}finally{checking=false;}}
  }
}
