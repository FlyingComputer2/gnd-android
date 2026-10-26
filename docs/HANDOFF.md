# Handoff for the next implementation agent

## Start here

Read [AGENTS.md](../AGENTS.md), the [implementation guide](IMPLEMENTATION.md),
[USB protocol](USB_PROTOCOL.md) and [development guide](DEVELOPMENT.md). This
handoff is sufficient without the original conversation. Check current Git
status and remote revisions before editing; the initial implementation snapshot
is not a guarantee that another agent has made no subsequent changes.

Runtime baseline checked on 2026-10-26:

| Repository | Revision / status |
| --- | --- |
| Android | `da3f881411adbee88d13b361ca20e87149a2b4db`, published on main |
| P4 | `5ca7857268102200e8ef225f5cd914438864366a`, PR #1 open/unmerged when checked |
| Android CI | [40266470635](https://github.com/FlyingComputer2/rover-ground-android/actions/runs/40266470635), passed |
| P4 PR CI | [40265893870](https://github.com/FlyingComputer2/rover-ground-esp32p4/actions/runs/40265893870), passed |

Android CI passed JVM wire tests, portable native FEC/RTP recovery and arm64
APK assembly. Clean P4 CI passed fetch/patch, firmware build and USB host tests.
The initial developer environment had no attached P4/phone. No live USB
enumeration, permission flow, MediaCodec playback, sustained performance or
hardware recovery result was established, and no P4 firmware was flashed.

## Decisions already made

- Keep USB vendor bulk packets, with Android host and P4 device.
- Keep winject-l3 on the ground P4. L3 creates `UpstreamQueue` for a queue app
  transport; the USB proxy accepts that object directly.
- Keep video stream decoding on Android: reuse vstreamer core/FEC/RTP through
  JNI, then MediaCodec/Surface playback. P4 preserves vstreamer datagrams.
- Channel IDs are u16; 0 = video, 1 = MSP DisplayPort (TBA), 2 = MSP RC telemetry
  (TBA). Channel 0 is not reserved for management.
- Management is selected by record kind. Both directions of every application
  channel have independent credits and bounded storage.
- P4 uses direct upstream bindings; Android registers one packet handler per ID.
- Existing radio settings and air buses must remain compatible with the rover;
  USB ID 0 is unrelated to L3 index 1 or air channel 1.

Do not reinterpret these as a request to introduce USB networking, put FEC on
P4, port winject-l3 into the app, or implement speculative MSP wire formats.
Change architectural decisions only when the user explicitly changes scope.

## Implemented surface

The app starts in landscape, discovers the P4, handles permission, opens video
and renders through a Surface. It has one connection-status view and a Connect
USB button. MSP entries are labels only. There is no configuration editor,
metrics dashboard, recording UI, background service, release signing workflow
or automatic recovery state machine.

`UsbProxy` implements registration, reset/HELLO/LIST/OPEN, matching replies,
framing, per-channel queues, credits and handler dispatch. It has no public
channel CLOSE or STATS API. EVENT is accepted but ignored. Every locally
registered channel must be present in P4 LIST; optional absent handlers are not
supported. Use a new proxy and handler resources for each reconnect.

The native core handles current vstreamer media records, RS recovery/deadlines,
RTP depayloading and receiver link reports. It compiles selected portable source
files rather than the full desktop receiver application. The decoder is a
minimal 1080p30 MediaCodec integration. Hardware acceleration is not explicitly
selected or verified by `createDecoderByType()`.

## Follow-up work in order

These are suggested work packages to select with the user's assigned scope.
They do not imply every item must be implemented in one turn.

### 1. Establish a hardware baseline

Follow the [bench procedure](DEVELOPMENT.md#hardware-acceptance-procedure).
Identify exact Android/P4/C6/rover revisions and capture logs from both ends.
Confirm ID 0 receives real video and reverse reports reach the rover.

Acceptance evidence:

- Successful permission, RESET, HELLO, discovery and OPEN on the target phone.
- Actual moving video for at least 10 minutes, with selected decoder and format.
- Feedback absent before media and visible on the sender after media begins.
- Measured receive/decode/render rates, queue/credit behavior, CPU/heap/GC,
  thermal state and a defensible latency measurement.
- Results of app restart without replug, unplug/replug and Surface recreation.

Do not replace this evidence with synthetic recovery tests or an APK build.
Record device-specific failures before guessing at protocol changes.

### 2. Test and harden transport/lifecycle behavior

Current inspection concerns below are not reproduced hardware failures. Add
focused regression tests before changing the corresponding paths.

| Area | Current behavior / concern | Follow-up acceptance |
| --- | --- | --- |
| Proxy lifecycle | Single-use executors and closed flag; public reuse guards incomplete | Explicit single-use API or correctly tested reconnect state machine |
| Shutdown | `close()` closes USB and queues onClose, but does not join USB threads or await handler completion | Teardown has bounded, documented completion; no stale callbacks use freed resources |
| Handler queue pressure | onClose is submitted to a bounded executor | Closing with a full callback queue cannot lose cleanup or throw unexpectedly |
| Failure callbacks | onOpen/onClose failures are not uniformly handled like packet-handler failures | Failures report through a defined path and clean resources once |
| Requests | Pending limits checked through concurrent map size; setup currently serial | Tested concurrent admission, timeout, malformed reply and cancellation behavior |
| Receive errors | bulkTransfer -1 is treated as timeout; Activity uses detach broadcasts | Distinguish/report persistent failure when broadcasts do not resolve it |
| P4 backend loss | P4 invalidates its session; Android has no heartbeat | Backend death cannot leave a permanent misleading connected state without a documented recovery action |
| Optional channels | All registered IDs must exist remotely | Preserve this contract or add an explicit optional/required registration policy with tests |

A useful test seam is an injectable bulk/control I/O interface under UsbProxy,
with a deterministic fake peer. Preserve a single reader/writer and existing
wire bytes; extracting I/O is not a request to change the transport architecture.

Required scenarios for such tests: partial OUT writes, split/merged IN records,
stale-session tails, HELLO failure, missing remote channel, OPEN reply followed
by DATA in the same read, zero/overflow credits, lease release during shutdown,
one stalled handler while another progresses, blocked writer, full queues and
detach during an outstanding RPC. Test that channel-0 management never reaches
video and high unsigned channel IDs remain distinct.

### 3. Harden codec/JNI lifecycle and diagnostics

Inspection concerns to evaluate:

- Partial VideoReceiver construction can allocate codec/native resources before
  a later failure; make cleanup explicit for every constructor failure path.
- The display thread logs codec output errors but does not provide a complete
  app-visible recovery path. Scheduled tick exceptions can stop periodic work.
- Native method lookup/allocation and pending Java exceptions need focused
  handling. Preserve thread-local JNIEnv and synchronous callback ownership.
- Native feed/tick/destruction must remain serialized; never retain a local
  jobject/JNIEnv for an asynchronous callback on a different thread.
- The receiver assumes 1080p30, uses fixed frame-rate timestamp conversion and
  monotonic clamping, and has no explicit stream-restart/reset policy.
- Runtime decoder selection, hardware/software classification, SPS/PPS changes,
  resolution changes, timestamp wrap, keyframe waits and input-buffer starvation
  are not covered by the host recovery test.

Acceptance: deterministic cleanup under construction failure, Surface loss,
codec error and repeated reconnect; meaningful UI/error events; device tests
with the actual rover stream. Keep FEC/packet processing off the USB reader.

### 4. Expose useful measurements before optimizing

Add bounded/sampled diagnostics for wire ingress, valid/reconstructed datagrams,
FEC recovery and residual loss, parser/session errors, queue occupancy/drops,
credit stalls, feedback enqueue failures, AUs and decode/render counts.
Separate transport, FEC and display loss; historical `udp` names in native
counters represent USB-delivered stream datagrams here.

Measure first. The native build currently uses portable scalar erasure code;
NEON may be a follow-up if FEC is a measured bottleneck. The parser already
avoids allocating a ByteBuffer for every payload byte. Do not reintroduce that
allocation pattern. Keep maximum-payload/high-bit fixtures when optimizing.

Acceptance: metrics explain whether a stall starts on radio/P4, USB, native
recovery or codec/display, without an unbounded logging/allocation workload.
Any speedup preserves byte-identical FEC/RTP behavior and protocol fixtures.

### 5. Define and implement MSP channels

Channels 1 and 2 are allocated but their implementation is TBA. Before coding,
agree the MSP protocol versions, payload boundaries, flow/direction, MTU,
DisplayPort semantics, RC/telemetry command set, update/deadline policy,
link-loss behavior and remote L3 routing with the relevant rover implementation.
The USB mapping does not define these details.

Then, per channel:

1. Configure the matching L3 queue app transport and have P4 register its
   L3-created UpstreamQueue under the agreed USB ID.
2. Add the Android handler and bounded application state/queues.
3. Add protocol fixtures and tests for fragmented/invalid MSP data as appropriate
   to the agreed application framing; one USB DATA remains one upstream datagram.
4. Add UI/logic appropriate to that service without blocking USB/video workers.
5. Test both directions alongside saturated video, reconnect and link loss.

Do not create fake ready P4 registrations or register Android placeholders just
to populate the UI. The current connect path rejects absent remote IDs. Define
application-level reliability/freshness independently of USB credit admission.

### 6. Maintenance and distribution

- Consider a pinned Gradle wrapper so local setup does not depend on system
  Gradle. Keep the documented toolchain and CI in agreement.
- Update deprecated CI actions deliberately; preserve the setup-android package
  override that avoids the retired `tools` package.
- Define signing/versioning/distribution when a release is requested; current
  artifacts are debug builds, and debug keys can differ between machines/runs.
- Make native tests meaningful under optimized builds: move setup side effects
  out of assert and ensure checks cannot silently disappear with NDEBUG.
- Extend codec tests for merged records, malformed kinds/flags/CREDIT lengths,
  bad management shapes, and transport state/lifecycle rather than only framing.
- When updating L3, review the P4 compatibility patch and migrate deliberately
  to an upstream equivalent instead of aliasing raw queues to UpstreamQueue.
- Coordinate vstreamer pin/format updates with the rover sender and feedback
  decoder. Do not assume stream wire version equals USB wire version.

## Completion record for future changes

In the change description record the concrete behavior changed, affected
protocols/dependency pins, tests and target device observations, remaining
limitations and relevant artifact/CI revisions. Update these docs if an API,
constant, lifecycle assumption or known gap changes. Preserve human authors and
verify required Codex attribution trailers on every created/rewritten commit.

## Starter prompt for a new agent

> Continue work in FlyingComputer2/rover-ground-android. Read AGENTS.md and the
> documentation index, then HANDOFF.md and the guide for the assigned task.
> Inspect current code and Git/remote state before making changes. Preserve USB
> bulk RGP4 v2, unsigned 16-bit channels (0 video, 1 MSP DisplayPort TBA, 2 MSP
> RC telemetry TBA), L3-created UpstreamQueue bindings on P4, and Android-native
> vstreamer FEC/RTP plus MediaCodec playback. Do not assume hardware validation
> from CI success. Implement the user-assigned work package, run checks relevant
> to changed layers, update documentation, and report exact remaining hardware
> or integration gaps. Follow repository commit-attribution requirements.
