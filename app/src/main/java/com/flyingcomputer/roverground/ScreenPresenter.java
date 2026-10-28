package com.flyingcomputer.roverground;

import android.graphics.ImageFormat;
import android.hardware.HardwareBuffer;
import android.hardware.SyncFence;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;
import android.view.SurfaceControl;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Presents decoded frames itself, so it learns when each one reached the display (API 34+).
 *
 * MediaCodec decodes into an ImageReader. Each frame's HardwareBuffer goes onto a child
 * SurfaceControl of the SurfaceView (scaled to fill it, like the codec's own scale-to-fit) in
 * one Transaction. The transaction's present fence signals when SurfaceFlinger put that frame
 * on screen; its signal time (CLOCK_MONOTONIC, like System.nanoTime) goes to the listener. A
 * frame's image returns to the decoder only after SurfaceFlinger's release fence for it.
 */
final class ScreenPresenter implements AutoCloseable {
    /** ptsUs: the frame's presentation time; presentNs: System.nanoTime() domain. */
    interface Listener { void onPresented(long ptsUs,long presentNs); }

    private static final int WIDTH=1920, HEIGHT=1080, MAX_IMAGES=6;
    private final SurfaceControl layer;
    private final ImageReader reader;
    private final HandlerThread thread=new HandlerThread("video-present");
    // Waits on fences off the image thread: release (then close the image) and present.
    private final ExecutorService fences=Executors.newSingleThreadExecutor();
    private final Listener listener;
    private volatile boolean closed;

    ScreenPresenter(SurfaceControl parent,int viewWidth,int viewHeight,Listener listener) {
        this.listener=listener;
        layer=new SurfaceControl.Builder().setName("rover-video").setParent(parent)
            .setBufferSize(WIDTH,HEIGHT).setHidden(false).build();
        new SurfaceControl.Transaction()
            .setPosition(layer,0,0)
            .setScale(layer,viewWidth/(float)WIDTH,viewHeight/(float)HEIGHT)
            .setLayer(layer,1)
            .setVisibility(layer,true)
            .apply();
        reader=ImageReader.newInstance(WIDTH,HEIGHT,ImageFormat.PRIVATE,MAX_IMAGES,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE|HardwareBuffer.USAGE_COMPOSER_OVERLAY);
        thread.start();
        reader.setOnImageAvailableListener(r->present(),new Handler(thread.getLooper()));
    }

    /** The decoder's output surface. */
    Surface surface() { return reader.getSurface(); }

    private void present() {
        Image image;
        try { image=reader.acquireNextImage(); }
        catch(IllegalStateException e) { return; } // all images still on screen or in flight
        if(image==null) return;
        if(closed) { image.close(); return; }
        final long ptsUs=image.getTimestamp()/1000;
        HardwareBuffer buffer=image.getHardwareBuffer();
        if(buffer==null) { image.close(); return; }
        SyncFence ready;
        try { ready=image.getFence(); }
        catch(java.io.IOException e) { buffer.close(); image.close(); return; }
        new SurfaceControl.Transaction()
            .setBuffer(layer,buffer,ready,release->fences.execute(()->{
                try { release.awaitForever(); } finally { release.close(); buffer.close(); image.close(); }
            }))
            .addTransactionCompletedListener(fences,stats->{
                try (SyncFence presented=stats.getPresentFence()) {
                    if(presented.await(Duration.ofMillis(200))) {
                        long ns=presented.getSignalTime();
                        if(ns!=SyncFence.SIGNAL_TIME_INVALID && ns!=SyncFence.SIGNAL_TIME_PENDING)
                            listener.onPresented(ptsUs,ns);
                    }
                } catch(Exception ignored) { /* no present time for this frame */ }
            })
            .apply();
    }

    @Override public void close() {
        closed=true;
        reader.setOnImageAvailableListener(null,null);
        new SurfaceControl.Transaction().reparent(layer,null).apply();
        layer.release();
        thread.quitSafely();
        fences.shutdown();
        reader.close();
    }
}
