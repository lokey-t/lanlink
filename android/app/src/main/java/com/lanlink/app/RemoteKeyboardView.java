package com.lanlink.app;

import android.content.Context;
import android.text.InputType;
import android.view.*;
import android.view.inputmethod.*;

final class RemoteKeyboardView extends View {
  interface Sender {
    void text(String text);

    void key(String code, boolean down);
  }

  final Sender sender;

  RemoteKeyboardView(Context context, Sender sender) {
    super(context);
    this.sender = sender;
    setFocusable(true);
    setFocusableInTouchMode(true);
  }

  public boolean onCheckIsTextEditor() {
    return true;
  }

  public InputConnection onCreateInputConnection(EditorInfo info) {
    info.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE;
    info.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_ACTION_NONE;
    return new BaseInputConnection(this, false) {
      String composing = "";

      public boolean setComposingText(CharSequence text, int position) {
        composing = text.toString();
        return true;
      }

      public boolean commitText(CharSequence text, int position) {
        composing = "";
        if (text.length() > 0) sender.text(text.toString());
        return true;
      }

      public boolean finishComposingText() {
        if (!composing.isEmpty()) {
          sender.text(composing);
          composing = "";
        }
        return true;
      }

      public boolean deleteSurroundingText(int before, int after) {
        if (!composing.isEmpty()) {
          composing = "";
          return true;
        }
        for (int i = 0; i < Math.min(before, 100); i++) {
          sender.key("Backspace", true);
          sender.key("Backspace", false);
        }
        for (int i = 0; i < Math.min(after, 100); i++) {
          sender.key("Delete", true);
          sender.key("Delete", false);
        }
        return true;
      }

      public boolean deleteSurroundingTextInCodePoints(int before, int after) {
        return deleteSurroundingText(before, after);
      }

      public boolean sendKeyEvent(KeyEvent e) {
        String code =
            e.getKeyCode() == KeyEvent.KEYCODE_DEL
                ? "Backspace"
                : e.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    ? "Enter"
                    : e.getKeyCode() == KeyEvent.KEYCODE_TAB ? "Tab" : null;
        if (code != null) sender.key(code, e.getAction() == KeyEvent.ACTION_DOWN);
        else if (e.getAction() == KeyEvent.ACTION_UP && e.getUnicodeChar() != 0)
          sender.text(new String(Character.toChars(e.getUnicodeChar())));
        return true;
      }

      public boolean performEditorAction(int action) {
        sender.key("Enter", true);
        sender.key("Enter", false);
        return true;
      }
    };
  }
}
