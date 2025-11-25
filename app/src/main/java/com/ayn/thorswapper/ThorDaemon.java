package com.ayn.thorswapper;

import android.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ThorDaemon {
    private static final String TAG = "ThorDaemon";
    private static final String CONFIG_FILE_PATH = "/data/local/tmp/thorswapper.conf";

    // СТАБИЛЬНЫЕ ПУТИ
    private static final String DEVICE_TOP = "/dev/input/event6";
    private static final String DEVICE_BOTTOM = "/dev/input/event5";
    private static final String DEVICE_KEYS = "/dev/input/event9";

    private static int THRESHOLD_TOP = 300;
    private static int THRESHOLD_BOTTOM = 300;
    private static boolean HOME_LONG_PRESS_ENABLED = true;

    private static final int FINGER_COUNT = 3;
    private static final int DISPLAY_TOP = 0;
    private static final int DISPLAY_BOTTOM = 4;
    private static final long MOVE_COOLDOWN_MS = 1000;
    private static final long HOME_LONG_PRESS_MS = 500;

    private static final short EV_KEY = 0x01;
    private static final short EV_ABS = 0x03;
    private static final short KEY_HOME = 102;
    private static final short ABS_MT_POSITION_Y = 0x36;
    private static final short ABS_MT_TRACKING_ID = 0x39;

    private static volatile boolean isRunning = true;
    private static long lastMoveTime = 0;
    private static long homeDownTime = 0;

    private static final ExecutorService executor = Executors.newFixedThreadPool(4);

    public static void main(String[] args) {
        Thread.currentThread().setName("thordaemon_main");
        loadConfig();
        Log.i(TAG, "Daemon started. Config: TOP=" + THRESHOLD_TOP + ", BOTTOM=" + THRESHOLD_BOTTOM);

        executeShellCommand("pkill -f thordaemon_main");

        executor.execute(() -> touchMonitorLoop(DEVICE_TOP, DISPLAY_TOP, THRESHOLD_TOP));
        executor.execute(() -> touchMonitorLoop(DEVICE_BOTTOM, DISPLAY_BOTTOM, THRESHOLD_BOTTOM));
        if (HOME_LONG_PRESS_ENABLED) {
            executor.execute(ThorDaemon::keyMonitorLoop);
        }

        while (isRunning) {
            try { Thread.sleep(60000); } catch (InterruptedException e) { break; }
        }
    }

    private static void loadConfig() {
        File configFile = new File(CONFIG_FILE_PATH);
        if (!configFile.exists() || !configFile.canRead()) return;
        try (BufferedReader reader = new BufferedReader(new FileReader(configFile))) {
            Properties props = new Properties();
            props.load(reader);
            THRESHOLD_TOP = Integer.parseInt(props.getProperty("threshold_top", "300"));
            THRESHOLD_BOTTOM = Integer.parseInt(props.getProperty("threshold_bottom", "300"));
            HOME_LONG_PRESS_ENABLED = Boolean.parseBoolean(props.getProperty("home_long_press", "true"));
        } catch (Exception e) { }
    }

    private static void touchMonitorLoop(String devicePath, int sourceDisplayId, int threshold) {
        try (FileInputStream fis = new FileInputStream(devicePath)) {
            byte[] buffer = new byte[24];
            int activeFingers = 0;
            int startY = -1;
            boolean gestureTriggered = false;

            while (isRunning && fis.read(buffer) != -1) {
                short type = (short) ((buffer[16] & 0xFF) | (buffer[17] << 8));
                short code = (short) ((buffer[18] & 0xFF) | (buffer[19] << 8));
                int value = (buffer[20] & 0xFF) | ((buffer[21] & 0xFF) << 8) | ((buffer[22] & 0xFF) << 16) | ((buffer[23] & 0xFF) << 24);

                if (type == EV_ABS && code == ABS_MT_TRACKING_ID) {
                    if (value == -1) {
                        if (activeFingers > 0) activeFingers--;
                        if (activeFingers < FINGER_COUNT) startY = -1;
                    } else {
                        activeFingers++;
                        if (activeFingers >= FINGER_COUNT) gestureTriggered = false;
                    }
                }

                if (type == EV_ABS && code == ABS_MT_POSITION_Y && activeFingers >= FINGER_COUNT && !gestureTriggered) {
                    if (startY == -1) {
                        startY = value;
                    } else {
                        int delta = value - startY;
                        boolean isValidGesture = false;

                        if (sourceDisplayId == DISPLAY_TOP) {
                            if (delta > threshold) isValidGesture = true;
                        } else if (sourceDisplayId == DISPLAY_BOTTOM) {
                            if (delta < -threshold) isValidGesture = true;
                        }

                        if (isValidGesture) {
                            long now = System.currentTimeMillis();
                            if (now - lastMoveTime > MOVE_COOLDOWN_MS) {
                                int targetDisplay = (sourceDisplayId == DISPLAY_TOP) ? DISPLAY_BOTTOM : DISPLAY_TOP;
                                lastMoveTime = now;
                                executor.execute(() -> performMoveTask(targetDisplay));
                            }
                            gestureTriggered = true;
                        }
                    }
                }
            }
            fis.close();
        } catch (IOException e) { }
    }

    private static void keyMonitorLoop() {
        try {
            File deviceFile = new File(DEVICE_KEYS);
            if (!deviceFile.canRead()) return;
            FileInputStream fis = new FileInputStream(deviceFile);
            byte[] buffer = new byte[24];
            while (isRunning && fis.read(buffer) != -1) {
                short type = (short) ((buffer[16] & 0xFF) | (buffer[17] << 8));
                short code = (short) ((buffer[18] & 0xFF) | (buffer[19] << 8));
                int value = (buffer[20] & 0xFF) | ((buffer[21] & 0xFF) << 8) | ((buffer[22] & 0xFF) << 16) | ((buffer[23] & 0xFF) << 24);
                if (type == EV_KEY && code == KEY_HOME) {
                    if (value == 1) homeDownTime = System.currentTimeMillis();
                    else if (value == 0 && homeDownTime != 0 && (System.currentTimeMillis() - homeDownTime >= HOME_LONG_PRESS_MS)) {
                        executeShellCommand("input keyevent KEYCODE_APP_SWITCH");
                        homeDownTime = 0;
                    }
                }
            }
            fis.close();
        } catch (IOException e) { }
    }

    private static void performMoveTask(int targetDisplayId) {

        TaskInfo info = getBestAvailableTaskInfo();

        if (info == null) {
            return;
        }

        if (info.componentName.contains("launcher") || info.componentName.contains("systemui") || info.componentName.contains("thorswapper")) {
            return;
        }

        Log.i(TAG, "Processing Task: " + info.taskId + " | " + info.componentName + " -> " + targetDisplayId);


        if (info.taskId != -1) {
            executeShellCommand("am task move-to-display " + info.taskId + " " + targetDisplayId);
        }


        String flags = "-f 0x10200000";
        String cmd = "am start -n " + info.componentName + " --display " + targetDisplayId + " " + flags + " --windowingMode 1";

        executeShellCommand(cmd);
    }

    private static class TaskInfo {
        int taskId = -1;
        String componentName = "";
    }


    private static TaskInfo getBestAvailableTaskInfo() {
        // 1. Сначала Activity Manager (игры)
        TaskInfo info = parseDump("dumpsys activity activities | grep mResumedActivity | tail -n 1");


        if (info == null) {
            info = parseDump("dumpsys window displays | grep -E 'mCurrentFocus|mFocusedApp' | tail -n 1");
        }

        return info;
    }


    private static TaskInfo parseDump(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = reader.readLine();

            if (line != null && line.contains("/")) {
                TaskInfo info = new TaskInfo();


                int slashIndex = line.indexOf('/');
                int start = line.lastIndexOf(' ', slashIndex) + 1;
                int end = line.indexOf(' ', slashIndex);
                if (end == -1) end = line.indexOf('}', slashIndex);


                if (end != -1) {
                    int braceIndex = line.indexOf('}', slashIndex);
                    if (braceIndex != -1 && braceIndex < end) {
                        end = braceIndex;
                    }
                }

                if (start != -1 && end != -1) {
                    info.componentName = line.substring(start, end).trim();

                    info.componentName = info.componentName.replace("}", "");
                }


                int tIndex = line.lastIndexOf(" t");
                if (tIndex != -1) {
                    String idPart = line.substring(tIndex + 2).trim();
                    if (idPart.contains("}")) idPart = idPart.substring(0, idPart.indexOf("}"));
                    try {
                        info.taskId = Integer.parseInt(idPart);
                    } catch (NumberFormatException e) {}
                }

                if (info.componentName != null && !info.componentName.isEmpty()) {
                    return info;
                }
            }
        } catch (Exception e) { }
        return null;
    }

    private static void executeShellCommand(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            p.waitFor();
        } catch (Exception e) { }
    }
}