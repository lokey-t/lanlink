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
import org.json.JSONArray;

public class MainActivity extends Activity {
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ThreadPoolExecutor actions =
      new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
  private final ExecutorService frames = Executors.newSingleThreadExecutor();
  private Ui ui;
  private LinearLayout root, layout, devices, detail, noticeBox, navigation;
  private TextView status, mode;
  private GroupLayoutPanel groupLayoutPanel;
  private final Map<String, Switch> permissionSwitches = new HashMap<>();
  private final Set<Dialog> sheets = new HashSet<>();
  private String page = "devices", selected = "local", noticeSignature = "";
  private String centerSelection="",centerSignature="",savingStage;
  private LinearLayout centerContent;private ScrollView centerScroll;
  private boolean resumed;
  private Dialog viewer;
  private Dialog pairDialog;
  private Dialog shareDialog;
  private java.util.ArrayList<android.net.Uri> pendingShareUris;
  private Dialog consentDialog;
  private Dialog permissionDialog;
  private Dialog backgroundGuide;
  private TextView backgroundGuideStatus;
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
  private void guardFeature(View view, Wire peer, String... keys) {
    LinkService s=LinkService.instance;if(peer==null||s==null)return;
    JSONObject report=s.peerAccess.get(peer.id);
    if(PeerAccess.allowed(report,keys))return;
    view.setAlpha(.45f);
    view.setOnClickListener(v->new AlertDialog.Builder(this).setTitle(Language.t("需要设备授权"))
      .setMessage(Language.t("请在「")+peer.info.optString("name")+Language.t("」上授权此功能。"))
      .setNegativeButton(Language.t("取消"),null).setPositiveButton(Language.t("发起授权"),(d,w)->run(()->{
        peer.request("permission-request",obj("permissions",new org.json.JSONArray(Arrays.asList(keys))));
        main.post(()->toast(Language.t("已发起授权，请在被控设备确认")));
      })).show());
  }

  private LinkService link() throws Exception {
    LinkService s = LinkService.instance;
    if (s == null) throw new Exception(Language.t("服务尚未启动"));
    return s;
  }

  private void run(Job job) {
    actions.execute(
        () -> {
          try {
            job.run();
          } catch (Exception e) {
            main.post(() -> toast(e.getMessage() == null ? Language.t("操作失败") : e.getMessage()));
          }
        });
  }

  private void toast(String s) {
    Toast.makeText(this, displayError(s), Toast.LENGTH_LONG).show();
  }

  private String displayError(String s) {
    return s == null
        ? Language.t("操作失败")
        : s.replace("CONSENT_PENDING: ", "")
            .replace("CONSENT_DENIED: ", "")
            .replace("PERMISSION_REQUIRED: ", "")
            .replace("CONTROL_CONFLICT: ", "")
            .replace("MASTER_REQUIRED: ", "")
            .replace("CONTROL_ENDED: ", "")
            .replace("FALLBACK_CONFIRMATION: ", "");
  }

  private void showPairingDialog(String scannedCode, String scannedId) {
    if (pairJoining) return;
    if (pairDialog != null) pairDialog.dismiss();
    Set<String> connectedBefore = new HashSet<>();
    if (LinkService.instance != null) connectedBefore.addAll(LinkService.instance.peers.keySet());
    boolean[] awaitingConnection = {false};
    Dialog dialog = new Dialog(this, Ui.dialogTheme());
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
        ui.iconButton(
            "back",
            Language.t("返回设备"),
            () -> {
              if (!pairJoining) dialog.dismiss();
            }),
        new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
    ui.gap(box, 12);
    box.addView(ui.title(Language.t("添加设备"), 30));
    ui.gap(box, 8);
    box.addView(ui.text(Language.t("连接同一 Wi-Fi，轻松加入"), 14, Ui.MUTED));
    ui.gap(box, 18);
    LinearLayout joinCard = ui.card();
    box.addView(joinCard);
    TextView joinTitle = ui.title(Language.t("加入其他组"), 18);
    joinCard.addView(joinTitle);
    ui.gap(joinCard, 8);
    joinCard.addView(ui.text(Language.t("扫描另一台设备的邀请二维码"), 12, Ui.MUTED));
    View scanIcon = ui.icon("scan", Ui.PURPLE);
    LinearLayout.LayoutParams scanIconSize = new LinearLayout.LayoutParams(ui.dp(52), ui.dp(52));
    scanIconSize.gravity = Gravity.CENTER_HORIZONTAL;
    scanIconSize.setMargins(0, ui.dp(14), 0, ui.dp(12));
    joinCard.addView(scanIcon, scanIconSize);
    Button scan =
        ui.button(
            Language.t("点击扫码"), true, () -> startActivityForResult(new Intent(this, ScanActivity.class), 32));
    joinCard.addView(scan);
    ui.gap(joinCard, 10);
    LinearLayout entries = ui.row();
    joinCard.addView(entries);
    LinearLayout manual = ui.row();
    manual.setGravity(Gravity.CENTER);
    manual.setPadding(0, ui.dp(10), 0, ui.dp(10));
    manual.setBackground(ui.touch(Color.TRANSPARENT, 12));
    manual.setContentDescription(Language.t("手动输入"));
    manual.addView(
        ui.icon("keyboard", Ui.MUTED), new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)));
    TextView manualText = ui.text(Language.t(" 手动输入"), 12, Ui.MUTED);
    manual.addView(manualText);
    entries.addView(manual, new LinearLayout.LayoutParams(0, -2, 1));
    LinearLayout photo = ui.row();
    photo.setGravity(Gravity.CENTER);
    photo.setPadding(0, ui.dp(10), 0, ui.dp(10));
    photo.setBackground(ui.touch(Color.TRANSPARENT, 12));
    photo.setContentDescription(Language.t("从图片选择"));
    photo.addView(
        ui.icon("picture", Ui.MUTED), new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)));
    photo.addView(ui.text(Language.t(" 从图片选择"), 12, Ui.MUTED));
    photo.setOnClickListener(
        v -> {
          try {
            startActivityForResult(
                new Intent(Intent.ACTION_GET_CONTENT)
                    .setType("image/*")
                    .addCategory(Intent.CATEGORY_OPENABLE),
                31);
          } catch (ActivityNotFoundException e) {
            toast(Language.t("没有可用的图片选择器，请使用扫码或手动输入"));
          }
        });
    entries.addView(photo, new LinearLayout.LayoutParams(0, -2, 1));
    LinearLayout form = ui.card();
    form.setVisibility(scannedCode == null ? View.GONE : View.VISIBLE);
    joinCard.addView(form);
    form.addView(ui.title(Language.t("输入其他组的配对码"), 16));
    ui.gap(form, 8);
    form.addView(ui.text(Language.t("输入对方「添加设备」页面的八位数字"), 13, Ui.MUTED));
    ui.gap(form, 16);
    EditText entry = ui.edit(Language.t("八位数字配对码"));
    entry.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
    entry.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(8)});
    if (scannedCode != null) entry.setText(scannedCode);
    form.addView(entry);
    ui.gap(form, 12);
    Button submit = ui.button(Language.t("配对并加入"), true, () -> {});
    form.addView(submit);
    ui.gap(form, 8);
    TextView message = ui.text("", 12, Ui.MUTED);
    form.addView(message);
    ui.gap(box, 18);
    LinearLayout card = ui.card();
    box.addView(card);
    card.addView(ui.title(Language.t("其他设备加入本组"), 18));
    card.addView(ui.text(Language.t("让新设备扫码，或输入下方八位数字"), 12, Ui.MUTED));
    ImageView qr = new ImageView(this);
    qr.setContentDescription(Language.t("二维码尚未显示"));
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
    Button reveal = ui.button(Language.t("查看二维码"), false, () -> {});
    LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
    rp.gravity = Gravity.CENTER_HORIZONTAL;
    reveal.setTextSize(12);
    reveal.setMinimumHeight(ui.dp(40));
    reveal.setPadding(0, 0, 0, 0);
    qrColumn.addView(reveal, rp);
    LinearLayout head = ui.row();
    head.addView(ui.title(Language.t("本机配对码"), 14), new LinearLayout.LayoutParams(0, -2, 1));

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
    TextView countdown = ui.text(Language.t("正在生成配对码…"), 12, Ui.MUTED);
    countdown.setGravity(Gravity.END);
    codeColumn.addView(countdown);
    boolean[] showing = {false};
    String[] lastQr = {""};
    Runnable refreshCode =
        new Runnable() {
          public void run() {
            if (!dialog.isShowing()) return;
            if (!pairJoining && LinkService.instance != null) {
              for (String peer : LinkService.instance.peers.keySet())
                if (awaitingConnection[0] || !connectedBefore.contains(peer)) {
                  finishPairing();
                  return;
                }
            }
            try {
              Pairing pairing = link().pairing;
              if (pairing == null) throw new Exception(Language.t("服务正在启动…"));
              boolean formed = link().groupEstablished();
              joinTitle.setText(Language.t("加入其他组"));
              submit.setText(formed ? Language.t("解散当前组并加入其他组") : Language.t("配对并加入"));
              JSONObject info = pairing.show();
              String code = info.getString("code"), id = info.getString("id");
              digits.setText(code.substring(0, 4) + " " + code.substring(4));
              long left = info.getLong("remainingMs");
              progress.setProgress((int) left);
              countdown.setText(((left + 999) / 1000) + Language.t(" 秒后刷新"));
              if (showing[0] && !id.equals(lastQr[0])) {
                String url = "lanlink://pair?v=2&code=" + code + "&id=" + id;
                com.google.zxing.common.BitMatrix matrix =
                    new com.google.zxing.qrcode.QRCodeWriter()
                        .encode(url, com.google.zxing.BarcodeFormat.QR_CODE, 320, 320);
                Bitmap bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888);
                for (int y = 0; y < 320; y++)
                  for (int x = 0; x < 320; x++)
                    bitmap.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
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
          qr.setContentDescription(showing[0] ? Language.t("本机配对二维码") : Language.t("二维码尚未显示"));
          reveal.setText(showing[0] ? Language.t("隐藏二维码") : Language.t("查看二维码"));
          main.removeCallbacks(refreshCode);
          refreshCode.run();
        });
    manual.setOnClickListener(
        v -> {
          boolean open = form.getVisibility() != View.VISIBLE;
          form.setVisibility(open ? View.VISIBLE : View.GONE);
          manualText.setText(open ? Language.t(" 收起输入") : Language.t(" 手动输入"));
          if (open) form.post(() -> scroll.smoothScrollTo(0, joinCard.getTop() + form.getTop()));
        });
    Runnable beginJoin =
        () -> {
          if (pairJoining) return;
          String value = entry.getText().toString();
          if (!value.matches("[0-9]{8}")) {
            entry.setError(Language.t("请输入完整的八位数字"));
            return;
          }
          LinkService pairingService = LinkService.instance;
          if (pairingService == null || pairingService.group == null) {
            toast(Language.t("组服务尚未就绪，请重试"));
            return;
          }
          final boolean dissolve = pairingService.groupEstablished();
          final String expectedGroup = pairingService.group.data.optString("id");
          pairJoining = true;
          submit.setEnabled(false);
          scan.setEnabled(false);
          photo.setEnabled(false);
          manual.setEnabled(false);
          entry.setEnabled(false);
          Runnable unlock =
              () -> {
                pairJoining = false;
                submit.setEnabled(true);
                scan.setEnabled(true);
                photo.setEnabled(true);
                manual.setEnabled(true);
                entry.setEnabled(true);
                dialog.setCancelable(true);
              };
          Runnable connect =
              () -> {
                dialog.setCancelable(false);
                message.setText(Language.t("正在查找设备并配对…"));
                actions.execute(
                    () -> {
                      try {
                        if (pairingService.pairing == null) throw new Exception(Language.t("配对服务尚未就绪"));
                        JSONObject result =
                            pairingService.pairing.join(
                                value, value.equals(scannedCode) ? scannedId : null);
                        pairingService.joinGroup(result, dissolve, expectedGroup);
                        main.post(
                            () -> {
                              unlock.run();
                              if (!alive) return;
                              awaitingConnection[0] = true;
                              message.setText(Language.t("已配对，正在建立加密连接…"));
                              if (!pairingService.peers.isEmpty()) finishPairing();
                            });
                      } catch (Exception error) {
                        main.post(
                            () -> {
                              unlock.run();
                              if (alive) message.setText(error.getMessage());
                            });
                      }
                    });
              };
          if (dissolve)
            new AlertDialog.Builder(this)
                .setTitle(Language.t("解散当前组并加入其他组？"))
                .setMessage(Language.t("原组所有设备都会退出，并断开控制。离线设备收到解散通知后退出。先验证新组配对码，成功后才解散原组。"))
                .setNegativeButton(Language.t("取消"), (d, w) -> unlock.run())
                .setOnCancelListener(d -> unlock.run())
                .setPositiveButton(Language.t("加入"), (d, w) -> connect.run())
                .show();
          else connect.run();
        };
    submit.setOnClickListener(v -> beginJoin.run());
    dialog.setOnDismissListener(
        d -> {
          main.removeCallbacks(refreshCode);
          if (LinkService.instance != null && LinkService.instance.pairing != null)
            LinkService.instance.pairing.hide();
          if (pairDialog == dialog) pairDialog = null;
        });
    dialog.show();
    if (scannedCode != null) main.post(beginJoin);
    Window w = dialog.getWindow();
    if (w != null) {
      w.setLayout(-1, -1);
      w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
      applyInsets(outer, w);
    }
    refreshCode.run();
  }

  public void onCreate(Bundle state) {
    Ui.applyTheme(this);
    super.onCreate(state);
    ui = new Ui(this);
    if (state != null) {
      page = state.getString("page", "devices");
      selected = state.getString("selected", "local");
      savingStage=state.getString("savingStage");centerSelection=state.getString("centerSelection","");
    }
    startForegroundService(new Intent(this, LinkService.class));
    renderPage();
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED)
      requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 20);
    main.post(refresh);
    if(state==null)handleIntent(getIntent());
    else {pendingShareUris=state.getParcelableArrayList("pendingShareUris");if(pendingShareUris!=null&&!pendingShareUris.isEmpty())main.post(()->chooseShareDevices(pendingShareUris));}
  }

  private void applyInsets(View view, Window window) {
    window.setStatusBarColor(Ui.BG);
    window.setNavigationBarColor(Ui.BG);
    window
        .getDecorView()
        .setSystemUiVisibility(
            Ui.dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
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
    groupLayoutPanel = null;
    permissionSwitches.clear();
    signature = "";
    noticeSignature = "";
    root = ui.column();
    root.setBackgroundColor(Ui.BG);
    noticeBox = ui.column();

    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setClipToPadding(false);
    layout = ui.column();
    layout.setPadding(ui.dp(22), ui.dp(22), ui.dp(22), ui.dp(24));
    scroll.addView(layout);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, page.equals("center")?3:1));
    if (page.equals("layout")) groupLayoutPage();
    else if (page.equals("permissions")) devicePermissionsPage();
    else if (page.equals("settings")) settingsPage();
    else if(page.equals("center")||page.equals("clipboard")){page="center";controlCenter(scroll);}
    else devicesPage();
    navigation = ui.row();
    navigation.setPadding(ui.dp(20), ui.dp(10), ui.dp(20), ui.dp(10));
    // A translucent surface over the pale page provides a quiet glass-like footer.
    // Keep text and icons sharp, including on devices without window blur support.
    navigation.setBackground(ui.shape(Ui.CARD, 26));
    navigation.setElevation(ui.dp(4));
    for (String tab : new String[] {"devices", "center", "settings"}) {
      boolean on = tab.equals(page) || tab.equals("settings") && page.equals("permissions") || tab.equals("devices") && page.equals("layout");
      LinearLayout nav = ui.column();
      nav.setGravity(Gravity.CENTER);
      nav.setPadding(0, ui.dp(6), 0, ui.dp(6));
      nav.setBackground(ui.touch(on ? Ui.LILAC : Color.TRANSPARENT, 18));
      nav.addView(
          ui.icon(
              on && tab.equals("devices")
                  ? "monitor"
                  : tab.equals("settings") ? "settings" : tab.equals("center")?"center":"monitor",
              on ? Ui.PURPLE : Ui.MUTED),
          new LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)));
      ui.gap(nav, 5);
      TextView navLabel =
          ui.text(tab.equals("devices") ? Language.t("设备") : tab.equals("center")?Language.t("控制中心"):Language.t("设置"), 12, on ? Ui.PURPLE : Ui.MUTED);
      navLabel.setGravity(Gravity.CENTER);
      nav.addView(navLabel);
      nav.setContentDescription(tab.equals("devices") ? Language.t("设备页面") : tab.equals("center")?Language.t("控制中心页面"):Language.t("设置页面"));
      nav.setOnClickListener(
          v -> {
            if (!tab.equals(page)) {
              page = tab;
              if(tab.equals("center"))centerSelection="";
              renderPage();
            }
          });
      navigation.addView(nav, new LinearLayout.LayoutParams(0, -2, 1));
    }
    root.addView(noticeBox);
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
    title.addView(ui.title(Language.t("设备"), 30), new LinearLayout.LayoutParams(0, -2, 1));
    if(getSharedPreferences("identity",0).getBoolean("clipboard_shortcuts",false) && (!getSharedPreferences("identity",0).getBoolean("clipboard_upload",true) || !getSharedPreferences("identity",0).getBoolean("clipboard_download",true))){LinearLayout.LayoutParams syncSpace=new LinearLayout.LayoutParams(-2,ui.dp(48));syncSpace.setMargins(0,0,ui.dp(12),0);title.addView(ui.clipboardShortcuts(()->manualClipboard(true),()->manualClipboard(false)),syncSpace);}
    title.addView(
        ui.iconButton("plus", Language.t("添加设备"), () -> showPairingDialog(null, null)),
        new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
    layout.addView(title);
    layout.addView(ui.text(Language.t("让设备，自在协作"), 14, Ui.MUTED));
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
    LinearLayout arrangement = ui.card();
    arrangement.addView(ui.item("monitor", Language.t("组内布局与主控"), Language.t("查看全组设备，选择主控电脑并调整屏幕位置"), null, () -> { page = "layout"; renderPage(); }));
    layout.addView(arrangement);
    ui.gap(layout, 16);
    LinearLayout local = ui.card();
    local.setBackground(ui.touch(Ui.MINT, 22));
    local.addView(
        ui.item(
            "phone",
            Language.t("本机协作"),
            Language.t("权限与连接状态"),
            null,
            () -> {
              page = "settings";
              renderPage();
            }));
    layout.addView(local);
    ui.gap(layout, 16);
    status = ui.text(Language.t("正在启动…"), 12, Ui.MUTED);
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

  private void groupLayoutPage() {
    layout.addView(ui.button(Language.t("返回设备"), false, () -> { page = "devices"; renderPage(); }));
    ui.gap(layout, 16);
    heading(Language.t("组内布局与主控"), Language.t("同一组的设备共享这份布局与主控选择"));
    LinkService service = LinkService.instance;
    if (service == null || service.group == null) { layout.addView(ui.text(Language.t("服务正在启动…"), 14, Ui.MUTED)); return; }
    groupLayoutPanel = new GroupLayoutPanel(this, ui, service); layout.addView(groupLayoutPanel);
  }

  private void finishPairing() {
    if (!alive || isFinishing()) return;
    if (pairDialog != null) pairDialog.dismiss();
    page = "devices";
    signature = "";
    renderPage();
  }

  private boolean modeChanging;
  private boolean pairJoining;
  private String settingsSignature = "";
  private int rootUiState = -1;

  private String settingsState(LinkService service) {
    return service == null
        ? "starting"
        : service.name
            + ":"
            + service.rootEnabled()
            + ":"
            + service.rootActive()
            + ":"
            + service.adbAuthorized()
            + ":"
            + service.shell.ready()
            + ":"
            + ":"
            + (modeChanging || service.rootModeChanging());
  }

  private void settingsPage() {
    heading(Language.t("设置"), Language.t("设备与权限 · 本页更改均立即保存"));
    LinkService service = LinkService.instance;
    boolean rootMode = service != null && service.rootEnabled();
    settingsSignature = settingsState(service);
    rootUiState = service != null && service.rootActive() ? 2 : rootMode ? 1 : 0;
    LinearLayout identity = ui.card();
    identity.addView(
        ui.item(
            "phone", Language.t("设备名称"), service == null ? Build.MODEL : service.name, null, this::showRename));
    try {
      String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
      JSONObject build = new JSONObject();
      try (java.io.InputStream input = getAssets().open("build-info.json")) { build = new JSONObject(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)); } catch (Exception ignored) {}
      TextView buildLabel = ui.text(Language.t("版本 ") + version + Language.t(" · 构建 ") + build.optString("revision", Language.t("开发构建")) + (build.optString("sourceFingerprint").isEmpty() ? "" : Language.t("\n源码指纹 ") + build.optString("sourceFingerprint")) + (build.optString("builtAt").isEmpty() ? "" : Language.t("\n构建时间 ") + build.optString("builtAt")), 11, Ui.MUTED);
      buildLabel.setTextIsSelectable(true);ui.gap(identity, 12);identity.addView(buildLabel);
    } catch (Exception ignored) {}
    ui.line(identity);
    String appearance = Ui.themeMode(this);
    identity.addView(ui.item("settings", Language.t("外观"), Language.t("仅对本设备生效"), "dark".equals(appearance) ? Language.t("深色") : "light".equals(appearance) ? Language.t("浅色") : Language.t("跟随系统"), () -> {
      String[] modes = {"system", "dark", "light"};
      int selectedTheme = java.util.Arrays.asList(modes).indexOf(Ui.themeMode(this));
      new AlertDialog.Builder(this).setTitle(Language.t("外观")).setSingleChoiceItems(new String[]{Language.t("跟随系统"), Language.t("深色"), Language.t("浅色")}, selectedTheme, (dialog, which) -> {
        getSharedPreferences("identity", 0).edit().putString("theme", modes[which]).apply();
        dialog.dismiss(); recreate();
      }).setNegativeButton(Language.t("取消"), null).show();
    }));
    ui.line(identity);
    identity.addView(ui.item("settings", Language.t("语言"), Language.t("语言更改立即生效"), Language.label(this), this::showLanguagePicker));
    layout.addView(identity);
    ui.gap(layout, 16);

    LinearLayout permissions = ui.card();
    permissions.addView(ui.title(Language.t("权限使用模式"), 15));
    ui.gap(permissions, 14);
    LinearLayout segmented = ui.row();
    segmented.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4));
    segmented.setBackground(ui.shape(Ui.BG, 18));
    for (boolean option : new boolean[] {true, false}) {
      Button button =
          ui.button(
              option ? Language.t("Root 模式") : Language.t("非 Root 模式"),
              rootMode == option,
              () -> selectPermissionMode(option));
      button.setContentDescription(option ? Language.t("切换 Root 模式") : Language.t("切换非 Root 模式"));
      button.setEnabled(!modeChanging && service != null && !service.rootModeChanging());
      if (rootMode != option) {
        button.setBackground(ui.touch(Color.TRANSPARENT, 14));
        button.setTextColor(Ui.MUTED);
      }
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
      params.setMargins(ui.dp(2), 0, ui.dp(2), 0);
      segmented.addView(button, params);
    }
    permissions.addView(segmented);
    ui.gap(permissions, 12);
    if (modeChanging || service != null && service.rootModeChanging())
      permissions.addView(ui.text(Language.t("正在切换，请留意 Root 管理器的提示…"), 12, Ui.PURPLE));
    if (rootMode) {
      boolean ready = service.rootActive();
      permissions.addView(
          authorizationRow(
              "settings",
              Language.t("Root 授权"),
              ready ? Language.t("输入与 H.264 投屏自动运行") : Language.t("助手恢复中，保留已选择的模式"),
              ready ? Language.t("已授权") : Language.t("恢复中"),
              ready,
              this::showRootAuthorization));
    } else {
      boolean adb = service != null && service.adbAuthorized();
      permissions.addView(
          authorizationRow(
              "keyboard",
              Language.t("ADB 授权"),
              adb ? service.shell.ready() ? Language.t("键鼠与屏幕串流已就绪") : Language.t("授权已保存，等待助手恢复") : Language.t("通过电脑为本机开启键鼠与屏幕串流"),
              adb ? service.shell.ready() ? Language.t("已就绪") : Language.t("待恢复") : Language.t("未授权"),
              adb,
              this::showAdbAuthorization));

    }
    layout.addView(permissions);
    ui.gap(layout, 16);
    LinearLayout glowCard=ui.card(),glowRow=ui.row();glowRow.addView(ui.text(Language.t("键鼠切换边缘光效"),14,Ui.INK),new LinearLayout.LayoutParams(0,-2,1));
    Switch glowToggle=new Switch(this);glowToggle.setContentDescription(Language.t("切换键鼠边缘光效"));glowToggle.setChecked(getSharedPreferences("identity",0).getBoolean("edge_glow",false));
    glowToggle.setOnCheckedChangeListener((button,enabled)->{if(enabled && !Settings.canDrawOverlays(this)){glowToggle.setChecked(false);requestEdgeGlowPermission();return;}getSharedPreferences("identity",0).edit().putBoolean("edge_glow",enabled).apply();});
    glowRow.addView(glowToggle);glowCard.addView(glowRow);glowCard.addView(ui.text(Language.t("默认关闭 · 切换到手机时在对应边缘显示短暂光效"),12,Ui.MUTED));layout.addView(glowCard);ui.gap(layout,16);
    LinearLayout options = ui.card();
    options.addView(
        ui.item(
            "monitor",
            Language.t("组设备授权管理"),
            Language.t("单独决定每台设备可以做什么"),
            null,
            () -> {
              page = "permissions";
              renderPage();
            }));
    ui.line(options);
    options.addView(ui.item("link", Language.t("使用说明"), Language.t("配对、授权与常见问题"), null, this::showUsage));
    ui.line(options);
    options.addView(ui.item("settings", Language.t("后台运行与自启动"), Language.t("设置省电白名单，减少后台连接中断"), null, () -> showBackgroundGuide(null)));
    layout.addView(options);
    clipboardSettingsCard();
    settingsFooter();
  }

  private AlertDialog showLanguagePicker() {
    String[] languages = LanguageSelection.IDS;
    int selectedLanguage = java.util.Arrays.asList(languages).indexOf(Language.mode(this));
    return new AlertDialog.Builder(this).setTitle(Language.t("语言"))
        .setSingleChoiceItems(Language.labels(), selectedLanguage, (dialog, which) -> {
          Language.setMode(this, languages[which]);
          if (LinkService.instance != null) LinkService.instance.refreshLanguage();
          dialog.dismiss();
          recreate();
        }).setNegativeButton(Language.t("取消"), null).show();
  }

  private boolean awaitingEdgeGlowPermission;
  private AlertDialog edgeGlowPermissionDialog;
  private void requestEdgeGlowPermission(){
    if(edgeGlowPermissionDialog!=null && edgeGlowPermissionDialog.isShowing())return;
    edgeGlowPermissionDialog=new AlertDialog.Builder(this).setTitle(Language.t("开启边缘切换光效"))
      .setMessage(Language.t("需要允许 LanLink 显示在其他应用上层，才能在使用其他应用时显示短暂的屏幕边缘光效。此权限仅用于光效，不是键鼠控制的必要权限。"))
      .setNegativeButton(Language.t("取消"),null).setPositiveButton(Language.t("前往设置"),(d,w)->{try{awaitingEdgeGlowPermission=true;startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,android.net.Uri.parse("package:"+getPackageName())));}catch(Exception e){awaitingEdgeGlowPermission=false;toast(Language.t("无法打开设置，请在系统设置中允许显示在其他应用上层"));}}).show();
  }
  private void settingsFooter() {
    ui.gap(layout, 18);
    status = ui.text("", 12, Ui.MUTED);
    mode = ui.text("", 12, Ui.MUTED);
    layout.addView(status);
    ui.gap(layout, 6);
    layout.addView(mode);
  }

  private LinearLayout authorizationRow(
      String icon,
      String title,
      String description,
      String state,
      boolean granted,
      Runnable action) {
    LinearLayout row = ui.item(icon, title, description, null, null);
    TextView badge = ui.text(state, 11, granted ? Ui.GREEN : Ui.MUTED);
    badge.setPadding(ui.dp(9), ui.dp(6), ui.dp(9), ui.dp(6));
    badge.setBackground(ui.shape(granted ? Ui.MINT : Ui.BG, 10));
    // Longer translations should not squeeze the permission title into fragments.
    if (badge.getPaint().measureText(state) > ui.dp(92)) {
      LinearLayout labels = (LinearLayout) row.getChildAt(1);
      ui.gap(labels, 8);
      labels.addView(badge, new LinearLayout.LayoutParams(-2, -2));
    } else row.addView(badge);
    row.addView(ui.icon("chevron", Ui.MUTED), new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)));
    row.setContentDescription(title + "，" + state);
    row.setBackground(ui.touch(Color.TRANSPARENT, 14));
    row.setOnClickListener(v -> action.run());
    return row;
  }

  private void showRename() {
    LinearLayout box = sheet(Language.t("设备名称"));
    Dialog dialog = (Dialog) box.getTag();
    EditText name = ui.edit(Language.t("输入设备名称"));
    name.setContentDescription(Language.t("设备名称输入"));
    name.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(40)});
    name.setText(LinkService.instance == null ? Build.MODEL : LinkService.instance.name);
    box.addView(name);
    ui.gap(box, 16);
    box.addView(
        ui.button(
            Language.t("保存名称"),
            true,
            () ->
                run(
                    () -> {
                      link().rename(name.getText().toString());
                      main.post(
                          () -> {
                            dialog.dismiss();
                            renderPage();
                          });
                    })));
  }

  private void selectPermissionMode(boolean rootMode) {
    LinkService service = LinkService.instance;
    if (service == null
        || modeChanging
        || service.rootModeChanging()
        || service.rootEnabled() == rootMode) return;
    modeChanging = true;
    renderPage();
    run(
        () -> {
          try {
            service.setRootMode(rootMode);
          } finally {
            main.post(
                () -> {
                  modeChanging = false;
                  renderPage();
                });
          }
        });
  }

  private void showRootAuthorization() {
    if (modeChanging || LinkService.instance != null && LinkService.instance.rootModeChanging())
      return;
    LinearLayout box = sheet(Language.t("Root 授权"));
    Dialog dialog = (Dialog) box.getTag();
    LinkService service = LinkService.instance;
    boolean enabled = service != null && service.rootEnabled();
    box.addView(
        ui.text(
            enabled
                ? service.rootActive()
                    ? Language.t("已授权。是否解除 LanLink 的 Root 模式？")
                    : Language.t("Root 模式已开启，输入助手尚未就绪。可先解除模式后重新授权。")
                : Language.t("未授权。开启后，请在 Root 管理器中允许 LanLink。"),
            15,
            Ui.INK));
    ui.gap(box, 12);
    box.addView(
        ui.text(
            Language.t("Root 用于增强键鼠与 H.264 屏幕串流。解除会停止 Root 输入和屏幕采集；如需彻底撤销系统 Root 许可，请在 Root 管理器中操作。"),
            13,
            Ui.MUTED));
    ui.gap(box, 18);
    box.addView(
        ui.button(
            enabled ? Language.t("解除授权") : Language.t("开启 Root 授权"),
            true,
            () -> {
              dialog.dismiss();
              selectPermissionMode(!enabled);
            }));
    ui.gap(box, 10);
    box.addView(ui.button(Language.t("取消"), false, dialog::dismiss));
  }

  private void showAdbAuthorization() {
    LinearLayout box = sheet(Language.t("ADB 授权"));
    Dialog dialog = (Dialog) box.getTag();
    LinkService service = LinkService.instance;
    boolean granted = service != null && service.adbAuthorized();
    box.addView(ui.text(granted ? service.shell.ready() ? Language.t("ADB 助手已就绪，同组设备可直接使用，无需重复连接数据线。") : Language.t("ADB 授权已保存，但助手尚未运行。请连接已信任的电脑恢复助手，恢复后同组设备均可直接使用。") : Language.t("未授权。需要通过电脑完成一次配置。"), 15, Ui.INK));
    ui.gap(box, 12);
    box.addView(
        ui.text(
            granted
                ? Language.t("解除后停止本应用的输入助手，并阻止电脑自动恢复。不会替你撤销系统 USB 调试信任；如需撤销，请在开发者选项操作。")
                : Language.t("1. 手机与电脑加入同一组。\n")
                    + Language.t("2. 用数据线连接电脑，在手机开发者选项中开启 USB 调试并信任电脑。\n")
                    + Language.t("3. 在电脑「安卓授权」选择本机并点击授权。小米等手机还需开启「USB 调试（安全设置）」。"),
            13,
            Ui.MUTED));
    ui.gap(box, 18);
    box.addView(
        ui.button(
            granted ? Language.t("解除授权") : Language.t("允许电脑授权"),
            true,
            () ->
                run(
                    () -> {
                      if (granted) link().revokeAdbAuthorization();
                      else link().enableAdbAuthorization();
                      main.post(
                          () -> {
                            dialog.dismiss();
                            renderPage();
                          });
                    })));
    ui.gap(box, 10);
    box.addView(ui.button(Language.t("打开开发者选项"), false, this::openAdbSettings));
    ui.gap(box, 10);
    box.addView(ui.button(Language.t("取消"), false, dialog::dismiss));
  }

  private void devicePermissionsPage() {
    Button back =
        ui.button(
            Language.t("‹ 返回设置"),
            false,
            () -> {
              page = "settings";
              renderPage();
            });
    layout.addView(back);
    ui.gap(layout, 20);
    heading(Language.t("组设备授权管理"), Language.t("设置每台组设备可以在本机使用的功能"));
    LinkService service = LinkService.instance;
    if (service == null || service.group == null) {
      layout.addView(ui.text(Language.t("服务正在启动…"), 14, Ui.MUTED));
      settingsFooter();
      return;
    }
    layout.addView(ui.text(Language.t("同组设备默认允许全部功能，无需首次确认。可单独关闭某台设备的功能；离线设备的设置也会保存。系统 ADB / Root 权限仍需单独配置。"), 13, Ui.MUTED));
    ui.gap(layout, 16);
    JSONObject known = service.authorizationDevices();
    ArrayList<String> ids = new ArrayList<>();
    Iterator<String> iterator = known.keys();
    while (iterator.hasNext()) ids.add(iterator.next());
    Collections.sort(ids);
    for (String id : ids) {
      if (service.group.excluded(id)) continue;
      JSONObject info = known.optJSONObject(id);
      if (info == null) continue;
      LinearLayout card = ui.card();
      card.addView(
          ui.item(
              platformIcon(info.optString("platform")),
              info.optString("name", Language.t("已配对设备")),
              service.peers.containsKey(id) ? Language.t("在线 · 可随时编辑授权") : Language.t("离线 · 可提前设置授权"),
              null,
              null));
      TextView grantSummary = ui.text(deviceGrantSummary(service, id), 13, Ui.MUTED);
      card.addView(grantSummary);
      String[] names = {Language.t("键鼠标操作"), Language.t("屏幕共享"), Language.t("剪贴板文件共享")};
      for (int i = 0; i < DeviceConsent.KEYS.length; i++) {
        final String key = DeviceConsent.KEYS[i];
        ui.line(card);
        LinearLayout row = ui.row();
        row.addView(ui.text(names[i], 14, Ui.INK), new LinearLayout.LayoutParams(0, -2, 1));
        Switch toggle = new Switch(this);
        toggle.setContentDescription(info.optString("name") + " " + names[i]);
        toggle.setChecked(service.consent.allowed(id, key));
        toggle.setThumbTintList(
            new android.content.res.ColorStateList(
                new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
                new int[] {Ui.PURPLE, Ui.THUMB}));
        toggle.setTrackTintList(
            new android.content.res.ColorStateList(
                new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
                new int[] {Ui.LILAC, Ui.LINE}));
        toggle.setOnCheckedChangeListener(
            (button, enabled) -> {
              toggle.setEnabled(false);
              run(
                  () -> {
                    try {
                      service.setDevicePermission(id, key, enabled);
                      main.post(() -> grantSummary.setText(deviceGrantSummary(service, id)));
                    } catch (Exception error) {
                      main.post(this::renderPage);
                      throw error;
                    } finally {
                      main.post(() -> toggle.setEnabled(true));
                    }
                  });
            });
        row.addView(toggle);
        card.addView(row);
      }
      layout.addView(card);
      ui.gap(layout, 16);
    }
    if (ids.isEmpty()) layout.addView(ui.text(Language.t("暂无组设备，请先在设备页添加。"), 14, Ui.MUTED));
    settingsFooter();
  }

  private String deviceGrantSummary(LinkService service, String id) {
    if (!service.consent.hasGrant(id)) return Language.t("组内默认 · 全部允许");
    int count = 0;
    for (String key : DeviceConsent.KEYS) if (service.consent.allowed(id, key)) count++;
    return count == 0 ? Language.t("已保存 · 暂不允许任何功能") : count == DeviceConsent.KEYS.length ? Language.t("已保存 · 全部授权") : Language.t("已保存 · 有限授权（") + count + "/" + DeviceConsent.KEYS.length + "）";
  }

  private void showUsage() {
    LinearLayout box = sheet(Language.t("使用说明"));
    box.addView(ui.title(Language.t("连接设备"), 15));
    ui.gap(box, 8);
    box.addView(ui.text(Language.t("让设备连接同一局域网，在设备页右上角添加设备，扫码或输入八位数字码加入组。同组设备默认允许协作，无需首次确认；可在组设备授权管理中关闭指定功能。"), 13, Ui.MUTED));
    ui.gap(box, 18);
    box.addView(ui.title(Language.t("选择权限模式"), 15));
    ui.gap(box, 8);
    box.addView(
        ui.text(
            Language.t("非 Root 模式由电脑完成 ADB 键鼠授权；查看手机屏幕时再按提示允许系统投屏。Root 模式需手机已 Root，并在 Root 管理器允许 LanLink，使用实时")
                + Language.t(" H.264 串流。"),
            13,
            Ui.MUTED));
    ui.gap(box, 18);
    box.addView(ui.title(Language.t("随时收回控制"), 15));
    ui.gap(box, 8);
    box.addView(
        ui.text(Language.t("在首页的被控提示中结束当前控制；在组设备授权管理中单独关闭某台设备的键鼠、屏幕或剪贴板权限。跨屏键鼠使用 Android 原生鼠标光标。"), 13, Ui.MUTED));
    ui.gap(box, 18);
    box.addView(ui.button(Language.t("连接与故障排查"), false, this::showAdvanced));
    ui.gap(box, 10);
    box.addView(ui.button(Language.t("关闭说明"), false, ((Dialog) box.getTag())::dismiss));
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
            new int[] {Ui.PURPLE, Ui.THUMB}));
        toggle.setTrackTintList(
            new android.content.res.ColorStateList(
                new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
                new int[] {Ui.LILAC, Ui.LINE}));
    toggle.setChecked(
        getSharedPreferences("identity", MODE_PRIVATE).getBoolean("allow_" + key, java.util.Arrays.asList(DeviceConsent.KEYS).contains(key)));
    toggle.setOnCheckedChangeListener(
        (v, on) -> {
          LinkService s = LinkService.instance;
          if (s == null) {
            toast(Language.t("服务正在启动，请稍后重试"));
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
    b.setBackground(ui.touch(selected.equals(id) ? Ui.LILAC : Ui.CARD, 22));
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
    int stateColor=state.equals(Language.t("● 在线"))?Ui.GREEN:state.equals(Language.t("● 未就绪"))?0xffb58a39:state.equals(Language.t("● 未授权"))?0xffbf6371:state.equals(Language.t("● 有限授权"))?0xff8270b9:Ui.MUTED;
    TextView stateLabel = ui.text(state, 12, stateColor);
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
    for (Wire p : peers) sig.append(p.id).append(p.info.toString()).append(p.fastPath != null && p.fastPath.connected).append(service.peerAccess.get(p.id));
    if (sig.toString().equals(signature)) return;
    signature = sig.toString();
    devices.removeAllViews();
    detail.removeAllViews();
    bubble("local", Language.t("本机"), "phone", Language.t("此设备"));
    for (Wire p : peers)
      bubble(
          p.id,
          p.info.optString("name", Language.t("已配对设备")),
          platformIcon(p.info.optString("platform")),
          "● " + PeerAccess.status(service.peerAccess.get(p.id),p.info.optString("platform")));
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
            device.optString("name", Language.t("已配对设备")),
            platformIcon(device.optString("platform")),
            Language.t("● 离线"));
    }

    LinearLayout card = ui.card();
    detail.addView(card);
    Wire peer = service == null ? null : service.peers.get(selected);
    if (peer == null && !selected.equals("local")) {
      JSONObject device = known.optJSONObject(selected);
      card.addView(ui.title(device == null ? Language.t("已配对设备") : device.optString("name"), 22));
      ui.gap(card, 12);
      card.addView(ui.text(Language.t("设备离线"), 14, Ui.MUTED));
      final String removeId = selected;
      card.addView(ui.button(Language.t("移出设备"), false, () -> removeDevice(removeId)));
      ui.gap(card, 28);
      View icon =
          ui.icon(
              device == null ? "monitor" : platformIcon(device.optString("platform")), Ui.MUTED);
      card.addView(icon, new LinearLayout.LayoutParams(-1, ui.dp(96)));
      ui.gap(card, 28);
      card.addView(ui.text(Language.t("让两台设备连接同一局域网，并打开 LanLink。设备上线后会自动连接。"), 14, Ui.MUTED));
      return;
    }
    card.addView(ui.title(peer == null ? Language.t("我的手机") : peer.info.optString("name", Language.t("已配对设备")), 22));
    ui.gap(card, 8);
    card.addView(
        ui.text(
            peer == null
                ? Build.MODEL + Language.t(" · 本机")
                : platformName(peer.info.optString("platform")) + Language.t(" · 已连接"),
            13,
            Ui.MUTED));
    ui.gap(card, 8);
    card.addView(
        ui.text(
            peer == null
                ? (peers.isEmpty() ? Language.t("等待其他设备加入") : Language.t("与 ") + peers.size() + Language.t(" 台设备连接"))
                : Language.t("● 同一局域网"),
            12,
            peer == null ? Ui.MUTED : Ui.GREEN));
    FrameLayout picture = new FrameLayout(this);
    View glyph =
        ui.icon(peer == null ? "phone" : platformIcon(peer.info.optString("platform")), Ui.PURPLE);
    picture.addView(glyph, new FrameLayout.LayoutParams(ui.dp(92), ui.dp(92), Gravity.CENTER));
    card.addView(picture, new LinearLayout.LayoutParams(-1, ui.dp(134)));
    card.addView(
        ui.button(
            peer == null ? Language.t("添加设备") : Language.t("桌面控制"),
            true,
            () -> {
              if (peer == null) showPairingDialog(null, null);
              else openViewer(peer);
            }));
    if(peer!=null)guardFeature(card.getChildAt(card.getChildCount()-1),peer,"input","screen");
    ui.line(card);
    LinearLayout buttons = ui.row();
    Button paste =
        ui.button(
            peer == null ? Language.t("共享本机内容") : Language.t("粘贴内容"),
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
                        main.post(() -> toast(Language.t("已发送粘贴请求")));
                      } finally {
                        s.releaseControl();
                      }
                    });
            });
    if(peer!=null)guardFeature(paste,peer,"input","clipboard");
    Button more =
        ui.button(
            Language.t("更多"),
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
        ui.iconButton("close", Language.t("关闭"), d::dismiss),
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
    LinearLayout box = sheet(peer.info.optString("name", Language.t("设备操作")));
    box.addView(
        ui.item(
            "settings",
            Language.t("撤销此设备的本机授权"),
            Language.t("关闭此设备的全部功能，需要重新申请恢复"),
            null,
            () -> {
              if (LinkService.instance != null) LinkService.instance.revokeConsent(peer.id);
              toast(Language.t("已撤销此设备的授权"));
              ((Dialog) box.getTag()).dismiss();
            }));
    box.addView(
        ui.item(
            "close",
            Language.t("移出设备"),
            Language.t("需要重新配对才能加入"),
            null,
            () -> {
              ((Dialog) box.getTag()).dismiss();
              removeDevice(peer.id);
            }));
    box.addView(ui.item("clipboard", Language.t("共享本机内容"), Language.t("选择要发送的文字或剪贴板内容"), null, this::showClipboard));
    if (!peer.info.optString("platform").equals("android")) {
      box.addView(
          ui.item(
              "power",
              Language.t("请求关机"),
              Language.t("设备将在 30 秒后关机"),
              null,
              () ->
                  new AlertDialog.Builder(this)
                      .setTitle(Language.t("请求关机？"))
                      .setMessage(peer.info.optString("name") + Language.t(" 将在 30 秒后关机。"))
                      .setNegativeButton(Language.t("取消"), null)
                      .setPositiveButton(
                          Language.t("发送请求"),
                          (d, w) ->
                              run(
                                  () -> {
                                    peer.request("shutdown", obj());
                                    main.post(() -> toast(Language.t("已发送关机请求")));
                                  }))
                      .show()));
      guardFeature(box.getChildAt(box.getChildCount()-1),peer,"shutdown");
      box.addView(
          ui.item(
              "back",
              Language.t("取消关机"),
              null,
              null,
              () ->
                  run(
                      () -> {
                        peer.request("cancelShutdown", obj());
                        main.post(() -> toast(Language.t("已发送取消请求")));
                      })));
    }
  }

  private void removeDevice(String id) {
    new AlertDialog.Builder(this)
        .setTitle(Language.t("移出设备？"))
        .setMessage(Language.t("该设备需要重新配对才能加入。其他离线成员会保留身份，上线后自动更新。"))
        .setNegativeButton(Language.t("取消"), null)
        .setPositiveButton(
            Language.t("移出"),
            (d, w) ->
                run(
                    () -> {
                      link().group.remove(id);
                      main.post(
                          () -> {
                            selected = "local";
                            update();
                            toast(Language.t("设备已移出组"));
                          });
                    }))
        .show();
  }

  private String clipboardHistorySignature="";
  static String fileSize(long size){if(size<1024)return size+" B";if(size<1024*1024)return String.format(java.util.Locale.ROOT,"%.1f KB",size/1024.0);if(size<1024L*1024*1024)return String.format(java.util.Locale.ROOT,"%.1f MB",size/1048576.0);return String.format(java.util.Locale.ROOT,"%.2f GB",size/1073741824.0);}
  private String centerKey() {
    LinkService service = LinkService.instance;
    StringBuilder value = new StringBuilder(service == null || service.staging == null
        ? "" : service.staging.list().toString());
    if (service != null && service.staging != null) value.append(service.staging.outgoingList());
    value.append(clipboardHistoryKey());
    if (service != null && centerSelection.equals("touchpad")) {
      JSONObject devices = service.authorizationDevices();
      List<String> ids = new ArrayList<>();
      for (Iterator<String> it = devices.keys(); it.hasNext();) ids.add(it.next());
      Collections.sort(ids);
      for (String id : ids) {
        Wire peer = service.peers.get(id);
        value.append(id).append(devices.optJSONObject(id)).append(peer != null && peer.connected)
            .append(service.peerAccess.get(id));
      }
    }
    return value.toString();
  }

  private void controlCenter(ScrollView scroll) {
    LinkService service = LinkService.instance;
    if (centerSelection.isEmpty()) centerSelection = service != null && service.staging != null
        && (service.staging.list().length() > 0 || service.staging.outgoingList().length() > 0) ? "files" : "clipboard";
    centerContent = layout;
    centerScroll = scroll;
    refreshCenter();
    LinearLayout.LayoutParams contentSize = (LinearLayout.LayoutParams) scroll.getLayoutParams();
    contentSize.weight = 1;
    scroll.setLayoutParams(contentSize);
    LinearLayout functions = ui.row();
    functions.setGravity(Gravity.CENTER);
    String[] keys = {"files", "clipboard", "touchpad"};
    String[] titles = {Language.t("文件暂存区"), Language.t("剪贴板"), Language.t("触控板")};
    for (int i = 0; i < keys.length; i++) {
      final String key = keys[i];
      View tile = ui.controlTile(key, titles[i], key.equals(centerSelection), () -> {
        if (!key.equals(centerSelection)) {
          centerSelection = key;
          renderPage();
        }
      });
      LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, ui.dp(112), 1);
      if (i > 0) size.setMargins(ui.dp(10), 0, 0, 0);
      functions.addView(tile, size);
    }
    LinearLayout.LayoutParams stripSize = new LinearLayout.LayoutParams(-1, -2);
    stripSize.setMargins(ui.dp(22), ui.dp(8), ui.dp(22), ui.dp(14));
    root.addView(functions, stripSize);
  }
  private void refreshCenter(){
    if(centerContent==null)return;centerContent.removeAllViews();layout=centerContent;centerSignature=centerKey();
    if(centerSelection.equals("clipboard")){clipboardPage();return;}
    if(centerSelection.equals("touchpad")){touchpadDevicesPage();return;}
    heading(Language.t("文件暂存区"),Language.t("发送进度与接收文件 · 支持取消和重试"));LinkService s=LinkService.instance;org.json.JSONArray list=s==null||s.staging==null?new org.json.JSONArray():s.staging.list();
    renderOutgoing(s);
    if(list.length()==0){LinearLayout empty=ui.card();empty.addView(ui.item("files",Language.t("暂存区是空的"),Language.t("其他设备发送的文件会出现在这里"),null,null));layout.addView(empty);return;}
    for(int i=0;i<list.length();i++){if(i>0)ui.gap(layout,16);org.json.JSONObject r=list.optJSONObject(i);String id=r.optString("id"),state=r.optString("state");LinearLayout card=ui.card();String label=state.equals("ready")?Language.t("已接收"):state.equals("receiving")?Language.t("传输中 · ")+(r.optLong("received")*100/Math.max(1,r.optLong("size")))+"%":state.equals("pending")?Language.t("待传输"):state.equals("queued")?Language.t("等待传输"):state.equals("cancelled")?Language.t("已取消"):state.equals("stopping")?Language.t("正在取消…"):Language.t("传输失败");
      card.addView(ui.item("files",r.optString("name"),r.optString("sourceName")+" · "+fileSize(r.optLong("size"))+" · "+new java.text.SimpleDateFormat("MM-dd HH:mm",java.util.Locale.ROOT).format(new java.util.Date(r.optLong("created"))),label,null));
      if(!r.optString("error").isEmpty()){ui.gap(card,8);card.addView(ui.text(displayError(r.optString("error")),12,Ui.RED));}LinearLayout actions=ui.row();
      if(state.equals("ready")){actions.addView(ui.button(Language.t("查看"),false,()->openStage(r)),new LinearLayout.LayoutParams(0,-2,1));View save=ui.button(Language.t("保存到…"),true,()->saveStage(r));LinearLayout.LayoutParams next=new LinearLayout.LayoutParams(0,-2,1);next.setMargins(ui.dp(10),0,0,0);actions.addView(save,next);}
      else if(!state.equals("receiving")&&!state.equals("queued")&&!state.equals("stopping"))actions.addView(ui.button(state.equals("pending")?Language.t("开始传输"):Language.t("重新传输"),true,()->run(()->{link().staging.start(id);main.post(()->toast(Language.t("正在接收文件…")));})),new LinearLayout.LayoutParams(0,-2,1));
      if(state.equals("receiving")||state.equals("queued")){actions.addView(ui.button(Language.t("取消传输"),false,()->run(()->{link().staging.cancel(id);main.post(this::refreshCenter);})),new LinearLayout.LayoutParams(0,-2,1));}
      else if(!state.equals("stopping")){View remove=ui.button(Language.t("删除"),false,()->deleteStage(r));remove.setContentDescription(Language.t("删除暂存文件 ")+r.optString("name"));LinearLayout.LayoutParams space=new LinearLayout.LayoutParams(-2,-2);space.setMargins(ui.dp(10),0,0,0);actions.addView(remove,space);}ui.gap(card,12);card.addView(actions);layout.addView(card);
    }
  }
  private void deleteStage(JSONObject row){
    String id=row.optString("id"),name=row.optString("name");
    new AlertDialog.Builder(this).setTitle(Language.t("删除暂存文件？"))
        .setMessage(Language.t("将删除「")+name+Language.t("」在本机暂存区的副本。尚未保存的内容将无法恢复；发送设备上的原文件不受影响。"))
        .setNegativeButton(Language.t("保留文件"),null).setPositiveButton(Language.t("删除"),(dialog,which)->run(()->{link().staging.remove(id);main.post(this::refreshCenter);})).show();
  }
  private void clearOutgoingStage(JSONObject row){
    Runnable remove=()->run(()->{link().staging.removeOutgoing(row.optString("id"));main.post(this::refreshCenter);});
    if(row.optString("state").equals("completed")){remove.run();return;}
    new AlertDialog.Builder(this).setTitle(Language.t("结束分享并清除记录？"))
        .setMessage(Language.t("接收方将无法继续下载「")+row.optString("name")+Language.t("」。需要时可从原应用重新分享；原文件不会被删除。"))
        .setNegativeButton(Language.t("保留记录"),null).setPositiveButton(Language.t("结束分享"),(dialog,which)->remove.run()).show();
  }
  private void renderOutgoing(LinkService service){
    if(service==null||service.staging==null)return;JSONArray outgoing=service.staging.outgoingList();
    for(int i=0;i<outgoing.length();i++){JSONObject row=outgoing.optJSONObject(i);String id=row.optString("id"),state=row.optString("state");boolean active=state.equals("queued")||state.equals("sending")||state.equals("preparing");
      String label=state.equals("completed")?Language.t("对方已接收"):state.equals("cancelled")?Language.t("已取消"):state.equals("failed")?Language.t("发送失败"):state.equals("preparing")?Language.t("准备发送"):state.equals("sending")?row.optLong("received")>=row.optLong("size")?Language.t("等待对方确认完成"):Language.t("发送中 · ")+(row.optLong("received")*100/Math.max(1,row.optLong("size")))+"%":Language.t("等待对方接收");
      LinearLayout card=ui.card();card.setTag("outgoing-"+id);card.addView(ui.item("files",row.optString("name"),Language.t("发送到 ")+row.optString("targetName")+" · "+fileSize(row.optLong("size")),label,null));
      if(!row.optString("error").isEmpty()){ui.gap(card,8);card.addView(ui.text(displayError(row.optString("error")),12,Ui.RED));}LinearLayout buttons=ui.row();
      if(active)buttons.addView(ui.button(Language.t("取消发送"),false,()->run(()->{link().staging.cancelOutgoing(id);main.post(this::refreshCenter);})),new LinearLayout.LayoutParams(0,-2,1));
      else {if(!state.equals("completed"))buttons.addView(ui.button(Language.t("重新发送"),true,()->run(()->{link().staging.retryOutgoing(id);main.post(this::refreshCenter);})),new LinearLayout.LayoutParams(0,-2,1));buttons.addView(ui.button(Language.t("清除记录"),false,()->clearOutgoingStage(row)),new LinearLayout.LayoutParams(0,-2,1));}
      ui.gap(card,12);card.addView(buttons);layout.addView(card);ui.gap(layout,16);
    }
  }
  private void touchpadDevicesPage() {
    heading(Language.t("触控板"), Language.t("将手机变成触控板，控制组内设备"));
    LinkService service = LinkService.instance;
    JSONObject devices = service == null ? new JSONObject() : service.authorizationDevices();
    List<String> ids = new ArrayList<>();
    for (Iterator<String> it = devices.keys(); it.hasNext();) {
      String id = it.next();
      JSONObject device = devices.optJSONObject(id);
      JSONObject capabilities = device == null ? null : device.optJSONObject("capabilities");
      if (capabilities != null && capabilities.optBoolean("relativeInput")) ids.add(id);
    }
    ids.sort((first, second) -> {
      Wire a = service.peers.get(first), b = service.peers.get(second);
      int online = Boolean.compare(b != null && b.connected, a != null && a.connected);
      return online != 0 ? online : devices.optJSONObject(first).optString("name")
          .compareToIgnoreCase(devices.optJSONObject(second).optString("name"));
    });
    if (ids.isEmpty()) {
      LinearLayout empty = ui.card();
      empty.addView(ui.item("touchpad", Language.t("暂无支持触控板的设备"),
          Language.t("请打开组内电脑的 LanLink，并更新到支持原生触控板的版本。设备上线后会自动出现在这里。"), null, null));
      layout.addView(empty);
    } else {
      for (String id : ids) {
        Wire peer = service.peers.get(id);
        JSONObject device = devices.optJSONObject(id);
        boolean online = peer != null && peer.connected;
        JSONObject report = service.peerAccess.get(id);
        String state = online ? touchpadAccessStatus(report) : Language.t("离线 · 等待设备上线");
        LinearLayout card = ui.card();
        View row = ui.item(platformIcon(device.optString("platform")), device.optString("name", Language.t("设备")),
            state, online ? Language.t("进入") : null, online ? () -> openTouchpad(peer) : null);
        row.setContentDescription(device.optString("name", Language.t("设备")) + "，" + state
            + (online ? Language.t("，打开全屏触控板") : ""));
        if (!online) row.setAlpha(.55f);
        else if (report != null && !PeerAccess.allowed(report, "input")) guardFeature(row, peer, "input");
        card.addView(row);
        layout.addView(card);
        ui.gap(layout, 12);
      }
    }
    ui.gap(layout, 14);
    LinearLayout help = ui.card();
    help.addView(ui.title(Language.t("原生触控板"), 16));
    ui.gap(help, 8);
    help.addView(ui.text(Language.t("单指移动与轻点单击，双指滚动与轻点右键，长按后拖动。连接只需远程输入授权，无需共享屏幕。"), 13, Ui.MUTED));
    layout.addView(help);
  }

  private String touchpadAccessStatus(JSONObject report) {
    JSONObject permissions = report == null ? null : report.optJSONObject("permissions");
    JSONObject input = permissions == null ? null : permissions.optJSONObject("input");
    if (input == null) return Language.t("在线 · 正在检查授权");
    if (!input.optBoolean("appEnabled") || !input.optBoolean("systemGranted"))
      return Language.t("在线 · 需要开启输入权限");
    return input.optBoolean("peerAllowed") ? Language.t("在线 · 已授权") : Language.t("在线 · 点击请求授权");
  }

  private void openTouchpad(Wire peer) {
    LinkService service = LinkService.instance;
    if (service == null || !peer.connected || service.peers.get(peer.id) != peer) {
      toast(Language.t("设备已离线，请等待设备重新连接"));
      refreshCenter();
      return;
    }
    if (viewer != null) viewer.dismiss();
    TouchpadDialog session = new TouchpadDialog(this, service, peer);
    viewer = session;
    session.setOnDismissListener(ignored -> {
      if (viewer == session) viewer = null;
      if (page.equals("center") && centerSelection.equals("touchpad")) refreshCenter();
    });
    session.show();
  }

  private void openStage(org.json.JSONObject row){org.json.JSONArray files=row.optJSONArray("files");if(files==null)return;if(row.optString("type").equals("folder")){LinearLayout box=sheet(row.optString("name"));for(int i=0;i<files.length();i++){org.json.JSONObject f=files.optJSONObject(i);box.addView(ui.item("files",f.optString("relative"),fileSize(f.optLong("size")),null,()->viewStageFile(row.optString("id"),f.optString("relative"))));}if(files.length()==0)box.addView(ui.text(Language.t("这是一个空文件夹"),14,Ui.MUTED));}else if(files.length()>0)viewStageFile(row.optString("id"),files.optJSONObject(0).optString("relative"));}
  private void viewStageFile(String id,String relative){try{android.net.Uri uri=link().staging.uri(id,relative);String mime=getContentResolver().getType(uri);startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime==null?"application/octet-stream":mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));}catch(Exception e){toast(Language.t("没有可打开此文件的应用，可以先保存到手机"));}}
  private void saveStage(org.json.JSONObject row){savingStage=row.optString("id");if(row.optString("type").equals("folder"))startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),43);else{String name=row.optString("name");String ext=android.webkit.MimeTypeMap.getFileExtensionFromUrl(name),mime=android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase(java.util.Locale.ROOT));startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime==null?"application/octet-stream":mime).putExtra(Intent.EXTRA_TITLE,name),42);}}
  private void chooseShareDevices(java.util.List<android.net.Uri> uris){
    if(!alive||isFinishing())return;pendingShareUris=new java.util.ArrayList<>(uris);LinkService service=LinkService.instance;
    if(service==null){main.postDelayed(()->chooseShareDevices(uris),500);return;}
    LinearLayout box=sheet(Language.t("发送文件到设备"));TextView progress=ui.text(uris.size()+Language.t(" 个文件 · 接收到对方暂存区"),14,Ui.MUTED);box.addView(progress);
    Dialog dialog=(Dialog)box.getTag();shareDialog=dialog;String requestId=java.util.UUID.randomUUID().toString();java.util.concurrent.atomic.AtomicBoolean busy=new java.util.concurrent.atomic.AtomicBoolean();List<View> choices=new ArrayList<>();
    Runnable reset=()->{if(!dialog.isShowing())return;busy.set(false);for(View item:choices){item.setEnabled(true);item.setAlpha(1f);}};
    dialog.setOnDismissListener(d->{sheets.remove(dialog);if(shareDialog==dialog){shareDialog=null;if(!isChangingConfigurations())pendingShareUris=null;}});
    boolean any=false;
    for(Wire peer:service.peers.values()){
      if(!peer.connected)continue;any=true;View item=ui.item(peer.info.optString("platform").equals("android")?"monitor":"laptop",peer.info.optString("name",Language.t("设备")),Language.t("在线 · 点击发送"),null,()->{
        if(!dialog.isShowing()||!busy.compareAndSet(false,true))return;
        for(View choice:choices){choice.setEnabled(false);choice.setAlpha(.5f);}progress.setText(Language.t("正在检查设备授权…"));
        actions.execute(()->{
          try{
            if(!dialog.isShowing())return;JSONObject report=peer.request("permission-status",obj());service.peerAccess.put(peer.id,report);
            main.post(()->{
              if(!alive||!dialog.isShowing())return;
              if(!PeerAccess.allowed(report,"clipboard")){
                progress.setText(Language.t("需要对方允许剪贴板文件共享"));reset.run();
                new AlertDialog.Builder(this).setTitle(Language.t("需要设备授权")).setMessage(Language.t("请在「")+peer.info.optString("name")+Language.t("」上允许剪贴板文件共享。"))
                    .setNegativeButton(Language.t("取消"),null).setPositiveButton(Language.t("发起授权"),(d,w)->run(()->{peer.request("permission-request",obj("permissions",new JSONArray().put("clipboard")));main.post(()->toast(Language.t("已发起授权，请在对方设备确认后再次选择")));})).show();return;
              }
              pendingShareUris=null;dialog.dismiss();Intent keep=new Intent(this,LinkService.class);ClipData clips=ClipData.newRawUri(Language.t("LanLink 文件分享"),uris.get(0));for(int n=1;n<uris.size();n++)clips.addItem(new ClipData.Item(uris.get(n)));keep.setClipData(clips);keep.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startForegroundService(keep);
              service.jobs.execute(()->{try{service.staging.share(peer,uris,requestId);main.post(()->{if(alive){toast(Language.t("已开始发送，可在文件暂存区查看结果"));if(page.equals("center"))refreshCenter();}});}catch(Exception failure){main.post(()->{if(alive){toast(failure.getMessage());if(page.equals("center"))refreshCenter();}});}});
              toast(Language.t("正在准备发送文件"));page="center";centerSelection="files";renderPage();
            });
          }catch(Exception failure){main.post(()->{if(dialog.isShowing()){progress.setText(Language.t("无法发送：")+displayError(failure.getMessage()));reset.run();}});}
        });
      });
      choices.add(item);box.addView(item);
    }
    if(!any)box.addView(ui.text(Language.t("暂无在线设备。请打开对方 LanLink，并保持同一网络。"),14,Ui.MUTED));
    View refresh=ui.button(Language.t("刷新设备"),false,()->{if(busy.get())return;dialog.dismiss();chooseShareDevices(uris);});choices.add(refresh);box.addView(refresh);
  }
  private void manualClipboard(boolean upload){run(()->{if(upload)link().publishClipboard();else link().groupClipboard.downloadNow();main.post(()->toast(upload?Language.t("剪贴板已上传到组"):Language.t("组剪贴板已同步到本机")));});}
  private void clipboardSettingsCard(){
    ui.gap(layout,16);LinearLayout card=ui.card();card.addView(ui.title(Language.t("共享剪贴板"),17));ui.gap(card,12);
    for(String key:new String[]{"upload","download","shortcuts"}){
      if(key.equals("shortcuts") && getSharedPreferences("identity",0).getBoolean("clipboard_upload",true) && getSharedPreferences("identity",0).getBoolean("clipboard_download",true))continue;
      String title=key.equals("upload")?Language.t("允许自动上传剪贴板信息到组"):key.equals("download")?Language.t("允许自动获取组剪贴板到本机"):Language.t("在首页显示快捷同步剪贴板按钮");
      LinearLayout row=ui.row();TextView label=ui.text(title,13,Ui.INK);row.addView(label,new LinearLayout.LayoutParams(0,-2,1));Switch toggle=new Switch(this);toggle.setContentDescription(title);toggle.setChecked(getSharedPreferences("identity",0).getBoolean("clipboard_"+key,!key.equals("shortcuts")));toggle.setThumbTintList(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{Ui.PURPLE,Ui.THUMB}));toggle.setTrackTintList(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{Ui.LILAC,Ui.LINE}));row.addView(toggle);card.addView(row);
      boolean upload=key.equals("upload");if(!key.equals("shortcuts") && !toggle.isChecked()){ui.gap(card,8);Button manual=ui.button(upload?Language.t("上传"):Language.t("下载"),false,()->manualClipboard(upload));manual.setContentDescription(upload?Language.t("上传剪贴板"):Language.t("下载剪贴板"));manual.setTextSize(12);manual.setMinWidth(0);manual.setMinimumWidth(0);manual.setPadding(ui.dp(10),ui.dp(8),ui.dp(10),ui.dp(8));LinearLayout.LayoutParams compact=new LinearLayout.LayoutParams(-2,-2);compact.setMargins(ui.dp(8),0,0,0);row.addView(manual,compact);}
      toggle.setOnCheckedChangeListener((button,on)->{getSharedPreferences("identity",0).edit().putBoolean("clipboard_"+key,on).apply();renderPage();});ui.gap(card,12);
    }
    LinkService service=LinkService.instance;card.addView(ui.text(service==null?Language.t("服务正在启动"):Language.t(service.groupClipboard.capability),12,Ui.MUTED));layout.addView(card);
  }
  private String clipboardHistoryKey(){LinkService service=LinkService.instance;if(service==null)return "starting";org.json.JSONArray list=service.groupClipboard.history();StringBuilder key=new StringBuilder();for(int i=0;i<list.length();i++)key.append(list.optJSONObject(i).optString("id")).append(';');return key.toString();}
  private void clipboardPage(){
    heading(Language.t("剪贴板"),Language.t("最近 10 分钟 · 最多 30 条文字记录"));LinkService service=LinkService.instance;org.json.JSONArray list=service==null?new org.json.JSONArray():service.groupClipboard.history();clipboardHistorySignature=clipboardHistoryKey();
    if(list.length()==0)layout.addView(ui.text(Language.t("暂无同步记录，复制文字后将在这里出现。"),14,Ui.MUTED));
    for(int i=0;i<list.length();i++){
      JSONObject record=list.optJSONObject(i);if(record==null)continue;String id=record.optString("id");LinearLayout card=ui.card();LinearLayout meta=ui.row();TextView source=ui.text(record.optString("name",Language.t("设备"))+" · "+new java.text.SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(new Date(record.optLong("at"))),12,Ui.MUTED);meta.addView(source,new LinearLayout.LayoutParams(0,-2,1));meta.addView(ui.button(Language.t("复制"),false,()->run(()->{link().groupClipboard.copy(id);main.post(()->toast(Language.t("已复制到本机剪贴板")));})));card.addView(meta);ui.gap(card,12);String value=record.optString("text");TextView text=ui.text(value.length()>2000?value.substring(0,2000):value,14,Ui.INK);text.setMaxLines(6);text.setEllipsize(android.text.TextUtils.TruncateAt.END);text.setTextIsSelectable(true);card.addView(text);if(value.length()>2000)card.addView(ui.text(Language.t("长文本预览 · 复制可获取完整内容"),12,Ui.MUTED));layout.addView(card);ui.gap(layout,12);
    }
    settingsFooter();
  }
  private void showClipboard() {
    LinearLayout box = sheet(Language.t("本机内容"));
    box.addView(ui.text(Language.t("自动同步可在设置的「共享剪贴板」中开关；允许哪些设备访问本机内容，可在「组设备授权管理」中调整。手动共享的内容将发送给已授权的同组设备。"), 13, Ui.MUTED));
    ui.gap(box, 16);
    box.addView(ui.button(Language.t("打开共享设置"), false, () -> { ((Dialog) box.getTag()).dismiss(); page = "settings"; renderPage(); }));
    ui.gap(box, 12);
    EditText shared = ui.edit(Language.t("输入要共享的文本"));
    shared.setSingleLine(false);
    shared.setMinLines(3);
    box.addView(shared);
    ui.gap(box, 12);
    box.addView(
        ui.button(
            Language.t("共享文本"),
            true,
            () -> {
              String value = shared.getText().toString();
              run(
                  () -> {
                    link().publishText(value);
                    main.post(() -> toast(Language.t("文本已共享")));
                  });
            }));
    ui.gap(box, 10);
    box.addView(
        ui.button(
            Language.t("共享当前剪贴板"),
            false,
            () ->
                run(
                    () -> {
                      link().publishClipboard();
                      main.post(() -> toast(Language.t("剪贴板已共享")));
                    })));
    ui.gap(box, 10);
    box.addView(
        ui.button(
            Language.t("接收共享内容"),
            false,
            () ->
                run(
                    () -> {
                      link().receiveCurrent();
                      main.post(() -> toast(Language.t("已接收到剪贴板")));
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
    LinearLayout box = sheet(Language.t("ADB / Root 输入"));
    box.addView(ui.text(Language.t("在电脑端打开「安卓授权」，选择本机。首次允许 USB 调试时勾选始终允许此电脑；绑定会保存，连接可用时自动恢复。"), 14, Ui.MUTED));
    ui.gap(box, 14);
    box.addView(
        ui.text(Language.t("应用重启后助手会自动重连。非 Root 手机重启后，需已信任的 USB 或无线调试连接可用；不会使用无障碍或专用输入法接管输入。"), 13, Ui.MUTED));
    ui.gap(box, 16);
    box.addView(ui.button(Language.t("打开开发者选项"), true, this::openAdbSettings));
    ui.gap(box, 10);
    box.addView(
        ui.button(
            LinkService.instance != null && LinkService.instance.rootActive()
                ? Language.t("已 Root 授权 · 关闭 Root 模式")
                : Language.t("开启 Root 模式"),
            false,
            this::toggleRootMode));
  }

  private void toggleRootMode() {
    if (LinkService.instance != null) selectPermissionMode(!LinkService.instance.rootEnabled());
  }

  private void requestScreen() {
    if (LinkService.instance != null && LinkService.instance.privilegedScreenReady()) {
      LinkService.instance.setAllowed("screen", true);
      toast(Language.t("ADB / Root 串流已就绪，收到屏幕请求时自动开始"));
      return;
    }
    MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
    Intent request =
        Build.VERSION.SDK_INT >= 34
            ? manager.createScreenCaptureIntent(
                android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
            : manager.createScreenCaptureIntent();
    startActivityForResult(request, 10);
  }

  private void showAdvanced() {
    LinearLayout box = sheet(Language.t("高级设置"));
    box.addView(ui.text(Language.t("同组设备通常会自动连接；自动发现不可用时可输入电脑的局域网 IP。"), 13, Ui.MUTED));
    ui.gap(box, 16);
    EditText address = ui.edit(Language.t("例如 192.168.1.10"));
    address.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
    box.addView(address);
    ui.gap(box, 10);
    box.addView(
        ui.button(
            Language.t("连接设备"),
            true,
            () -> {
              String value = address.getText().toString().trim();
              if (value.isEmpty()) {
                address.setError(Language.t("请输入 IP 地址"));
                return;
              }
              run(() -> link().connect(value, 45831));
            }));
    ui.gap(box, 24);
    box.addView(ui.title(Language.t("ADB / Root 输入助手"), 16));
    ui.gap(box, 8);
    box.addView(ui.text(Language.t("支持系统键鼠注入。电脑端完成首次 ADB 授权后保存绑定，助手可自动重连；手机重启后需已信任的调试连接可用。"), 13, Ui.MUTED));
    ui.gap(box, 8);
    box.addView(ui.text(Language.t("没有 Root / ADB 时仍可查看已授权的屏幕，无法操作手机。"), 13, Ui.MUTED));
    ui.gap(box, 14);
    box.addView(
        ui.button(
            Language.t("显示 ADB 启动命令"),
            false,
            () ->
                run(
                    () -> {
                      String command = "adb shell \"" + link().shell.command() + "\"";
                      main.post(
                          () ->
                              new AlertDialog.Builder(this)
                                  .setTitle(Language.t("在已授权 ADB 的电脑执行"))
                                  .setMessage(command + Language.t("\n\n保持命令运行。服务重启后需使用新命令。"))
                                  .setPositiveButton(
                                      Language.t("复制命令"),
                                      (d, w) ->
                                          ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                                              .setPrimaryClip(
                                                  ClipData.newPlainText("ADB command", command)))
                                  .setNegativeButton(Language.t("关闭"), null)
                                  .show());
                    })));
    ui.gap(box, 10);
    box.addView(
        ui.button(
            LinkService.instance != null && LinkService.instance.rootActive()
                ? Language.t("已 Root 授权 · 关闭 Root 模式")
                : Language.t("开启 Root 模式"),
            false,
            this::toggleRootMode));
  }

  private void refreshPermissionNotice(LinkService s) {
    if (s != null) s.clearResolvedPermissionNotice();
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
    LinearLayout.LayoutParams noticeParams = new LinearLayout.LayoutParams(-1, -2);
    noticeParams.bottomMargin = controlled == null ? 0 : -ui.dp(14);
    noticeBox.setLayoutParams(noticeParams);
    if (controlled != null) {
      LinearLayout activeBanner = ui.row();
      activeBanner.setPadding(ui.dp(16), ui.dp(12), ui.dp(12), ui.dp(28));
      activeBanner.setBackground(ui.shape(Ui.LILAC,20));
      LinearLayout.LayoutParams bannerParams=new LinearLayout.LayoutParams(-1,-2);bannerParams.setMargins(0,ui.dp(8),0,0);activeBanner.setLayoutParams(bannerParams);
      LinearLayout labels = ui.column();
      TextView controlledText=ui.title(Language.t("● 正在被控制 / 共享屏幕"),14);controlledText.setTextColor(Ui.PURPLE);labels.addView(controlledText);
      ui.gap(labels, 5);
      TextView deviceName=ui.text(controlled,12,Ui.MUTED);
      deviceName.setSingleLine(true);
      deviceName.setEllipsize(android.text.TextUtils.TruncateAt.END);
      labels.addView(deviceName);
      LinearLayout.LayoutParams labelParams=new LinearLayout.LayoutParams(0,-2,1);
      labelParams.rightMargin=ui.dp(12);
      activeBanner.addView(labels,labelParams);
      Button stopButton=ui.button(
              Language.t("中止任务"),
              false,
              () -> {
                s.stopInbound();
                update();
              });
      stopButton.setTextSize(13);
      stopButton.setBackground(ui.touch(Ui.LILAC,14));
      activeBanner.addView(stopButton,new LinearLayout.LayoutParams(-2,ui.dp(48)));
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
      toast(Language.t("请求已结束，请让对方重新发起"));
      return;
    }
    if (s.consent.peek() != null) {
      closePermissionDialog();
      if (page.equals("settings")) {
        int state = s != null && s.rootActive() ? 2 : s != null && s.rootEnabled() ? 1 : 0;
        if (rootUiState != state) {
          renderPage();
          return;
        }
      }
      if(page.equals("clipboard") && !clipboardHistorySignature.equals(clipboardHistoryKey())){renderPage();return;}
    refreshConsent(s);
      return;
    }
    String key = request.peer + "/" + request.needs.toString();
    if (permissionDialog != null && key.equals(permissionDialogKey)) {
      refreshPermissionRows();
      return;
    }
    closePermissionDialog();
    LinearLayout box = sheet(request.needs.contains("adb") ? s.adbRevoked()?Language.t("恢复 ADB 授权"):Language.t("ADB 授权") : Language.t("开启协作权限"));
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
    box.addView(ui.title(request.name + Language.t(" 请求协作"), 16));
    ui.gap(box, 8);
    if(!request.needs.contains("adb"))box.addView(ui.text(Language.t("开启所需权限后即可协作，已开启的项目会显示「已授权」。"), 14, Ui.MUTED));
    ui.gap(box, 18);
    if (request.needs.contains("adb")) {
      box.addView(ui.title(Language.t("允许电脑配置 ADB"), 18));
      ui.gap(box, 12);
      box.addView(ui.text(s.adbRevoked()?Language.t("你之前解除了本应用的 ADB 授权。允许后，已信任的电脑可通过数据线恢复键鼠与屏幕串流。设备控制权限仍由你单独决定。"):Language.t("本机尚未完成 ADB 授权。允许后，请连接电脑数据线，在电脑连接引导中完成配置。设备控制权限仍由你单独决定。"),14,Ui.MUTED));
      ui.gap(box, 18);
      box.addView(ui.button(Language.t("允许 ADB 授权"),true,()->{s.enableAdbAuthorization();s.dismissPermissionNotice();closePermissionDialog();update();}));
      ui.gap(box,10);
      box.addView(ui.button(Language.t("暂不允许"),false,dialog::dismiss));
      return;
    } else if (s.rootEnabled() && !request.needs.contains("consent")) {
      box.addView(ui.text(Language.t("Root 助手或系统操作暂不可用，请确认 Root 管理器仍允许 LanLink。"), 14, Ui.MUTED));
      if (s.shell.lastInputError != null)
        box.addView(ui.text(s.shell.lastInputError, 13, Ui.MUTED));
      box.addView(
          ui.button(
              Language.t("通过 Root 恢复权限"),
              true,
              () ->
                  run(
                      () -> {
                        s.setRootMode(true);
                        main.post(this::update);
                      })));
    } else {
      if (request.needs.contains("input")) {
        box.addView(
            ui.title(
                s.shell.ready() && s.shell.lastInputError == null
                    ? Language.t("Root / ADB 增强输入 · 已授权")
                    : Language.t("需要开启系统输入权限"),
                15));
        if (s.shell.lastInputError != null)
          box.addView(ui.text(s.shell.lastInputError, 13, Ui.MUTED));
        box.addView(ui.text(Language.t("在电脑端完成一次 ADB 键鼠授权并记住此电脑。应用重启后助手会重连；手机重启后需已信任的调试连接可用。"), 13, Ui.MUTED));
        ui.gap(box, 12);
        permissionAction(
            box,
            Language.t("允许远程输入"),
            () -> s.allowed("input"),
            () -> {
              s.setAllowed("input", true);
              update();
            });
        permissionAction(
            box,
            Language.t("打开开发者选项"),
            () -> s.shell.ready() && s.shell.lastInputError == null,
            this::openAdbSettings);
      }
      if (request.needs.contains("screen")) {
        ui.gap(box, 16);
        box.addView(ui.text(Language.t("ADB 助手就绪时直接串流；未连接助手时可使用系统屏幕共享。"), 13, Ui.MUTED));
        permissionAction(
            box, Language.t("授权本次屏幕共享"), () -> s.allowed("screen") && s.screenReady(), this::requestScreen);
      }
      if (request.needs.contains("clipboard"))
        permissionAction(
            box,
            Language.t("允许剪贴板与文件共享"),
            () -> s.allowed("clipboard"),
            () -> {
              s.setAllowed("clipboard", true);
              update();
            });
    }
    ui.gap(box, 12);
    box.addView(ui.text(Language.t("权限就绪后，等待中的远程窗口会自动重试。"), 12, Ui.MUTED));
    ui.gap(box, 16);
    box.addView(
        ui.button(
            Language.t("完成设置"),
            false,
            () -> {
              boolean ready =
                  (!request.needs.contains("input")
                          || s.allowed("input")
                              && s.capabilities().optBoolean("input")
                              && s.shell.lastInputError == null)
                      && (!request.needs.contains("screen")
                          || s.allowed("screen") && s.screenReady())
                      && (!request.needs.contains("clipboard") || s.allowed("clipboard"));
              if (!ready) {
                toast(Language.t("仍有权限未开启，请完成上方设置"));
                return;
              }
              s.dismissPermissionNotice();
              ((Dialog) box.getTag()).dismiss();
              update();
            }));
    ui.gap(box, 10);
    box.addView(ui.button(Language.t("暂不设置"), false, dialog::dismiss));
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
      b.setText(permissionTitles.get(b) + (granted ? Language.t(" · 已授权") : ""));
      b.setEnabled(!granted);
      b.setTextColor(granted ? Ui.GREEN : Ui.dark ? 0xff171323 : Color.WHITE);
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
    LinearLayout box = sheet(Language.t("设备授权确认"));
    Dialog dialog = (Dialog) box.getTag();
    consentDialog = dialog;
    box.addView(ui.text(request.name + Language.t(" 希望控制这台手机。默认允许全部功能，可在高级选项中调整。"), 15, Ui.INK));
    Map<String, CheckBox> choices = new LinkedHashMap<>();
    LinearLayout advanced = ui.column(); advanced.setVisibility(View.GONE);
    box.addView(ui.button(Language.t("高级选项"), false, () -> advanced.setVisibility(advanced.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE)));
    box.addView(advanced);
    String[] labels = {Language.t("键鼠标操作"), Language.t("屏幕共享"), Language.t("剪贴板文件共享")};
    for (int i = 0; i < DeviceConsent.KEYS.length; i++) {
      CheckBox check = new CheckBox(this);
      check.setText(labels[i]);
      check.setTextColor(Ui.INK);
      check.setChecked(true);
      check.setPadding(0, ui.dp(10), 0, ui.dp(10));
      choices.put(DeviceConsent.KEYS[i], check);
      advanced.addView(check);
    }
    boolean[] decided = {false};
    ui.gap(box, 12);
    box.addView(
        ui.button(
            Language.t("同意并记住"),
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
                // The retried operation requests only the system permissions it actually needs.
              } catch (Exception e) {
                toast(e.getMessage());
              }
            }));
    ui.gap(box, 10);
    box.addView(ui.button(Language.t("暂不允许"), false, dialog::dismiss));
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
    if (ui == null) return;
    if (page.equals("layout") && groupLayoutPanel != null) groupLayoutPanel.refresh();
    if(page.equals("center")&&!centerSignature.equals(centerKey()))refreshCenter();
    LinkService s = LinkService.instance;
    if (page.equals("settings") && !settingsSignature.equals(settingsState(s))) {
      renderPage();
      return;
    }
    if(page.equals("clipboard") && !clipboardHistorySignature.equals(clipboardHistoryKey())){renderPage();return;}
    if(page.equals("center")&&!centerSignature.equals(centerKey()))refreshCenter();
    refreshConsent(s);
    refreshPermissionRows();
    if(status!=null)status.setText(s == null ? Language.t("服务正在启动…") : Language.t(s.status));
    if(mode!=null)mode.setText(
        s != null && s.shell != null && s.shell.ready()
            ? Language.t("输入助手已连接 · ADB / Root")
            : Language.t("输入助手待连接 · 可在电脑端恢复"));
    if (s != null) {
      if (s.backgroundRecovery != null) s.backgroundRecovery.foreground(resumed);
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
    refreshBackgroundGuide(s);
  }

  private void refreshBackgroundGuide(LinkService service) {
    if (service == null || service.backgroundRecovery == null) return;
    if (backgroundGuideStatus != null) backgroundGuideStatus.setText(backgroundStatus());
    if (!resumed || !alive || isFinishing() || !hasWindowFocus() || backgroundGuide != null
        || permissionDialog != null || consentDialog != null || pairDialog != null || viewer != null
        || !sheets.isEmpty() || service.consent.peek() != null
        || service.permissionNotice.peek(System.currentTimeMillis()) != null) return;
    JSONObject pending = service.backgroundRecovery.pendingGuide();
    if (pending != null) showBackgroundGuide(pending);
  }

  private String backgroundStatus() {
    JSONObject policy = BackgroundRecovery.readPolicy(this);
    String battery = policy.optBoolean("batteryExempt")
        ? Language.t("省电白名单：已加入") : Language.t("省电白名单：尚未加入");
    String restricted = policy.optBoolean("backgroundRestricted")
        ? Language.t("后台运行：系统当前有限制") : Language.t("后台运行：未检测到系统限制");
    return battery + "\n" + restricted + "\n" + Language.t("自启动：请在系统设置中确认");
  }

  private void showBackgroundGuide(JSONObject recovery) {
    if (!alive || isFinishing() || backgroundGuide != null) return;
    LinkService service = LinkService.instance;
    if (recovery == null && service != null && service.backgroundRecovery != null)
      recovery = service.backgroundRecovery.pendingGuide();
    LinearLayout box = sheet(Language.t("后台运行与自启动"));
    Dialog dialog = (Dialog) box.getTag();
    backgroundGuide = dialog;
    dialog.setCanceledOnTouchOutside(false);
    dialog.setOnDismissListener(ignored -> {
      sheets.remove(dialog);
      if (backgroundGuide == dialog) { backgroundGuide = null; backgroundGuideStatus = null; }
    });
    if (recovery != null) {
      box.addView(ui.text(recovery.optString("reason").equals("restricted")
          ? Language.t("上次后台连接中断，且系统限制了 LanLink 后台运行。这可能与本次中断有关，请检查下方设置。")
          : Language.t("上次后台连接意外中断，系统未提供明确原因。可检查省电与自启动设置，减少后台被清理的机会。"), 14, Ui.INK));
      ui.gap(box, 14);
    }
    box.addView(ui.title(Build.MANUFACTURER + " · " + Build.MODEL, 16));
    ui.gap(box, 10);
    box.addView(ui.text(BackgroundRecovery.guidance(), 14, Ui.MUTED));
    ui.gap(box, 16);
    backgroundGuideStatus = ui.text(backgroundStatus(), 13, Ui.MUTED);
    box.addView(backgroundGuideStatus);
    ui.gap(box, 16);
    box.addView(ui.button(Language.t("设置省电白名单"), true, () -> {
      if (!BackgroundRecovery.openBatterySettings(this)) toast(Language.t("无法打开系统设置，请手动进入 LanLink 应用信息进行设置"));
    }));
    ui.gap(box, 10);
    box.addView(ui.button(Language.t("设置自启动与后台运行"), false, () -> {
      if (!BackgroundRecovery.openStartupSettings(this)) toast(Language.t("无法打开系统设置，请手动进入 LanLink 应用信息进行设置"));
    }));
    ui.gap(box, 10);
    box.addView(ui.text(Language.t("若打开的是应用信息页，请按上方机型说明进入电池或自启动选项。设置后返回即可；这些设置无法保证系统永不清理后台。"), 12, Ui.MUTED));
    ui.gap(box, 14);
    box.addView(ui.button(Language.t("完成 / 稍后设置"), false, dialog::dismiss));
    if (recovery != null && service != null && service.backgroundRecovery != null)
      service.backgroundRecovery.guideShown(recovery.optString("id"));
  }

  protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    state.putString("page", page);if(pendingShareUris!=null)state.putParcelableArrayList("pendingShareUris",pendingShareUris);
    state.putString("selected", selected);state.putString("savingStage",savingStage);state.putString("centerSelection",centerSelection);
  }

  @Override
  public void onConfigurationChanged(android.content.res.Configuration configuration) {
    super.onConfigurationChanged(configuration);
    Language.refresh(this);
    if (viewer == null) renderPage();
    else if (viewer.getWindow() != null) {
      viewer.getWindow().setLayout(-1, -1);
      viewer.getWindow().getDecorView().requestApplyInsets();
    }
  }

  protected void onResume() {
    super.onResume();
    resumed = true;
    if (Language.refresh(this)) renderPage();
    if(awaitingEdgeGlowPermission){awaitingEdgeGlowPermission=false;if(Settings.canDrawOverlays(this))getSharedPreferences("identity",0).edit().putBoolean("edge_glow",true).apply();if("settings".equals(page))renderPage();}
    if (LinkService.instance != null){LinkService.instance.uiVisible=true;LinkService.instance.groupClipboard.poll();}
    if (LinkService.instance != null && LinkService.instance.backgroundRecovery != null)
      LinkService.instance.backgroundRecovery.sample(true);
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
    if (Intent.ACTION_SEND.equals(i.getAction())||Intent.ACTION_SEND_MULTIPLE.equals(i.getAction())) {
      java.util.ArrayList<android.net.Uri> uris=Intent.ACTION_SEND_MULTIPLE.equals(i.getAction())?i.getParcelableArrayListExtra(Intent.EXTRA_STREAM):new java.util.ArrayList<>();if(uris==null)uris=new java.util.ArrayList<>();android.net.Uri uri=Intent.ACTION_SEND.equals(i.getAction())?i.getParcelableExtra(Intent.EXTRA_STREAM):null;if(uri!=null)uris.add(uri);if(uris.isEmpty()&&i.getClipData()!=null)for(int n=0;n<i.getClipData().getItemCount();n++)if(i.getClipData().getItemAt(n).getUri()!=null)uris.add(i.getClipData().getItemAt(n).getUri());
      if(!uris.isEmpty()){final java.util.List<android.net.Uri> selectedUris=new java.util.ArrayList<>(uris);main.postDelayed(()->chooseShareDevices(selectedUris),1000);}
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
    viewer = new Dialog(this, Ui.dialogTheme());
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
        ui.iconButton("back", Language.t("关闭并释放控制"), session::dismiss),
        new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
    TextView name = ui.title(p.info.optString("name"), 18);
    name.setMaxLines(1);
    name.setEllipsize(android.text.TextUtils.TruncateAt.END);
    name.setPadding(ui.dp(12), 0, 0, 0);
    title.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
    box.addView(title);
    ui.gap(box, 12);
    TextView frameStatus = ui.text(Language.t("正在连接画面…"), 12, Ui.MUTED);
    box.addView(frameStatus);
    java.util.concurrent.atomic.AtomicBoolean autoControl =
        new java.util.concurrent.atomic.AtomicBoolean(true);
    Button control = ui.button(Language.t("请求中"), false, () -> {});
    control.setTextSize(12);
    control.setOnClickListener(
        v ->
            run(
                () -> {
                  autoControl.set(true);
                  freshRequest.set(true);
                  link().control(p.id, freshRequest.get());
                  freshRequest.set(false);
                  main.post(() -> control.setText(Language.t("已控制")));
                }));
    title.addView(control, new LinearLayout.LayoutParams(ui.dp(100), ui.dp(44)));
    ui.gap(box, 12);
    image = new ImageView(this);
    image.setFocusableInTouchMode(true);
    boolean phone = "android".equals(p.info.optString("platform"));
    TextureView videoTexture = new TextureView(this);
    VideoViewport videoBox = new VideoViewport(this, videoTexture, image, !phone);
    final RemoteFullscreen[] fullscreen = new RemoteFullscreen[1];
    videoBox.followCursor = getPreferences(0).getBoolean("follow_remote_cursor", true);
    videoBox.setBackground(ui.shape(0xff202637, 22));
    videoBox.setClipToOutline(true);
    FrameLayout videoStage = new FrameLayout(this);
    videoStage.addView(videoBox, new FrameLayout.LayoutParams(-1, -1));
    box.addView(videoStage, new LinearLayout.LayoutParams(-1, 0, 1));
    final VideoPlayer player = new VideoPlayer(p, videoTexture, image, frameStatus);
    player.viewport = videoBox;
    if (!phone) {
      LinearLayout viewportTools = ui.row();
      viewportTools.addView(
          ui.text(Language.t("拖动画面 · 双指缩放"), 11, Ui.MUTED), new LinearLayout.LayoutParams(0, -2, 1));
      Button reset = ui.button(Language.t("复位画面"), false, videoBox::reset);
      reset.setTextSize(11);
      viewportTools.addView(reset, new LinearLayout.LayoutParams(ui.dp(100), ui.dp(40)));
      box.addView(viewportTools);
      android.widget.Switch follow = new android.widget.Switch(this);
      follow.setText(Language.t("缩放画面跟随鼠标"));
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
            if (fullscreen[0] != null && fullscreen[0].isActive()) return false;
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
                        control.setText(Language.t("重新请求"));
                      }
                    }));
    if (!phone) box.addView(touchpad, new LinearLayout.LayoutParams(-1, 0, 0.9f));
    else {
      LinearLayout keys = ui.row();
      for (String[] key :
          new String[][] {{"back", Language.t("◁ 返回")}, {"home", Language.t("○ 主页")}, {"recents", Language.t("□ 最近任务")}})
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
          {"Ctrl+A", "Ctrl+C", "Ctrl+V", "ContextMenu"}
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
                .replace("Enter", Language.t("回车"))
                .replace("ContextMenu", Language.t("右键"));
        Button shortcut =
            ui.button(
                label,
                false,
                () ->
                    run(
                        () -> {
                          LinkService service = link();
                          if (code.equals("ContextMenu")) {
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
            Language.t("键盘"),
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
            Language.t("功能键"),
            false,
            () -> {
              ime.hideSoftInputFromWindow(keyboard.getWindowToken(), 0);
              keyboardShown[0] = false;
              shortcuts.setVisibility(
                  shortcuts.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            }),
        new LinearLayout.LayoutParams(0, -2, 1));
    footer.addView(
        ui.button(Language.t("粘贴"), false, () -> run(() -> link().pasteRemote())),
        new LinearLayout.LayoutParams(0, -2, 1));
    footer.addView(
        ui.button(Language.t("全屏"), false, () -> { keyboardShown[0] = false; fullscreen[0].enter(); }),
        new LinearLayout.LayoutParams(0, -2, 1));
    for (int i = 0; i < footer.getChildCount(); i++) {
      LinearLayout.LayoutParams spacing =
          (LinearLayout.LayoutParams) footer.getChildAt(i).getLayoutParams();
      spacing.setMargins(ui.dp(3), 0, ui.dp(3), 0);
    }
    box.addView(footer);
    JSONObject remoteCapabilities = p.info.optJSONObject("capabilities");
    fullscreen[0] = new RemoteFullscreen(this, session, outer, box, videoStage, videoBox,
        keyboard, touchpad, remoteCapabilities != null && remoteCapabilities.optBoolean("relativeInput"),
        event -> {
          LinkService service = link();
          try { service.remoteInput(p.id, event); }
          catch (Exception error) {
            // A failed button release must also end the lease, even under temporary queue pressure.
            if ("button".equals(event.optString("kind")) && !event.optBoolean("down")
                && p.id.equals(service.active)) service.releaseControl();
            throw error;
          }
        },
        error -> main.post(() -> {
          if (viewer == session) {
            frameStatus.setText(displayError(error.getMessage()));
            control.setText(Language.t("重新请求"));
          }
        }),
        () -> applyInsets(outer, session.getWindow()));
    frameStatus.addTextChangedListener(new android.text.TextWatcher() {
      public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
      public void onTextChanged(CharSequence s,int start,int before,int count) { fullscreen[0].connectionStatus(s); }
      public void afterTextChanged(android.text.Editable text) {}
    });
    fullscreen[0].connectionStatus(frameStatus.getText());
    viewer.setContentView(outer);
    viewer.setOnDismissListener(
        d -> {
          fullscreen[0].close();
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
    player.requireControl = true;
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
                        toast(Language.t("对方已结束控制"));
                      }
                    });
                return;
              }
              if (autoControl.get() && resumed && !p.id.equals(link().active)) {
                try {
                  link().control(p.id, freshRequest.get());
                  freshRequest.set(false);
                  if (viewer != session || !resumed) {
                    link().releaseControl();
                    return;
                  }
                  main.post(() -> control.setText(Language.t("已控制")));
                } catch (Exception denied) {
                  String reason = denied.getMessage() == null ? Language.t("控制连接失败") : denied.getMessage();
                  if (reason.startsWith("CONSENT_") || reason.startsWith("PERMISSION_"))
                    freshRequest.set(false);
                  if (reason.startsWith("CONSENT_DENIED:")
                      || reason.startsWith("CONTROL_ENDED:")
                      || reason.startsWith("CONTROL_CONFLICT:") && !reason.contains("上次控制请求尚未结束"))
                    autoControl.set(false);
                  main.post(
                      () -> {
                        if (viewer == session) {
                          control.setText(
                              autoControl.get()
                                  ? (reason.startsWith("CONSENT_")
                                          || reason.startsWith("PERMISSION_")
                                      ? Language.t("等待授权")
                                      : Language.t("重新连接"))
                                  : Language.t("重新请求"));
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
                          e.getMessage() == null ? Language.t("画面暂不可用") : displayError(e.getMessage()));
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
    if (bitmap == null) throw new Exception(Language.t("未取得二维码图片"));
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
      throw new Exception(Language.t("不是 LanLink 配对二维码"));
    android.net.Uri uri = android.net.Uri.parse(value);
    String pairing = uri.getQueryParameter("code"), id = uri.getQueryParameter("id");
    if (pairing == null
        || !pairing.matches("[0-9]{8}")
        || id == null
        || !id.matches("[a-f0-9]{32}")) throw new Exception(Language.t("请在新版设备中重新显示二维码"));
    main.post(() -> showPairingDialog(pairing, id));
  }

  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if(request==42||request==43){String id=savingStage;savingStage=null;if(result==RESULT_OK&&data!=null&&data.getData()!=null&&id!=null)run(()->{if(request==42)link().staging.saveFile(id,data.getData());else link().staging.saveFolder(id,data.getData());main.post(()->{toast(Language.t("已保存，文件已移出暂存区"));if(page.equals("center"))refreshCenter();});});return;}
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
    if (LinkService.instance != null && LinkService.instance.backgroundRecovery != null)
      LinkService.instance.backgroundRecovery.foreground(false);
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
