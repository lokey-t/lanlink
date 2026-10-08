using System;
using System.Collections.Generic;
using System.IO;
using System.Text.RegularExpressions;
using System.Windows;
using System.Windows.Media;
using Microsoft.Win32;

namespace LanLink.Desktop {
  // Local appearance preference; never part of pairing or group state.
  public static class Theme {
    static readonly Dictionary<string,SolidColorBrush> brushes = new Dictionary<string,SolidColorBrush>();
    static readonly HashSet<string> resourceColors = new HashSet<string>();
    static bool dark, listening, initialized;
    public static string Mode = "system";
    public static bool Dark { get { return dark; } }
    static bool SystemDark() {
      try { using(var key=Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize")) return Convert.ToInt32(key == null ? 1 : key.GetValue("AppsUseLightTheme",1)) == 0; }
      catch { return false; }
    }
    public static void Initialize(string directory) {
      try { var config=new System.Web.Script.Serialization.JavaScriptSerializer().DeserializeObject(File.ReadAllText(Path.Combine(directory,"config.json"))); Mode=Json.Text(config,"theme"); } catch {}
      Set(Mode);
      if(!listening) { listening=true;SystemEvents.UserPreferenceChanged+=delegate { if(Application.Current!=null)Application.Current.Dispatcher.BeginInvoke(new Action(delegate { Set(Mode); })); }; }
    }
    public static Color Resolve(string hex) {
      Color c=(Color)ColorConverter.ConvertFromString(hex);if(!dark || c.A==0)return c;
      // Preserve semantic hues while placing pale surfaces and dark text on a dark palette.
      double max=Math.Max(c.R,Math.Max(c.G,c.B)),min=Math.Min(c.R,Math.Min(c.G,c.B));
      if(min>=210) {
        if(min>=248)return Color.FromArgb(c.A,34,44,60);
        if(min>=235)return Color.FromArgb(c.A,23,30,43);
        return Color.FromArgb(c.A,53,66,89);
      }
      if(max<105)return Color.FromArgb(c.A,228,234,244);
      return Color.FromArgb(c.A,(byte)(c.R+(255-c.R)*.40),(byte)(c.G+(255-c.G)*.40),(byte)(c.B+(255-c.B)*.40));
    }
    public static Brush Brush(string hex) {
      SolidColorBrush result;if(!brushes.TryGetValue(hex,out result)){result=new SolidColorBrush(Resolve(hex));brushes[hex]=result;}return result;
    }
    public static void Set(string mode, bool force=false) {
      string next=mode=="dark"||mode=="light"?mode:"system";
      bool nextDark=next=="dark" || next=="system"&&SystemDark();
      if(initialized && !force && Mode==next && dark==nextDark)return;
      initialized=true;Mode=next;dark=nextDark;
      foreach(var item in brushes)if(!item.Value.IsFrozen)item.Value.Color=Resolve(item.Key);
      if(Application.Current!=null)foreach(var hex in resourceColors){Application.Current.Resources["ThemeColor"+hex.Substring(1)]=Resolve(hex);Application.Current.Resources["ThemeBrush"+hex.Substring(1)]=new SolidColorBrush(Resolve(hex));}
    }
    public static ResourceDictionary Load(string path) {
      string xaml=File.ReadAllText(path);
      xaml=Regex.Replace(xaml,"(?<name>[A-Za-z]+)=\"(?<hex>#[0-9A-Fa-f]{6,8})\"",m=>{
        string hex=m.Groups["hex"].Value;resourceColors.Add(hex);
        return m.Groups["name"].Value+"=\"{DynamicResource Theme"+(m.Groups["name"].Value=="Color"?"Color":"Brush")+hex.Substring(1)+"}\"";
      });
      Set(Mode,true);return (ResourceDictionary)System.Windows.Markup.XamlReader.Parse(xaml);
    }
  }
}
