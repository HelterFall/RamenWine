# PRoot + Box64 + Wine launch contract

**Status:** command builder, process supervisor and host-tested Wine-prefix initializer implemented; actual runtime package and device launch remain unverified.

## Purpose

This document defines the first real Windows-process launch route for the `glibc-proot-box64` backend. It keeps host Android binaries separate from Linux rootfs binaries and avoids shell-string command construction.

## Required package layout

The runtime package must include at least:

```text
host/
  bin/
    proot                  # ARM64 Android/Bionic executable
  lib/                     # Bionic dependencies (including libtalloc/libandroid-shmem) for PRoot
  libexec/
    proot/
      loader               # ARM64 PRoot ELF helper; selected with PROOT_LOADER
rootfs/                    # ARM64 Linux distribution with glibc
  lib/
    ld-linux-aarch64.so.1  # ARM64 glibc ELF interpreter for Box64
  lib64/
    ld-linux-x86-64.so.2   # x86-64 glibc ELF interpreter for Wine
  usr/bin/
    env                    # ARM64 coreutils env used to create a clean guest environment
    box64                  # ARM64 Linux/glibc Box64 executable
    wine                   # x86-64 Wine 11 unified WoW64 loader
    wineboot               # Wine prefix initializer (entry point for the unified loader)
  usr/lib/x86_64-linux-gnu/wine/
    x86_64-unix/
      winewayland.so       # x86-64 Unix side of Wine's Wayland driver
    x86_64-windows/
      winewayland.drv      # PE side of Wine's Wayland driver
  ...
```

RamenWine normalizes the runtime package to these paths. The rootfs is **ARM64 glibc**, not an x86-64-only image: native ARM64 Linux programs run directly under PRoot, Box64 is an ARM64/glibc guest executable, and Box64 loads the x86-64 Wine WoW64 files installed alongside the ARM64 userspace. The x86-64 loader and x86-64 libraries are an additional guest ABI inside that same rootfs.

The command builder refuses to launch if either glibc loader or either Wayland driver half is missing. The package build must include the ARM64 dependency closure for Box64, the x86-64 dependency closure for Wine and its Wayland Unix driver, and the Wayland client/protocol libraries selected by that Wine build. Wine's PE Wayland driver must be packaged as well.

A runtime inventory that omits any required executable is not launch-ready. The command builder checks the PRoot host executable, the host library directory, and the Box64/Wine executables directly before constructing a process command. Prefix initialization additionally requires an executable `/usr/bin/wineboot` entry; a relative symlink to the unified Wine loader is acceptable when included safely in the runtime package.

## Pinned upstream build inputs (not yet assembled or device-validated)

These pins define the first reproducible build attempt. They are source inputs, not a claim that a ready-to-install package exists.

| Component | Source pin | Build / package note |
|---|---|---|
| Android PRoot host | [Termux PRoot v5.1.107.102](https://github.com/termux/proot/tree/v5.1.107.102), commit `52d65a4dc140ca554d241a1438ddd4f6b00c9813` | Use the Android-patched Termux fork; its package recipe builds with `PROOT_WITH_LIBANDROID_SHMEM=true` and declares `libandroid-shmem` + `libtalloc` host dependencies. The recipe pins the source archive SHA-256 to `30d12a6e2cc4147123756ff58edea262d3083bbc238c12d0ab9b39e3d1c40852`. Build with `PROOT_UNBUNDLE_LOADER=<staging>/host/libexec/proot`, which installs `loader` there, then set the runtime host variable `PROOT_LOADER=<backend>/host/libexec/proot/loader` to override the Termux prefix path compiled into the binary. |
| Android build toolchain | Termux package build system commit `29a6879366dad92650fb7db7719c5a4b1b9d55f1` | Android SDK command-line tools revision `9123335` (SHA-256 `0bebf59339eaa534f4217f8aa0972d14dc49e7207be225511073c661ae01da0a`); NDK `r30` (SHA-256 `753611f410d002cfcd3f3dc2ef49aad532089d3180b436c060a90bf0fcb64df2`); Android build-tools `37.0.0`. The PRoot host artifact records these pins in `host/PROVENANCE.json`. |
| PRoot host support libraries | [libandroid-shmem v0.7](https://github.com/termux/libandroid-shmem/tree/v0.7) and [talloc 2.5.0](https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz) | Termux package recipes at the pinned build-system commit set source SHA-256 values: libandroid-shmem `1e5ff8459bc0a8c229dd8a94b27d119987e09ef3414331c2b5ebfff20b98e867`; talloc `912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007`. Build/package only the ARM64 Android/Bionic libraries needed by PRoot. |
| ARM64 glibc rootfs seed | [Ubuntu Base 24.04.5 ARM64](https://cdimages.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz) | SHA-256: `a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2`. This is the official minimal base archive, not the completed Wine/Box64 runtime. |
| Box64 guest | [Box64 v0.4.4](https://github.com/ptitSeb/box64/tree/2f130fab1d6e1a4ee8a71dc60cfdfcc839ad192a), commit `2f130fab1d6e1a4ee8a71dc60cfdfcc839ad192a` | Build inside ARM64 Linux/PRoot with the upstream ARM64/PRoot configuration (`ARM64=1`, `ARM_DYNAREC=ON`, `BAD_SIGNAL=ON`, GCC). Output belongs in the ARM64 glibc rootfs. |
| Wine guest | [Wine 11.0 source](https://dl.winehq.org/wine/source/11.0/wine-11.0.tar.xz) | SHA-256: `c07a6857933c1fc60dff5448d79f39c92481c1e9db5aa628db9d0358446e0701`. Build x86-64 Unix Wine with PE targets `--enable-archs=i386,x86_64` and the corresponding MinGW/LLVM-MinGW cross-compilers to support the new WoW64 mode. Do not use `--enable-win64` alone: Wine configure describes it as a Win64-only emulator that will not run Win32 binaries. Install the normal Wine build outputs and explicitly verify the executable `/usr/bin/wine` and `/usr/bin/wineboot` entries; there is no `--enable-tools` configure option. Install Wayland development headers and libraries before configure, do not pass `--without-wayland`, and fail the build if either `winewayland.so` or `winewayland.drv` is absent. |

Wine 11.0 completes the new WoW64 implementation and replaces the separate `wine64` loader with one `wine` loader; the runtime must therefore build both i386 and x86-64 PE targets while retaining the x86-64 Unix ABI used by Box64. The source-build gate checks the generated driver files and the installed Wine entry points rather than trusting configure options alone.

The ARM64 rootfs base is pinned to the official Ubuntu Base 24.04.5 ARM64 archive above. Its SHA-256 authenticates only this minimal seed archive. The final rootfs is not yet reproducible: the Ubuntu package snapshot, package versions for both ARM64 and x86-64, Box64 build/install outputs, Wine build dependencies and the complete shared-library closure must still be pinned and assembled. The package builder currently zips an already prepared directory; it does not download or compile any of these inputs.

## Building the Android PRoot host payload

The source tree now contains a separate manual-only workflow:
[`.github/workflows/build-proot-host-runtime.yml`](https://github.com/HelterFall/RamenWine/blob/feature/wawona-shm-smoke/.github/workflows/build-proot-host-runtime.yml).

Run **Build Android PRoot host payload (manual runtime only)** from the Actions tab on the feature branch. It builds the pinned Termux package system at commit `29a6879366dad92650fb7db7719c5a4b1b9d55f1`, requests the `aarch64` `proot` package (which declares `libandroid-shmem` and `libtalloc` dependencies), stages the three exact package versions into the `host/` layout, validates the ARM64 ELF interpreter and loader, and uploads a separate `ramenwine-proot-host-arm64-<commit>` artifact.

The source inputs and SDK/NDK archive checksums are pinned, but this is **not yet a bit-for-bit hermetic build**: the Termux builder Dockerfile uses the mutable `ubuntu:26.04` tag and runs `apt-get update`/upgrade without an Ubuntu snapshot. The artifact records the source commit, package versions, source archive pins and output DEB SHA-256 values in `host/PROVENANCE.json`. Pinning the builder base-image digest and apt snapshot remains a separate reproducibility task.

This workflow is `workflow_dispatch` only. It does not run on pushes or pull requests, does not assemble an APK, and does not claim that Wine or the complete Windows runtime is ready.

## Host/guest path mapping

For each imported game, the builder prepares private host directories and maps them into the guest:

| Host path | Guest path | Purpose |
|---|---|---|
| `filesDir/games/<id>` | `/games/<id>` | Imported game files, read/write only if a game requires it |
| `filesDir/prefixes/<id>` | `/prefixes/<id>` | Persistent per-game Wine prefix |
| `filesDir/wine-home/<id>` | `/home/ramen` | Per-game home/configuration |
| `cacheDir/wine-tmp/<id>` | `/tmp` | Per-game temporary files |
| Android-host display backend runtime directory | `/run/user/<Android UID>` | Wayland socket |

The launch environment uses:

- `WINEPREFIX=/prefixes/<id>`
- `HOME=/home/ramen`
- `XDG_RUNTIME_DIR=/run/user/<Android UID>`
- `WAYLAND_DISPLAY=wayland-0`
- `TMPDIR=/tmp`
The environment is deliberately split into two layers:

- Android/PRoot host environment: `LD_LIBRARY_PATH=<backend>/host/lib` for Bionic PRoot dependencies, `PROOT_LOADER=<backend>/host/libexec/proot/loader` for the separately-installed PRoot ELF helper, and `PROOT_TMP_DIR=<app-private host cache path>`. These are passed to the PRoot process by `ProcessBuilder`; none are passed into the guest Wine environment.
- ARM64 Linux guest environment: set by the guest `/usr/bin/env` immediately before Box64. It includes `LD_LIBRARY_PATH=/lib/aarch64-linux-gnu:/usr/lib/aarch64-linux-gnu`, `BOX64_LD_LIBRARY_PATH=/lib/x86_64-linux-gnu:/usr/lib/x86_64-linux-gnu:/usr/lib/x86_64-linux-gnu/wine/x86_64-unix`, `WINEDLLPATH` for the packaged Wine driver directories, `WINEPREFIX`, `HOME`, `XDG_RUNTIME_DIR`, `WAYLAND_DISPLAY`, `WINEARCH=win64`, `LANG`, `LC_ALL`, `PATH` and `BOX64_NOBANNER=1`.

The guest `env -i` invocation discards the entire inherited environment before applying only the explicit ARM64 glibc, Box64, Wine, Wayland and locale variables. This prevents Android/Bionic paths and unrelated host settings from leaking into Wine or the game process. This matches the launch layering used by [Winlator WB64's guest launcher](https://github.com/winebox64/winlator-wb64/blob/main/app/src/main/java/com/winlator/xenvironment/components/GuestProgramLauncherComponent.java), which runs guest `env` and then `box64` under PRoot. The host-only `PROOT_TMP_DIR` remains outside that guest environment.

The Android app does not grant INTERNET permission in this scaffold. The game process inherits the app UID and Android sandbox; PRoot is not root and the command does not use PRoot's fake-root option.

## Command construction

The first launch route is an argument vector, not a shell command:

```text
proot
  --kill-on-exit
  --link2symlink
  --sysvipc
  --rootfs=<app-private backend>/rootfs
  --bind=/dev:/dev
  --bind=/proc:/proc
  --bind=<game host dir>:/games/<id>
  --bind=<prefix host dir>:/prefixes/<id>
  --bind=<home host dir>:/home/ramen
  --bind=<temp host dir>:/tmp
  --bind=<Wayland socket dir>:/run/user/<uid>
  --pwd=/games/<id>
  /usr/bin/env
  -i
  HOME=/home/ramen
  WINEPREFIX=/prefixes/<id>
  LD_LIBRARY_PATH=/lib/aarch64-linux-gnu:/usr/lib/aarch64-linux-gnu
  BOX64_LD_LIBRARY_PATH=/lib/x86_64-linux-gnu:/usr/lib/x86_64-linux-gnu:/usr/lib/x86_64-linux-gnu/wine/x86_64-unix
  WINEDLLPATH=/usr/lib/x86_64-linux-gnu/wine/x86_64-windows:/usr/lib/x86_64-linux-gnu/wine/x86_64-unix
  WAYLAND_DISPLAY=wayland-0
  XDG_RUNTIME_DIR=/run/user/<uid>
  /usr/bin/box64
  /usr/bin/wine
  /games/<id>/<selected.exe>
```

PRoot's documented CLI supports the rootfs, bind and initial-working-directory options used above. PRoot bind targets are prepared inside the guest rootfs before process launch. The implementation does not use `-0`: the Windows game remains in the Android app's ordinary UID/sandbox and does not receive fake root.

## Wine prefix initialization

Before the selected EXE starts, GameLaunchCoordinator asks WinePrefixInitializer to prepare the game's private prefix. If neither \`system.reg\` nor \`user.reg\` exists, it builds a separate PRoot command:

\`\`\`text
/usr/bin/env -i <guest environment assignments> /usr/bin/box64 /usr/bin/wineboot --init
\`\`\`

The initializer serializes concurrent requests, tags output with \`[wineboot]\`, and waits up to five minutes. A non-zero exit, timeout, or cancellation prevents the game EXE from starting and attempts to stop the bootstrap process. A successful exit is accepted only when both \`system.reg\` and \`user.reg\` exist. If exactly one registry file exists, the prefix is treated as partial and fails closed rather than being overwritten automatically.

The package must expose Wineboot at \`/usr/bin/wineboot\`; standard Wine package layouts provide that entry point, often as a symlink to the unified \`wine\` loader. The \`--init\` option creates the initial prefix. See the [Wineboot manual](https://manpages.debian.org/testing/wine-development/wineboot.1.en.html) and the [Arch Wine file list](https://archlinux.org/packages/extra/x86_64/wine/files/).

The JVM tests use a controlled fake PRoot process that creates the expected registry files. This verifies orchestration, timeout/error gating and concurrency, but it is not proof that a real Wine build can initialize a prefix in the final rootfs.

## Display preconditions

The command builder refuses to construct a launch if the Wayland socket is absent, is not an actual Unix-domain socket (checked with Android lstat), or its runtime directory resolves outside the app-private cache directory. It also requires both guest glibc loaders — ARM64 for Box64 and x86-64 for Wine — and both Wine Wayland driver files before preparing PRoot. This catches a rootfs with the wrong ABI or missing display payload before starting a process.

The intended display endpoint is an Android-host Unix socket supplied by WawonaDisplayBackend. The RamenWine adapter source is present, but its pinned Nix-built native payload still has to be generated/staged and its Vulkan surface must pass a physical-device smoke test. Wawona's Android target is an application rather than a reusable AAR; see [Wawona Integration](WAWONA_INTEGRATION.md). GameLaunchCoordinator starts the supplied backend, validates its resulting socket, builds the command and owns the display/process lifetime. This launcher is still not connected to a user-facing Play button.

Wine's Wayland driver must be present and successfully selected. Wine's Wayland driver is not a universal solution for every old application; legacy X11-only dependencies and incomplete Wayland protocol support still need a fallback display strategy.

## Process lifecycle

The process supervisor:
- starts PRoot directly with `ProcessBuilder`, without a shell;
- merges stdout/stderr into diagnostic output;
- delivers output and exit callbacks on the configured callback dispatcher (Android main dispatcher by default);
- waits for the output stream to reach EOF before delivering the exit callback, including the manual-stop path;
- supports graceful termination followed by forced termination and reports an error if PRoot remains alive.

JVM regression tests exercise merged output, natural exit, deterministic final-output draining, concurrent stop calls, graceful-to-forced termination escalation, prefix initialization and fail-closed prefix checks. The process-wide prefix lock is shared between coordinator instances, while each display-session lease serializes resize and close. The GameLaunchCoordinator runs prefix initialization before starting the selected game and closes its display session once the supervised PRoot process exits or is stopped. The implementation has not yet been validated against Wine child-process trees on a physical Android device; a production backend must verify that all Wine/Box64 children terminate when a game exits or the user closes the session.

## Validation gates before enabling Play

1. Package PRoot for Android/Bionic ARM64 and run `proot --version` under the app UID.
2. Install an ARM64 glibc rootfs and run a Linux ARM64 smoke executable inside it; verify its ARM64 ELF interpreter is present.
3. Build Box64 for generic ARM64 Linux/glibc inside an ARM64 PRoot/chroot environment; run `box64 -v` and an x86-64 ELF smoke program. Do not use Box64's native Termux build, which upstream labels experimental and says will not run Linux binaries.
4. Add the x86-64 glibc loader and library closure to the same rootfs. Build Wine 11 WoW64 with Wayland development dependencies present, verify both Wayland driver outputs, and prove `wineboot --init` creates both registry files.
5. Start the Wayland compositor on Android, bind its socket into the rootfs and run an original Win32 GDI test window.
6. Verify the window appears on the Android SurfaceView and that touch, keyboard, close, pause/resume and process teardown work.
7. Run x86-64 and WoW64 samples before attempting commercial visual-novel engines.

No actual Windows EXE launch is claimed until these gates pass.
