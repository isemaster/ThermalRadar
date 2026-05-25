package com.termo1.radar.ui;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

import java.util.Arrays;

/**
 * VarioSoundManager — Brauniger IQ vario pulsed beeping via AudioTrack.
 *
 * Generates pulsed sine beeps whose rate, duration, and pitch scale with climb rate:
 *   - Climb 0.3→9.0 m/s:
 *       Frequency:  350→1000 Hz      (higher pitch = stronger climb)
 *       Tone:       200→50 ms        (shorter beep = faster climb)
 *       Pause:      300→75 ms        (shorter pause = faster climb)
 *       Net effect: slow climb = long beep + long pause → slow pulsing
 *                   fast climb = short beep + short pause → rapid pulsing
 *   - Very fast climb (>9 m/s): continuous tone at 1000 Hz
 *   - Sink / near-zero: silence
 *   - Thermal alarm: single 800 Hz beep for 150 ms
 *
 * Audio format: 44100 Hz, MONO, 16-bit PCM.
 * Runs a dedicated background thread with a beep/pause/silence state machine.
 */
public class VarioSoundManager {

    private static final String TAG = "VarioSound";

    // ===== AUDIO PARAMETERS =====
    private static final int SAMPLE_RATE = 44100;
    private static final int BUFFER_SAMPLES = 4096;           // samples per write chunk
    private static final int AMPLITUDE = 8000;                // 16-bit amplitude (0-32767)

    // ===== BRAUNIGER IQ VARIO PARAMETERS =====
    private static final float MIN_CLIMB_THRESHOLD = 0.3f;    // m/s — below this = silence
    private static final float VERY_FAST_THRESHOLD = 9.0f;    // m/s — above this = continuous tone

    // Climb range mapping: slow (0.3 m/s) → fast (9.0 m/s)
    private static final float MIN_FREQ = 350f;               // Hz at threshold (slow climb)
    private static final float MAX_FREQ = 1000f;              // Hz at very fast climb
    private static final int SLOW_TONE_MS = 200;              // beep duration at slow climb
    private static final int FAST_TONE_MS = 50;               // beep duration at fast climb
    private static final int SLOW_PAUSE_MS = 300;             // pause at slow climb
    private static final int FAST_PAUSE_MS = 75;              // pause at fast climb

    // How often (ms) to re-check vario during silence / continuous tone
    private static final int STATE_CHECK_MS = 100;

    // ===== THERMAL BEEP =====
    private static final int THERMAL_BEEP_MS = 150;
    private static final int THERMAL_BEEP_FREQ = 800;         // Hz

    // ===== TEST FEEDBACK BEEP (700 Hz × 100ms × 5) =====
    private static final int TEST_BEEP_FREQ = 700;
    private static final int TEST_BEEP_MS = 100;
    private static final int TEST_BEEP_PAUSE_MS = 100;
    private static final int TEST_BEEP_COUNT = 5;
    private static final int TEST_BEEP_TOTAL_MS = (TEST_BEEP_MS + TEST_BEEP_PAUSE_MS) * TEST_BEEP_COUNT;
    private static final int TEST_BEEP_SAMPLES = SAMPLE_RATE * TEST_BEEP_TOTAL_MS / 1000;
    private static final int THERMAL_BEEP_SAMPLES = SAMPLE_RATE * THERMAL_BEEP_MS / 1000;

    // ===== STATE =====
    private AudioTrack audioTrack;
    private volatile boolean running = false;
    private volatile boolean soundEnabled = true;
    private volatile float currentVario = 0f;                 // latest vario from update()
    private volatile boolean thermalBeepPending = false;
    private volatile boolean testBeepPending = false;

    private Thread audioThread;
    private double phase = 0.0;                                 // running phase for sine generation

    // ===== LIFECYCLE =====

    /** Create and start the AudioTrack + background audio thread. */
    public void start() {
        if (audioTrack != null) {
            Log.w(TAG, "start() called but already running");
            return;
        }

        int minBufSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(minBufSize, BUFFER_SAMPLES * 2); // *2 because 16-bit = 2 bytes

        audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
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

    /**
     * Update the current variometer value for the audio thread to use.
     *
     * @param vario vertical speed in m/s (positive = climb)
     */
    public void update(float vario) {
        if (!soundEnabled) {
            currentVario = 0f;
            return;
        }

        // Guard against NaN / Infinity
        if (Float.isNaN(vario) || Float.isInfinite(vario)) {
            currentVario = 0f;
            return;
        }

        currentVario = vario;
    }

    /** Queue a short 800 Hz beep for thermal detection. */
    public void playThermalBeep() {
        if (soundEnabled) {
            thermalBeepPending = true;
        }
    }

    /** Queue test feedback beep: 700 Hz × 100 ms × 5 (correct movement feedback). */
    public void playTestBeep() {
        if (soundEnabled) {
            testBeepPending = true;
        }
    }

    /** Stop the audio thread and release AudioTrack. Safe to call multiple times. */
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

    public boolean isSoundEnabled() {
        return soundEnabled;
    }

    public void setSoundEnabled(boolean enabled) {
        this.soundEnabled = enabled;
        if (!enabled) {
            currentVario = 0f;
        }
    }

    // ===== INTERNALS =====

    /**
     * Background audio generation loop.
     * Implements a Brauniger IQ state machine:
     *   BEEP_ON  → generate sine tone for calculated duration
     *   BEEP_OFF → generate silence for calculated duration (pause between beeps)
     *   SILENCE  → generate silence (sink / near-zero)
     *   Continuous tone when vario > 9 m/s (re-evaluates every STATE_CHECK_MS)
     */
    private void audioLoop() {
        short[] buffer = new short[BUFFER_SAMPLES];

        if (audioTrack == null) return;

        try {
            audioTrack.play();
        } catch (Exception e) {
            Log.e(TAG, "Failed to start AudioTrack playback", e);
            return;
        }

        // State machine variables
        //   samplesRemaining: how many PCM samples left in the current phase
        //   isBeepOn: true = generating tone, false = generating silence between beeps
        //   currentSineFreq: sine frequency for current tone phase (0 if silence)
        int samplesRemaining = 0;
        boolean isBeepOn = false;
        float currentSineFreq = 0f;

        while (running && audioTrack != null) {
            // ================================================================
            // 0. Test feedback beep — highest priority
            // ================================================================
            if (testBeepPending) {
                testBeepPending = false;
                phase = 0f;
                for (int beep = 0; beep < TEST_BEEP_COUNT && running && audioTrack != null; beep++) {
                    // Tone
                    int toneSamples = SAMPLE_RATE * TEST_BEEP_MS / 1000;
                    while (toneSamples > 0 && running && audioTrack != null) {
                        int count = Math.min(BUFFER_SAMPLES, toneSamples);
                        fillSine(buffer, TEST_BEEP_FREQ, count);
                        int written = audioTrack.write(buffer, 0, count);
                        if (written < 0) break;
                        toneSamples -= count;
                    }
                    // Pause (except after last)
                    if (beep < TEST_BEEP_COUNT - 1) {
                        int pauseSamples = SAMPLE_RATE * TEST_BEEP_PAUSE_MS / 1000;
                        while (pauseSamples > 0 && running && audioTrack != null) {
                            int count = Math.min(BUFFER_SAMPLES, pauseSamples);
                            java.util.Arrays.fill(buffer, 0, count, (short) 0);
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
            if (thermalBeepPending) {
                phase = 0f; // reset phase for clean beep start
                thermalBeepPending = false;

                int remaining = THERMAL_BEEP_SAMPLES;
                while (remaining > 0 && running && audioTrack != null) {
                    int count = Math.min(BUFFER_SAMPLES, remaining);
                    fillSine(buffer, THERMAL_BEEP_FREQ, count);
                    int written = audioTrack.write(buffer, 0, count);
                    if (written < 0) {
                        Log.e(TAG, "AudioTrack write error during thermal beep: " + written);
                        break;
                    }
                    remaining -= count;
                }
                // Reset state machine — will re-evaluate vario on next iteration
                samplesRemaining = 0;
                continue;
            }

            // ================================================================
            // 2. State machine — determine next phase when current one ends
            // ================================================================
            if (samplesRemaining <= 0) {
                float v = currentVario;

                if (v > VERY_FAST_THRESHOLD) {
                    // Very fast climb → continuous tone at max frequency
                    isBeepOn = true;
                    currentSineFreq = MAX_FREQ;
                    samplesRemaining = SAMPLE_RATE * STATE_CHECK_MS / 1000; // re-check vario often
                } else if (v > MIN_CLIMB_THRESHOLD) {
                    // Normal climb → alternate between beep and pause
                    if (isBeepOn) {
                        // End of beep: start pause
                        isBeepOn = false;
                        currentSineFreq = 0f;
                        int pauseMs = Math.round(map(v, MIN_CLIMB_THRESHOLD, VERY_FAST_THRESHOLD,
                                SLOW_PAUSE_MS, FAST_PAUSE_MS));
                        samplesRemaining = SAMPLE_RATE * pauseMs / 1000;
                    } else {
                        // End of pause or initial state: start beep
                        isBeepOn = true;
                        currentSineFreq = map(v, MIN_CLIMB_THRESHOLD, VERY_FAST_THRESHOLD,
                                MIN_FREQ, MAX_FREQ);
                        int toneMs = Math.round(map(v, MIN_CLIMB_THRESHOLD, VERY_FAST_THRESHOLD,
                                SLOW_TONE_MS, FAST_TONE_MS));
                        samplesRemaining = SAMPLE_RATE * toneMs / 1000;
                    }
                } else {
                    // Sink or near-zero → silence
                    isBeepOn = false;
                    currentSineFreq = 0f;
                    samplesRemaining = SAMPLE_RATE * STATE_CHECK_MS / 1000; // re-check vario often
                }
            }

            // ================================================================
            // 3. Generate PCM samples for this chunk
            // ================================================================
            int count = Math.min(BUFFER_SAMPLES, samplesRemaining);

            if (isBeepOn && currentSineFreq > 0f) {
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

    /**
     * Fill the buffer (first {@code count} elements) with a sine wave at the
     * given frequency, updating the running phase accumulator.
     */
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
     * Map a value from one numeric range to another (clamped).
     *
     * @param x      value to map
     * @param inMin  lower bound of input range
     * @param inMax  upper bound of input range
     * @param outMin lower bound of output range
     * @param outMax upper bound of output range
     * @return mapped value, clamped to [outMin, outMax]
     */
    private static float map(float x, float inMin, float inMax, float outMin, float outMax) {
        float t = (x - inMin) / (inMax - inMin);
        t = Math.max(0f, Math.min(1f, t));
        return outMin + t * (outMax - outMin);
    }
}
