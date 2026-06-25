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
        title.setTextSize(19);
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

        // 2b. Blind flight mode checkbox
        CheckBox blindCb = createCheckBox("Слепой полёт (карман)", "blind_mode", false);
        root.addView(blindCb);

        // 2c. Voice prompts checkbox
        CheckBox voiceCb = createCheckBox("Голосовые подсказки", "voice_prompts", true);
        root.addView(voiceCb);

        // 2d. Sunlight mode checkbox
        CheckBox sunCb = createCheckBox("Солнечный режим (яркие цвета)", "sunlight_mode", false);
        root.addView(sunCb);

        // 2e. Airspeed slider
        TextView airspeedLabel = new TextView(this);
        airspeedLabel.setText("Воздушная скорость");
        airspeedLabel.setTextSize(14);
        airspeedLabel.setTypeface(android.graphics.Typeface.MONOSPACE);
        airspeedLabel.setTextColor(android.graphics.Color.argb(200, 0, 255, 0));
        airspeedLabel.setPadding(0, 16, 0, 8);
        root.addView(airspeedLabel);

        final android.widget.TextView airspeedValue = new android.widget.TextView(this);
        float currentAirspeed = prefs.getFloat("airspeed_ms", 9.5f);
        airspeedValue.setText(String.format(java.util.Locale.US, "%.1f м/с", currentAirspeed));
        airspeedValue.setTextSize(13);
        airspeedValue.setTypeface(android.graphics.Typeface.MONOSPACE);
        airspeedValue.setTextColor(android.graphics.Color.argb(255, 255, 255, 0));
        airspeedValue.setPadding(0, 0, 0, 8);
        root.addView(airspeedValue);

        android.widget.SeekBar airspeedSeek = new android.widget.SeekBar(this);
        airspeedSeek.setMax(70); // 8.0..15.0 → (15-8)*10 = 70
        airspeedSeek.setProgress((int)((currentAirspeed - 8f) * 10f));
        airspeedSeek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                float value = 8f + progress * 0.1f;
                airspeedValue.setText(String.format(java.util.Locale.US, "%.1f м/с", value));
                prefs.edit().putFloat("airspeed_ms", value).apply();
            }
            public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
            public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
        });
        root.addView(airspeedSeek);

        // 2f. VarioThermal threshold slider
        TextView varioThreshLabel = new TextView(this);
        varioThreshLabel.setText("Порог Vario-термика");
        varioThreshLabel.setTextSize(14);
        varioThreshLabel.setTypeface(android.graphics.Typeface.MONOSPACE);
        varioThreshLabel.setTextColor(android.graphics.Color.argb(200, 0, 255, 0));
        varioThreshLabel.setPadding(0, 16, 0, 8);
        root.addView(varioThreshLabel);

        final android.widget.TextView varioThreshValue = new android.widget.TextView(this);
        float currentVarioThresh = prefs.getFloat("vario_threshold", 0.5f);
        varioThreshValue.setText(String.format(java.util.Locale.US, "%+.1f м/с", currentVarioThresh));
        varioThreshValue.setTextSize(13);
        varioThreshValue.setTypeface(android.graphics.Typeface.MONOSPACE);
        varioThreshValue.setTextColor(android.graphics.Color.argb(255, 255, 255, 0));
        varioThreshValue.setPadding(0, 0, 0, 8);
        root.addView(varioThreshValue);

        android.widget.SeekBar varioThreshSeek = new android.widget.SeekBar(this);
        varioThreshSeek.setMax(300); // -1.0..+2.0 → (2 - (-1)) * 100 = 300
        varioThreshSeek.setProgress((int)((currentVarioThresh + 1f) * 100f));
        varioThreshSeek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                float value = -1f + progress * 0.01f;
                varioThreshValue.setText(String.format(java.util.Locale.US, "%+.2f м/с", value));
                prefs.edit().putFloat("vario_threshold", value).apply();
            }
            public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
            public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
        });
        root.addView(varioThreshSeek);

        // Spacer
        View spacerVib = new View(this);
        spacerVib.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 24));
        root.addView(spacerVib);

        // 3. Color scheme selector
        TextView schemeLabel = new TextView(this);
        schemeLabel.setText(getString(R.string.settings_scheme));
        schemeLabel.setTextSize(14);
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
            rb.setTextSize(13);
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

        // 4. Flight test button (replaces old simulation)
        Button simulateBtn = createButton("✈️ Тест полёта (100с)");
        simulateBtn.setOnClickListener(v -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra("flight_test", true);
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(intent);
        });
        root.addView(simulateBtn);

        // 4b. Треклоги button
        Button trackBtn = createButton("Треклоги");
        trackBtn.setOnClickListener(v -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra("track_replay", true);
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(intent);
        });
        root.addView(trackBtn);

        // Ищет IGC-файлов в getExternalFilesDir(null)/igc/
        java.io.File igcDir = new java.io.File(
                getExternalFilesDir(null), "igc");
        if (igcDir.exists() && igcDir.isDirectory()) {
            java.io.File[] igcFiles = igcDir.listFiles((dir, name) ->
                    name.toLowerCase(java.util.Locale.US).endsWith(".igc"));
            if (igcFiles != null && igcFiles.length > 0) {
                // Sort by last modified, newest first
                java.util.Arrays.sort(igcFiles, (a, b) ->
                        Long.compare(b.lastModified(), a.lastModified()));

                // Section header
                TextView igcHeader = new TextView(this);
                igcHeader.setText("Файлы IGC:");
                igcHeader.setTextSize(13);
                igcHeader.setTypeface(android.graphics.Typeface.MONOSPACE);
                igcHeader.setTextColor(android.graphics.Color.argb(180, 0, 255, 0));
                igcHeader.setPadding(0, 12, 0, 8);
                root.addView(igcHeader);

                for (final java.io.File igcFile : igcFiles) {
                    LinearLayout row = new LinearLayout(this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setPadding(8, 4, 8, 4);
                    LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
                    row.setLayoutParams(rowLp);

                    // File name label
                    String sizeStr = igcFile.length() > 1024
                            ? (igcFile.length() / 1024) + " КБ"
                            : igcFile.length() + " Б";
                    String label = igcFile.getName() + "  (" + sizeStr + ")";
                    TextView fileNameView = new TextView(this);
                    fileNameView.setText(label);
                    fileNameView.setTextSize(13);
                    fileNameView.setTypeface(android.graphics.Typeface.MONOSPACE);
                    fileNameView.setTextColor(android.graphics.Color.argb(200, 0, 255, 0));
                    fileNameView.setLayoutParams(new LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                    row.addView(fileNameView);

                    // ▶ Play button
                    Button playBtn = new Button(this);
                    playBtn.setText("▶ Play");
                    playBtn.setTextSize(13);
                    playBtn.setTypeface(android.graphics.Typeface.MONOSPACE);
                    playBtn.setTextColor(android.graphics.Color.argb(200, 0, 255, 0));
                    playBtn.setBackgroundColor(android.graphics.Color.argb(40, 0, 255, 0));
                    playBtn.setPadding(16, 8, 16, 8);
                    playBtn.setOnClickListener(v -> {
                        Intent intent = new Intent(this, MainActivity.class);
                        intent.putExtra("track_replay", true);
                        intent.putExtra("track_file", igcFile.getAbsolutePath());
                        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                        startActivity(intent);
                    });
                    row.addView(playBtn);

                    root.addView(row);
                }
            }
        }

        // Spacer
        View spacer2 = new View(this);
        spacer2.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 24));
        root.addView(spacer2);

        // Spacer before calibration
        View spacerVario = new View(this);
        spacerVario.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 24));
        root.addView(spacerVario);

        // 5a. Vario smoothing slider (5-100 samples)
        TextView smoothLabel = new TextView(this);
        smoothLabel.setText(getString(R.string.settings_vario_smooth));
        smoothLabel.setTextSize(14);
        smoothLabel.setTypeface(android.graphics.Typeface.MONOSPACE);
        smoothLabel.setTextColor(android.graphics.Color.argb(200, 0, 255, 0));
        smoothLabel.setPadding(0, 16, 0, 8);
        root.addView(smoothLabel);

        final android.widget.TextView smoothValue = new android.widget.TextView(this);
        int currentSmooth = prefs.getInt("vario_smooth", 30);
        smoothValue.setText(String.format(getString(R.string.settings_smooth_format), currentSmooth));
        smoothValue.setTextSize(13);
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

        // Spacer before cal reset
        View spacerCal = new View(this);
        spacerCal.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 24));
        root.addView(spacerCal);

        // 5. Calibration button — запоминает наклон крепления
        final float[] mountTiltDeg = {prefs.getFloat("mount_tilt_deg", 0f)};
        Button calResetBtn = createButton(getString(R.string.settings_cal_reset)
                + (mountTiltDeg[0] > 0.5f ? String.format(" %.0f°", mountTiltDeg[0]) : ""));
        calResetBtn.setOnClickListener(v -> {
            prefs.edit().putBoolean("tilt_calibration_requested", true).apply();
            // Пока не знаем угол — MainActivity сохранит его в mount_tilt_deg
            Toast.makeText(this, "Повисните неподвижно на 2 секунды", Toast.LENGTH_LONG).show();
            mountTiltDeg[0] = 0f;
            v.postDelayed(() -> {
                float saved = prefs.getFloat("mount_tilt_deg", 0f);
                mountTiltDeg[0] = saved;
                ((Button)v).setText(getString(R.string.settings_cal_reset)
                        + (saved > 0.5f ? String.format(" %.0f°", saved) : ""));
            }, 4000);
        });
        root.addView(calResetBtn);

        // 5. Pilot config (H-03)
        root.addView(createLabel("Пилот / дельтаплан"));
        root.addView(createInputField("Имя пилота", "pilot_name", "UNKNOWN"));
        root.addView(createInputField("Тип дельтаплана", "glider_type", "Paraglider"));
        root.addView(createInputField("ID дельтаплана", "glider_id", "UNKNOWN"));

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

        // Spacer
        View spacer5 = new View(this);
        spacer5.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 24));
        root.addView(spacer5);

        // 7. Version & log path
        TextView infoText = new TextView(this);
        infoText.setTextSize(11);
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
        cb.setTextSize(14);
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
        btn.setTextSize(14);
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

    /** Создать текстовую метку */
    private android.widget.TextView createLabel(String text) {
        android.widget.TextView label = new android.widget.TextView(this);
        label.setText(text);
        label.setTextSize(12);
        label.setTypeface(android.graphics.Typeface.MONOSPACE);
        label.setTextColor(android.graphics.Color.argb(120, 0, 255, 0));
        label.setPadding(8, 16, 8, 4);
        return label;
    }

    /** Создать поле ввода, сохранённое в SharedPreferences */
    private android.widget.EditText createInputField(String hint, String prefKey, String defValue) {
        final android.content.SharedPreferences prefs = getSharedPreferences("termo1_settings", MODE_PRIVATE);
        android.widget.EditText edit = new android.widget.EditText(this);
        edit.setHint(hint);
        edit.setText(prefs.getString(prefKey, defValue));
        edit.setTextSize(14);
        edit.setTypeface(android.graphics.Typeface.MONOSPACE);
        edit.setTextColor(android.graphics.Color.argb(220, 0, 255, 0));
        edit.setBackgroundColor(android.graphics.Color.argb(20, 0, 255, 0));
        edit.setPadding(12, 8, 12, 8);
        edit.setSingleLine(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 4, 0, 8);
        edit.setLayoutParams(lp);
        // Save on text change
        edit.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            public void afterTextChanged(android.text.Editable s) {
                prefs.edit().putString(prefKey, s.toString()).apply();
            }
        });
        return edit;
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
