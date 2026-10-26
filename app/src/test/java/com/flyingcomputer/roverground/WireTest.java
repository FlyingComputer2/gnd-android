package com.flyingcomputer.roverground;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class WireTest {
    private static byte[] unhex(String text) {
        byte[] out=new byte[text.length()/2];
        for(int i=0;i<out.length;i++) out[i]=(byte)Integer.parseInt(text.substring(i*2,i*2+2),16);
        return out;
    }
    @Test public void goldenAndEverySplit() {
        int session=0x01020304;
        for(int channel:new int[]{0,1,2,255,256,32767,32768,65535}) {
            byte[] bytes=Wire.encode(new Wire.Packet(Wire.DATA,channel,session,0x11223344,new byte[]{1,2,3}));
            if(channel==65535) assertArrayEquals(unhex("5247503402040000ffff03000403020144332211010203"),bytes);
            for(int split=0;split<=bytes.length;split++) {
                Wire.Parser p=new Wire.Parser(); p.reset(session);
                List<Wire.Packet> got=new ArrayList<>();
                p.feed(Arrays.copyOfRange(bytes,0,split),split,got::add);
                byte[] rest=Arrays.copyOfRange(bytes,split,bytes.length); p.feed(rest,rest.length,got::add);
                assertEquals(1,got.size()); assertEquals(channel,got.get(0).channel());
                assertArrayEquals(new byte[]{1,2,3},got.get(0).payload());
            }
        }
    }
    @Test public void zeroIsVideoAndStaleSessionIsIgnored() {
        Wire.Parser p=new Wire.Parser(); p.reset(2); List<Wire.Packet> got=new ArrayList<>();
        byte[] old=Wire.encode(new Wire.Packet(Wire.DATA,0,1,1,new byte[]{7}));
        p.feed(old,old.length,got::add); assertTrue(got.isEmpty());
        byte[] video=Wire.encode(new Wire.Packet(Wire.DATA,0,2,1,new byte[]{8}));
        byte[] reply=Wire.encode(new Wire.Packet(Wire.REPLY,0,2,1,new byte[]{123,125}));
        p.feed(video,video.length,got::add); p.feed(reply,reply.length,got::add);
        assertEquals(Wire.DATA,got.get(0).kind()); assertEquals(Wire.REPLY,got.get(1).kind());
    }
    @Test(expected=IllegalArgumentException.class) public void invalidChannelFails() { Wire.checkChannel(65536); }
    @Test(expected=IllegalArgumentException.class) public void malformedLengthFails() {
        Wire.Parser p=new Wire.Parser(); p.reset(1);
        byte[] wire=Wire.encode(new Wire.Packet(Wire.DATA,0,1,1,new byte[]{1}));
        wire[10]=(byte)255; wire[11]=(byte)255; p.feed(wire,wire.length,r->{});
    }
}
