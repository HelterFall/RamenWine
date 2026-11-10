# Android game display backend and Wawona integration

**Status:** Wawona selected as the current upstream candidate; not yet integrated into RamenWine  
**Review snapshot:** 2026-11-10  
**Pinned research revision:** Wawona/Wawona commit dd745564ffe4e28628c9cf2525c620bcfd4ebed5 (upstream Cargo package version 26.11.2)

## Goal

Display a Windows game's graphical output inside the RamenWine Android application, without exposing a Linux desktop shell to the user. The display backend must own an Android Surface, start a Wayland compositor in the same app UID, publish a local Wayland socket for Wine, route input/IME, and survive Activity and Surface lifecycle changes.

Wine process execution and display are separate systems. ProotWineCommandBuilder already expects a Wayland socket; it deliberately refuses to construct a launch command until the socket exists. RamenWine currently has no component that creates that socket.

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

Upstream Android settings in the reviewed revision include minSdk 24, compileSdk 36 and NDK 29. RamenWine currently targets minSdk 26, compileSdk 36 and NDK 30. The SDK floor is compatible in principle, but the NDK and native dependency graph must be made reproducible rather than mixing arbitrary .so files from an APK.

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
- WawonaAssetIntegrityTest covers matching assets, missing/modified data, traversal and symlink escape.

Not implemented:
- No Wawona native libraries or generated JNI bindings are present in RamenWine.
- No Wawona Kotlin host classes or compositor SurfaceView are integrated.
- No pinned Wawona APK/source archive or provenance manifest is bundled.
- WawonaAssetIntegrity is only a verifier; it does not extract assets and is not connected to the runtime installer.
- No in-process Wayland compositor starts and no RamenWine code creates the Wayland socket.
- No Wine process is currently launched from the Android UI.

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

1. Pin the exact Wawona source revision and record its license and third-party dependency inventory.
2. Build the arm64-v8a Android native output from that revision using a reproducible toolchain; do not extract unidentified native libraries from a random APK.
3. Identify the minimal library/asset closure for compositor core, JNI, Android Surface presentation, Vulkan/ANGLE selection and XKB support.
4. Add a thin RamenWine JNI/Kotlin adapter. Prefer explicit JNI registration over copying the entire upstream application package; keep upstream changes isolated and traceable.
5. Start the compositor on the app cache path, wait for compositor-ready and verify that the Wayland socket is actually connectable before exposing a session.
6. Attach a RamenWine SurfaceView, test create/change/destroy and Activity pause/resume, and ensure surface teardown does not stop a newer surface.
7. Launch an original Wayland client smoke program, then a Wine Win32 GDI test program through the existing PRoot builder.
8. Verify IME, touch, mouse, hardware keyboard, orientation, focus, exit and complete cleanup on a physical ARM64 device.

## Acceptance gates

- A Wayland client can connect to the socket while RamenWine owns the Android Surface.
- A minimal Wine Win32 GUI window is visible in the RamenWine surface.
- Surface recreation and Activity lifecycle do not leave stale Vulkan swapchains or compositor threads.
- Process exit closes Wine/Box64/PRoot children and compositor resources.
- The license/NOTICE inventory exactly matches the linked and bundled components.
- The same test is reproducible on the documented Android version, SoC, GPU and driver.

Passing an asset hash test, loading a shared library, seeing a compositor-ready flag or building an APK is not by itself proof that Wine windows render correctly.