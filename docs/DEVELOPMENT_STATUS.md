# RamenWine development status

**Snapshot:** 2026-11-09  
**Current milestone:** Android host, safe executable inspection, private game import and runtime backend inventory.

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
- Validation that the selected EXE is a PE image before import finalization.
- App-private JSON library metadata.
- Runtime backend inventory for Bionic ARM64EC/FEX, Bionic x64/Box64 and glibc/PRoot/Box64 packages.
- Runtime manifest checks for host ABI, required files, safe paths and executable permissions.
- Unit tests for PE parsing, game path validation and runtime manifest states.
- GitHub Actions workflow for Android build, unit tests, lint and debug APK artifact upload.

## Current Android user flow

1. Choose a game folder.
2. The scanner lists EXE candidates and their PE machine architecture.
3. Choose the intended game EXE.
4. Import the complete folder into the app-private games/<uuid> directory.
5. Save the game title, relative path, EXE path, architecture and import size in the local library index.

The current UI clearly says that imported games cannot be launched yet. EXE inspection/import never executes the inspected game.

## Runtime backend strategy

RamenWine supports three backend package IDs in its runtime inventory:

- bionic-arm64ec-fex
- bionic-x64-box64
- glibc-proot-box64

The Bionic-native path is the performance experiment. The glibc + PRoot path is retained as a compatibility fallback. The Bionic ARM64EC/Wine builds currently available publicly are testing builds, so RamenWine must not treat them as production-ready without device/game testing.

See Android Runtime Backends for the decision and package manifest contract.

## What is not implemented yet

- No Wine, Proton, FEX, Box64, Box86 or PRoot runtime binaries are bundled.
- No Linux rootfs is built or installed.
- No runtime package installer or SHA-256 package verifier exists.
- No process supervisor launches a Windows EXE.
- No Wine prefix is created for each imported game.
- No game surface is connected to a Wine display/window backend.
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

GitHub Actions passed the Android build/test/lint workflow for the scanner/import scaffold. Runtime backend manifest changes are undergoing a new CI run; check the Actions run before treating the latest revision as validated.

## Next engineering steps

1. Complete CI verification for the latest runtime backend manifest implementation.
2. Add safe local runtime package installation with SHA-256 verification and staged activation.
3. Build a minimal Android/Bionic Wine + ARM64EC/FEX launch prototype.
4. Build a glibc rootfs + PRoot + Box64 fallback prototype.
5. Run the same original x86-64 test executable through each backend and compare stability/performance.
6. Add 32-bit Windows execution after a confirmed 64-bit path.
7. Add the Android game surface, process supervision and correct pause/resume/exit lifecycle.
8. Add graphics and audio backend probing only after the first Wine window renders.
9. Build a reproducible visual-novel compatibility matrix on physical ARM64 devices.

## Engineering rules

- A detected EXE is not a supported game.
- A runtime manifest with files present is not proof of successful execution.
- Never execute game files during scanning or import.
- Do not remove system APIs solely to shrink the package.
- Prefer local/offline runtime packages; the first launch must not depend on downloading binaries.
- Keep a fallback backend and per-game compatibility profiles.
