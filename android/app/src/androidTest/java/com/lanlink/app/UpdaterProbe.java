package com.lanlink.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.os.Bundle;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.security.MessageDigest;

/** Run only on the disposable LanLink test emulator. */
final class UpdaterProbe {
  static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
  static AppUpdater create(Context app) throws Exception {
    Constructor<AppUpdater> ctor=AppUpdater.class.getDeclaredConstructor(Context.class);ctor.setAccessible(true);return ctor.newInstance(app);
  }
  static void result(AppUpdater updater,int session,int status) throws Exception {
    Method method=AppUpdater.class.getDeclaredMethod("installResult",Intent.class);method.setAccessible(true);
    method.invoke(updater,new Intent().putExtra(PackageInstaller.EXTRA_SESSION_ID,session).putExtra(PackageInstaller.EXTRA_STATUS,status));
  }
  static void finish(Instrumentation probe,int code,String message) { Bundle report=new Bundle();report.putString("stream","\n"+message+"\n");probe.finish(code,report); }
  static void run(Instrumentation probe) {
    try {
      Context app=probe.getTargetContext();SharedPreferences saved=app.getSharedPreferences("updates",0);
      long current=app.getPackageManager().getPackageInfo(app.getPackageName(),0).getLongVersionCode();
      File folder=new File(app.getFilesDir(),"updates"),apk=new File(folder,"lanlink-update.apk");folder.mkdirs();
      Files.write(apk.toPath(),new byte[]{1,2,3});
      saved.edit().clear().putLong("versionCode",current+1).putLong("size",3).putString("sha256","bad").commit();
      final AppUpdater[] selected=new AppUpdater[1];probe.runOnMainSync(()->{try{selected[0]=create(app);}catch(Exception e){throw new RuntimeException(e);}});
      AppUpdater updater=selected[0];check(updater.phase()==AppUpdater.Phase.READY,"saved download recovers after process recreation");
      for(int status:new int[]{PackageInstaller.STATUS_FAILURE_ABORTED,PackageInstaller.STATUS_FAILURE_STORAGE,PackageInstaller.STATUS_FAILURE_BLOCKED,PackageInstaller.STATUS_FAILURE_CONFLICT}) {
        saved.edit().putInt("session",12345).commit();
        probe.runOnMainSync(()->{try{result(updater,12345,status);}catch(Exception e){throw new RuntimeException(e);}});
        check(updater.phase()==AppUpdater.Phase.READY&&updater.canInstall(),"installer failure remains retryable: "+status);
        check(apk.isFile(),"installer failure preserves the downloaded APK");
      }
      saved.edit().putInt("session",12345).commit();
      probe.runOnMainSync(()->{try{result(updater,54321,PackageInstaller.STATUS_SUCCESS);}catch(Exception e){throw new RuntimeException(e);}});
      check(apk.isFile()&&saved.getInt("session",-1)==12345,"late result from another session is ignored");
      Method submit=AppUpdater.class.getDeclaredMethod("submitInstaller");submit.setAccessible(true);submit.invoke(updater);probe.waitForIdleSync();
      check(updater.phase()==AppUpdater.Phase.READY&&apk.isFile(),"invalid saved APK is rejected before installing and remains recoverable");
      saved.edit().putLong("versionCode",current).commit();
      probe.runOnMainSync(()->{try{selected[0]=create(app);}catch(Exception e){throw new RuntimeException(e);}});
      check(!apk.exists()&&selected[0].phase()==AppUpdater.Phase.IDLE,"completed install clears stale download state");
      finish(probe,Activity.RESULT_OK,"PASS: Android updater restart recovery, cancellation, storage/blocked/conflict failures, stale session isolation, corrupt APK rejection, and completed-install cleanup");
    } catch(Throwable error) { finish(probe,Activity.RESULT_CANCELED,"FAIL: "+android.util.Log.getStackTraceString(error)); }
  }
}
