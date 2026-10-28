using System;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Diagnostics;
using System.Windows.Forms;
using Microsoft.Win32;

class WindowsSetup {
  static readonly string Home=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"Programs","LanLink");
  static readonly string Support=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"LanLinkSetup");
  const string Key=@"Software\Microsoft\Windows\CurrentVersion\Uninstall\LanLink";
  static bool Chinese=System.Globalization.CultureInfo.CurrentUICulture.Name.StartsWith("zh");
  static string T(string zh,string en){return Chinese?zh:en;}
  [STAThread] static int Main(string[] args){
    Application.EnableVisualStyles();
    try{
      if(args.Length==2&&args[0]=="--verify-payload"){Extract(Path.GetFullPath(args[1]));return 0;}
      if(args.Length==1&&args[0]=="--uninstall"){Uninstall();return 0;}
      var form=new Form{Text="LanLink Setup",Width=540,Height=260,StartPosition=FormStartPosition.CenterScreen,FormBorderStyle=FormBorderStyle.FixedDialog,MaximizeBox=false};
      var label=new Label{Left=24,Top=24,Width=475,Height=100,Text=T("安装 LanLink ","Install LanLink ")+Assembly.GetExecutingAssembly().GetName().Version.ToString(3)+"\r\n\r\n"+Home+"\r\n"+T("仅为当前用户安装，无需管理员权限。","Installs for the current user; no administrator rights required.")};
      var install=new Button{Left=340,Top=155,Width=150,Height=35,Text=T("安装并启动","Install and launch")};
      install.Click+=delegate{install.Enabled=false;form.UseWaitCursor=true;try{Install();form.Close();Process.Start(new ProcessStartInfo(Path.Combine(Home,"LanLink.exe")){WorkingDirectory=Home});}catch(Exception e){MessageBox.Show(e.Message,"LanLink",MessageBoxButtons.OK,MessageBoxIcon.Error);install.Enabled=true;form.UseWaitCursor=false;}};
      form.Controls.Add(label);form.Controls.Add(install);Application.Run(form);return 0;
    }catch(Exception e){Console.Error.WriteLine(e);return 1;}
  }
  static void Extract(string destination){
    if(Directory.Exists(destination))throw new IOException("Destination already exists: "+destination);
    Directory.CreateDirectory(destination);
    using(var stream=Assembly.GetExecutingAssembly().GetManifestResourceStream("payload.zip"))using(var zip=new ZipArchive(stream,ZipArchiveMode.Read)){
      foreach(var entry in zip.Entries){string name=entry.FullName.Replace('/',Path.DirectorySeparatorChar);string target=Path.GetFullPath(Path.Combine(destination,name));if(!target.StartsWith(destination+Path.DirectorySeparatorChar,StringComparison.OrdinalIgnoreCase))throw new IOException("Invalid archive path");if(entry.Name==""){Directory.CreateDirectory(target);continue;}Directory.CreateDirectory(Path.GetDirectoryName(target));entry.ExtractToFile(target,false);}
    }
    foreach(string name in new[]{"LanLink.exe","runtime/node.exe","package.json"})if(!File.Exists(Path.Combine(destination,name)))throw new IOException("Incomplete payload: "+name);
  }
  static void NotRunning(){foreach(var p in Process.GetProcessesByName("LanLink")){try{if(p.MainModule.FileName.StartsWith(Home+Path.DirectorySeparatorChar,StringComparison.OrdinalIgnoreCase))throw new IOException(T("请先从托盘菜单退出 LanLink。","Exit LanLink from its tray menu first."));}catch(System.ComponentModel.Win32Exception){throw new IOException("Cannot inspect running LanLink; close it before installation.");}finally{p.Dispose();}}}
  static void Shortcut(string name){Type type=Type.GetTypeFromProgID("WScript.Shell");dynamic shell=Activator.CreateInstance(type);dynamic link=shell.CreateShortcut(name);link.TargetPath=Path.Combine(Home,"LanLink.exe");link.WorkingDirectory=Home;link.IconLocation=Path.Combine(Home,"LanLink.exe");link.Save();}
  static void Install(){
    NotRunning();Directory.CreateDirectory(Path.GetDirectoryName(Home));string stage=Home+".install-"+Guid.NewGuid().ToString("N"),backup=Home+".backup-"+DateTime.UtcNow.Ticks;
    Extract(stage);bool moved=false;try{if(Directory.Exists(Home)){Directory.Move(Home,backup);moved=true;}Directory.Move(stage,Home);}catch{if(moved&&!Directory.Exists(Home))Directory.Move(backup,Home);throw;}
    Directory.CreateDirectory(Support);string self=Assembly.GetExecutingAssembly().Location,uninstall=Path.Combine(Support,"Uninstall.exe");if(!string.Equals(self,uninstall,StringComparison.OrdinalIgnoreCase))File.Copy(self,uninstall,true);
    Shortcut(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs),"LanLink.lnk"));Shortcut(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory),"LanLink.lnk"));
    using(var key=Registry.CurrentUser.CreateSubKey(Key)){key.SetValue("DisplayName","LanLink");key.SetValue("DisplayVersion",Assembly.GetExecutingAssembly().GetName().Version.ToString(3));key.SetValue("Publisher","LanLink");key.SetValue("InstallLocation",Home);key.SetValue("DisplayIcon",Path.Combine(Home,"LanLink.exe"));key.SetValue("UninstallString","\""+uninstall+"\" --uninstall");key.SetValue("NoModify",1);key.SetValue("NoRepair",1);}
  }
  static void Uninstall(){
    if(MessageBox.Show(T("卸载 LanLink？个人配对与配置将保留。","Uninstall LanLink? Personal pairing and settings will be retained."),"LanLink",MessageBoxButtons.YesNo)!=DialogResult.Yes)return;
    NotRunning();string expected=Path.GetFullPath(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"Programs","LanLink"));if(!string.Equals(Path.GetFullPath(Home),expected,StringComparison.OrdinalIgnoreCase))throw new IOException("Invalid uninstall path");
    if(Directory.Exists(Home))Directory.Delete(Home,true);foreach(var folder in new[]{Environment.SpecialFolder.Programs,Environment.SpecialFolder.DesktopDirectory})File.Delete(Path.Combine(Environment.GetFolderPath(folder),"LanLink.lnk"));Registry.CurrentUser.DeleteSubKeyTree(Key,false);
  }
}
