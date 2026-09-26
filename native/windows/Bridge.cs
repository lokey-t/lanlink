using System;
using System.Collections.Generic;
using System.Collections.Concurrent;
using System.Runtime.InteropServices;
using System.Windows.Forms;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Threading;
using System.Web.Script.Serialization;
using System.Diagnostics;

namespace LanLink {
 public static class Bridge {
  delegate IntPtr Hook(int n, IntPtr w, IntPtr l);
  [DllImport("user32.dll")] static extern IntPtr SetWindowsHookEx(int id, Hook callback, IntPtr module, uint thread);
  [DllImport("user32.dll")] static extern bool UnhookWindowsHookEx(IntPtr h);
  [DllImport("user32.dll")] static extern IntPtr CallNextHookEx(IntPtr h, int n, IntPtr w, IntPtr l);
  [DllImport("kernel32.dll", CharSet=CharSet.Auto)] static extern IntPtr GetModuleHandle(string name);
  [DllImport("user32.dll")] static extern bool GetCursorPos(out POINT p);
  [DllImport("user32.dll")] static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] static extern short GetAsyncKeyState(int key);
  [DllImport("user32.dll")] static extern uint GetClipboardSequenceNumber();
  [DllImport("user32.dll")] static extern uint SendInput(uint n, INPUT[] inputs, int size);
  [DllImport("user32.dll")] static extern bool SetProcessDPIAware();
  [StructLayout(LayoutKind.Sequential)] struct POINT { public int x,y; }
  [StructLayout(LayoutKind.Sequential)] struct MOUSE { public POINT pt; public uint data,flags,time; public UIntPtr extra; }
  [StructLayout(LayoutKind.Sequential)] struct KEY { public uint vk,scan,flags,time; public UIntPtr extra; }
  [StructLayout(LayoutKind.Sequential)] struct INPUT { public uint type; public UNION u; }
  [StructLayout(LayoutKind.Explicit)] struct UNION { [FieldOffset(0)] public MI mi; [FieldOffset(0)] public KI ki; }
  [StructLayout(LayoutKind.Sequential)] struct MI { public int dx,dy; public uint data,flags,time; public UIntPtr extra; }
  [StructLayout(LayoutKind.Sequential)] struct KI { public ushort vk,scan; public uint flags,time; public UIntPtr extra; }
  static readonly JavaScriptSerializer Json = new JavaScriptSerializer { MaxJsonLength = 12000000 };
  static readonly object OutputLock = new object();
  static readonly ConcurrentQueue<string> Queue = new ConcurrentQueue<string>();
  static readonly HashSet<int> Held = new HashSet<int>();
  static readonly HashSet<int> Buttons = new HashSet<int>();
  static Hook MouseCallback = MouseHook, KeyCallback = KeyHook;
  static IntPtr MouseHandle,KeyHandle;
  static bool Remote = false;
  static POINT Anchor;
  static DateTime Lease = DateTime.MinValue;
  static DateTime InputLease = DateTime.MinValue;
  static uint ClipboardSequence;
  static int TickCount;
  static double RelativeX, RelativeY;
  static void Emit(object m) { lock(OutputLock) { Console.Out.WriteLine(Json.Serialize(m)); Console.Out.Flush(); } }
  static string Code(int vk) {
   if(vk>=65 && vk<=90) return "Key"+(char)vk;
   if(vk>=48 && vk<=57) return "Digit"+(char)vk;
   if(vk>=112 && vk<=123) return "F"+(vk-111);
   switch(vk) { case 8:return "Backspace";case 9:return "Tab";case 13:return "Enter";case 27:return "Escape";case 32:return "Space";case 33:return "PageUp";case 34:return "PageDown";case 35:return "End";case 36:return "Home";case 37:return "ArrowLeft";case 38:return "ArrowUp";case 39:return "ArrowRight";case 40:return "ArrowDown";case 45:return "Insert";case 46:return "Delete";case 160:case 16:return "ShiftLeft";case 161:return "ShiftRight";case 162:case 17:return "ControlLeft";case 163:return "ControlRight";case 164:case 18:return "AltLeft";case 165:return "AltRight";case 91:return "MetaLeft";case 92:return "MetaRight";case 20:return "CapsLock";case 186:return "Semicolon";case 187:return "Equal";case 188:return "Comma";case 189:return "Minus";case 190:return "Period";case 191:return "Slash";case 192:return "Backquote";case 219:return "BracketLeft";case 220:return "Backslash";case 221:return "BracketRight";case 222:return "Quote"; }
   if(vk>=96 && vk<=105) return "Numpad"+(vk-96); return "";
  }
  static int Vk(string code) { for(int i=8;i<=222;i++) if(Code(i)==code) return i; return 0; }
  static IntPtr KeyHook(int n,IntPtr w,IntPtr l) {
   if(n>=0) { KEY k=(KEY)Marshal.PtrToStructure(l,typeof(KEY)); if((k.flags&16)==0) {
    bool down=w.ToInt32()==0x100 || w.ToInt32()==0x104;
    if(down && k.vk==123 && GetAsyncKeyState(17)<0 && GetAsyncKeyState(18)<0) { Remote=false; Release(); Emit(new { type="emergency" }); return (IntPtr)1; }
    if(Remote) { string code=Code((int)k.vk); if(code!="") Emit(new { type="input", kind="key", code=code, down=down }); return (IntPtr)1; }
   }} return CallNextHookEx(KeyHandle,n,w,l);
  }
  static IntPtr MouseHook(int n,IntPtr w,IntPtr l) {
   if(n>=0 && Remote) { MOUSE m=(MOUSE)Marshal.PtrToStructure(l,typeof(MOUSE)); if((m.flags&1)==0) {
    int msg=w.ToInt32();
    if(msg==0x200) Emit(new {type="input",kind="move",dx=m.pt.x-Anchor.x,dy=m.pt.y-Anchor.y});
    else if(msg==0x20A) Emit(new {type="input",kind="wheel",delta=(short)(m.data>>16)});
    else if(msg==0x201||msg==0x202||msg==0x204||msg==0x205||msg==0x207||msg==0x208) Emit(new {type="input",kind="button",button=msg==0x201||msg==0x202?0:msg==0x204||msg==0x205?2:1,down=msg==0x201||msg==0x204||msg==0x207});
    return (IntPtr)1;
   }} return CallNextHookEx(MouseHandle,n,w,l);
  }
  static void Send(INPUT input) { if(SendInput(1,new INPUT[]{input},Marshal.SizeOf(typeof(INPUT)))!=1) throw new Exception("系统拒绝输入，请检查目标窗口权限"); }
  static void KeyInput(int vk,bool down) { if(vk==0) return; INPUT i=new INPUT(); i.type=1; i.u.ki.vk=(ushort)vk; i.u.ki.flags=down?0u:2u; if(vk>=33&&vk<=46 || vk==163||vk==165||vk==91||vk==92) i.u.ki.flags|=1; Send(i); if(down)Held.Add(vk);else Held.Remove(vk); }
  static void MouseInput(int button,bool down) { INPUT i=new INPUT(); i.u.mi.flags=button==0?(down?2u:4u):button==2?(down?8u:16u):(down?32u:64u); Send(i); if(down)Buttons.Add(button);else Buttons.Remove(button); }
  static void Release() { foreach(int key in new List<int>(Held)) try{KeyInput(key,false);}catch{} foreach(int b in new List<int>(Buttons))try{MouseInput(b,false);}catch{} Held.Clear();Buttons.Clear(); }
  static double Num(Dictionary<string,object> a,string key,double fallback) { object v;return a.TryGetValue(key,out v)?Convert.ToDouble(v):fallback; }
  static string Str(Dictionary<string,object> a,string key) { object v; return a.TryGetValue(key,out v)?Convert.ToString(v):""; }
  static bool Bool(Dictionary<string,object>a,string key) { object v;return a.TryGetValue(key,out v)&&Convert.ToBoolean(v); }
  static object Execute(string method,Dictionary<string,object>a) {
   Rectangle r=Screen.PrimaryScreen.Bounds;
   if(method=="capabilities")return new {relativeInput=true,input=MouseHandle!=IntPtr.Zero&&KeyHandle!=IntPtr.Zero,screen=true,clipboard=true,shutdown=true,host=MouseHandle!=IntPtr.Zero&&KeyHandle!=IntPtr.Zero,width=r.Width,height=r.Height};
   if(method=="cursor") { POINT cursor; if(!GetCursorPos(out cursor)) throw new IOException("无法读取鼠标位置"); return new {x=cursor.x,y=cursor.y,width=r.Width,height=r.Height}; }
   if(method=="mode") { Remote=Bool(a,"remote");Lease=DateTime.UtcNow.AddSeconds(3);GetCursorPos(out Anchor);if(!Remote)Release();return new {}; }
   if(method=="heartbeat") {Lease=DateTime.UtcNow.AddSeconds(3);InputLease=DateTime.UtcNow.AddSeconds(3);return new {};}
   if(method=="release") { Release();return new {}; }
   if(method=="warp") {SetCursorPos(r.Left+(int)(Num(a,"x",0.5)*(r.Width-1)),r.Top+(int)(Num(a,"y",0.5)*(r.Height-1)));return new {};}
   if(method=="input") {
    InputLease=DateTime.UtcNow.AddSeconds(3);string kind=Str(a,"kind");
     if(kind=="relative") { RelativeX+=Num(a,"dx",0);RelativeY+=Num(a,"dy",0);int dx=(int)RelativeX,dy=(int)RelativeY;RelativeX-=dx;RelativeY-=dy;if(dx!=0||dy!=0){INPUT movement=new INPUT();movement.u.mi.dx=dx;movement.u.mi.dy=dy;movement.u.mi.flags=1;Send(movement);} }
    if(kind=="move")SetCursorPos(r.Left+(int)(Num(a,"x",0.5)*(r.Width-1)),r.Top+(int)(Num(a,"y",0.5)*(r.Height-1)));
    if(kind=="key")KeyInput(Vk(Str(a,"code")),Bool(a,"down"));
    if(kind=="button")MouseInput((int)Num(a,"button",0),Bool(a,"down"));
    if(kind=="wheel") {INPUT i=new INPUT();i.u.mi.flags=0x800;i.u.mi.data=unchecked((uint)(int)Num(a,"delta",0));Send(i);}
    return new {};
   }
   if(method=="text") {string s=Str(a,"text");foreach(char c in s) {INPUT i=new INPUT();i.type=1;i.u.ki.scan=c;i.u.ki.flags=4;Send(i);i.u.ki.flags=6;Send(i);}return new {};}
   if(method=="paste") { KeyInput(17,true);KeyInput(86,true);KeyInput(86,false);KeyInput(17,false);return new {}; }
   if(method=="clipboardGet") { var files=new List<string>();if(Clipboard.ContainsFileDropList())foreach(string f in Clipboard.GetFileDropList())files.Add(f);return new {text=Clipboard.ContainsText()?Clipboard.GetText():"",files=files}; }
   if(method=="clipboardSet") {if(a.ContainsKey("files")) {var list=new System.Collections.Specialized.StringCollection();foreach(object f in (System.Collections.IEnumerable)a["files"])list.Add(Convert.ToString(f));Clipboard.SetFileDropList(list);} else {string text=Str(a,"text");if(text.Length==0)Clipboard.Clear();else Clipboard.SetText(text);} ClipboardSequence=GetClipboardSequenceNumber();return new {};}
   if(method=="screen") {
    using(Bitmap full=new Bitmap(r.Width,r.Height)) {using(Graphics g=Graphics.FromImage(full))g.CopyFromScreen(r.Left,r.Top,0,0,r.Size);
     int width=Math.Min(1440,r.Width),height=(int)((double)r.Height*width/r.Width);using(Bitmap small=new Bitmap(full,width,height))using(MemoryStream output=new MemoryStream()) {small.Save(output,ImageFormat.Jpeg);return new {mime="image/jpeg",data=Convert.ToBase64String(output.ToArray()),width=width,height=height};}}
   }
   if(method=="shutdown") { Process.Start(new ProcessStartInfo("shutdown.exe","/s /t 0") {UseShellExecute=false,CreateNoWindow=true});return new {}; }
   throw new Exception("不支持的原生操作");
  }
  [STAThread] public static void Run() {
   Console.InputEncoding=new System.Text.UTF8Encoding(false);Console.OutputEncoding=new System.Text.UTF8Encoding(false);
   SetProcessDPIAware();Application.EnableVisualStyles();
   IntPtr module=GetModuleHandle(null);MouseHandle=SetWindowsHookEx(14,MouseCallback,module,0);KeyHandle=SetWindowsHookEx(13,KeyCallback,module,0);
   ClipboardSequence=GetClipboardSequenceNumber();
   Thread reader=new Thread(delegate() {string line;while((line=Console.ReadLine())!=null)Queue.Enqueue(line);Queue.Enqueue("EXIT");});reader.IsBackground=true;reader.Start();
   var timer=new System.Windows.Forms.Timer();timer.Interval=16;timer.Tick+=delegate {
    string line;int count=0;while(count++<100 && Queue.TryDequeue(out line)) {if(line=="EXIT"){Application.Exit();return;}string id="";try {var m=Json.Deserialize<Dictionary<string,object>>(line);id=Convert.ToString(m["id"]);var result=Execute(Convert.ToString(m["method"]),(Dictionary<string,object>)m["args"]);Emit(new {replyTo=id,result=result});}catch(Exception e){Emit(new {replyTo=id,error=e.Message});}}
    if(Remote&&DateTime.UtcNow>Lease){Remote=false;Release();Emit(new {type="emergency"});}
    if((Held.Count>0||Buttons.Count>0)&&DateTime.UtcNow>InputLease)Release();
    if(!Remote) {POINT p;GetCursorPos(out p);Rectangle r=Screen.PrimaryScreen.Bounds;Emit(new {type="cursor",x=p.x-r.Left,y=p.y-r.Top,width=r.Width,height=r.Height});}
    if(++TickCount%30==0){uint seq=GetClipboardSequenceNumber();if(seq!=ClipboardSequence){ClipboardSequence=seq;Emit(new {type="clipboardChanged"});}}
   };timer.Start();Application.Run();timer.Stop();Remote=false;Release();UnhookWindowsHookEx(MouseHandle);UnhookWindowsHookEx(KeyHandle);
  }
 }
}
