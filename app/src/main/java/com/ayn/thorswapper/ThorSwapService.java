package com.ayn.thorswapper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service; // <--- ТЕПЕРЬ ОБЫЧНЫЙ СЕРВИС
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.IBinder; // <--- НУЖЕН ДЛЯ ОБЫЧНОГО СЕРВИСА
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ThorSwapService extends Service {

    private static final int NOTIFICATION_ID = 1337;
    private static final String CHANNEL_ID = "ThorSwapServiceChannel";
    private static final String TAG = "ThorSwap";
    private static final String DAEMON_SEARCH_STRING = "com.ayn.thorswapper.ThorDaemon";

    public static final String ACTION_START_DAEMON = "com.ayn.thorswapper.action.START_DAEMON";
    public static final String ACTION_STOP_DAEMON = "com.ayn.thorswapper.action.STOP_DAEMON";

    public static final String PREFS_NAME = "ThorPrefs";
    public static final String PREF_DAEMON_RUNNING = "daemon_running_state";

    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean isDaemonActive = false;


    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundService();


        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean autoStart = prefs.getBoolean("auto_start_boot", false);

        if (autoStart) {
            executeStartDaemon();
        } else {

            executeStopDaemon();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executeStopDaemon();
        executor.shutdown();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            if (ACTION_START_DAEMON.equals(action)) {
                executeStartDaemon();
            } else if (ACTION_STOP_DAEMON.equals(action)) {
                executeStopDaemon();
                stopForeground(true);
                stopSelf();
            }
        }
        return START_STICKY;
    }

    private void executeStartDaemon() {
        executor.execute(this::startDaemonCommand);
    }

    private void executeStopDaemon() {
        executor.execute(this::stopDaemonCommand);
    }

    private void startDaemonCommand() {
        killAllDaemons();

        try {
            ApplicationInfo appInfo = getPackageManager().getApplicationInfo(getPackageName(), 0);
            String apkPath = appInfo.sourceDir;

            String cmd = "su -c \"export CLASSPATH=" + apkPath + "; " +
                    "nohup app_process /system/bin com.ayn.thorswapper.ThorDaemon " +
                    ">/dev/null 2>&1 &\"";

            executeShellCommand(cmd);

            Thread.sleep(500);
            setDaemonState(true);
            Log.i(TAG, "Daemon started successfully.");

        } catch (Exception e) {
            Log.e(TAG, "Failed to start daemon", e);
            setDaemonState(false);
        }
    }

    private void stopDaemonCommand() {
        Log.i(TAG, "Stopping daemon...");
        killAllDaemons();
        setDaemonState(false);
    }

    private void killAllDaemons() {
        executeShellCommand("su -c \"pkill -f " + DAEMON_SEARCH_STRING + "\"");
        try { Thread.sleep(100); } catch (InterruptedException e) {}
        executeShellCommand("su -c \"pkill -9 -f " + DAEMON_SEARCH_STRING + "\"");
    }

    private void executeShellCommand(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            p.waitFor();
        } catch (Exception e) {
            Log.e(TAG, "Shell command failed: " + cmd, e);
        }
    }

    private void setDaemonState(boolean isRunning) {
        isDaemonActive = isRunning;
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(PREF_DAEMON_RUNNING, isRunning).apply();
        updateNotificationContent(isRunning ? "Active: Gestures enabled" : "Stopped");
    }

    private void startForegroundService() {
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("Service started."));
    }

    private void updateNotificationContent(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    private Notification buildNotification(String contentText) {
        int pendingIntentFlags = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                : PendingIntent.FLAG_UPDATE_CURRENT;
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Thor Swapper")
                .setContentText(contentText)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID, "Thor Swapper Service", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(serviceChannel);
        }
    }
}