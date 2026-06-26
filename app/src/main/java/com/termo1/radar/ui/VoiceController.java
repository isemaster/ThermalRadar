package com.termo1.radar.ui;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.Locale;

/**
 * VoiceController — голосовые подсказки TTS.
 * Управляет TextToSpeech engine, дебаунсом 8с, очередностью.
 *
 * Исправлено P2: выделен из MainActivity.
 */
public class VoiceController {

    private static final long VOICE_DEBOUNCE_MS = 8000;

    private TextToSpeech tts;
    private boolean ttsReady;
    private String lastPhrase = "";
    private long lastSpeakMs;
    private boolean enabled = true;

    private final Context context;

    public VoiceController(Context context) {
        this.context = context;
    }

    /** Инициализация TTS engine. Вызвать в onCreate. */
    public void init() {
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

    /** Произнести фразу с дебаунсом. */
    public void speak(String text, Runnable onBeforeSpeak) {
        if (!ttsReady || !enabled || text == null || text.isEmpty()) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastSpeakMs < VOICE_DEBOUNCE_MS && text.equals(lastPhrase)) return;
        lastPhrase = text;
        lastSpeakMs = now;
        if (onBeforeSpeak != null) onBeforeSpeak.run();
        if (Build.VERSION.SDK_INT >= 21) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "VOICE");
        } else {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null);
        }
    }

    /** Сбросить дебаунс (принудительно разрешить следующий speak). */
    public void resetDebounce() {
        lastSpeakMs = 0;
    }

    public boolean isReady() { return ttsReady; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public String getLastPhrase() { return lastPhrase; }
    public long getLastSpeakMs() { return lastSpeakMs; }

    /** Shutdown — вызвать в onDestroy. */
    public void shutdown() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
            ttsReady = false;
        }
    }
}
