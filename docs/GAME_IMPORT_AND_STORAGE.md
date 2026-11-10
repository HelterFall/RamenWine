# Game import and storage

## Import

The selected EXE is checked for a valid Windows PE header and supported x86/x86-64 architecture before copying starts. The copied EXE is checked again before the game folder is committed.

RamenWine asks Android's system folder picker for read access to one game folder. It scans for Windows `.exe` candidates, lets the user select the main executable, then copies the selected tree into the app's private storage.

The copy is staged in a temporary `games/.<uuid>.importing` directory. The selected executable is checked again after copying, including its Windows PE header. Only after validation is the staging directory renamed into `games/<uuid>` and added to `library.json`. A failed or cancelled copy is rolled back; a staging directory left behind by process death is removed on the next app startup.

Folder enumeration uses `DocumentsContract` directly rather than `DocumentFile.listFiles()`. The AndroidX helper converts a failed DocumentsProvider query or a null cursor into an empty list, which can look like a genuinely empty directory. RamenWine treats those cases as import errors instead, preventing an apparently successful but incomplete copy.

The scanner and importer use matching safety limits: 32 directory levels and 100,000 entries. A scan that is truncated, contains a duplicate path under Windows case-insensitive naming, an unsupported document type, an unsafe Windows filename, or an unreadable directory blocks the Import button. The app does not silently skip files that might be required by the game. The importer reserves 128 MiB of free internal storage and checks available space while copying. It has no fixed total game-size ceiling; available phone storage is the practical limit.

## Where data is stored

All writable data is under RamenWine's app-private directories. It is not placed in Downloads, Documents, or a shared public Android folder.

| Data | App directory | What it contains |
| --- | --- | --- |
| Imported game | `files/games/<uuid>/` | A private copy of the selected game folder |
| Library index | `files/games/library.json` | Game titles, EXE paths, architecture and import metadata |
| Wine prefix | `files/prefixes/<uuid>/` | Windows C: drive, registry, per-game Wine configuration and most Windows save locations such as AppData/Documents |
| Wine HOME | `files/wine-home/<uuid>/` | Per-game Linux HOME data |
| Wine temporary files | `cache/wine-tmp/<uuid>/` | Temporary files, not intended for persistent saves |
| PRoot temporary files | `cache/proot-tmp/<uuid>/` | Temporary PRoot working files |
| Shared runtime | `files/runtime/current/` | One shared Wine/Box64/PRoot installation used by all imported games |

Some games save beside their executable or inside their own data folder; those writes remain inside that game's private `games/<uuid>` directory. Wine prefix and Wine HOME data are also isolated per game, so ordinary save locations and Windows settings do not mix between games.

## Cleaning up storage

Each library item has a **Delete game and saves** action. It removes that game's imported copy, Wine prefix, Wine HOME and per-game temporary directories. It leaves the shared runtime installed so other games continue to work. The original folder selected in Android's picker is never changed or deleted.

At startup, the app removes abandoned import staging folders, unregistered game directories and orphaned per-game Wine/cache folders. Deletion does not follow symbolic links, so a link inside a game or Wine prefix cannot make cleanup traverse outside the app-owned directory.

For a complete reset, Android's **Settings → Apps → RamenWine → Storage → Clear storage** removes the app's private data, including the shared runtime. Uninstalling the app also removes its private data. Users should not need a file manager or a shared folder to clean up individual games.

## Remaining verification

Unit tests cover storage-size accounting, per-game deletion, orphan cleanup, preservation of the shared runtime and non-following symlink deletion. A successful APK build only verifies packaging and unit tests; it does not prove that every Android document provider behaves correctly or that a Windows game launches on a phone. Real-device import and launch testing remains necessary.
