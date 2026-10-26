# Agent instructions

Start with [docs/README.md](docs/README.md), then read the implementation guide,
USB protocol, development guide and handoff backlog linked there. These files
are the repository's implementation handoff; keep them aligned with code.

## Architecture to preserve

- Android is USB host, depayloads recovered RTP H.264 and uses MediaCodec.
  P4 runs winject-l3 and stream_receiver for framing/FEC/feedback, then fans RTP
  to multicast and USB 0. Channel 0 requires format `rtp-h264-v1`, receive-only.
- Channel IDs are unsigned 16-bit values. Java stores them as validated ints.
  Channel 0 is video, 1 is MSP DisplayPort (TBA), 2 is MSP RC telemetry (TBA).
- Management dispatch uses record kinds. Channel 0 is never reserved away from
  video. Preserve the RGP4 v2 contract in `docs/USB_PROTOCOL.md`.
- L3 creates `UpstreamQueue` for queue app transport. The P4 receiver consumes
  that object; processed video has a separate bounded USB source. Direct L3 USB
  bindings remain supported for other services. Never fabricate L3 queue handles.
- One USB reader and writer serve independent bounded channels. Release packet
  leases to return receive credits; keep callbacks off the USB reader thread.
- Keep vstreamer dependencies pinned. Change shared protocol behavior in concert
  with rover-ground-esp32p4 and update both sides' fixtures/tests.

## Working and verification

The source is Java 17 plus C++17/JNI. There is no Gradle wrapper in this baseline;
use the pinned Gradle version in `docs/DEVELOPMENT.md`. Follow that guide for
change-specific checks. Documentation-only changes need source/link review;
runtime tests are required when their corresponding implementation changes.

Distinguish code inspection, unit/CI verification and actual hardware evidence.
No live P4/phone playback was verified in the initial implementation. Record
hardware configuration, revisions and measurements before claiming it works.

Read the known limitations in `docs/HANDOFF.md` before changing lifecycle,
threading, session reset, credits, decoder behavior or MSP functionality.

## Commit attribution

Whenever Codex creates or rewrites commits, use the `codex-commit-attribution`
skill (in the original workspace:
`/home/ubuntu/.codex/skills/codex-commit-attribution/SKILL.md`). Every resulting
commit must contain:

```text
Co-authored-by: Codex <codex@openai.com>
Made-with: Codex
```

Preserve human authors and existing contributor trailers. Verify actual commit
messages, including remote merge/squash commits, before reporting completion.
