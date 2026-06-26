package com.termo1.radar;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import com.termo1.radar.ui.VarioSoundManager;

import java.util.Locale;

/**
 * TtsManager — голосовые подсказки и звук варио.
 * Объединяет TextToSpeech, VarioSoundManager, circling voice callback.
 *
 * Выделен из MainActivity.
 */
public class TtsManager {

    public interface Callback {
        void onSpeak(String text);
    }

    private static final long VOICE_DEBOUNCE_MS = 8000;
    private static final long THERMAL_BEEP_INTERVAL_MS = 6000L;

    private TextToSpeech tts;
    private boolean ttsReady;
    private String lastPhrase = "";
    private long lastSpeakMs;
    private boolean voiceEnabled = true;

    private VarioSoundManager varioSoundManager;
    private float varioThreshold = 0.5f;

    private long lastThermalBeepRealMs;
    private long lastThermalBeepMs;

    private final Context context;
    private final Callback callback;

    public TtsManager(Context context, Callback callback) {
        this.context = context;
        this.callback = callback;
    }

    public void init() {
        varioSoundManager = new VarioSoundManager();
        varioSoundManager.setDeadBandHigh(varioThreshold);

        tts = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.forLanguageTag("ru"));
                ttsReady = true;
                if (Build.VERSION.SDK_INT >= 21) {
                    tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override public void onStart(String utteranceId) {}
                        @Override public void onDone(String utteranceId) {}
                        @Override public void onError(String utteranceId) {}
                    });
                }
            }
        });
    }

    public void setVarioThreshold(float threshold) {
        this.varioThreshold = threshold;
        if (varioSoundManager != null) varioSoundManager.setDeadBandHigh(threshold);
    }

    public void speak(String text) {
        if (!ttsReady || !voiceEnabled || text == null || text.isEmpty()) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastSpeakMs < VOICE_DEBOUNCE_MS && text.equals(lastPhrase)) return;
        lastPhrase = text;
        lastSpeakMs = now;
        if (callback != null) callback.onSpeak(text);
        if (Build.VERSION.SDK_INT >= 21) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "VOICE");
        } else {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null);
        }
    }

    /** CirclingManager voice callback — формирует и произносит подсказку */
    public com.termo1.radar.flight.CirclingManager.VoiceCallback getCirclingVoiceCallback() {
        return text -> speak(text);
    }

    public void updateVarioSound(float vario) {
        if (varioSoundManager != null) varioSoundManager.update(vario);
    }

    /** Термик-бип (не чаще раза в 6 секунд) */
    public void playThermalBeep() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastThermalBeepRealMs >= THERMAL_BEEP_INTERVAL_MS) {
            lastThermalBeepRealMs = now;
            if (varioSoundManager != null) varioSoundManager.playThermalBeep();
        }
    }

    public void setSoundEnabled(boolean enabled) {
        if (varioSoundManager != null) varioSoundManager.setSoundEnabled(enabled);
    }

    public void setVoiceEnabled(boolean enabled) { this.voiceEnabled = enabled; }
    public boolean isVoiceEnabled() { return voiceEnabled; }
    public boolean isTtsReady() { return ttsReady; }
    public VarioSoundManager getVarioSoundManager() { return varioSoundManager; }

    public void shutdown() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
            ttsReady = false;
        }
    }
}
