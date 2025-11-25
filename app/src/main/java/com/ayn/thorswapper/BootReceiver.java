package com.ayn.thorswapper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) ||
                "android.intent.action.QUICKBOOT_POWERON".equals(intent.getAction())) {

            Log.i("ThorBoot", "Boot completed detected.");


            SharedPreferences prefs = context.getSharedPreferences(ThorSwapService.PREFS_NAME, Context.MODE_PRIVATE);
            boolean autoStart = prefs.getBoolean("auto_start_boot", false);

            if (autoStart) {
                Log.i("ThorBoot", "Auto-start enabled. Launching service...");


                Intent serviceIntent = new Intent(context, ThorSwapService.class);
                serviceIntent.setAction(ThorSwapService.ACTION_START_DAEMON);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            } else {
                Log.i("ThorBoot", "Auto-start disabled. Doing nothing.");
            }
        }
    }
}