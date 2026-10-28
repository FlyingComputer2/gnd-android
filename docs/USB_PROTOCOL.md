# RGP4 v2 USB protocol and P4 integration

This describes the implemented transport baseline, not a proposed network
adapter. Source authorities are [Wire.java](../app/src/main/java/com/flyingcomputer/roverground/Wire.java),
[UsbProxy.java](../app/src/main/java/com/flyingcomputer/roverground/UsbProxy.java),
and the P4 peer's
[protocol/service specification](https://github.com/FlyingComputer2/rover-ground-esp32p4/blob/feature/android-usb-radio/docs/usb-upstream-proxy.md).
Coordinate changes across both repositories.

## USB identity and channel namespace

| Item | Value |
| --- | --- |
| Roles | Android host, P4 device |
| Development VID:PID | `303a:4001` (decimal 12346:16385) |
| Interface | 0, vendor-specific |
| Bulk OUT | `0x01`, Android → P4 |
| Bulk IN | `0x81`, P4 → Android |
| P4 connector | High Speed USB, USB3 on Guition board schematic |
| Channel type | Unsigned 16-bit, entire range 0..65535 |
| Channel 0 | Receive-only RTP H.264, format `rtp-h264-v1` |
| Channel 1 | MSP DisplayPort, TBA |
| Channel 2 | MSP RC telemetry, TBA |
| Channel 3 | Clock sync, format `synch-v1`, bidirectional |

Android uses checked Java `int` values for u16 channels. Session and sequence
fields use Java int bit patterns; negative Java values are valid u32 wire values.
Management uses record kind, not a reserved channel ID. Management records have
a zero channel field with no application-routing meaning. DATA/CREDIT channel 0
belongs to video. Always dispatch kind before application channel lookup.

## Record layout

All multi-byte fields are little endian. There is no alignment padding.

| Offset | Bytes | Field / constraints |
| --- | --- | --- |
| 0 | 4 | ASCII `RGP4` (`52 47 50 34`) |
| 4 | 1 | Version, exactly 2 |
| 5 | 1 | Kind, table below |
| 6 | 2 | Flags, zero |
| 8 | 2 | Channel ID, unsigned |
| 10 | 2 | Payload byte count, excluding header |
| 12 | 4 | Current nonzero USB session ID |
| 16 | 4 | Sequence or request ID |
| 20 | length | Payload |

| Kind | Value | Payload / sequence |
| --- | --- | --- |
| REQUEST | 1 | UTF-8 JSON object; nonzero request ID; channel field zero |
| REPLY | 2 | UTF-8 JSON object; echoes request ID; channel field zero |
| EVENT | 3 | Reserved P4 status event; channel field zero; current Android ignores it |
| DATA | 4 | One nonempty upstream datagram; channel/direction DATA sequence |
| CREDIT | 5 | Exactly two bytes: uint16 slots released; sequence zero |

Hard payload cap: 2048 bytes. Management cap: 1024 bytes. Video DATA must fit
the OPEN reply MTU, currently 1445 bytes. The RTP receiver also caps input at
1445; raising the USB cap does not raise that receiver limit. Application DATA
is not split/reassembled at this layer. Over-MTU application sends fail locally.

USB reads/writes may divide headers and payloads arbitrarily or combine several
records. Parser storage is bounded to header plus maximum payload. Stale-session
bytes are skipped until a current-session header is found. Invalid version,
kind, flags, length or kind-specific framing in a current-session header is an
error. Android closes; P4 invalidates its session. Do not silently continue a
partially failed OUT write in a new session.

`Wire.encode()` currently validates channel range and maximum payload, while
the parser/proxy enforce additional kind/state constraints. Use `UsbProxy` to
construct production traffic rather than trusting the raw encoder to reject
all invalid records.

Golden DATA: channel 65535, length 3, session 0x01020304, sequence 0x11223344,
payload 01 02 03:

```text
52475034 02 04 0000 ffff 0300 04030201 44332211 010203
```

Both peer tests assert these bytes. DATA sequence counters start at 1 when a
channel opens and wrap as u32. They are assigned when selecting outgoing DATA;
they do not account for every upstream packet evicted before selection and are
not delivery acknowledgements. Request IDs correlate RPC independently.

## RESET_SESSION and setup

After Android USB permission and interface claim, before bulk worker startup:

```text
bmRequestType = 0xc0  (vendor, device recipient, IN)
bRequest      = 0x01  (RESET_SESSION)
wValue        = 0
wIndex        = 0
wLength       = 4
response      = fresh nonzero uint32 session ID, little endian
```

The current timeout is 2000 ms. A stall/short response usually means old firmware
or the wrong interface. RGP4 v1 does not support this handshake.

Reset clears P4 proxy staging, parser state, open/closed channel state and
credits, and flushes software bulk FIFOs under its USB worker lock. Already
submitted IN bytes may complete; session filtering discards them. L3-created
transport objects survive USB reset. Reset cannot retract packets already
admitted to L3 or already transmitted on air. Session IDs isolate connections;
they are not authentication.

Android creates one reader and writer, sends HELLO, discovers LIST pages and
opens its registered channels. A fresh connection uses a fresh `UsbProxy`
instance. There is no automatic replay or reconnect of old queued packets.

## Management RPC

The client permits at most four pending requests; the current Activity issues
setup requests serially. Management timeout is three seconds. A timeout, error
reply or ambiguous request outcome closes the current connection; no automatic
retry occurs. Concurrent request-limit admission is not a fully tested API.

Success has `"ok":true`; failure has `"ok":false,"error":"CODE"`.

### HELLO

```json
{"op":"hello","version":2}
```

The current P4 success reply includes `version:2` and `max_payload:2048`.
Other operations require a successful HELLO.

### LIST

```json
{"op":"list","offset":0,"limit":4}
```

The P4 accepts offsets 0..16 and limits 1..4. It returns `channels` entries with
`id`, `name`, `max_payload`, `ready`, `read_only`, optional `format`, and
`next_offset` (null at the end).
Android discovers every page, then checks all locally registered IDs are in the
remote set. It does not currently expose the full descriptor list in its UI or
retain all readiness metadata. Do not locally register MSP IDs before their
remote transports exist: discovery will reject that connection.

### OPEN

```json
{"op":"open","channel":0,"rx_window":8}
```

`rx_window` is Android's available P4→Android DATA slots, in 1..16. P4 replies
with `channel`, its opposite-direction `rx_window` (currently 8), and
`max_payload` (video 1445). Android applies these limits and marks the channel
open on the reader before processing any following DATA. Before marking video
open, Android requires `format:"rtp-h264-v1"`. P4 also replies `read_only:true`.
Android rejects DATA sends on read-only channels. P4 invalidates the session if
it receives DATA for this source. Reverse video feedback is generated on P4.
Missing/mismatched format fails OPEN; update both peers together.

P4 requires a registered and connected L3 app transport. It drains pre-open
backlog and emits the successful reply before that channel's DATA. An ID cannot
be opened twice or reopened after CLOSE within the same USB session.

### CLOSE and STATS

```json
{"op":"close","channel":0}
```

P4 stops that channel and clears pending traffic/credits. An already-started
record finishes before its CLOSE reply. Clients adding channel-level close must
fence their outgoing DATA/CREDIT, drain/discard in-flight IN records until the
reply, then tombstone the ID until reset. Android currently exposes only whole
proxy close, not this per-channel operation.

```json
{"op":"stats","offset":0,"limit":4}
```

P4 returns paginated local `id`, `up`, `down`, `drop`, `tx_credit` counters and
`next_offset`. The initial Android app has no public stats operation or UI.
These counters do not prove radio delivery or isolate all L3 queue drops.

P4 errors include `BAD_REQUEST`, `VERSION`, `HELLO_REQUIRED`,
`NO_REMOTE_HANDLER`, `BACKEND_NOT_READY`, `ALREADY_OPEN_OR_CLOSED`, `NOT_OPEN`
and `UNKNOWN_OP`. Invalid management JSON nesting is bounded on P4 to protect
its worker stack. Invalid DATA/CREDIT state can invalidate the session without
a useful JSON reply; there is no DATA-level error acknowledgement.

## Packet credits and buffers

Each direction of each open channel has independent packet credits. One credit
reserves a receive slot for that channel's MTU, not a byte count or end-to-end
acknowledgement. Initial counts come from OPEN. Every DATA consumes one credit;
zero-credit channels are skipped by the writer.

CREDIT carries a positive uint16 grant and cannot raise the sender's available
count above the negotiated window. CREDIT is not DATA and needs no DATA credit.
Duplicate, zero or overflow grants and DATA without a slot are protocol errors.

- Android decrements receive slots on DATA. Releasing its `Lease` accumulates a
  pending grant; the writer coalesces grants and restores advertised slots.
- P4 returns a slot after submitting ingress to `UpstreamQueue`, including local
  discard paths. L3 may subsequently lose/evict traffic; credits do not prevent
  that or acknowledge delivery to the rover.
- Android has eight outgoing datagram slots per channel; `trySend()` clones an
  accepted payload and returns false on full/closed/empty/oversized input.
- P4 creates 16-slot L3 app queues in the current boot. Closed channels are
  drained/discarded. Open zero-credit egress queues retain a bounded backlog
  and evict oldest entries on sustained overload.
- Android handler executors have 16 queued callback slots. The management OUT
  queue has 16 entries; request correlation is capped at four pending RPCs.

Writers prioritize management with a burst cap of four, then pending credit
returns, then round-robin eligible DATA. Scheduling occurs at record boundaries;
a partial record must finish. This is packet round-robin, not byte-based deficit
scheduling or guaranteed channel bandwidth. Whole-endpoint host stalls affect
all channels despite independent credits.

## P4/L3 and video contract

L3 creates/owns UpstreamQueue for a queue app transport. Its creation callback
passes the actual video object to `ground_video_start()`. The video service
bridges that transport into the stock UDP-only stream_receiver on P4 loopback.
The receiver removes stream headers, recovers FEC and returns feedback through
L3. Its recovered RTP is independently queued for Ethernet multicast and USB 0.

USB channel 0 is registered as a local processed packet source, not as another
L3 transport. Other channels may still register real L3-owned objects directly.
Do not fabricate app-created raw queue pairs or aliases as L3 UpstreamQueue.
L3 disconnects retained handles at teardown. No automatic manager restart exists.

One channel-0 DATA contains one RTP H.264 datagram with original sequence,
timestamp and extensions, at most 1445 bytes. No stream header/FEC envelope or
Annex-B AU is present. P4 advertises `rtp-h264-v1` and `read_only:true` in LIST
and OPEN. Android depayloads RTP and displays via MediaCodec. USB record version
remains 2; video payload compatibility is explicit and changes with both peers.
Old app/firmware combinations are incompatible. FEC ends before USB/Ethernet
fanout, so it cannot repair packet loss introduced after that boundary.

Ground video remains L3 index 1, TX d4, RX c3, domain 0xB00B, channel 1,
OFDM_24M, 15 dBm and no manager FEC. L3 pins and compatibility patch remain
unchanged. Radio proxy is local UDP 22/9000; manager console 127.0.0.1:2400.
P4 multicast is 239.255.42.1:5600 (RTP payload 96, H264/90000, TTL 1), enabled
when Ethernet has IPv4. USB video requires no Ethernet cable or DHCP lease.

## Channel 3: clock sync

Channel 3 carries app_synch rtt datagrams unchanged (winject-l3
`docs/synch.md`). The phone's `SynchClient` sends one `sync_request` a second.
The P4 answers each request against its own `CLOCK_REALTIME` as it arrives,
which is the clock the rover follows. LIST and OPEN report `format:"synch-v1"`
and `read_only:false`. MTU is 64.

| Message | Bytes (big-endian) |
| --- | --- |
| `sync_request` | `01`, u32 trid |
| `sync_response` | `02`, u32 trid, i64 server_time_us |

The phone keeps its offset to the P4 clock against `elapsedRealtime`, from the
lowest-delay exchange of the last eight. The OSD (top left) shows, once a second, `<kbps> kbps, <fps> fps, <ms> ms`:

- Channel-0 kbps.
- Decoder output fps.
- Camera-to-screen (glass) latency, Android 14+: the P4 clock when the display
  showed a frame, minus the capture `CLOCK_REALTIME` the rover's packer wrote
  into that frame's RTP header extension id 2; the mean over the second, `---`
  before the clock syncs (and on older Android). The app presents frames
  itself: MediaCodec decodes into an `ImageReader`, and each frame's buffer goes
  onto a child `SurfaceControl` of the SurfaceView in one transaction. The
  transaction's present fence signals when SurfaceFlinger put that frame on
  screen; its signal time (`CLOCK_MONOTONIC`) is mapped onto the P4 clock. The
  panel's own response time is not included. `MediaCodec`'s
  `OnFrameRenderedListener` is not used: on the test phone it reports the
  requested render time, not the display time.

The log also keeps camera-to-decode latency (`latency_ms`): the same
difference taken when `MediaCodec` outputs the frame.

The accuracy of the latency figure rests on three things:
- The rover's sync to the ground: rtt measured −3 to +2 ms under video, the
  wire tens of µs.
- The USB exchange: sub-millisecond.
- The ground's own `CLOCK_REALTIME`: it follows the GS host's chrony over SNTP.

The same numbers go to logcat once a second, tag `RoverOSD`, as key=value.
Read these instead of screenshots:

```text
kbps=6023 latency_ms=85.681 screen_latency_ms=101.240 fps=29.90 latency_frames=30 screen_frames=30 frames=12345 bytes=… sync_delay_us=812
```

`latency_ms=-1.000` (`screen_latency_ms=-1.000`) means no frame decoded
(rendered) in the second had a capture time, or the clock had not synced. `sync_delay_us` is the round trip of the USB sync
exchange the offset came from.

An app that registers channel 3 needs firmware that lists it; discovery rejects
missing channels, so update P4 and Android together.
