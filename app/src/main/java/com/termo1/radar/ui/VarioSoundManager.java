package com.termo1.radar.ui;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

import java.util.Arrays;

/**
 * VarioSoundManager — звуковой вариометр с PCM-синтезом.
 *
 * По образу XCSoar VarioSynthesiser:
 * - Подъём: импульсный тон (частота↑, скважность↑ с силой подъёма)
 * - Снижение: непрерывный низкий тон (300→100 Гц, глубже = ниже)
 * - Dead band: тишина при vario ~ -0.3..+0.3 м/с
 * - Anti-click: завершение синусоиды перед паузой
 *
 * Audio format: 44100 Hz, MONO, 16-bit PCM.
 */
public class VarioSoundManager {

    private static final String TAG = "VarioSound";

    // ===== AUDIO PARAMETERS =====
    private static final int SAMPLE_RATE = 44100;
    private static final int BUFFER_SAMPLES = 4096;
    private static final int AMPLITUDE = 8000;

    // ===== VARIO RANGES (м/с) =====
    private static final float DEAD_BAND_LOW = -1.2f;       // тишина ниже этого = sink tone
    private volatile float deadBandHigh = 0.3f;              // тишина выше этого = climb beep (управляется из настроек)
    private static final float VERY_FAST_CLIMB = 9.0f;      // непрерывный тон
    private static final float MAX_SINK = -5.0f;             // максимальная скорость снижения

    // ===== CLIMB PARAMETERS (подъём) =====
    private static final float CLIMB_MIN_FREQ = 350f;
    private static final float CLIMB_MAX_FREQ = 1000f;
    private static final int CLIMB_SLOW_TONE_MS = 200;
    private static final int CLIMB_FAST_TONE_MS = 50;
    private static final int CLIMB_SLOW_PAUSE_MS = 300;
    private static final int CLIMB_FAST_PAUSE_MS = 75;

    // ===== SINK PARAMETERS (снижение — как XCSoar) =====
    /** Частота при слабом снижении (-0.3 м/с) — чуть ниже zero_frequency */
    private static final float SINK_MIN_FREQ = 300f;
    /** Частота при сильном снижении (-5 м/с) */
    private static final float SINK_MAX_FREQ = 100f;
    /** Sink tone — непрерывный, без прерываний */

    // ===== THERMAL BEEP =====
    private static final int THERMAL_BEEP_MS = 150;
    private static final int THERMAL_BEEP_FREQ = 800;

    // ===== TEST BEEP =====
    private static final int TEST_BEEP_FREQ = 700;
    private static final int TEST_BEEP_MS = 100;
    private static final int TEST_BEEP_PAUSE_MS = 100;
    private static final int TEST_BEEP_COUNT = 5;

    // ===== RE-EVALUATION =====
    private static final int STATE_CHECK_MS = 100;

    // ===== STATE =====
    private AudioTrack audioTrack;
    private volatile boolean running = false;
    private volatile boolean soundEnabled = true;
    private volatile float currentVario = 0f;
    private volatile boolean thermalBeepPending = false;
    private volatile boolean testBeepPending = false;

    private Thread audioThread;
    private double phase = 0.0;   // running phase for sine generation

    // ===== LIFECYCLE =====

    public void start() {
        if (audioTrack != null) {
            Log.w(TAG, "start() called but already running");
            return;
        }

        int minBufSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(minBufSize, BUFFER_SAMPLES * 2);

        audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(bufSize)
                .build();

        running = true;
        audioThread = new Thread(this::audioLoop, "vario-sound");
        audioThread.setDaemon(true);
        audioThread.start();

        Log.d(TAG, "VarioSoundManager started (buffer=" + bufSize + " bytes)");
    }

    public void update(float vario) {
        if (!soundEnabled) {
            currentVario = 0f;
            return;
        }
        if (Float.isNaN(vario) || Float.isInfinite(vario)) {
            currentVario = 0f;
            return;
        }
        currentVario = vario;
    }

    public void playThermalBeep() {
        if (soundEnabled) thermalBeepPending = true;
    }

    public void playTestBeep() {
        if (soundEnabled) testBeepPending = true;
    }

    public void stop() {
        running = false;
        if (audioThread != null) {
            try {
                audioThread.join(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            audioThread = null;
        }
        if (audioTrack != null) {
            try {
                if (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    audioTrack.stop();
                }
                audioTrack.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing AudioTrack", e);
            }
            audioTrack = null;
        }
        Log.d(TAG, "VarioSoundManager stopped");
    }

    public boolean isSoundEnabled() { return soundEnabled; }

    /** Установить порог dead band для варио-звука (берётся из настроек Vario-термика) */
    public void setDeadBandHigh(float threshMs) {
        this.deadBandHigh = threshMs;
    }

    public void setSoundEnabled(boolean enabled) {
        this.soundEnabled = enabled;
        if (!enabled) currentVario = 0f;
    }

    // ===== AUDIO LOOP =====

    private void audioLoop() {
        short[] buffer = new short[BUFFER_SAMPLES];

        if (audioTrack == null) return;

        try {
            audioTrack.play();
        } catch (Exception e) {
            Log.e(TAG, "Failed to start AudioTrack playback", e);
            return;
        }

        // State machine
        //   mode: 0=silence, 1=climb_beep, 2=climb_pause, 3=sink_tone, 4=continuous_tone
        int mode = 0;
        int samplesRemaining = 0;
        float currentSineFreq = 0f;

        while (running && audioTrack != null) {
            // ================================================================
            // 0. Test feedback beep — highest priority
            // ================================================================
            if (testBeepPending) {
                testBeepPending = false;
                phase = 0f;
                for (int beep = 0; beep < TEST_BEEP_COUNT && running && audioTrack != null; beep++) {
                    int toneSamples = SAMPLE_RATE * TEST_BEEP_MS / 1000;
                    while (toneSamples > 0 && running && audioTrack != null) {
                        int count = Math.min(BUFFER_SAMPLES, toneSamples);
                        fillSine(buffer, TEST_BEEP_FREQ, count);
                        int written = audioTrack.write(buffer, 0, count);
                        if (written < 0) break;
                        toneSamples -= count;
                    }
                    if (beep < TEST_BEEP_COUNT - 1) {
                        int pauseSamples = SAMPLE_RATE * TEST_BEEP_PAUSE_MS / 1000;
                        while (pauseSamples > 0 && running && audioTrack != null) {
                            int count = Math.min(BUFFER_SAMPLES, pauseSamples);
                            Arrays.fill(buffer, 0, count, (short) 0);
                            int written = audioTrack.write(buffer, 0, count);
                            if (written < 0) break;
                            pauseSamples -= count;
                        }
                    }
                }
                samplesRemaining = 0;
                continue;
            }

            // ================================================================
            // 1. Thermal beep — second priority
            // ================================================================
            if (thermalBeepPending) {
                phase = 0f;
                thermalBeepPending = false;
                int remaining = SAMPLE_RATE * THERMAL_BEEP_MS / 1000;
                while (remaining > 0 && running && audioTrack != null) {
                    int count = Math.min(BUFFER_SAMPLES, remaining);
                    fillSine(buffer, THERMAL_BEEP_FREQ, count);
                    int written = audioTrack.write(buffer, 0, count);
                    if (written < 0) break;
                    remaining -= count;
                }
                samplesRemaining = 0;
                continue;
            }

            // ================================================================
            // 2. State machine with anti-click + sink tone + dead band
            // ================================================================
            if (samplesRemaining <= 0) {
                float v = currentVario;

                if (v > VERY_FAST_CLIMB) {
                    // Very fast climb → continuous tone at max frequency
                    mode = 4;
                    currentSineFreq = CLIMB_MAX_FREQ;
                    samplesRemaining = SAMPLE_RATE * STATE_CHECK_MS / 1000;
                } else if (v > deadBandHigh) {
                    // Normal climb → alternate beep/pause
                    if (mode == 2 || mode == 0 || mode == 3) {
                        // Start beep
                        mode = 1;
                        currentSineFreq = map(v, deadBandHigh, VERY_FAST_CLIMB,
                                CLIMB_MIN_FREQ, CLIMB_MAX_FREQ);
                        int toneMs = Math.round(map(v, deadBandHigh, VERY_FAST_CLIMB,
                                CLIMB_SLOW_TONE_MS, CLIMB_FAST_TONE_MS));
                        samplesRemaining = SAMPLE_RATE * toneMs / 1000;
                    } else {
                        // End of beep → anti-click → pause
                        mode = 2;
                        // Anti-click: complete current sine period using last frequency
                        int antiClickSamples = completeSinePeriod(currentSineFreq);
                        currentSineFreq = 0f;
                        int pauseMs = Math.round(map(v, deadBandHigh, VERY_FAST_CLIMB,
                                CLIMB_SLOW_PAUSE_MS, CLIMB_FAST_PAUSE_MS));
                        samplesRemaining = antiClickSamples + SAMPLE_RATE * pauseMs / 1000;
                    }
                } else if (v < DEAD_BAND_LOW) {
                    // Sink → continuous LOW tone (как XCSoar: непрерывный тон при снижении)
                    mode = 3;
                    // Отображаем sink rate на частоту: -0.3 м/с → 300 Гц, -5 м/с → 100 Гц
                    float sinkFrac = (v - DEAD_BAND_LOW) / (MAX_SINK - DEAD_BAND_LOW);
                    sinkFrac = Math.max(0f, Math.min(1f, sinkFrac));
                    currentSineFreq = SINK_MIN_FREQ + (SINK_MAX_FREQ - SINK_MIN_FREQ) * sinkFrac;
                    samplesRemaining = SAMPLE_RATE * STATE_CHECK_MS / 1000;
                } else {
                    // Dead band → silence (с anti-click если был тон)
                    if (mode == 1 || mode == 4 || mode == 3) {
                        // Был тон → anti-click (завершить синусоиду)
                        int antiClickSamples = completeSinePeriod(currentSineFreq);
                        mode = 0;
                        currentSineFreq = 0f;
                        samplesRemaining = antiClickSamples + SAMPLE_RATE * STATE_CHECK_MS / 1000;
                    } else {
                        // Уже тишина → просто ждём
                        mode = 0;
                        currentSineFreq = 0f;
                        samplesRemaining = SAMPLE_RATE * STATE_CHECK_MS / 1000;
                    }
                }
            }

            // ================================================================
            // 3. Generate PCM samples
            // ================================================================
            int count = Math.min(BUFFER_SAMPLES, samplesRemaining);

            if (currentSineFreq > 0f) {
                fillSine(buffer, currentSineFreq, count);
            } else {
                Arrays.fill(buffer, 0, count, (short) 0);
            }

            // ================================================================
            // 4. Write to AudioTrack
            // ================================================================
            int written = audioTrack.write(buffer, 0, count);
            if (written < 0) {
                Log.e(TAG, "AudioTrack write error: " + written);
                break;
            }

            samplesRemaining -= count;
        }

        Log.d(TAG, "Audio loop ended");
    }

    // ========================================================================
    // Sine generation
    // ========================================================================

    private void fillSine(short[] buffer, float freqHz, int count) {
        double twoPiF = 2.0 * Math.PI * freqHz / SAMPLE_RATE;
        double twoPi = 2.0 * Math.PI;
        int limit = Math.min(count, buffer.length);
        for (int i = 0; i < limit; i++) {
            phase += twoPiF;
            if (phase > twoPi) {
                phase -= twoPi;
            }
            buffer[i] = (short) (Math.sin(phase) * AMPLITUDE);
        }
    }

    /**
     * Anti-click: завершить текущий период синусоиды, дойдя до zero crossing.
     * Использует актуальную частоту тона для точного расчёта количества сэмплов.
     *
     * @param lastFreq частота тона перед остановкой (Гц), 0 = тишина
     * @return количество сэмплов для завершения (0 если уже на zero crossing)
     */
    private int completeSinePeriod(float lastFreq) {
        if (lastFreq <= 0) return 0;
        double twoPi = 2.0 * Math.PI;
        double modPhase = phase % twoPi;
        if (modPhase < 0) modPhase += twoPi;

        if (modPhase <= 0.001 || Math.abs(modPhase - Math.PI) < 0.001) {
            return 0;
        }

        double toZero;
        if (modPhase < Math.PI) {
            toZero = Math.PI - modPhase;
        } else {
            toZero = twoPi - modPhase;
        }

        // Используем реальную частоту: onePiF = 2*PI*freq/SAMPLE_RATE
        double twoPiF = 2.0 * Math.PI * lastFreq / SAMPLE_RATE;
        // samples = toZero / twoPiF
        int samples = (int) Math.ceil(toZero / twoPiF);
        if (samples > SAMPLE_RATE / 50) samples = SAMPLE_RATE / 50; // max 20ms
        if (samples < 1) samples = 1;
        return samples;
    }

    // ========================================================================
    // Utility
    // ========================================================================

    private static float map(float x, float inMin, float inMax,
                              float outMin, float outMax) {
        float t = (x - inMin) / (inMax - inMin);
        t = Math.max(0f, Math.min(1f, t));
        return outMin + t * (outMax - outMin);
    }
}
