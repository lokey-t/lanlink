using System;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Animation;
namespace LanLink.Desktop {
  // A transient, non-activating, click-through edge strip, independent of the main window.
  sealed class EdgeGlow : Window {
    [DllImport("user32.dll")] static extern int GetWindowLong(IntPtr h,int index);
    [DllImport("user32.dll")] static extern int SetWindowLong(IntPtr h,int index,int value);
    static EdgeGlow current;
    readonly string edgeName; bool counting; readonly bool vertical; readonly Border light;
    public static void Feedback(object data){
      string phase=Json.Text(data,"phase"),edge=Json.Text(data,"edge");
      if(phase=="clear" || phase=="cancel"){if(current!=null && (phase=="clear" || current.counting))current.Close();return;}
      if(edge!="left" && edge!="right" && edge!="top" && edge!="bottom")return;
      if(phase=="depart" || phase=="return" || phase=="hold"){
        if(current==null || current.edgeName!=edge){if(current!=null)current.Close();current=new EdgeGlow(edge);current.Opacity=.5;current.Show();}var panel=current;panel.counting=false;panel.BeginAnimation(OpacityProperty,null);
        bool hide=Json.Flag(data,"hideAway"),back=phase=="return";double start=back?(hide?0:.5):.5,end=back||hide?0:.5;
        panel.Opacity=start;
        if(phase=="hold"){panel.Opacity=end;if(end==0)panel.Close();return;}
        var pulse=new DoubleAnimationUsingKeyFrames();pulse.KeyFrames.Add(new LinearDoubleKeyFrame(start,TimeSpan.Zero));pulse.KeyFrames.Add(new EasingDoubleKeyFrame(back&&hide?.5:1,TimeSpan.FromMilliseconds(100)));pulse.KeyFrames.Add(new EasingDoubleKeyFrame(end,TimeSpan.FromMilliseconds(back?260:340)));pulse.Completed+=delegate{if(end==0)panel.Close();};panel.BeginAnimation(OpacityProperty,pulse);return;
      }
      if(phase!="countdown")return;
      if(current==null || !current.counting || current.edgeName!=edge){if(current!=null)current.Close();current=new EdgeGlow(edge,true);current.Show();}
      double p=Math.Max(0,Math.Min(1,Json.Number(data,"progress",0)));current.Opacity=.5*p;if(current.vertical)current.light.Width=10+22*p;else current.light.Height=10+22*p;
    }
    internal static async System.Threading.Tasks.Task Verify(){Feedback(new System.Collections.Generic.Dictionary<string,object>{{"phase","countdown"},{"edge","right"},{"progress",0.0}});double dim=current.Opacity;Feedback(new System.Collections.Generic.Dictionary<string,object>{{"phase","countdown"},{"edge","right"},{"progress",.8}});if(current.Opacity<=dim || current.light.Width<=10)throw new Exception("Countdown must intensify and widen");Feedback(new System.Collections.Generic.Dictionary<string,object>{{"phase","cancel"}});if(current!=null)throw new Exception("Leaving must cancel countdown");Feedback(new System.Collections.Generic.Dictionary<string,object>{{"phase","countdown"},{"edge","left"},{"progress",1.0}});var countdownPanel=current;Feedback(new System.Collections.Generic.Dictionary<string,object>{{"phase","depart"},{"edge","left"}});if(current!=countdownPanel || current.Opacity<.5)throw new Exception("Countdown to departure must reuse the window without going dark");await System.Threading.Tasks.Task.Delay(450);if(current==null || Math.Abs(current.Opacity-.5)>.01 || current.edgeName!="left")throw new Exception("Departure must hold at half brightness on its edge");Feedback(new System.Collections.Generic.Dictionary<string,object>{{"phase","cancel"}});if(current==null)throw new Exception("Cancel countdown must preserve away glow");Feedback(new System.Collections.Generic.Dictionary<string,object>{{"phase","return"},{"edge","left"}});await System.Threading.Tasks.Task.Delay(350);if(current!=null)throw new Exception("Return must clear glow");Feedback(new System.Collections.Generic.Dictionary<string,object>{{"phase","depart"},{"edge","top"},{"hideAway",true}});await System.Threading.Tasks.Task.Delay(450);if(current!=null)throw new Exception("Hidden-away departure must fade out");ShowEdge("right");var panel=current;await System.Threading.Tasks.Task.Delay(100);int flags=GetWindowLong(new WindowInteropHelper(panel).Handle,-20);if(panel.IsActive || (flags&0x08000020)!=0x08000020 || !panel.Topmost)throw new Exception("Edge glow must stay non-activating and click-through");await System.Threading.Tasks.Task.Delay(950);if(current!=null)throw new Exception("Edge glow must disappear automatically");}
    public static void ShowEdge(string edge){Feedback(new System.Collections.Generic.Dictionary<string,object>{{"phase","return"},{"edge",edge},{"hideAway",true}});}
    EdgeGlow(string edge,bool countdown=false){
      edgeName=edge;counting=countdown;
      WindowStyle=WindowStyle.None;AllowsTransparency=true;Background=Brushes.Transparent;ShowActivated=false;ShowInTaskbar=false;Topmost=true;ResizeMode=ResizeMode.NoResize;IsHitTestVisible=false;
      var main=Application.Current.MainWindow;var source=PresentationSource.FromVisual(main);var dpi=source==null?Matrix.Identity:source.CompositionTarget.TransformFromDevice;var bounds=System.Windows.Forms.Screen.PrimaryScreen.Bounds;
      vertical=edge=="left"||edge=="right";bool reverse=edge=="right"||edge=="bottom";
      Width=vertical?64:bounds.Width*dpi.M11;Height=vertical?bounds.Height*dpi.M22:64;Left=bounds.Left*dpi.M11+(edge=="right"?bounds.Width*dpi.M11-Width:0);Top=bounds.Top*dpi.M22+(edge=="bottom"?bounds.Height*dpi.M22-Height:0);
      var brush=new LinearGradientBrush{StartPoint=vertical?new Point(reverse?1:0,0):new Point(0,reverse?1:0),EndPoint=vertical?new Point(reverse?0:1,0):new Point(0,reverse?0:1)};
      brush.GradientStops.Add(new GradientStop(Color.FromArgb(230,155,137,255),0));brush.GradientStops.Add(new GradientStop(Color.FromArgb(105,108,183,255),.25));brush.GradientStops.Add(new GradientStop(Colors.Transparent,1));light=new Border{Background=brush,HorizontalAlignment=vertical?(reverse?HorizontalAlignment.Right:HorizontalAlignment.Left):HorizontalAlignment.Stretch,VerticalAlignment=vertical?VerticalAlignment.Stretch:(reverse?VerticalAlignment.Bottom:VerticalAlignment.Top)};if(vertical)light.Width=32;else light.Height=32;Content=light;
      SourceInitialized+=delegate{var h=new WindowInteropHelper(this).Handle;SetWindowLong(h,-20,GetWindowLong(h,-20)|0x20|0x08000000|0x80);};
      Closed+=delegate{if(current==this)current=null;};
    }
  }
}
