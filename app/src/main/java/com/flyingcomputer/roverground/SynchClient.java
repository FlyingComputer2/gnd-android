package com.flyingcomputer.roverground;

import android.os.SystemClock;
import java.util.ArrayDeque;

/**
 * app_synch client (rtt) on USB channel 3 against the ground P4's clock, the same reference the
 * rover's clock follows. Once a second it sends sync_request{trid}; the P4 answers
 * sync_response{trid, server_time_us} (its CLOCK_REALTIME). The offset is kept against this
 * phone's elapsedRealtime, from the lowest-delay of the last eight exchanges.
 * Wire, big-endian: [u8 type][u32 trid] (+ [i64 server_time_us] in a response).
 */
public final class SynchClient implements UsbProxy.Handler, AutoCloseable {
    static final int CHANNEL = 3;
    private static final int SYNC_REQUEST = 1, SYNC_RESPONSE = 2, WINDOW = 8;
    private static final long PERIOD_MS = 1000;
    private record Sample(long offsetUs, long delayUs) {}
    private final ArrayDeque<Sample> window = new ArrayDeque<>();
    private final Thread thread;
    private volatile boolean running = true;
    private volatile UsbProxy.Channel channel;
    private int trid;
    private long sentTrid = -1, sentUs;
    private Sample best;
    private long requests, replies;

    public SynchClient() {
        thread = new Thread(this::run, "synch-client");
        thread.start();
    }

    /** Monotonic µs this client measures against. */
    public static long nowUs() { return SystemClock.elapsedRealtimeNanos() / 1000; }

    /** Server CLOCK_REALTIME µs now, or Long.MIN_VALUE before the first reply. */
    public synchronized long serverNowUs() {
        return best == null ? Long.MIN_VALUE : nowUs() + best.offsetUs;
    }

    public synchronized long delayUs() { return best == null ? -1 : best.delayUs; }

    @Override public String format() { return "synch-v1"; }
    @Override public void onOpen(UsbProxy.Channel sender) { channel = sender; }

    @Override public void onPacket(UsbProxy.Lease packet, UsbProxy.Channel sender) {
        try {
            final long t4 = nowUs();
            byte[] p = packet.payload;
            if (p.length != 13 || p[0] != SYNC_RESPONSE) return;
            long id = u32(p, 1);
            long server = 0;
            for (int i = 5; i < 13; i++) server = (server << 8) | (p[i] & 0xff);
            synchronized (this) {
                if (id != sentTrid) return; // late or duplicate
                sentTrid = -1;
                long delay = t4 - sentUs;
                window.addLast(new Sample(server - (sentUs + delay / 2), delay));
                while (window.size() > WINDOW) window.removeFirst();
                Sample b = null;
                for (Sample s : window) if (b == null || s.delayUs < b.delayUs) b = s;
                best = b;
                replies++;
            }
        } finally {
            packet.close();
        }
    }

    private void run() {
        while (running) {
            UsbProxy.Channel c = channel;
            if (c != null) {
                byte[] req = new byte[5];
                synchronized (this) {
                    trid++;
                    req[0] = SYNC_REQUEST;
                    req[1] = (byte) (trid >>> 24); req[2] = (byte) (trid >>> 16);
                    req[3] = (byte) (trid >>> 8); req[4] = (byte) trid;
                    sentTrid = trid & 0xffffffffL;
                    sentUs = nowUs();
                    requests++;
                }
                c.trySend(req);
            }
            try { Thread.sleep(PERIOD_MS); } catch (InterruptedException e) { return; }
        }
    }

    private static long u32(byte[] p, int off) {
        return ((p[off] & 0xffL) << 24) | ((p[off + 1] & 0xffL) << 16) | ((p[off + 2] & 0xffL) << 8)
            | (p[off + 3] & 0xffL);
    }

    @Override public void close() {
        running = false;
        thread.interrupt();
        try { thread.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
