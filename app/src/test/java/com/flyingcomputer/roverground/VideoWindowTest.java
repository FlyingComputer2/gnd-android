package com.flyingcomputer.roverground;

import org.junit.Test;
import static org.junit.Assert.*;

public class VideoWindowTest {
    @Test public void fourLineOsd() {
        var w = new VideoReceiver.Window(6000,80,28,28,140,1000,99.1,28,"",1.2,2);
        assertEquals("frame 28.0 fps\nerror 2.0 fps\njitter 1.2 ms\nlatency 99.1 ms",w.osd());
    }
    @Test public void unknownTiming() {
        var w = new VideoReceiver.Window(0,-1,0,0,0,0,-1,0,"",-1,30);
        assertEquals("frame 0.0 fps\nerror 30.0 fps\njitter --- ms\nlatency --- ms",w.osd());
    }
}
