# Android audio backend research

## Current state

The Debian guest already contains ALSA, PulseAudio and OpenAL client libraries,
but RamenWine does not currently provide an Android audio server or an Android
AAudio/Oboe bridge. Installing a client library alone does not produce sound on
the phone.

Do not add a home-grown mixer or a second general-purpose audio server unless
the upstream options below prove unusable.

## Reusable upstream candidate: DirectAudio

Project: https://github.com/The412Banner/directaudio  
License: LGPL-2.1-or-later (the source and license notices must accompany any
distributed derivative).

DirectAudio is an existing Wine `mmdevapi` audio driver. Its Linux/PRoot relay
design separates the two C library environments:

- A small ARM64 Android/Bionic helper owns the Android AAudio output stream.
- The Wine driver runs inside the glibc guest and sends mixed PCM to that helper
  through a Unix socket plus shared-memory rings.
- The helper is started by the Android app under the app's UID, before Wine
  starts, and is stopped with the game session.
- The relay socket can live in the runtime directory that RamenWine already
  bind-mounts into the guest as `XDG_RUNTIME_DIR`.

The upstream project supplies the relay helper and a relay-mode driver source.
This is a much better starting point than writing an audio engine ourselves.

## Why the published driver cannot be copied into RamenWine

RamenWine executes an x86-64 glibc Wine loader through Box64. The currently
published DirectAudio Linux relay driver artifacts are built for an ARM64 Wine
guest: they contain an AArch64 Unix library and ARM64EC/i386 PE driver pieces.
They are not loadable as the x86-64 Wine driver pair in our guest.

The upstream Linux workflow also pins specific Wine `mmdevapi/unixlib.h`
interfaces. That internal table is not a stable public ABI: the project itself
documents that a mismatched driver can result in silence rather than a clean
error. RamenWine uses the LUG Wine/TkG 11.20-2 Wayland build, so the matching
header and the exact source revision used for that build must be verified before
building or bundling a driver.

## Safe integration sequence

1. Identify the exact Wine source revision and hash its
   `dlls/mmdevapi/unixlib.h`.
2. Build the existing DirectAudio relay-mode Unix library for x86-64 glibc and
   its matching x86/x64 PE driver modules against that exact Wine source tree.
3. Verify ELF/PE architecture, direct dependencies, exported/imported symbols and
   the mmdevapi ABI identity in CI.
4. Bundle the ARM64 Bionic relay helper and the ABI-matched x86-64 Wine driver
   files into the app's offline runtime package, including LGPL source/license
   notices and provenance.
5. Start the helper as the Android app process, set
   `BANNER_AUDIO_DIRECT_RELAY` and `WINEDLLPATH`, then set the per-game Wine
   prefix's `HKCU\\Software\\Wine\\Drivers\\Audio` value to
   `directaudio` before launching the EXE.
6. Stop the helper when that game session ends, including launch failures and
   Android Surface teardown.

The above is a build/integration plan, not a claim that audio currently works.
The driver must remain disabled until the exact LUG Wine ABI has been matched
and a real Windows audio smoke test has played sound through the Android device.

## Upstream references

- DirectAudio project and its Wine compatibility notes:
  https://github.com/The412Banner/directaudio
- DirectAudio Linux relay installation and host lifecycle:
  https://github.com/The412Banner/directaudio/blob/main/docs/linux-relay/INSTALL.md
- DirectAudio driver's Wine integration notes:
  https://github.com/The412Banner/directaudio/blob/main/INTEGRATION.md
