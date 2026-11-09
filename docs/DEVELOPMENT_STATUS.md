# RamenWine development status

**Snapshot:** 2026-11-09  
**Current milestone:** Android host, safe executable inspection, private game import, offline runtime package installation and backend inventory.

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
- Coroutine PRoot process supervision with one process-exit waiter, merged stdout/stderr, main-thread callbacks, serialized stop requests, graceful/forced termination and output draining before the exit callback.
- WinePrefixInitializer that runs Box64 → wineboot --init for a new per-game prefix, serializes requests, enforces a timeout and verifies system.reg/user.reg before permitting game launch (host-tested with a fake PRoot process).
- GameLaunchCoordinator that starts the display session before building the launch command, owns process/display lifetime, and closes the display once the process exits or is stopped, including cancellation and stop failures.
- Serialized display-session resize/close operations and JVM regression tests for idempotent cleanup and concurrent PRoot stop calls.
- JVM regression tests for command construction, process output/termination and rejection of placeholder Wayland socket files.
- A compositor-facing GameDisplayBackend/GameDisplaySession contract that accepts an Android Surface and exports a per-session Wayland endpoint.
- Android lstat-based validation that the configured Wayland endpoint is an actual Unix-domain socket, not a regular file; its runtime directory must also stay inside app-private cache storage.
- A process-wide Wine-prefix initialization lock shared across GameLaunchCoordinator instances.
- Focused GitHub Actions checks: Python runtime-packager tests and JVM unit tests only; APK packaging, artifact upload, lint and NDK/CMake setup are deferred.

## Current Android user flow

1. Choose a game folder.
2. The scanner lists EXE candidates and their PE machine architecture.
3. Choose the intended game EXE.
4. Import the complete folder into the app-private games/<uuid> directory.
5. Watch copied file count and byte progress.
6. Save the game title, relative path, EXE path, architecture and import size in the local library index.
7. Optionally install a runtime ZIP selected from local device storage.

The current UI clearly says that imported games cannot be launched yet. EXE inspection/import never executes the inspected game. The PRoot command builder, process supervisor, WinePrefixInitializer and GameLaunchCoordinator are implemented as a separate runtime layer, but they are not connected to a Play action because no concrete compositor backend is available yet. Prefix initialization is tested with a controlled fake PRoot process; a real Wine package/rootfs and physical-device launch remain unverified.

## Runtime backend strategy

RamenWine supports three backend package IDs in its runtime inventory:

- bionic-arm64ec-fex
- bionic-x64-box64
- glibc-proot-box64

The Bionic-native path is the performance experiment. The glibc + PRoot path is retained as a compatibility fallback. Public Bionic ARM64EC/Hangover packages are research inputs and need device/game validation before production use.

The offline ZIP format is documented in Android Runtime Backends. The package builder lives in tools/package_runtime.py.

## Display backend research

Piperella Wayland Compositor was reviewed as a possible Android SurfaceView/Wayland implementation. It is a substantial proof-of-concept, but is not included as a dependency because its project is GPL-3.0 and declares minSdk 36, while RamenWine currently targets minSdk 26. Its Steam example also has an unresolved documentation/code mismatch and GPU-dependent limitations.

See Android Display Backend Research for the findings and integration gates. The Wawona-based interface and real-socket precondition are now defined, but no Wayland compositor is currently integrated.

## What is not implemented yet

- No Wine, Proton, FEX, Box64, Box86 or PRoot runtime binaries are bundled.
- No Linux rootfs is built or installed.
- No signed runtime package metadata or pinned release signing key exists. SHA-256 verifies payload integrity but does not authenticate the publisher.
- GameLaunchCoordinator can manage a display/process session when a backend is supplied, including cleanup on cancellation/stop failure, but no concrete compositor is registered and the coordinator is not yet wired to the Compose UI.
- Process teardown has not been validated against Android PRoot/Wine child-process behavior on a device.
- WinePrefixInitializer has host-side JVM coverage only; it has not been validated against a real Wine build, the final Linux rootfs or an Android device.
- No Android game surface is connected to a Wine display/window backend.
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

The latest completed Android CI run on main (run #66, commit 439aa47ad63720c089926900fcb7bd120aeca711) passed build, unit tests and lint. The current branch code head (945a4ed, run #88) passed all 5 Python runtime-packager tests and Gradle testDebugUnitTest, including Wine-prefix bootstrap, cross-coordinator serialization and Wayland/prefix path-containment tests. The focused workflow does not build an APK, run lint, or set up NDK/CMake. Host CI success does not substitute for physical Android/ARM64 device validation.

## Next engineering steps

1. Complete CI verification for import progress and the display-research documentation commit.
2. Decide the RamenWine distribution license before considering any GPL-3.0 compositor integration.
3. Prototype a RamenWine-owned Wawona adapter behind GameDisplayBackend. First package/review the Rust static library and Nix-staged Android native dependencies, then wrap Wawona's process-global compositor as a single-active-session backend. Wawona is not a reusable Gradle library and does not provide independent per-session compositor instances.
4. Build a minimal Android/Bionic Wine + ARM64EC/FEX package and validate its ELF dependencies and ABI.
5. Build a glibc rootfs + PRoot + Box64 fallback package.
6. Run the same original Windows GUI test executable through each backend.
7. Implement a concrete GameDisplayBackend, then connect GameLaunchCoordinator to the Compose Surface lifecycle and validate wineboot, prefix state and process teardown on a real ARM64 device.
8. Connect Wine's Wayland/X11 display path to the Android game surface.
9. Add 32-bit Windows execution after a confirmed 64-bit path.
10. Add graphics and audio backend probing only after the first Wine window renders.
11. Build a reproducible visual-novel compatibility matrix on physical ARM64 devices.

## Engineering rules

- A detected EXE is not a supported game.
- A runtime manifest with files present is not proof of successful execution.
- A SHA-256 match does not authenticate a runtime publisher.
- Never execute game files during scanning or import.
- Do not remove system APIs solely to shrink the package.
- Prefer local/offline runtime packages; the first launch must not depend on downloading binaries.
- Keep a fallback backend and per-game compatibility profiles.
