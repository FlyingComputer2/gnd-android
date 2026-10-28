# Implementation guide

## System boundary

Android displays video received over vendor USB bulk from the ground P4.
P4 owns C6/SDIO, winject-l3, stream framing, radio FEC and receiver feedback.
It runs vstreamer's stock `stream_receiver`, then fans recovered RTP H.264
out to Ethernet multicast and an independent USB channel-0 queue.

```text
Rover → radio → P4 L3 UpstreamQueue → stream_receiver → recovered RTP H.264
                                                        ├→ Ethernet multicast
                                                        └→ USB 0 → Android
Android: USB 0 → JNI RTP depay → Annex-B H.264 → MediaCodec → Surface
```

Channel 0 is receive-only video with format `rtp-h264-v1`. Each DATA payload
contains exactly one RTP packet (maximum 1445 bytes), without a vstreamer
header, FEC shard or additional USB video envelope. Android validates the OPEN
format before enabling callbacks. An older firmware reply without that format
fails the connection with an update-both-peers error. Android sends no video
DATA and no radio feedback. RGP4 record framing remains version 2.

MSP channel 1 (DisplayPort) and channel 2 (RC/telemetry) remain unregistered.
No MSP proxy, USB network adapter, RTSP server or recording UI is implemented.

## Source map

| Source | Responsibility |
| --- | --- |
| `MainActivity.java` | Surface lifecycle, USB permissions/discovery, setup and cleanup |
| `UsbProxy.java` | Handler registration, management, format validation, credits and bounded queues |
| `Wire.java` | RGP4 v2 codec/parser; unsigned 16-bit channel IDs |
| `VideoReceiver.java` | Channel-0 handler, synchronized JNI lifetime, MediaCodec input/output |
| `ScreenPresenter.java` | Android 14+: presents decoded frames via `SurfaceControl` and reports each frame's display time |
| `receiver.cpp` / `receiver.hpp` | Portable RTP/H.264 depayloading and AU callback |
| `jni.cpp` | Synchronous JNI feed and Annex-B callback |
| `app/src/main/cpp/CMakeLists.txt` | Pinned vstreamer RTP source; JNI or native-test target |
| `native-tests/receiver_test.cpp` | Fragmented RTP, matching IDRs/PTS and malformed/legacy rejection |

Java sources are under `app/src/main/java/com/flyingcomputer/roverground`;
C++ sources are under `app/src/main/cpp`.

## Connect and thread ownership

1. Activity setup executor disposes of old resources and creates a receiver for
   an existing Surface, then registers handler 0 on a fresh UsbProxy.
2. UsbProxy claims interface 0, finds bulk endpoints and resets the USB session.
3. Reader/writer start; HELLO negotiates RGP4 v2 and LIST discovers required IDs.
4. OPEN requests eight receive slots. On the USB reader, before accepting DATA,
   its completion validates `format:rtp-h264-v1`, saves read-only/MTU/credit limits,
   marks the channel open and queues onOpen before packet callbacks.
5. One bounded worker per channel handles packets. Video native feed runs under
   the VideoReceiver monitor and releases the packet lease in finally.
6. Native depay synchronously calls `onAccessUnit(byte[],long,boolean)`; Java
   submits the AU to MediaCodec. A separate video-display thread drains output.
7. Detach, errors, Surface destruction or Activity teardown close the proxy and
   receiver. Reconnection creates fresh objects. No old packets are replayed.

The USB reader never invokes video processing directly. Each channel worker
has 16 queued callbacks; channel receive credits constrain outstanding leases.
Lease.close is idempotent and returns one credit while the channel is active.
Do not copy into an unbounded queue and immediately release credits.

UsbProxy is single-use. Its writer schedules management, credits and eligible
DATA at record boundaries. `trySend` rejects sends on read-only channels.
Other future bidirectional channels retain their independent credits/queues.

No Android FEC timer or feedback worker remains. P4 receiver deadlines and
feedback run regardless of Android connection or Ethernet subscribers. Radio
FEC does not cover P4 output-queue or later USB/Ethernet packet drops.

## Native/decoder contract

Native Receiver validates input size, uses pinned `rtp_h264_depacketizer`, and
returns completed Annex-B access units with keyframe status and nominal-30-fps
PTS. Feed/destruction are serialized by Java; the native receiver is not
independently thread-safe. Native callback JNIEnv and jobject are valid only
inside that call and must never be retained for another thread.

| JNI entry | Purpose |
| --- | --- |
| `nativeCreate()` | Allocate depay state and resolve onAccessUnit |
| `nativeFeed(long, byte[])` | Copy one RTP packet and deliver completed AUs |
| `nativeDestroy(long)` | Free state after feeds are quiescent |

C++ exceptions become IllegalStateException. The decoder starts as H.264
1920x1080, requests a 4 MiB input buffer and API-30+ low latency, and uses
createDecoderByType, with realtime priority and operating rate 240 (on the test
phone the Exynos decoder otherwise took 12–20 ms per 1080p frame at 30 fps
instead of ~5). Hardware acceleration is not explicitly selected/verified.
UsbProxy keeps four 16 KiB IN requests queued (`UsbRequest`), so the bus is
not idle between the P4's per-record transfers. The activity asks for the
display's fastest refresh mode; Samsung's Motion smoothness setting can still
cap it at 60 Hz.
On Android 14+ the decoder outputs to `ScreenPresenter` (an `ImageReader`
whose frames go onto a child `SurfaceControl` of the SurfaceView, scaled to
fill it, one transaction per frame) so each frame's present fence gives its
display time for the OSD's `screen` latency; older versions decode straight
into the SurfaceView.
Java waits for a keyframe initially and after input-buffer starvation; PTS is
clamped monotonically. RTP depay handles fragmented NALs and packet gaps.

Known limits still need device work: SPS/PPS and resolution changes, timestamp
wrap/restarts, codec construction failure cleanup, output errors, lifecycle
concurrency, and sustained CPU/thermal/latency behavior. There is no automatic
recovery from every codec error or USB failure. Host tests use synthetic NALs;
they do not prove MediaCodec playback on a phone.

## Extending channels

Use registerHandler with a validated 0..65535 Java int before connect. Each
registered ID must exist on P4; no optional absent registration is supported.
A handler may declare a required format; format validation happens in OPEN.
The P4 USB proxy can still accept real L3-created UpstreamQueue handles for
unprocessed bidirectional services. Video instead binds a local processed
source, while its receiver consumes the actual L3 video transport.
