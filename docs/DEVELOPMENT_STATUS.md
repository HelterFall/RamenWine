# RamenWine development status

**Snapshot:** 2026-11-10  
**Current milestone:** Android host, safe game import, offline runtime package installation, PRoot/Wine launch scaffolding, Wawona display adapter and per-game launch-flow integration.

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
- Backend-aware glibc/PRoot package contract: verifies the PRoot host executable, guest env, Box64, Wine, wineboot and host-library payload both before extraction and in the installed runtime inventory.
- ELF/PE ABI inspection for the PRoot/Wine package: requires AArch64 ELF for Android PRoot, guest env and Box64, x86-64 ELF for Wine, and AMD64 PE32+ for wineboot. The Python packager and Android post-install inventory perform the same header checks without executing the files.
- Rootfs-aware absolute symlink handling: Linux absolute links inside rootfs resolve from the guest root, remain confined to rootfs and are rewritten as app-private relative links; absolute host-payload links are rejected.
- Python runtime package builder with manifest, hashes, executable flags, symlink metadata and glibc/PRoot launch-prerequisite validation.
- PRoot/Wine command builder that validates paths and runtime executables, separates Android-host and Linux-guest environments, prepares per-game directories and creates an argument-vector without a shell.
- WinePrefixManager that initializes each per-game prefix with wineboot.exe, reuses a matching runtime-version marker and updates an existing prefix when the Wine package version changes.
- PRoot/Wine process supervisor that starts the validated command with ProcessBuilder, merges stdout/stderr, reports exit and supports graceful then forced stop and bounds final output draining so an inherited pipe cannot hold the session open indefinitely.
- Wawona support-asset SHA-256 verifier and JVM tests for missing, modified, traversal and escaping-symlink cases.
- Wawona native-library inventory that verifies every manifest-listed arm64-v8a shared object and SHA-256 directly inside the installed APK, a minimal JNI wrapper matching the pinned upstream Android symbols, an in-process compositor lifecycle adapter and a SurfaceHolder-backed SurfaceView with basic touch/hardware-key forwarding.
- A GameSessionCoordinator that orders compositor start, Surface attachment, PRoot command construction, process launch and display teardown.
- A pinned Wawona APK importer and Nix build script that stage all arm64-v8a native libraries plus XKB/font/Weston assets and generate a SHA-256 provenance manifest.
- A Compose Surface host and initial per-game display/Play flow that only enables launch after the native Surface is attached and the compositor socket is ready.
- JVM/Python tests and GitHub Actions workflow for Android build, tests, lint and debug APK artifacts.

## Current Android user flow

1. Choose a game folder.
2. The scanner lists EXE candidates and their PE machine architecture.
3. Choose the intended game EXE.
4. Import the complete folder into the app-private games/<uuid> directory.
5. Watch copied-file and byte progress.
6. Save game metadata in the local library index.
7. Optionally install a runtime ZIP selected from local device storage.
8. Choose “Open game display” for an imported game; the UI checks the pinned Wawona package and starts the compositor.
9. Wait for the real Android Surface to attach; the launch button stays disabled until attachment is reported.
10. Request the Windows EXE launch; the coordinator validates runtime paths and requires the active Wayland socket before starting PRoot/Wine.

The UI now exposes an initial per-game flow: prepare and verify Wawona off the UI thread, attach its Surface, then request a Windows EXE launch. The flow fails closed when the native Wawona package or a compatible PRoot/Wine runtime is absent. Scanning and importing never execute game code. Runtime file inventory and a successful ZIP install prove package presence/integrity only; they do not prove that Wine or a Windows program can run.

## Runtime launch scaffolding

The current glibc/PRoot path has a command builder and process supervisor, coordinated by GameSessionCoordinator:

- ProotWineCommandBuilder checks the imported game ID and EXE path, validates the runtime rootfs and host PRoot executable, requires Box64 and the unified Wine 11 loader at rootfs/usr/bin/wine, prepares bind mount targets and constructs the environment/argument vector.
- ProotWineProcessSupervisor starts that command without a shell, streams diagnostics and waits for the PRoot process. GameSessionCoordinator connects it to the Compose per-game launch flow and owns display/process teardown.

The builder requires a Wayland socket before it will create a command. It maps the supplied host runtime directory to /run/user/<uid> inside the rootfs. Per-game prefix/home/temp directories are prepared. WinePrefixManager now runs wineboot.exe -i or -u through Box64/Wine before the game starts and writes a version marker only after the registry files exist. The package parser and installed inventory now reject a glibc/PRoot package missing those launch prerequisites. This is source-level implementation and has not yet been validated with a real Wine package or on a device. Child-process-tree supervision remains unproven.

## Display backend decision

Wawona is the selected upstream display/compositor. The upstream repository root is MIT-licensed and uses a shared Rust Wayland core with a Kotlin Android host, JNI and native Android presentation. The reviewed upstream snapshot is Wawona/Wawona commit dd745564ffe4e28628c9cf2525c620bcfd4ebed5 (Cargo package version 26.11.2).

RamenWine now contains a narrow JNI wrapper, native-library inventory, pinned APK provenance reader, XKB/font/Weston asset installer, Android host-keymap bridge, WawonaDisplayBackend, SurfaceView/Compose host and GameSessionCoordinator. The source-to-bundle recipe is tools/build_wawona_android.sh; it builds the pinned upstream revision with Nix and stages native libraries/assets locally. Generated binary files are intentionally Git-ignored and are not present in a clean checkout until that script is run.

The upstream root MIT license does not make every transitive native dependency MIT. Before distribution, review the notices/licenses of every actual linked or bundled component.

## What is not implemented or validated yet

- No Wine, Box64, FEX or PRoot runtime binaries are bundled in the repository.
- No glibc rootfs is built or installed by the app.
- No pinned signing key or signed runtime release metadata exists. SHA-256 in a ZIP detects payload mismatch; it does not authenticate the publisher.
- No real Windows EXE has been launched by RamenWine.
- The initial Play/session UI and coordinator are wired, but the native package/runtime prerequisites are not built or installed and the launch path has not been validated on a physical device.
- The generated Wawona native libraries are not present in a clean checkout until tools/build_wawona_android.sh is run; therefore no in-process Wayland server or Android display socket is running in the current APK.
- No Android game surface has been validated with a real Wine client; the new wineboot flow has not yet been exercised with an actual Wine package.
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

The last explicitly documented successful Android build/test/lint run passed on commit 500374b9311e938a7931b48e456daeeb5def3f88. The later PRoot/Wine, Wawona and Compose launch-flow changes have not been run through Gradle/CI in this task or verified on a physical Android device; do not treat them as device-validated.

## Next engineering steps

1. Run tools/build_wawona_android.sh on a Nix-capable build host and build the RamenWine APK with the generated Wawona native bundle.
2. Build and install a matching glibc + PRoot + Box64 + Wine runtime ZIP that satisfies the package contract; the package builder only packages already-built binaries and does not produce them.
3. Verify native loading, compositor readiness and the actual app-private Wayland socket on a physical ARM64 Android device.
4. Run an original Win32 GDI test executable and verify rendering inside the RamenWine Surface.
5. Verify pause/resume, surface recreation, keyboard/IME, touch, process-tree cleanup and long-session stability.
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