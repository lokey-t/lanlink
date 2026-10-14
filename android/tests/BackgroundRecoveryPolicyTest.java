package com.lanlink.app;

public final class BackgroundRecoveryPolicyTest {
  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  public static void main(String[] args) {
    for (boolean background : new boolean[] {true, false}) {
      for (boolean restricted : new boolean[] {true, false}) {
        check(BackgroundRecoveryPolicy.reason(3, background, restricted).equals("low-memory"), "memory evidence wins");
        for (int crash : new int[] {4, 5, 6})
          check(BackgroundRecoveryPolicy.reason(crash, background, restricted).equals("crash"), "crash evidence wins");
        for (int stopped : new int[] {10, 11})
          check(BackgroundRecoveryPolicy.reason(stopped, background, restricted).equals("user-stop"), "user action wins");
        for (int other : new int[] {1, 7, 8, 9, 12, 13, 14, 15, 16}) {
          check(BackgroundRecoveryPolicy.reason(other, background, restricted).equals("unknown"), "no invented battery cause");
          check(!BackgroundRecoveryPolicy.offerGuide("unknown", other, background, true, false), "do not nudge for known unrelated exits");
        }
      }
    }
    for (int uncertain : new int[] {-1, 0, 2}) {
      check(BackgroundRecoveryPolicy.reason(uncertain, true, true).equals("restricted"), "restricted snapshot is evidence");
      check(BackgroundRecoveryPolicy.reason(uncertain, true, false).equals("unknown"), "ordinary battery optimization proves nothing");
      check(BackgroundRecoveryPolicy.reason(uncertain, false, true).equals("unknown"), "foreground termination is not background restriction");
      check(BackgroundRecoveryPolicy.offerGuide("unknown", uncertain, true, true, false), "uncertain interruption can offer qualified guidance");
      check(!BackgroundRecoveryPolicy.offerGuide("unknown", uncertain, false, true, false), "no foreground nudge");
      check(!BackgroundRecoveryPolicy.offerGuide("unknown", uncertain, true, false, false), "no unrelated disconnected session nudge");
      check(!BackgroundRecoveryPolicy.offerGuide("unknown", uncertain, true, true, true), "reboot is not a background kill");
    }
    for (String confirmed : new String[] {"crash", "user-stop", "low-memory"})
      check(!BackgroundRecoveryPolicy.offerGuide(confirmed, 0, true, true, false), "no battery nudge for known causes");
    check(BackgroundRecoveryPolicy.manufacturer("XIAOMI", "POCO").equals("xiaomi"), "Xiaomi variants");
    check(BackgroundRecoveryPolicy.manufacturer("HONOR", "honor").equals("huawei"), "Honor variant");
    check(BackgroundRecoveryPolicy.manufacturer("OnePlus", null).equals("oppo"), "OnePlus variant");
    check(BackgroundRecoveryPolicy.manufacturer(null, "realme").equals("oppo"), "realme variant");
    check(BackgroundRecoveryPolicy.manufacturer("vivo", "iQOO").equals("vivo"), "vivo variant");
    check(BackgroundRecoveryPolicy.manufacturer("SAMSUNG", "samsung").equals("samsung"), "Samsung");
    check(BackgroundRecoveryPolicy.manufacturer(null, null).equals("other"), "missing maker fallback");
    check(BackgroundRecoveryPolicy.manufacturer("Google", "google").equals("other"), "stock Android fallback");
    System.out.println("PASS: background exit evidence, known-cause precedence, reboot/foreground suppression and OEM fallback");
  }
}
