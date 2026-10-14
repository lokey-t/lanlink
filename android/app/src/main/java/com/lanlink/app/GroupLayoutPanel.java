package com.lanlink.app;

import android.app.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import org.json.*;

/** The same whole-group map on every device; selecting a master never moves tiles. */
final class GroupLayoutPanel extends LinearLayout {
  private final Activity activity;
  private final LinkService service;
  private final Ui ui;
  private String signature = "", selected, anchor;
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
    String next = graph.toString() + devices.toString() + master + selected + anchor;
    if (next.equals(signature)) return; signature = next;
    JSONObject positions = graph.optJSONObject("positions"); if (positions == null) positions = new JSONObject();
    if (selected == null || !devices.has(selected)) selected = devices.has(service.deviceId) ? service.deviceId : ids(devices).stream().findFirst().orElse(null);
    if (anchor == null || !positions.has(anchor)) anchor = master != null && positions.has(master.optString("id")) ? master.optString("id") : ids(positions).stream().findFirst().orElse(null);
    removeAllViews();
    LinearLayout masterCard = ui.card();
    masterCard.addView(ui.item("monitor", Language.t("主控电脑"), master == null ? Language.t("尚未选择主控电脑") : label(devices, master.optString("id")), Language.t("更改"), () -> chooseMaster(devices)));
    ui.gap(masterCard, 8); masterCard.addView(ui.text(Language.t("由这台 Mac 或 Windows 电脑的鼠标跨屏移动；手机可修改组内选择。"), 12, Ui.MUTED));
    addView(masterCard); ui.gap(this, 16);
    LinearLayout map = ui.card(); map.addView(ui.title(Language.t("全组设备布局"), 17)); ui.gap(map, 8);
    map.addView(ui.text(Language.t("点击电脑切换主控，主控标识会同步到同组设备。长按设备可选为摆放参考。"), 13, Ui.MUTED)); ui.gap(map, 12);
    if (positions.length() == 0) map.addView(ui.text(Language.t("布局还是空的，请先放置第一台设备。"), 14, Ui.MUTED));
    else {
      ScrollView vertical = new ScrollView(activity); HorizontalScrollView horizontal = new HorizontalScrollView(activity);
      horizontal.addView(new Graph(devices, positions, master == null ? null : master.optString("id")));
      int minY = 32, maxY = -32; for (String id : ids(positions)) { int y = positions.optJSONObject(id).optInt("y"); minY = Math.min(minY, y); maxY = Math.max(maxY, y); }
      vertical.addView(horizontal); map.addView(vertical, new LayoutParams(-1, ui.dp(Math.min(280, (maxY - minY + 1) * 96 + 16))));
    }
    ui.gap(map, 12); map.addView(ui.text(Language.t("参考设备：") + label(devices, anchor), 13, Ui.MUTED));
    addView(map); ui.gap(this, 16);
    LinearLayout edit = ui.card(); edit.addView(ui.title(Language.t("摆放设备"), 17)); ui.gap(edit, 12);
    List<String> choices = ids(devices); String[] names = new String[choices.size()];
    for (int i = 0; i < names.length; i++) names[i] = label(devices, choices.get(i));
    Spinner select = new Spinner(activity); select.setContentDescription(Language.t("选择要摆放的设备"));
    ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, names); select.setAdapter(adapter);
    select.setSelection(Math.max(0, choices.indexOf(selected)), false); edit.addView(select, new LayoutParams(-1, ui.dp(48)));
    select.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
      public void onNothingSelected(AdapterView<?> parent) {}
      public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { String value = choices.get(position); if (!value.equals(selected)) { selected = value; signature = ""; refresh(); } }
    });
    ui.gap(edit, 12);
    if (positions.length() == 0) edit.addView(ui.button(Language.t("放置第一台设备"), true, () -> { String id = selected; change(() -> service.groupLayout.place(id, 0, 0)); }));
    else {
      JSONObject reference = positions.optJSONObject(anchor);
      LinearLayout directions = ui.row();
      String[] labels = {"左侧", "右侧", "上方", "下方"}; int[] dx = {-1, 1, 0, 0}, dy = {0, 0, -1, 1};
      for (int i = 0; i < 4; i++) {
        final int x = reference == null ? 0 : reference.optInt("x") + dx[i], y = reference == null ? 0 : reference.optInt("y") + dy[i];
        Button button = ui.button(Language.t(labels[i]), false, () -> { String id = selected; change(() -> service.groupLayout.place(id, x, y)); });
        button.setContentDescription(Language.t("放在参考设备") + Language.t(labels[i])); button.setEnabled(reference != null && !Objects.equals(selected, anchor));
        directions.addView(button, new LayoutParams(0, -2, 1));
      }
      edit.addView(directions);
      ui.gap(edit, 8);
      if (positions.has(selected)) {
        edit.addView(ui.button(Language.t("设为参考设备"), false, () -> { anchor = selected; signature = ""; refresh(); }));
        edit.addView(ui.button(Language.t("移出布局"), false, () -> { String id = selected; change(() -> service.groupLayout.remove(id)); }));
      }
    }
    ui.gap(edit, 12); edit.addView(ui.text(Language.t("布局和主控选择只在当前组内同步，已摆放的设备不会随主控切换而移动。"), 12, Ui.MUTED)); addView(edit);
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
        tile.setBackground(ui.outlinedShape(id.equals(anchor) ? Ui.LILAC : Ui.BG, 14, id.equals(master) || id.equals(anchor) ? Ui.PURPLE : Ui.LINE));
        TextView name = ui.title(label(devices, id), 13); name.setMaxLines(1); name.setEllipsize(android.text.TextUtils.TruncateAt.END); tile.addView(name);
        ui.gap(tile, 7);
        String tag = id.equals(master) ? Language.t("主控电脑") : id.equals(service.deviceId) ? Language.t("本机") : GroupLayout.desktop(devices.optJSONObject(id)) ? Language.t("电脑") : Language.t("移动设备");
        TextView status = ui.text(tag, 11, id.equals(master) ? Ui.PURPLE : Ui.MUTED);
        if (id.equals(master)) { status.setPadding(ui.dp(6), ui.dp(2), ui.dp(6), ui.dp(2)); status.setBackground(ui.outlinedShape(Ui.LILAC, 8, Ui.PURPLE)); status.setTag("layoutMasterBadge"); }
        tile.addView(status); tile.setTag("layoutDevice-" + id);
        tile.setContentDescription(label(devices, id) + "，" + tag);
        tile.setOnClickListener(view -> {
          if (GroupLayout.desktop(devices.optJSONObject(id))) {
            if (!id.equals(master)) change(() -> service.groupLayout.chooseMaster(id));
          } else { anchor = id; signature = ""; refresh(); }
        });
        tile.setOnLongClickListener(view -> { anchor = id; signature = ""; refresh(); return true; });
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
