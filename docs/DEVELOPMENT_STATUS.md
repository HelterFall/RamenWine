# RamenWine development status

**Snapshot:** 2026-11-10  
**Current milestone:** Android host, safe game import, offline runtime package installation, PRoot/Wine launch scaffolding and Wawona display integration research.

## Implemented

- Android Gradle application using Kotlin and Jetpack Compose.
- ARM64-first (arm64-v8a) Android NDK/CMake native bridge.
- Android Storage Access Framework folder selection and persisted read permission.
- Bounded, read-only game-folder scan and safe relative-path validation.
- Bounded PE header inspection for x86, x86-64, ARM32, ARM64 and ARM64EC COFF machine values.
- Explicit selection of the intended game EXE candidate.
- Streamed copy of the selected game folder into app-private storage, with staging cleanup on failure/cancellation and live progress.
- Import guardrails: maximum 32 directory levels, 100,000 entries, 64 GiB total bytes and 128 MiB free-space reserve.
- App-private JSON library metadata, atomically replaced and validated on load.
- Runtime backend inventory for Bionic ARM64EC/FEX, Bionic x64/Box64 and glibc/PRoot/Box64 package families.
- Offline runtime ZIP installer with bounded streaming extraction, SHA-256 payload checks, executable flags, safe path/symlink validation, staged activation and rollback after failed post-install inventory.
- Python runtime package builder with manifest, hashes, executable flags and symlink metadata.
- PRoot/Wine command builder that validates paths and runtime executables, prepares per-game directories and creates an argument-vector without a shell.
- PRoot/Wine process supervisor that starts the validated command with ProcessBuilder, merges stdout/stderr, reports exit and supports graceful then forced stop.
- Wawona support-asset SHA-256 verifier and JVM tests for missing, modified, traversal and escaping-symlink cases.
- Wawona native-library inventory, a minimal JNI wrapper matching the pinned upstream Android symbols, an in-process compositor lifecycle adapter and a SurfaceHolder-backed SurfaceView with basic touch/hardware-key forwarding.
- GameSessionCoordinator to order compositor start, Surface attachment, PRoot command construction, process launch and display teardown.
- JVM/Python tests and GitHub Actions workflow for Android build, tests, lint and debug APK artifacts.

## Current Android user flow

1. Choose a game folder.
2. The scanner lists EXE candidates and their PE machine architecture.
3. Choose the intended game EXE.
4. Import the complete folder into the app-private games/<uuid> directory.
5. Watch copied-file and byte progress.
6. Save game metadata in the local library index.
7. Optionally install a runtime ZIP selected from local device storage.

The UI does not expose a Play action. Scanning and importing never execute game code. Runtime file inventory and a successful ZIP install prove package presence/integrity only; they do not prove that Wine or a Windows program can run.

## Runtime launch scaffolding

The current glibc/PRoot path has two code components:

- ProotWineCommandBuilder checks the imported game ID and EXE path, validates the runtime rootfs and host PRoot executable, requires Box64 and the unified Wine 11 loader at rootfs/usr/bin/wine, prepares bind mount targets and constructs the environment/argument vector.
- ProotWineProcessSupervisor starts that command without a shell, streams diagnostics and waits for the PRoot process. It is not yet connected to the Compose UI or a GameSession coordinator.

The builder requires a Wayland socket before it will create a command. It maps the supplied host runtime directory to /run/user/<uid> inside the rootfs. Per-game prefix/home/temp directories are prepared, but first-time Wine prefix initialization and child-process-tree supervision have not been validated on a device.

## Display backend decision

Wawona is the current upstream display/compositor candidate. The upstream repository is MIT-licensed at its root and uses a shared Rust Wayland core with a Kotlin Android host, JNI and native Android presentation. The reviewed upstream snapshot is Wawona/Wawona commit dd745564ffe4e28628c9cf2525c620bcfd4ebed5 (Cargo package version 26.11.2).

Wawona is not a drop-in Gradle library: its Android directory builds a full application, and its native output is assembled through a Nix/NDK dependency graph. The upstream Android host demonstrates the required Surface/JNI lifecycle and creates XDG_RUNTIME_DIR below the app cache directory. RamenWine currently contains only WawonaAssetIntegrity.kt and its tests; there is no Wawona native library, compositor host, asset extraction pipeline or Wayland socket producer in this repository.

The root MIT license does not make every transitive native dependency MIT. Before distribution, pin the upstream revision and review the licenses/notices of every component actually linked or bundled.

## What is not implemented or validated yet

- No Wine, Box64, FEX or PRoot runtime binaries are bundled in the repository.
- No glibc rootfs is built or installed by the app.
- No pinned signing key or signed runtime release metadata exists. SHA-256 in a ZIP detects payload mismatch; it does not authenticate the publisher.
- No real Windows EXE has been launched by RamenWine.
- The PRoot command builder and process supervisor are not wired to the app's Play/session UI and have not been validated on a physical device.
- No Wawona compositor is linked or loaded; no in-process Wayland server or Android display socket is created by RamenWine.
- No Android game surface is attached to Wine; no per-game Wine prefix is initialized with wineboot.
- No DXVK, WineD3D, D8VK, VKD3D, DirectDraw or Android Vulkan-driver integration exists.
- No production input, IME, audio, save-management, pause/resume or child-process lifecycle bridge exists.
- No physical Android device has been validated by this bootstrap.

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

The last explicitly documented successful Android build/test/lint run passed on commit 500374b9311e938a7931b48e456daeeb5def3f88. The later PRoot/Wine and Wawona changes must not be treated as device-validated merely because JVM or packaging tests exist.

## Next engineering steps

1. Build and stage the pinned Wawona Android native libraries for arm64-v8a, including the exact ANGLE/JNI dependency closure.
2. Verify native loading, compositor readiness and the actual app-private Wayland socket on an Android device.
3. Add a GameSession coordinator that owns the Wawona display session and PRoot/Wine process lifecycle.
4. Connect the Wawona socket endpoint to ProotWineCommandBuilder and start an original Win32 GDI test application only after both compositor and surface are ready.
5. Verify pause/resume, surface recreation, IME/input and complete process teardown on a physical ARM64 device.
6. Add 32-bit Windows execution and only then begin graphics backend selection and visual-novel engine compatibility tests.

## Engineering rules

- A detected EXE is not a supported game.
- Runtime manifest checks are FILES_PRESENT, not proof of execution.
- A SHA-256 match does not authenticate a runtime publisher.
- Never execute game files during scanning or import.
- Do not enable Play until the display socket, process lifecycle and first Windows GUI smoke test are real.
- Keep the display abstraction independent of Wawona so a future backend can be substituted without changing the game manager.
- Keep runtime packages offline-capable and per-game profiles isolated.

See README.md, Android Runtime Backends, Android Display Backend and PRoot Wine Launch for the current design contracts.