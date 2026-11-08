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
- GitHub Actions workflow for Python tests, Android build, unit tests, lint and debug APK artifact upload.

## Current Android user flow

1. Choose a game folder.
2. The scanner lists EXE candidates and their PE machine architecture.
3. Choose the intended game EXE.
4. Import the complete folder into the app-private games/<uuid> directory.
5. Watch copied file count and byte progress.
6. Save the game title, relative path, EXE path, architecture and import size in the local library index.
7. Optionally install a runtime ZIP selected from local device storage.

The current UI clearly says that imported games cannot be launched yet. EXE inspection/import never executes the inspected game. Runtime installation checks package integrity and inventory; it does not execute Wine or test a Windows program.

## Runtime backend strategy

RamenWine supports three backend package IDs in its runtime inventory:

- bionic-arm64ec-fex
- bionic-x64-box64
- glibc-proot-box64

The Bionic-native path is the performance experiment. The glibc + PRoot path is retained as a compatibility fallback. Public Bionic ARM64EC/Hangover packages are research inputs and need device/game validation before production use.

The offline ZIP format is documented in Android Runtime Backends. The package builder lives in tools/package_runtime.py.

## Display backend research

Piperella Wayland Compositor was reviewed as a possible Android SurfaceView/Wayland implementation. It is a substantial proof-of-concept, but is not included as a dependency because its project is GPL-3.0 and declares minSdk 36, while RamenWine currently targets minSdk 26. Its Steam example also has an unresolved documentation/code mismatch and GPU-dependent limitations.

See Android Display Backend Research for the findings and integration gates. No Wayland compositor is currently integrated.

## What is not implemented yet

- No Wine, Proton, FEX, Box64, Box86 or PRoot runtime binaries are bundled.
- No Linux rootfs is built or installed.
- No signed runtime package metadata or pinned release signing key exists. SHA-256 verifies payload integrity but does not authenticate the publisher.
- No process supervisor launches a Windows EXE.
- No Wine prefix is created for each imported game.
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

The last completed Android build/test/lint run passed on commit 500374b9311e938a7931b48e456daeeb5def3f88. New import-progress and display-research changes are undergoing their own CI run; do not treat them as validated until that run completes successfully.

## Next engineering steps

1. Complete CI verification for import progress and the display-research documentation commit.
2. Decide the RamenWine distribution license before considering any GPL-3.0 compositor integration.
3. Choose a display backend strategy: test an independent Wayland compositor or prototype a different Android display bridge.
4. Build a minimal Android/Bionic Wine + ARM64EC/FEX package and validate its ELF dependencies and ABI.
5. Build a glibc rootfs + PRoot + Box64 fallback package.
6. Run the same original Windows GUI test executable through each backend.
7. Add a process supervisor, Wine prefix creation and first real Windows EXE launch.
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
