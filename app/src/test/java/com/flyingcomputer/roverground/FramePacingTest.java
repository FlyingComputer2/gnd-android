package com.flyingcomputer.roverground;

import org.junit.Test;
import static org.junit.Assert.*;

public class FramePacingTest {
    @Test public void steadyAndUnevenIntervals() {
        FramePacing p = new FramePacing(0);
        assertEquals(-1, p.errorMs(0), 0);
        p.frame(0);
        p.frame(20_000_000);
        p.frame(60_000_000);
        assertEquals(10, p.errorMs(60_000_000), 1e-9);
        // Expire both old intervals; a steady cadence then has zero jitter.
        p.frame(6_000_000_000L);
        p.frame(6_030_000_000L);
        p.frame(11_010_000_000L);
        // The 5940 ms and 30 ms intervals have expired at 11.04 s.
        assertEquals(0, p.errorMs(11_040_000_000L), 1e-9);
        assertEquals(-1, p.errorMs(17_000_000_000L), 0);
    }

    @Test public void shortfallUsesRollingWindowAndDecaysOnStall() {
        FramePacing p = new FramePacing(0);
        for (int i=1; i<=140; i++) p.frame(i*5_000_000_000L/140);
        assertEquals(2, p.decodeErrorFps(5_000_000_000L, 30), 1e-9);
        assertEquals(30, p.decodeErrorFps(10_000_000_000L, 30), 1e-9);
        // A new instance has no history from the old stream.
        FramePacing fresh = new FramePacing(10_000_000_000L);
        assertEquals(-1, fresh.errorMs(10_000_000_000L), 0);
        for (int i=1; i<=40; i++) fresh.frame(10_000_000_000L+i*25_000_000L);
        assertEquals(0, fresh.decodeErrorFps(11_000_000_000L, 30), 0);
    }
}
