package com.flyingcomputer.roverground;

import android.hardware.usb.*;
import org.json.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** One USB reader and writer, independent bounded bidirectional u16 channels. */
public final class UsbProxy implements AutoCloseable {
    public interface Handler {
        default void onOpen(Channel sender) {}
        default String format() { return null; }
        void onPacket(Lease packet, Channel sender);
        default void onClose() {}
    }
    public interface Listener { void failed(Exception error); }
    public final class Lease implements AutoCloseable {
        public final byte[] payload;
        private final Channel channel;
        private final AtomicBoolean released=new AtomicBoolean();
        private Lease(Channel c, byte[] bytes) { channel=c; payload=bytes; }
        @Override public void close() {
            if(released.compareAndSet(false,true)) synchronized(state) {
                if(running && channel.open) { channel.pendingCredit++; state.notifyAll(); }
            }
        }
    }
    public final class Channel {
        public final int id;
        private final Handler handler;
        private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16),r->new Thread(r,"usb-channel"));
        private final ArrayBlockingQueue<byte[]> outgoing=new ArrayBlockingQueue<>(8);
        private int mtu=1445, txCredits, txWindow, rxCredits, rxWindow, pendingCredit, sequence;
        private boolean open, readOnly;
        private Channel(int id,Handler handler) { this.id=id; this.handler=handler; }
        public boolean trySend(byte[] payload) {
            synchronized(state) {
                if(!running || !open || readOnly || payload.length==0 || payload.length>mtu) return false;
                boolean ok=outgoing.offer(payload.clone()); state.notifyAll(); return ok;
            }
        }
        public void open(int receiveWindow) throws Exception {
            if(receiveWindow<1 || receiveWindow>16) throw new IllegalArgumentException("window");
            request(new JSONObject().put("op","open").put("channel",id).put("rx_window",receiveWindow), reply->{
                synchronized(state) {
                    String expected=handler.format();
                    if(expected!=null && !expected.equals(reply.optString("format")))
                        throw new IOException("video format mismatch: update P4 and Android together; expected "+expected);
                    readOnly=reply.optBoolean("read_only",false);
                    rxWindow=rxCredits=receiveWindow;
                    txWindow=txCredits=reply.getInt("rx_window"); mtu=reply.getInt("max_payload");
                    if(txWindow<1 || txWindow>16 || mtu<1 || mtu>2048) throw new IOException("invalid channel limits");
                    open=true;
                    worker.execute(()->handler.onOpen(this));
                }
            });
        }
    }
    private interface Completion { void accept(JSONObject reply) throws Exception; }
    private record Pending(CompletableFuture<JSONObject> future, Completion completion) {}
    private final Object state=new Object();
    private final Map<Integer,Channel> channels=new LinkedHashMap<>();
    private final Map<Integer,Pending> requests=new ConcurrentHashMap<>();
    private final ArrayBlockingQueue<Wire.Packet> controls=new ArrayBlockingQueue<>(16);
    private final Wire.Parser parser=new Wire.Parser();
    private final AtomicInteger requestSequence=new AtomicInteger();
    private final Listener listener;
    private final AtomicBoolean closed=new AtomicBoolean();
    private volatile boolean running;
    private UsbDeviceConnection connection;
    private UsbEndpoint input, output;
    private Thread reader, writer;
    private int session, cursor, controlBurst;
    public UsbProxy(Listener listener) { this.listener=listener; }
    public Channel registerHandler(int id,Handler handler) {
        Wire.checkChannel(id);
        synchronized(state) {
            if(running || channels.size()>=16 || channels.containsKey(id)) throw new IllegalStateException("duplicate/full/frozen registry");
            Channel c=new Channel(id,Objects.requireNonNull(handler)); channels.put(id,c); return c;
        }
    }
    public void connect(UsbManager manager,UsbDevice device) throws Exception {
        if(device.getVendorId()!=0x303a || device.getProductId()!=0x4001) throw new IOException("not a rover USB device");
        if(!manager.hasPermission(device)) throw new IOException("USB permission required");
        try {
            connection=manager.openDevice(device); if(connection==null) throw new IOException("open USB failed");
            UsbInterface intf=device.getInterface(0);
            if(!connection.claimInterface(intf,true)) throw new IOException("claim USB failed");
            for(int i=0;i<intf.getEndpointCount();i++) {
                UsbEndpoint ep=intf.getEndpoint(i);
                if(ep.getAddress()==0x81 && ep.getType()==UsbConstants.USB_ENDPOINT_XFER_BULK) input=ep;
                if(ep.getAddress()==1 && ep.getType()==UsbConstants.USB_ENDPOINT_XFER_BULK) output=ep;
            }
            if(input==null || output==null) throw new IOException("missing bulk endpoints");
            byte[] response=new byte[4];
            if(connection.controlTransfer(0xc0,1,0,0,response,4,2000)!=4) throw new IOException("RESET_SESSION failed; requires P4 firmware v2");
            session=ByteBuffer.wrap(response).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if(session==0) throw new IOException("invalid USB session");
            parser.reset(session); running=true;
            reader=new Thread(this::readLoop,"usb-reader"); writer=new Thread(this::writeLoop,"usb-writer");
            reader.start(); writer.start();
            request(new JSONObject().put("op","hello").put("version",2),null);
            // Discover all pages; fail OPEN early if the requested channel is absent.
            Set<Integer> available=new HashSet<>(); int page=0;
            do {
                JSONObject reply=request(new JSONObject().put("op","list").put("offset",page).put("limit",4),null);
                JSONArray list=reply.getJSONArray("channels");
                for(int i=0;i<list.length();i++) available.add(list.getJSONObject(i).getInt("id"));
                page=reply.isNull("next_offset")?-1:reply.getInt("next_offset");
            } while(page>=0);
            for(int id:channels.keySet()) if(!available.contains(id)) throw new IOException("no P4 upstream for USB channel "+id);
        } catch(Exception e) { close(); throw e; }
    }
    private JSONObject request(JSONObject command,Completion completion) throws Exception {
        if(!running || requests.size()>=4) throw new IOException("session closed or request limit");
        int seq=requestSequence.incrementAndGet();
        Pending p=new Pending(new CompletableFuture<>(),completion); requests.put(seq,p);
        if(!controls.offer(new Wire.Packet(Wire.REQUEST,0,session,seq,command.toString().getBytes(StandardCharsets.UTF_8)))) {
            requests.remove(seq); throw new IOException("management queue full");
        }
        synchronized(state) { state.notifyAll(); }
        try { return p.future().get(3,TimeUnit.SECONDS); }
        catch(Exception e) { close(); throw e; }
        finally { requests.remove(seq); }
    }
    private void onPacket(Wire.Packet p) {
        try {
            if(p.kind()==Wire.REPLY) {
                Pending pending=requests.remove(p.sequence());
                if(pending==null) throw new IOException("unexpected management reply");
                JSONObject reply=new JSONObject(new String(p.payload(),StandardCharsets.UTF_8));
                if(!reply.optBoolean("ok")) { pending.future().completeExceptionally(new IOException(reply.optString("error"))); return; }
                try {
                    if(pending.completion()!=null) pending.completion().accept(reply);
                    pending.future().complete(reply);
                } catch(Exception e) { pending.future().completeExceptionally(e); throw e; }
                return;
            }
            if(p.kind()==Wire.EVENT) return;
            synchronized(state) {
                Channel c=channels.get(p.channel());
                if(c==null || !c.open) throw new IOException("DATA/CREDIT for unopened channel");
                if(p.kind()==Wire.CREDIT) {
                    int grant=ByteBuffer.wrap(p.payload()).order(ByteOrder.LITTLE_ENDIAN).getShort()&65535;
                    if(grant==0 || c.txCredits+grant>c.txWindow) throw new IOException("credit overflow");
                    c.txCredits+=grant; state.notifyAll();
                } else if(p.kind()==Wire.DATA) {
                    if(c.rxCredits==0 || p.payload().length>c.mtu) throw new IOException("receive credit/MTU violation");
                    c.rxCredits--;
                    Lease lease=new Lease(c,p.payload());
                    try { c.worker.execute(()->{
                        if(!running) { lease.close(); return; }
                        try { c.handler.onPacket(lease,c); }
                        catch(Exception e) { lease.close(); fail(e); }
                    }); } catch(RejectedExecutionException e) { lease.close(); throw new IOException("handler queue overflow",e); }
                } else throw new IOException("unexpected record kind");
            }
        } catch(Exception e) { fail(e); }
    }
    // Several IN requests stay queued, so the host controller always has a buffer for the
    // device's next transfer. One synchronous bulkTransfer at a time left the bus idle between
    // transfers (the P4 ends a transfer per record), which delayed frame bursts.
    private static final int READ_REQUESTS=4, READ_BYTES=16384;
    private void readLoop() {
        List<UsbRequest> requests=new ArrayList<>();
        try {
            for(int i=0;i<READ_REQUESTS;i++) {
                UsbRequest r=new UsbRequest();
                if(!r.initialize(connection,input)) throw new IOException("USB read request init failed");
                ByteBuffer b=ByteBuffer.allocateDirect(READ_BYTES);
                r.setClientData(b);
                if(!r.queue(b)) throw new IOException("USB read queue failed");
                requests.add(r);
            }
            byte[] bytes=new byte[READ_BYTES];
            while(running) {
                UsbRequest done;
                try { done=connection.requestWait(500); }
                catch(TimeoutException timeout) { continue; }
                if(done==null) continue; // detach; Activity handles detach broadcasts
                ByteBuffer b=(ByteBuffer)done.getClientData();
                int n=b.position();
                if(n>0) { b.flip(); b.get(bytes,0,n); parser.feed(bytes,n,this::onPacket); }
                b.clear();
                if(running && !done.queue(b)) throw new IOException("USB read requeue failed");
            }
        } catch(Exception e) { if(running) fail(e); }
        finally { for(UsbRequest r:requests) { r.cancel(); r.close(); } }
    }
    private Wire.Packet nextPacket() {
        synchronized(state) {
            if(!controls.isEmpty() && controlBurst<4) { controlBurst++; return controls.poll(); }
            for(Channel c:channels.values()) if(c.open && c.pendingCredit>0) {
                int n=c.pendingCredit; c.pendingCredit=0; c.rxCredits+=n;
                if(c.rxCredits>c.rxWindow) throw new IllegalStateException("receive credit overflow");
                byte[] grant=ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short)n).array();
                return new Wire.Packet(Wire.CREDIT,c.id,session,0,grant);
            }
            List<Channel> list=new ArrayList<>(channels.values());
            for(int i=0;i<list.size();i++) {
                Channel c=list.get(cursor++%list.size());
                if(!c.open || c.txCredits==0) continue;
                byte[] bytes=c.outgoing.poll(); if(bytes==null) continue;
                c.txCredits--; controlBurst=0;
                return new Wire.Packet(Wire.DATA,c.id,session,++c.sequence,bytes);
            }
            controlBurst=0; return controls.poll();
        }
    }
    private void writeLoop() {
        try {
            while(running) {
                Wire.Packet p=nextPacket();
                if(p==null) { synchronized(state) { state.wait(10); } continue; }
                byte[] bytes=Wire.encode(p); int sent=0;
                while(sent<bytes.length && running) {
                    int n=connection.bulkTransfer(output,bytes,sent,bytes.length-sent,2000);
                    if(n<=0) throw new IOException("USB write failed; reconnect/reset session");
                    sent+=n;
                }
            }
        } catch(Exception e) { if(running) fail(e); }
    }
    private void fail(Exception e) { boolean report=running; close(); if(report) listener.failed(e); }
    @Override public void close() {
        if(!closed.compareAndSet(false,true)) return;
        synchronized(state) {
            running=false; state.notifyAll();
            for(Channel c:channels.values()) c.open=false;
        }
        if(connection!=null) connection.close();
        for(Pending p:requests.values()) p.future().completeExceptionally(new IOException("USB session closed"));
        requests.clear();
        for(Channel c:channels.values()) {
            c.outgoing.clear();
            c.worker.execute(c.handler::onClose);
            c.worker.shutdown(); // Accepted callbacks finish before closure notification.
        }
    }
}
