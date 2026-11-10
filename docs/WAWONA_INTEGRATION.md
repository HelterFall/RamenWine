# Wawona Android compositor integration

**Status (2026-11-10):** the pinned Wawona native payload is included in a successfully built RamenWine debug APK. The first Android 16 device smoke test reaches asset installation but fails before compositor rendering because the original app-side provenance allow-list omitted the optional Vulkan ICD manifest. The source fix and regression test are committed; rebuilding and retesting the APK on the phone remain outstanding.  
**Reviewed upstream revision:** `d298ddd25726fec0977285aa5f57181110c966a6` on Wawona's `development` branch (2026-11-09).

## Why this is not an AAR dependency

[Wawona](https://github.com/Wawona/Wawona) is MIT-licensed, but its Android target is an application, not a reusable Android library. The Android native library is assembled from:

- the Rust compositor core (`libwawona.a` / `libwawona_core.so`);
- upstream JNI and Vulkan/Android Surface rendering code;
- a Nix-built set of shared native dependencies, including ANGLE and Wayland-related libraries;
- runtime support assets such as xkeyboard-config data, fonts, Weston decoration data and an optional Vulkan ICD manifest.

The upstream Android JNI is also coupled to Wawona's application package name through exported JNI symbols. RamenWine therefore keeps a minimal compatibility class in the exact package `com.aspauldingcode.wawona` and does not import Wawona's Compose UI, machine model, shell, terminal, SSH or session activities.

## Pinned artifact build

A reviewed Android APK already exists for the exact Wawona revision used here.
The upstream Actions artifact is ID `13377766663` from run `41941778312`.
Its artifact ZIP SHA-256 is `04000a9b31c0125f24b7aecfa3f9bbbc3a51cad7e8fa0ba7207f2e553e7be953`;
the contained APK SHA-256 is `60006df2d5839e6ae3d7137170bbff093ab4f42b74b192f226b293cfee291f37`.
The checked-in packager can consume this archive directly and verifies the outer
ZIP digest, product manifest revision, and inner APK digest before extracting
any native files. This avoids rebuilding the pinned Wawona compositor with Nix
when the reviewed artifact is available.

Download the upstream `product-android-apk` artifact ZIP, then pass its path to Gradle. The `prepareWawonaPayload` task runs automatically before `preBuild`, uses the NDK's host `llvm-readelf`, verifies the pinned outer ZIP, product manifest and inner APK hashes, and writes the generated source set under `app/build/generated/wawona`:

```bash
gradle --no-daemon :app:assembleDebug \
  -PwawonaArtifactZip=/path/to/wawona-product-android-apk.zip
```

The equivalent environment variable is `WAWONA_ARTIFACT_ZIP`. When neither is supplied, this optional task is skipped and a previously generated source set is left intact; clean source/JVM-only builds therefore remain possible without the large native payload.

The Nix build below remains available to rebuild the same pinned source and
produce a fresh APK for review. Do not use the old prebuilt Wawona APK release:
it predates the reviewed source revision.

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

The three required JNI/renderer roots and their recursive ELF `DT_NEEDED` closure are staged. The packager also reads the dynamic symbol table of `libwawona.so` and requires every JNI method declared by RamenWine's minimal `WawonaNative.kt` adapter; missing or renamed native entry points fail packaging rather than becoming an `UnsatisfiedLinkError` at compositor startup. Two explicit dlopen-only helpers are added when present: `libweston_simple_shm.so` (the debug SHM smoke client) and `libvk_swiftshader.so` (the optional offscreen ICD). The SHM helper is not a `DT_NEEDED` edge from `libwawona.so`; the JNI stub calls `dlopen("libweston_simple_shm.so")`, so the packager treats it as an additional runtime root. Unrelated executable payloads that upstream stores under `jniLibs` with a `.so` suffix (for example SSH helper executables) are not copied unless they are actually part of the linked native dependency closure. Android system libraries are left to the OS linker. An unresolved non-system dependency is a hard packaging error.

The generated directory is ignored by Git and included through the Android `main` source set. A clean checkout can still run JVM/Python tests without the optional generated native payload. The runtime adapter fails clearly when its Wawona assets or libraries are not present.

Before following ELF dependencies, the extractor uses NDK `llvm-readelf` to require ELF64/AArch64 and 16-KB-compatible `PT_LOAD` alignment for every selected shared library, including versioned SONAMEs. Every load segment must have `p_align >= 0x4000` and valid file-offset/virtual-address congruence. A library built only for 4-KB pages is rejected before it enters the Gradle native source set. The packager requests wide program-header output so a wrapped `readelf` table cannot hide alignment fields. It calculates the input APK SHA-256 as a stream (without reading the whole APK into memory) and records that hash, the pinned revision supplied by the build command, the required Android ELF page size, each staged library SHA-256 and extracted asset paths in `assets/wawona/provenance.json`. The revision argument is a build assertion, not cryptographic proof of how an APK was built; retain the Nix build log and store path with release artifacts.

Run the packager tests without Nix:

```bash
python -m unittest discover -s tools/tests -v
```

## Observed artifact for the pinned revision

The Wawona upstream Actions run [`Gate: products` #41941778312](https://github.com/Wawona/Wawona/actions/runs/41941778312) ran on the exact pinned commit `d298ddd25726fec0977285aa5f57181110c966a6`. Its Android-specific child job **Build: Android / Android debug APK** completed successfully, including the upstream Play-safe graphics-bundle verification and artifact upload. The Android emulator ADB runtime smoke child job also completed successfully. The overall multi-platform `Gate: products` run was marked failed because unrelated Linux/macOS/Apple-platform product jobs failed; do not interpret that aggregate result as an Android APK failure.

The published upstream `product-android-apk` artifact (artifact ID `13377766663`) had a ZIP size of 93,193,378 bytes and artifact digest:

```text
sha256:04000a9b31c0125f24b7aecfa3f9bbbc3a51cad7e8fa0ba7207f2e553e7be953
```

The APK inside it was 93,192,914 bytes, with SHA-256:

```text
60006df2d5839e6ae3d7137170bbff093ab4f42b74b192f226b293cfee291f37
```

Its `product-manifest.json` records the same upstream revision and Nix store output `/nix/store/z2q69igr3rcnl3vhm32miak4fpiir7qf-wawona-android`.

An independent ELF-header/DT_NEEDED inspection of this real APK found 27 ARM64 shared-object entries. The required roots, recursive dependencies and the two explicit auxiliary roots resolve to 11 libraries (about 83.3 MiB), with no unresolved non-system dependency. Each of the 11 selected libraries was independently checked with `readelf -Wl`; all PT_LOAD segments use `p_align=0x4000` and satisfy 16-KB alignment:

```text
libEGL_angle.so
libGLESv2_angle.so
libanowaw.so
libvk_swiftshader.so
libwawona.so
libwayland-client.so
libwayland-cursor.so
libwayland-server.so
libweston_simple_shm.so
libxkbcommon.so
libzstd.so
```

The other 16 entries are not part of this runtime closure; they include standalone shell, SSH, Niri, Fuzzel, Foot and other desktop/helper binaries. The APK contains 75 XKB asset files, 8 font files, 23 Weston data files and one Vulkan ICD manifest (107 selected files, about 13.4 MiB total). This is the reference closure for future pin updates. It is not evidence that RamenWine itself has rendered the SHM client on a device.

## Runtime ownership and lifecycle

The adapter is split across:

- `WawonaNative.kt`: the minimal JNI surface, in the upstream package required by native symbol names. It loads `EGL_angle`, `GLESv2_angle`, then `wawona`; the debug smoke screen also exposes upstream's `nativeRunWestonSimpleSHM` / stop / running methods.
`WawonaRuntimeAssets.kt`: installs XKB, fonts, optional Weston data and the optional `vulkan/icd.d` manifest into app-private storage. It does not copy Wawona shell or unrelated application assets. The generated provenance manifest contains a SHA-256 for every selected asset; the Android installer validates the full inventory on each compositor startup and restores any missing or modified file before Wawona initializes. The native environment preparation then points Wawona's rootfs/XKB/Fontconfig variables at these exact private directories.
- `WawonaDisplayBackend.kt`: starts the compositor, verifies its live socket, attaches an Android `Surface`, and returns a `GameDisplaySession`.
- `WawonaSurfaceView.kt`: a SurfaceView host that forwards basic touch, key and mouse events only after `bindSession()` receives a Wawona-owned session. Every event is serialized through that session's process-wide lease; stale Surface events are ignored after the lease is released.
- `WawonaPreviewActivity.kt` (debug source set only): a separate compositor diagnostic screen. It starts Wawona on a Surface, launches the pinned upstream `weston-simple-shm` client and reports its native running state while active. It sends a stop request before releasing the Surface/core; it never starts Wine or imported EXE files.
- `GameLaunchCoordinator`: remains the owner that joins a supplied display session with the PRoot process lifecycle.

### Process-global restriction

Upstream Android JNI uses process-global `g_core`, Vulkan instance/device/surface, swapchain and render-thread state. `nativeInit` is not a factory for independent compositor objects. RamenWine consequently enforces one active Wawona session per app process and serializes startup, resize, input, surface teardown and shutdown through a session-specific lease guard. Input events carrying an old lease are dropped rather than reaching a later compositor session.

Do not remove this limit unless the upstream Android renderer and JNI lifecycle are redesigned to own independent core and renderer instances.

### Surface and shutdown ordering

Upstream separates Vulkan surface cleanup from Rust compositor shutdown. RamenWine follows this order:

1. Start the Rust Wayland core and create its socket.
2. Verify the socket is a real AF_UNIX socket.
3. Dump the Android host keymap before `nativeInit`, then call `nativeReloadHostKeymap()` after the Rust core is ready (matching upstream `MainActivity`).
4. Set display density and disable Wawona's native touch-to-pointer emulation; RamenWine maps a single finger to one left-click stream itself.
5. Attach the Android `Surface` to the upstream Vulkan renderer.
6. Synchronize the initial logical output size with `nativeSyncOutputSize(width, height)`.
7. Apply the Wawona clean-install graphics defaults (system Vulkan WSI, ANGLE, automatic compositor backend).
8. On close, call `nativeDestroySurface(surface)`.
9. Then call `nativeShutdown()` to stop/free the Rust core.
10. Release the process-wide lease.

Resize calls upstream `nativeResizeSurface` and then synchronizes the logical output size. The RamenWine session close is idempotent; a second start remains blocked until the first session releases its lease.

### Runtime directory hardening

Upstream derives `XDG_RUNTIME_DIR` as `<cacheDir>/wawona-runtime`. Its current JNI contains a fallback to `/data/local/tmp/wawona-runtime` when the path plus its socket-name reserve would exceed `sockaddr_un.sun_path`.

Wawona also documents that a stale `wayland-0.lock` or `instance.lock` can leave a socket path present while every client connection is refused. Before starting the core, RamenWine removes those two lock paths only when `lstat` proves each is a regular file owned by the app UID. It refuses symlinks, sockets, directories or files owned by another UID; the live Wayland socket is separately checked and cleaned only as an app-owned socket.

RamenWine must never accept the `/data/local/tmp` fallback. The backend:

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

The basic input view maps one finger to a single left-click stream, ignores additional fingers to avoid duplicate VN advances, forwards Android key-down/up, and supports hardware mouse motion/buttons and scroll. The click sequence hit-tests the Wayland window first, then gives that window keyboard focus before pressing the button. The debug-only SHM preview can exercise a real in-process Wayland client against the host compositor. Upstream's SHM stop JNI method only sets a running flag and resets its counter; its client runs on a detached pthread and upstream provides no join/completion API. RamenWine records whether a client was launched, sends at most one stop request per session, and applies a short best-effort grace window before surface/core teardown. The native running flag is meaningful while the client is active, but a false value after stop is **not** proof that the detached thread has exited. Device-side teardown must still be validated. The preview is not yet a complete IME/text-input bridge, gamepad mapping, touchpad gesture implementation, orientation controller or audio router.

The Wawona display adapter is connected to the library's **Играть** action through `GamePlayerActivity` and `GameLaunchCoordinator`. When the required runtime package is installed, the game screen starts Wawona on its Android Surface before launching the PRoot process. The Wawona JNI libraries and support assets are now staged in the successful debug APK. The complete PRoot/Box64/Wine Linux rootfs package is still not built or installed, so the source-level launch connection is not proof that Wine boots or that an imported Windows game can launch.


## First Android device test and provenance fix

The first phone test used Android 16 / arm64-v8a. The Android Surface was
valid (1200×2001), but Wawona failed immediately with:

```text
Wawona asset provenance contains an unsafe or invalid entry.
```

Inspection of the exact APK that had been installed showed 107 selected
provenance assets: 75 XKB files, 8 font files, 23 Weston files and one optional
Vulkan ICD manifest:

```text
wawona/vulkan/icd.d/vk_swiftshader_icd.json
```

The packager included and SHA-256-recorded this file, but the original
`WawonaRuntimeAssets.isAllowedAssetPath()` and
`WawonaAssetIntegrity.mapAssetPath()` accepted only XKB/fonts/Weston. The
manifest therefore failed closed before `nativeInit`; this was an asset
allow-list mismatch, not yet evidence of a Vulkan/GPU rendering failure.

The source fix:
- explicitly allows only the `wawona/vulkan/icd.d/` asset subtree;
- maps it to app-private `usr/share/vulkan/icd.d`;
- copies it through the same bounded, app-private asset copier;
- includes it in the exact SHA-256 inventory check;
- adds JVM regression coverage for the ICD path and rejects other Vulkan roots.

The backend currently selects Android's `system` Vulkan driver, so this
manifest is retained as an optional pinned asset; the source fix does not claim
that SwiftShader fallback is already configured or working. A new APK must be
built and the SHM preview must be retested on the phone. Until the test window
actually appears on the Android Surface, compositor rendering remains
unverified.

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

- [x] Package the reviewed pinned Wawona APK artifact into the RamenWine debug APK; the successful package run is recorded above.
- [ ] Run the extractor; inspect `provenance.json` and the exact native closure.
- [ ] Run Python packager tests.
- [ ] Run Gradle JVM tests and `:app:assembleDebug`.
- [ ] Inspect the APK's `lib/arm64-v8a` contents and check 16 KB ELF page-size compatibility.

### Physical ARM64 device checks

- [ ] Install the APK on Android 8+ (RamenWine minSdk 26).
- [ ] Start Wawona from an actual Android SurfaceView and confirm the compositor socket stays under app cache storage.
- [ ] Rebuild the APK containing the Vulkan ICD provenance fix, then open the debug-only “Wawona / Wayland” preview and confirm the upstream `weston-simple-shm` window actually appears in the Android Surface (the JNI run flag alone is not proof of pixels).
- [ ] Run an independent original minimal Wayland `wl_shm` client and confirm pixels reach the Android Surface.
- [ ] Exercise resize, touch, keyboard and mouse input.
- [ ] Confirm that surface destruction precedes compositor shutdown and that a second session can start after close.
- [ ] Bind the Wayland socket into the PRoot rootfs and run an original Win32 GDI/Wine GUI smoke test.
- [ ] Verify process teardown, Activity recreation, pause/resume and a longer session.

No physical-device compositor render or real Wine launch has been verified by the current repository changes.
