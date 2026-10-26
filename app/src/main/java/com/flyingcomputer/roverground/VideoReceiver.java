package com.flyingcomputer.roverground;

import android.media.*;
import android.os.Build;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.util.concurrent.*;

/** Recovered RTP/H.264 receiver -> Android H.264 decoder -> Surface. */
public final class VideoReceiver implements UsbProxy.Handler, AutoCloseable {
    static { System.loadLibrary("rover_receiver"); }
    private final MediaCodec decoder;
    private final Thread output;
    private volatile boolean running=true;
    private long nativeHandle;
    private boolean needKey=true;
    private long lastPts;
    public VideoReceiver(Surface surface) throws Exception {
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
                try { int index=decoder.dequeueOutputBuffer(info,10000); if(index>=0) decoder.releaseOutputBuffer(index,true); }
                catch(IllegalStateException e) { if(running) android.util.Log.e("RoverVideo","decoder output",e); break; }
            }
        },"video-display");
        output.start();
    }
    @Override public String format() { return "rtp-h264-v1"; }
    @Override public synchronized void onPacket(UsbProxy.Lease packet,UsbProxy.Channel sender) {
        try { if(running && nativeHandle!=0) nativeFeed(nativeHandle,packet.payload); }
        finally { packet.close(); }
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
        decoder.queueInputBuffer(index,0,annexB.length,lastPts,0); needKey=false;
    }
    @Override public void close() {
        synchronized(this) {
            if(!running) return; running=false;
            if(nativeHandle!=0) { nativeDestroy(nativeHandle); nativeHandle=0; }
        }
        try { output.join(1000); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
        decoder.stop(); decoder.release();
    }
    private native long nativeCreate();
    private native void nativeFeed(long handle,byte[] bytes);
    private native void nativeDestroy(long handle);
}
