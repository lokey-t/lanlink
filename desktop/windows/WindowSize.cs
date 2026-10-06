using System;
using System.IO;
using System.Windows;
using System.Windows.Threading;
using System.Web.Script.Serialization;
namespace LanLink.Desktop {
  public static class WindowSize {
    public static string RemoteFile(string directory,string id) {
      using(var hash=System.Security.Cryptography.SHA256.Create())return Path.Combine(directory,"remote-"+BitConverter.ToString(hash.ComputeHash(System.Text.Encoding.UTF8.GetBytes(id))).Replace("-","").ToLowerInvariant()+".json");
    }
    public static bool RestoreRemote(Window window,string file) {
      try {
        var data=new JavaScriptSerializer().DeserializeObject(File.ReadAllText(file));double width=Json.Number(data,"width",0),height=Json.Number(data,"height",0),left=Json.Number(data,"left",double.NaN),top=Json.Number(data,"top",double.NaN);
        foreach(double n in new[]{width,height,left,top})if(double.IsNaN(n)||double.IsInfinity(n))return false;
        if(width<window.MinWidth || height<window.MinHeight || width>20000 || height>20000)return false;
        var source=PresentationSource.FromVisual(window);var matrix=source==null?System.Windows.Media.Matrix.Identity:source.CompositionTarget.TransformFromDevice;
        Rect area=SystemParameters.WorkArea;double best=0;var saved=new Rect(left,top,width,height);
        foreach(var screen in System.Windows.Forms.Screen.AllScreens){var b=screen.WorkingArea;var candidate=new Rect(b.Left*matrix.M11,b.Top*matrix.M22,b.Width*matrix.M11,b.Height*matrix.M22);var overlap=Rect.Intersect(saved,candidate);if(!overlap.IsEmpty && overlap.Width*overlap.Height>best){area=candidate;best=overlap.Width*overlap.Height;}}
        window.Width=Math.Max(window.MinWidth,Math.Min(width,area.Width));window.Height=Math.Max(window.MinHeight,Math.Min(height,area.Height));window.WindowStartupLocation=WindowStartupLocation.Manual;
        window.Left=Math.Max(area.Left,Math.Min(left,area.Right-window.Width));window.Top=Math.Max(area.Top,Math.Min(top,area.Bottom-window.Height));return true;
      }catch{return false;}
    }
    public static void SaveRemote(Window window,string file) {
      try {var bounds=window.WindowState==WindowState.Normal?new Rect(window.Left,window.Top,window.Width,window.Height):window.RestoreBounds;if(bounds.IsEmpty || bounds.Width<window.MinWidth || bounds.Height<window.MinHeight)return;File.WriteAllText(file,new JavaScriptSerializer().Serialize(new{width=window is RemoteWindow?((RemoteWindow)window).SavedWindowWidth:bounds.Width,height=bounds.Height,left=bounds.Left,top=bounds.Top,sidebarCollapsed=window is RemoteWindow && ((RemoteWindow)window).SidebarCollapsed}));}catch{}
    }
    public static bool RemoteCollapsed(string file){try{var value=Json.Get(new JavaScriptSerializer().DeserializeObject(File.ReadAllText(file)),"sidebarCollapsed");return value is bool?(bool)value:true;}catch{return true;}}
    public static void AttachRemote(Window window,string file) {
      var timer=new DispatcherTimer{Interval=TimeSpan.FromMilliseconds(400)};timer.Tick+=delegate{timer.Stop();SaveRemote(window,file);};
      EventHandler moved=delegate{if(window.IsLoaded && window.WindowState==WindowState.Normal){timer.Stop();timer.Start();}};
      window.LocationChanged+=moved;window.SizeChanged+=delegate{moved(window,EventArgs.Empty);};window.Closing+=delegate{timer.Stop();SaveRemote(window,file);};
    }
    public static void Restore(Window window,string file) {
      try {var data=new JavaScriptSerializer().DeserializeObject(File.ReadAllText(file));double width=Json.Number(data,"width",0),height=Json.Number(data,"height",0);
        if(double.IsNaN(width)||double.IsNaN(height)||width<640||height<440||width>10000||height>10000)return;
        var area=SystemParameters.WorkArea;double scale=Math.Min(1,Math.Min((area.Width-20)/width,(area.Height-20)/height));window.Width=width*scale;window.Height=height*scale;
      }catch{}
    }
    public static void Save(Window window,string file) {
      try {var bounds=window.WindowState==WindowState.Normal?new Rect(0,0,window.Width,window.Height):window.RestoreBounds;if(bounds.Width<640||bounds.Height<440)return;
        File.WriteAllText(file,new JavaScriptSerializer().Serialize(new {width=bounds.Width,height=bounds.Height}));
      }catch{}
    }
    public static void Attach(Window window,string file) {
      Restore(window,file);var timer=new DispatcherTimer{Interval=TimeSpan.FromMilliseconds(500)};
      timer.Tick+=delegate{timer.Stop();Save(window,file);};
      window.SizeChanged+=delegate{if(window.IsLoaded && window.WindowState==WindowState.Normal){timer.Stop();timer.Start();}};
      window.Closing+=delegate{timer.Stop();Save(window,file);};
    }
  }
}
