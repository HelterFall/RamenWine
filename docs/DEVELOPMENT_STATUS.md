# RamenWine development status

**Snapshot:** 2026-11-09  
**Current milestone:** Android host scaffold + bounded, read-only EXE inspection.

## What is implemented

- Android Gradle project with Kotlin, Jetpack Compose and an NDK/CMake native library.
- ARM64 (arm64-v8a) is the first Android native ABI.
- Folder selection through Android Storage Access Framework.
- Persistable read permission for a user-selected folder.
- Bounded recursive scan (maximum depth 16, maximum 15,000 directory entries).
- Bounded PE header inspection (up to 64 KiB per candidate; no executable loading).
- Detection of common COFF machine values: x86, x86-64, ARM32, ARM64 and ARM64EC.
- Basic runtime-layout inspection.
- JNI bridge smoke diagnostic.
- JVM unit tests for PE header parsing and runtime-layout state.
- GitHub Actions workflow for debug build, unit tests and lint.

## Important: Android Wine is not a standalone NDK library

The current code intentionally does not pretend that Wine can be loaded directly from an APK as an ordinary Android shared library.

The Android implementation needs a Linux userspace compatible with the chosen Wine and Box64 builds. The reference Android approach used by Winlator includes a Linux rootfs, PRoot, Wine, Box86/Box64 and graphics components. PRoot works without root, but its syscall interception has a performance cost that must be measured.

Proposed on-device layout:

```text
files/runtime/current/
  host/
    bin/
      proot                 # Android/host executable
  rootfs/
    usr/bin/
      box64                 # Linux ARM64 host translator in rootfs
      wine                  # Wine loader in rootfs
    usr/lib/
    lib/
```

This layout is a first contract for the app/runtime boundary, not a downloadable runtime package. File presence alone does not prove that the binaries are compatible with Android, the rootfs, SELinux, linker, or device kernel.

## Not implemented yet

- No Wine binaries or Linux rootfs are bundled.
- No PRoot process supervisor is integrated.
- No Box64 or 32-bit execution path is integrated.
- No graphics backend is bundled or selected.
- No EXE is launched.
- No Android game surface is connected to a Wine display backend.
- No audio bridge, touch-to-mouse mapping or per-game prefix creation exists.
- No physical-device validation has been performed by this repository bootstrap.

## Build toolchain pinned for the first scaffold

- Android Gradle Plugin: 9.1.1
- Gradle: 9.3.1
- AGP built-in Kotlin + Compose compiler plugin 2.2.10
- Android compile SDK: 37
- Android NDK: 30.0.16248370 (NDK r30, 2026 LTS)
- CMake: 3.22.1
- Compose BOM: 2026.09.00
- AndroidX Activity Compose: 1.13.0
- AndroidX DocumentFile: 1.1.0
- kotlinx.coroutines-android: 1.11.0

These versions are pinned to documented compatible/stable lines for this initial scaffold. Re-check upstream compatibility before changing the build toolchain.

## Next tasks

1. Run GitHub Actions and fix all build/test/lint failures.
2. Test folder scanning against real SAF providers (Downloads, removable storage where available, and local storage).
3. Add a safe folder importer into app-private storage with storage quota checks.
4. Build and package a small ARM64 PRoot host executable.
5. Produce a minimal glibc rootfs and validate PRoot under the Android app UID.
6. Cross-build or integrate Wine and Box64 for that exact rootfs; run a Linux-side smoke test before attempting Windows EXE launch.
7. Only then implement a native process supervisor and a first test Windows GUI executable.
8. Add GPU probing and a graphics backend matrix before shipping DXVK or any driver-specific layer.

## Compatibility policy

- Do not classify a game as supported just because an EXE or engine marker was found.
- Never execute game files during scanning.
- Do not remove system APIs solely to shrink the package; optional features must be tested against the game compatibility corpus.
- Prefer local/offline runtime packages. A first launch must not depend on downloading binaries.
