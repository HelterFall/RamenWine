# Android game display backend and Wawona integration

**Status:** Kotlin/JNI adapter, support-asset bootstrap and SurfaceView host implemented; local native bundle build recipe added  
**Review snapshot:** 2026-11-10  
**Pinned Android package:** official Wawona v26.8.12 release APK, source commit `75ffe2982bd7d99b29b7e743c4f70a9becf61f86`

## Goal

Display a Windows game's graphical output inside the RamenWine Android application, without exposing a Linux desktop shell to the user. The display backend must own an Android Surface, start a Wayland compositor in the same app UID, publish a local Wayland socket for Wine, route input/IME, and survive Activity and Surface lifecycle changes.

Wine process execution and display are separate systems. ProotWineCommandBuilder already expects a Wayland socket; it deliberately refuses to construct a launch command until the socket exists. RamenWine now has a Wawona adapter that starts the in-process core and verifies the real socket, and the pinned Android release libraries/assets are staged by the checksum-verifying package importer before Android packaging.

## Selected candidate: Wawona

Upstream repository: https://github.com/Wawona/Wawona  
License: MIT at the upstream repository root (retain the copyright and permission notice).  
Android source: android/app and src/platform/android.

The current upstream architecture has:
- a shared Rust Wayland compositor core using Smithay and generated Wayland protocol bindings;
- a Kotlin/Jetpack Compose Android host;
- a JNI/C Android platform layer;
- native Android presentation using ANativeWindow and a dedicated render path, with Vulkan support;
- a SurfaceView host and input forwarding for touch, pointer, keyboard and Android IME;
- a local XDG_RUNTIME_DIR under the app cache directory, with the Wayland socket published from the native compositor core.

The Android Kotlin wrapper exposes lifecycle calls including nativeInit(cacheDir), nativeIsCompositorReady(), nativeSetSurface(surface), nativeDestroySurface(surface), nativeResizeSurface(width,height) and nativeShutdown(). The JNI layer creates the Rust core, starts/stops it, attaches the Android native window and drives presentation. The upstream WawonaNative class loads its own native library family; its JNI symbols are tied to the upstream package name unless RamenWine adds an explicit JNI registration/adapter layer.

## Integration constraints

### It is not a drop-in Android library

The upstream android/app Gradle module is a full application, not a small AAR intended for embedding. The Android native build is assembled through the upstream Nix dependency graph and links the Rust core plus platform/native dependencies. Copying one Kotlin SurfaceView class is not sufficient to get a compositor.

RamenWine uses minSdk 28 because the bundled DirectAudio Android relay is built against Android API 28 AAudio. The current adapter packages the checksum-pinned upstream Wawona 26.8.12 native release rather than rebuilding that large native dependency graph.

### License boundary

The Wawona repository root declares MIT. That does not automatically relicense all libraries linked into its Android application. Before bundling a native build, generate and review the exact third-party license/NOTICE inventory for the selected feature set, including renderer, Vulkan loader/driver shims, xkbcommon, Wayland, Weston assets and any optional clients. Do not assume every dependency is MIT.

The former Piperella candidate is not the selected route. It had a GPL-3.0 integration concern and a higher Android API floor; Wawona's current MIT-licensed root project and native Android host offer a better starting point, subject to the dependency audit above.

### App-private socket and process boundary

Wawona's Android JNI initialization sets XDG_RUNTIME_DIR under the supplied cache directory as <cacheDir>/wawona-runtime. RamenWine's display endpoint should therefore be based on the same app-private directory and the actual socket name returned/confirmed by the compositor. Do not launch the separately installed Wawona application and expect RamenWine to access its private Unix socket: Android application UIDs and private storage isolate those processes by default.

The target is an in-process compositor inside the RamenWine process, or a deliberately designed same-UID component. The app should not rely on /data/local/tmp fallbacks intended for upstream development/emulator conditions.

## Current RamenWine state

Implemented:
- ProotWineCommandBuilder accepts a WaylandDisplayEndpoint, checks that the supplied socket path exists, checks the Unix socket path length and binds the host runtime directory into the guest at /run/user/<uid>.
- ProotWineProcessSupervisor starts the PRoot argument vector and forwards merged stdout/stderr.
- WawonaAssetIntegrity maps selected APK-relative xkb/font/Weston asset paths into an app-private support tree and checks their SHA-256 hashes.
- WawonaLibraryInventory checks the complete native-library closure from the provenance manifest by inspecting lib/arm64-v8a entries inside ApplicationInfo.sourceDir and comparing SHA-256 hashes. This avoids assuming that non-legacy JNI packaging extracts .so files to nativeLibraryDir.
- WawonaNative declares the minimal upstream JNI method set using the exact upstream package/class symbol name and loads libraries in the upstream order.
- WawonaPackageManifestReader validates the pinned source revision and complete native-library/asset inventories.
- WawonaSupportAssetInstaller extracts XKB, fonts and Weston data to filesDir/wawona-rootfs with size limits, staging/rollback and SHA-256 verification.
- WawonaHostKeymap applies Android KeyCharacterMap levels to the Wawona seat.
- WawonaDisplayBackend follows upstream bootstrap order: prepare shell environment, apply host keymap, initialize the core, reload the keymap, set density, then check compositor readiness and the Wayland socket.
- WawonaDisplayBackend.prepare() verifies every manifest-listed native library against the installed APK and extracts XKB/font/Weston assets on an IO dispatcher. WawonaDisplayBackend.start() then performs only the native bootstrap on the Android main thread, confirms native readiness and verifies that wayland-0 is an actual Unix socket.
- WawonaDisplaySession owns Surface attach/detach/resize, forwards basic touch and hardware-key events, and releases Vulkan presentation resources even when no Surface was attached.
- WawonaSurfaceView connects Android SurfaceHolder callbacks and basic input events to DisplaySession.
- WawonaSurfaceHost embeds the view in Compose; surface attach/detach state is delivered back to the UI.
- WawonaSurfaceHost embeds that SurfaceView in Compose and detaches the native Surface when the composable leaves the UI.
- GameSessionCoordinator starts PRoot/Wine only after the compositor socket and Android Surface are ready, then tears down display resources when the game process exits.
- JVM tests cover the expected native-library inventory.

Not implemented:
- The generated Wawona native bundle is not currently present in this repository checkout, so availability() reports missing libraries/manifest and start() fails closed until the staging script has been run.
- No reproducible Wawona Nix/NDK build output has been staged into jniLibs for the current checkout.
- The pinned APK importer and runtime support-asset installer exist; the generated provenance manifest and native binaries are materialized by the Android packaging workflow and are not committed as opaque binary files.
- The Compose UI now offers a per-game prepare-display and launch flow. APK hashing and support-asset extraction run off the main thread; JNI and Surface lifecycle stay on main. Errors are surfaced instead of simulating a successful launch.
- WawonaSurfaceHost reports Android Surface attach/detach transitions back to the Compose state; the launch button remains disabled until the Surface is attached.
- The app now exposes a pinned Weston SHM-client + Android PixelCopy verification flow. It proves visible pixels only when run successfully on a device; no Wine process has yet been shown rendering a Win32 window through RamenWine.
- Basic touch/key and IME/text composition forwarding exist. Scroll mapping, full focus management and actual audio playback still require real-game/device verification.
- No physical-device Vulkan presentation or pause/resume test has been completed.

## RamenWine-owned abstraction

Keep game management independent from the selected compositor. The intended boundary is:

    GameSessionCoordinator
      ├── DisplayBackend
      │     └── WawonaDisplayBackend
      │           ├── initialize compositor core
      │           ├── attach/detach Android Surface
      │           ├── publish/confirm Wayland endpoint
      │           ├── resize and forward input/IME
      │           └── stop compositor and release native resources
      └── RuntimeProcess
            └── PRoot → Box64 → Wine

The DisplayBackend API should describe capabilities and lifecycle, not expose upstream Wawona types to the game library. A later compositor can implement the same interface.

## Integration sequence

1. Download the checksum-pinned official v26.8.12 Android release asset from Wawona's GitHub Releases page.
2. Verify the APK digest, ARM64 ELF headers and required JNI exports; stage the complete same-release native-library closure and the XKB/font/Weston assets.
3. Package .so files through jniLibs with extraction enabled, and package support data plus provenance under assets/wawona.
4. Start the compositor on the app cache path, wait for compositor-ready and verify that the Wayland socket is actually connectable before exposing a session.
6. Attach a RamenWine SurfaceView, test create/change/destroy and Activity pause/resume, and ensure surface teardown does not stop a newer surface.
7. Launch an original Wayland client smoke program, then a Wine Win32 GDI test program through the existing PRoot builder.
8. Verify IME, touch, mouse, hardware keyboard, orientation, focus, exit and complete cleanup on a physical ARM64 device.

## Real Android Surface smoke test

RamenWine exposes a manual **Проверить реальный вывод Wayland** action after the Wawona Surface attaches. It starts the pinned upstream `weston-simple-shm` Wayland client, which submits a real shared-memory buffer through the compositor. Android `PixelCopy` then reads pixels from the actual `SurfaceView`; the check passes only when a sufficient number of sampled pixels are non-black. A native client-start return value, compositor-ready flag, or socket file alone is not considered success.

This gate isolates the compositor-to-Android-Surface path from Wine. A successful result proves that a Wayland SHM client produced visible pixels through Wawona's Android presentation surface. It does not yet prove Wine, Box64, PRoot, or a Windows EXE can render.

## Acceptance gates

- A Wayland client can connect to the socket while RamenWine owns the Android Surface.
- A minimal Wine Win32 GUI window is visible in the RamenWine surface.
- Surface recreation and Activity lifecycle do not leave stale Vulkan swapchains or compositor threads.
- Process exit closes Wine/Box64/PRoot children and compositor resources.
- The license/NOTICE inventory exactly matches the linked and bundled components.
- The same test is reproducible on the documented Android version, SoC, GPU and driver.

Passing an asset hash test, loading a shared library, seeing a compositor-ready flag or building an APK is not by itself proof that Wine windows render correctly.

## Building and staging the pinned native bundle

The native libraries are intentionally not committed as opaque binaries. From the RamenWine checkout, run:

```bash
tools/build_wawona_android.sh
```

The script clones Wawona at revision `dd745564ffe4e28628c9cf2525c620bcfd4ebed5`, builds `.#wawona-android` using the upstream Nix flake, then runs `tools/import_wawona_android.py` against the resulting APK.

The importer extracts every arm64-v8a `.so`/versioned shared object plus the XKB, font and Weston support assets. It rejects non-AArch64 ELF files, path traversal, symlink entries, duplicate names and oversized payloads, then creates `assets/wawona/provenance.json` with the source revision and SHA-256 inventory.

Generated files are local and Git-ignored:
- `app/src/main/jniLibs/arm64-v8a/`
- `app/src/main/assets/wawona/`

The Android adapter checks the full library inventory, verifies the embedded source revision, installs support assets into app-private storage and follows the upstream native bootstrap order. This is packaging preparation only: it does not prove the native libraries load or that a Wine window renders on a device.
