package com.lanlink.app;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.PowerManager;

/** Keeps the panel awake only while an already unlocked phone is being controlled. */
final class RemoteDisplay {
  private final PowerManager power;
  private final KeyguardManager keyguard;
  private final PowerManager.WakeLock keepAwake;
  private final PowerManager.WakeLock wake;

  @SuppressWarnings("deprecation")
  RemoteDisplay(Context context) {
    power = context.getSystemService(PowerManager.class);
    keyguard = context.getSystemService(KeyguardManager.class);
    keepAwake = power.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "LanLink:remote-control");
    keepAwake.setReferenceCounted(false);
    wake = power.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
        "LanLink:remote-wake");
    wake.setReferenceCounted(false);
  }

  boolean interactive() { return power.isInteractive(); }
  boolean locked() { return keyguard.isKeyguardLocked(); }

  synchronized void keepAwake(boolean enabled) {
    if (enabled && interactive() && !locked()) keepAwake.acquire(6500);
    else if (keepAwake.isHeld()) keepAwake.release();
  }

  synchronized void wake() {
    if (!interactive()) wake.acquire(8000);
  }

  synchronized void release() {
    keepAwake(false);
    if (wake.isHeld()) wake.release();
  }
}
