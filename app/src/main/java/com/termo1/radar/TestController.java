package com.termo1.radar;

import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.termo1.radar.core.SignalProcessor;
import com.termo1.radar.core.ThermalDetector;
import com.termo1.radar.sensors.SensorController;

/**
 * TestController — тестовый режим проверки сенсоров (6 шагов).
 * Калибровка, оси X/Y, поворот, спираль, тряска.
 */
public class TestController {

    public interface Callback {
        void onTestInstruction(String title, String line1, String line2);
        void onTestComplete();
        void onTestFeedback(String text, int color);
    }

    private static final float TEST_AMP_THRESHOLD = 0.05f;
    private static final float TEST_STRONG_THRESHOLD = 0.15f;
    private static final float TEST_AXIS_RATIO = 2.5f;
    private static final float TEST_HEADING_RANGE = 50f;
    private static final float TEST_FAST_RMS = 0.3f;
    private static final int TEST_MAX_STEP_MS = 60000;
    private static final int TEST_CORRECT_WINDOW_MS = 1500;
    private static final int TEST_WINDOW_FRAMES = 45;
    private static final int TEST_CORRECT_RATIO = 4;
    private static final int HEADING_WINDOW_FRAMES = 150;

    private boolean testMode;
    private int testStep;
    private String testInstruction = "";
    private String testFeedback = "";
    private int testFeedbackColor;
    private boolean testStepCorrect;
    private long testLastBeepMs;
    private long testCorrectStartMs;
    private long testStepStartMs;
    private boolean testBeepPlaying;
    private int testCorrectCount;

    private final boolean[] testWindowBuffer = new boolean[TEST_WINDOW_FRAMES];
    private int testWindowIdx;
    private int testWindowFill;
    private final float[] headingWindow = new float[HEADING_WINDOW_FRAMES];
    private int headingWindowIdx;
    private int headingWindowFill;

    private final Handler testHandler = new Handler(Looper.getMainLooper());
    private Runnable testTask;
    private final Callback callback;

    public TestController(Callback callback) {
        this.callback = callback;
    }

    public boolean isTestMode() { return testMode; }
    public int getTestStep() { return testStep; }
    public String getTestInstruction() { return testInstruction; }
    public String getTestFeedback() { return testFeedback; }
    public int getTestFeedbackColor() { return testFeedbackColor; }
    public boolean isTestStepCorrect() { return testStepCorrect; }
    public long getTestLastBeepMs() { return testLastBeepMs; }
    public long getTestCorrectStartMs() { return testCorrectStartMs; }

    public void start() {
        testMode = true;
        testStep = 0;
        testStepStartMs = System.currentTimeMillis();
        testStepCorrect = false;
        testFeedback = "";
        testFeedbackColor = Color.argb(255, 255, 255, 255);
        testCorrectStartMs = 0;
        testLastBeepMs = 0;
        nextStep();
    }

    public void stop() {
        testMode = false;
        if (testTask != null) testHandler.removeCallbacks(testTask);
    }

    public void update(SignalProcessor sp, float compassHeading) {
        if (!testMode || testStepCorrect || sp == null) return;

        float hpX = sp.getBpX();
        float hpY = sp.getBpY();
        float level = sp.getTurbulenceLevel();
        float levelMs2 = level * 9.81f;
        long now = System.currentTimeMillis();
        long stepElapsed = now - testStepStartMs;

        if (stepElapsed > TEST_MAX_STEP_MS) {
            testFeedbackColor = Color.argb(255, 255, 100, 100);
            testFeedback = "Время вышло. Переход...";
            nextStep();
            return;
        }

        updateHeadingWindow(compassHeading);
        boolean correct = false;

        switch (testStep) {
            case 1: correct = level < TEST_AMP_THRESHOLD; break;
            case 2: correct = Math.abs(hpX) > Math.abs(hpY) * TEST_AXIS_RATIO && level > TEST_AMP_THRESHOLD; break;
            case 3: correct = Math.abs(hpY) > Math.abs(hpX) * TEST_AXIS_RATIO && level > TEST_AMP_THRESHOLD; break;
            case 4: correct = getHeadingRange() > TEST_HEADING_RANGE; break;
            case 5: correct = level > TEST_AMP_THRESHOLD; break;
            case 6: correct = level > TEST_FAST_RMS; break;
        }

        testWindowBuffer[testWindowIdx] = correct;
        testWindowIdx = (testWindowIdx + 1) % TEST_WINDOW_FRAMES;
        if (testWindowFill < TEST_WINDOW_FRAMES) testWindowFill++;

        // Check if step is passing
        float ratio = getTestCorrectRatio();
        if (ratio >= 0.4f && testCorrectCount < TEST_CORRECT_RATIO) {
            testCorrectCount++;
            if (testCorrectCount >= TEST_CORRECT_RATIO && !testStepCorrect) {
                testStepCorrect = true;
                testFeedbackColor = Color.argb(255, 100, 255, 100);
                testFeedback = "Верно!";
                testCorrectStartMs = now;
                new Handler(Looper.getMainLooper()).postDelayed(this::nextStep, 800);
            }
        }

        testFeedback = String.format("ур %+.3f | bpX %+.3f bpY %+.3f | пр %d%%",
                levelMs2, hpX, hpY, (int)(ratio * 100));
        testFeedbackColor = Color.argb(255, 255, 255, 255);
    }

    private void nextStep() {
        testStep++;
        testStepStartMs = System.currentTimeMillis();
        testCorrectCount = 0;
        testWindowIdx = 0;
        testWindowFill = 0;
        java.util.Arrays.fill(testWindowBuffer, false);
        headingWindowIdx = 0;
        headingWindowFill = 0;
        java.util.Arrays.fill(headingWindow, 0f);
        testStepCorrect = false;
        testCorrectStartMs = 0;
        testFeedback = "";

        String title, line1, line2;
        switch (testStep) {
            case 1: title="ШАГ 1/6: КАЛИБРОВКА"; line1="Положите телефон НЕПОДВИЖНО"; line2="Датчики калибруются... 5 секунд"; break;
            case 2: title="ШАГ 2/6: ОСЬ X (ВЛЕВО-ВПРАВО)"; line1="Качайте ВЛЕВО-ВПРАВО ±3-5см"; line2="Частота: 1-2 Гц"; break;
            case 3: title="ШАГ 3/6: ОСЬ Y (ВПЕРЁД-НАЗАД)"; line1="Качайте ВПЕРЁД-НАЗАД ±3-5см"; line2="Частота: 1-2 Гц"; break;
            case 4: title="ШАГ 4/6: ПОВОРОТ (YAW)"; line1="Поверните на 90° ВЛЕВО"; line2="Затем обратно — проверка компаса"; break;
            case 5: title="ШАГ 5/6: СПИРАЛЬ"; line1="Крутите плавно на 360°"; line2="Крен ±30° | 1 оборот за 5-7с"; break;
            case 6: title="ШАГ 6/6: ТРЯСКА"; line1="Трясите БЫСТРО"; line2="Частота: 3-5 Гц | ±5см"; break;
            default:
                testMode = false;
                if (callback != null) callback.onTestComplete();
                return;
        }
        if (callback != null) callback.onTestInstruction(title, line1, line2);
    }

    private float getTestCorrectRatio() {
        if (testWindowFill == 0) return 0f;
        int count = 0;
        for (int i = 0; i < testWindowFill; i++) {
            if (testWindowBuffer[i]) count++;
        }
        return (float) count / testWindowFill;
    }

    private void updateHeadingWindow(float heading) {
        headingWindow[headingWindowIdx] = heading;
        headingWindowIdx = (headingWindowIdx + 1) % HEADING_WINDOW_FRAMES;
        if (headingWindowFill < HEADING_WINDOW_FRAMES) headingWindowFill++;
    }

    private float getHeadingRange() {
        if (headingWindowFill < 10) return 0f;
        float[] hdgs = new float[headingWindowFill];
        System.arraycopy(headingWindow, 0, hdgs, 0, headingWindowFill);
        java.util.Arrays.sort(hdgs);
        float maxGap = hdgs[0] + 360f - hdgs[hdgs.length - 1];
        for (int i = 1; i < hdgs.length; i++) {
            float gap = hdgs[i] - hdgs[i - 1];
            if (gap > maxGap) maxGap = gap;
        }
        return 360f - maxGap;
    }
}
