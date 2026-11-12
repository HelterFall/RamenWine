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
        usr/lib/wine/x86_64-windows/wineboot.exe
        ...                       # Wine PE/Unix drivers and guest libraries

The Wine build must include and load a compatible Wayland driver for the Windows program architecture. The exact driver/library file paths depend on the Wine build, so package construction must validate them rather than assuming one universal path.

The glibc package is rejected before extraction unless it contains executable `host/bin/proot`, `rootfs/usr/bin/env`, `rootfs/usr/bin/box64` and `rootfs/usr/bin/wine`, plus a readable `wineboot.exe` at one of the supported paths. Symlink chains are followed when checking required launch files. The installer creates the app-private `host/lib` directory when it is omitted; extra Bionic host libraries may be packaged there only when the selected PRoot build actually needs them.

The binary ABI is also checked from the actual headers:

| Path | Required format |
|---|---|
| `host/bin/proot` | 64-bit little-endian AArch64 ELF (Android/Bionic host) |
| `rootfs/usr/bin/env` | 64-bit little-endian AArch64 ELF (guest glibc) |
| `rootfs/usr/bin/box64` | 64-bit little-endian AArch64 ELF |
| `rootfs/usr/bin/wine` | 64-bit little-endian x86-64 ELF (launched through Box64) |
| `wineboot.exe` | AMD64 PE32+ |

This selects the x86-64 Wine WOW64 route under Box64. Box64's Wine guide documents the x86-64 WOW64 build as capable of running both 32-bit and 64-bit Windows programs without Box32; Wine 11.0 marks the new WoW64 mode fully supported and uses one `wine` loader rather than separate `wine64` binaries. See [Box64 Wine guidance](https://github.com/deepin-community/box64/blob/master/docs/WINE.md) and the [Wine 11.0 release announcement](https://list.winehq.org/hyperkitty/list/wine-announce%40list.winehq.org/thread/MXULWEMXQ5C24UEJ2STHPIUHNOZ7AZPQ/).

The Python packager validates these headers before creating a ZIP. The Android inventory repeats the header checks after extraction. They reject architecture mismatches but still do not execute binaries or verify runtime linking.

The ELF audit is strict for the Wine launcher, prefix initializer, core Wine modules and all guest libraries. Three optional integrations in the pinned Kron4ek archive are warnings rather than hard build blockers: SANE scanner access, WinPcap packet capture and the DirectShow DMO module built against the older FFmpeg 4.4 ABI. Ordinary Wine startup and the normal multimedia path remain audited; those optional host integrations are not claimed as available in this runtime package.

## Host/guest path mapping

| Host path | Guest path | Purpose |
|---|---|---|
| filesDir/games/<id> | /games/<id> | Imported game directory |
| filesDir/prefixes/<id> | /prefixes/<id> | Per-game Wine prefix directory |
| filesDir/wine-home/<id> | /home/ramen | Per-game home/configuration |
| cacheDir/wine-tmp/<id> | /tmp | Per-game temporary files |
| Wawona runtime directory | /run/user/<Android UID> | Wayland socket directory |

The command builder prepares private directories and mount targets. Before the selected EXE starts, WinePrefixManager runs the packaged x86-64 wineboot.exe through the same Box64/Wine loader using -i for a new prefix or -u for an existing prefix that needs an update. If an interrupted first initialization left only some registry files, the next launch retries with -u rather than permanently rejecting the prefix; existing files are not deleted by this recovery path. If all three registry hives (system.reg, user.reg and userdef.reg) were written but the process failed during shutdown before the marker was saved, the next launch restores the marker and proceeds without rerunning wineboot. A present marker tied to a different runtime version or locale still triggers a prefix update.

## Command and environment

The builder requires:
- a valid imported-game UUID and relative directory games/<id>;
- a safe selected EXE path contained in the imported game directory;
- host/bin/proot and host/lib;
- rootfs/usr/bin/env, used to create a clean guest environment;
- the ARM64 glibc and x86-64 library directories expected by the guest environment;
- rootfs/usr/bin/box64 and rootfs/usr/bin/wine, both executable;
- wineboot.exe in the package's x86_64-windows Wine PE directory;
- an app-private Wayland runtime directory containing the selected socket;
- a socket path below the Android AF_UNIX sun_path limit.

The argument vector includes PRoot's --link2symlink and --sysvipc options, the rootfs, binds for /dev, /proc, and the host CPU sysfs subtree at /sys/devices/system/cpu, per-game directories, the Wayland runtime directory and the guest working directory. The CPU sysfs bind allows guest lscpu and Box64's CPU detection to read the host CPU topology rather than failing because the rootfs's /sys is intentionally not populated. The guest working directory is the parent directory of the selected EXE; when the EXE is at the imported game root, that root is used. This preserves relative-resource lookup for games whose launcher lives in a subfolder. PRoot then starts the guest /usr/bin/env, which removes host-only variables, applies the guest environment and starts /usr/bin/box64 /usr/bin/wine <game-exe> without a shell.

The two environments are deliberately separate:

- **Android host / PRoot:** LD_LIBRARY_PATH points to backend/host/lib so the Android/Bionic PRoot executable can resolve its host libraries. PROOT_TMP_DIR points to the app-private PRoot temporary directory.
- **Linux guest / Box64 / Wine:** HOME, WINEPREFIX, WINEDEBUG, WAYLAND_DISPLAY, XDG_RUNTIME_DIR, TMPDIR, LANG, LC_ALL and PATH are guest paths. LD_LIBRARY_PATH points to ARM64 glibc directories inside the rootfs; BOX64_LD_LIBRARY_PATH starts with the Wine build's actual `/opt/kron4ek-wine/x86_64-unix` module directory, then adds compatible Wine and system x86-64 library directories. BOX64_NOBANNER is set to 1.

This split matters: Box64 uses BOX64_LD_LIBRARY_PATH to find x86-64 shared libraries, while ordinary LD_LIBRARY_PATH is used for the guest's native ARM64/glibc libraries. The Android host library path must not be reused as Wine's guest library path. See the [Box64 environment-variable reference](https://github.com/ptitSeb/box64/blob/main/docs/USAGE.md) and [PRoot-Distro guest-environment documentation](https://github.com/termux/proot-distro#guest-environment) for the corresponding environment model.

Wine prefix setup and game launch use separate PRoot processes but the same per-game bind mounts and guest environment. The initialization command replaces the game EXE with the packaged wineboot PE executable and passes -i or -u through the Wine loader.

The command does not use PRoot fake-root mode. The guest process inherits the app's ordinary Android UID and sandbox.

## Display precondition

The host socket is supplied by a display backend. The builder only checks the socket path before constructing the command; it does not start or verify the compositor protocol handshake. The planned Wawona Android runtime directory is <cacheDir>/wawona-runtime, subject to the actual socket name published by the integrated backend.

Without an in-process compositor and a reachable Wayland socket, a launch command cannot be built. The Compose per-game flow is wired through GameSessionCoordinator, but the native Wawona bundle is generated locally and the full launch path is not yet validated on a device.

## Process supervisor

ProotWineProcessSupervisor starts PRoot directly with ProcessBuilder, merges stdout/stderr, dispatches output and exit callbacks to the main dispatcher, and supports a graceful stop followed by forced stop. One coroutine owns process-exit observation; concurrent stop requests are serialized, and the exit callback is delivered only after a bounded final-output drain. If a Wine descendant keeps the inherited pipe open, the supervisor closes its read end after the drain timeout rather than blocking the game screen forever. The class also provides a run-to-completion path for one-shot setup commands such as wineboot. The current stop grace period is 1.5 seconds, followed by a 1.5-second forced-wait period.

PRoot's `--kill-on-exit` remains the cleanup mechanism for one-shot `wineboot` and `reg.exe` setup commands, so a Wine server can complete or be reused between those short invocations. For a long-running game session, the Android supervisor also samples `/proc` while the PRoot process is alive, remembers descendant PID/start-time pairs, and after PRoot exits sends TERM then KILL only to remembered descendants whose start times still match. This catches escaped Wine/game children without killing a different process after PID reuse. Physical-device verification is still needed for Android's per-device `/proc` visibility and PRoot's actual tracing behavior.

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

## Runtime symlink rules

Runtime package paths are relative to the backend package. Within \`rootfs/\`, an absolute Linux symlink target such as \`/usr/bin/env\` is interpreted relative to the guest root filesystem and rewritten as a safe relative link during installation. It must remain inside \`rootfs/\`; it cannot cross into the Android-host \`host/\` payload. Absolute symlinks in \`host/\` are rejected because they would address Android's filesystem rather than the app-private runtime package. Relative host links must remain inside \`host/\`.

The package builder and Android installer apply the same domain rules. The post-install inventory independently checks the required PRoot, guest env, Box64, Wine, wineboot and host-library paths. These checks catch a structurally incomplete package; they do not replace native execution and graphics smoke tests.
