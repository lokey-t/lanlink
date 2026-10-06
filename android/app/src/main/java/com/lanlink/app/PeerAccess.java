package com.lanlink.app;
import org.json.*;
final class PeerAccess {
  static String[] keys(String platform) {return "android".equals(platform)?DeviceConsent.KEYS:new String[]{"input","screen","clipboard","shutdown"};}
  static String status(JSONObject report,String platform) {
    JSONObject p=report==null?null:report.optJSONObject("permissions");if(p==null)return "未就绪";
    int total=0,allowed=0;
    for(String key:keys(platform)){JSONObject row=p.optJSONObject(key);if(row!=null&&!row.optBoolean("supported",true))continue;total++;if(row==null||!row.optBoolean("systemGranted")||!row.optBoolean("appEnabled"))return "未就绪";if(row.optBoolean("peerAllowed"))allowed++;}
    return allowed==0?"未授权":allowed<total?"有限授权":"在线";
  }
  static boolean allowed(JSONObject report,String... keys) {
    JSONObject p=report==null?null:report.optJSONObject("permissions");if(p==null)return false;
    for(String key:keys){JSONObject row=p.optJSONObject(key);if(row==null||!row.optBoolean("peerAllowed"))return false;}return true;
  }
}
