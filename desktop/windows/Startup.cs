using System;
using System.Diagnostics;
using System.Security.Principal;
using System.Threading.Tasks;
namespace LanLink.Desktop {
  static class Startup {
    static string Sid { get { return WindowsIdentity.GetCurrent().User.Value; } }
    static string Name { get { return "LanLink-"+Sid; } }
    static dynamic Folder(){dynamic service=Activator.CreateInstance(Type.GetTypeFromProgID("Schedule.Service"));service.Connect();return service.GetFolder("\\");}
    public static bool ElevatedEnabled(){try{dynamic task=Folder().GetTask(Name);return task.Enabled && task.Definition.Principal.RunLevel==1;}catch{return false;}}
    public static void Configure(bool enabled,string expectedSid){
      if(Sid!=expectedSid)throw new Exception("请使用当前 Windows 用户确认管理员权限");
      dynamic folder=Folder();
      if(!enabled){try{folder.GetTask(Name);}catch{return;}folder.DeleteTask(Name,0);return;}
      dynamic task=Definition();
      folder.RegisterTaskDefinition(Name,task,6,Sid,null,3,null);
    }
    static dynamic Definition(){
      dynamic service=Activator.CreateInstance(Type.GetTypeFromProgID("Schedule.Service"));service.Connect();dynamic task=service.NewTask(0);
      task.RegistrationInfo.Description="当前用户登录后以管理员权限启动 LanLink";
      task.Principal.UserId=Sid;task.Principal.LogonType=3;task.Principal.RunLevel=1;
      task.Settings.Enabled=true;task.Settings.StartWhenAvailable=true;task.Settings.DisallowStartIfOnBatteries=false;task.Settings.StopIfGoingOnBatteries=false;task.Settings.ExecutionTimeLimit="PT0S";task.Settings.MultipleInstances=2;
      dynamic trigger=task.Triggers.Create(9);trigger.UserId=Sid;trigger.Delay="PT0S";
      dynamic action=task.Actions.Create(0);action.Path=System.Reflection.Assembly.GetExecutingAssembly().Location;action.Arguments="--data "+Quote(MainWindow.DataDir);action.WorkingDirectory=MainWindow.Root;
      return task;
    }
    public static string Quote(string value){return "\""+value+(value.EndsWith("\\")?"\\":"")+"\"";}
    public static void Verify(){dynamic task=Definition();string user=task.Principal.UserId;string resolved=user.StartsWith("S-1-")?user:new NTAccount(user).Translate(typeof(SecurityIdentifier)).Value;if(resolved!=Sid || task.Principal.RunLevel!=1 || task.Principal.LogonType!=3 || task.Triggers.Count!=1 || task.Triggers.Item(1).Type!=9 || task.Actions.Item(1).Path!=System.Reflection.Assembly.GetExecutingAssembly().Location || task.Actions.Item(1).Arguments!="--data "+Quote(MainWindow.DataDir))throw new Exception("Invalid administrator startup definition");}
    public static async Task SetElevated(bool enabled){
      var info=new ProcessStartInfo(System.Reflection.Assembly.GetExecutingAssembly().Location,"--startup-admin "+(enabled?"on":"off")+" --startup-user "+Sid+" --data "+Quote(MainWindow.DataDir)){UseShellExecute=true,Verb="runas",WindowStyle=ProcessWindowStyle.Hidden};
      using(var process=Process.Start(info)){await Task.Run(()=>process.WaitForExit());if(process.ExitCode!=0)throw new Exception("未完成管理员启动设置，请重试");}
    }
  }
}
