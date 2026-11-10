# RamenWine development status

**Snapshot:** 2026-11-10  
**Active milestone:** make the Android app own the complete Windows runtime lifecycle, bring up the Wawona display on the phone, and launch the first real Windows GUI/visual-novel executable.

## Product contract

The player experience is deliberately small:

1. Choose a game folder.
2. Let RamenWine scan it and suggest the main EXE.
3. Import the game into app-private storage.
4. Press **Играть**.
5. If the shared Windows runtime is missing or outdated, RamenWine prepares it itself, shows progress and continues the launch.

The player must never search for or select a runtime ZIP, install Wine/Box64/PRoot as separate apps, use a terminal or interact with a Windows desktop. The only folder the player selects is the game folder. The runtime ZIP is an internal build/transport format, not a user-facing workflow.

The runtime delivery decision and implementation plan are in [Runtime Delivery](RUNTIME_DELIVERY.md).

## Implemented in source

### Android app and game library

- Kotlin + Jetpack Compose Android app, ARM64-first NDK/JNI integration.
- Android Storage Access Framework folder selection and persisted read permission.
- Bounded, read-only game-folder scan; the scanner does not execute EXE files.
- Safe relative-path validation and bounded PE header inspection for x86, x86-64, ARM32, ARM64 and ARM64EC COFF machine values.
- Heuristic main-EXE selection using folder name and common VN filenames; ambiguous cases remain manually selectable.
- Streamed copy of the selected game into app-private storage, staging cleanup on failure/cancellation and live import progress.
- Import limits and free-space reserve; selected EXE must still be a valid PE image before import finalization.
- App-private JSON library metadata with atomic persistence.
- A library Play action wired to an immersive `GamePlayerActivity`; the intended game surface shows only the selected game's window, not a Windows desktop.
- Single-finger tap-to-left-click and drag-to-pointer-motion mapping; extra simultaneous fingers are currently ignored.

### Windows runtime orchestration

- Runtime backend inventory and manifest parser for Bionic ARM64EC/FEX, Bionic + Box64 and the selected glibc + PRoot + Box64 path.
- Offline runtime ZIP extraction engine with bounded streaming, SHA-256 file validation, safe paths/symlinks, staging, atomic activation and rollback. This is an internal installation primitive; it is no longer exposed through a runtime ZIP file picker in the Android UI.
- Python packager creates a manifest-backed archive with file hashes, executable flags and symlink metadata.
- PRoot/Box64/Wine argument-vector construction without a shell, including app-private per-game directories, host/guest path checks and Wayland endpoint validation.
- Wine 11 new WoW64 launch configuration for both x86 and x86-64 Windows PE programs.
- WinePrefixInitializer orchestration for isolated per-game prefixes; JVM tests use a controlled fake PRoot process and therefore do not prove that real Wine has booted.
- PRoot process supervision, merged output, graceful/forced stop, output draining and cancellation-safe cleanup.
- GameLaunchCoordinator owns the relationship between a display session, Wine prefix and PRoot process.
- ABI validators for ARM64/x86-64 ELF components and i386/AMD64 PE trees; package and launch preflight rejects incomplete or mixed-architecture payloads.

### Wawona display integration

- A RamenWine-owned minimal JNI adapter uses the exact upstream package and JNI symbol names; ANGLE is loaded in upstream order.
- A WawonaDisplayBackend prototype owns one compositor session at a time, serializes JNI calls, checks app-private runtime paths and verifies the Wayland socket using Android `lstat`.
- Wawona attaches to an Android `Surface`; the game Activity hosts the surface and binds touch/key/mouse events to the active display-session lease.
- A pinned Wawona APK extractor selects the ARM64 JNI roots, recursive ELF dependency closure, optional helper libraries and a small asset inventory; all selected assets are recorded with SHA-256 hashes.
- Wawona's optional Vulkan ICD manifest is now accepted by the provenance allow-list, copied into app-private `usr/share/vulkan/icd.d`, and hash-verified alongside XKB, fonts and Weston assets. Regression coverage was added for this mapping.
- A debug-only Wawona/Wayland SHM smoke screen exists. It is a graphics diagnostic only; it does not launch Wine or a game.

## Verified builds and device evidence

### GitHub build evidence

- APK packaging workflow [#42725634203](https://github.com/HelterFall/RamenWine/actions/runs/42725634203) completed successfully on commit `3f3be82f75aa124f1424e753cb5dea16c7b1b379`. It built `app-debug.apk`, verified the expected Wawona/ANGLE/Wayland native libraries and assets inside the APK, and uploaded the artifact.
- Focused Python and Android JVM checks passed on commit `6ed50634c0ad6c77e5e3afb8ee71e35b36dc0700` in run [#42732461795](https://github.com/HelterFall/RamenWine/actions/runs/42732461795).
- The APK workflow is manual-only again. Routine source changes use the lightweight Python/JVM checks rather than rebuilding the APK each time.

### What the phone test proved

The user's Android 16 / arm64-v8a device test showed:

- The app can select the game folder, scan 406 files, detect two EXE candidates and select `SaDisticBloOD.exe` as an x86 candidate.
- The game folder was imported into the app library.
- **Играть** remained disabled because the glibc/PRoot/Box64/Wine runtime was not installed.
- The Wawona diagnostic received a valid Android Surface, but the compositor stopped during asset-provenance validation, before the test window could render.

The Wawona failure was traced to a mismatch between the actual packaged asset inventory and the Android allow-list: the pinned APK includes `wawona/vulkan/icd.d/vk_swiftshader_icd.json`, while the installer only accepted XKB, fonts and Weston. The source fix now explicitly accepts that single Vulkan ICD subtree, maps it into app-private storage, copies it and includes it in SHA-256 verification. The new source passed focused CI. **A new APK containing this fix has not yet been built and retested on the phone.**

The earlier message `Unexpected runtime ZIP entry` appeared because the selected archive was not a RamenWine runtime package (which must contain `runtime-manifest.json` and `payload/...`). Players must not be asked to repeat that action. The debug runtime ZIP picker has now been removed from source; the already-installed older APK still contains the old UI until a replacement APK is built.

## Runtime backend decision

The first real launch target remains:

- Android/Bionic ARM64 PRoot host;
- Ubuntu ARM64/glibc guest rootfs;
- ARM64 Linux Box64;
- x86-64 glibc Wine 11 unified loader with i386 and x86-64 WoW64 PE trees;
- Wine Wayland driver connected to the app-owned Wawona socket.

The app must retain a broad Wine Win32/Win64 implementation. Do not create a whitelist of known VN engines or strip APIs simply because the current test title does not use them. Unknown/custom engines should go through the common Windows launch path. Optimize by excluding a Windows desktop shell, build tools, caches and unrelated desktop applications—not by arbitrarily deleting Wine API support.

Bionic ARM64EC/FEX and Bionic x86-64/Box64 remain deferred experiments; do not build several runtime paths in parallel before the selected backend launches a real game.

## Not yet implemented or not yet proven

- No complete PRoot + Ubuntu ARM64 + Box64 + Wine 11 runtime package has been assembled and validated on the phone.
- No full Wine/Box64/PRoot runtime is bundled in the APK.
- No in-app network `RuntimeProvisioner`, signed release index or app-managed runtime update channel exists yet.
- The offline ZIP installer is only an internal extraction/activation primitive; it does not fetch the package.
- The Play action is still gated by local runtime file presence; there is no automatic download/installation path yet.
- The Vulkan asset fix has passed source-level tests but has not yet been installed and confirmed on the physical phone.
- The Wawona SHM test window has not yet been observed rendering on the phone.
- Wine has not run a real `wineboot.exe --init` on the device.
- No original Win32 GUI test or visual novel has launched successfully on an ARM64 Android device.
- DirectX translation (DXVK/WineD3D/legacy DirectDraw/D3D12 paths), Android audio routing, video compatibility, gamepad mapping and long-session behavior require further integration and testing.
- Runtime metadata/package publisher authentication is not implemented. SHA-256 file checks detect corruption but do not authenticate the publisher.

## Runtime delivery target

The app will provision the base runtime itself from immutable, versioned RamenWine artifacts. A pinned public key in the app will verify a signed release index; the package digest and per-file SHA-256 values will be checked before staged extraction and atomic activation. The app will show first-run download/installation progress, retain the previous known-good version for rollback, and reuse the same shared runtime for every game. Each title receives its own Wine prefix.

The package format may remain ZIP internally. There must be no player file picker for it. Once provisioned, the base runtime is local and can be used offline. Optional graphics components may be app-managed add-ons when a title actually requires them.

## Next engineering steps

1. Build a new debug APK with the Vulkan ICD provenance fix; retest the Wawona SHM window on the phone and inspect native/Vulkan logs if it still remains black.
2. Finish the pinned PRoot host and full Ubuntu/Box64/Wine package build; record actual package size, source pins and complete dependency provenance.
3. Validate the runtime archive's ELF/PE machines, transitive dependencies, symlinks and Wine 11 WoW64 structure.
4. Publish an immutable runtime artifact and signed version catalog.
5. Implement the in-app RuntimeProvisioner (download, progress, retry/resume, signature verification, SHA-256 verification, staging, activation and rollback).
6. Connect **Играть** to: ensure shared runtime → initialize per-game Wine prefix → launch the selected EXE on the Wawona game surface.
7. Prove the path with x86 and x86-64 Windows GUI probes, then appropriately licensed visual-novel samples, including at least one engine not pre-identified by RamenWine.
8. Only then expand graphics backends, sound/video, input profiles and compatibility coverage.

## Engineering rules

- A detected EXE is not a supported game.
- Runtime files present is not proof that Wine can execute a game.
- A successful APK build is not proof of Windows compatibility.
- Do not execute game files during scanning or import.
- Do not gate compatibility on a list of known engine names.
- Do not remove Windows APIs merely to shrink the package.
- Do not expose runtime ZIP selection to the player.
- Keep per-game prefixes isolated and preserve rollback to a known-good shared runtime.
