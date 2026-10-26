# Development, build and validation

## Supported baseline

| Component | Version / setting |
| --- | --- |
| App language / package | Java 17, `com.flyingcomputer.roverground` |
| Native language | C++17 / JNI; selected vstreamer core files |
| Minimum Android | API 26 (Android 8) |
| Compile / target SDK | 35 / 35 |
| APK ABI | arm64-v8a only |
| Android Gradle Plugin | 8.9.2 |
| Gradle | 8.11.1 |
| JDK | 17 |
| Android NDK | 27.2.12479018 |
| Android CMake | 3.22.1 |
| JUnit | 4.13.2 |
| vstreamer | f4044615fe6857dc1a7dd3fdb8704b492d618caf |

Read [app/build.gradle](../app/build.gradle), [root Gradle](../build.gradle) and
[native CMake](../app/src/main/cpp/CMakeLists.txt) before changing versions.
There is no checked-in Gradle wrapper in this baseline; commands use `gradle`.
AndroidX is disabled and the initial Activity uses platform widgets.

CMake fetches source over HTTPS and populates dependencies without configuring
the full desktop vstreamer project. `receiver_core` compiles only RTP depay; BFC/ISA-L and FEC are no longer
Android dependencies. P4 owns those parts.
JNI builds as `librover_receiver.so`; host tests build `receiver_test` instead.

## Reproduce the Android build

Clone the repository and enter its root. Install JDK/Gradle as above and point
your SDK environment or `local.properties` at the actual Android SDK. SDK
installation needs network access, accepted SDK licenses and enough disk for
NDK/CMake. Install the pinned packages with the SDK's `sdkmanager`:

```sh
sdkmanager 'platforms;android-35' 'build-tools;35.0.0' \
  'ndk;27.2.12479018' 'cmake;3.22.1'
gradle testDebugUnitTest assembleDebug --stacktrace
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. JVM test reports
are under `app/build/reports/tests/testDebugUnitTest/`. CMake/NDK intermediates
are under `app/.cxx/`; these, local.properties and build outputs are ignored.

Do not commit local SDK paths, fetched dependency trees, APKs, private signing
keys or build caches. Debug artifacts are development builds; a production
signing/distribution setup has not been implemented. Different debug keystores
can prevent `adb install -r` over an existing installation signed by another
key. Uninstalling loses app data, so diagnose the signature mismatch first.

## Portable native tests

A host C/C++ compiler, CMake 3.22+ and Git are sufficient; Android SDK is not
needed for the portable receiver tests:

```sh
cmake -S app/src/main/cpp -B native-build \
  -DROVER_NATIVE_TESTS=ON -DCMAKE_BUILD_TYPE=Debug
cmake --build native-build -j 4
ctest --test-dir native-build --output-on-failure
```

Use Debug/default assertion-enabled test builds. `receiver_test.cpp` currently
uses `assert` for checks and some setup calls; building that executable with
NDEBUG can remove verification/setup. Keep assertion-enabled native test builds.

To use existing pinned source checkouts without network fetches:

```sh
cmake -S app/src/main/cpp -B native-build \
  -DROVER_NATIVE_TESTS=ON -DCMAKE_BUILD_TYPE=Debug \
  -DFETCHCONTENT_SOURCE_DIR_VSTREAMER=/absolute/path/to/vstreamer
```

Verify checkout revisions and dirty state before using overrides. A passing
local build against modified dependencies does not prove a clean pinned build.
Use a separate build directory when changing toolchains or dependency overrides.

## Existing tests and their limits

| Check | Evidence provided | Evidence not provided |
| --- | --- | --- |
| `WireTest.goldenAndEverySplit` | Shared C++/Java golden bytes and every two-part split for representative u16 IDs | Real USB transfers or scheduling |
| `maximumPayloadAndHighSessionBytewise` | 2048-byte payload, bytewise feed, high-bit session and channel | Phone throughput/GC behavior |
| Other `WireTest` cases | Channel-0 DATA vs REPLY, stale sessions, invalid channel/length | Management futures, credits and close concurrency |
| `receiver_test` | Fragmented RTP, byte-identical IDRs, timestamps and malformed/legacy rejection | A decoder accepting a real encoded video stream |
| Android assemble | Java/JNI compilation, dependency fetch/link and APK packaging | Installation, permission dialog, Surface or MediaCodec runtime |
| P4 peer host/CI tests | Direct app transport binding, u16 wire agreement, credit isolation and reset/error paths | Real P4 resource usage or live phone interoperability |

The synthetic IDR is a protocol fixture, not a full standards-valid camera
stream validated through MediaCodec. Do not report native test success as a
hardware decode/playback result.

For changes, select checks by affected layer:

- Wire/session/credits: JVM codec tests, new transport-state tests where needed,
  and matching P4 protocol/service tests if the shared contract changes.
- Native RTP/timestamps: portable native tests plus Android assemble.
- JNI, Java types, package or Gradle changes: Android JVM tests and APK assemble.
- Decoder/lifecycle/UI: APK build plus targeted device/instrumentation checks;
  codec behavior cannot be established by the portable test.
- Docs only: compare against source, validate links and run `git diff --check`.

## CI and artifacts

[Android workflow](../.github/workflows/build.yml) runs on push, pull request and
manual dispatch. It sets up JDK/SDK/Gradle, runs JVM tests and assembleDebug,
builds/runs portable native tests and uploads `rover-ground-android-debug`.

The workflow deliberately asks setup-android for `platform-tools`; its default
legacy `tools` package failed during initial setup. Preserve this override when
updating actions. Action/runtime deprecation warnings are different from build
failures; inspect the actual failing step and its logs.

Initial implementation evidence:

- [Final runtime Android CI](https://github.com/FlyingComputer2/rover-ground-android/actions/runs/40266470635),
  runtime commit da3f881: JVM tests, native test and APK assembly passed.
- [Clean P4 PR CI](https://github.com/FlyingComputer2/rover-ground-esp32p4/actions/runs/40265893870),
  peer commit 5ca7857: clean dependency fetch/patch, firmware and host tests passed.

To fetch an artifact using authenticated GitHub CLI:

```sh
gh run list --repo FlyingComputer2/rover-ground-android --limit 5
gh run download <successful-run-id> \
  --repo FlyingComputer2/rover-ground-android \
  --name rover-ground-android-debug --dir /path/to/empty-output-directory
```

Check the run's head commit before distributing its APK. A prior green run does
not validate newer code. If an API/client operation fails, verify permissions
and endpoint support; do not print tokens. The initial environment's old `gh pr
edit` failed on a deprecated GraphQL field; REST PR updates worked instead.

## P4 peer setup

The peer repository is
[FlyingComputer2/rover-ground-esp32p4](https://github.com/FlyingComputer2/rover-ground-esp32p4).
The matching receiver implementation is in PR #1, starting at commit 41d182e.
Older commit 5ca7857 forwards encapsulated video and is incompatible. It uses ESP-IDF 5.5.5, pinned L3 and the UpstreamQueue patch.
Follow that repository's board/build instructions for any authorized flash.

Required integration settings: USB channel 0 video; ground video L3 index 1;
TX bus d4, RX bus c3, domain 0xB00B, air channel 1, OFDM_24M, 15 dBm; rover
buses are swapped. No second external L3 manager should contend for the radio.
The CH340 connector is for P4 flashing/logs; Android connects to High Speed USB
(USB3). USB operation does not require Ethernet DHCP. HTTP OTA remains Ethernet.

Relevant P4 logs are `GROUND_READY usb=v2 video=0 ...`, `GROUND_USB channel=...`,
`GROUND_PROXY_STATS`, and `GROUND_L3_EXIT`. USB-interface readiness is not proof
of a working C6 air link or an active rover video sender.

## Hardware acceptance procedure

No steps in this section were established by initial hardware testing. Run them
on an authorized bench and record observations rather than assuming success.

1. Record Android model/OS/ABI, app commit/APK source, P4 firmware commit, C6 and
   rover revisions, cable/OTG adapter, power arrangement and radio settings.
2. Confirm the P4's USB v2 logs and device identity. Connect its High Speed USB
   port to the phone in host/OTG mode; grant Android USB permission.
3. Confirm RESET_SESSION, HELLO, discovery and OPEN complete. Check video is ID
   0 and MSP IDs are absent. The initial status text only reports connection.
4. Start a matching rover stream. Confirm an actual moving image on the Surface,
   decoder name/output format and continued display for at least 10 minutes.
5. Verify P4 reverse link reports reach the rover sender independently of USB.
   Verify simultaneous Ethernet multicast playback and isolation when USB stalls.
6. Exercise denied permission, unplug/replug, app reopen without replug, Surface
   loss/recreation and foreground/background transitions. Repeat reconnects and
   inspect thread count, memory growth, native crashes and leaked USB claims.
7. Stop/restart the sender, interrupt the C6/air link and stop the P4 manager.
   Distinguish sender silence from backend death. Check status/recovery and
   document where manual reconnect or P4 restart is still required.
8. Measure receive rate, input/reconstructed frame counts, decode/render rate,
   startup/keyframe delay, credit stalls, P4/Android queue drops, CPU, native/Java
   heap, GC, thermal behavior and latency under sustained load. The initial app
   lacks a complete metrics UI; instrumentation may be needed.
9. Once MSP backends exist, saturate video while exercising both MSP directions.
   Verify channel routing and bounded memory. Define latency targets before
   declaring RC/telemetry acceptable; none has been agreed in this baseline.

Measure latency using a valid clock relationship or a physical visual method.
P4 monotonic report times, RTP timestamps and remote capture clocks cannot
be subtracted arbitrarily to claim end-to-end latency.

## Troubleshooting

| Symptom | Inspect first |
| --- | --- |
| P4 not found | USB3 vs CH340/FS port, cable data capability, host/OTG mode, VID/PID and power |
| USB permission denied | Permission broadcast result and device identity; retry through the UI |
| RESET_SESSION failed | P4 v2 firmware, correct interface/EP0 request, stall or short control response |
| HELLO/LIST timeout | OUT/IN endpoints, session byte order, writer/reader state, P4 session invalidation |
| No P4 upstream for channel | Handler ID vs LIST; MSP placeholders must not be registered yet |
| OPEN backend-not-ready | L3-created queue registration/readiness, P4 manager status |
| Connected but no video | Rover sender, buses/domain/radio, USB credits, vstreamer MTU/version, keyframe and decoder logs |
| Video stalls after some packets | Leaked leases, zero credits, handler backlog, P4 receiver/branch drops, codec input starvation |
| Black Surface with incoming AUs | MediaCodec configuration/SPS/PPS/keyframes, actual resolution, Surface validity, decoder error logs |
| Reports absent | P4 receiver has no valid media, bridge failure, L3 admission or wrong reverse bus |
| Crash on exit/reconnect | Native pointer lifetime, queued callbacks, executor shutdown, codec partial construction and Surface teardown |
| Install update rejected | ABI/API compatibility and debug signing certificate mismatch |

Useful bench commands:

```sh
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -v threadtime
adb shell dumpsys usb
adb shell dumpsys media.codec
adb shell dumpsys meminfo com.flyingcomputer.roverground
```

Device commands and available diagnostics vary by OS/build. USB debugging over
the same phone port may conflict with its host connection; arrange an available
debugging path before collecting a live USB-video trace.
