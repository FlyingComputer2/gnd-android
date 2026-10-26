# rover-ground-android

Android USB ground station for [rover-ground-esp32p4](https://github.com/FlyingComputer2/rover-ground-esp32p4).
Android is the USB host. P4 runs winject-l3 and stream_receiver, recovers radio
FEC, and fans recovered RTP H.264 to Ethernet multicast and USB channel 0.

| USB channel (unsigned 16-bit) | Service |
| --- | --- |
| 0 | Video: receive-only RTP H.264 (`rtp-h264-v1`) |
| 1 | MSP DisplayPort — TBA |
| 2 | MSP RC telemetry — TBA |

The app registers only video. Its JNI receiver depayloads RTP H.264 into
Annex-B access units; MediaCodec renders them onto a Surface. Radio framing,
FEC and reverse receiver reports now belong to P4. Update both peers together:
Android requires `rtp-h264-v1` in the OPEN reply and rejects older firmware.

## Implementation handoff

Start at [docs/README.md](docs/README.md). It links the architecture/source map,
exact USB protocol, build/test and bench procedures, and an ordered agent
handoff with known limitations and acceptance criteria. Coding agents should
also read [AGENTS.md](AGENTS.md).

## Run

Build/install the debug APK from the Actions artifact or Android Studio. Requires
Android 8+ (API 26), arm64, USB host/OTG and an H.264 decoder. Connect the phone
to the P4's **High Speed USB / USB3** connector, grant USB permission and select
Connect USB. The CH340 port remains for flashing/logging. The P4 must run the
matching **RGP4 v2** firmware; the earlier v1 radio bridge is incompatible.

The video upstream must match the rover: ground TX bus `d4`, RX bus `c3`, domain
`0xB00B`, channel 1. MSP channels remain unregistered on both endpoints until
those backends are implemented. This initial app assumes the rover's 1080p30
H.264 stream; decoder selection and sustained latency still need phone testing.

## USB transport

One bulk IN (0x81) / OUT (0x01) pair, development VID:PID 303a:4001. Records have
a 20-byte little-endian header: magic RGP4, u8 version 2, u8 kind, u16 flags 0,
u16 channel, u16 payload length, u32 session, u32 sequence. Payload cap is 2048;
video's L3 maximum is 1445. DATA=4 and CREDIT=5 address all IDs 0..65535.
REQUEST=1, REPLY=2 and EVENT=3 are management records, with channel field 0
unused; they never enter video's DATA handler.

Before HELLO, vendor IN control request `c0/01`, value/index 0, length 4 creates
a new session and returns its u32 ID. The app then discovers channels and opens
video with eight receive slots. Each channel has independent packet credits,
bounded queues and one handler worker. One USB reader demultiplexes records;
one writer schedules channels in round-robin order. A released packet lease
returns its receive credit. Send results mean local queue admission, not radio
or application acknowledgement. Oversized packets are rejected, never truncated.

The app resets on each connection, so reopening it does not require unplugging
the P4. Physical detach, protocol errors or ambiguous USB writes close the
transport and require a new connection. A slow video consumer only exhausts its
channel credits; the USB reader remains available for other channels.

## Build and verification

Use JDK 17, Gradle 8.11.1, Android SDK 35, NDK 27.2.12479018 and CMake 3.22.1:

```sh
gradle testDebugUnitTest assembleDebug
cmake -S app/src/main/cpp -B native-build -DROVER_NATIVE_TESTS=ON
cmake --build native-build -j 4
ctest --test-dir native-build --output-on-failure
```

The native build fetches pinned vstreamer and compiles its portable RTP core.
The native test generates fragmented RTP, verifies matching Annex-B IDRs and
presentation times, and rejects malformed and legacy encapsulated inputs.
P4 host tests cover radio FEC recovery, feedback and fanout isolation. JVM tests cover the
shared wire fixture, split transfers, channel 0 vs management, unsigned high
channel IDs, stale sessions and invalid lengths. GitHub Actions builds an APK
and runs both test sets. Actual phone/P4 enumeration and live playback require
hardware validation; they are not established by these tests.
