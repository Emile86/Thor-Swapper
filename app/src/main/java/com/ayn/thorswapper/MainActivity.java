package com.ayn.thorswapper;

import androidx.appcompat.app.AlertDialog; // Для диалога
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.appcompat.widget.SwitchCompat;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends AppCompatActivity {

    private Button btnStart, btnStop, btnAbout;
    private TextView statusText, textThresholdTop, textThresholdBottom, textCooldown;
    private SeekBar seekBarTop, seekBarBottom, seekBarCooldown;
    private SwitchCompat switchHomeLongPress, switchAutoStart;
    private RadioGroup radioGroupFingers;

    private SharedPreferences prefs;
    private static final String CONFIG_FILE_PATH = "/data/local/tmp/thorswapper.conf";

    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener =
            (sharedPreferences, key) -> {
                if (ThorSwapService.PREF_DAEMON_RUNNING.equals(key)) {
                    runOnUiThread(this::updateDaemonStatusUI);
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        prefs = getSharedPreferences(ThorSwapService.PREFS_NAME, Context.MODE_PRIVATE);

        setupDaemonControls();
        setupSettingsControls();
        saveSettingsToFile();
        updateDaemonStatusUI();
    }

    private void initViews() {
        btnStart = findViewById(R.id.btnStartDaemon);
        btnStop = findViewById(R.id.btnStopDaemon);
        btnAbout = findViewById(R.id.btnAbout);

        statusText = findViewById(R.id.statusText);
        textThresholdTop = findViewById(R.id.textThresholdTop);
        textThresholdBottom = findViewById(R.id.textThresholdBottom);
        textCooldown = findViewById(R.id.textCooldown);

        seekBarTop = findViewById(R.id.seekBarThresholdTop);
        seekBarBottom = findViewById(R.id.seekBarThresholdBottom);
        seekBarCooldown = findViewById(R.id.seekBarCooldown);

        switchHomeLongPress = findViewById(R.id.switchHomeLongPress);
        switchAutoStart = findViewById(R.id.switchAutoStart);
        radioGroupFingers = findViewById(R.id.radioGroupFingers);
    }

    @Override protected void onResume() { super.onResume(); prefs.registerOnSharedPreferenceChangeListener(prefsListener); updateDaemonStatusUI(); }
    @Override protected void onPause() { super.onPause(); prefs.unregisterOnSharedPreferenceChangeListener(prefsListener); }

    private void setupDaemonControls() {
        btnStart.setOnClickListener(v -> {
            saveSettingsToFile();
            sendCommandToService(ThorSwapService.ACTION_START_DAEMON);
        });

        btnStop.setOnClickListener(v -> sendCommandToService(ThorSwapService.ACTION_STOP_DAEMON));


        if (btnAbout != null) {
            btnAbout.setOnClickListener(v -> showAboutDialog());
        }
    }

    private void showAboutDialog() {
        new AlertDialog.Builder(this)
                .setTitle("About Thor Swapper")
                .setMessage("Utility for switching apps between screens on Ayn Thor.\n\nVersion: 1.0")
                .setPositiveButton("GitHub", (dialog, which) -> {
                    // GitHub
                    String url = "https://github.com/Emile86/Thor-Swapper";
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW);
                        i.setData(Uri.parse(url));
                        startActivity(i);
                    } catch (Exception e) {
                        Toast.makeText(this, "Could not open link", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void updateDaemonStatusUI() {
        boolean isRunning = prefs.getBoolean(ThorSwapService.PREF_DAEMON_RUNNING, false);
        if (isRunning) {
            statusText.setText("Daemon: RUNNING");
            statusText.setTextColor(ContextCompat.getColor(this, R.color.green_success));
            btnStart.setEnabled(false); btnStop.setEnabled(true);
        } else {
            statusText.setText("Daemon: STOPPED");
            statusText.setTextColor(ContextCompat.getColor(this, R.color.red_error));
            btnStart.setEnabled(true); btnStop.setEnabled(false);
        }
    }

    private void setupSettingsControls() {
        int top = prefs.getInt("threshold_top", 300);
        int bottom = prefs.getInt("threshold_bottom", 220);
        int cooldown = prefs.getInt("move_cooldown", 1000);
        boolean home = prefs.getBoolean("home_long_press", true);
        boolean auto = prefs.getBoolean("auto_start_boot", false);
        int fingers = prefs.getInt("finger_count", 3);

        seekBarTop.setProgress(top); textThresholdTop.setText(top + " px");
        seekBarBottom.setProgress(bottom); textThresholdBottom.setText(bottom + " px");
        seekBarCooldown.setProgress(cooldown); textCooldown.setText(cooldown + " ms");
        switchHomeLongPress.setChecked(home);
        switchAutoStart.setChecked(auto);

        if (fingers == 2) radioGroupFingers.check(R.id.radio2Fingers);
        else if (fingers == 4) radioGroupFingers.check(R.id.radio4Fingers);
        else radioGroupFingers.check(R.id.radio3Fingers);

        SeekBar.OnSeekBarChangeListener sbListener = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (seekBar == seekBarTop) { textThresholdTop.setText(progress + " px"); prefs.edit().putInt("threshold_top", progress).apply(); }
                if (seekBar == seekBarBottom) { textThresholdBottom.setText(progress + " px"); prefs.edit().putInt("threshold_bottom", progress).apply(); }
                if (seekBar == seekBarCooldown) { textCooldown.setText(progress + " ms"); prefs.edit().putInt("move_cooldown", progress).apply(); }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) { saveSettingsToFile(); }
        };

        seekBarTop.setOnSeekBarChangeListener(sbListener);
        seekBarBottom.setOnSeekBarChangeListener(sbListener);
        seekBarCooldown.setOnSeekBarChangeListener(sbListener);

        switchHomeLongPress.setOnCheckedChangeListener((v, c) -> { prefs.edit().putBoolean("home_long_press", c).apply(); saveSettingsToFile(); });
        switchAutoStart.setOnCheckedChangeListener((v, c) -> prefs.edit().putBoolean("auto_start_boot", c).apply());

        radioGroupFingers.setOnCheckedChangeListener((group, checkedId) -> {
            int count = 3;
            if (checkedId == R.id.radio2Fingers) count = 2;
            else if (checkedId == R.id.radio4Fingers) count = 4;
            prefs.edit().putInt("finger_count", count).apply();
            saveSettingsToFile();
        });
    }

    private void saveSettingsToFile() {
        int top = prefs.getInt("threshold_top", 300);
        int bottom = prefs.getInt("threshold_bottom", 300);
        boolean home = prefs.getBoolean("home_long_press", true);
        int fingers = prefs.getInt("finger_count", 3);
        int cooldown = prefs.getInt("move_cooldown", 1000);

        String configData = "threshold_top=" + top + "\n" +
                "threshold_bottom=" + bottom + "\n" +
                "home_long_press=" + home + "\n" +
                "finger_count=" + fingers + "\n" +
                "move_cooldown=" + cooldown + "\n";

        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "echo '" + configData + "' > " + CONFIG_FILE_PATH + " && chmod 644 " + CONFIG_FILE_PATH});
            p.waitFor();
        } catch (Exception e) {
            Toast.makeText(this, "Error saving settings!", Toast.LENGTH_SHORT).show();
        }
    }

    private void sendCommandToService(String action) {
        Intent intent = new Intent(this, ThorSwapService.class);
        intent.setAction(action);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }
}