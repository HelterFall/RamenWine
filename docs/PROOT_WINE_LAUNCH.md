# PRoot + Box64 + Wine launch contract

**Status:** command builder and process supervisor implemented; actual runtime package and device launch remain unverified.

## Purpose

This document defines the first real Windows-process launch route for the `glibc-proot-box64` backend. It keeps host Android binaries separate from Linux rootfs binaries and avoids shell-string command construction.

## Required package layout

The runtime package must include at least:

```text
host/
  bin/
    proot                  # ARM64 Android/Bionic executable
rootfs/
  usr/bin/
    box64                  # ARM64 Linux/glibc Box64 executable
    wine64                 # x86-64 Linux/glibc Wine loader
  ...
```

The Wine installation must also include the matching Wayland driver components for the selected Wine build. Their exact paths vary by distribution/build, so the runtime build recipe must verify their presence and test that Wine loads the driver:

- `winewayland.drv` for the relevant Windows PE architecture(s);
- `winewayland.so` for the relevant Unix architecture(s);
- Wayland client libraries and protocols required by that Wine build.

A runtime inventory that omits any required executable is not launch-ready. The command builder checks the three required paths directly before constructing a process command.

## Host/guest path mapping

For each imported game, the builder prepares private host directories and maps them into the guest:

| Host path | Guest path | Purpose |
|---|---|---|
| `filesDir/games/<id>` | `/games/<id>` | Imported game files, read/write only if a game requires it |
| `filesDir/prefixes/<id>` | `/prefixes/<id>` | Persistent per-game Wine prefix |
| `filesDir/wine-home/<id>` | `/home/ramen` | Per-game home/configuration |
| `cacheDir/wine-tmp/<id>` | `/tmp` | Per-game temporary files |
| Wawona/runtime host socket directory | `/run/user/<Android UID>` | Wayland socket |

The launch environment uses:

- `WINEPREFIX=/prefixes/<id>`
- `HOME=/home/ramen`
- `XDG_RUNTIME_DIR=/run/user/<Android UID>`
- `WAYLAND_DISPLAY=wayland-0`
- `TMPDIR=/tmp`
- `PROOT_TMP_DIR=<app-private host cache path>`
- `LANG=C.UTF-8` by default (a per-game locale can be added later)
- `DISPLAY` is explicitly removed so Wine does not choose an inherited X11 display over Wayland.

The Android app does not grant INTERNET permission in this scaffold. The game process inherits the app UID and Android sandbox; PRoot is not root and the command does not use PRoot's fake-root option.

## Command construction

The first launch route is an argument vector, not a shell command:

```text
proot
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
  /usr/bin/wine64
  /games/<id>/<selected.exe>
```

PRoot's documented CLI supports the rootfs, bind and initial-working-directory options used above. PRoot bind targets are prepared inside the guest rootfs before process launch. The implementation does not use `-0`: the Windows game remains in the Android app's ordinary UID/sandbox and does not receive fake root.

## Display preconditions

The command builder refuses to construct a launch if the Wayland socket is absent. This prevents a misleading process start when there is no compositor to display the game's window.

The intended display endpoint is an Android-host Unix socket supplied by a future RamenWine display backend. Wawona is the leading MIT-licensed research candidate. Its compositor has not yet been integrated into RamenWine, so this launcher is not connected to a user-facing Play button.

Wine's Wayland driver must be present and successfully selected. Wine's Wayland driver is not a universal solution for every old application; legacy X11-only dependencies and incomplete Wayland protocol support still need a fallback display strategy.

## Process lifecycle

The process supervisor:
- starts PRoot directly with `ProcessBuilder`;
- merges stdout/stderr into diagnostic output;
- delivers output and exit callbacks on the Android main dispatcher;
- supports graceful process termination followed by forced termination as a fallback.

The implementation has not yet been validated against Wine child-process trees on a physical Android device. A production session manager must verify that all Wine/Box64 children terminate when a game exits or the user closes the session.

## Validation gates before enabling Play

1. Package PRoot for Android/Bionic ARM64 and run `proot --version` under the app UID.
2. Package a minimal ARM64 glibc rootfs and run a Linux ARM64 smoke executable inside it.
3. Run Box64 inside that rootfs and validate host library resolution.
4. Build Wine x86-64 with the Wayland driver and validate the installed PE/Unix driver files.
5. Start the Wayland compositor on Android, bind its socket into the rootfs and run an original Win32 GDI test window.
6. Verify the window appears on the Android SurfaceView and that touch, keyboard, close, pause/resume and process teardown work.
7. Run x86-64 and WoW64 samples before attempting commercial visual-novel engines.

No actual Windows EXE launch is claimed until these gates pass.
