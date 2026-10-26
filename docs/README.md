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

The runtime baseline documented here is Android commit
[`da3f881`](https://github.com/FlyingComputer2/rover-ground-android/commit/da3f881411adbee88d13b361ca20e87149a2b4db)
and P4 commit
[`5ca7857`](https://github.com/FlyingComputer2/rover-ground-esp32p4/commit/5ca7857268102200e8ef225f5cd914438864366a),
checked on 2026-10-26. The P4 implementation is in
[PR #1](https://github.com/FlyingComputer2/rover-ground-esp32p4/pull/1), which was
open and unmerged when checked. Verify current remote state before integrating
or flashing; default-branch P4 firmware may not contain the USB v2 implementation.

These documents distinguish implemented behavior from proposed work. Follow-up
items in the handoff are a backlog, not features already present or new work
automatically authorized by reading this documentation.
