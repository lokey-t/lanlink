using System;
using System.Collections.Generic;
using System.Linq;
using System.Windows;
using System.Windows.Media;
using System.Windows.Interop;
using System.Runtime.InteropServices;
using System.Threading.Tasks;
using Forms=System.Windows.Forms;
namespace LanLink.Desktop {
  // OLE file data is read from a real drag target, never guessed from clipboard.
  public sealed class FileDragEdges {
    readonly MainWindow main;readonly List<Window> windows=new List<Window>();string signature="";readonly System.Windows.Threading.DispatcherTimer visibility=new System.Windows.Threading.DispatcherTimer{Interval=TimeSpan.FromMilliseconds(40)};
    public FileDragEdges(MainWindow owner){main=owner;owner.Closed+=delegate{visibility.Stop();Close();};visibility.Tick+=delegate{bool pressed=((GetAsyncKeyState(1)&0x8000)!=0);foreach(var w in windows){if(pressed&&!w.IsVisible)w.Show();else if(!pressed&&w.IsVisible)w.Hide();}};}
    public void Close(){visibility.Stop();foreach(var w in windows)w.Close();windows.Clear();}
    public void Update(object state){var edges=Json.Map(Json.Get(state,"edges"));string next=Json.Flag(state,"master")&&Json.Text(state,"active")==""?string.Join("|",edges.Select(e=>e.Key+":"+Convert.ToString(e.Value))):"";if(signature==next)return;signature=next;Close();if(next=="")return;
      var screen=Forms.SystemInformation.VirtualScreen;var source=PresentationSource.FromVisual(main);double sx=source==null?1:source.CompositionTarget.TransformFromDevice.M11,sy=source==null?1:source.CompositionTarget.TransformFromDevice.M22;
      foreach(var entry in edges){string edge=entry.Key,id=Convert.ToString(entry.Value);if(id==""||!new[]{"left","right","top","bottom"}.Contains(edge))continue;bool vertical=edge=="left"||edge=="right";
        var w=new Window{WindowStyle=WindowStyle.None,ResizeMode=ResizeMode.NoResize,ShowInTaskbar=false,ShowActivated=false,Topmost=true,AllowsTransparency=true,Background=new SolidColorBrush(Color.FromArgb(1,0,0,0)),AllowDrop=true,Left=(edge=="right"?screen.Right-5:screen.Left)*sx,Top=(edge=="bottom"?screen.Bottom-5:screen.Top)*sy,Width=(vertical?5:screen.Width)*sx,Height=(vertical?screen.Height:5)*sy};bool hovered=false,triggered=false;int generation=0;
        w.SourceInitialized+=delegate{var hwnd=new WindowInteropHelper(w).Handle;SetWindowLong(hwnd,-20,GetWindowLong(hwnd,-20)|0x08000000|0x80);};
        w.Closed+=delegate{hovered=false;triggered=true;generation++;};
        w.DragEnter+=async(sender,e)=>{if(!e.Data.GetDataPresent(DataFormats.FileDrop)||triggered)return;var files=e.Data.GetData(DataFormats.FileDrop) as string[];if(files==null)return;if(Json.Text(main.State,"switchMode")=="hotkey"&&!Hotkey(main.State))return;hovered=true;int entered=++generation;e.Effects=DragDropEffects.None;e.Handled=true;await Task.Delay((int)Math.Max(0,(Json.Text(main.State,"switchMode")=="hotkey"?0:Json.Number(main.State,"dwell",100))));if(!hovered||triggered||entered!=generation||(GetAsyncKeyState(1)&0x8000)==0)return;triggered=true;main.Run(async()=>{try{var result=await main.Client.Call("stage-edge",new{id=id,edge=edge,files=files});main.NotifyTransient(Json.Text(result,"message"));}finally{triggered=false;}});};
        w.DragOver+=delegate(object sender,DragEventArgs e){e.Effects=DragDropEffects.None;e.Handled=true;};w.DragLeave+=delegate{hovered=false;generation++;};w.Drop+=delegate{hovered=false;generation++;};windows.Add(w);
      }if(windows.Count>0)visibility.Start();
    }
    static bool Hotkey(object state){string key=Json.Text(state,"edgeHotkey");var flags=System.Windows.Input.Keyboard.Modifiers;var needed=key=="ctrl-shift"?System.Windows.Input.ModifierKeys.Control|System.Windows.Input.ModifierKeys.Shift:key=="alt-shift"?System.Windows.Input.ModifierKeys.Alt|System.Windows.Input.ModifierKeys.Shift:key=="meta-shift"?System.Windows.Input.ModifierKeys.Windows|System.Windows.Input.ModifierKeys.Shift:System.Windows.Input.ModifierKeys.Control|System.Windows.Input.ModifierKeys.Alt;return (flags&needed)==needed;}
    [DllImport("user32.dll")]static extern short GetAsyncKeyState(int key);
    [DllImport("user32.dll")]static extern int GetWindowLong(IntPtr hwnd,int index);
    [DllImport("user32.dll")]static extern int SetWindowLong(IntPtr hwnd,int index,int value);
  }
}
