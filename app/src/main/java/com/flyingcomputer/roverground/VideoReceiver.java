package com.flyingcomputer.roverground;

import android.media.*;
import android.os.Build;
import android.view.Surface;
import android.view.SurfaceView;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Recovered RTP/H.264 receiver -> Android H.264 decoder -> Surface.
 *
 * Also measures, for the OSD, the channel-0 bitrate, the decoder's output frame rate and
 * camera-to-decode latency: the rover's packer stamps each frame's capture instant as
 * CLOCK_REALTIME (RTP header extension id 2), and latency is the ground P4's clock (via
 * SynchClient) when the decoder hands out the frame, minus that stamp. On API 34+ the app
 * presents frames itself (ScreenPresenter), and screen latency takes the same difference at the
 * time the display showed the frame (the transaction's present fence).
 */
public final class VideoReceiver implements UsbProxy.Handler, AutoCloseable {
    static { System.loadLibrary("rover_receiver"); }
    private final MediaCodec decoder;
    private final Thread output;
    private volatile boolean running=true;
    private long nativeHandle;
    private boolean needKey=true;
    private long lastPts;
    private final SynchClient clock;
    // Capture CLOCK_REALTIME ns by RTP timestamp, for the AUs being assembled.
    private final LinkedHashMap<Long,Long> captureByRtpTs=new LinkedHashMap<>(64,0.75f,false) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long,Long> e) { return size()>64; }
    };
    private long packetTs=-1, previousTs=-1;
    private boolean packetMarker;
    // One frame's capture time and its stage stamps (System.nanoTime()), for the log's
    // per-stage latency: AU complete (its last USB packet), queued to the decoder, decoded.
    private static final class Frame {
        final long captureNs, auNs; long queuedNs, decodedNs;
        Frame(long captureNs,long auNs) { this.captureNs=captureNs; this.auNs=auNs; }
    }
    // Frames by the PTS the AU was queued with, until the decoder outputs them.
    private final ConcurrentHashMap<Long,Frame> captureByPts=new ConcurrentHashMap<>();
    // Decoded frames by PTS, until the display presents them (API 34+).
    private final ConcurrentHashMap<Long,Frame> captureByScreenPts=new ConcurrentHashMap<>();
    // Stage sums for the window, µs: capture -> AU complete (P4 clock), AU -> queued,
    // queued -> decoded, decoded -> presented.
    private final AtomicLong arrivalUs=new AtomicLong(), arrivalN=new AtomicLong();
    private final AtomicLong queueUs=new AtomicLong(), decodeUs=new AtomicLong(), stageN=new AtomicLong();
    private final AtomicLong presentUs=new AtomicLong(), presentN=new AtomicLong();
    private final ScreenPresenter presenter;
    private final AtomicLong screenSumUs=new AtomicLong(), screenCount=new AtomicLong();
    private final AtomicLong bytes=new AtomicLong(), frames=new AtomicLong();
    private final AtomicLong latencySumUs=new AtomicLong(), latencyCount=new AtomicLong();
    private long statsAtNs=System.nanoTime(), statsBytes, statsFrames;
    public VideoReceiver(SurfaceView view,SynchClient clock) throws Exception {
        this.clock=clock;
        Surface surface;
        if(Build.VERSION.SDK_INT>=34) {
            presenter=new ScreenPresenter(view.getSurfaceControl(),view.getWidth(),view.getHeight(),this::onPresented);
            surface=presenter.surface();
        } else {
            presenter=null;
            surface=view.getHolder().getSurface();
        }
        MediaFormat format=MediaFormat.createVideoFormat("video/avc",1920,1080);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,4*1024*1024);
        if(Build.VERSION.SDK_INT>=30) format.setInteger(MediaFormat.KEY_LOW_LATENCY,1);
        // Realtime priority and a high operating rate (within the decoder's 1080p performance
        // point) keep the decoder clocked up; at the 30 fps it is fed, it otherwise took 12-20 ms
        // per frame instead of a few.
        format.setInteger(MediaFormat.KEY_PRIORITY,0);
        format.setInteger(MediaFormat.KEY_OPERATING_RATE,240);
        decoder=MediaCodec.createDecoderByType("video/avc");
        decoder.configure(format,surface,null,0); decoder.start();
        nativeHandle=nativeCreate();
        if(nativeHandle==0) throw new IllegalStateException("native receiver allocation failed");
        output=new Thread(()->{
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            while(running) {
                try {
                    int index=decoder.dequeueOutputBuffer(info,10000);
                    if(index>=0) { onDecoded(info.presentationTimeUs); decoder.releaseOutputBuffer(index,true); }
                }
                catch(IllegalStateException e) { if(running) android.util.Log.e("RoverVideo","decoder output",e); break; }
            }
        },"video-display");
        output.start();
    }
    @Override public String format() { return "rtp-h264-v1"; }
    @Override public synchronized void onPacket(UsbProxy.Lease packet,UsbProxy.Channel sender) {
        try {
            if(running && nativeHandle!=0) {
                bytes.addAndGet(packet.payload.length);
                noteRtp(packet.payload);
                nativeFeed(nativeHandle,packet.payload);
            }
        }
        finally { packet.close(); }
    }
    // RTP timestamp, marker and capture extension (one-byte header 0xBEDE, id 2, 8-byte BE ns).
    private void noteRtp(byte[] p) {
        if(p.length<12) return;
        long ts=((p[4]&0xffL)<<24)|((p[5]&0xffL)<<16)|((p[6]&0xffL)<<8)|(p[7]&0xffL);
        if(ts!=packetTs) { previousTs=packetTs; packetTs=ts; }
        packetMarker=(p[1]&0x80)!=0;
        int off=12+4*(p[0]&0x0f);
        if((p[0]&0x10)==0 || p.length<off+13) return;
        if((p[off]&0xff)!=0xbe || (p[off+1]&0xff)!=0xde) return;
        int id=(p[off+4]&0xff)>>4, len=(p[off+4]&0x0f)+1;
        if(id!=2 || len!=8) return;
        long ns=0;
        for(int i=0;i<8;i++) ns=(ns<<8)|(p[off+5+i]&0xff);
        if(ns>0) captureByRtpTs.put(ts,ns);
    }
    // The depayloader finishes an AU on its marker packet, or on the first packet of the next
    // AU when the marker was lost.
    private long completedAuTs() { return packetMarker ? packetTs : previousTs; }
    private void onDecoded(long pts) {
        frames.incrementAndGet();
        Frame frame=captureByPts.remove(pts);
        long server=clock.serverNowUs();
        if(frame==null) return;
        frame.decodedNs=System.nanoTime();
        queueUs.addAndGet((frame.queuedNs-frame.auNs)/1000);
        decodeUs.addAndGet((frame.decodedNs-frame.queuedNs)/1000);
        stageN.incrementAndGet();
        if(presenter!=null) {
            if(captureByScreenPts.size()>64) captureByScreenPts.clear(); // frames never presented
            captureByScreenPts.put(pts,frame);
        }
        if(server==Long.MIN_VALUE) return;
        long us=server-frame.captureNs/1000;
        if(us<0 || us>10_000_000) return;
        latencySumUs.addAndGet(us); latencyCount.incrementAndGet();
    }
    // presentNs: when the display showed the frame (System.nanoTime() domain), mapped onto the
    // ground P4's clock through the current offset.
    private void onPresented(long ptsUs,long presentNs) {
        Frame frame=captureByScreenPts.remove(ptsUs);
        long server=clock.serverNowUs();
        if(frame==null) return;
        presentUs.addAndGet((presentNs-frame.decodedNs)/1000); presentN.incrementAndGet();
        if(server==Long.MIN_VALUE) return;
        long us=server-(System.nanoTime()-presentNs)/1000-frame.captureNs/1000;
        if(us<0 || us>10_000_000) return;
        screenSumUs.addAndGet(us); screenCount.incrementAndGet();
    }
    /** One stats window: the OSD's numbers and counters. latencyMs / screenMs are -1 without data. */
    public record Window(long kbps,double latencyMs,double fps,long latencyFrames,long frames,long bytes,
                         double screenMs,long screenFrames,String stages) {
        /** OSD text: "<kbps> kbps, <fps> fps, <camera-to-screen> ms" ("---" until known). */
        public String osd() {
            String glass=screenMs>=0 ? String.format(Locale.US,"%.1f",screenMs) : "---";
            return String.format(Locale.US,"%d kbps, %.1f fps, %s ms",kbps,fps,glass);
        }
        /** key=value log line, in place of screenshots. */
        public String log(SynchClient clock) {
            return String.format(Locale.US,"kbps=%d latency_ms=%.3f screen_latency_ms=%.3f fps=%.2f latency_frames=%d screen_frames=%d frames=%d bytes=%d sync_delay_us=%d %s",
                kbps,latencyMs,screenMs,fps,latencyFrames,screenFrames,frames,bytes,clock.delayUs(),stages);
        }
    }
    /** Closes the window since the previous call. */
    public synchronized Window window() {
        long now=System.nanoTime(), b=bytes.get(), f=frames.get();
        double s=Math.max(1e-3,(now-statsAtNs)/1e9);
        long kbps=Math.round((b-statsBytes)*8/1000.0/s);
        double fps=(f-statsFrames)/s;
        long n=latencyCount.getAndSet(0), sum=latencySumUs.getAndSet(0);
        long sn=screenCount.getAndSet(0), ssum=screenSumUs.getAndSet(0);
        long an=arrivalN.getAndSet(0), a=arrivalUs.getAndSet(0);
        long qn=stageN.getAndSet(0), q=queueUs.getAndSet(0), d=decodeUs.getAndSet(0);
        long pn=presentN.getAndSet(0), p=presentUs.getAndSet(0);
        String stages=String.format(Locale.US,"arrival_ms=%.1f queue_ms=%.1f decode_ms=%.1f present_ms=%.1f",
            an>0 ? a/1000.0/an : -1, qn>0 ? q/1000.0/qn : -1, qn>0 ? d/1000.0/qn : -1, pn>0 ? p/1000.0/pn : -1);
        statsAtNs=now; statsBytes=b; statsFrames=f;
        return new Window(kbps,n>0 ? sum/1000.0/n : -1,fps,n,f,b,sn>0 ? ssum/1000.0/sn : -1,sn,stages);
    }
    // Called on the serialized native feed path. Never on the USB reader.
    private void onAccessUnit(byte[] annexB,long pts,boolean key) {
        final long auNs=System.nanoTime();
        if(needKey && !key) return;
        int index=decoder.dequeueInputBuffer(10000);
        if(index<0) { needKey=true; return; }
        ByteBuffer input=decoder.getInputBuffer(index);
        if(input==null || input.capacity()<annexB.length) {
            decoder.queueInputBuffer(index,0,0,0,0); needKey=true; return;
        }
        input.clear(); input.put(annexB);
        lastPts=Math.max(lastPts+1,pts);
        Long capture=captureByRtpTs.remove(completedAuTs());
        Frame frame=null;
        if(capture!=null) {
            frame=new Frame(capture,auNs);
            long server=clock.serverNowUs();
            if(server!=Long.MIN_VALUE) {
                long us=server-(System.nanoTime()-auNs)/1000-capture/1000;
                if(us>=0 && us<10_000_000) { arrivalUs.addAndGet(us); arrivalN.incrementAndGet(); }
            }
            if(captureByPts.size()>64) captureByPts.clear(); // decoder dropped frames
            captureByPts.put(lastPts,frame);
        }
        decoder.queueInputBuffer(index,0,annexB.length,lastPts,0); needKey=false;
        if(frame!=null) frame.queuedNs=System.nanoTime();
    }
    @Override public void close() {
        synchronized(this) {
            if(!running) return; running=false;
            if(nativeHandle!=0) { nativeDestroy(nativeHandle); nativeHandle=0; }
        }
        try { output.join(1000); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
        decoder.stop(); decoder.release();
        if(presenter!=null) presenter.close();
    }
    private native long nativeCreate();
    private native void nativeFeed(long handle,byte[] bytes);
    private native void nativeDestroy(long handle);
}
