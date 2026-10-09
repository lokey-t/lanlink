using System;
using System.Collections.Generic;
using System.Text.RegularExpressions;

namespace LanLink.Desktop {
  // Shared text rules are independent of WPF so malformed input and failure states
  // can be regression-tested on every build host.
  public static class UiText {
    public static string PairingCode(string value) {
      if(value==null)return null;
      string digits=Regex.Replace(value,@"\s", "");
      return Regex.IsMatch(digits,"^[0-9]{1,8}$")?digits:null;
    }
    public static string TransferState(string state,bool sending,double received,double size) {
      switch(state){
        case "ready":case "completed":case "complete":return sending?"对方已接收":"已接收";
        case "receiving":case "sending":return sending?"发送中":"传输中 "+(Math.Min(100,Math.Max(0,100*received/Math.Max(1,size)))).ToString("0")+"%";
        case "pending":case "offered":case "accepted":return sending?"等待对方接收":"待传输";
        case "queued":return "等待传输";
        case "cancelled":case "canceled":return "已取消";
        default:return "传输失败";
      }
    }
    public static string ErrorMessage(string message) {
      string value = string.IsNullOrWhiteSpace(message) ? "操作失败，请重试。" : message.Trim();
      var known = new Dictionary<string,string> { {"EACCES", "没有访问此位置的权限，请选择其他文件夹。"}, {"EPERM", "系统未允许此操作，请检查权限后重试。"}, {"ENOSPC", "磁盘空间不足，请清理空间或选择其他位置。"}, {"ENOENT", "文件或位置已不存在，请刷新后重试。"}, {"EEXIST", "目标已有同名文件，请选择其他名称。"}, {"ECONNREFUSED", "目标设备未响应，请确认对方已打开 LanLink。"}, {"ENOTFOUND", "找不到该设备地址，请检查地址后重试。"}, {"ETIMEDOUT", "连接超时，请确认设备在线且处于同一局域网。"} };
      foreach(var pair in known) if(value.StartsWith(pair.Key + ":") || value == pair.Key) return pair.Value;
      value = System.Text.RegularExpressions.Regex.Replace(value, @"^(?:Error:\s*)?(?:[A-Z][A-Z0-9_]+:\s*)+", "");
      if(System.Text.RegularExpressions.Regex.IsMatch(value, @"^[A-Z][A-Z0-9_]+$")) return "操作未完成，请检查设备连接和权限后重试。";
      return value;
    }
  }
}
