package com.lanlink.app;

public final class PermissionNoticeProbe {
  static void check(boolean value, String description) {
    if (!value) throw new AssertionError(description);
  }

  public static void main(String[] args) {
    PermissionNotice notices = new PermissionNotice();
    check(notices.offer("a", "电脑 A", "input", 1000), "first request is shown");
    PermissionNotice.Request first = notices.peek(1000);
    check(!notices.offer("a", "电脑 A", "input", 1100), "rapid retries are suppressed");
    check(notices.peek(1100) == first, "retries do not rebuild the prompt");
    check(notices.offer("a", "电脑 A", "screen", 1200), "additional permission is surfaced");
    check(notices.peek(1200).needs.size() == 2, "same peer requests are combined");
    check(notices.offer("b", "电脑 B", "clipboard", 1300), "another peer has its own request");
    check(
        notices.peek(1300).needs.size() == 1 && notices.peek(1300).peer.equals("b"),
        "permissions never leak across peers");
    notices.removePeer("a");
    check(notices.peek(1300) != null, "unrelated disconnect does not dismiss");
    notices.removePeer("b");
    check(notices.peek(1300) == null, "disconnect clears prompt");
    notices.offer("a", "电脑 A", "input", 32000);
    notices.dismiss();
    check(!notices.offer("a", "电脑 A", "input", 32001), "dismiss keeps cooldown");
    check(notices.offer("a", "电脑 A", "input", 62000), "later deliberate retry can be shown");
    check(notices.peek(182000) == null, "stale requests expire");
    notices.reset();
    check(notices.offer("a", "电脑 A", "input", 62001), "account reset clears cooldown");
    System.out.println("Permission notice lifecycle checks passed");
  }
}
