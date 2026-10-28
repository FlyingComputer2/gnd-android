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
    // Capture ns by the PTS the AU was queued with, until the decoder outputs it.
    private final ConcurrentHashMap<Long,Long> captureByPts=new ConcurrentHashMap<>();
    // Capture ns by PTS for decoded frames, until the display presents them (API 34+).
    private final ConcurrentHashMap<Long,Long> captureByScreenPts=new ConcurrentHashMap<>();
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
        Long capture=captureByPts.remove(pts);
        long server=clock.serverNowUs();
        if(capture==null) return;
        if(presenter!=null) {
            if(captureByScreenPts.size()>64) captureByScreenPts.clear(); // frames never presented
            captureByScreenPts.put(pts,capture);
        }
        if(server==Long.MIN_VALUE) return;
        long us=server-capture/1000;
        if(us<0 || us>10_000_000) return;
        latencySumUs.addAndGet(us); latencyCount.incrementAndGet();
    }
    // presentNs: when the display showed the frame (System.nanoTime() domain), mapped onto the
    // ground P4's clock through the current offset.
    private void onPresented(long ptsUs,long presentNs) {
        Long capture=captureByScreenPts.remove(ptsUs);
        long server=clock.serverNowUs();
        if(capture==null || server==Long.MIN_VALUE) return;
        long us=server-(System.nanoTime()-presentNs)/1000-capture/1000;
        if(us<0 || us>10_000_000) return;
        screenSumUs.addAndGet(us); screenCount.incrementAndGet();
    }
    /** One stats window: the OSD's numbers and counters. latencyMs / screenMs are -1 without data. */
    public record Window(long kbps,double latencyMs,double fps,long latencyFrames,long frames,long bytes,
                         double screenMs,long screenFrames) {
        /** OSD text: "<kbps> kbps, <fps> fps, <camera-to-screen> ms" ("---" until known). */
        public String osd() {
            String glass=screenMs>=0 ? String.format(Locale.US,"%.1f",screenMs) : "---";
            return String.format(Locale.US,"%d kbps, %.1f fps, %s ms",kbps,fps,glass);
        }
        /** key=value log line, in place of screenshots. */
        public String log(SynchClient clock) {
            return String.format(Locale.US,"kbps=%d latency_ms=%.3f screen_latency_ms=%.3f fps=%.2f latency_frames=%d screen_frames=%d frames=%d bytes=%d sync_delay_us=%d",
                kbps,latencyMs,screenMs,fps,latencyFrames,screenFrames,frames,bytes,clock.delayUs());
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
        statsAtNs=now; statsBytes=b; statsFrames=f;
        return new Window(kbps,n>0 ? sum/1000.0/n : -1,fps,n,f,b,sn>0 ? ssum/1000.0/sn : -1,sn);
    }
    // Called on the serialized native feed path. Never on the USB reader.
    private void onAccessUnit(byte[] annexB,long pts,boolean key) {
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
        if(capture!=null) {
            if(captureByPts.size()>64) captureByPts.clear(); // decoder dropped frames
            captureByPts.put(lastPts,capture);
        }
        decoder.queueInputBuffer(index,0,annexB.length,lastPts,0); needKey=false;
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
