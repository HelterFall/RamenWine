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

The implemented Android installer validates each listed payload file against its manifest SHA-256 before activation, extracts into app-private staging storage, and rolls back to the previous backend if the post-install inventory check fails. The manifest is still not an authenticated publisher statement when it is carried inside the same ZIP.

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
- RuntimeBackendId and inventory for the three backend package families.
- Bounded runtime.properties inspection, host ABI checks, safe relative paths, required-file presence, executable-bit checks and symlink containment.
- Offline ZIP installer with bounded streaming extraction, runtime-manifest parsing, SHA-256 verification, executable flag restoration, symlink validation, staging, activation and rollback after post-install inventory failure.
- Python runtime package builder in tools/package_runtime.py.
- Android UI for selecting a local ZIP and displaying per-backend inventory state.
- ProotWineCommandBuilder for the glibc/PRoot path. It requires host/bin/proot, host/lib, rootfs/usr/bin/box64 and rootfs/usr/bin/wine, then builds a PRoot argument vector and per-game bind/environment configuration.
- ProotWineProcessSupervisor for ProcessBuilder start, merged diagnostic output, exit notification and graceful/forced stop.

These are package-management and launch-scaffolding capabilities. No production Wine/Box64/FEX/PRoot binaries or rootfs are bundled. The command builder requires a Wayland socket from the in-process Wawona display backend. The Compose per-game flow is connected through GameSessionCoordinator, but the native Wawona bundle is staged by a local build/import script and the full launch path has not been validated on a physical device. No Windows EXE launch is claimed.

Not implemented or not proven:
- Signed runtime metadata or a pinned release signing key. SHA-256 protects payload consistency, not publisher identity.
- Runtime binary acquisition/build pipeline and a production rootfs.
- Physical-device validation of first-time per-game Wine prefix initialization.
- Physical-device validation of Wawona, Wine GUI display, graphics, audio and input.
- Wine GUI display, graphics translation, audio and input on a physical device.

## Rule

Bionic-native remains the performance-first experiment; glibc + PRoot remains the compatibility fallback. Production default must be chosen from reproducible device and game results, not theoretical performance. Keep the current offline installer and backend inventory as infrastructure, but do not label an installed package launch-ready until the real smoke tests pass.

## Offline runtime package format

The Android app accepts a locally selected ZIP package. It does not fetch runtime
binaries from the network during installation.

Expected archive layout:

```text
runtime-manifest.json
payload/
  host/
    bin/proot
  rootfs/
    usr/bin/env
    usr/bin/box64
    usr/bin/wine
    usr/lib/...
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
      "path": "rootfs/usr/bin/env",
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
    }
  ],
  "symlinks": [
    {
      "path": "rootfs/bin",
      "target": "usr/bin"
    }
  ]
}
```

The manifest file list is illustrative only; each backend has its own real file
requirements. The installer creates an empty `host/lib` directory for the glibc/PRoot backend if the package does not include one; package host libraries are optional and should only be included when the chosen Android PRoot build requires them. Every file listed in the manifest must be present under
`payload/`, and its SHA-256 must match. Paths must be relative, unique
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


## Pinned source acquisition

The first glibc/PRoot/Box64 source set is tracked in \`tools/runtime-sources.lock.json\`.
Run \`python3 tools/fetch_runtime_sources.py\` from a development/build environment to
download its archives and check out its Git sources into \`build/runtime-sources/\`.
The fetcher verifies SHA-256 for release archives and verifies the full 40-character
Git commit for repositories. It writes \`fetched-sources.json\` as local provenance.
It does not compile any binary, construct the guest rootfs, resolve Wine's runtime
library closure, or produce a runtime ZIP.

Pinned first-pass inputs:

| Component | Pin | Role |
|---|---|---|
| Termux PRoot | \`termux/proot\` commit \`52d65a4d…\` | Android/Bionic host process |
| Box64 | \`ptitSeb/box64\` commit \`c415ed7a…\` | ARM64 glibc guest x86-64 translator |
| talloc | 2.5.0, SHA-256 in lock file | PRoot build dependency; build target intends static linking |
| Wine | 11.21 \`amd64-wow64\`, SHA-256 in lock file | Third-party Vanilla x86-64 new-WoW64 distribution |

The Wine archive is from Kron4ek's third-party Wine-Builds project, not a WineHQ
binary release. Its README describes the Vanilla build as compiled from WineHQ
sources and states that ordinary Wine runtime dependencies are still required.
The WOW64 variant removes the need for a separate 32-bit Unix library stack; it
does not make Wine self-contained. The rootfs assembly step must therefore resolve
and include the actual x86-64 shared-library dependencies before the package can
be considered complete.

The Android NDK pin in the lock is \`30.0.16248370\`. The source fetcher intentionally
does not download the NDK; the native build stage must use that exact installed SDK
package and record its version in build provenance. These are initial reproducible
inputs, not a production release guarantee. Before runtime distribution, RamenWine
must review upstream licenses/notices, generate a dependency closure, run the
binary/package checks, sign the release metadata and move the player flow away from
manual ZIP selection to an app-managed component download.


### Guest ELF dependency audit

Before emitting a glibc/PRoot runtime ZIP, \`tools/package_runtime.py\` now calls
\`tools/audit_runtime_elf.py\` against the supplied \`rootfs/\`. The audit reads
ELF64 program headers and dynamic tables without executing guest binaries. It
checks each guest ELF's interpreter, architecture and direct \`DT_NEEDED\`
libraries, using the binary's RPATH/RUNPATH plus the matching ARM64 or x86-64
library directories. A same-named library only satisfies the dependency when
its ELF machine type also matches the requesting binary.

For investigation without packaging:

\`\`\`bash
python3 tools/audit_runtime_elf.py --rootfs path/to/backend/rootfs --json
\`\`\`

Add \`--strict\` to return a non-zero exit status when the report contains
missing interpreters, unresolved direct library dependencies, malformed ELF
headers or unsupported ELF architectures. Package generation always uses
strict behavior for the glibc/PRoot backend.

This is not a complete dynamic-loader emulator. It does not discover libraries
loaded only with \`dlopen\`, Wine plugins loaded at runtime, Vulkan ICDs, audio
drivers, or Android linker namespace behavior. A clean report is a prerequisite
for packaging, not proof that Wine or a Windows game launches.
