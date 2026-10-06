# Real cursor confinement and synthetic Raw Input packets; no desktop clicks or key input.
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Windows.Forms,System.Drawing,System.Web.Extensions
$bridgeSource=Join-Path $PSScriptRoot '../native/windows/Bridge.cs'
$probe=@'
public static class CaptureProbe {
 public static void Run() {
  Type type=typeof(LanLink.Bridge); BindingFlags flags=BindingFlags.NonPublic|BindingFlags.Static;
  MethodInfo execute=type.GetMethod("Execute",flags),cursor=type.GetMethod("GetCursorPos",flags),clip=type.GetMethod("GetClipCursor",flags);
  object[] position={null},rectangle={null};cursor.Invoke(null,position);clip.Invoke(null,rectangle);object old=position[0],oldClip=rectangle[0];Type point=old.GetType();
  int oldX=(int)point.GetField("x").GetValue(old),oldY=(int)point.GetField("y").GetValue(old);
  TextWriter original=Console.Out;IntPtr memory=IntPtr.Zero;
  try {
   execute.Invoke(null,new object[]{"mode",new Dictionary<string,object>{{"remote",true}}});
   cursor.Invoke(null,position);if((int)point.GetField("x").GetValue(position[0])!=oldX||(int)point.GetField("y").GetValue(position[0])!=oldY)throw new Exception("Capture moved the desktop cursor");
   clip.Invoke(null,rectangle);Type rect=rectangle[0].GetType();if((int)rect.GetField("left").GetValue(rectangle[0])!=oldX||(int)rect.GetField("right").GetValue(rectangle[0])!=oldX+1)throw new Exception("Desktop cursor is not confined in place");
   Type header=type.GetNestedType("RAWHEADER",BindingFlags.NonPublic),mouse=type.GetNestedType("RAWMOUSE",BindingFlags.NonPublic);int headerSize=Marshal.SizeOf(header);object h=Activator.CreateInstance(header),m=Activator.CreateInstance(mouse);
   mouse.GetField("dx").SetValue(m,18);mouse.GetField("dy").SetValue(m,-9);memory=Marshal.AllocHGlobal(headerSize+Marshal.SizeOf(mouse));Marshal.StructureToPtr(h,memory,false);Marshal.StructureToPtr(m,IntPtr.Add(memory,headerSize),false);
   var output=new StringWriter();Console.SetOut(output);type.GetMethod("ReadRawMouse",flags).Invoke(null,new object[]{memory});
   var data=new JavaScriptSerializer().Deserialize<Dictionary<string,object>>(output.ToString());if(Convert.ToInt32(data["dx"])!=18||Convert.ToInt32(data["dy"])!=-9)throw new Exception("Raw relative movement was clipped");
   output.GetStringBuilder().Clear();mouse.GetField("extra").SetValue(m,0x4c414e4cu);Marshal.StructureToPtr(m,IntPtr.Add(memory,headerSize),false);type.GetMethod("ReadRawMouse",flags).Invoke(null,new object[]{memory});if(output.ToString()!="")throw new Exception("Injected recovery movement re-entered captured raw input");
   execute.Invoke(null,new object[]{"input",new Dictionary<string,object>{{"kind","relative"},{"dx",0},{"dy",0}}});if(!(bool)type.GetField("SyntheticCursor",flags).GetValue(null))throw new Exception("Network pointer correction was not marked as synthetic");
   var availability=type.GetMethod("UpdateDesktopAvailability",flags);
   output.GetStringBuilder().Clear();availability.Invoke(null,new object[]{false});availability.Invoke(null,new object[]{false});
   if((bool)type.GetField("Remote",flags).GetValue(null))throw new Exception("Secure desktop did not release capture");
   string[] interrupted=output.ToString().Trim().Split('\n');if(interrupted.Length!=1 || !interrupted[0].Contains("input-interrupted"))throw new Exception("Interruption should be emitted only once");
   bool rejected=false;try{execute.Invoke(null,new object[]{"mode",new Dictionary<string,object>{{"remote",true}}});}catch(TargetInvocationException){rejected=true;}if(!rejected)throw new Exception("Capture allowed while secure desktop was active");
   availability.Invoke(null,new object[]{true});if((bool)type.GetField("Remote",flags).GetValue(null))throw new Exception("Desktop recovery resumed capture automatically");
   execute.Invoke(null,new object[]{"mode",new Dictionary<string,object>{{"remote",false}}});
   cursor.Invoke(null,position);if((int)point.GetField("x").GetValue(position[0])!=oldX||(int)point.GetField("y").GetValue(position[0])!=oldY)throw new Exception("Release moved the desktop cursor");
   clip.Invoke(null,rectangle);foreach(var field in oldClip.GetType().GetFields())if(!field.GetValue(oldClip).Equals(field.GetValue(rectangle[0])))throw new Exception("Clip bounds not restored");
   output.GetStringBuilder().Clear();
   ProbeKey(type,flags,162,true);ProbeKey(type,flags,164,true);
   execute.Invoke(null,new object[]{"mode",new Dictionary<string,object>{{"remote",true}}});
   var localUps=(System.Collections.Generic.HashSet<int>)type.GetField("LocalModifierUps",flags).GetValue(null);if(localUps.Count!=2)throw new Exception("Held local modifiers were not retained for release");
   ProbeKey(type,flags,162,false);ProbeKey(type,flags,164,false);if(localUps.Count!=0)throw new Exception("Local modifier releases were swallowed");
   var masks=new List<int>();foreach(string row in output.ToString().Trim().Split('\n')){var item=new JavaScriptSerializer().Deserialize<Dictionary<string,object>>(row);if(Convert.ToString(item["type"])=="modifiers")masks.Add(Convert.ToInt32(item["mask"]));}if(string.Join(",",masks)!="1,3,2,0")throw new Exception("Physical shortcut modifier states were incorrect");
  } finally {
   Console.SetOut(original);execute.Invoke(null,new object[]{"mode",new Dictionary<string,object>{{"remote",false}}});if(memory!=IntPtr.Zero)Marshal.FreeHGlobal(memory);
   var raw=type.GetField("Raw",flags).GetValue(null) as IDisposable;if(raw!=null)raw.Dispose();type.GetField("Raw",flags).SetValue(null,null);
  }
  Console.WriteLine("PASS: Windows capture/release preserves cursor coordinates; real one-pixel confinement restored; raw relative deltas survive clipping. No global hooks installed and no clicks/keys generated.");
 }
 static void ProbeKey(Type bridge,BindingFlags flags,int vk,bool down){Type key=bridge.GetNestedType("KEY",BindingFlags.NonPublic);object data=Activator.CreateInstance(key);key.GetField("vk").SetValue(data,(uint)vk);IntPtr memory=Marshal.AllocHGlobal(Marshal.SizeOf(key));try{Marshal.StructureToPtr(data,memory,false);bridge.GetMethod("KeyHook",flags).Invoke(null,new object[]{0,new IntPtr(down?0x100:0x101),memory});}finally{Marshal.FreeHGlobal(memory);}}
}
'@
Add-Type -TypeDefinition ('using System.Reflection;' + (Get-Content -LiteralPath $bridgeSource -Raw -Encoding UTF8) + $probe) -ReferencedAssemblies System.Windows.Forms,System.Drawing,System.Web.Extensions
[CaptureProbe]::Run()
