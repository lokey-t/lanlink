package com.lanlink.app;

import static com.lanlink.app.Wire.obj;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.graphics.*;
import android.media.projection.MediaProjectionManager;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

public class MainActivity extends Activity {
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ThreadPoolExecutor actions =
      new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
  private final ExecutorService frames = Executors.newSingleThreadExecutor();
  private Ui ui;
  private LinearLayout root, layout, devices, detail, noticeBox, navigation;
  private TextView status, mode;
  private final Map<String, Switch> permissionSwitches = new HashMap<>();
  private final Set<Dialog> sheets = new HashSet<>();
  private String page = "devices", selected = "local", noticeSignature = "";
  private boolean resumed;
  private Dialog viewer;
  private Dialog pairDialog;
  private Dialog consentDialog;
  private Dialog permissionDialog;
  private String permissionDialogKey = "";
  private String consentRequestId = "";
  private final Map<Button, java.util.function.BooleanSupplier> permissionRows = new HashMap<>();
  private final Map<Button, String> permissionTitles = new HashMap<>();
  private ImageView image;
  private volatile String viewing;
  private volatile boolean alive = true;
  private final Runnable refresh =
      new Runnable() {
        public void run() {
          if (!alive) return;
          update();
          main.postDelayed(this, 1500);
        }
      };

  interface Job {
    void run() throws Exception;
  }

  private LinkService link() throws Exception {
    LinkService s = LinkService.instance;
    if (s == null) throw new Exception("服务尚未启动");
    return s;
  }

  private void run(Job job) {
    actions.execute(
        () -> {
          try {
            job.run();
          } catch (Exception e) {
            main.post(() -> toast(e.getMessage() == null ? "操作失败" : e.getMessage()));
          }
        });
  }

  private void toast(String s) {
    Toast.makeText(this, displayError(s), Toast.LENGTH_LONG).show();
  }

  private String displayError(String s) {
    return s == null
        ? "操作失败"
        : s.replace("CONSENT_PENDING: ", "")
            .replace("CONSENT_DENIED: ", "")
            .replace("PERMISSION_REQUIRED: ", "")
            .replace("CONTROL_CONFLICT: ", "")
            .replace("CONTROL_ENDED: ", "")
            .replace("FALLBACK_CONFIRMATION: ", "");
  }

  private void showPairingDialog(String scannedCode, String scannedId) {
    if (pairDialog != null) pairDialog.dismiss();
    Dialog dialog = new Dialog(this, android.R.style.Theme_Material_Light_NoActionBar);
    pairDialog = dialog;
    LinearLayout outer = ui.column();
    outer.setBackgroundColor(Ui.BG);
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    LinearLayout box = ui.column();
    box.setPadding(ui.dp(24), ui.dp(18), ui.dp(24), ui.dp(28));
    scroll.addView(box);
    outer.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
    dialog.setContentView(outer);
    box.addView(
        ui.iconButton("back", "返回设备", dialog::dismiss),
        new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
    ui.gap(box, 12);
    box.addView(ui.title("添加设备", 30));
    ui.gap(box, 8);
    box.addView(ui.text("连接同一 Wi-Fi，轻松加入", 14, Ui.MUTED));
    ui.gap(box, 18);
    LinearLayout joinCard = ui.card();
    box.addView(joinCard);
    TextView joinTitle = ui.title("加入其他组", 18);
    joinCard.addView(joinTitle);
    ui.gap(joinCard, 8);
    joinCard.addView(ui.text("扫描另一台设备的邀请二维码", 12, Ui.MUTED));
    View scanIcon = ui.icon("scan", Ui.PURPLE);
    LinearLayout.LayoutParams scanIconSize = new LinearLayout.LayoutParams(ui.dp(52), ui.dp(52));
    scanIconSize.gravity = Gravity.CENTER_HORIZONTAL;
    scanIconSize.setMargins(0, ui.dp(14), 0, ui.dp(12));
    joinCard.addView(scanIcon, scanIconSize);
    Button scan =
        ui.button(
            "点击扫码", true, () -> startActivityForResult(new Intent(this, ScanActivity.class), 32));
    joinCard.addView(scan);
    ui.gap(joinCard, 10);
    LinearLayout entries = ui.row();
    joinCard.addView(entries);
    LinearLayout manual = ui.row();
    manual.setGravity(Gravity.CENTER);
    manual.setPadding(0, ui.dp(10), 0, ui.dp(10));
    manual.setBackground(ui.touch(Color.TRANSPARENT, 12));
    manual.setContentDescription("手动输入");
    manual.addView(
        ui.icon("keyboard", Ui.MUTED), new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)));
    TextView manualText = ui.text(" 手动输入", 12, Ui.MUTED);
    manual.addView(manualText);
    entries.addView(manual, new LinearLayout.LayoutParams(0, -2, 1));
    LinearLayout photo = ui.row();
    photo.setGravity(Gravity.CENTER);
    photo.setPadding(0, ui.dp(10), 0, ui.dp(10));
    photo.setBackground(ui.touch(Color.TRANSPARENT, 12));
    photo.setContentDescription("从图片选择");
    photo.addView(
        ui.icon("picture", Ui.MUTED), new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)));
    photo.addView(ui.text(" 从图片选择", 12, Ui.MUTED));
    photo.setOnClickListener(
        v -> {
          try {
            startActivityForResult(
                new Intent(Intent.ACTION_GET_CONTENT)
                    .setType("image/*")
                    .addCategory(Intent.CATEGORY_OPENABLE),
                31);
          } catch (ActivityNotFoundException e) {
            toast("没有可用的图片选择器，请使用扫码或手动输入");
          }
        });
    entries.addView(photo, new LinearLayout.LayoutParams(0, -2, 1));
    LinearLayout form = ui.card();
    form.setVisibility(scannedCode == null ? View.GONE : View.VISIBLE);
    joinCard.addView(form);
    form.addView(ui.title("输入其他组的配对码", 16));
    ui.gap(form, 8);
    form.addView(ui.text("输入对方「添加设备」页面的八位数字", 13, Ui.MUTED));
    ui.gap(form, 16);
    EditText entry = ui.edit("八位数字配对码");
    entry.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
    entry.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(8)});
    if (scannedCode != null) entry.setText(scannedCode);
    form.addView(entry);
    ui.gap(form, 12);
    Button submit = ui.button("配对并加入", true, () -> {});
    form.addView(submit);
    ui.gap(form, 8);
    TextView message = ui.text("", 12, Ui.MUTED);
    form.addView(message);
    ui.gap(box, 18);
    LinearLayout card = ui.card();
    box.addView(card);
    card.addView(ui.title("其他设备加入本组", 18));
    card.addView(ui.text("让新设备扫码，或输入下方八位数字", 12, Ui.MUTED));
    ImageView qr = new ImageView(this);
    qr.setContentDescription("二维码尚未显示");
    qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
    LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(ui.dp(116), ui.dp(116));
    qp.gravity = Gravity.CENTER_HORIZONTAL;
    qp.setMargins(0, ui.dp(12), 0, ui.dp(16));
    LinearLayout invite = ui.row();
    invite.setGravity(Gravity.CENTER_VERTICAL);
    card.addView(invite);
    LinearLayout qrColumn = ui.column();
    invite.addView(qrColumn, new LinearLayout.LayoutParams(ui.dp(128), -2));
    qrColumn.addView(qr, qp);
    LinearLayout codeColumn = ui.column();
    codeColumn.setPadding(ui.dp(14), 0, 0, 0);
    invite.addView(codeColumn, new LinearLayout.LayoutParams(0, -2, 1));
    Bitmap skeleton = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(skeleton);
    Paint paint = new Paint(3);
    paint.setColor(0xffb8aef0);
    paint.setStrokeWidth(8);
    paint.setStrokeCap(Paint.Cap.ROUND);
    paint.setStyle(Paint.Style.STROKE);
    for (int[] corner :
        new int[][] {{16, 16, 1, 1}, {184, 16, -1, 1}, {16, 184, 1, -1}, {184, 184, -1, -1}}) {
      canvas.drawLine(corner[0], corner[1], corner[0] + 30 * corner[2], corner[1], paint);
      canvas.drawLine(corner[0], corner[1], corner[0], corner[1] + 30 * corner[3], paint);
    }
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(0xffe1ddf6);
    for (int[] block :
        new int[][] {{58, 48}, {120, 47}, {88, 88}, {48, 123}, {121, 126}, {91, 151}})
      canvas.drawRoundRect(block[0], block[1], block[0] + 22, block[1] + 22, 6, 6, paint);
    qr.setImageBitmap(skeleton);
    Button reveal = ui.button("查看二维码", false, () -> {});
    LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
    rp.gravity = Gravity.CENTER_HORIZONTAL;
    reveal.setTextSize(12);
    reveal.setMinimumHeight(ui.dp(40));
    reveal.setPadding(0, 0, 0, 0);
    qrColumn.addView(reveal, rp);
    LinearLayout head = ui.row();
    head.addView(ui.title("本机配对码", 14), new LinearLayout.LayoutParams(0, -2, 1));

    codeColumn.addView(head);
    ui.gap(codeColumn, 12);
    TextView digits = ui.text("···· ····", 22, Ui.INK);
    digits.setTypeface(Typeface.MONOSPACE);
    digits.setGravity(Gravity.CENTER);
    digits.setLetterSpacing(0);
    digits.setSingleLine();
    if (android.os.Build.VERSION.SDK_INT >= 26)
      digits.setAutoSizeTextTypeUniformWithConfiguration(
          14, 22, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
    codeColumn.addView(digits);
    ui.gap(codeColumn, 18);
    ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    progress.setMax(60000);
    progress.setProgressTintList(android.content.res.ColorStateList.valueOf(0xffa797ec));
    progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Ui.LILAC));
    codeColumn.addView(progress, new LinearLayout.LayoutParams(-1, ui.dp(6)));
    ui.gap(codeColumn, 10);
    TextView countdown = ui.text("正在生成配对码…", 12, Ui.MUTED);
    countdown.setGravity(Gravity.END);
    codeColumn.addView(countdown);
    boolean[] showing = {false};
    String[] lastQr = {""};
    Runnable refreshCode =
        new Runnable() {
          public void run() {
            if (!dialog.isShowing()) return;
            try {
              Pairing pairing = link().pairing;
              if (pairing == null) throw new Exception("服务正在启动…");
              boolean formed = link().groupEstablished();
              joinTitle.setText("加入其他组");
              submit.setText(formed ? "解散当前组并加入其他组" : "配对并加入");
              JSONObject info = pairing.show();
              String code = info.getString("code"), id = info.getString("id");
              digits.setText(code.substring(0, 4) + " " + code.substring(4));
              long left = info.getLong("remainingMs");
              progress.setProgress((int) left);
              countdown.setText(((left + 999) / 1000) + " 秒后刷新");
              if (showing[0] && !id.equals(lastQr[0])) {
                String url = "lanlink://pair?v=2&code=" + code + "&id=" + id;
                com.google.zxing.common.BitMatrix matrix =
                    new com.google.zxing.qrcode.QRCodeWriter()
                        .encode(url, com.google.zxing.BarcodeFormat.QR_CODE, 320, 320);
                Bitmap bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888);
                for (int y = 0; y < 320; y++)
                  for (int x = 0; x < 320; x++)
                    bitmap.setPixel(x, y, matrix.get(x, y) ? Ui.INK : Color.WHITE);
                qr.setImageBitmap(bitmap);
                lastQr[0] = id;
              }
            } catch (Exception e) {
              digits.setText("···· ····");
              progress.setProgress(0);
              countdown.setText(e.getMessage());
              qr.setImageBitmap(skeleton);
              lastQr[0] = "";
            }
            main.postDelayed(this, 500);
          }
        };
    reveal.setOnClickListener(
        v -> {
          showing[0] = !showing[0];
          lastQr[0] = "";
          qr.setImageBitmap(skeleton);
          qr.setContentDescription(showing[0] ? "本机配对二维码" : "二维码尚未显示");
          reveal.setText(showing[0] ? "隐藏二维码" : "查看二维码");
          main.removeCallbacks(refreshCode);
          refreshCode.run();
        });
    manual.setOnClickListener(
        v -> {
          boolean open = form.getVisibility() != View.VISIBLE;
          form.setVisibility(open ? View.VISIBLE : View.GONE);
          manualText.setText(open ? " 收起输入" : " 手动输入");
          if (open) form.post(() -> scroll.smoothScrollTo(0, joinCard.getTop() + form.getTop()));
        });
    submit.setOnClickListener(
        v -> {
          String value = entry.getText().toString();
          if (!value.matches("[0-9]{8}")) {
            entry.setError("请输入完整的八位数字");
            return;
          }
          LinkService pairingService = LinkService.instance;
          if (pairingService == null || pairingService.group == null) {
            toast("组服务尚未就绪");
            return;
          }
          final boolean dissolve = pairingService.groupEstablished();
          final String expectedGroup = pairingService.group.data.optString("id");
          new AlertDialog.Builder(this)
              .setTitle(dissolve ? "解散当前组并加入其他组？" : "加入其他组？")
              .setMessage(
                  dissolve
                      ? "原组所有设备都会退出，并断开控制。离线设备收到解散通知后退出。先验证新组配对码，成功后才解散原组。"
                      : "本机将加入对方的组，在同一局域网内自动连接。")
              .setNegativeButton("取消", null)
              .setPositiveButton(
                  "加入",
                  (d, w) -> {
                    submit.setEnabled(false);
                    entry.setEnabled(false);
                    dialog.setCancelable(false);
                    message.setText("正在查找设备并配对…");
                    actions.execute(
                        () -> {
                          try {
                            LinkService s = link();
                            if (s.pairing == null) throw new Exception("配对服务尚未就绪");
                            JSONObject result =
                                s.pairing.join(value, value.equals(scannedCode) ? scannedId : null);
                            s.joinGroup(result, dissolve, expectedGroup);
                            main.post(
                                () -> {
                                  dialog.dismiss();
                                  toast("已加入组，正在连接设备");
                                  signature = "";
                                  update();
                                });
                          } catch (Exception e) {
                            main.post(
                                () -> {
                                  message.setText(e.getMessage());
                                  submit.setEnabled(true);
                                  entry.setEnabled(true);
                                  dialog.setCancelable(true);
                                });
                          }
                        });
                  })
              .show();
        });
    dialog.setOnDismissListener(
        d -> {
          main.removeCallbacks(refreshCode);
          if (LinkService.instance != null && LinkService.instance.pairing != null)
            LinkService.instance.pairing.hide();
          if (pairDialog == dialog) pairDialog = null;
        });
    dialog.show();
    Window w = dialog.getWindow();
    if (w != null) {
      w.setLayout(-1, -1);
      w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
      applyInsets(outer, w);
    }
    refreshCode.run();
  }

  public void onCreate(Bundle state) {
    super.onCreate(state);
    ui = new Ui(this);
    if (state != null) {
      page = state.getString("page", "devices");
      selected = state.getString("selected", "local");
    }
    startForegroundService(new Intent(this, LinkService.class));
    renderPage();
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED)
      requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 20);
    main.post(refresh);
    handleIntent(getIntent());
  }

  private void applyInsets(View view, Window window) {
    window.setStatusBarColor(Ui.BG);
    window.setNavigationBarColor(Ui.BG);
    window
        .getDecorView()
        .setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    if (Build.VERSION.SDK_INT >= 30) {
      window.setDecorFitsSystemWindows(false);
      view.setOnApplyWindowInsetsListener(
          (v, insets) -> {
            Insets bars =
                insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
          });
      view.requestApplyInsets();
    }
  }

  private void renderPage() {
    permissionSwitches.clear();
    signature = "";
    noticeSignature = "";
    root = ui.column();
    root.setBackgroundColor(Ui.BG);
    noticeBox = ui.column();
    root.addView(noticeBox);
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setClipToPadding(false);
    layout = ui.column();
    layout.setPadding(ui.dp(22), ui.dp(22), ui.dp(22), ui.dp(24));
    scroll.addView(layout);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    if (page.equals("settings")) settingsPage();
    else devicesPage();
    navigation = ui.row();
    navigation.setPadding(ui.dp(20), ui.dp(10), ui.dp(20), ui.dp(10));
    // A translucent surface over the pale page provides a quiet glass-like footer.
    // Keep text and icons sharp, including on devices without window blur support.
    navigation.setBackground(ui.shape(0xf5ffffff, 26));
    navigation.setElevation(ui.dp(4));
    for (String tab : new String[] {"devices", "settings"}) {
      boolean on = tab.equals(page);
      LinearLayout nav = ui.column();
      nav.setGravity(Gravity.CENTER);
      nav.setPadding(0, ui.dp(6), 0, ui.dp(6));
      nav.setBackground(ui.touch(on ? Ui.LILAC : Color.TRANSPARENT, 18));
      nav.addView(
          ui.icon(
              on && tab.equals("devices")
                  ? "monitor"
                  : tab.equals("settings") ? "settings" : "monitor",
              on ? Ui.PURPLE : Ui.MUTED),
          new LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)));
      ui.gap(nav, 5);
      TextView navLabel =
          ui.text(tab.equals("devices") ? "设备" : "设置", 12, on ? Ui.PURPLE : Ui.MUTED);
      navLabel.setGravity(Gravity.CENTER);
      nav.addView(navLabel);
      nav.setContentDescription(tab.equals("devices") ? "设备页面" : "设置页面");
      nav.setOnClickListener(
          v -> {
            if (!tab.equals(page)) {
              page = tab;
              renderPage();
            }
          });
      navigation.addView(nav, new LinearLayout.LayoutParams(0, -2, 1));
    }
    root.addView(navigation);
    setContentView(root);
    applyInsets(root, getWindow());
    update();
  }

  private void heading(String title, String subtitle) {
    layout.addView(ui.title(title, 30));
    ui.gap(layout, 8);
    layout.addView(ui.text(subtitle, 14, Ui.MUTED));
    ui.gap(layout, 24);
  }

  private void devicesPage() {
    LinearLayout brand = ui.row();
    brand.addView(ui.icon("link", Ui.PURPLE), new LinearLayout.LayoutParams(ui.dp(28), ui.dp(28)));
    TextView wordmark = ui.title("  LanLink", 18);
    brand.addView(wordmark);
    layout.addView(brand);
    ui.gap(layout, 20);
    LinearLayout title = ui.row();
    title.addView(ui.title("设备", 30), new LinearLayout.LayoutParams(0, -2, 1));
    title.addView(
        ui.iconButton("plus", "添加设备", () -> showPairingDialog(null, null)),
        new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
    layout.addView(title);
    layout.addView(ui.text("让设备，自在协作", 14, Ui.MUTED));
    ui.gap(layout, 24);
    HorizontalScrollView strip = new HorizontalScrollView(this);
    strip.setHorizontalScrollBarEnabled(false);
    devices = ui.row();
    strip.addView(devices);
    layout.addView(strip);
    ui.gap(layout, 16);
    detail = ui.column();
    layout.addView(detail);
    ui.gap(layout, 16);
    LinearLayout local = ui.card();
    local.setBackground(ui.touch(Ui.MINT, 22));
    local.addView(
        ui.item(
            "phone",
            "本机协作",
            "权限与连接状态",
            null,
            () -> {
              page = "settings";
              renderPage();
            }));
    layout.addView(local);
    ui.gap(layout, 16);
    status = ui.text("正在启动…", 12, Ui.MUTED);
    layout.addView(status);
    mode = ui.text("", 12, Ui.MUTED);
    ui.gap(layout, 6);
    layout.addView(mode);
  }

  private void section(String label) {
    ui.gap(layout, 24);
    layout.addView(ui.title(label, 14));
    ui.gap(layout, 12);
  }

  private void settingsPage() {
    heading("设置", "按你的方式连接");
    LinearLayout identity = ui.card();
    identity.addView(ui.item("phone", Build.MODEL, "本机 · Android", null, null));
    layout.addView(identity);
    section("协作权限");
    LinearLayout permissions = ui.card();
    addPermission(permissions, "input", "keyboard", "远程输入", "允许其他设备操作本机");
    ui.line(permissions);
    addPermission(permissions, "screen", "eye", "屏幕查看", "每次共享需单独授权");
    ui.line(permissions);
    addPermission(permissions, "clipboard", "clipboard", "剪贴板与文件", "在设备间接力内容");
    layout.addView(permissions);
    section("输入与连接");
    LinearLayout input = ui.card();
    input.addView(
        ui.item("keyboard", "ADB / Root 输入", "一次设置，连接可用时自动恢复", null, this::showInputSetup));
    ui.line(input);
    input.addView(ui.item("settings", "高级设置", "手动连接、ADB / Root", null, this::showAdvanced));
    if (!getSystemService(NotificationManager.class).areNotificationsEnabled()) {
      ui.line(input);
      input.addView(
          ui.item(
              "settings",
              "开启协作提醒",
              "接收后台控制请求的权限提示",
              null,
              () ->
                  startActivity(
                      new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                          .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()))));
    }
    layout.addView(input);
    ui.gap(layout, 20);
    Button stop =
        ui.button(
            "停止协作",
            false,
            () ->
                new AlertDialog.Builder(this)
                    .setTitle("停止协作？")
                    .setMessage("将断开设备并停止本机共享。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton(
                        "停止",
                        (d, w) -> {
                          stopService(new Intent(this, LinkService.class));
                          finish();
                        })
                    .show());
    stop.setTextColor(Ui.RED);
    stop.setBackground(ui.touch(Color.WHITE, 18));
    layout.addView(stop);
    ui.gap(layout, 16);
    status = ui.text("", 12, Ui.MUTED);
    mode = ui.text("", 12, Ui.MUTED);
    layout.addView(status);
    ui.gap(layout, 6);
    layout.addView(mode);
  }

  private void addPermission(
      LinearLayout card, String key, String icon, String title, String description) {
    LinearLayout row = ui.item(icon, title, description, null, null);
    if (key.equals("screen")) {
      row.setBackground(ui.touch(Color.TRANSPARENT, 14));
      row.setOnClickListener(v -> requestScreen());
    }
    Switch toggle = new Switch(this);
    toggle.setContentDescription(title);
    toggle.setButtonTintList(null);
    toggle.setThumbTintList(
        new android.content.res.ColorStateList(
            new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
            new int[] {Ui.PURPLE, 0xffaab0be}));
    toggle.setChecked(
        getSharedPreferences("identity", MODE_PRIVATE).getBoolean("allow_" + key, false));
    toggle.setOnCheckedChangeListener(
        (v, on) -> {
          LinkService s = LinkService.instance;
          if (s == null) {
            toast("服务正在启动，请稍后重试");
            return;
          }
          s.setAllowed(key, on);
          if (on && key.equals("screen")) requestScreen();
          if (on && key.equals("input") && !s.capabilities().optBoolean("input")) showInputSetup();
        });
    row.addView(toggle);
    card.addView(row);
    permissionSwitches.put(key, toggle);
  }

  private String platformIcon(String platform) {
    return "android".equals(platform)
        ? "phone"
        : "darwin".equals(platform) || "macos".equals(platform) ? "laptop" : "monitor";
  }

  private String platformName(String platform) {
    return "android".equals(platform)
        ? "Android"
        : "darwin".equals(platform) || "macos".equals(platform)
            ? "macOS"
            : "win32".equals(platform) || "windows".equals(platform) ? "Windows" : platform;
  }

  private void bubble(String id, String name, String icon, String state) {
    LinearLayout b = ui.column();
    b.setGravity(Gravity.CENTER);
    b.setPadding(ui.dp(14), ui.dp(16), ui.dp(14), ui.dp(16));
    b.setBackground(ui.touch(selected.equals(id) ? Ui.LILAC : Color.WHITE, 22));
    b.addView(
        ui.icon(icon, selected.equals(id) ? Ui.PURPLE : Ui.INK),
        new LinearLayout.LayoutParams(ui.dp(30), ui.dp(30)));
    ui.gap(b, 12);
    TextView label = ui.title(name, 14);
    label.setGravity(Gravity.CENTER);
    label.setMaxLines(1);
    label.setEllipsize(android.text.TextUtils.TruncateAt.END);
    b.addView(label);
    ui.gap(b, 6);
    TextView stateLabel = ui.text(state, 12, state.equals("● 在线") ? Ui.GREEN : Ui.MUTED);
    stateLabel.setGravity(Gravity.CENTER);
    b.addView(stateLabel);
    b.setContentDescription(name + "，" + state);
    b.setOnClickListener(
        v -> {
          selected = id;
          signature = "";
          update();
        });
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ui.dp(118), -2);
    p.setMargins(0, 0, ui.dp(10), 0);
    devices.addView(b, p);
  }

  private void updateDevices(LinkService service) {
    if (!page.equals("devices")) return;
    ArrayList<Wire> peers = new ArrayList<>();
    if (service != null) peers.addAll(service.peers.values());
    peers.sort(Comparator.comparing(p -> p.info.optString("name", "")));
    JSONObject known = service == null ? new JSONObject() : service.knownDevices();
    if (!selected.equals("local")
        && !known.has(selected)
        && (service == null || !service.peers.containsKey(selected))) selected = "local";
    StringBuilder sig = new StringBuilder(selected).append(known.toString());
    for (Wire p : peers) sig.append(p.id).append(p.info.toString());
    if (sig.toString().equals(signature)) return;
    signature = sig.toString();
    devices.removeAllViews();
    detail.removeAllViews();
    bubble("local", "本机", "phone", "此设备");
    for (Wire p : peers)
      bubble(
          p.id,
          p.info.optString("name", "已配对设备"),
          platformIcon(p.info.optString("platform")),
          "● 在线");
    ArrayList<String> offline = new ArrayList<>();
    Iterator<String> ids = known.keys();
    while (ids.hasNext()) {
      String id = ids.next();
      if (service == null || !service.peers.containsKey(id)) offline.add(id);
    }
    Collections.sort(offline);
    for (String id : offline) {
      JSONObject device = known.optJSONObject(id);
      if (device != null)
        bubble(
            id,
            device.optString("name", "已配对设备"),
            platformIcon(device.optString("platform")),
            "● 离线");
    }
    View add = ui.iconButton("plus", "添加设备", () -> showPairingDialog(null, null));
    devices.addView(add, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
    LinearLayout card = ui.card();
    detail.addView(card);
    Wire peer = service == null ? null : service.peers.get(selected);
    if (peer == null && !selected.equals("local")) {
      JSONObject device = known.optJSONObject(selected);
      card.addView(ui.title(device == null ? "已配对设备" : device.optString("name"), 22));
      ui.gap(card, 12);
      card.addView(ui.text("设备离线", 14, Ui.MUTED));
      final String removeId = selected;
      card.addView(ui.button("移出设备", false, () -> removeDevice(removeId)));
      ui.gap(card, 28);
      View icon =
          ui.icon(
              device == null ? "monitor" : platformIcon(device.optString("platform")), Ui.MUTED);
      card.addView(icon, new LinearLayout.LayoutParams(-1, ui.dp(96)));
      ui.gap(card, 28);
      card.addView(ui.text("让两台设备连接同一局域网，并打开 LanLink。设备上线后会自动连接。", 14, Ui.MUTED));
      return;
    }
    card.addView(ui.title(peer == null ? "我的手机" : peer.info.optString("name", "已配对设备"), 22));
    ui.gap(card, 8);
    card.addView(
        ui.text(
            peer == null
                ? Build.MODEL + " · 本机"
                : platformName(peer.info.optString("platform")) + " · 已连接",
            13,
            Ui.MUTED));
    ui.gap(card, 8);
    card.addView(
        ui.text(
            peer == null
                ? (peers.isEmpty() ? "等待其他设备加入" : "与 " + peers.size() + " 台设备连接")
                : "● 同一局域网",
            12,
            peer == null ? Ui.MUTED : Ui.GREEN));
    FrameLayout picture = new FrameLayout(this);
    View glyph =
        ui.icon(peer == null ? "phone" : platformIcon(peer.info.optString("platform")), Ui.PURPLE);
    picture.addView(glyph, new FrameLayout.LayoutParams(ui.dp(92), ui.dp(92), Gravity.CENTER));
    card.addView(picture, new LinearLayout.LayoutParams(-1, ui.dp(134)));
    card.addView(
        ui.button(
            peer == null ? "添加设备" : "远程控制",
            true,
            () -> {
              if (peer == null) showPairingDialog(null, null);
              else openViewer(peer);
            }));
    ui.line(card);
    LinearLayout buttons = ui.row();
    Button paste =
        ui.button(
            peer == null ? "共享本机内容" : "粘贴内容",
            false,
            () -> {
              if (peer == null) showClipboard();
              else
                run(
                    () -> {
                      LinkService s = link();
                      s.control(peer.id);
                      try {
                        s.pasteRemote();
                        main.post(() -> toast("已发送粘贴请求"));
                      } finally {
                        s.releaseControl();
                      }
                    });
            });
    Button more =
        ui.button(
            "更多",
            false,
            () -> {
              if (peer == null) {
                page = "settings";
                renderPage();
              } else showDeviceActions(peer);
            });
    buttons.addView(paste, new LinearLayout.LayoutParams(0, -2, 1));
    Space space = new Space(this);
    buttons.addView(space, new LinearLayout.LayoutParams(ui.dp(10), 1));
    buttons.addView(more, new LinearLayout.LayoutParams(0, -2, 1));
    card.addView(buttons);
  }

  private LinearLayout sheet(String title) {
    Dialog d = new Dialog(this);
    sheets.add(d);
    d.setOnDismissListener(ignored -> sheets.remove(d));
    LinearLayout box = ui.column();
    box.setTag(d);
    box.setPadding(ui.dp(22), ui.dp(20), ui.dp(22), ui.dp(24));
    box.setBackground(ui.shape(Ui.BG, 28));
    LinearLayout top = ui.row();
    top.addView(ui.title(title, 22), new LinearLayout.LayoutParams(0, -2, 1));
    top.addView(
        ui.iconButton("close", "关闭", d::dismiss),
        new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
    box.addView(top);
    ui.gap(box, 18);
    ScrollView scroll = new ScrollView(this);
    scroll.addView(box);
    d.setContentView(scroll);
    d.show();
    Window w = d.getWindow();
    if (w != null) {
      w.setBackgroundDrawableResource(android.R.color.transparent);
      w.setLayout(-1, -2);
      w.setGravity(Gravity.BOTTOM);
      w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
      if (Build.VERSION.SDK_INT >= 31
          && getSystemService(WindowManager.class).isCrossWindowBlurEnabled()) {
        w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
        WindowManager.LayoutParams attrs = w.getAttributes();
        attrs.setBlurBehindRadius(ui.dp(18));
        w.setAttributes(attrs);
      }
    }
    return box;
  }

  private void showDeviceActions(Wire peer) {
    LinearLayout box = sheet(peer.info.optString("name", "设备操作"));
    box.addView(
        ui.item(
            "settings",
            "重置此设备的本机授权",
            "下次控制本机时重新确认",
            null,
            () -> {
              if (LinkService.instance != null) LinkService.instance.revokeConsent(peer.id);
              toast("授权已重置");
              ((Dialog) box.getTag()).dismiss();
            }));
    box.addView(
        ui.item(
            "close",
            "移出设备",
            "需要重新配对才能加入",
            null,
            () -> {
              ((Dialog) box.getTag()).dismiss();
              removeDevice(peer.id);
            }));
    box.addView(ui.item("clipboard", "共享本机内容", "选择要发送的文字或剪贴板内容", null, this::showClipboard));
    if (!peer.info.optString("platform").equals("android")) {
      box.addView(
          ui.item(
              "power",
              "请求关机",
              "设备将在 30 秒后关机",
              null,
              () ->
                  new AlertDialog.Builder(this)
                      .setTitle("请求关机？")
                      .setMessage(peer.info.optString("name") + " 将在 30 秒后关机。")
                      .setNegativeButton("取消", null)
                      .setPositiveButton(
                          "发送请求",
                          (d, w) ->
                              run(
                                  () -> {
                                    peer.request("shutdown", obj());
                                    main.post(() -> toast("已发送关机请求"));
                                  }))
                      .show()));
      box.addView(
          ui.item(
              "back",
              "取消关机",
              null,
              null,
              () ->
                  run(
                      () -> {
                        peer.request("cancelShutdown", obj());
                        main.post(() -> toast("已发送取消请求"));
                      })));
    }
  }

  private void removeDevice(String id) {
    new AlertDialog.Builder(this)
        .setTitle("移出设备？")
        .setMessage("该设备需要重新配对才能加入。其他离线成员会保留身份，上线后自动更新。")
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "移出",
            (d, w) ->
                run(
                    () -> {
                      link().group.remove(id);
                      main.post(
                          () -> {
                            selected = "local";
                            update();
                            toast("设备已移出组");
                          });
                    }))
        .show();
  }

  private void showClipboard() {
    LinearLayout box = sheet("本机内容");
    box.addView(ui.text("需要在设置中开启「剪贴板与文件」。内容将共享给同组设备。", 13, Ui.MUTED));
    ui.gap(box, 16);
    EditText shared = ui.edit("输入要共享的文本");
    shared.setSingleLine(false);
    shared.setMinLines(3);
    box.addView(shared);
    ui.gap(box, 12);
    box.addView(
        ui.button(
            "共享文本",
            true,
            () -> {
              String value = shared.getText().toString();
              run(
                  () -> {
                    link().publishText(value);
                    main.post(() -> toast("文本已共享"));
                  });
            }));
    ui.gap(box, 10);
    box.addView(
        ui.button(
            "共享当前剪贴板",
            false,
            () ->
                run(
                    () -> {
                      link().publishClipboard();
                      main.post(() -> toast("剪贴板已共享"));
                    })));
    ui.gap(box, 10);
    box.addView(
        ui.button(
            "接收共享内容",
            false,
            () ->
                run(
                    () -> {
                      link().receiveCurrent();
                      main.post(() -> toast("已接收到剪贴板"));
                    })));
  }

  private void openAdbSettings() {
    try {
      startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
    } catch (ActivityNotFoundException e) {
      startActivity(new Intent(Settings.ACTION_SETTINGS));
    }
  }

  private void showInputSetup() {
    LinearLayout box = sheet("ADB / Root 输入");
    box.addView(
        ui.text("在电脑端选择本机 → ADB 键鼠授权。首次允许 USB 调试时勾选始终允许此电脑；绑定会保存，连接可用时自动恢复。", 14, Ui.MUTED));
    ui.gap(box, 14);
    box.addView(
        ui.text("应用重启后助手会自动重连。非 Root 手机重启后，需已信任的 USB 或无线调试连接可用；不会使用无障碍或专用输入法接管输入。", 13, Ui.MUTED));
    ui.gap(box, 16);
    box.addView(ui.button("打开开发者选项", true, this::openAdbSettings));
    ui.gap(box, 10);
    box.addView(ui.button("通过 Root 启动助手", false, () -> run(() -> link().shell.root())));
  }

  private void requestScreen() {
    startActivityForResult(
        getSystemService(MediaProjectionManager.class).createScreenCaptureIntent(), 10);
  }

  private void showAdvanced() {
    LinearLayout box = sheet("高级设置");
    box.addView(ui.text("同组设备通常会自动连接；自动发现不可用时可输入电脑的局域网 IP。", 13, Ui.MUTED));
    ui.gap(box, 16);
    EditText address = ui.edit("例如 192.168.1.10");
    address.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
    box.addView(address);
    ui.gap(box, 10);
    box.addView(
        ui.button(
            "连接设备",
            true,
            () -> {
              String value = address.getText().toString().trim();
              if (value.isEmpty()) {
                address.setError("请输入 IP 地址");
                return;
              }
              run(() -> link().connect(value, 45831));
            }));
    ui.gap(box, 24);
    box.addView(ui.title("ADB / Root 输入助手", 16));
    ui.gap(box, 8);
    box.addView(ui.text("支持系统键鼠注入。电脑端完成首次 ADB 授权后保存绑定，助手可自动重连；手机重启后需已信任的调试连接可用。", 13, Ui.MUTED));
    ui.gap(box, 8);
    box.addView(ui.text("没有 Root / ADB 时仍可查看已授权的屏幕，无法操作手机。", 13, Ui.MUTED));
    ui.gap(box, 14);
    box.addView(
        ui.button(
            "显示 ADB 启动命令",
            false,
            () ->
                run(
                    () -> {
                      String command = "adb shell \"" + link().shell.command() + "\"";
                      main.post(
                          () ->
                              new AlertDialog.Builder(this)
                                  .setTitle("在已授权 ADB 的电脑执行")
                                  .setMessage(command + "\n\n保持命令运行。服务重启后需使用新命令。")
                                  .setPositiveButton(
                                      "复制命令",
                                      (d, w) ->
                                          ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                                              .setPrimaryClip(
                                                  ClipData.newPlainText("ADB command", command)))
                                  .setNegativeButton("关闭", null)
                                  .show());
                    })));
    ui.gap(box, 10);
    box.addView(ui.button("通过 Root 启动助手", false, () -> run(() -> link().shell.root())));
  }

  private void refreshPermissionNotice(LinkService s) {
    PermissionNotice.Request request =
        s == null ? null : s.permissionNotice.peek(System.currentTimeMillis());
    if (request == null || (s != null && s.consent.peek() != null)) {
      closePermissionDialog();
    } else if (resumed && alive && !isFinishing()) {
      showPermissionHelp();
    }
    String sig =
        request == null ? "none" : request.peer + request.needs.toString() + request.created;
    String controlled = s == null ? null : s.controlledBy();
    sig += "|" + controlled;
    if (sig.equals(noticeSignature)) return;
    noticeSignature = sig;
    noticeBox.removeAllViews();
    if (controlled != null) {
      LinearLayout activeBanner = ui.card();
      activeBanner.addView(ui.title("正在被 " + controlled + " 控制 / 共享屏幕", 15));
      activeBanner.addView(
          ui.button(
              "结束控制",
              true,
              () -> {
                s.stopInbound();
                update();
              }));
      noticeBox.addView(activeBanner);
    }
    if (request == null) {
      if (s != null) getSystemService(NotificationManager.class).cancel(2);
      return;
    }
  }

  private void closePermissionDialog() {
    Dialog previous = permissionDialog;
    permissionDialog = null;
    permissionDialogKey = "";
    if (previous != null) previous.dismiss();
  }

  private void showPermissionHelp() {
    if (!alive || !resumed || isFinishing()) return;
    LinkService s = LinkService.instance;
    PermissionNotice.Request request =
        s == null ? null : s.permissionNotice.peek(System.currentTimeMillis());
    if (request == null) {
      toast("请求已结束，请让对方重新发起");
      return;
    }
    if (s.consent.peek() != null) {
      closePermissionDialog();
      refreshConsent(s);
      return;
    }
    String key = request.peer + "/" + request.needs.toString();
    if (permissionDialog != null && key.equals(permissionDialogKey)) {
      refreshPermissionRows();
      return;
    }
    closePermissionDialog();
    LinearLayout box = sheet("开启协作权限");
    Dialog dialog = (Dialog) box.getTag();
    permissionDialog = dialog;
    permissionDialogKey = key;
    View scroll = (View) box.getParent();
    scroll.setBackground(ui.shape(Ui.BG, 28));
    scroll.setClipToOutline(true);
    dialog.setCanceledOnTouchOutside(false);
    Window window = dialog.getWindow();
    if (window != null) {
      window.setGravity(Gravity.CENTER);
      window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
      window.setDimAmount(.32f);
      int width = Math.min(ui.dp(440), getResources().getDisplayMetrics().widthPixels - ui.dp(40));
      window.setLayout(width, -2);
      box.post(
          () -> {
            if (dialog.isShowing())
              window.setLayout(
                  width,
                  Math.min(
                      box.getHeight(),
                      (int) (getResources().getDisplayMetrics().heightPixels * .8f)));
          });
    }
    dialog.setOnDismissListener(
        d -> {
          sheets.remove(dialog);
          if (permissionDialog != dialog) return;
          permissionDialog = null;
          permissionDialogKey = "";
          PermissionNotice.Request current = s.permissionNotice.peek(System.currentTimeMillis());
          if (alive
              && !isChangingConfigurations()
              && current != null
              && current.peer.equals(request.peer)) s.dismissPermissionNotice();
        });
    box.addView(ui.title(request.name + " 请求协作", 16));
    ui.gap(box, 8);
    box.addView(ui.text("开启所需权限后即可协作，已开启的项目会显示「已授权」。", 14, Ui.MUTED));
    ui.gap(box, 18);
    if (request.needs.contains("input")) {
      box.addView(
          ui.title(
              s.shell.ready() && s.shell.lastInputError == null
                  ? "Root / ADB 增强输入 · 已授权"
                  : "需要开启系统输入权限",
              15));
      if (s.shell.lastInputError != null)
        box.addView(ui.text(s.shell.lastInputError, 13, Ui.MUTED));
      box.addView(ui.text("在电脑端完成一次 ADB 键鼠授权并记住此电脑。应用重启后助手会重连；手机重启后需已信任的调试连接可用。", 13, Ui.MUTED));
      ui.gap(box, 12);
      permissionAction(
          box,
          "允许远程输入",
          () -> s.allowed("input"),
          () -> {
            s.setAllowed("input", true);
            update();
          });
      permissionAction(
          box,
          "打开开发者选项",
          () -> s.shell.ready() && s.shell.lastInputError == null,
          this::openAdbSettings);
    }
    if (request.needs.contains("screen")) {
      ui.gap(box, 16);
      box.addView(ui.text("屏幕共享需要本次系统授权，结束后需重新开启。", 13, Ui.MUTED));
      permissionAction(
          box, "授权本次屏幕共享", () -> s.allowed("screen") && s.screenReady(), this::requestScreen);
    }
    if (request.needs.contains("clipboard"))
      permissionAction(
          box,
          "允许剪贴板与文件共享",
          () -> s.allowed("clipboard"),
          () -> {
            s.setAllowed("clipboard", true);
            update();
          });
    ui.gap(box, 12);
    box.addView(ui.text("权限就绪后，等待中的远程窗口会自动重试。", 12, Ui.MUTED));
    ui.gap(box, 16);
    box.addView(
        ui.button(
            "完成设置",
            false,
            () -> {
              boolean ready =
                  (!request.needs.contains("input")
                          || s.allowed("input") && s.capabilities().optBoolean("input"))
                      && (!request.needs.contains("screen")
                          || s.allowed("screen") && s.screenReady())
                      && (!request.needs.contains("clipboard") || s.allowed("clipboard"));
              if (!ready) {
                toast("仍有权限未开启，请完成上方设置");
                return;
              }
              s.dismissPermissionNotice();
              ((Dialog) box.getTag()).dismiss();
              update();
            }));
    ui.gap(box, 10);
    box.addView(ui.button("暂不设置", false, dialog::dismiss));
    refreshPermissionRows();
  }

  private void permissionAction(
      LinearLayout box, String title, java.util.function.BooleanSupplier granted, Runnable action) {
    Button b = ui.button(title, true, action);
    b.setTag(box.getTag());
    permissionRows.put(b, granted);
    permissionTitles.put(b, title);
    ui.gap(box, 12);
    box.addView(b);
  }

  private void refreshPermissionRows() {
    for (Button b : new ArrayList<>(permissionRows.keySet())) {
      if (!((Dialog) b.getTag()).isShowing()) {
        permissionRows.remove(b);
        permissionTitles.remove(b);
        continue;
      }
      boolean granted = permissionRows.get(b).getAsBoolean();
      b.setText(permissionTitles.get(b) + (granted ? " · 已授权" : ""));
      b.setEnabled(!granted);
      b.setTextColor(granted ? Ui.GREEN : Color.WHITE);
      b.setBackground(ui.touch(granted ? Ui.MINT : Ui.PURPLE, 16));
    }
  }

  private void refreshConsent(LinkService service) {
    if (service == null || service.consent == null) return;
    DeviceConsent.Request request = service.consent.peek();
    if (consentDialog != null && (request == null || !request.requestId.equals(consentRequestId))) {
      consentDialog.dismiss();
      consentDialog = null;
    }
    if (request == null || consentDialog != null || !resumed) return;
    consentRequestId = request.requestId;
    LinearLayout box = sheet("首次控制确认");
    Dialog dialog = (Dialog) box.getTag();
    consentDialog = dialog;
    box.addView(ui.text(request.name + " 希望控制这台手机。选择允许的功能，同意后记住此设备。", 15, Ui.INK));
    Map<String, CheckBox> choices = new LinkedHashMap<>();
    String[] labels = {"键鼠操作", "查看屏幕", "剪贴板与文件"};
    for (int i = 0; i < DeviceConsent.KEYS.length; i++) {
      CheckBox check = new CheckBox(this);
      check.setText(labels[i]);
      check.setTextColor(Ui.INK);
      check.setChecked(true);
      check.setPadding(0, ui.dp(10), 0, ui.dp(10));
      choices.put(DeviceConsent.KEYS[i], check);
      box.addView(check);
    }
    boolean[] decided = {false};
    ui.gap(box, 12);
    box.addView(
        ui.button(
            "同意并记住",
            true,
            () -> {
              JSONObject permissions = new JSONObject();
              try {
                for (String key : choices.keySet())
                  permissions.put(key, choices.get(key).isChecked());
                service.decideConsent(request, true, permissions);
                decided[0] = true;
                dialog.dismiss();
                update();
                if (permissions.optBoolean("screen") && !service.screenReady()) requestScreen();
                else if (permissions.optBoolean("input")
                    && !service.capabilities().optBoolean("input")) {
                  service.permissionNotice.offer(
                      request.id, request.name, "input", System.currentTimeMillis());
                  showPermissionHelp();
                }
              } catch (Exception e) {
                toast(e.getMessage());
              }
            }));
    ui.gap(box, 10);
    box.addView(ui.button("暂不允许", false, dialog::dismiss));
    dialog.setOnDismissListener(
        d -> {
          sheets.remove(dialog);
          if (consentDialog == dialog) consentDialog = null;
          if (!decided[0] && !isChangingConfigurations()) {
            try {
              service.decideConsent(request, false, new JSONObject());
            } catch (Exception ignored) {
            }
          }
        });
  }

  private String signature = "";

  private void update() {
    if (ui == null || status == null) return;
    LinkService s = LinkService.instance;
    refreshConsent(s);
    refreshPermissionRows();
    status.setText(s == null ? "服务正在启动…" : s.status);
    mode.setText(
        s != null && s.shell != null && s.shell.ready()
            ? "输入助手已连接 · ADB / Root"
            : "输入助手待连接 · 可在电脑端恢复");
    if (s != null) {
      s.uiVisible = resumed && pairDialog == null && viewer == null;
      for (Map.Entry<String, Switch> e : permissionSwitches.entrySet()) {
        boolean on = s.allowed(e.getKey());
        if (e.getValue().isChecked()
            != on) { // Rebuild only when a grant changed elsewhere; never fire a permission action
          // during refresh.
          e.getValue().setOnCheckedChangeListener(null);
          e.getValue().setChecked(on);
          final String key = e.getKey();
          e.getValue()
              .setOnCheckedChangeListener(
                  (v, value) -> {
                    s.setAllowed(key, value);
                    if (value && key.equals("screen")) requestScreen();
                    if (value && key.equals("input") && !s.capabilities().optBoolean("input"))
                      showInputSetup();
                  });
        }
      }
    }
    updateDevices(s);
    refreshPermissionNotice(s);
  }

  protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    state.putString("page", page);
    state.putString("selected", selected);
  }

  protected void onResume() {
    super.onResume();
    resumed = true;
    if (LinkService.instance != null) LinkService.instance.uiVisible = true;
    update();
  }

  protected void onNewIntent(Intent i) {
    super.onNewIntent(i);
    handleIntent(i);
  }

  private void handleIntent(Intent i) {
    if (i == null) return;
    if ("com.lanlink.app.PERMISSIONS".equals(i.getAction()))
      main.postDelayed(this::showPermissionHelp, 300);
    if (Intent.ACTION_VIEW.equals(i.getAction())
        && i.getData() != null
        && "lanlink".equals(i.getData().getScheme())) {
      String value = i.getData().getQueryParameter("code");
      if (value != null && value.matches("[0-9]{8}"))
        showPairingDialog(value, i.getData().getQueryParameter("id"));
    }
    if (Intent.ACTION_SEND.equals(i.getAction())) {
      android.net.Uri uri = i.getParcelableExtra(Intent.EXTRA_STREAM);
      if (uri != null) main.postDelayed(() -> run(() -> link().publishUri(uri)), 1500);
      else {
        String value = i.getStringExtra(Intent.EXTRA_TEXT);
        if (value != null) main.postDelayed(() -> run(() -> link().publishText(value)), 1500);
      }
    }
  }

  private void openViewer(Wire p) {
    if (viewer != null) viewer.dismiss();
    try {
      link().beginViewing(p.id);
    } catch (Exception blocked) {
      toast(blocked.getMessage());
      return;
    }
    viewing = p.id;
    viewer = new Dialog(this, android.R.style.Theme_Material_Light_NoActionBar);
    final Dialog session = viewer;
    final long openedAt = System.currentTimeMillis();
    final java.util.concurrent.atomic.AtomicBoolean freshRequest =
        new java.util.concurrent.atomic.AtomicBoolean(true);
    LinearLayout outer = ui.column();
    outer.setBackgroundColor(Ui.BG);
    LinearLayout box = ui.column();
    box.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(12));
    outer.addView(box, new LinearLayout.LayoutParams(-1, -1));
    LinearLayout title = ui.row();
    title.addView(
        ui.iconButton("back", "关闭并释放控制", session::dismiss),
        new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
    TextView name = ui.title(p.info.optString("name"), 18);
    name.setMaxLines(1);
    name.setEllipsize(android.text.TextUtils.TruncateAt.END);
    name.setPadding(ui.dp(12), 0, 0, 0);
    title.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
    box.addView(title);
    ui.gap(box, 12);
    TextView frameStatus = ui.text("正在连接画面…", 12, Ui.MUTED);
    box.addView(frameStatus);
    java.util.concurrent.atomic.AtomicBoolean autoControl =
        new java.util.concurrent.atomic.AtomicBoolean(true);
    Button control = ui.button("请求中", false, () -> {});
    control.setTextSize(12);
    control.setOnClickListener(
        v ->
            run(
                () -> {
                  autoControl.set(true);
                  freshRequest.set(true);
                  link().control(p.id, freshRequest.getAndSet(false));
                  main.post(() -> control.setText("已控制"));
                }));
    title.addView(control, new LinearLayout.LayoutParams(ui.dp(100), ui.dp(44)));
    ui.gap(box, 12);
    image = new ImageView(this);
    image.setFocusableInTouchMode(true);
    boolean phone = "android".equals(p.info.optString("platform"));
    TextureView videoTexture = new TextureView(this);
    VideoViewport videoBox = new VideoViewport(this, videoTexture, image, !phone);
    videoBox.followCursor = getPreferences(0).getBoolean("follow_remote_cursor", true);
    videoBox.setBackground(ui.shape(0xff202637, 22));
    videoBox.setClipToOutline(true);
    box.addView(videoBox, new LinearLayout.LayoutParams(-1, 0, 1));
    final VideoPlayer player = new VideoPlayer(p, videoTexture, image, frameStatus);
    player.viewport = videoBox;
    if (!phone) {
      LinearLayout viewportTools = ui.row();
      viewportTools.addView(
          ui.text("拖动画面 · 双指缩放", 11, Ui.MUTED), new LinearLayout.LayoutParams(0, -2, 1));
      Button reset = ui.button("复位画面", false, videoBox::reset);
      reset.setTextSize(11);
      viewportTools.addView(reset, new LinearLayout.LayoutParams(ui.dp(100), ui.dp(40)));
      box.addView(viewportTools);
      android.widget.Switch follow = new android.widget.Switch(this);
      follow.setText("缩放画面跟随鼠标");
      follow.setTextSize(12);
      follow.setChecked(videoBox.followCursor);
      follow.setOnCheckedChangeListener(
          (button, checked) -> {
            videoBox.followCursor = checked;
            getPreferences(0).edit().putBoolean("follow_remote_cursor", checked).apply();
          });
      box.addView(follow);
    }
    if (phone)
      videoBox.setOnTouchListener(
          (v, e) -> {
            if (!"android".equals(p.info.optString("platform"))) return true;
            LinkService s = LinkService.instance;
            if (s == null || !p.id.equals(s.active)) return true;
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_MOVE && actions.getQueue().size() > 1) return true;
            if (action == MotionEvent.ACTION_DOWN) image.requestFocus();
            if (player.width < 1 || player.height < 1) return true;
            float scale =
                Math.min(
                    (float) videoBox.getWidth() / player.width,
                    (float) videoBox.getHeight() / player.height);
            float left = (videoBox.getWidth() - player.width * scale) / 2,
                top = (videoBox.getHeight() - player.height * scale) / 2;
            if (action == MotionEvent.ACTION_DOWN
                && (e.getX() < left
                    || e.getY() < top
                    || e.getX() > videoBox.getWidth() - left
                    || e.getY() > videoBox.getHeight() - top)) return true;
            float x = Math.max(0, Math.min(1, (e.getX() - left) / (player.width * scale))),
                y = Math.max(0, Math.min(1, (e.getY() - top) / (player.height * scale)));
            run(
                () -> {
                  s.remoteInput(obj("kind", "move", "x", x, "y", y));
                  if (action == MotionEvent.ACTION_DOWN
                      || action == MotionEvent.ACTION_UP
                      || action == MotionEvent.ACTION_CANCEL)
                    s.remoteInput(
                        obj(
                            "kind",
                            "button",
                            "button",
                            0,
                            "down",
                            action == MotionEvent.ACTION_DOWN));
                });
            return true;
          });
    ui.gap(box, 12);
    final TouchpadView touchpad =
        new TouchpadView(
            this,
            event -> {
              LinkService service = link();
              service.remoteInput(p.id, event);
            },
            error ->
                main.post(
                    () -> {
                      if (viewer == session) {
                        frameStatus.setText(displayError(error.getMessage()));
                        control.setText("重新请求");
                      }
                    }));
    if (!phone) box.addView(touchpad, new LinearLayout.LayoutParams(-1, 0, 0.9f));
    else {
      LinearLayout keys = ui.row();
      for (String[] key :
          new String[][] {{"back", "◁ 返回"}, {"home", "○ 主页"}, {"recents", "□ 最近任务"}})
        keys.addView(
            ui.button(
                key[1],
                false,
                () -> run(() -> link().remoteInput(obj("kind", "navigation", "action", key[0])))),
            new LinearLayout.LayoutParams(0, -2, 1));
      box.addView(keys);
    }
    final RemoteKeyboardView keyboard =
        new RemoteKeyboardView(
            this,
            new RemoteKeyboardView.Sender() {
              public void text(String text) {
                run(
                    () -> {
                      if (p.id.equals(link().active)) p.request("text", obj("text", text));
                    });
              }

              public void key(String code, boolean down) {
                run(
                    () -> {
                      if (p.id.equals(link().active))
                        link().remoteInput(obj("kind", "key", "code", code, "down", down));
                    });
              }
            });
    box.addView(keyboard, new LinearLayout.LayoutParams(1, 1));
    LinearLayout shortcuts = ui.column();
    shortcuts.setVisibility(View.GONE);
    for (String[] row :
        new String[][] {
          {"Escape", "Tab", "Enter", "Backspace"},
          {"ArrowLeft", "ArrowUp", "ArrowDown", "ArrowRight"},
          {"Ctrl+A", "Ctrl+C", "Ctrl+V", "右键"}
        }) {
      LinearLayout keys = ui.row();
      for (String code : row) {
        String label =
            code.replace("ArrowLeft", "←")
                .replace("ArrowRight", "→")
                .replace("ArrowUp", "↑")
                .replace("ArrowDown", "↓")
                .replace("Backspace", "⌫")
                .replace("Escape", "Esc")
                .replace("Enter", "回车");
        Button shortcut =
            ui.button(
                label,
                false,
                () ->
                    run(
                        () -> {
                          LinkService service = link();
                          if (code.equals("右键")) {
                            service.remoteInput(obj("kind", "button", "button", 2, "down", true));
                            service.remoteInput(obj("kind", "button", "button", 2, "down", false));
                          } else if (code.equals("Ctrl+V")) service.pasteRemote();
                          else {
                            boolean modifier = code.startsWith("Ctrl+");
                            String actual = modifier ? "Key" + code.substring(5) : code,
                                mod =
                                    p.info.optString("platform").equals("darwin")
                                        ? "MetaLeft"
                                        : "ControlLeft";
                            if (modifier)
                              service.remoteInput(obj("kind", "key", "code", mod, "down", true));
                            try {
                              service.remoteInput(obj("kind", "key", "code", actual, "down", true));
                              service.remoteInput(
                                  obj("kind", "key", "code", actual, "down", false));
                            } finally {
                              if (modifier)
                                service.remoteInput(obj("kind", "key", "code", mod, "down", false));
                            }
                          }
                        }));
        LinearLayout.LayoutParams spacing = new LinearLayout.LayoutParams(0, -2, 1);
        spacing.setMargins(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3));
        keys.addView(shortcut, spacing);
      }
      shortcuts.addView(keys);
    }
    box.addView(shortcuts);
    ui.gap(box, 8);
    LinearLayout footer = ui.row();
    final boolean[] keyboardShown = {false};
    android.view.inputmethod.InputMethodManager ime =
        (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
    footer.addView(
        ui.button(
            "键盘",
            false,
            () -> {
              shortcuts.setVisibility(View.GONE);
              keyboardShown[0] = !keyboardShown[0];
              if (keyboardShown[0]) {
                keyboard.requestFocus();
                ime.showSoftInput(
                    keyboard, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
              } else ime.hideSoftInputFromWindow(keyboard.getWindowToken(), 0);
            }),
        new LinearLayout.LayoutParams(0, -2, 1));
    footer.addView(
        ui.button(
            "功能键",
            false,
            () -> {
              ime.hideSoftInputFromWindow(keyboard.getWindowToken(), 0);
              keyboardShown[0] = false;
              shortcuts.setVisibility(
                  shortcuts.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            }),
        new LinearLayout.LayoutParams(0, -2, 1));
    footer.addView(
        ui.button("粘贴", false, () -> run(() -> link().pasteRemote())),
        new LinearLayout.LayoutParams(0, -2, 1));
    for (int i = 0; i < footer.getChildCount(); i++) {
      LinearLayout.LayoutParams spacing =
          (LinearLayout.LayoutParams) footer.getChildAt(i).getLayoutParams();
      spacing.setMargins(ui.dp(3), 0, ui.dp(3), 0);
    }
    box.addView(footer);
    viewer.setContentView(outer);
    viewer.setOnDismissListener(
        d -> {
          touchpad.close();
          player.close();
          autoControl.set(false);
          viewing = null;
          if (viewer == session) viewer = null;
          if (LinkService.instance != null) {
            LinkService.instance.releaseControl();
            LinkService.instance.endViewing(p.id);
          }
        });
    viewer.show();
    viewer.getWindow().setLayout(-1, -1);
    viewer.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    applyInsets(outer, viewer.getWindow());
    player.start();
    if (!phone)
      main.post(
          new Runnable() {
            JSONObject last;

            public void run() {
              if (viewer != session || !alive) return;
              LinkService service = LinkService.instance;
              if (service != null
                  && p.id.equals(service.active)
                  && p.id.equals(service.cursorPeer)) {
                JSONObject cursor = service.remoteCursor;
                if (cursor != null && cursor != last) {
                  last = cursor;
                  videoBox.cursor((float) cursor.optDouble("x"), (float) cursor.optDouble("y"));
                }
              }
              main.postDelayed(this, 32);
            }
          });
    frames.execute(
        () -> {
          while (p.id.equals(viewing) && viewer == session && alive) {
            try {
              LinkService targetService = link();
              if (p.id.equals(targetService.endedPeer) && targetService.endedAt >= openedAt) {
                player.close();
                autoControl.set(false);
                main.post(
                    () -> {
                      if (viewer == session) {
                        session.dismiss();
                        toast("对方已结束控制");
                      }
                    });
                return;
              }
              if (autoControl.get() && resumed && !p.id.equals(link().active)) {
                try {
                  link().control(p.id, freshRequest.getAndSet(false));
                  if (viewer != session || !resumed) {
                    link().releaseControl();
                    return;
                  }
                  main.post(() -> control.setText("已控制"));
                } catch (Exception denied) {
                  String reason = denied.getMessage() == null ? "控制连接失败" : denied.getMessage();
                  if (!reason.startsWith("CONSENT_PENDING:")
                      && !reason.startsWith("PERMISSION_REQUIRED:")) autoControl.set(false);
                  main.post(
                      () -> {
                        if (viewer == session) {
                          control.setText(autoControl.get() ? "等待授权" : "重新请求");
                          frameStatus.setText(displayError(reason));
                        }
                      });
                }
              }
              Thread.sleep(500);
            } catch (Exception e) {
              main.post(
                  () -> {
                    if (viewer == session)
                      frameStatus.setText(
                          e.getMessage() == null ? "画面暂不可用" : displayError(e.getMessage()));
                  });
              try {
                Thread.sleep(2000);
              } catch (InterruptedException stop) {
                return;
              }
            }
          }
        });
  }

  private void decodeQr(Bitmap bitmap) throws Exception {
    if (bitmap == null) throw new Exception("未取得二维码图片");
    int w = bitmap.getWidth(), h = bitmap.getHeight();
    int[] pixels = new int[w * h];
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
    com.google.zxing.BinaryBitmap input =
        new com.google.zxing.BinaryBitmap(
            new com.google.zxing.common.HybridBinarizer(
                new com.google.zxing.RGBLuminanceSource(w, h, pixels)));
    Map<com.google.zxing.DecodeHintType, Object> hints =
        new EnumMap<>(com.google.zxing.DecodeHintType.class);
    hints.put(com.google.zxing.DecodeHintType.TRY_HARDER, true);
    String value = new com.google.zxing.MultiFormatReader().decode(input, hints).getText();
    openPairingLink(value);
  }

  private void openPairingLink(String value) throws Exception {
    if (value == null || !value.startsWith("lanlink://pair?"))
      throw new Exception("不是 LanLink 配对二维码");
    android.net.Uri uri = android.net.Uri.parse(value);
    String pairing = uri.getQueryParameter("code"), id = uri.getQueryParameter("id");
    if (pairing == null
        || !pairing.matches("[0-9]{8}")
        || id == null
        || !id.matches("[a-f0-9]{32}")) throw new Exception("请在新版设备中重新显示二维码");
    main.post(() -> showPairingDialog(pairing, id));
  }

  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request == 31 || request == 32) {
      if (result != RESULT_OK || data == null) {
        showPairingDialog(null, null);
        return;
      }
      if (request == 32) {
        try {
          openPairingLink(data.getStringExtra("pairingLink"));
        } catch (Exception e) {
          showPairingDialog(null, null);
          toast(e.getMessage());
        }
        return;
      }
    }
    if (result != RESULT_OK || data == null) return;
    if (request == 10 && LinkService.instance != null)
      try {
        LinkService.instance.startProjection(result, data);
      } catch (Exception e) {
        toast(e.getMessage());
      }
    if (request == 30) run(() -> decodeQr(data.getParcelableExtra("data")));
    if (request == 31 && data.getData() != null)
      run(
          () -> {
            ImageDecoder.Source source =
                ImageDecoder.createSource(getContentResolver(), data.getData());
            Bitmap bitmap =
                ImageDecoder.decodeBitmap(
                    source,
                    (decoder, info, s) -> {
                      decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                      int max = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                      if (max > 1600) decoder.setTargetSampleSize((max + 1599) / 1600);
                    });
            try {
              decodeQr(bitmap);
            } catch (Exception error) {
              main.post(() -> showPairingDialog(null, null));
              throw error;
            } finally {
              bitmap.recycle();
            }
          });
  }

  protected void onPause() {
    super.onPause();
    resumed = false;
    if (LinkService.instance != null) LinkService.instance.uiVisible = false;
    if (pairDialog != null) pairDialog.dismiss();
    if (LinkService.instance != null) LinkService.instance.releaseControl();
    if (viewer != null) viewer.dismiss();
  }

  protected void onDestroy() {
    alive = false;
    closePermissionDialog();
    viewing = null;
    main.removeCallbacks(refresh);
    if (viewer != null) viewer.dismiss();
    if (pairDialog != null) pairDialog.dismiss();
    for (Dialog dialog : new ArrayList<>(sheets)) dialog.dismiss();
    actions.shutdownNow();
    frames.shutdownNow();
    super.onDestroy();
  }
}
