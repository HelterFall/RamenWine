# Runtime delivery: managed entirely by RamenWine

**Product decision (2026-11-10):** a player must never be asked to find, download, select or install a Wine/Box64 runtime ZIP. Runtime delivery and lifecycle belong to RamenWine itself.

## Player-facing contract

The intended player flow is:

1. Select a game folder.
2. RamenWine scans it, suggests the main Windows EXE and imports the game into app-private storage.
3. Press **Играть**.
4. If the shared runtime is missing or outdated, RamenWine prepares it itself, shows progress, verifies it and then continues the launch.
5. The game is displayed inside RamenWine's Android game surface.

The player does **not** install Wine, Box64, PRoot, Ubuntu, a helper APK or a separate Windows application. The player does **not** browse for a runtime ZIP. The only user-selected folder is the folder containing the game.

The game surface is not a Windows desktop. There is no Explorer shell, Start menu, taskbar, terminal or general-purpose desktop session. Wine and its internal helper processes run behind the game surface; RamenWine owns the Android UI, touch input, display, audio focus and game-session lifecycle.

## Runtime architecture

The first end-to-end backend remains the selected `glibc-proot-box64` path:

- Android ARM64/Bionic PRoot host and its Android-native dependencies.
- Ubuntu ARM64/glibc guest root filesystem.
- ARM64 Linux Box64 inside that guest.
- x86-64 Linux/glibc Wine 11 unified loader and Wine libraries.
- Wine 11 WoW64 PE trees for both i386 and x86-64 Windows applications.
- Wine Wayland driver connected to RamenWine's app-private Wawona socket.
- Per-game Wine prefix, home/configuration and temporary directories.

This is one internal runtime, not a list of per-engine launchers. RamenWine must not require a known engine name before attempting a Windows executable. Unknown and custom engines go through the same broad Win32/Win64 compatibility path first. Engine detection may select diagnostics or an optional graphics profile; it must not be a hard allow-list.

### Compatibility before aggressive trimming

The runtime is a broad Windows compatibility environment, not a hand-written API subset for five known engines.

Keep the Wine API implementations and supporting components that ordinary Windows applications may call directly or indirectly. Do not delete COM, cryptography, networking, installer, multimedia, font, input or system-library support merely because the first sample game does not use it. A custom engine can depend on an API that is not obvious from its EXE name.

Optimize distribution by removing the **desktop product surface** and build-only material, not by arbitrarily deleting Windows APIs:

- do not ship a Windows desktop shell, Explorer, taskbar, terminal or window manager as the player's interface;
- do not ship compilers, headers, source trees, package-manager caches, test build trees or unrelated Linux desktop apps;
- retain the Wine services and libraries needed by Windows programs;
- keep optional graphics translation layers (for example DXVK/VKD3D or legacy helpers) as separately versioned app-managed components when they are not needed by every title;
- measure package size and memory use after building a complete runtime; do not publish guessed size savings.

Wine's own internal processes and API services are not the same thing as the Windows desktop. Removing the desktop does not mean removing Windows compatibility.

## App-owned provisioning and updates

The runtime package can remain a ZIP internally because it is a convenient transport/archive format. That is a developer/build detail, not a player workflow.

The target Android implementation is an app-owned `RuntimeProvisioner` with this sequence:

1. Read a versioned runtime channel/index from a pinned RamenWine distribution endpoint.
2. Verify the index signature using a public key pinned in the application. Do not trust a manifest merely because it is stored inside the same ZIP.
3. Select the ARM64 backend and compatible release channel for this app/device.
4. Download the runtime package directly into RamenWine's private cache. Support progress, cancellation, retry and resumable transfer where the transport permits it.
5. Verify the downloaded package digest and per-file manifest before activation.
6. Extract to a new staging directory, reject unsafe paths/symlinks and validate ELF/PE architecture and required dependencies.
7. Atomically activate the new version. Keep the previous known-good version until post-install checks pass; roll back on failure.
8. Reuse one shared runtime across all imported games. Create a separate Wine prefix for each game.

The app should request the base runtime automatically when the player first presses **Играть** (or during an explicit first-run preparation step). Once installed, the runtime is local and games can launch without downloading the base package again. A later update is also managed by the app; it must not destroy a working runtime before the replacement has passed validation.

If the device is offline and no runtime is installed, show a clear retryable message that an initial download is required. If a valid runtime is already installed, offline game launches remain available. There is no manual ZIP fallback in the normal player interface.

The distribution endpoint must use immutable versioned artifacts and a signed release index. Do not rely on a mutable `latest` URL as the only integrity control. SHA-256 detects corruption; the pinned signing key authenticates the release metadata.

## Package layout and ownership

The managed installation belongs under app-private storage, for example:

```text
files/runtime/
  current/
    backends/
      glibc-proot-box64/
        runtime.properties
        host/
        rootfs/
  versions/
    <runtime-version>/
      manifest.json
      ...
  prefixes/
    <game-id>/
  wine-home/
    <game-id>/
  game-tmp/
    <game-id>/
```

Exact paths remain governed by `RuntimeLayout` and the launch command builder. The key ownership rules are:

- shared runtime files are installed once and reused by every game;
- per-game Wine prefixes and mutable user state are isolated;
- a game cannot overwrite runtime files or another game's prefix;
- extraction and update happen only in app-private storage;
- no root access or external helper application is required.

## What exists now versus what remains

### Already present in source

- folder import, bounded EXE/PE inspection, main-EXE selection and app-private game library;
- runtime manifest parser, offline ZIP extraction/verification engine, staged activation and rollback;
- runtime inventory and architecture/dependency preflight;
- PRoot/Box64/Wine command construction and per-game prefix initialization orchestration;
- game/display process coordination and Wawona Android surface adapter;
- Python runtime package builder and source-build scripts for PRoot, Ubuntu/glibc, Box64 and Wine.

The offline ZIP installer is an internal installation primitive. It is not proof that a complete runtime artifact exists, and it is not intended to be exposed as a file-picker workflow.

### Not implemented or not proven yet

- the complete PRoot + Ubuntu ARM64 + Box64 + Wine 11 runtime has not yet been built, installed and validated on the phone;
- an in-app network provisioner, signed runtime release index and signed published runtime artifacts do not yet exist;
- the current Play action remains gated while the required runtime files are absent;
- Wine has not yet booted a real prefix on the target phone;
- no real Windows EXE or visual novel has been launched successfully on the target phone;
- Wawona's physical pixel presentation still needs a successful device-side SHM smoke test.

The debug runtime ZIP picker has been removed from the Android UI. The current ZIP error shown by an earlier test came from selecting an archive that was **not** a RamenWine runtime package; players should not repeat that action. The package installer remains internal for controlled development/testing while app-managed provisioning is built.

## Engineering sequence

1. Install the replacement APK from [Actions run #42733393190](https://github.com/HelterFall/RamenWine/actions/runs/42733393190) and verify that the Wawona SHM test window actually renders on the phone. APK-level provenance verification now passes; physical rendering remains the outstanding Wawona check.
2. Build the complete pinned PRoot/Ubuntu/Box64/Wine package and record its actual size, hashes and source provenance.
3. Validate the runtime package with the same strict ELF/PE checks used by the Android installer.
4. Publish an immutable versioned artifact and signed release index.
5. Implement the Android RuntimeProvisioner: download, progress, signature/digest verification, staging, activation, rollback and update.
6. Connect **Играть** to this manager: ensure runtime → ensure per-game prefix → launch selected EXE in the Wawona surface.
7. Validate with original x86 and x86-64 Windows GUI probes, then real visual-novel samples and long-session tests.

## Non-goals

- Do not show a Windows desktop.
- Do not require the player to install another app, open a terminal, browse for a ZIP or understand Wine/Box64/PRoot.
- Do not limit launch attempts to named engines or a curated API allow-list.
- Do not claim universal compatibility merely because the generic Wine path exists. Compatibility claims must be based on tested games, architectures, APIs and devices.
