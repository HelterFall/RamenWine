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
- The Ubuntu Base 24.04.5 ARM64 seed is pinned by SHA-256, but its apt snapshot and all ARM64/x86-64 runtime package versions still need pinning; the guest also needs a second x86-64 glibc loader/library closure for Wine.
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

Implemented in RamenWine source:
- `RuntimeBackendId` and a bounded inventory reader for the backend families;
- an internal offline ZIP extractor with streaming SHA-256 verification, safe path/symlink handling, staging, atomic activation and rollback;
- a Python runtime package builder and source-build scripts for Android PRoot, Ubuntu/glibc, Box64 and Wine;
- PRoot/Box64/Wine argument-vector construction and a host-tested Wine-prefix initializer;
- ARM64/x86-64 ELF and i386/AMD64 PE architecture/dependency validation;
- a Wawona Android display adapter and a pinned native payload already included in a successfully packaged debug APK;
- an Android game surface and touch-to-mouse input wiring.

Not ready yet:
- the complete PRoot + Ubuntu ARM64 + Box64 + Wine runtime has not been assembled, installed or booted on the phone;
- the in-app download/provisioning manager and signed runtime release catalog do not exist yet;
- no real Windows EXE or visual novel has launched successfully;
- the Wawona asset-provenance fix has passed source-level checks, but the fixed APK still needs a physical-device rendering retest.

The manual runtime ZIP picker has been removed from the Android UI. The installer remains an internal implementation primitive; it is not a player feature.
## Rule

The current target is one ARM64 glibc rootfs + Android PRoot + ARM64 Linux Box64 + x86-64 Wine Wayland path. Bionic/FEX remains deferred. Reconsider the backend only if the selected path is blocked by a demonstrated technical limitation or a measured incompatibility with the chosen visual-novel fixture.


## Internal runtime archive format (not a player action)

This ZIP layout is an internal build/transport format. It is not a player-facing file. The normal Android UI must never ask the player to select, browse for or import a runtime ZIP.

The target flow is app-owned provisioning: when a game needs the shared runtime and none is installed, RamenWine fetches a versioned artifact from its pinned distribution channel, verifies signed release metadata and package/file hashes, stages and validates the contents, and atomically activates the new version. The player sees progress and retry/error messages, not an archive picker. Once installed, the shared runtime is reusable by every game and works offline.

The Android app does not yet implement that network provisioner or signed release channel. Until then the Play button remains unavailable when the runtime is missing. The internal installer can only consume an archive supplied by controlled development code.

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
    usr/lib/x86_64-linux-gnu/wine/x86_64-windows/wineboot.exe
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
      "path": "rootfs/usr/lib/x86_64-linux-gnu/wine/x86_64-windows/wineboot.exe",
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
created the package. The planned RamenWine release channel must publish an
immutable versioned archive and an Ed25519-signed index/manifest. The Android
app must verify that signature with a public key pinned in the APK before it
trusts the package digest or extracts any payload. The app must not rely on a
mutable `latest` URL or on hashes supplied only by the same untrusted ZIP.

The installer limits ZIP entries, manifest size, uncompressed bytes, and
available disk reserve. It does not execute any runtime or game binary during
installation. Presence of installed files is still not proof that Wine starts.

## Compatibility scope: broad Windows API, not an engine allow-list

The selected Wine build is the generic Windows compatibility layer. RamenWine
must not compile a small API subset tied to named visual-novel engines, and it
must not reject an EXE only because its engine is unknown. Keep the Wine
components that applications may call directly or indirectly (including COM,
cryptography, networking, installers, multimedia, fonts, input and system
libraries) unless a concrete dependency analysis proves they are not part of
the supported runtime contract.

The product does not need a Windows desktop shell. Removing Explorer, Start
menu, taskbar, terminal, window manager and unrelated Linux desktop programs is
a separate decision from removing Wine API support. Optional rendering
translators such as DXVK/VKD3D or legacy compatibility libraries can be
versioned app-managed add-ons; the generic Wine runtime remains broad.

See [Runtime Delivery](RUNTIME_DELIVERY.md) for the player-facing contract and
the planned automatic in-app provisioning lifecycle.
