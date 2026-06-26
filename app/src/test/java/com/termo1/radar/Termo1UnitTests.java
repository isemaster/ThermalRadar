package com.termo1.radar;

import com.termo1.radar.core.SimulationManager;
import com.termo1.radar.flight.WindDriftCalculator;
import com.termo1.radar.flight.WindDriftCalculator.WindCorrected;
import com.termo1.radar.flight.WindStore;
import com.termo1.radar.flight.WindStore.WindMeasurement;
import com.termo1.radar.flight.CirclingManager;
import com.termo1.radar.model.ThermalBlip;
import com.termo1.radar.sensors.HeadingFilter;

import org.junit.Test;
import static org.junit.Assert.*;

public class Termo1UnitTests {

    private static final float DELTA = 0.01f;
    private static final float ALT_DELTA = 0.05f;

    // ================================================
    // SimulationManager Tests
    // ================================================

    @Test
    public void sim_initialState() {
        SimulationManager sm = new SimulationManager();
        sm.start();
        assertTrue("Sim should be running", sm.isRunning());
        assertEquals("Initial heading 0 (north)", 0f, sm.getHeading(), DELTA);
        assertEquals("Initial altitude 500m", 500f, sm.getAltitudeMsl(), ALT_DELTA);
    }

    @Test
    public void sim_runs75seconds() {
        SimulationManager sm = new SimulationManager();
        sm.start();
        for (int ms = 0; ms <= 75000; ms += 20) {
            sm.update(ms);
        }
        assertFalse("Sim should end after 75s", sm.isRunning());
    }

    @Test
    public void sim_headingChangesDuringTimeline() {
        SimulationManager sm = new SimulationManager();
        sm.start();

        sm.update(0);
        assertEquals("T=0: heading 0", 0f, sm.getHeading(), 5f);

        sm.update(5000);
        assertEquals("T=5: heading near 0", 0f, sm.getHeading(), 5f);

        sm.update(30000);
        float h30 = sm.getHeading();
        assertTrue("T=30: heading turning 0..90 (got " + h30 + ")", h30 > 0f && h30 < 100f);

        sm.update(40000);
        assertEquals("T=40: heading near 90", 90f, sm.getHeading(), 10f);

        sm.update(50000);
        assertEquals("T=50: heading near 90", 90f, sm.getHeading(), 10f);

        sm.update(74000);
        assertEquals("T=74: heading near 0", 0f, sm.getHeading(), 10f);
    }

    @Test
    public void sim_accelValuesProduced() {
        SimulationManager sm = new SimulationManager();
        sm.start();
        for (int ms = 0; ms <= 60000; ms += 20) {
            sm.update(ms);
            float ax = sm.getAccelX();
            float ay = sm.getAccelY();
            assertFalse("AccelX should be finite", Float.isNaN(ax));
            assertFalse("AccelY should be finite", Float.isNaN(ay));
            assertFalse("AccelX should not be infinite", Float.isInfinite(ax));
            assertFalse("AccelY should not be infinite", Float.isInfinite(ay));
        }
    }

    @Test
    public void sim_varioGenerated() {
        SimulationManager sm = new SimulationManager();
        sm.start();

        sm.update(0);
        assertEquals("T=0: vario -0.5", -0.5f, sm.getVario(), DELTA);

        sm.update(10000);
        assertTrue("T=10: vario positive", sm.getVario() > 0f);

        sm.update(20000);
        assertTrue("T=20: vario ~1.0", sm.getVario() > 0.5f);

        sm.update(74999);
        assertTrue("T=74.999: vario near 0", sm.getVario() < 0.2f);
    }

    @Test
    public void sim_altitudeStarts500() {
        SimulationManager sm = new SimulationManager();
        sm.start();
        sm.update(0);
        assertEquals("MSL starts at 500m", 500f, sm.getAltitudeMsl(), ALT_DELTA);
        assertEquals("AGL starts at 0m", 0f, sm.getAltitudeAgl(), ALT_DELTA);
    }

    @Test
    public void sim_stressNoExceptions() {
        SimulationManager sm = new SimulationManager();
        sm.start();
        for (int ms = 0; ms <= 76000; ms += 10) {
            sm.update(ms);
            sm.getHeading();
            sm.getVario();
            sm.getSpeed();
            sm.getPressure();
            sm.getAltitudeMsl();
            sm.getAltitudeAgl();
            sm.getSnr();
            sm.getAccelX();
            sm.getAccelY();
        }
        assertFalse("Sim ended after 75s", sm.isRunning());
    }

    // ================================================
    // ThermalBlip Tests
    // ================================================

    @Test
    public void blip_hasLimitedLifetime() {
        ThermalBlip b = new ThermalBlip(0f, 5f, 50f, "test", 0L);
        assertTrue("Alive at birth", b.isAlive(0));
        assertTrue("Alive at 6s", b.isAlive(6000));
        assertFalse("Dead after 7s", b.isAlive(7000));
    }

    @Test
    public void blip_strengthCapped() {
        ThermalBlip b = new ThermalBlip(0f, 12f, 50f, "test", 0L);
        assertEquals("Strength capped at 8", 8f, b.strength, DELTA);
    }

    @Test
    public void blip_brightnessDecreases() {
        ThermalBlip b = new ThermalBlip(0f, 5f, 50f, "test", 0L);
        assertTrue("Brightness 1.0 at birth", b.getBrightness(0) > 0.99f);
        assertTrue("Brightness decreases", b.getBrightness(3000) < b.getBrightness(0));
        assertEquals("Brightness 0 past lifespan", 0f, b.getBrightness(7000), DELTA);
    }

    // ================================================
    // WC-1/2/3: WindDriftCalculator sign fix
    // ================================================

    @Test
    public void wc1_windFromNorth_desiredTrackEast() {
        // Ветер с севера (180°), летим на восток (90°), воздушная скорость 10 м/с, ветер 5 м/с
        WindCorrected r = WindDriftCalculator.calculate(90f, 10f, 180f, 5f);
        // WCA ≈ -30° => heading ≈ 60° (нос влево), НЕ 120° (как было до фикса WC-1)
        assertEquals("Heading should compensate for north wind", 60f, r.heading, 5f);
        assertTrue("Drift angle should be positive (drift right)", r.driftAngle > 0);
    }

    @Test
    public void wc2_headwind_reducesGroundSpeed() {
        // Встречный ветер 10 м/с, воздушная скорость 10 м/с → ground speed ≈ 0
        WindCorrected r = WindDriftCalculator.calculate(0f, 10f, 180f, 10f);
        assertTrue("Ground speed should be reduced by headwind", r.groundSpeed < 5f);
        assertTrue("Ground speed should be positive", r.groundSpeed > 0f);
    }

    @Test
    public void wc4_wcaCappedAt45Deg() {
        // Очень сильный боковой ветер: 15 м/с при 10 м/с воздушной
        WindCorrected r = WindDriftCalculator.calculate(90f, 10f, 180f, 15f);
        // WCA не должен превышать 45° (WC-4 fix)
        assertTrue("WCA should be capped at ~45°", Math.abs(r.driftAngle) <= 50f);
        assertTrue("DriftPerKm should be finite", r.driftPerKm > 0);
    }

    // ================================================
    // HF-1: HeadingFilter median does not corrupt buffer
    // ================================================

    @Test
    public void hf1_medianDoesNotCorruptBuffer() {
        HeadingFilter f = new HeadingFilter();
        // Заполняем 5 обновлениями (окно медианы = 5)
        f.update(10, 0);
        f.update(20, 100);
        f.update(30, 200);
        f.update(40, 300);
        f.update(50, 400);
        double m1 = f.getLastOutput();
        // 6-е обновление сдвигает окно FIFO: [10,20,30,40,50] → [20,30,40,50,35]
        f.update(35, 500);
        double m2 = f.getLastOutput();
        // После фикса HF-1 буфер не сортируется in-place
        // Медиана [20,30,35,40,50] = 35 (а не 30 если бы буфер был [10,20,30,40,50] и упал 10)
        assertEquals("Median after FIFO shift should be 35", 35.0, m2, 1.0);
    }

    // ================================================
    // WS-1: WindStore vector averaging wraparound
    // ================================================

    @Test
    public void ws1_avgBearing_wraparound() {
        WindStore ws = new WindStore();
        long now = System.currentTimeMillis();
        // 350° + 10° → среднее должно быть 0° (север), НЕ 180° (юг)
        ws.addMeasurement(350f, 5f, 1, 100f, now);
        ws.addMeasurement(10f, 5f, 1, 100f, now + 100);
        WindMeasurement r = ws.getWindAt(100f, now + 200);
        assertNotNull("Wind measurement should exist", r);
        // Векторное среднее 350°+10° → ~0°
        float bearing = r != null ? r.bearing : -1f;
        assertTrue("Average of 350°+10° should be ~0° (got " + bearing + "°)",
                bearing < 20f || bearing > 340f);
    }

    // ================================================
    // TR-3: TrackReplayer skips invalid V-fixes
    // ================================================

    @Test
    public void tr3_loadFromIGC_skipsInvalidFix() {
        // Создаём трек реплеер, парсим IGC с V-фиксом (невалидный)
        com.termo1.radar.core.TrackReplayer tr = new com.termo1.radar.core.TrackReplayer();
        String igc = "B1000005925380N02930380E V 00000000000\n"
                   + "B1000015925381N02930381E A 00100001000\n"
                   + "B1000025925382N02930382E A 00200002000\n";
        java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(igc.getBytes());
        // loadFile возвращает false для битых файлов, но мы проверяем через loadFromIGC
        // Для теста используем рефлексию или проверяем getTrack() после loadFile
        String tmpFile = System.getProperty("java.io.tmpdir") + "/test_tr3.igc";
        try {
            java.nio.file.Files.write(java.nio.file.Paths.get(tmpFile), igc.getBytes());
            boolean ok = tr.loadFile(tmpFile);
            assertTrue("loadFile should succeed with 2 valid records", ok);
            java.util.List<?> track = tr.getTrack();
            assertNotNull("Track should not be null", track);
            // Должно быть 2 валидные точки (V-фикс пропущен)
            assertEquals("Only 2 valid B-records (V-fix skipped)", 2, track.size());
        } catch (Exception e) {
            fail("Exception: " + e.getMessage());
        } finally {
            new java.io.File(tmpFile).delete();
        }
    }

    // ================================================
    // CM-1: CirclingManager.estimateWindFromGps direction
    // ================================================

    @Test
    public void cm1_estimateWindFromGps_matchesMeteoConvention() {
        CirclingManager cm = new CirclingManager();
        // Летим на север (0°), heading=0°, track=0°, airspeed=10 м/с, gpsSpeed=5 м/с
        // Ветер встречный 5 м/с → по meteo convention должен быть 180° (дует с юга)
        cm.estimateWindFromGps(0f, 0f, 5f, 10f);
        float windFrom = cm.getWindFromDeg();
        assertTrue("Wind should be from south (~180°) with headwind (got " + windFrom + "°)",
                windFrom > 150f && windFrom < 210f);
        assertTrue("Wind speed should be ~5 m/s", cm.getWindSpeedMs() > 3f && cm.getWindSpeedMs() < 7f);
    }
}
