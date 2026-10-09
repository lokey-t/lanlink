using System;
using LanLink.Desktop;

public static class WindowsUiTextTest {
  static int cases;
  static void Equal(string expected,string actual,string context) { cases++; if(expected!=actual)throw new Exception(context+": expected "+expected+" but got "+actual); }
  public static void Main() {
    Equal("00123456",UiText.PairingCode("00123456"),"Leading zeroes");
    Equal("00123456",UiText.PairingCode(" 0012 3456\r\n"),"Grouped copy with line ending");
    Equal("00123456",UiText.PairingCode("0012\u00a03456"),"Nonbreaking space copied from another device");
    Equal("00123456",UiText.PairingCode("0012\u202f3456"),"Narrow nonbreaking space");
    Equal("1234",UiText.PairingCode("12 34"),"Partial paste may be completed by typing");
    foreach(string invalid in new[]{"", "1234AB56", "１２３４５６７８", "123456789", "1234-5678", "\t\r\n"})Equal(null,UiText.PairingCode(invalid),"Invalid pasted credential "+invalid);
    Equal(null,UiText.PairingCode(null),"Clipboard does not contain text");
    foreach(string prefix in new[]{"PERMISSION_REQUIRED", "CONTROL_CONFLICT", "CONSENT_DENIED", "ADB_SETUP_REQUIRED", "VIDEO_H264_REQUIRED", "CONTROL_ENDED"})Equal("请重试",UiText.ErrorMessage(prefix+": 请重试"),"Actionable reason without internal code");
    Equal("请重试",UiText.ErrorMessage("Error: PERMISSION_REQUIRED: 请重试"),"Nested transport error prefix");
    Equal("磁盘空间不足，请清理空间或选择其他位置。",UiText.ErrorMessage("ENOSPC: no space left on device, copyfile '/private/file'"),"Disk full does not expose internal paths");
    Equal("目标已有同名文件，请选择其他名称。",UiText.ErrorMessage("EEXIST"),"Save conflict preserves source and explains recovery");
    Equal("操作未完成，请检查设备连接和权限后重试。",UiText.ErrorMessage("PERMISSION_REQUIRED"),"Bare error code");
    Equal("目标离线，请打开对方 LanLink。",UiText.ErrorMessage("目标离线，请打开对方 LanLink。"),"Already useful message preserved");
    foreach(string state in new[]{"queued","pending","offered","accepted","sending","receiving","failed","cancelled"}){cases++;if(UiText.TransferState(state,true,100,100).Contains("已接收"))throw new Exception("Only a completed receipt may claim completion: "+state);}
    Equal("对方已接收",UiText.TransferState("completed",true,0,100),"Completion receipt");
    Equal("已取消",UiText.TransferState("cancelled",false,50,100),"Cancelled inbound can be retried");
    Equal("传输中 50%",UiText.TransferState("receiving",false,50,100),"Visible receiving progress");
    Equal("传输中 100%",UiText.TransferState("receiving",false,200,100),"Progress clamps out-of-order counters");
    Console.WriteLine("Windows text and state regressions passed ("+cases+" cases).");
  }
}
