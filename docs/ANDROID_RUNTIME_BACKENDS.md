# Android runtime backend decision

**Status:** first-launch backend selected; alternative backends deferred  
**Scope:** execution environment selection on ARM64 Android

## Decision

Keep the backend interface, but do not build several runtimes in parallel. The first end-to-end launch target is **an ARM64 Linux/glibc rootfs + an Android/Bionic PRoot host + ARM64 Linux Box64 + x86-64 glibc Wine 11 WoW64 with the Wayland driver**. The ARM64 rootfs contains both the ARM64 loader for Box64 and the x86-64 loader/library payload for Wine; this is a multi-ABI guest, not an x86-64-only rootfs. Wine connects to the Wawona Wayland socket. Bionic-native Wine remains a later experiment only after the first visual novel launches.

The Bionic path is an experiment, not yet the default production runtime. Current public ARM64EC Proton/Wine packages for Android are explicitly marked as testing builds and may be unstable. The fallback must remain available until a device/game compatibility corpus shows which path is more reliable.

## Why this first path

The PRoot route matches the launch command and Wine-prefix code already present in RamenWine. The guest is a normal ARM64 glibc userspace so its native Box64 executable can run there; the x86-64 Wine loader and libraries are added as a second guest ABI. The Android app separately owns the Android/Bionic PRoot host process and the Wayland socket.

Advantages:
- Established Android deployment model.
- Keeps the Android/Bionic PRoot host separate from the ARM64 Linux/glibc guest.
- Runs Box64 as an ARM64 Linux/glibc guest process, with x86-64 Wine and its dependencies packaged alongside as a second guest ABI.
- Allows per-game Wine prefixes and a conventional Linux userspace.
- Does not require Android root access.
- Can use Wine's Wayland driver to connect to the Wawona socket without introducing X11/Xwayland in the first milestone.

Costs and limitations:
- PRoot uses ptrace to intercept guest syscalls and rewrite paths; this adds overhead.
- The ARM64 glibc rootfs needs a second x86-64 glibc loader/library closure for Wine; both ABI trees must be pinned and validated.
- Box64 must be built for ARM64 Linux/glibc inside an ARM64 PRoot/chroot environment. Its native Termux/Bionic build is explicitly marked experimental by upstream and documented as not running Linux binaries.
- Android GPU/audio integration still needs host-side bridges and compatible libraries.
- A rootfs is larger than a Bionic-native Wine package.

References:
- [Winlator official site](https://www.winlator.org/) — describes the Android Wine + Box64 stack and PRoot/rootfs architecture.
- [Termux PRoot-Distro documentation](https://github.com/termux/proot-distro) — describes PRoot's ptrace-based syscall interception and unprivileged rootfs operation.

## Deferred experiment: Bionic-native Wine

A Wine build targeting Android's Bionic libc can run in the Android app environment without a glibc rootfs and without PRoot. This may reduce package size and PRoot syscall overhead, but its ARM64EC/FEX and x86-64/Box64 variants require separate validation. Do not implement these paths in parallel with the first launch target.

Reference implementation/release:
- [GameNative/proton-wine releases](https://github.com/GameNative/proton-wine/releases)

Its Android-specific ARM64EC releases document Bionic fixes and FEX integration, but mark the builds as testing/unstable. Treat these as future research inputs, not as a runtime dependency for the first launch.

## Runtime router contract

The app recognizes backend package IDs:

| Backend ID | Purpose |
|---|---|
| `bionic-arm64ec-fex` | Bionic-native ARM64EC Wine + FEX execution |
| `bionic-x64-box64` | Bionic-compatible x86-64 Wine + Box64 |
| `glibc-proot-box64` | ARM64 glibc rootfs + Android/Bionic PRoot host + ARM64 Linux Box64 + x86-64 Wine |

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

## First-launch validation plan

Do not benchmark alternative backends yet. First prove one original Windows GUI process can start and render through Wawona. For this selected path, record:
- install size and incremental size for optional components;
- cold and warm launch time;
- idle and peak RAM;
- CPU use and time spent in syscall-heavy test workloads;
- Vulkan/GPU initialization and frame pacing;
- audio startup and long-session stability;
- behavior across Android pause/resume and process teardown.

Only after the first GUI test passes, repeat using original or appropriately licensed visual-novel fixtures:
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
- an offline ZIP installer with streaming extraction, SHA-256 payload verification, executable flags, safe symlink creation, staged activation and rollback;
- a Python runtime package builder for manifest-backed ZIP archives;
- PRoot/Box64/Wine argument-vector construction and a host-tested Wine-prefix initializer;
- UI display of backend states;
- JVM tests for inventory states, path containment, command construction and process lifecycle.

Not implemented yet:
- no Wine, FEX, Box64 or PRoot runtime package is bundled;
- no Linux rootfs is built or installed;
- runtime metadata is not signed, so SHA-256 does not authenticate the publisher;
- no real Windows EXE launch has been validated;
- no graphics backend is integrated;
- no physical-device performance/compatibility results exist.

## Rule

The current target is one ARM64 glibc rootfs + Android PRoot + ARM64 Linux Box64 + x86-64 Wine Wayland path. Bionic/FEX remains deferred. Reconsider the backend only if the selected path is blocked by a demonstrated technical limitation or a measured incompatibility with the chosen visual-novel fixture.


## Offline runtime package format

The Android app accepts a locally selected ZIP package. It does not fetch runtime
binaries from the network during installation.

Expected archive layout:

```text
runtime-manifest.json
payload/
  host/
    bin/proot
    lib/...
  rootfs/
    lib/ld-linux-aarch64.so.1
    lib64/ld-linux-x86-64.so.2
    usr/bin/box64
    usr/bin/wine
    usr/bin/wineboot
    usr/lib/x86_64-linux-gnu/wine/...
```

Example manifest:

```json
{
  "schemaVersion": 1,
  "backendId": "glibc-proot-box64",
  "hostAbi": "arm64-v8a",
  "runtimeVersion": "prototype-1",
  "files": [
    {
      "path": "host/bin/proot",
      "sha256": "REPLACE_WITH_64_HEX_SHA256",
      "executable": true
    },
    {
      "path": "rootfs/lib/ld-linux-aarch64.so.1",
      "sha256": "REPLACE_WITH_64_HEX_SHA256",
      "executable": true
    },
    {
      "path": "rootfs/lib64/ld-linux-x86-64.so.2",
      "sha256": "REPLACE_WITH_64_HEX_SHA256",
      "executable": true
    },
    {
      "path": "rootfs/usr/bin/box64",
      "sha256": "REPLACE_WITH_64_HEX_SHA256",
      "executable": true
    },
    {
      "path": "rootfs/usr/bin/wine",
      "sha256": "REPLACE_WITH_64_HEX_SHA256",
      "executable": true
    },
    {
      "path": "rootfs/usr/lib/x86_64-linux-gnu/wine/x86_64-unix/winewayland.so",
      "sha256": "REPLACE_WITH_64_HEX_SHA256",
      "executable": false
    },
    {
      "path": "rootfs/usr/lib/x86_64-linux-gnu/wine/x86_64-windows/winewayland.drv",
      "sha256": "REPLACE_WITH_64_HEX_SHA256",
      "executable": false
    }
  ],
  "symlinks": [
    {
      "path": "rootfs/bin",
      "target": "usr/bin"
    },
    {
      "path": "rootfs/usr/bin/wineboot",
      "target": "wine"
    }
  ]
}
```

The manifest file list is illustrative only; each backend has its own real file
requirements. Every file listed in the manifest must be present under
`payload/`, and its SHA-256 must match. Entries listed under `symlinks` are created
only after their targets are normalized and confirmed to remain inside the backend. Paths must be relative, unique
case-insensitively and remain inside the backend staging directory. Symlink
targets are normalized to remain inside that backend directory. The installer
sets executable permissions from the manifest and writes the runtime inventory
only after extraction.

Installation is staged in app-private storage. The previous backend is renamed
to a temporary backup; the new directory is activated; the app then performs a
post-install inventory check. If the check fails, the new directory is removed
and the backup is restored.

**Security boundary:** SHA-256 checks detect a damaged or inconsistent file
payload, but a manifest stored inside the same ZIP does not authenticate who
created the package. Until RamenWine adds a pinned signing key and signed
release metadata, users should install runtime ZIPs only from a source they
trust. A future release pipeline must sign package metadata (for example with
Ed25519) and the app must verify the signature before extraction.

The installer limits ZIP entries, manifest size, uncompressed bytes, and
available disk reserve. It does not execute any runtime or game binary during
installation. Presence of installed files is still not proof that Wine starts.
