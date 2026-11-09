# Wawona Android compositor integration

**Status:** RamenWine-owned adapter and pinned APK extractor added; native payload build and physical-device validation remain outstanding.  
**Reviewed upstream revision:** `d298ddd25726fec0977285aa5f57181110c966a6` on Wawona's `development` branch (2026-11-09).

## Why this is not an AAR dependency

[Wawona](https://github.com/Wawona/Wawona) is MIT-licensed, but its Android target is an application, not a reusable Android library. The Android native library is assembled from:

- the Rust compositor core (`libwawona.a` / `libwawona_core.so`);
- upstream JNI and Vulkan/Android Surface rendering code;
- a Nix-built set of shared native dependencies, including ANGLE and Wayland-related libraries;
- runtime support assets such as xkeyboard-config data, fonts, Weston decoration data and an optional Vulkan ICD manifest.

The upstream Android JNI is also coupled to Wawona's application package name through exported JNI symbols. RamenWine therefore keeps a minimal compatibility class in the exact package `com.aspauldingcode.wawona` and does not import Wawona's Compose UI, machine model, shell, terminal, SSH or session activities.

## Pinned artifact build

Do not use the old prebuilt Wawona APK release. It predates the reviewed source revision and does not provide the provenance used by this integration.

Build the exact upstream revision with Nix (flakes enabled):

```bash
WAWONA_REVISION=d298ddd25726fec0977285aa5f57181110c966a6

nix build "github:Wawona/Wawona/$WAWONA_REVISION#wawona-android"

APK="$(find -L result -type f -name '*.apk' -print -quit)"
READELF="$(find "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt" -type f -name llvm-readelf -print -quit)"

test -n "$APK"
test -n "$READELF"

python tools/package_wawona_apk.py \
  --apk "$APK" \
  --readelf "$READELF" \
  --revision "$WAWONA_REVISION" \
  --output app/build/generated/wawona

gradle --no-daemon :app:assembleDebug
```

The Nix build uses upstream's `wawona-android` output to assemble the Android app and stage the ARM64 native dependency set. The extractor then creates a much smaller RamenWine Gradle source directory:

```text
app/build/generated/wawona/
  jniLibs/arm64-v8a/
    libwawona.so
    libEGL_angle.so
    libGLESv2_angle.so
    ...recursive DT_NEEDED dependencies...
  assets/wawona/
    xkb/
    fonts/
    weston/
    vulkan/icd.d/       # included when present in the source APK
    provenance.json
```

Only the three required JNI/renderer roots, their recursive ELF `DT_NEEDED` closure, and the optional `libvk_swiftshader.so` are staged. Unrelated executable payloads that upstream stores under `jniLibs` with a `.so` suffix (for example SSH helper executables) are not copied unless they are actually part of the linked native dependency closure. Android system libraries are left to the OS linker. An unresolved non-system dependency is a hard packaging error.

The generated directory is ignored by Git and included through the Android `main` source set. A clean checkout can still run JVM/Python tests without the optional generated native payload. The runtime adapter fails clearly when its Wawona assets or libraries are not present.

The extractor records the input APK SHA-256, the pinned revision supplied by the build command, each staged library SHA-256 and the extracted asset paths in `assets/wawona/provenance.json`. The revision argument is a build assertion, not a cryptographic proof of how an APK was built; retain the Nix build log and store path with release artifacts.

Run the packager tests without Nix:

```bash
python -m unittest discover -s tools/tests -v
```

## Runtime ownership and lifecycle

The adapter is split across:

- `WawonaNative.kt`: the minimal JNI surface, in the upstream package required by native symbol names. It loads `EGL_angle`, `GLESv2_angle`, then `wawona`.
- `WawonaRuntimeAssets.kt`: installs only XKB, fonts and optional Weston data into app-private storage. It does not copy Wawona shell or unrelated application assets.
- `WawonaDisplayBackend.kt`: starts the compositor, verifies its live socket, attaches an Android `Surface`, and returns a `GameDisplaySession`.
- `WawonaSurfaceView.kt`: a SurfaceView host that forwards basic touch, key and mouse events after its owner enables input.
- `GameLaunchCoordinator`: remains the owner that joins a supplied display session with the PRoot process lifecycle.

### Process-global restriction

Upstream Android JNI uses process-global `g_core`, Vulkan instance/device/surface, swapchain and render-thread state. `nativeInit` is not a factory for independent compositor objects. RamenWine consequently enforces one active Wawona session per app process and serializes every JNI operation through a lease guard.

Do not remove this limit unless the upstream Android renderer and JNI lifecycle are redesigned to own independent core and renderer instances.

### Surface and shutdown ordering

Upstream separates Vulkan surface cleanup from Rust compositor shutdown. RamenWine follows this order:

1. Start the Rust Wayland core and create its socket.
2. Verify the socket is a real AF_UNIX socket.
3. Attach the Android `Surface` to the upstream Vulkan renderer.
4. On close, call `nativeDestroySurface(surface)`.
5. Then call `nativeShutdown()` to stop/free the Rust core.
6. Release the process-wide lease.

Resize calls upstream `nativeResizeSurface` and then synchronizes the logical output size. The RamenWine session close is idempotent; a second start remains blocked until the first session releases its lease.

### Runtime directory hardening

Upstream derives `XDG_RUNTIME_DIR` as `<cacheDir>/wawona-runtime`. Its current JNI contains a fallback to `/data/local/tmp/wawona-runtime` when the path plus its socket-name reserve would exceed `sockaddr_un.sun_path`.

RamenWine must never accept that fallback. The backend:

- passes canonical app-private `filesDir` and `cacheDir` paths to JNI;
- checks the runtime directory resolves directly under the app cache directory;
- enforces the upstream 32-byte suffix budget against Android's 108-byte AF_UNIX path field before JNI initialization;
- requires a real directory owned by the app UID and mode 0700;
- removes a previous socket only when `lstat` proves it is a socket owned by the app UID;
- rejects a regular file, symlink or socket owned by another UID at the endpoint;
- verifies the newly created endpoint with Android `lstat` and checks its UID.

The existing PRoot command builder independently verifies that the socket exists, is a Unix-domain socket and remains under the app-private cache directory before constructing the guest bind mount.

## What this adapter proves — and does not prove

The adapter source establishes a concrete startup/shutdown path and a live Wayland endpoint contract. The upstream `nativeIsCompositorReady()` reports the Rust core state. The upstream `nativeSetSurface()` returns `void`; it does not expose an explicit success result for Vulkan surface/swapchain creation. A ready socket and core therefore do **not** prove that pixels are successfully presented to the Android surface. Vulkan failures must still be checked in logcat and verified with a real client on hardware.

The basic input view forwards multi-touch, Android key-down/up, mouse motion/buttons and scroll. It is not yet a complete IME/text-input bridge, gamepad mapping, touchpad gesture implementation, orientation controller or audio router.

The adapter is not yet connected to a user-facing Play action. RamenWine still has no bundled PRoot/Box64/Wine Linux rootfs and no real Wine package. The presence of `GameLaunchCoordinator` plus a display backend is not a claim that imported Windows games can launch.

## Licensing and release obligations

The upstream Wawona MIT license and upstream third-party license summary are included under `app/src/main/assets/wawona/licenses/`. The main Wawona license is permissive, but the transitive native dependency closure has its own notices and license texts.

Before distributing a build containing this adapter:

1. Generate the actual dependency/license inventory for the exact Nix-built libraries staged by the extractor.
2. Include the required full license texts and copyright/NOTICE files for every shipped dependency.
3. Confirm the final native closure does not accidentally include separately executable GPL components such as upstream's optional `sshpass` helper. If one is included, satisfy its source and notice obligations.
4. Re-review the inventory whenever the Wawona pin or Nix dependency graph changes.

The upstream license summary is a starting point, not a substitute for that release audit.

## Validation gates

### Build/package checks

- [ ] Build the pinned Wawona APK with Nix and record its store path.
- [ ] Run the extractor; inspect `provenance.json` and the exact native closure.
- [ ] Run Python packager tests.
- [ ] Run Gradle JVM tests and `:app:assembleDebug`.
- [ ] Inspect the APK's `lib/arm64-v8a` contents and check 16 KB ELF page-size compatibility.

### Physical ARM64 device checks

- [ ] Install the APK on Android 8+ (RamenWine minSdk 26).
- [ ] Start Wawona from an actual Android SurfaceView and confirm the compositor socket stays under app cache storage.
- [ ] Run an original minimal Wayland `wl_shm` client and confirm pixels reach the Android Surface.
- [ ] Exercise resize, touch, keyboard and mouse input.
- [ ] Confirm that surface destruction precedes compositor shutdown and that a second session can start after close.
- [ ] Bind the Wayland socket into the PRoot rootfs and run an original Win32 GDI/Wine GUI smoke test.
- [ ] Verify process teardown, Activity recreation, pause/resume and a longer session.

No physical-device compositor render or real Wine launch has been verified by the current repository changes.
