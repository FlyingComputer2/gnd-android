# RGP4 v2 USB protocol and P4 integration

This describes the implemented transport baseline, not a proposed network
adapter. Source authorities are [Wire.java](../app/src/main/java/com/flyingcomputer/roverground/Wire.java),
[UsbProxy.java](../app/src/main/java/com/flyingcomputer/roverground/UsbProxy.java),
and the P4 peer's
[protocol/service specification](https://github.com/FlyingComputer2/rover-ground-esp32p4/blob/5ca7857268102200e8ef225f5cd914438864366a/docs/usb-upstream-proxy.md).
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
| Channel 0 | Video datagrams and reverse receiver reports |
| Channel 1 | MSP DisplayPort, TBA |
| Channel 2 | MSP RC telemetry, TBA |

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
the OPEN reply MTU, currently 1445 bytes. The native receiver also hard-codes
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
`id`, `name`, `max_payload`, `ready`, and `next_offset` (null at the end).
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
open on the reader before processing any following DATA.

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

## P4/L3 contract

L3 owns radio/MPDU/FEC routing and creates an `UpstreamQueue` for queue app
transport. P4's `cfg.on_upstream_queue` receives that object and registers it:

```cpp
usb_upstream_register(uint16_t{0}, "video", video_upstream);
```

The proxy takes a shared handle to the actual L3 app object. L3 disconnects it
on teardown; retained handles remain memory-valid and reject sends. Android
uses a local handler with the same USB ID. An app-created raw queue pair or a
USB-defined `using UpstreamQueue = ...` alias is not this architecture.

The peer currently pins L3 `fb3eea11d3edc81ce39287a8fd5c77e3d43d41f1` and applies
[a compatibility patch](https://github.com/FlyingComputer2/rover-ground-esp32p4/blob/5ca7857268102200e8ef225f5cd914438864366a/patches/winject-l3-upstream-queue.patch)
to create/publish this object. Preserve or deliberately replace that API when
updating L3. The P4 has no automatic L3 restart/rebinding implementation.

Ground video uses L3 upstream index 1, TX bus d4, RX bus c3, domain 0xB00B,
air channel 1, OFDM_24M, power 15 dBm and no manager FEC. USB ID 0 is independent
of that upstream index and of the air channel number. The P4 reaches its local
radio proxy over UDP 22/9000 and binds its manager console to 127.0.0.1:2400.
A second external Ethernet L3 manager must not take the same radio peer role.
