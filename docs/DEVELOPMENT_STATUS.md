# RamenWine development status

**Snapshot:** 2026-11-09  
**Current milestone:** finish a real Windows execution runtime and launch an original visual-novel test executable. The installable APK is a final packaging step only.

## Implemented

- Android Gradle project using Kotlin and Jetpack Compose.
- ARM64-first (arm64-v8a) Android NDK/CMake native bridge.
- Android Storage Access Framework folder selection and persisted read permission.
- Bounded, read-only game-folder scan.
- Safe relative-path validation for Windows game file names.
- Bounded PE header inspection for x86, x86-64, ARM32, ARM64 and ARM64EC COFF machine values.
- Explicit selection of the game's main EXE candidate.
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
- PRoot launch preflight now requires both the ARM64 glibc loader (for ARM64 Linux Box64) and the x86-64 glibc loader (for Wine), plus both halves of Wine's Wayland driver; Android/Bionic `LD_LIBRARY_PATH` is reserved for the PRoot host and guest x86-64 libraries use `BOX64_LD_LIBRARY_PATH`.
- PRoot runtime packaging now requires the separately-installed ARM64 helper at `host/libexec/proot/loader` and passes its app-private path through `PROOT_LOADER`; this avoids the Termux build prefix being baked into the executable.
- Added a manual-only Termux build workflow for the Android/Bionic PRoot host and a staging tool that verifies exact package versions, symlink containment, ARM64 ELF files and records package/toolchain hashes. This builds only a separate runtime artifact, not an APK; it has not yet been run end-to-end.
- Coroutine PRoot process supervision with one process-exit waiter, merged stdout/stderr, main-thread callbacks, serialized stop requests, graceful/forced termination and output draining before the exit callback.
- WinePrefixInitializer that runs Box64 → wineboot --init for a new per-game prefix, serializes requests, enforces a timeout and verifies system.reg/user.reg before permitting game launch (host-tested with a fake PRoot process).
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
- Focused GitHub Actions checks: Python runtime-packager tests and JVM unit tests only. The Wawona/APK packaging workflow is manual-only and must not run during normal runtime development.

## Current Android user flow

1. Choose a game folder.
2. The scanner lists EXE candidates and their PE machine architecture.
3. Choose the intended game EXE.
4. Import the complete folder into the app-private games/<uuid> directory.
5. Watch copied file count and byte progress.
6. Save the game title, relative path, EXE path, architecture and import size in the local library index.
7. Optionally install a runtime ZIP selected from local device storage.

The current UI clearly says that imported games cannot be launched yet. EXE inspection/import never executes the inspected game. The PRoot command builder, process supervisor, WinePrefixInitializer, GameLaunchCoordinator and WawonaDisplayBackend are implemented as a separate runtime layer, but there is no real Wine package/rootfs and no user-facing Play flow yet. Prefix initialization is tested with a controlled fake PRoot process only. The immediate goal is not another APK: it is to produce a launchable runtime package, prove that Wine initializes a prefix, then open an original Win32 GUI test and finally a visual-novel executable.

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

## What is not implemented yet

- No Wine, Proton, FEX, Box64, Box86 or PRoot runtime binaries are bundled.
- No Linux rootfs is built or installed.
- No signed runtime package metadata or pinned release signing key exists. SHA-256 verifies payload integrity but does not authenticate the publisher.
- WawonaDisplayBackend source is present but its Nix-built native payload has not yet been generated/staged in the app; it is not registered to a user-facing Play action or validated on physical hardware.
- GameLaunchCoordinator can manage a display/process session when a backend is supplied, including cleanup on cancellation/stop failure, but the coordinator is not yet wired to the Compose UI.
- Process teardown has not been validated against Android PRoot/Wine child-process behavior on a device.
- WinePrefixInitializer has host-side JVM coverage only; it has not been validated against a real Wine build, the final Linux rootfs or an Android device.
- WawonaSurfaceView exists with basic input forwarding, but no Android game surface is yet wired to the Play/Compose lifecycle or to a validated Wine display session.
- No DXVK, WineD3D, D8VK, VKD3D, DirectDraw or Android Vulkan-driver integration exists.
- No audio bridge, input mapping, game pause/resume integration or save management exists.
- No physical Android device has been tested by this bootstrap.

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

The latest full Android CI run on main (run #66, commit 439aa47ad63720c089926900fcb7bd120aeca711) passed build, unit tests and lint. The latest focused branch run, #131 on PR tree `7e674b7` (source changes from `135ac7e`), passed the Python runtime-packager suite and Gradle `testDebugUnitTest`; this includes the Wawona APK extractor tests, Android/Kotlin compilation and Wawona lease/path regression tests. The focused workflow does not build an APK, run lint, set up NDK/CMake or build the pinned Wawona native payload. Host CI success does not substitute for physical Android/ARM64 device validation.

## Next engineering steps — runtime first, APK last

1. Freeze the first target backend to glibc-proot-box64; keep Bionic/FEX as later experiments.
2. Run and validate the manual PRoot host workflow, then make its Docker builder base image and Ubuntu apt package set reproducible. After that, produce the full runtime package using the pinned Ubuntu Base 24.04.5 ARM64 seed, ARM64 Linux Box64 and x86-64 Wine 11 WoW64 with wineboot, the Wine Wayland driver and both ABI dependency closures.
3. Keep runtime inventory separate from launch readiness: the PRoot command builder rejects a missing ARM64 or x86-64 glibc loader, or either Wine Wayland driver half, before starting. Tighten the UI state later so a generic `FILES_PRESENT` manifest is never presented as proof that Wine is launch-ready.
4. Build Wine 11 with both PE targets (`--enable-archs=i386,x86_64`) and verify the Wine Wayland driver plus the unified `wine`/`wineboot` entry points; then validate the package independently of the APK by checking hashes/ELF architectures, running PRoot and Box64 smoke commands, and proving `wineboot --init` creates both registry files. Do not start another APK build for these checks.
5. Make Wine connect to Wawona over the bound Wayland socket and validate a small original Win32 GUI/GDI executable. Diagnose runtime logs and process teardown before opening game-specific work.
6. Add the first Play/session flow only after the runtime contract and compositor endpoint are proven; wire it to the existing imported-game record and GameLaunchCoordinator.
7. Launch an original, redistributable visual-novel test fixture; record engine, architecture, render path, input, audio, resume and save/load results. Expand compatibility from this first working fixture rather than trying every engine at once.
8. Complete the exact third-party license/NOTICE audit and runtime-package provenance/signing before distributing runtime binaries.
9. Only after the above gates pass, manually run the final APK packaging workflow once and verify the installable artifact on a physical ARM64 Android device.

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
