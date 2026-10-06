package com.lanlink.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;

/** Small native design system shared by the phone's pages and sheets. */
final class Ui {
  static final int BG = 0xfff7f8fb,
      INK = 0xff202637,
      MUTED = 0xff788195,
      PURPLE = 0xff7163c8,
      LILAC = 0xffeeebfc,
      MINT = 0xffe3f3ed,
      GREEN = 0xff368769,
      LINE = 0xffedf0f5,
      RED = 0xffc54d60;
  final Context context;

  Ui(Context context) {
    this.context = context;
  }

  int dp(float n) {
    return Math.round(n * context.getResources().getDisplayMetrics().density);
  }

  GradientDrawable shape(int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    return d;
  }

  android.graphics.drawable.Drawable touch(int color, int radius) {
    return new RippleDrawable(
        ColorStateList.valueOf(0x227163c8), shape(color, radius), shape(-1, radius));
  }

  LinearLayout column() {
    LinearLayout v = new LinearLayout(context);
    v.setOrientation(LinearLayout.VERTICAL);
    return v;
  }

  LinearLayout row() {
    LinearLayout v = new LinearLayout(context);
    v.setGravity(Gravity.CENTER_VERTICAL);
    return v;
  }

  TextView text(String value, int size, int color) {
    TextView t = new TextView(context);
    t.setText(value);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setFontFeatureSettings("kern");
    t.setIncludeFontPadding(false);
    t.setLineSpacing(dp(3), 1);
    return t;
  }

  TextView title(String value, int size) {
    TextView t = text(value, size, INK);
    t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    return t;
  }

  LinearLayout card() {
    LinearLayout c = column();
    c.setPadding(dp(20), dp(20), dp(20), dp(20));
    c.setBackground(shape(Color.WHITE, 24));
    return c;
  }

  void gap(LinearLayout parent, int height) {
    parent.addView(new View(context), new LinearLayout.LayoutParams(1, dp(height)));
  }

  void line(LinearLayout parent) {
    View v = new View(context);
    v.setBackgroundColor(LINE);
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(1));
    p.setMargins(0, dp(12), 0, dp(12));
    parent.addView(v, p);
  }

  Button button(String label, boolean primary, Runnable action) {
    Button b = new Button(context);
    b.setText(label);
    b.setAllCaps(false);
    b.setTextSize(15);
    b.setTextColor(primary ? Color.WHITE : PURPLE);
    b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    b.setBackgroundTintList(null);
    b.setBackground(touch(primary ? PURPLE : LILAC, 16));
    b.setMinHeight(dp(50));
    b.setMinimumHeight(dp(50));
    b.setMinWidth(0);
    b.setMinimumWidth(0);
    b.setPadding(dp(12), dp(10), dp(12), dp(10));
    b.setStateListAnimator(null);
    b.setOnClickListener(v -> action.run());
    return b;
  }

  EditText edit(String hint) {
    EditText e = new EditText(context);
    e.setTextSize(16);
    e.setTextColor(INK);
    e.setHintTextColor(MUTED);
    e.setHint(hint);
    e.setSingleLine(true);
    e.setPadding(dp(16), dp(14), dp(16), dp(14));
    e.setBackground(shape(BG, 14));
    e.setMinHeight(dp(52));
    return e;
  }

  LinearLayout item(String symbol, String label, String detail, String trailing, Runnable action) {
    LinearLayout row = row();
    row.setMinimumHeight(dp(66));
    row.setPadding(dp(4), dp(10), dp(4), dp(10));
    row.addView(icon(symbol, INK), new LinearLayout.LayoutParams(dp(24), dp(24)));
    LinearLayout labels = column();
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
    lp.setMargins(dp(14), 0, dp(8), 0);
    row.addView(labels, lp);
    labels.addView(title(label, 15));
    if (detail != null && !detail.isEmpty()) {
      gap(labels, 5);
      labels.addView(text(detail, 12, MUTED));
    }
    if (trailing != null) row.addView(text(trailing, 12, MUTED));
    if (action != null) {
      row.addView(icon("chevron", MUTED), new LinearLayout.LayoutParams(dp(24), dp(24)));
      row.setBackground(touch(Color.TRANSPARENT, 14));
      row.setOnClickListener(v -> action.run());
    }
    return row;
  }

  View icon(String name, int color) {
    return new Glyph(context, name, color);
  }

  View iconButton(String glyph, String description, Runnable action) {
    FrameLayout frame = new FrameLayout(context);
    frame.setContentDescription(description);
    frame.setBackground(touch(LILAC, 24));
    View image = icon(glyph, PURPLE);
    frame.addView(image, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
    frame.setOnClickListener(v -> action.run());
    return frame;
  }

  View clipboardShortcuts(Runnable upload, Runnable download) {
    LinearLayout group=row();group.setBackground(shape(LILAC,16));group.setClipToOutline(true);
    group.setContentDescription("快捷同步剪贴板");
    for(int i=0;i<2;i++) {
      if(i==1){View divider=new View(context);divider.setBackgroundColor(0xffd8d1ed);group.addView(divider,new LinearLayout.LayoutParams(dp(1),dp(24)));}
      FrameLayout button=new FrameLayout(context);button.setContentDescription(i==0?"上传剪贴板":"下载剪贴板");button.setBackground(touch(Color.TRANSPARENT,0));
      button.addView(icon(i==0?"clipboard-upload":"clipboard-download",PURPLE),new FrameLayout.LayoutParams(dp(24),dp(24),Gravity.CENTER));
      Runnable action=i==0?upload:download;button.setOnClickListener(v->action.run());group.addView(button,new LinearLayout.LayoutParams(dp(48),dp(48)));
    }
    return group;
  }

  static final class Glyph extends View {
    final String name;
    final int color;
    final Paint p = new Paint(3);

    Glyph(Context context, String name, int color) {
      super(context);
      this.name = name;
      this.color = color;
      setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void line(Canvas c, float x, float y, float a, float b) {
      c.drawLine(x, y, a, b, p);
    }

    void rect(Canvas c, float x, float y, float a, float b, float r) {
      c.drawRoundRect(x, y, a, b, r, r, p);
    }

    protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      Canvas c = canvas;
      c.save();
      float s = Math.min(getWidth(), getHeight()) / 24f;
      c.translate((getWidth() - 24 * s) / 2, (getHeight() - 24 * s) / 2);
      c.scale(s, s);
      p.setColor(color);
      p.setStrokeWidth(1.6f);
      p.setStrokeCap(Paint.Cap.ROUND);
      p.setStrokeJoin(Paint.Join.ROUND);
      p.setStyle(Paint.Style.STROKE);
      switch (name) {
        case "phone":
          rect(c, 6, 2, 18, 22, 2.4f);
          line(c, 10, 19, 14, 19);
          break;
        case "laptop":
          rect(c, 4, 4, 20, 17, 1.5f);
          line(c, 2, 20, 22, 20);
          line(c, 4, 17, 2, 20);
          line(c, 20, 17, 22, 20);
          break;
        case "monitor":
          rect(c, 2, 3, 22, 17, 1.8f);
          line(c, 12, 17, 12, 21);
          line(c, 7, 21, 17, 21);
          break;
        case "center":
          rect(c,2,3,22,10,2);rect(c,2,14,11,22,2);rect(c,15,14,22,22,2);line(c,6,6.5f,18,6.5f);break;
        case "files":
          Path folder=new Path();folder.moveTo(2,7);folder.lineTo(2,5);folder.quadTo(2,3,4,3);folder.lineTo(10,3);folder.lineTo(13,6);folder.lineTo(20,6);folder.quadTo(22,6,22,8);folder.lineTo(22,19);folder.quadTo(22,21,20,21);folder.lineTo(4,21);folder.quadTo(2,21,2,19);folder.close();c.drawPath(folder,p);line(c,7,12,17,12);line(c,7,16,14,16);break;
        case "upload":
          line(c,4,16,4,21);line(c,4,21,20,21);line(c,20,21,20,16);line(c,12,16,12,3);line(c,7,8,12,3);line(c,12,3,17,8);break;
        case "download":
          line(c,4,16,4,21);line(c,4,21,20,21);line(c,20,21,20,16);line(c,12,3,12,16);line(c,7,11,12,16);line(c,12,16,17,11);break;
        case "clipboard-upload":
        case "clipboard-download":
          Path board=new Path();board.moveTo(8,5);board.lineTo(5,5);board.quadTo(3,5,3,7);board.lineTo(3,20);board.quadTo(3,22,5,22);board.lineTo(14,22);board.quadTo(16,22,16,20);board.lineTo(16,18);c.drawPath(board,p);
          Path clip=new Path();clip.moveTo(7,2);clip.lineTo(12,2);clip.quadTo(13,2,13,3);clip.lineTo(13,6);clip.lineTo(6,6);clip.lineTo(6,3);clip.quadTo(6,2,7,2);c.drawPath(clip,p);
          line(c,6,11,11,11);line(c,6,15,10,15);line(c,19,4,19,15);boolean up=name.equals("clipboard-upload");line(c,15,up?8:11,19,up?4:15);line(c,19,up?4:15,23,up?8:11);break;
        case "plus":
          line(c, 12, 5, 12, 19);
          line(c, 5, 12, 19, 12);
          break;
        case "back":
          line(c, 19, 12, 5, 12);
          line(c, 5, 12, 11, 6);
          line(c, 5, 12, 11, 18);
          break;
        case "chevron":
          line(c, 10, 7, 15, 12);
          line(c, 15, 12, 10, 17);
          break;
        case "close":
          line(c, 6, 6, 18, 18);
          line(c, 18, 6, 6, 18);
          break;
        case "settings":
          for (int i = 0; i < 3; i++) {
            int y = 5 + i * 7;
            line(c, 3, y, 21, y);
            p.setStyle(Paint.Style.FILL);
            p.setColor(BG);
            c.drawCircle(i == 1 ? 15 : 8, y, 2.6f, p);
            p.setColor(color);
            p.setStyle(Paint.Style.STROKE);
            c.drawCircle(i == 1 ? 15 : 8, y, 2.6f, p);
          }
          break;
        case "clipboard":
          rect(c, 5, 4, 19, 22, 2);
          p.setStyle(Paint.Style.FILL);
          p.setColor(Color.WHITE);
          rect(c, 9, 2, 15, 6, 1);
          p.setStyle(Paint.Style.STROKE);
          p.setColor(color);
          rect(c, 9, 2, 15, 6, 1);
          line(c, 8, 11, 16, 11);
          line(c, 8, 15, 14, 15);
          break;
        case "keyboard":
          rect(c, 2, 5, 22, 19, 2);
          for (int y = 9; y <= 12; y += 3)
            for (int x = 6; x <= 18; x += 4) line(c, x, y, x + .3f, y);
          line(c, 7, 16, 17, 16);
          break;
        case "eye":
          Path eye = new Path();
          eye.moveTo(2, 12);
          eye.cubicTo(7, 3, 17, 3, 22, 12);
          eye.cubicTo(17, 21, 7, 21, 2, 12);
          c.drawPath(eye, p);
          c.drawCircle(12, 12, 3, p);
          break;
        case "access":
          c.drawCircle(12, 4, 2, p);
          line(c, 3, 9, 21, 9);
          line(c, 12, 7, 12, 15);
          line(c, 12, 15, 7, 22);
          line(c, 12, 15, 17, 22);
          break;
        case "power":
          line(c, 12, 2, 12, 12);
          c.drawArc(3, 4, 21, 22, -50, 280, false, p);
          break;
        case "scan":
          c.drawLine(3, 8, 3, 3, p); c.drawLine(3, 3, 8, 3, p);
          c.drawLine(16, 3, 21, 3, p); c.drawLine(21, 3, 21, 8, p);
          c.drawLine(3, 16, 3, 21, p); c.drawLine(3, 21, 8, 21, p);
          c.drawLine(16, 21, 21, 21, p); c.drawLine(21, 21, 21, 16, p);
          c.drawRect(7, 7, 10, 10, p); c.drawRect(14, 7, 17, 10, p);
          c.drawRect(7, 14, 10, 17, p); c.drawLine(14, 15, 17, 15, p);
          break;
        case "camera":
          rect(c, 2, 6, 22, 21, 3);
          line(c, 7, 6, 9, 3);
          line(c, 9, 3, 15, 3);
          line(c, 15, 3, 17, 6);
          c.drawCircle(12, 13, 4, p);
          break;
        case "picture":
          rect(c, 3, 3, 21, 21, 3);
          c.drawCircle(15, 8, 2, p);
          line(c, 4, 17, 9, 11);
          line(c, 9, 11, 16, 20);
          break;
        case "check":
          line(c, 5, 12, 10, 17);
          line(c, 10, 17, 20, 6);
          break;
        case "more":
          p.setStyle(Paint.Style.FILL);
          for (int x = 5; x <= 19; x += 7) c.drawCircle(x, 12, 1.6f, p);
          break;
        default:
          c.drawCircle(9, 12, 6, p);
          c.drawCircle(16, 12, 6, p);
          break;
      }
      c.restore();
    }
  }
}
