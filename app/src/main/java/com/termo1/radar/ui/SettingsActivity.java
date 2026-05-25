package com.termo1.radar.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;

import com.termo1.radar.BuildConfig;
import com.termo1.radar.MainActivity;
import com.termo1.radar.R;

/**
 * Settings activity for TERMO1.
 * No action bar, black background, green monospace text.
 */
public class SettingsActivity extends Activity {

    public static final String PREFS_NAME = "termo1_settings";

    public static final String KEY_SOUND    = "sound_enabled";
    public static final String KEY_VIBRATE  = "vibrate_enabled";
    public static final String KEY_NIGHT    = "night_mode";
    public static final String KEY_COLOR_SCHEME = "color_scheme";
    public static final String KEY_SIMULATE = "simulate";

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // fullscreen, black background
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        setContentView(buildLayout());
    }

    private View buildLayout() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(android.graphics.Color.rgb(10, 10, 10));
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 48, 32, 48);

        // --- Title ---
        TextView title = new TextView(this);
        title.setText(getString(R.string.settings_title));
        title.setTextSize(24);
        title.setTypeface(android.graphics.Typeface.MONOSPACE);
        title.setTextColor(android.graphics.Color.argb(200, 76, 175, 80));
        title.setPadding(0, 0, 0, 40);
        root.addView(title);

        // Back button at TOP
        Button backBtn = createButton(getString(R.string.settings_back));
        backBtn.setOnClickListener(v -> {
            finish();
        });
        root.addView(backBtn);

        // 1. Sound checkbox
        CheckBox soundCb = createCheckBox(getString(R.string.settings_sound), KEY_SOUND, true);
        root.addView(soundCb);

        // 2. Vibration checkbox
        CheckBox vibrateCb = createCheckBox(getString(R.string.settings_vibrate), KEY_VIBRATE, true);
        root.addView(vibrateCb);

        // 3. Color scheme selector
        TextView schemeLabel = new TextView(this);
        schemeLabel.setText(getString(R.string.settings_scheme));
        schemeLabel.setTextSize(18);
        schemeLabel.setTypeface(android.graphics.Typeface.MONOSPACE);
        schemeLabel.setTextColor(android.graphics.Color.argb(200, 0, 255, 0));
        schemeLabel.setPadding(0, 16, 0, 8);
        root.addView(schemeLabel);

        final int currentScheme = prefs.getInt(KEY_COLOR_SCHEME, 0);
        RadioGroup schemeGroup = new RadioGroup(this);
        schemeGroup.setOrientation(RadioGroup.VERTICAL);

        String[] schemeLabels = {getString(R.string.scheme_dark), getString(R.string.scheme_light), getString(R.string.scheme_high)};
        for (int i = 0; i < schemeLabels.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(schemeLabels[i]);
            rb.setTextSize(16);
            rb.setTypeface(android.graphics.Typeface.MONOSPACE);
            rb.setTextColor(android.graphics.Color.argb(200, 0, 255, 0));
            rb.setId(i);
            if (i == currentScheme) rb.setChecked(true);
            schemeGroup.addView(rb);
        }

        schemeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            prefs.edit().putInt(KEY_COLOR_SCHEME, checkedId).apply();
        });
        root.addView(schemeGroup);

        // Spacer
        View spacer1 = new View(this);
        spacer1.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 40));
        root.addView(spacer1);

        // 4. Simulation button
        Button simulateBtn = createButton(getString(R.string.settings_simulate));
        simulateBtn.setOnClickListener(v -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra("simulate", true);
            startActivity(intent);
        });
        root.addView(simulateBtn);

        // Spacer
        View spacer2 = new View(this);
        spacer2.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 24));
        root.addView(spacer2);

        // 5a. Vario smoothing slider (5-100 samples)
        TextView smoothLabel = new TextView(this);
        smoothLabel.setText(getString(R.string.settings_vario_smooth));
        smoothLabel.setTextSize(18);
        smoothLabel.setTypeface(android.graphics.Typeface.MONOSPACE);
        smoothLabel.setTextColor(android.graphics.Color.argb(200, 0, 255, 0));
        smoothLabel.setPadding(0, 16, 0, 8);
        root.addView(smoothLabel);

        final android.widget.TextView smoothValue = new android.widget.TextView(this);
        int currentSmooth = prefs.getInt("vario_smooth", 30);
        smoothValue.setText(String.format(getString(R.string.settings_smooth_format), currentSmooth));
        smoothValue.setTextSize(16);
        smoothValue.setTypeface(android.graphics.Typeface.MONOSPACE);
        smoothValue.setTextColor(android.graphics.Color.argb(255, 255, 255, 0));
        smoothValue.setPadding(0, 0, 0, 8);
        root.addView(smoothValue);

        android.widget.SeekBar smoothSeek = new android.widget.SeekBar(this);
        smoothSeek.setMax(95); // 5..100, offset by 5
        smoothSeek.setProgress(currentSmooth - 5);
        smoothSeek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                int value = progress + 5;
                smoothValue.setText(String.format(getString(R.string.settings_smooth_format), value));
                prefs.edit().putInt("vario_smooth", value).apply();
            }
            public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
            public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
        });
        root.addView(smoothSeek);

        // Spacer
        View spacer2b = new View(this);
        spacer2b.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 24));
        root.addView(spacer2b);

        // 5. Calibration reset button
        Button calResetBtn = createButton(getString(R.string.settings_cal_reset));
        calResetBtn.setOnClickListener(v -> {
            prefs.edit().remove("calibration_done").apply();
            Toast.makeText(this, getString(R.string.toast_cal_reset), Toast.LENGTH_SHORT).show();
        });
        root.addView(calResetBtn);

        // Spacer
        View spacer3 = new View(this);
        spacer3.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 24));
        root.addView(spacer3);

        // 6. Send logs button — opens file picker dialog
        Button sendLogsBtn = createButton(getString(R.string.settings_send_logs));
        sendLogsBtn.setOnClickListener(v -> showLogFilePicker());
        root.addView(sendLogsBtn);

        // Spacer
        View spacer4 = new View(this);
        spacer4.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 24));
        root.addView(spacer4);

        // 6b. Flight log START button (auto-log by vario)
        final Button flightLogStartBtn = createButton(getString(R.string.settings_flight_log_start));
        flightLogStartBtn.setOnClickListener(v -> {
            prefs.edit()
                .putBoolean("flight_log_enabled", true)
                .putLong("flight_log_start_ms", System.currentTimeMillis())
                .apply();
            Toast.makeText(this, getString(R.string.toast_flight_log_start),
                    Toast.LENGTH_LONG).show();
        });
        root.addView(flightLogStartBtn);

        // 6c. Flight log STOP button
        Button flightLogStopBtn = createButton(getString(R.string.settings_flight_log_stop));
        flightLogStopBtn.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.dialog_stop_recording_title))
                    .setPositiveButton(getString(R.string.exit_dialog_yes), (dialog, w) -> {
                        prefs.edit().putBoolean("flight_log_enabled", false).apply();
                        // Signal MainActivity to stop via intent extra
                        Intent intent = new Intent(this, MainActivity.class);
                        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                        intent.putExtra("stop_flight_log", true);
                        startActivity(intent);
                        Toast.makeText(this, getString(R.string.toast_recording_stopped),
                                Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton(getString(R.string.exit_dialog_no), null)
                    .show();
        });
        root.addView(flightLogStopBtn);

        // Spacer
        View spacer5 = new View(this);
        spacer5.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 24));
        root.addView(spacer5);

        // 7. Version & log path
        TextView infoText = new TextView(this);
        infoText.setTextSize(14);
        infoText.setTypeface(android.graphics.Typeface.MONOSPACE);
        infoText.setTextColor(android.graphics.Color.argb(80, 0, 255, 0));
        String version;
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            version = "1.0";
        }
        String logPath = getExternalFilesDir(null) != null
                ? getExternalFilesDir(null).getAbsolutePath() + "/logs/"
                : "/data/data/" + getPackageName() + "/files/logs/";
        infoText.setText(String.format("Версия: %s\nЛоги: %s", version, logPath));
        root.addView(infoText);

        // Spacer
        View spacerBottom = new View(this);
        spacerBottom.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 24));
        root.addView(spacerBottom);

        // Exit button at VERY BOTTOM
        Button exitBtn = createButton(getString(R.string.settings_exit));
        exitBtn.setOnClickListener(v -> {
            finishAffinity();
        });
        root.addView(exitBtn);

        scroll.addView(root);
        return scroll;
    }

    private CheckBox createCheckBox(String label, String prefKey, boolean defaultVal) {
        CheckBox cb = new CheckBox(this);
        cb.setText(label);
        cb.setTextSize(18);
        cb.setTypeface(android.graphics.Typeface.MONOSPACE);
        cb.setTextColor(android.graphics.Color.argb(200, 0, 255, 0));
        cb.setChecked(prefs.getBoolean(prefKey, defaultVal));
        cb.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefs.edit().putBoolean(prefKey, isChecked).apply());
        return cb;
    }

    private Button createButton(String text) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(18);
        btn.setTypeface(android.graphics.Typeface.MONOSPACE);
        btn.setTextColor(android.graphics.Color.argb(200, 0, 255, 0));
        btn.setBackgroundColor(android.graphics.Color.argb(30, 0, 255, 0));
        btn.setPadding(24, 16, 24, 16);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 8, 0, 8);
        btn.setLayoutParams(lp);
        return btn;
    }

    // ========================================================================
    // Log file picker + share
    // ========================================================================

    /**
     * Show dialog with checkboxes for each log file.
     * Отметить → Отправить или Удалить.
     */
    private void showLogFilePicker() {
        java.io.File extDir = getExternalFilesDir(null);
        if (extDir == null) {
            Toast.makeText(this, getString(R.string.toast_no_external_storage),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        java.io.File logDir = new java.io.File(extDir, "logs");
        if (!logDir.exists() || !logDir.isDirectory()) {
            Toast.makeText(this, getString(R.string.toast_no_logs),
                    Toast.LENGTH_SHORT).show();
            return;
        }

        final java.io.File[] files = logDir.listFiles();
        if (files == null || files.length == 0) {
            Toast.makeText(this, getString(R.string.toast_no_logs),
                    Toast.LENGTH_SHORT).show();
            return;
        }

        // Sort by modification time, newest first
        java.util.Arrays.sort(files, (a, b) ->
                Long.compare(b.lastModified(), a.lastModified()));

        // Build human-readable names
        String[] names = new String[files.length];
        for (int i = 0; i < files.length; i++) {
            String name = files[i].getName();
            long size = files[i].length();
            String sizeStr = size > 1024 ? (size / 1024) + " КБ" : size + " Б";
            // Show: filename (size)
            String displayName = name;
            // Parse readable info from filename: test/flight prefix
            if (name.startsWith("test_") || name.startsWith("flight_")) {
                // flight_20260523_114514.csv -> 20260523_114514
                displayName = name;
            }
            // Shorten: remove extension, show name + size
            names[i] = displayName.replace(".csv", "").replace(".zip", "") + "  (" + sizeStr + ")";
        }

        // Checkboxes state
        final boolean[] checked = new boolean[files.length];

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.dialog_log_picker_title));

        builder.setMultiChoiceItems(names, checked, (dialog, which, isChecked) -> {
            checked[which] = isChecked;
        });

        builder.setPositiveButton(getString(R.string.dialog_send), (dialog, which) -> {
            int sent = 0;
            for (int i = 0; i < files.length; i++) {
                if (checked[i]) {
                    sendLogFile(files[i]);
                    sent++;
                }
            }
            if (sent == 0) {
                Toast.makeText(this, getString(R.string.toast_no_files_selected),
                        Toast.LENGTH_SHORT).show();
            }
        });

        builder.setNeutralButton(getString(R.string.dialog_delete), (dialog, which) -> {
            int deleted = 0;
            for (int i = 0; i < files.length; i++) {
                if (checked[i] && files[i].delete()) {
                    deleted++;
                }
            }
            if (deleted > 0) {
                Toast.makeText(this, String.format(getString(R.string.toast_deleted_files), deleted),
                        Toast.LENGTH_SHORT).show();
                // Re-open dialog with updated list
                showLogFilePicker();
            } else {
                Toast.makeText(this, getString(R.string.toast_no_files_selected),
                        Toast.LENGTH_SHORT).show();
            }
        });

        builder.setNegativeButton(getString(R.string.dialog_cancel), null);
        builder.show();
    }

    /**
     * Send a single log file via system share dialog.
     * Uses custom Termo1FileProvider to create content:// URI.
     */
    private void sendLogFile(java.io.File file) {
        // Content URI via our custom FileProvider
        // authority: <applicationId>.fileprovider (устойчив к смене applicationId)
        android.net.Uri contentUri = android.net.Uri.parse(
                "content://" + BuildConfig.APPLICATION_ID
                + ".fileprovider"
                + "/logs/" + file.getName());

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        // Определяем MIME-тип по расширению
        String mimeType = file.getName().endsWith(".zip") ? "application/zip" : "text/csv";
        shareIntent.setType(mimeType);
        shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try {
            startActivity(Intent.createChooser(shareIntent,
                    "Отправить лог: " + file.getName()));
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.send_error_prefix) + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }
}
