# RamenWine development status

**Snapshot:** 2026-11-10  
**Current milestone:** finish a real Windows execution runtime and launch an original visual-novel test executable. The installable APK is a final packaging step only.

## Implemented

- Android Gradle project using Kotlin and Jetpack Compose.
- ARM64-first (arm64-v8a) Android NDK/CMake native bridge.
- Android Storage Access Framework folder selection and persisted read permission.
- Bounded, read-only game-folder scan.
- Safe relative-path validation for Windows game file names.
- Bounded PE header inspection for x86, x86-64, ARM32, ARM64 and ARM64EC COFF machine values.
- Heuristic main-EXE selection using the game-folder name and common visual-novel engine filenames; ambiguous cases remain a manual choice.
- A library Play action connected to a dedicated immersive GamePlayerActivity. The intended surface contains the game's window, not a Windows desktop shell.
- Single-finger touch-to-left-click and drag-to-pointer-motion mapping; multi-touch is currently ignored.
- Streamed copy of the selected game folder into app-private storage.
- Import staging directory and cleanup on failure/cancellation.
- Import guardrails: maximum 32 directory levels, 100,000 entries, 64 GiB total bytes, and 128 MiB free-space reserve.
- Live import progress in the UI (file count and copied bytes).
- Validation that the selected EXE is a PE image before import finalization.
- App-private JSON library metadata, atomically replaced and validated on load.
- Runtime backend inventory for Bionic ARM64EC/FEX, Bionic x64/Box64 and glibc/PRoot/Box64 packages.
- Runtime manifest checks for host ABI, required files, safe paths, executable permissions and symlink containment.
- Offline runtime ZIP installer with streaming extraction, size/entry limits, SHA-256 checks, staged activation and rollback to the previous backend on post-install inventory failure.
- Runtime package picker in the Android UI.
- Python runtime packager that creates ZIP files with a JSON manifest, SHA-256 values, executable flags and symlink metadata.
- Python tests for archive output, traversal, case collisions, virtual mount symlinks and symlink escape.
- JVM tests for PE parsing, game path validation and runtime manifest/symlink states.
- PRoot/Box64/Wine command construction as a validated argument vector (no shell), including per-game private directories, host/guest path checks, Wayland endpoint preconditions and a Wine 11 unified-loader path.
- PRoot launch preflight requires both the ARM64 glibc loader (for ARM64 Linux Box64) and the x86-64 glibc loader (for Wine), the three Wine Wayland files (x86-64 Unix, x86-64 PE and i386 PE), and Box64's required x86-64 fallback libraries. Android/Bionic `LD_LIBRARY_PATH` is reserved for PRoot; guest x86-64 libraries use `BOX64_LD_LIBRARY_PATH`.
- New Wine prefixes explicitly use `WINEARCH=wow64`, selecting Wine 11's 64-bit-host WoW64 mode for both 32-bit and 64-bit Windows executables.
- PRoot runtime packaging now requires the separately-installed ARM64 helper at `host/libexec/proot/loader` and passes its app-private path through `PROOT_LOADER`; this avoids the Termux build prefix being baked into the executable.
- Added a Termux-based source build and staging tool for the Android/Bionic PRoot host. It verifies exact package versions, symlink containment, ARM64 ELF files and records package/toolchain hashes. This produces a separate runtime input, not an APK; no validated host payload is currently bundled in the app.
- Added `tools/build_glibc_runtime.py` for the guest runtime: pinned Ubuntu Base ARM64, ARM64 Box64, Box64's x86-64 fallback-library bundle, Wine 11.0 with i386 and x86-64 PE targets, Wayland, XKB data and an offline runtime ZIP. It validates required files and records source/dependency provenance. The builder source exists; the full runtime has not yet been built or launched.
- Runtime packager checks the full Wine WoW64 PE trees: it requires `ntdll.dll` in both `x86_64-windows` and `i386-windows` and rejects a Windows executable, DLL or driver whose PE machine type does not match its directory.
- Runtime packager recursively validates the Android/Bionic PRoot executable and loader dependencies: every non-system ELF dependency must resolve inside `host/lib`, and system dependencies are limited to public Android NDK libraries available at minSdk 26.
- The PRoot host payload is validated before the guest runtime build downloads Ubuntu packages or compiles Box64/Wine, then checked again before ZIP packaging; a broken host artifact now fails at the inexpensive preflight stage.
- The Termux PRoot host assembler itself checks the real ELF dependency closure and publishes its output transactionally; failed validation does not leave a half-written host package.
- Coroutine PRoot process supervision with one process-exit waiter, merged stdout/stderr, main-thread callbacks, serialized stop requests, graceful/forced termination and output draining before the exit callback. Process creation is cancellation-safe: if the screen closes before a process session is handed back, the new process is stopped instead of being orphaned.
- WinePrefixInitializer that runs Box64 → Wine's unified Linux ELF loader → the AMD64 PE `wineboot.exe --init` program for a new per-game prefix, serializes requests, enforces a timeout and verifies system.reg/user.reg before permitting game launch (host-tested with a fake PRoot process).
- GameLaunchCoordinator that starts the display session before building the launch command, owns process/display lifetime, and closes the display once the process exits or is stopped, including cancellation and stop failures.
- Serialized display-session resize/close operations and JVM regression tests for idempotent cleanup and concurrent PRoot stop calls.
- JVM regression tests for command construction, process output/termination and rejection of placeholder Wayland socket files.
- A compositor-facing GameDisplayBackend/GameDisplaySession contract that accepts an Android Surface and exports a per-session Wayland endpoint.
- A minimal Wawona JNI compatibility class using the exact upstream package and native symbol names, with ANGLE libraries loaded in upstream order.
- A WawonaDisplayBackend prototype with one-active-session process guarding, serialized JNI operations, safe surface/core teardown ordering, and app-private XDG_RUNTIME_DIR checks that reject the upstream /data/local/tmp fallback.
- Session-bound Wawona input dispatch: touch/key/mouse/scroll JNI events are serialized through the active display lease; events from a released or stale session are dropped.
- A Wawona runtime asset installer for XKB/fonts/optional Weston data and a SurfaceView host for basic multi-touch, keyboard and mouse input. Input binding is explicit via the owning Wawona GameDisplaySession; Surface destruction disables input before display teardown.
- A pinned Wawona APK extractor that lazily stages only the JNI roots, recursive ELF dependency closure (including versioned SONAMEs), optional SwiftShader ICD library and selected assets; Python regression tests cover dependency closure, unresolved libraries, asset traversal and unrelated payload exclusion.
- Android lstat-based validation that the configured Wayland endpoint is an actual Unix-domain socket, not a regular file; its runtime directory must also stay inside app-private cache storage.
- A process-wide Wine-prefix initialization lock shared across GameLaunchCoordinator instances.
- Activity teardown tracks in-flight launches and stop jobs so a cancelled launch or destroyed game screen cannot cancel process/display cleanup while it is still running.
- Focused GitHub Actions checks: Python runtime-packager tests and JVM unit tests only. The Wawona/APK packaging workflow is manual-only and must not run during normal runtime development.

## Current Android user flow

1. Choose a Windows visual-novel folder.
2. The scanner checks EXE headers without running them. It tries to identify the main game file from the folder name and known engine filenames; ambiguous cases remain a manual choice.
3. Import the full folder into app-private storage. The original folder is not modified.
4. The game appears in the library with its selected EXE and detected architecture.
5. When a runtime is installed, the user can press **Играть**. If the runtime is absent, the button is unavailable and the UI explains why.
6. Play opens a dedicated immersive Android game screen, not a Windows desktop or file manager. Wawona is intended to display only the launched game window.
7. A finger tap maps to a left mouse click; a single-finger drag moves the pointer. Extra simultaneous fingers are ignored for now.
8. Before starting Wine, the app checks the runtime files, both glibc loaders, Box64 libraries, all Wine Wayland driver files and the live Wayland socket. It initializes a private Wine prefix for the selected game before running its EXE.

**Important:** the screen, library button and launch orchestration are connected in source code, but there is not yet a built runtime ZIP, staged Wawona native payload or successful physical-device launch. The UI flow is implemented; actual game execution is still unproven. Import and EXE inspection never execute the selected game.

## Runtime backend strategy

RamenWine supports three backend package IDs in its runtime inventory:

- bionic-arm64ec-fex
- bionic-x64-box64
- glibc-proot-box64

For the first end-to-end launch, prioritize the glibc-proot-box64 contract: an ARM64 Android/Bionic PRoot host, an ARM64 Linux/glibc guest rootfs, an ARM64 Linux Box64 executable inside that guest, and an x86-64 glibc Wine 11 WoW64 payload installed alongside the ARM64 userspace. The rootfs must provide both `lib/ld-linux-aarch64.so.1` (Box64) and `lib64/ld-linux-x86-64.so.2` (Wine), plus Wine's Wayland driver connected to the Wawona socket. This is a prototype choice to reach the first visible window, not a claim that it is the final fastest backend. Do not use Box64's native Termux/Bionic build: upstream labels it experimental and says it will not run Linux binaries. Defer Bionic ARM64EC/FEX and Bionic x64/Box64 until the first launch works; do not branch into multiple runtime backends in parallel.

The offline ZIP format is documented in Android Runtime Backends. The package builder lives in tools/package_runtime.py.

## Display backend research

Piperella Wayland Compositor was reviewed as a possible Android SurfaceView/Wayland implementation. It is a substantial proof-of-concept, but is not included as a dependency because its project is GPL-3.0 and declares minSdk 36, while RamenWine currently targets minSdk 26. Its Steam example also has an unresolved documentation/code mismatch and GPU-dependent limitations.

See Android Display Backend Research and [Wawona Integration](WAWONA_INTEGRATION.md). Wawona adapter source and the real-socket precondition are now implemented; its generated native payload and device rendering are not yet validated.

## What is not implemented or not yet proven

- No complete PRoot + ARM64 Box64 + Wine 11 runtime ZIP has been built and installed on a phone.
- No full ARM64 Linux/glibc rootfs is currently bundled in the app.
- The Wawona JNI/native payload and its required runtime assets have not yet been built and staged into an installable app package. The Android surface/launch integration is source code only until that payload is present.
- Wine's real `wineboot.exe --init` has not been run with the new rootfs. Prefix tests use a controlled fake process, not Wine itself.
- No original Win32 GUI/GDI test executable has been displayed through Wine and Wawona.
- No original or appropriately licensed visual novel has been launched on an ARM64 Android device.
- Process teardown, pause/resume and surface recreation have not been exercised on physical hardware.
- No DXVK, WineD3D, D8VK, VKD3D, DirectDraw or Android Vulkan-driver integration exists. Older games may therefore render incorrectly or not at all.
- No Android audio bridge has been implemented. The first milestone is a visible, touch-controlled game window; sound is a later integration.
- Save-state handling, game-specific input profiles and game pause/resume controls are not complete.
- Runtime metadata is not signed. SHA-256 detects damaged files but does not authenticate who published a package.

## Build toolchain

- Android Gradle Plugin: 9.1.1
- Gradle: 9.3.1
- Compose Compiler Gradle plugin: 2.2.10
- Android compile/target SDK: 36
- Minimum Android SDK: 26
- Android NDK: 30.0.16248370
- CMake: 3.22.1
- Compose BOM: 2026.04.01
- JDK: 17

Automated Python and JVM tests validate source-level behavior only. They do not build or install the full runtime ZIP, stage the Wawona native payload, or substitute for a real Android/ARM64 device launch.

## Next engineering steps — runtime first, APK last

1. Produce and validate the Android/Bionic PRoot host payload from its existing pinned source inputs.
2. On Ubuntu 24.04 x86-64, run `tools/build_glibc_runtime.py` with that host payload to generate the offline ARM64-rootfs/Box64/Wine ZIP. Keep source hashes and package provenance in the result.
3. Validate every ELF/PE architecture and the complete runtime dependency closure. A manifest or file-presence check alone is not proof that Wine works.
4. Install the ZIP through the Android app and initialize a real per-game Wine 11 WoW64 prefix on an ARM64 phone.
5. Show a small original Win32 GUI/GDI test window over the Wawona Wayland socket, then verify finger taps, pointer movement, resize and clean shutdown.
6. Only after the GUI test works, launch an original or appropriately licensed visual-novel fixture and record engine, architecture, rendering, input and audio results.
7. Complete audio, graphics acceleration, pause/resume, save/load and runtime signing before claiming broader game compatibility.

## Engineering rules

- A detected EXE is not a supported game.
- A runtime manifest with files present is not proof of successful execution.
- A SHA-256 match does not authenticate a runtime publisher.
- Never execute game files during scanning or import.
- Do not remove system APIs solely to shrink the package.
- Prefer a reproducible runtime package with pinned sources, checksums and provenance; do not bundle/download it through repeated APK builds.
- Do not assemble or upload an APK during runtime development. The Wawona/APK workflow is reserved for one deliberate final packaging run after the visual-novel launch milestone passes.
- Keep a fallback backend and per-game compatibility profiles.


The current Wawona build and validation procedure is documented in [Wawona Integration](WAWONA_INTEGRATION.md). The CI workflow still does not build the Wawona native payload or an APK.
