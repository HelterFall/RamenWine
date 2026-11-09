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
  lib/                     # Android/Bionic dependencies of PRoot/Box64
rootfs/
  lib64/
    ld-linux-x86-64.so.2   # x86-64 glibc ELF interpreter
  usr/bin/
    box64                  # ARM64 Android/Bionic Box64 executable
    wine                   # x86-64 Wine 11 unified WoW64 loader
    wineboot               # Wine prefix initializer (link to the same loader)
  usr/lib/x86_64-linux-gnu/wine/
    x86_64-unix/
      winewayland.so       # x86-64 Unix side of Wine's Wayland driver
    x86_64-windows/
      winewayland.drv      # PE side of Wine's Wayland driver
  ...
```

RamenWine normalizes the runtime package to these paths. The command builder refuses to launch if the x86-64 glibc loader or either Wayland driver half is missing. The package build must also include the recursive x86-64 ELF dependency closure for Wine, the Wayland driver and Box64's guest libraries (including the Wayland client/protocol libraries selected by that Wine build).

A runtime inventory that omits any required executable is not launch-ready. The command builder checks the PRoot host executable, the host library directory, and the Box64/Wine executables directly before constructing a process command. Prefix initialization additionally requires an executable `/usr/bin/wineboot` entry; a relative symlink to the unified Wine loader is acceptable when included safely in the runtime package.

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
- `PROOT_TMP_DIR=<app-private host cache path>`
- `LD_LIBRARY_PATH=<backend>/host/lib` for the ARM64 Android/Bionic PRoot + Box64 host binaries
- `BOX64_LD_LIBRARY_PATH=/lib/x86_64-linux-gnu:/usr/lib/x86_64-linux-gnu:/usr/lib/x86_64-linux-gnu/wine/x86_64-unix` for x86-64 guest libraries
- `WINEDLLPATH` points at the packaged x86-64 Windows and Unix Wine driver directories
- `WINEARCH=win64` for the 64-bit WoW64 prefix
- `LANG=C.UTF-8` by default (a per-game locale can be added later)
- `DISPLAY` is explicitly removed so Wine does not choose an inherited X11 display over Wayland.

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
  /usr/bin/box64
  /usr/bin/wine
  /games/<id>/<selected.exe>
```

PRoot's documented CLI supports the rootfs, bind and initial-working-directory options used above. PRoot bind targets are prepared inside the guest rootfs before process launch. The implementation does not use `-0`: the Windows game remains in the Android app's ordinary UID/sandbox and does not receive fake root.

## Wine prefix initialization

Before the selected EXE starts, GameLaunchCoordinator asks WinePrefixInitializer to prepare the game's private prefix. If neither \`system.reg\` nor \`user.reg\` exists, it builds a separate PRoot command:

\`\`\`text
/usr/bin/box64 /usr/bin/wineboot --init
\`\`\`

The initializer serializes concurrent requests, tags output with \`[wineboot]\`, and waits up to five minutes. A non-zero exit, timeout, or cancellation prevents the game EXE from starting and attempts to stop the bootstrap process. A successful exit is accepted only when both \`system.reg\` and \`user.reg\` exist. If exactly one registry file exists, the prefix is treated as partial and fails closed rather than being overwritten automatically.

The package must expose Wineboot at \`/usr/bin/wineboot\`; standard Wine package layouts provide that entry point, often as a symlink to the unified \`wine\` loader. The \`--init\` option creates the initial prefix. See the [Wineboot manual](https://manpages.debian.org/testing/wine-development/wineboot.1.en.html) and the [Arch Wine file list](https://archlinux.org/packages/extra/x86_64/wine/files/).

The JVM tests use a controlled fake PRoot process that creates the expected registry files. This verifies orchestration, timeout/error gating and concurrency, but it is not proof that a real Wine build can initialize a prefix in the final rootfs.

## Display preconditions

The command builder refuses to construct a launch if the Wayland socket is absent, is not an actual Unix-domain socket (checked with Android lstat), or its runtime directory resolves outside the app-private cache directory. It also requires the guest glibc loader and both Wine Wayland driver files before preparing PRoot. This prevents a regular file, stale placeholder or external directory from masquerading as a compositor endpoint and catches a runtime that cannot display a Wine window before starting a process.

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
2. Package a minimal ARM64 glibc rootfs and run a Linux ARM64 smoke executable inside it.
3. Run Box64 inside that rootfs and validate host library resolution.
4. Build Wine x86-64 with the Wayland driver, verify `wineboot --init` on the target rootfs, and validate the installed PE/Unix driver files.
5. Start the Wayland compositor on Android, bind its socket into the rootfs and run an original Win32 GDI test window.
6. Verify the window appears on the Android SurfaceView and that touch, keyboard, close, pause/resume and process teardown work.
7. Run x86-64 and WoW64 samples before attempting commercial visual-novel engines.

No actual Windows EXE launch is claimed until these gates pass.
