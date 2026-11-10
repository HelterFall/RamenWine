# Android audio backend

## Implemented integration

RamenWine uses the existing **DirectAudio** Wine driver and its Linux/PRoot
relay mode. It does not implement a new mixer and does not ask the player to
install a PulseAudio server or a separate helper application.

Pinned upstream sources:

- DirectAudio: `The412Banner/directaudio` commit
  `52aedbf8ddd42874a5bbd21df20e1046367f442e`, LGPL-2.1-or-later.
- Wine base: `wine-mirror/wine` commit
  `7298a818b79446bae153b8d5aa27235ab3703bf9` (Wine 11.20).
- ABI assertion: the Wine 11.20 `dlls/mmdevapi/unixlib.h` is the system-thread
  interface expected by DirectAudio. CI checks its hash prefix
  `0de9fde40d091d82`, verifies `create_unix_thread` is present and verifies
  the incompatible `timer_loop_params` interface is absent.

## Audio path

```
Windows visual novel (WASAPI / DirectSound via Wine mmdevapi)
   -> x86-64 Wine DirectAudio driver (glibc, inside Debian/PRoot/Box64)
   -> Unix socket + shared-memory audio ring
   -> directaudio-relay (ARM64 Android/Bionic, app UID)
   -> Android AAudio
   -> Android audio mixer / speaker
```

The relay protocol uses only fixed-width 32-bit fields and float PCM, with no pointers in its shared wire structures. CI compiles protocol-layout static assertions for both x86-64 and Android ARM64 targets, checking little-endian operation and matching structure sizes/PCM offsets before producing the artifacts.

The Wine-side driver is built for x86-64 glibc and includes both PE modules:
`x86_64-windows/winedirectaudio.drv` and
`i386-windows/winedirectaudio.drv`. The ARM64 helper is built separately
with Android NDK r27d / API 28 and 16 KB maximum page alignment. The Linux
guest never loads Android's Bionic `libaaudio.so`.

At game launch, RamenWine starts the helper in its own Android process UID,
waits for its Unix socket, passes `WINEDLLPATH` and
`BANNER_AUDIO_DIRECT_RELAY` to the guest, and selects Wine's
`HKCU\\Software\\Wine\\Drivers\\Audio=directaudio` registry setting
inside the per-game prefix. The helper stops when the game/display session ends,
including a failed launch.

The helper's microphone endpoint is not enabled. Output audio requires no
microphone permission.

## Runtime packaging and licensing

The DirectAudio job emits an artifact consumed by the normal offline runtime
packager. The Android player does not download a runtime, driver or helper
separately. The package includes the relevant upstream source, LGPL-2.1-or-later
license text, AUTHORS, NOTICE and a build-provenance notice under the app-private
runtime's `host/share/ramenwine/directaudio` directory.

The runtime packager checks:

- the relay is an executable AArch64 ELF;
- the Wine Unix library is an x86-64 ELF and does not link to Bionic AAudio;
- the 64-bit PE driver is AMD64 PE32+;
- the 32-bit PE driver is i386 PE32.

## What is and is not proven

The build pipeline and launch wiring are implemented in source. A successful
CI build proves that the binaries compile against the pinned Wine ABI and pass
the package's static architecture checks. It does **not** prove actual playback
on a phone. A real Windows audio smoke test is still required to validate the
full route, then music, voice and sound effects should be tested separately.

## Upstream references

- DirectAudio project: https://github.com/The412Banner/directaudio
- DirectAudio Linux relay design and runtime lifecycle:
  https://github.com/The412Banner/directaudio/blob/main/docs/linux-relay/INSTALL.md
- DirectAudio integration requirements and driver selection:
  https://github.com/The412Banner/directaudio/blob/main/INTEGRATION.md
- Wine 11.20 ABI source:
  https://github.com/wine-mirror/wine/blob/7298a818b79446bae153b8d5aa27235ab3703bf9/dlls/mmdevapi/unixlib.h
