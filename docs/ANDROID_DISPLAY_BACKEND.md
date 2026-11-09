# Android game display backend research

**Status:** Wawona adapter source integrated; native payload build and device validation outstanding  
**Review date:** 2026-11-09

## Goal

RamenWine must display a Windows game's graphical output inside an Android SurfaceView, without showing a Windows desktop or asking the user to interact with a Linux desktop shell.

This is separate from Wine process execution and CPU translation. A working Wine process is not useful to the user until a display backend can present windows, route input and survive Android lifecycle events.

## Candidate: Piperella Wayland Compositor

Repository: https://github.com/Piperella/Piperella-Wayland-Compositor

Piperella is a substantial Android Wayland compositor proof-of-concept. Its source tree contains a Kotlin Android library, a C++ Wayland server, EGL/GLES rendering, JNI, Wayland protocol implementations, input forwarding, and example Android applications. Its design is directly relevant to RamenWine:

- Starts an in-process libwayland-server and publishes a Unix-domain socket in app-private storage.
- Uses SurfaceView, ANativeWindow, EGL/GLES3 and Android Choreographer for output.
- Imports wl_shm and DMA-BUF buffers; the DMA-BUF path can avoid a CPU frame copy when the Android driver exposes the required extensions.
- Implements Wayland core surfaces, XDG shell windows/popups, output, seat/input, text input, and additional protocols needed by graphical clients.
- Has a Kotlin CompositorEngine lifecycle API and a JNI/C++ compositor core.
- Its Wine example configures winewayland.drv, WAYLAND_DISPLAY, XDG_RUNTIME_DIR and a per-game Wine prefix.

The architecture is a strong reference for an Android-native game surface and Wayland display server. It is not proof that every Wine application or graphics backend works.

## Direct-integration blockers

### 1. License

The repository is GPL-3.0. Its latest license-change commit explains that the AIDL input-forwarding path derives from wlroots-android-bridge, which is GPL-3.0, and that the whole compositor project therefore moved to GPL-3.0.

Directly adding Piperella as a linked Gradle/native module can create GPL-3.0 obligations for a combined distributed application. RamenWine has not yet selected an application license, so we must not silently vendor or link this dependency.

Before direct integration, the project owner must explicitly choose one of these paths:

- Accept and comply with GPL-3.0 for the relevant RamenWine distribution.
- Obtain a separate compatible license from the relevant rightsholders.
- Keep Piperella as an external reference and implement an independent compositor using components whose licenses are compatible with RamenWine's chosen distribution model.

A separate process or IPC boundary is not an automatic legal solution; that design would still need license review.

### 2. Android version floor

Piperella's Android library declares minSdk = 36 and targets API 36. RamenWine currently declares minSdk = 26.

A direct dependency would prevent RamenWine from installing on Android 8–15 unless we:
- raise RamenWine's minimum Android version to API 36, or
- fork/adapt the required compositor code to a lower API baseline and validate all Android API dependencies.

Do not raise minSdk just to make a proof-of-concept dependency compile. RamenWine's current broad-device target remains API 26 until a deliberate product decision changes it.

### 3. Toolchain and native integration

Piperella documents NDK r29, committed Wayland/xkbcommon prebuilts, generated protocol glue, CMake and AIDL-generated native sources. RamenWine currently builds with NDK r30 and CMake 3.22.1.

Before integration, verify:
- exact binary dependencies and their ABI/API requirements;
- 16 KB ELF page alignment;
- AIDL generation paths under the chosen Android Gradle Plugin;
- JNI symbol visibility and library loading;
- coexistence of multiple native libraries and shared dependencies;
- lifecycle, socket cleanup and process shutdown;
- memory ownership and synchronization between JNI, Wayland dispatch and rendering.

### 4. Proof-of-concept maturity and example caveats

The upstream README itself labels Piperella a proof of concept.

The public examples are not a general visual-novel compatibility suite:
- SteamTest documents a CPU-only baseline where Steam's Chromium Embedded Framework UI still enters a white-screen/restart loop without a usable GPU path.
- SteamTestVk adds an additional Vulkan/Turnip/DXVK stack. This is device- and driver-sensitive.
- The SteamTest README describes rootless XDG toplevel windows, while its current Launcher.kt still constructs an explorer /desktop=... command. This documentation/code mismatch must be resolved in an upstream integration test before copying the launch flow.
- The example's Bionic Hangover package is a separate release asset of roughly 340 MB, and its release history is from July 2026. It is a research input, not a stable RamenWine dependency.
- Wine's Wayland driver is a particular display route. Older Wine builds, games requiring X11 paths, and games with unusual windowing behavior still need tests and possibly an XWayland/X11 fallback.

### 5. Graphics is not solved by the compositor

The compositor's EGL/GLES renderer displays client buffers. It does not itself provide a universal Direct3D implementation or a Vulkan driver.

RamenWine still needs a separate graphics/runtime matrix:
- Wine Wayland + system-memory/SHM path for basic 2D and GDI-style windows.
- Vulkan/DXVK path only when the device driver and extensions are proven compatible.
- WineD3D and legacy DirectDraw/Direct3D fallbacks for older games.
- D3D12 through VKD3D only on validated driver/device combinations.

The CPU-only SteamTest example does not establish that DXVK works on arbitrary Android GPUs.

## RamenWine decision

Do not add Piperella as a Gradle dependency or vendor its source at this stage.

Use it as a technical reference for:
- Android SurfaceView + EGL lifecycle;
- Wayland server/socket lifecycle;
- JNI contract and thread marshalling;
- input/IME forwarding;
- DMA-BUF and wl_shm fallbacks;
- Wine winewayland.drv environment and per-game prefixes.

In parallel, define a RamenWine-owned display abstraction so the runtime is not permanently coupled to one compositor:

    GameSession
      ├── RuntimeProcess (Wine / native adapter)
      ├── DisplayBackend
      │     ├── WaylandSurfaceBackend (candidate)
      │     └── X11/XWaylandBackend (legacy fallback candidate)
      ├── InputRouter
      └── AudioRouter

A compositor candidate must pass:
1. a native Wayland client smoke test in an Android SurfaceView;
2. a Wine GDI/Win32 GUI test (not Steam);
3. an old 32-bit visual-novel test;
4. a current 64-bit Wine GUI test;
5. touch, mouse, keyboard, IME and orientation tests;
6. pause/resume, Activity recreation and long-session tests;
7. a Vulkan/DXVK test on each supported GPU family;
8. a documented license and third-party-notice review.

## Related references

- Piperella repository: https://github.com/Piperella/Piperella-Wayland-Compositor
- Piperella documentation: https://github.com/Piperella/Piperella-Wayland-Compositor/blob/main/DOCUMENTATION.md
- Piperella third-party notices: https://github.com/Piperella/Piperella-Wayland-Compositor/blob/main/THIRD_PARTY_NOTICES.md
- Piperella Bionic Hangover release project: https://github.com/Piperella/Piperella-Hangover-Bionic


## Wawona integration assessment

The current upstream [Wawona compositor](https://github.com/Wawona/Wawona) is MIT-licensed and its Android application declares minSdk 24, so its license and Android floor are compatible with RamenWine's current minSdk 26. The upstream project has evolved: the Wayland compositor core is now Rust, with Kotlin/JNI Android host integration and a Vulkan renderer.

It is **not** a drop-in Android library today:

- The upstream Android Gradle project is an application module, not a published reusable AAR.
- Its Android CMake target combines JNI/rendering sources with a Rust backend static library and generated protocol/shader sources. The Nix build stages many native dependencies, including libwayland, xkbcommon, pixman, expat, OpenSSL, zstd/lz4, Weston components, ANGLE and iland.
- The Android API is application-shaped: WawonaNative is a singleton, nativeInit(cacheDir) creates one process-global core, and the native code starts the fixed socket name wayland-0 under its XDG_RUNTIME_DIR. It also sets WAYLAND_DISPLAY in the host process environment.
- The native renderer keeps one global Vulkan surface/swapchain. nativeDestroySurface tears down the surface but deliberately preserves the Wayland core; nativeShutdown stops and frees the core. Those are different lifecycle operations and must not be conflated.
- The Kotlin surface is coupled to Wawona's preferences, session activities and machine model. Copying only WawonaSurfaceView would not provide a working compositor.
- Its fallback Studio native build uses stub render/backend sources, so it is not a runtime integration path.

**Current decision:** keep Wawona as a pinned native build input and use the RamenWine-owned adapter. Do not add its full Android app as a Gradle dependency or copy its application UI into RamenWine. The adapter and APK dependency-closure extractor are implemented; the generated native payload still needs to be built and validated.

The current WawonaDisplayBackend explicitly handles Wawona's process-global model: it enforces one active compositor/game session per app process, uses a canonical app-private runtime directory and live wayland-0 socket, attaches/detaches the Android Surface independently from stopping the core, and serializes shutdown. It does not support multiple independent compositor sessions.

RamenWine now has a concrete WawonaDisplayBackend behind the GameDisplayBackend / GameDisplaySession boundary, plus a WawonaSurfaceView for basic touch, key and mouse input. The backend verifies the live socket and uses the upstream surface/core shutdown order. Its code is not yet a claim of successful pixel presentation: upstream nativeSetSurface returns void, and a real Vulkan/Wayland client smoke test on ARM64 hardware is still required.

The PRoot command builder checks the socket node type with Android lstat(2). A same-named regular file is rejected; a backend cannot satisfy the launch precondition by merely creating a placeholder file.

## Related upstream sources

- [Wawona changelog](https://github.com/Wawona/Wawona/blob/master/CHANGELOG.md) — Rust-core migration and Android support
- [Wawona Android Gradle project](https://github.com/Wawona/Wawona/tree/master/android/app) — application module and Kotlin host
- [Wawona Android JNI implementation](https://github.com/Wawona/Wawona/blob/master/src/platform/android/android_jni.c) — process-global compositor and Vulkan surface lifecycle
- [Wawona Android Nix build](https://github.com/Wawona/Wawona/blob/master/dependencies/wawona/android.nix) — staged native dependencies


## RamenWine implementation update (2026-11-09)

See [Wawona Integration](WAWONA_INTEGRATION.md) for the exact upstream revision, Nix/APK extraction command, asset and native dependency strategy, private socket-path rules, license obligations and physical-device validation gates. The app does not yet ship the generated Wawona native payload by default, and no real Wine game launch is claimed.
