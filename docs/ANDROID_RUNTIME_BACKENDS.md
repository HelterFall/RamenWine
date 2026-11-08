# Android runtime backend decision

**Status:** architecture decision for the first Android runtime prototypes  
**Scope:** execution environment selection on ARM64 Android

## Decision

RamenWine must not hard-code one Android/Linux execution strategy. Keep a backend interface and validate two families:

1. **Bionic-native Wine** — preferred performance experiment because it can run without a glibc rootfs and PRoot syscall interception.
2. **glibc Linux rootfs + PRoot** — compatibility fallback based on the established Winlator-style Android architecture.

The Bionic path is an experiment, not yet the default production runtime. Current public ARM64EC Proton/Wine packages for Android are explicitly marked as testing builds and may be unstable. The fallback must remain available until a device/game compatibility corpus shows which path is more reliable.

## Why two paths

### Bionic-native path

A Wine build targeting Android's Bionic libc can run in the Android app environment without a glibc rootfs and without PRoot. This avoids PRoot's ptrace syscall interception overhead and reduces the amount of Linux userspace that must be shipped.

Two variants should be evaluated separately:

- **ARM64EC + FEX**: ARM64EC Wine build with FEX-based x86/x64 execution. This is a promising compatibility/performance experiment, but needs validation for the exact games, device ABI, Android linker, graphics driver and required support libraries.
- **x86-64 Wine + Box64**: Wine and Box64 builds that are compatible with Bionic. Do not assume that a generic glibc Box64 binary can be used in this path.

Reference implementation/release:
- [GameNative/proton-wine releases](https://github.com/GameNative/proton-wine/releases)

Its Android-specific ARM64EC releases document Bionic fixes and FEX integration, but mark the builds as testing/unstable. Treat these as research evidence, not as a production guarantee.

### glibc rootfs + PRoot path

This path packages a Linux root filesystem with Wine and Box64, then enters it through a host-side PRoot executable.

Advantages:
- Established Android deployment model.
- Keeps glibc-oriented Linux binaries together in a rootfs.
- Allows per-game Wine prefixes and a conventional Linux userspace.
- Does not require Android root access.

Costs and limitations:
- PRoot uses ptrace to intercept guest syscalls and rewrite paths. This has measurable overhead and must be benchmarked.
- Android GPU/audio integration still needs host-side bridges and compatible libraries.
- A rootfs is larger than a Bionic-native Wine package.

References:
- [Winlator official site](https://www.winlator.org/) — describes the Android Wine + Box86/Box64 stack and credits PRoot, rootfs, Mesa, DXVK, VKD3D and D8VK.
- [Termux PRoot-Distro documentation](https://github.com/termux/proot-distro) — describes PRoot's ptrace-based syscall interception and unprivileged rootfs operation.

## Runtime router contract

The app recognizes backend package IDs:

| Backend ID | Purpose |
|---|---|
| `bionic-arm64ec-fex` | Bionic-native ARM64EC Wine + FEX execution |
| `bionic-x64-box64` | Bionic-compatible x86-64 Wine + Box64 |
| `glibc-proot-box64` | glibc rootfs + PRoot + Box64 + Wine |

The backend inventory is stored under:

```text
files/runtime/current/
  backends/
    bionic-arm64ec-fex/
      runtime.properties
      ...
    bionic-x64-box64/
      runtime.properties
      ...
    glibc-proot-box64/
      runtime.properties
      ...
```

Each `runtime.properties` file must contain:

```properties
backendId=bionic-arm64ec-fex
hostAbi=arm64-v8a
runtimeVersion=build-id
requiredFiles=bin/wine,lib/wine/...
executableFiles=bin/wine
```

- `backendId` must match the backend directory.
- `hostAbi` must match the installed app ABI.
- Every path in `requiredFiles` must be relative, remain inside the backend directory and exist.
- Every path in `executableFiles` must also appear in `requiredFiles` and have executable permissions.
- A valid manifest and file inventory mean only **FILES_PRESENT**, not that Wine can launch a game.

A future package installer must validate package SHA-256 digests before extraction and must extract into a staging directory before atomically activating a runtime version. The runtime package manifest is trusted only after its package digest has been verified.

## Prototype and comparison plan

Do not select the production backend from theoretical performance alone. Build three small, independently testable prototypes:

1. ARM64EC/FEX Bionic Wine launching an original x86-64 Windows GUI test program.
2. Bionic-compatible x86-64 Wine + Box64 launching the same program.
3. glibc rootfs + PRoot + Box64 launching the same program.

For each path, record:
- install size and incremental size for optional components;
- cold and warm launch time;
- idle and peak RAM;
- CPU use and time spent in syscall-heavy test workloads;
- Vulkan/GPU initialization and frame pacing;
- audio startup and long-session stability;
- behavior across Android pause/resume and process teardown.

Then repeat using original or appropriately licensed visual-novel fixtures:
- 32-bit Win32 GUI + GDI;
- x86-64 Win32 GUI;
- DirectDraw / Direct3D 8-era renderer;
- Direct3D 9;
- Direct3D 10/11 where device Vulkan features permit;
- KiriKiri/KAG sample;
- Unity sample.

Keep backend selection per game. Never replace a known-working profile automatically with a newer runtime.

## Graphics integration rule

Wine execution backend and graphics backend are separate decisions. A Bionic Wine build does not itself provide a working Android Vulkan translation stack, and a glibc rootfs does not make Android GPU drivers automatically compatible with glibc.

Graphics selection remains a separate router:
- DXVK only when the exact driver exposes the required Vulkan features.
- WineD3D or other tested paths for fallback.
- Separate legacy DirectDraw/Direct3D 8 and D3D12 paths where validated.

## Current implementation status

Implemented in RamenWine:
- `RuntimeBackendId` for the three package families;
- a bounded `runtime.properties` inventory reader;
- checks for host ABI, safe relative paths, required files and executable bits;
- UI display of backend states;
- JVM tests for missing, incomplete, complete and path-escape cases.

Not implemented yet:
- no Wine, FEX, Box64 or PRoot runtime package is bundled;
- no rootfs is built or installed;
- no runtime package installer or checksum verifier exists;
- no EXE launch process exists;
- no graphics backend is integrated;
- no physical-device performance/compatibility results exist.

## Rule

Bionic-native is the performance-first experiment; glibc + PRoot is the compatibility fallback. Production default is chosen only from reproducible device and game test results.
