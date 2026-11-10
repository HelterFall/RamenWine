# PRoot + Box64 + Wine launch contract

**Status:** command builder and process supervisor exist; runtime binaries, compositor socket and device launch remain unverified.

## Purpose

This document describes the first Windows-process launch route using the glibc rootfs + PRoot backend. Host Android binaries stay outside the guest rootfs. Commands are passed as an argument vector; no shell command string is constructed.

## Required package layout

    backend/
      host/
        bin/proot                 # Android/Bionic ARM64 executable
        lib/                      # host libraries required by PRoot
      rootfs/
        usr/bin/box64             # ARM64 Linux/glibc Box64
        usr/bin/wine              # Wine 11 unified loader
        ...                       # Wine PE/Unix drivers and guest libraries

The Wine build must include and load a compatible Wayland driver for the Windows program architecture. The exact driver/library file paths depend on the Wine build, so package construction must validate them rather than assuming one universal path.

## Host/guest path mapping

| Host path | Guest path | Purpose |
|---|---|---|
| filesDir/games/<id> | /games/<id> | Imported game directory |
| filesDir/prefixes/<id> | /prefixes/<id> | Per-game Wine prefix directory |
| filesDir/wine-home/<id> | /home/ramen | Per-game home/configuration |
| cacheDir/wine-tmp/<id> | /tmp | Per-game temporary files |
| Wawona runtime directory | /run/user/<Android UID> | Wayland socket directory |

The command builder prepares private directories and mount targets. It does not run wineboot or prove that a newly created prefix has been initialized.

## Command and environment

The builder requires:
- a valid imported-game UUID and relative directory games/<id>;
- a safe selected EXE path contained in the imported game directory;
- host/bin/proot and host/lib;
- rootfs/usr/bin/env, used to create a clean guest environment;
- the ARM64 glibc and x86-64 library directories expected by the guest environment;
- rootfs/usr/bin/box64 and rootfs/usr/bin/wine, both executable;
- an app-private Wayland runtime directory containing the selected socket;
- a socket path below the Android AF_UNIX sun_path limit.

The argument vector includes PRoot's --kill-on-exit, --link2symlink and --sysvipc options, the rootfs, binds for /dev and /proc, per-game directories, the Wayland runtime directory and the guest working directory. PRoot then starts the guest /usr/bin/env, which removes host-only variables, applies the guest environment and starts /usr/bin/box64 /usr/bin/wine <game-exe> without a shell.

The two environments are deliberately separate:

- **Android host / PRoot:** LD_LIBRARY_PATH points to backend/host/lib so the Android/Bionic PRoot executable can resolve its host libraries. PROOT_TMP_DIR points to the app-private PRoot temporary directory.
- **Linux guest / Box64 / Wine:** HOME, WINEPREFIX, WINEDEBUG, WAYLAND_DISPLAY, XDG_RUNTIME_DIR, TMPDIR, LANG, LC_ALL and PATH are guest paths. LD_LIBRARY_PATH points to ARM64 glibc directories inside the rootfs; BOX64_LD_LIBRARY_PATH points to x86-64 library directories inside the rootfs. BOX64_NOBANNER is set to 1.

This split matters: Box64 uses BOX64_LD_LIBRARY_PATH to find x86-64 shared libraries, while ordinary LD_LIBRARY_PATH is used for the guest's native ARM64/glibc libraries. The Android host library path must not be reused as Wine's guest library path. See the [Box64 environment-variable reference](https://github.com/ptitSeb/box64/blob/main/docs/USAGE.md) and [PRoot-Distro guest-environment documentation](https://github.com/termux/proot-distro#guest-environment) for the corresponding environment model.

The command does not use PRoot fake-root mode. The guest process inherits the app's ordinary Android UID and sandbox.

## Display precondition

The host socket is supplied by a display backend. The builder only checks the socket path before constructing the command; it does not start or verify the compositor protocol handshake. The planned Wawona Android runtime directory is <cacheDir>/wawona-runtime, subject to the actual socket name published by the integrated backend.

Without an in-process compositor and a reachable Wayland socket, a launch command cannot be built. RamenWine currently has no Play action wired to this command path.

## Process supervisor

ProotWineProcessSupervisor starts PRoot directly with ProcessBuilder, merges stdout/stderr, dispatches output and exit callbacks to the main dispatcher, and supports a graceful stop followed by forced stop. The current stop grace period is 1.5 seconds, followed by a 1.5-second forced-wait period.

The supervisor currently supervises the Process returned by ProcessBuilder. It has not been proven that all Wine/Box64 descendants terminate in every failure or Android lifecycle scenario. A production GameSession coordinator must own and verify the complete process tree.

## Security and package trust

Imported games are untrusted. Scanning and import do not execute game code. Runtime ZIP extraction enforces limits, safe paths, symlink containment and SHA-256 payload consistency. A manifest inside the same ZIP is not publisher authentication; only install packages from trusted origins until signed metadata and a pinned key are implemented.

## Validation gates before enabling Play

1. Install a pinned PRoot package and run a host-side version/smoke check under the app UID.
2. Enter the glibc rootfs and run an ARM64 Linux smoke program.
3. Run Box64 and verify host/guest library resolution.
4. Run Wine's version/loader and prefix initialization checks.
5. Start Wawona on an Android Surface and verify a Wayland client can connect to the socket.
6. Run an original Win32 GDI window through PRoot/Box64/Wine and display it in the RamenWine surface.
7. Verify touch, keyboard, IME, resize, pause/resume, exit and complete child-process teardown.

No actual Windows EXE launch is claimed until these gates pass on a physical Android device.