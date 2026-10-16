package com.lanlink.app;

import android.app.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import org.json.*;

/** Read-only whole-group preview; master selection uses its separate explicit control. */
final class GroupLayoutPanel extends LinearLayout {
  private final Activity activity;
  private final LinkService service;
  private final Ui ui;
  private String signature = "";
  private boolean busy;
  GroupLayoutPanel(Activity activity, Ui ui, LinkService service) {
    super(activity); this.activity = activity; this.ui = ui; this.service = service;
    setOrientation(VERTICAL); refresh();
  }
  private String label(JSONObject devices, String id) {
    JSONObject device = devices.optJSONObject(id == null ? "" : id);
    return device == null ? Language.t("未选择") : device.optString("name", Language.t("设备"));
  }
  private List<String> ids(JSONObject value) { ArrayList<String> ids = new ArrayList<>(); for (Iterator<String> it = value.keys(); it.hasNext();) ids.add(it.next()); Collections.sort(ids); return ids; }
  interface Change { void run() throws Exception; }
  private void change(Change action) {
    if (busy) return; busy = true;
    service.jobs.execute(() -> {
      String failure = null; try { action.run(); } catch (Exception error) { failure = error.getMessage(); }
      String message = failure;
      activity.runOnUiThread(() -> { busy = false; if (message != null) Toast.makeText(activity, Language.t(message), Toast.LENGTH_LONG).show(); signature = ""; refresh(); });
    });
  }
  void refresh() {
    JSONObject graph = service.groupLayout.layout(), devices = service.groupLayout.devices(), master = service.groupLayout.master();
    String next = graph.toString() + devices.toString() + master;
    if (next.equals(signature)) return; signature = next;
    JSONObject positions = graph.optJSONObject("positions"); if (positions == null) positions = new JSONObject();
    removeAllViews();
    LinearLayout masterCard = ui.card();
    masterCard.addView(ui.item("monitor", Language.t("主控电脑"), master == null ? Language.t("尚未选择主控电脑") : label(devices, master.optString("id")), Language.t("更改"), () -> chooseMaster(devices)));
    ui.gap(masterCard, 8); masterCard.addView(ui.text(Language.t("由这台 Mac 或 Windows 电脑的鼠标跨屏移动；手机可修改组内选择。"), 12, Ui.MUTED));
    addView(masterCard); ui.gap(this, 16);
    LinearLayout map = ui.card(); map.addView(ui.title(Language.t("全组设备布局"), 17)); ui.gap(map, 8);
    map.setTag("layoutPreview");
    map.addView(ui.text(Language.t("仅预览全组设备布局，请在电脑端调整设备位置。"), 13, Ui.MUTED)); ui.gap(map, 12);
    if (positions.length() == 0) map.addView(ui.text(Language.t("布局还是空的，请在电脑端设置设备布局。"), 14, Ui.MUTED));
    else {
      ScrollView vertical = new ScrollView(activity); HorizontalScrollView horizontal = new HorizontalScrollView(activity);
      horizontal.addView(new Graph(devices, positions, master == null ? null : master.optString("id")));
      int minY = 32, maxY = -32; for (String id : ids(positions)) { int y = positions.optJSONObject(id).optInt("y"); minY = Math.min(minY, y); maxY = Math.max(maxY, y); }
      vertical.addView(horizontal); map.addView(vertical, new LayoutParams(-1, ui.dp(Math.min(280, (maxY - minY + 1) * 96 + 16))));
    }
    addView(map);
  }
  private void chooseMaster(JSONObject devices) {
    List<String> choices = new ArrayList<>(); for (String id : ids(devices)) if (GroupLayout.desktop(devices.optJSONObject(id))) choices.add(id);
    if (choices.isEmpty()) { Toast.makeText(activity, Language.t("请先将 Mac 或 Windows 电脑加入当前组"), Toast.LENGTH_LONG).show(); return; }
    String[] names = new String[choices.size()]; for (int i = 0; i < names.length; i++) names[i] = label(devices, choices.get(i));
    JSONObject current = service.groupLayout.master();
    new AlertDialog.Builder(activity).setTitle(Language.t("选择主控电脑")).setSingleChoiceItems(names, current == null ? -1 : choices.indexOf(current.optString("id")), (dialog, which) -> { String id = choices.get(which); dialog.dismiss(); change(() -> service.groupLayout.chooseMaster(id)); }).setNegativeButton(Language.t("取消"), null).show();
  }
  private final class Graph extends FrameLayout {
    private final JSONObject devices, positions;
    private final String master;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Map<String, RectF> bounds = new LinkedHashMap<>();
    private final int width, height;
    Graph(JSONObject devices, JSONObject positions, String master) {
      super(activity); this.devices = devices; this.positions = positions; this.master = master;
      int minX = 32, minY = 32, maxX = -32, maxY = -32;
      for (String id : ids(positions)) { JSONObject p = positions.optJSONObject(id); minX = Math.min(minX, p.optInt("x")); minY = Math.min(minY, p.optInt("y")); maxX = Math.max(maxX, p.optInt("x")); maxY = Math.max(maxY, p.optInt("y")); }
      int stepX = ui.dp(136), stepY = ui.dp(96), pad = ui.dp(8);
      width = (maxX - minX + 1) * stepX + pad * 2; height = (maxY - minY + 1) * stepY + pad * 2;
      for (String id : ids(positions)) { JSONObject p = positions.optJSONObject(id); float x = pad + (p.optInt("x") - minX) * stepX, y = pad + (p.optInt("y") - minY) * stepY; bounds.put(id, new RectF(x, y, x + ui.dp(120), y + ui.dp(76))); }
      setContentDescription(Language.t("全组设备布局")); setWillNotDraw(false);
      for (String id : bounds.keySet()) {
        RectF box = bounds.get(id); LinearLayout tile = ui.column(); tile.setGravity(Gravity.CENTER);
        tile.setPadding(ui.dp(7), ui.dp(10), ui.dp(7), ui.dp(10));
        tile.setBackground(ui.outlinedShape(Ui.BG, 14, id.equals(master) ? Ui.PURPLE : Ui.LINE));
        TextView name = ui.title(label(devices, id), 13); name.setMaxLines(1); name.setEllipsize(android.text.TextUtils.TruncateAt.END); tile.addView(name);
        ui.gap(tile, 7);
        String tag = id.equals(master) ? Language.t("主控电脑") : id.equals(service.deviceId) ? Language.t("本机") : GroupLayout.desktop(devices.optJSONObject(id)) ? Language.t("电脑") : Language.t("移动设备");
        TextView status = ui.text(tag, 11, id.equals(master) ? Ui.PURPLE : Ui.MUTED);
        if (id.equals(master)) { status.setPadding(ui.dp(6), ui.dp(2), ui.dp(6), ui.dp(2)); status.setBackground(ui.outlinedShape(Ui.LILAC, 8, Ui.PURPLE)); status.setTag("layoutMasterBadge"); }
        tile.addView(status); tile.setTag("layoutDevice-" + id);
        tile.setContentDescription(label(devices, id) + "，" + tag);
        tile.setClickable(false); tile.setLongClickable(false); tile.setFocusable(false);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams((int) box.width(), (int) box.height()); params.leftMargin = (int) box.left; params.topMargin = (int) box.top; addView(tile, params);
      }
    }
    protected void onMeasure(int w, int h) { super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)); }
    protected void onDraw(Canvas canvas) {
      paint.setColor(Ui.LINE); paint.setStrokeWidth(ui.dp(3));
      for (String a : bounds.keySet()) for (String b : bounds.keySet()) if (a.compareTo(b) < 0) {
        JSONObject p = positions.optJSONObject(a), q = positions.optJSONObject(b);
        if (Math.abs(p.optInt("x") - q.optInt("x")) + Math.abs(p.optInt("y") - q.optInt("y")) != 1) continue;
        RectF from = bounds.get(a), to = bounds.get(b);
        float gap = ui.dp(3);
        if (p.optInt("y") == q.optInt("y")) {
          boolean right = from.centerX() < to.centerX();
          canvas.drawLine(right ? from.right + gap : from.left - gap, from.centerY(), right ? to.left - gap : to.right + gap, to.centerY(), paint);
        } else {
          boolean down = from.centerY() < to.centerY();
          canvas.drawLine(from.centerX(), down ? from.bottom + gap : from.top - gap, to.centerX(), down ? to.top - gap : to.bottom + gap, paint);
        }
      }
    }

  }
}
