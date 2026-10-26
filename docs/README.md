# Implementation documentation

This is the entry point for an agent continuing `rover-ground-android` without
access to the original conversation.

Read in this order:

1. [Repository instructions](../AGENTS.md): architecture invariants and attribution.
2. [Implementation guide](IMPLEMENTATION.md): system boundaries, source map,
   data flow, thread ownership, JNI and decoder behavior.
3. [USB protocol](USB_PROTOCOL.md): exact RGP4 v2 framing, management, credits,
   sessions and direct P4/L3 binding.
4. [Development and validation](DEVELOPMENT.md): toolchain, dependency pins,
   reproducible builds, tests, CI artifacts, hardware bring-up and diagnosis.
5. [Agent handoff](HANDOFF.md): verified baseline, gaps, ordered follow-up work,
   acceptance criteria and a starter prompt.

The current video contract is receive-only `rtp-h264-v1` on USB channel 0.
P4 owns stream_receiver, FEC recovery, feedback and Ethernet multicast; Android
owns RTP depay and MediaCodec. Update the two repositories together. Earlier
encapsulated-video baseline commits and CI runs do not establish correctness of
this architecture. See current branch CI and HANDOFF for remaining bench work.
