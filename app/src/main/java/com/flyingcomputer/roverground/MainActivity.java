package com.flyingcomputer.roverground;

import android.app.*;
import android.content.*;
import android.hardware.usb.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity implements SurfaceHolder.Callback {
    private static final String PERMISSION="com.flyingcomputer.roverground.USB_PERMISSION";
    private final ExecutorService setup=Executors.newSingleThreadExecutor();
    private UsbManager manager;
    private SurfaceView view;
    private TextView status;
    private volatile boolean surfaceReady;
    private UsbProxy proxy;
    private VideoReceiver video;
    private SynchClient clock;
    private TextView osd;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final Runnable osdTick=new Runnable() {
        @Override public void run() {
            VideoReceiver v=video;
            SynchClient c=clock;
            if(v!=null && c!=null) {
                VideoReceiver.Window w=v.window();
                osd.setText(w.osd());
                android.util.Log.i("RoverOSD",w.log(c)); // key=value, once a second
            } else osd.setText("");
            ui.postDelayed(this,1000);
        }
    };
    private volatile int currentDevice=-1;
    private final BroadcastReceiver events=new BroadcastReceiver() {
        @Override public void onReceive(Context context,Intent intent) {
            UsbDevice device=intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if(PERMISSION.equals(intent.getAction())) {
                if(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED,false) && device!=null) connect(device);
                else show("USB permission denied");
            } else if(UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                if(device!=null && device.getDeviceId()==currentDevice) submit(()->{ disconnect(); show("USB disconnected — reconnect to resume"); });
            } else discover();
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        manager=(UsbManager)getSystemService(USB_SERVICE);
        FrameLayout root=new FrameLayout(this);
        view=new SurfaceView(this); view.getHolder().addCallback(this); root.addView(view);
        osd=new TextView(this); osd.setTextColor(0xffffffff); osd.setTextSize(16);
        osd.setTypeface(android.graphics.Typeface.MONOSPACE); osd.setShadowLayer(3,1,1,0xff000000);
        osd.setPadding(16,12,16,12);
        root.addView(osd,new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.START));
        // Below the status bar / cutout.
        root.setOnApplyWindowInsetsListener((v,insets)->{
            osd.setPadding(16+insets.getSystemWindowInsetLeft(),12+insets.getSystemWindowInsetTop(),16,12);
            return insets;
        });
        LinearLayout controls=new LinearLayout(this); controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(20,12,20,12); controls.setBackgroundColor(0x99000000);
        status=new TextView(this); status.setTextColor(0xffffffff); controls.addView(status);
        Button connect=new Button(this); connect.setText("Connect USB"); connect.setOnClickListener(v->discover()); controls.addView(connect);
        TextView channels=new TextView(this); channels.setTextColor(0xffdddddd);
        channels.setText("0 · Video     1 · MSP DisplayPort (TBA)     2 · MSP RC telemetry (TBA)     3 · Sync"); controls.addView(channels);
        root.addView(controls,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM)); setContentView(root);
        IntentFilter filter=new IntentFilter(PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if(Build.VERSION.SDK_INT>=33) registerReceiver(events,filter,Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(events,filter);
        show("Connect P4 High Speed USB (USB3), then grant permission");
        ui.post(osdTick);
    }
    private void submit(Runnable work) {
        try { setup.execute(work); }
        catch(RejectedExecutionException ignored) { /* Activity teardown already owns cleanup. */ }
    }
    private void show(String text) { runOnUiThread(()->status.setText(text)); }
    private void discover() {
        if(!surfaceReady) return;
        for(UsbDevice d:manager.getDeviceList().values()) if(d.getVendorId()==0x303a && d.getProductId()==0x4001) {
            if(manager.hasPermission(d)) connect(d);
            else manager.requestPermission(d,PendingIntent.getBroadcast(this,0,new Intent(PERMISSION).setPackage(getPackageName()),PendingIntent.FLAG_IMMUTABLE));
            return;
        }
        show("P4 not found — use its High Speed USB port, not CH340");
    }
    private void connect(UsbDevice device) {
        submit(()->{
            disconnect();
            if(!surfaceReady) return;
            show("Connecting to P4…"); currentDevice=device.getDeviceId();
            try {
                clock=new SynchClient();
                video=new VideoReceiver(view,clock);
                proxy=new UsbProxy(error->submit(()->{ disconnect(); show("USB error: "+error.getMessage()); }));
                UsbProxy.Channel channel=proxy.registerHandler(0,video);
                UsbProxy.Channel sync=proxy.registerHandler(SynchClient.CHANNEL,clock);
                proxy.connect(manager,device); channel.open(8); sync.open(2);
                show("Video channel 0 and sync channel 3 connected");
            } catch(Exception e) { disconnect(); show("Connection failed: "+e.getMessage()); }
        });
    }
    private void disconnect() {
        if(proxy!=null) { proxy.close(); proxy=null; }
        if(video!=null) { video.close(); video=null; }
        if(clock!=null) { clock.close(); clock=null; }
        currentDevice=-1;
    }
    @Override public void surfaceCreated(SurfaceHolder holder) { surfaceReady=true; discover(); }
    @Override public void surfaceChanged(SurfaceHolder holder,int format,int width,int height) {}
    @Override public void surfaceDestroyed(SurfaceHolder holder) { surfaceReady=false; submit(this::disconnect); }
    @Override public void onDestroy() {
        ui.removeCallbacks(osdTick); unregisterReceiver(events); surfaceReady=false; submit(this::disconnect); setup.shutdown(); super.onDestroy();
    }
}
