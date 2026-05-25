package com.termo1.radar;

import com.termo1.radar.core.SimulationManager;
import com.termo1.radar.model.ThermalBlip;

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
            // Read all values — should never throw
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
    public void blip_lifeLeft() {
        ThermalBlip b = new ThermalBlip(0f, 5f, 50f, "test", 0L);
        assertTrue("Life left positive at birth", b.lifeLeft(0) > 0.9f);
        assertTrue("Life left decreases over time", b.lifeLeft(1000) < b.lifeLeft(0));
        assertTrue("Past lifespan returns negative", b.lifeLeft(8000) < 0f);
    }
}
