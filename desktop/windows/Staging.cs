using System;
using System.Collections;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Diagnostics;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using Microsoft.Win32;

namespace LanLink.Desktop {
  public sealed class StagingPage : Grid {
    readonly MainWindow main; readonly StackPanel list=new StackPanel(),outgoing=new StackPanel();
    readonly TextBlock status=MainWindow.Text("",12,"#788195"),empty=MainWindow.LText("暂存区是空的。收到文件后可选择“保存到…”或拖到文件夹。",14,"#788195"),sentEmpty=MainWindow.LText("暂无发送任务",14,"#788195");
    readonly Dictionary<string,StageCard> cards=new Dictionary<string,StageCard>(); readonly Dictionary<string,StageCard> sentCards=new Dictionary<string,StageCard>(); bool loading,preview;
    public int RowCount {get{return cards.Count;}} public void SetPreview(object data){preview=true;Display(data);}
    sealed class StageCard {public Border View;public TextBlock Name,Progress,Info,Error;public StackPanel Actions;public object Data;public string State,LocalError="";public bool Busy;}
    public StagingPage(MainWindow owner){
      main=owner;RowDefinitions.Add(new RowDefinition{Height=GridLength.Auto});RowDefinitions.Add(new RowDefinition());
      var top=new DockPanel{Margin=new Thickness(0,0,0,22)};var refresh=MainWindow.LButton("刷新",()=>main.Run(Refresh));DockPanel.SetDock(refresh,Dock.Right);top.Children.Add(refresh);var title=new StackPanel();title.Children.Add(MainWindow.LText("暂存文件",26));status.TextWrapping=TextWrapping.Wrap;status.Margin=new Thickness(0,8,0,0);title.Children.Add(status);top.Children.Add(title);Children.Add(top);
      var body=new StackPanel();body.Children.Add(MainWindow.LText("收到的文件",18));list.Margin=new Thickness(0,14,0,0);body.Children.Add(list);empty.TextWrapping=TextWrapping.Wrap;body.Children.Add(empty);var sentTitle=MainWindow.LText("发送任务",18);sentTitle.Margin=new Thickness(0,28,0,14);body.Children.Add(sentTitle);body.Children.Add(outgoing);body.Children.Add(sentEmpty);
      var scroll=new ScrollViewer{Content=body,VerticalScrollBarVisibility=ScrollBarVisibility.Auto};SetRow(scroll,1);Children.Add(scroll);
    }
    public static string Size(double size){return size<1024?size+" B":size<1048576?(size/1024).ToString("0.0")+" KB":size<1073741824?(size/1048576).ToString("0.0")+" MB":(size/1073741824).ToString("0.00")+" GB";}
    public async Task Refresh(){
      if(loading||preview)return;loading=true;
      try{var incoming=main.Client.Call("stage-list");var sent=main.Client.Call("stage-outgoing-list");await Task.WhenAll(incoming,sent);if(!preview){Display(await incoming);var data=await sent;DisplayRows(Json.Get(data,"outgoing") as IEnumerable ?? Json.Get(data,"files") as IEnumerable ?? data as IEnumerable ?? new object[0],true);}}
      catch(Exception ex){status.Text=MainWindow.DisplayError(ex.Message)+Locale.T(" 点击刷新重试。");}
      finally{loading=false;}
    }
    public void Display(object data){DisplayRows(Json.Get(data,"files") as IEnumerable??new object[0],false);}
    StageCard MakeCard(string id,bool sending){
      var card=new StageCard{ Name=MainWindow.Text("",17), Progress=MainWindow.Text("",12,"#7163C8"),Info=MainWindow.Text("",12,"#788195"),Error=MainWindow.Text("",12,"#B76370"),Actions=new StackPanel{Orientation=Orientation.Horizontal}};
      var content=new StackPanel();var head=new DockPanel();DockPanel.SetDock(card.Progress,Dock.Right);head.Children.Add(card.Progress);card.Name.TextWrapping=TextWrapping.Wrap;head.Children.Add(card.Name);content.Children.Add(head);card.Info.Margin=new Thickness(0,8,0,12);card.Info.TextWrapping=TextWrapping.Wrap;content.Children.Add(card.Info);content.Children.Add(card.Actions);card.Error.TextWrapping=TextWrapping.Wrap;card.Error.Margin=new Thickness(0,12,0,0);content.Children.Add(card.Error);
      card.View=new Border{Child=content,Background=MainWindow.Brush("#FFFFFF"),CornerRadius=new CornerRadius(20),Padding=new Thickness(22),Margin=new Thickness(0,0,0,14),BorderBrush=MainWindow.Brush("#E5EAF4"),BorderThickness=new Thickness(1)};
      (sending?sentCards:cards).Add(id,card);(sending?outgoing:list).Children.Add(card.View);return card;
    }
    static string StateLabel(object row,bool sending){
      string state=Json.Text(row,"state");double received=Json.Number(row,"received",0),size=Json.Number(row,"size",0);
      if(!sending && (state=="receiving" || state=="sending"))return Locale.Format("传输中 {0}%",Math.Min(100,Math.Max(0,100*received/Math.Max(1,size))).ToString("0"));
      return Locale.T(UiText.TransferState(state,sending,received,size));
    }
    void DisplayRows(IEnumerable data,bool sending){
      var entries=sending?sentCards:cards;var panel=sending?outgoing:list;var seen=new HashSet<string>();
      foreach(var row in data){string id=Json.Text(row,"id"),state=Json.Text(row,"state");seen.Add(id);StageCard card;if(!entries.TryGetValue(id,out card))card=MakeCard(id,sending);card.Data=row;card.Name.Text=Json.Text(row,"name");card.Progress.Text=StateLabel(row,sending);
        card.Info.Text=(sending?Locale.T("发送到 ")+Json.Text(row,"targetName"):Locale.T("来自 ")+Json.Text(row,"sourceName"))+" · "+Size(Json.Number(row,"size",0))+" · "+(Json.Text(row,"type")=="folder"?Locale.T("文件夹"):Locale.T("文件"))+" · "+new DateTime(1970,1,1,0,0,0,DateTimeKind.Utc).AddMilliseconds(Json.Number(row,"created",0)).ToLocalTime().ToString("MM-dd HH:mm");
        if(!card.Busy){if(card.State!=state){card.State=state;card.LocalError="";BuildActions(card,id,sending);}card.Error.Text=card.LocalError!=""?card.LocalError:MainWindow.DisplayErrorOrEmpty(Json.Text(row,"error"));}
      }
      foreach(string id in entries.Keys.Where(id=>!seen.Contains(id)).ToArray()){if(entries[id].Busy)continue;panel.Children.Remove(entries[id].View);entries.Remove(id);}
      (sending?sentEmpty:empty).Visibility=entries.Count==0?Visibility.Visible:Visibility.Collapsed;
    }
    Button ActionButton(StageCard card,string title,Func<Task> action){var button=MainWindow.LButton(title,()=>main.Run(()=>Perform(card,action)));button.Margin=new Thickness(0,0,10,0);card.Actions.Children.Add(button);return button;}
    async Task Perform(StageCard card,Func<Task> action){if(card.Busy)return;card.Busy=true;card.Actions.IsEnabled=false;card.LocalError="";card.Error.Text="";try{await action();}catch(Exception ex){card.LocalError=MainWindow.DisplayError(ex.Message);card.Error.Text=card.LocalError;}finally{card.Busy=false;card.Actions.IsEnabled=true;}await Refresh();}
    void BuildActions(StageCard card,string id,bool sending){
      card.Actions.Children.Clear();string state=card.State;
      if(sending){
        if(state=="failed"||state=="cancelled"||state=="canceled")ActionButton(card,"重新发送",async()=>{var result=await main.Client.Call("stage-outgoing-retry",new{id=id});status.Text=Json.Text(result,"message");});
        if(state!="ready" && state!="complete" && state!="completed" && state!="cancelled" && state!="canceled")ActionButton(card,"取消发送",async()=>{await main.Client.Call("stage-outgoing-cancel",new{id=id});status.Text=Locale.T("已取消发送，其他任务继续。");});
        if(state=="failed" || state=="cancelled" || state=="canceled" || state=="completed" || state=="complete" || state=="ready")ActionButton(card,"清除记录",async()=>{if(!main.Confirm(main,Locale.T("清除发送记录"),Locale.T("清除“")+Json.Text(card.Data,"name")+Locale.T("”的发送记录？尚未完成的分享将结束。原文件和对方已保存的文件不受影响。")))return;await main.Client.Call("stage-outgoing-remove",new{id=id});status.Text=Locale.T("发送记录已清除，可重新选择文件分享。");});
        return;
      }
      if(state=="ready"){
        ActionButton(card,"保存到…",()=>SaveTo(card,id));
        ActionButton(card,"在文件夹中显示",async()=>{var ready=await main.Client.Call("stage-ready",new{id=id});System.Diagnostics.Process.Start(new ProcessStartInfo("explorer.exe","/select,\""+Json.Text(ready,"path")+"\""){UseShellExecute=true});});
        var drag=MainWindow.LText("拖到文件夹",12,"#7163C8");drag.Margin=new Thickness(0,0,14,0);drag.Cursor=Cursors.Hand;Point down=new Point();bool pressed=false;drag.MouseLeftButtonDown+=(s,e)=>{down=e.GetPosition(drag);pressed=true;};drag.MouseLeftButtonUp+=(s,e)=>pressed=false;
        drag.MouseMove+=async(s,e)=>{if(!pressed||card.Busy||e.LeftButton!=MouseButtonState.Pressed||(e.GetPosition(drag)-down).Length<6)return;pressed=false;await Perform(card,async()=>{var ready=await main.Client.Call("stage-ready",new{id=id});var payload=new DataObject(DataFormats.FileDrop,new[]{Json.Text(ready,"path")});var effect=DragDrop.DoDragDrop(drag,payload,DragDropEffects.Copy);if(effect==DragDropEffects.Copy)status.Text=Locale.T("复制已交给文件管理器。请确认目标文件已保存，再删除暂存副本。");});};card.Actions.Children.Add(drag);
      }else if(state=="receiving"||state=="queued")ActionButton(card,"取消传输",async()=>{await main.Client.Call("stage-cancel",new{id=id});status.Text=Locale.T("已取消传输，可稍后重试；其他任务继续。");});
      else ActionButton(card,state=="pending"?"开始传输":"重新传输",async()=>{await main.Client.Call("stage-start",new{id=id});status.Text=Locale.T("已开始接收，请等待完成。");});
      if(state!="receiving" && state!="queued")ActionButton(card,"删除",async()=>{if(!main.Confirm(main,Locale.T("删除暂存副本"),Locale.T("删除“")+Json.Text(card.Data,"name")+Locale.T("”的暂存副本？尚未保存的文件将无法从本机恢复。")))return;await main.Client.Call("stage-remove",new{id=id});status.Text=Locale.T("已删除暂存副本。");});
    }
    sealed class FolderOwner : System.Windows.Forms.IWin32Window { public IntPtr Handle { get; private set; } public FolderOwner(Window window){Handle=new System.Windows.Interop.WindowInteropHelper(window).Handle;} }
    async Task SaveTo(StageCard card,string id){
      string destination;
      if(Json.Text(card.Data,"type")=="folder"){
        using(var picker=new System.Windows.Forms.FolderBrowserDialog{Description=Locale.T("选择保存文件夹的位置；已有同名文件夹时将保留暂存副本。"),ShowNewFolderButton=true}){if(picker.ShowDialog(new FolderOwner(main))!=System.Windows.Forms.DialogResult.OK)return;destination=Path.Combine(picker.SelectedPath,Json.Text(card.Data,"name"));}
      }else{var picker=new SaveFileDialog{Title=Locale.T("保存暂存文件（不覆盖已有文件）"),FileName=Json.Text(card.Data,"name"),OverwritePrompt=false,CheckPathExists=true,AddExtension=false};if(picker.ShowDialog(main)!=true)return;destination=picker.FileName;}
      var result=await main.Client.Call("stage-save",new{id=id,destination=destination});string saved=Json.Text(result,"path");status.Text=Locale.T("已保存到 ")+(saved==""?destination:saved)+Locale.T("，并移出暂存区。");
    }
  }
  public sealed class FileShareWindow : Window {
    public FileShareWindow(MainWindow main,string[] files,IEnumerable<Device> devices){
      Title=Locale.T("分享文件 · LanLink");Owner=main;Width=420;Height=440;MinWidth=360;MinHeight=300;WindowStartupLocation=WindowStartupLocation.CenterOwner;Background=MainWindow.Brush("#F7F8FB");Resources=main.Resources;
      var body=new StackPanel{Margin=new Thickness(24)};body.Children.Add(MainWindow.LText("发送到设备",24));var summary=MainWindow.Text(files.Length==1?Path.GetFileName(files[0]):files.Length+Locale.T(" 个文件或文件夹"),13,"#788195");summary.Margin=new Thickness(0,12,0,20);body.Children.Add(summary);
      var targets=new StackPanel();body.Children.Add(targets);var status=MainWindow.Text("",12,"#B76370");status.TextWrapping=TextWrapping.Wrap;body.Children.Add(status);bool any=false,busy=false;
      foreach(var device in devices.Where(d=>d.Online&&!d.Local)){any=true;var d=device;var send=MainWindow.Button(d.Name+"   →",delegate{});send.Click+=async delegate{if(busy)return;busy=true;targets.IsEnabled=false;status.Text=Locale.T("正在准备发送到“")+d.Name+"”…";try{await main.PrepareFileShare(d,files);Close();}catch(Exception ex){status.Text=MainWindow.DisplayError(ex.Message);busy=false;targets.IsEnabled=true;}};send.HorizontalContentAlignment=HorizontalAlignment.Left;send.Margin=new Thickness(0,0,0,12);targets.Children.Add(send);}
      if(!any)body.Children.Add(MainWindow.LText("暂无在线设备，请打开对方 LanLink。",14,"#788195"));var tip=MainWindow.LText("文件会进入对方暂存区。可在“暂存文件”的发送任务中查看进度、取消或重试。",12,"#788195");tip.TextWrapping=TextWrapping.Wrap;tip.Margin=new Thickness(0,14,0,16);body.Children.Add(tip);body.Children.Add(MainWindow.LButton("关闭",Close));WindowShell.Wrap(this,new Border{Background=MainWindow.Brush("#F7F8FB"),Child=new ScrollViewer{Content=body,VerticalScrollBarVisibility=ScrollBarVisibility.Auto}},true,true);
    }
  }
  public static class FileShareShell {
    static string Quote(string value){return "\""+value.Replace("\"","\\\"")+"\"";}
    public static void Register(){string exe=Process.GetCurrentProcess().MainModule.FileName;foreach(string kind in new[]{"*","Directory"})using(var key=Registry.CurrentUser.CreateSubKey("Software\\Classes\\"+kind+"\\shell\\LanLink.Share")){key.SetValue("",Locale.T("分享文件 · LanLink"));key.SetValue("Icon",exe);key.SetValue("MultiSelectModel","Single");using(var command=key.CreateSubKey("command"))command.SetValue("",Quote(exe)+" --data "+Quote(MainWindow.DataDir)+" --root "+Quote(MainWindow.Root)+" --share \"%1\"");}}
    public static void Enqueue(string[] files){if(files.Length==0||files.Length>100||files.Any(f=>!Path.IsPathRooted(f)))throw new IOException(Locale.T("文件路径无效"));string folder=Path.Combine(MainWindow.DataDir,"share-inbox");Directory.CreateDirectory(folder);string dest=Path.Combine(folder,Guid.NewGuid()+".json"),part=dest+".part";File.WriteAllText(part,new System.Web.Script.Serialization.JavaScriptSerializer().Serialize(files));File.Move(part,dest);}
    public static IEnumerable<string[]> Drain(){string folder=Path.Combine(MainWindow.DataDir,"share-inbox");if(!Directory.Exists(folder))yield break;foreach(string file in Directory.GetFiles(folder,"*.json").Take(100)){string[] paths=null;try{paths=new System.Web.Script.Serialization.JavaScriptSerializer().Deserialize<string[]>(File.ReadAllText(file));if(paths.Length==0||paths.Length>100||paths.Any(p=>!Path.IsPathRooted(p)))paths=null;}catch{}try{File.Delete(file);}catch{}if(paths!=null)yield return paths;}}
  }
}
