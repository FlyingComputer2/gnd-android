package com.flyingcomputer.roverground;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.function.Consumer;

/** RGP4 v2; Java int channel values are checked unsigned 16-bit IDs. */
public final class Wire {
    public static final int HEADER=20, MAX_PAYLOAD=2048;
    public static final int REQUEST=1, REPLY=2, EVENT=3, DATA=4, CREDIT=5;
    public record Packet(int kind, int channel, int session, int sequence, byte[] payload) {}
    public static void checkChannel(int channel) {
        if(channel<0 || channel>65535) throw new IllegalArgumentException("channel must be u16");
    }
    public static byte[] encode(Packet p) {
        checkChannel(p.channel());
        if(p.payload().length>MAX_PAYLOAD) throw new IllegalArgumentException("oversized record");
        return ByteBuffer.allocate(HEADER+p.payload().length).order(ByteOrder.LITTLE_ENDIAN)
            .put(new byte[]{82,71,80,52,2,(byte)p.kind()}).putShort((short)0)
            .putShort((short)p.channel()).putShort((short)p.payload().length)
            .putInt(p.session()).putInt(p.sequence()).put(p.payload()).array();
    }
    public static final class Parser {
        private final byte[] buffer=new byte[HEADER+MAX_PAYLOAD];
        private int used, session;
        public void reset(int id) { used=0; session=id; }
        private static int u16(byte[] bytes,int at) {
            return (bytes[at]&255) | ((bytes[at+1]&255)<<8);
        }
        private static int u32(byte[] bytes,int at) {
            return u16(bytes,at) | (u16(bytes,at+2)<<16);
        }
        public void feed(byte[] bytes, int n, Consumer<Packet> callback) {
            for(int i=0;i<n;i++) {
                buffer[used++]=bytes[i];
                if(used<HEADER) continue;
                if(buffer[0]!=82 || buffer[1]!=71 || buffer[2]!=80 || buffer[3]!=52 || u32(buffer,12)!=session || session==0) {
                    System.arraycopy(buffer,1,buffer,0,--used); continue;
                }
                int kind=buffer[5]&255, channel=u16(buffer,8), length=u16(buffer,10);
                if(buffer[4]!=2 || kind<REQUEST || kind>CREDIT || u16(buffer,6)!=0 || length>MAX_PAYLOAD ||
                   (kind<=EVENT && (channel!=0 || length>1024)) || (kind==DATA && length==0) ||
                   (kind==CREDIT && (length!=2 || u32(buffer,16)!=0)))
                    throw new IllegalArgumentException("invalid USB record; reset session");
                if(used==HEADER+length) {
                    Packet p=new Packet(kind,channel,session,u32(buffer,16),Arrays.copyOfRange(buffer,HEADER,used));
                    used=0; callback.accept(p);
                }
            }
        }
    }
}
