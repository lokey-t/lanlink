package com.lanlink.app;

import android.app.*;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;

/** Native UI checks, including resized windows, cancelled back and dock clipping. */
final class UiMotionProbe {
  static Object field(Object object, String name) throws Exception {
    Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object);
  }
  static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
  static void page(Activity activity, String page) throws Exception {
    Field f = MainActivity.class.getDeclaredField("page"); f.setAccessible(true); f.set(activity, page);
    Method render = MainActivity.class.getDeclaredMethod("renderPage"); render.setAccessible(true); render.invoke(activity);
  }
  static void capture(Instrumentation test, String name) throws Exception {
    Bitmap bitmap = test.getUiAutomation().takeScreenshot();
    File dir = new File(test.getTargetContext().getExternalFilesDir(null), "ui-motion"); dir.mkdirs();
    try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) {
      check(bitmap != null && bitmap.compress(Bitmap.CompressFormat.PNG, 100, out), "screenshot " + name);
    } finally { if (bitmap != null) bitmap.recycle(); }
  }
  static EditText findEdit(View root) {
    if (root instanceof EditText) return (EditText)root;
    if (root instanceof ViewGroup) for (int i=0;i<((ViewGroup)root).getChildCount();i++) {
      EditText found = findEdit(((ViewGroup)root).getChildAt(i)); if (found != null) return found;
    }
    return null;
  }

  static View findDescription(View view, String description) {
    if (description.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
    if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) {
      View found = findDescription(((ViewGroup)view).getChildAt(i), description); if (found != null) return found;
    }
    return null;
  }

  static void recordScrollbars(View view, java.util.Map<View, boolean[]> states) {
    states.put(view, new boolean[]{view.isVerticalScrollBarEnabled(), view.isHorizontalScrollBarEnabled()});
    if (view instanceof ViewGroup) for (int i=0;i<((ViewGroup)view).getChildCount();i++)
      recordScrollbars(((ViewGroup)view).getChildAt(i), states);
  }

  static boolean noScrollbars(View view) {
    if (view.isVerticalScrollBarEnabled() || view.isHorizontalScrollBarEnabled()) return false;
    if (view instanceof ViewGroup) for (int i=0;i<((ViewGroup)view).getChildCount();i++)
      if (!noScrollbars(((ViewGroup)view).getChildAt(i))) return false;
    return true;
  }

  static void checkGesture(Instrumentation test, Dialog dialog) throws Exception {
    checkGesture(test, dialog, null);
  }

  static void checkGesture(Instrumentation test, Dialog dialog, String previewName) throws Exception {
    final View[] surface = new View[1];
    final java.util.Map<View, boolean[]> scrollbars = new java.util.IdentityHashMap<>();
    test.runOnMainSync(() -> { try {
      check(dialog instanceof PredictiveDialog, "popup supports predictive back");
      if (android.os.Build.VERSION.SDK_INT < 34) { dialog.cancel(); return; }
      surface[0] = dialog.findViewById(android.R.id.content);
      recordScrollbars(surface[0], scrollbars);
      android.window.OnBackAnimationCallback callback = (android.window.OnBackAnimationCallback)field(dialog,"backCallback");
      callback.onBackStarted(new android.window.BackEvent(0, 100, 0, android.window.BackEvent.EDGE_LEFT));
      callback.onBackProgressed(new android.window.BackEvent(100, 100, .6f, android.window.BackEvent.EDGE_LEFT));
      check(surface[0].getScaleX() < 1, "popup follows gesture progress");
      check(surface[0].getAlpha() == 1, "popup background stays opaque");
      check(noScrollbars(surface[0]), "scrollbars hidden during predictive gesture");
    } catch (Exception e) { throw new RuntimeException(e); } });
    if (previewName != null) {
      SystemClock.sleep(120); test.waitForIdleSync(); capture(test, previewName);
    }
    test.runOnMainSync(() -> { try {
      if (android.os.Build.VERSION.SDK_INT >= 34)
        ((android.window.OnBackAnimationCallback)field(dialog,"backCallback")).onBackCancelled();
    } catch (Exception error) { throw new RuntimeException(error); } });
    SystemClock.sleep(250);
    test.runOnMainSync(() -> { try {
      if (android.os.Build.VERSION.SDK_INT < 34) return;
      check(dialog.isShowing() && surface[0].getScaleX() == 1 && surface[0].getAlpha() == 1,
          "cancel restores popup without closing it");
      for (java.util.Map.Entry<View, boolean[]> entry : scrollbars.entrySet())
        check(entry.getKey().isVerticalScrollBarEnabled() == entry.getValue()[0]
            && entry.getKey().isHorizontalScrollBarEnabled() == entry.getValue()[1],
            "cancel restores scrollbar settings");
      android.window.OnBackAnimationCallback callback = (android.window.OnBackAnimationCallback)field(dialog,"backCallback");
      callback.onBackStarted(new android.window.BackEvent(0, 100, 0, android.window.BackEvent.EDGE_RIGHT));
      callback.onBackProgressed(new android.window.BackEvent(100, 100, .8f, android.window.BackEvent.EDGE_RIGHT));
      callback.onBackInvoked();
      check(!dialog.isShowing(), "committed gesture closes top popup");
    } catch (Exception e) { throw new RuntimeException(e); } });
  }

  static void checkSystemBack(Instrumentation test) throws Exception {
    if (android.os.Build.VERSION.SDK_INT < 33) return;
    String dump;
    try (android.os.ParcelFileDescriptor fd = test.getUiAutomation().executeShellCommand(
        "dumpsys activity com.lanlink.app");
        InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)) {
      dump = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
    int start = dump.indexOf("WindowOnBackDispatcher:");
    check(start >= 0, "activity back dispatcher is available");
    int end = dump.indexOf("View Hierarchy:", start);
    check(end > start, "activity back dispatcher dump is complete");
    String callbacks = dump.substring(start, end);
    check(callbacks.contains("Priority=-1"), "framework owns core-tab Back for system preview");
    check(!java.util.regex.Pattern.compile("Priority=[0-9]+").matcher(callbacks).find(),
        "core tabs must not consume system Back with an application callback: " + callbacks);
  }

  static void inject(Instrumentation test, long down, int action, float x, float y) {
    MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
    event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
    try { check(test.getUiAutomation().injectInputEvent(event, true), "inject edge gesture"); }
    finally { event.recycle(); }
  }

  // Requires gesture navigation and predictive back enabled on the test emulator.
  // These events go through SystemUI and the window dispatcher, unlike checkGesture.
  static void checkDispatchedGesture(Instrumentation test, Dialog dialog, boolean commit) throws Exception {
    final View[] surface = new View[1];
    final int[] dimensions = new int[2];
    test.runOnMainSync(() -> {
      surface[0] = dialog.findViewById(android.R.id.content);
      dimensions[0] = dialog.getWindow().getDecorView().getWidth();
      dimensions[1] = dialog.getWindow().getDecorView().getHeight();
    });
    float y = dimensions[1] * .45f, distance = dimensions[0] * .4f;
    long down = SystemClock.uptimeMillis();
    inject(test, down, MotionEvent.ACTION_DOWN, 1, y);
    try {
      for (int i = 1; i <= 20; i++) {
        inject(test, down, MotionEvent.ACTION_MOVE, 1 + distance * i / 20, y);
        SystemClock.sleep(16);
      }
      SystemClock.sleep(150);
      test.runOnMainSync(() -> check(dialog.isShowing() && surface[0].getScaleX() < 1,
          "real edge swipe reaches popup progress callback"));
      if (!commit) for (int i = 19; i >= 0; i--) {
        inject(test, down, MotionEvent.ACTION_MOVE, 1 + distance * i / 20, y);
        SystemClock.sleep(16);
      }
    } finally {
      inject(test, down, MotionEvent.ACTION_UP, commit ? 1 + distance : 1, y);
    }
    SystemClock.sleep(600); test.waitForIdleSync();
    test.runOnMainSync(() -> {
      check(dialog.isShowing() != commit, "real edge swipe commits or cancels only top popup");
      if (!commit) check(surface[0].getScaleX() == 1 && surface[0].getTranslationX() == 0,
          "cancelled real edge swipe restores popup");
    });
  }

  static String run(Instrumentation test, Activity activity) throws Exception {
    for (String page : new String[]{"devices", "settings", "center", "permissions", "layout"}) {
      test.runOnMainSync(() -> { try { page(activity, page);
        if (page.equals("center")) {
          ViewGroup functions = (ViewGroup) field(activity, "centerFunctions");
          for (int i = 0; i < functions.getChildCount(); i++)
            check(functions.getChildAt(i).getTranslationY() >= 112 * activity.getResources()
                .getDisplayMetrics().density, "tile starts fully hidden");
        }
      } catch (Exception e) { throw new RuntimeException(e); } });
      SystemClock.sleep(650); test.waitForIdleSync();
      test.runOnMainSync(() -> { try {
        View root = (View) field(activity, "root"), content = (View) field(activity, "layout");
        View dock = (View) field(activity, "navigation");
        float density = activity.getResources().getDisplayMetrics().density;
        boolean landscape = (boolean)field(activity, "landscapeLayout");
        boolean tablet = (boolean)field(activity, "tabletLayout");
        boolean phoneLandscape = (boolean)field(activity,"phoneLandscape");
        if (phoneLandscape && field(activity,"popupDialog") == null) {
          check(dock.getWidth() <= 96 * density && "phone-landscape-dock".equals(dock.getTag()),
              "phone landscape uses a compact left dock");
          float dockRatio = dock.getHeight() / (float)(root.getHeight() - root.getPaddingTop() - root.getPaddingBottom());
          check(dockRatio >= .88f && dockRatio <= .92f, "landscape dock uses ninety percent height");
          check(((LinearLayout)dock).getOrientation() == LinearLayout.VERTICAL,
              "phone landscape navigation stacks vertically");
          if (page.equals("center")) check(((View)field(activity,"coreScroll")).getHeight()
              >= activity.getResources().getConfiguration().screenHeightDp * density * .4f,
              "control center reserves useful content height");
        }
        check(content.getWidth() <= (landscape ? 1320 : 960) * density + 1, "bounded page width");
        check(dock.getWidth() <= 720 * density + 1, "bounded dock width");
        check(dock.getBottom() <= root.getHeight() - root.getPaddingBottom(), "dock above system insets");
        check(content.getWidth() > 0 && content.getTranslationY() == 0 && content.getAlpha() == 1, "page animation settles");
        check(content.getHeight() > 0 && ((View)content.getParent()).getHeight() > 0, "page content remains visible: " + page);
        Dialog popup = (Dialog)field(activity,"popupDialog");
        if (popup != null) {
          check(popup.isShowing(), "overlay is showing");
          check(findDescription(popup.getWindow().getDecorView(), Language.t("设备页面")) == null,
              "overlay contains no navigation");
        }
        check(tablet == "navigation-rail".equals(dock.getTag()), "tablet sidebar and phone dock");
        if (tablet) {
          check(dock.getHeight() == root.getHeight() - root.getPaddingTop() - root.getPaddingBottom(),
              "sidebar spans available height");
          ViewGroup sidebar = (ViewGroup)dock;
          int navigationRows = 0;
          for (int i = 0; i < sidebar.getChildCount(); i++) {
            View child = sidebar.getChildAt(i);
            if (!"navigation-item".equals(child.getTag())) continue;
            navigationRows++;
            LinearLayout row = (LinearLayout)child;
            View icon = row.getChildAt(0), label = row.getChildAt(2);
            check(row.getOrientation() == LinearLayout.HORIZONTAL && label.getLeft() > icon.getRight(),
                "sidebar icon and label share horizontal row");
            check(label.getWidth() > 0 && row.getHeight() >= 48 * density, "readable sidebar touch target");
          }
          check(navigationRows == 3, "all navigation destinations present");
          check(sidebar.getChildAt(0).getTop() == dock.getPaddingTop(), "sidebar brand stays at top");
        }
        if (page.equals("devices") && phoneLandscape && root.findViewWithTag("device-workspace") != null) {
          ViewGroup detail = (ViewGroup)field(activity,"detail");
          ViewGroup card = (ViewGroup)detail.getChildAt(0);
          for (int i = 0; i < card.getChildCount(); i++) if (card.getChildAt(i) instanceof Button) {
            android.graphics.Rect visible = new android.graphics.Rect();
            check(card.getChildAt(i).getGlobalVisibleRect(visible) && visible.height() >= 48 * density,
                "primary device action visible without scrolling in landscape");
            break;
          }
        }
        if (page.equals("devices") && landscape) {
          ViewGroup workspace = root.findViewWithTag("device-workspace");
          check(workspace != null && workspace.getChildCount() == 2, "device list and detail panes");
          check(((ViewGroup)field(activity,"devices")).getWidth() >= 200 * density, "readable device sidebar");
          check("navigation-rail".equals(dock.getTag()), "landscape navigation rail");
        }
        if (page.equals("settings") && (boolean)field(activity,"twoColumnLayout")) {
          ViewGroup columns = root.findViewWithTag("settings-columns");
          check(columns != null && columns.getChildCount() == 2, "settings columns");
          check(columns.getChildAt(0).getWidth() >= 300 * density, "readable settings cards");
        }
        if (page.equals("center")) {
          ViewGroup functions = (ViewGroup) field(activity, "centerFunctions");
          check(functions.getClipChildren(), "dock emergence is clipped");
          int[] functionLocation = new int[2], dockLocation = new int[2];
          functions.getLocationOnScreen(functionLocation); dock.getLocationOnScreen(dockLocation);
          if (tablet || phoneLandscape) check(functionLocation[0] > dockLocation[0] + dock.getWidth(), "functions clear navigation rail");
          else check(functionLocation[1] + functions.getHeight() <= dockLocation[1], "functions above dock");
          for (int i = 0; i < functions.getChildCount(); i++) {
            View tile = functions.getChildAt(i);
            check(tile.getTranslationY() == 0 && tile.getHeight() > 0, "tile animation settles");
          }
        }
      } catch (Exception e) { throw new RuntimeException(e); } });
      capture(test, page);
    }
    for (String core : new String[]{"devices", "center", "settings"}) {
      test.runOnMainSync(() -> { try {
        page(activity, core);
        check(field(activity, "popupDialog") == null, "core page has no popup");
      } catch (Exception e) { throw new RuntimeException(e); } });
    }
    checkSystemBack(test);
    for (String popup : new String[]{"layout", "permissions"}) {
      final Dialog[] window = new Dialog[1];
      test.runOnMainSync(() -> { try {
        page(activity, popup.equals("layout") ? "devices" : "settings");
        page(activity, popup); window[0] = (Dialog)field(activity, "popupDialog");
        check(window[0] instanceof PredictiveDialog && window[0].isShowing(), "full-page popup window");
        check(findDescription(window[0].getWindow().getDecorView(), Language.t("设备页面")) == null,
            "popup excludes phone dock and tablet sidebar");
      } catch (Exception e) { throw new RuntimeException(e); } });
      SystemClock.sleep(350);
      if (android.os.Build.VERSION.SDK_INT >= 34
          && "2".equals(android.provider.Settings.Secure.getString(
              activity.getContentResolver(), "navigation_mode"))) {
        checkDispatchedGesture(test, window[0], false);
        checkDispatchedGesture(test, window[0], true);
      } else checkGesture(test, window[0], popup + "-preview");
      test.runOnMainSync(() -> { try {
        check((popup.equals("layout") ? "devices" : "settings").equals(field(activity,"page")),
            "popup returns to its originating core tab");
      } catch (Exception e) { throw new RuntimeException(e); } });
    }
    final Dialog[] pair = new Dialog[1], nested = new Dialog[1];
    test.runOnMainSync(() -> { try {
      page(activity,"center");
      Method method = MainActivity.class.getDeclaredMethod("showPairingDialog", String.class, String.class);
      method.setAccessible(true); method.invoke(activity, null, null);
      pair[0] = (Dialog)field(activity,"pairDialog");
      if ((boolean)field(activity,"phoneLandscape") && activity.getResources().getConfiguration().screenWidthDp
          / activity.getResources().getConfiguration().fontScale >= 700)
        check(pair[0].getWindow().getDecorView().findViewWithTag("pairing-columns") != null,
            "landscape pairing uses two panels");
      check(findDescription(pair[0].getWindow().getDecorView(), Language.t("设备页面")) == null,
          "pairing popup has no navigation");
      Method picker = MainActivity.class.getDeclaredMethod("showLanguagePicker"); picker.setAccessible(true);
      nested[0] = (Dialog)picker.invoke(activity);
    } catch (Exception e) { throw new RuntimeException(e); } });
    SystemClock.sleep(350); checkGesture(test, nested[0]);
    test.runOnMainSync(() -> { try {
      check(pair[0].isShowing() && "center".equals(field(activity,"page")), "nested popup closes only top layer");
      pair[0].setCancelable(false);
      check(field(pair[0],"backCallback") == null, "busy pairing cannot be dismissed by gesture");
      pair[0].setCancelable(true);
    } catch (Exception e) { throw new RuntimeException(e); } });
    SystemClock.sleep(250);
    capture(test, "pairing");
    if ((boolean)field(activity,"phoneLandscape")) {
      final EditText[] entry = new EditText[1];
      test.runOnMainSync(() -> {
        View manual = findDescription(pair[0].getWindow().getDecorView(), Language.t("手动输入"));
        check(manual != null && manual.performClick(), "manual pairing entry opens");
        entry[0] = findEdit(pair[0].getWindow().getDecorView());
        check(entry[0] != null, "pairing input exists"); entry[0].setText("1234");
      });
      int rotation = activity.getDisplay().getRotation();
      try {
        test.getUiAutomation().setRotation(rotation == 0 ? 1 : 0);
        SystemClock.sleep(700); test.waitForIdleSync();
        test.runOnMainSync(() -> check(pair[0].isShowing() && "1234".contentEquals(entry[0].getText()),
            "rotating with pairing open preserves entered code and popup"));
      } finally {
        test.getUiAutomation().setRotation(rotation);
        SystemClock.sleep(700); test.waitForIdleSync();
      }
      test.runOnMainSync(() -> entry[0].setText(""));
    }
    checkGesture(test, pair[0]);
    for (String methodName : new String[]{"showUsage", "showBackgroundGuide"}) {
      final Dialog[] window = new Dialog[1];
      test.runOnMainSync(() -> { try {
        java.util.Set<?> before = new java.util.HashSet<>((java.util.Set<?>)field(activity,"sheets"));
        Method method = methodName.equals("showUsage") ? MainActivity.class.getDeclaredMethod(methodName)
            : MainActivity.class.getDeclaredMethod(methodName, org.json.JSONObject.class);
        method.setAccessible(true);
        if (methodName.equals("showUsage")) method.invoke(activity); else method.invoke(activity, (Object)null);
        for (Object item : (java.util.Set<?>)field(activity,"sheets"))
          if (!before.contains(item)) window[0] = (Dialog)item;
        check(window[0] != null, "sheet opened: " + methodName);
      } catch (Exception e) { throw new RuntimeException(e); } });
      SystemClock.sleep(350); capture(test, methodName); checkGesture(test, window[0]);
    }
    final Dialog[] appearance = new Dialog[1];
    test.runOnMainSync(() -> { try {
      Method show = MainActivity.class.getDeclaredMethod("showChoicePicker", String.class,
          String[].class, int.class, java.util.function.IntConsumer.class);
      show.setAccessible(true);
      appearance[0] = (Dialog)show.invoke(activity, Language.t("外观"),
          new String[]{Language.t("跟随系统"), Language.t("深色"), Language.t("浅色")}, 0,
          (java.util.function.IntConsumer)index -> {});
    } catch (Exception error) { throw new RuntimeException(error); } });
    SystemClock.sleep(350); checkGesture(test, appearance[0], "appearance-preview");
    test.runOnMainSync(() -> { try {
      page(activity, "devices");
      ViewGroup devices = (ViewGroup)field(activity, "devices");
      // Use a known offline device when available, without making any network request.
      if (devices.getChildCount() > 1) {
        devices.getChildAt(1).performClick();
        View detail = (View)field(activity,"detail");
        boolean horizontal = activity.getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        check(horizontal ? detail.getTranslationY() > 0 && detail.getTranslationX() == 0
            : detail.getTranslationX() > 0, "next device enters along the appropriate axis");
        ((ViewGroup)field(activity,"devices")).getChildAt(0).performClick();
        check(horizontal ? detail.getTranslationY() < 0 && detail.getTranslationX() == 0
            : detail.getTranslationX() < 0, "previous device reverses along the appropriate axis");
      }
    } catch (Exception e) { throw new RuntimeException(e); } });
    final Dialog[] picker = new Dialog[1];
    test.runOnMainSync(() -> { try {
      Method method = MainActivity.class.getDeclaredMethod("showLanguagePicker"); method.setAccessible(true);
      picker[0] = (Dialog)method.invoke(activity);
    } catch (Exception e) { throw new RuntimeException(e); } });
    SystemClock.sleep(400);
    test.runOnMainSync(() -> {
      android.graphics.Rect bounds = new android.graphics.Rect();
      picker[0].getWindow().getDecorView().getWindowVisibleDisplayFrame(bounds);
      check(picker[0].getWindow().getDecorView().getHeight() <= activity.getWindow().getDecorView().getHeight(),
          "language picker fits short window");
    });
    capture(test,"language");
    checkGesture(test, picker[0]);
    return "PASS: core tabs retain system back preview; navigation-free layout, permissions and pairing popups; nested dialog progress/cancel/commit; help, background and language sheets; busy pairing guard; responsive layouts";
  }
}
