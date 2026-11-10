# Per-game language and text locale

RamenWine stores a locale profile separately for every imported game. The profile is
applied to the Linux/Wine process as both `LANG` and `LC_ALL`; it does not translate
the game itself or change the language of its assets.

The default is `ja_JP.UTF-8`, suitable as a starting profile for Japanese visual
novels. The library menu also offers English (United States), Russian, Simplified
Chinese, Traditional Chinese, Korean and a neutral `C.UTF-8` profile.

The bundled Debian rootfs generates the corresponding UTF-8 locales and includes
Debian's Noto CJK font package. That gives Wine and applications a real Japanese,
Chinese and Korean font fallback without asking the player to install fonts or
download a language pack.

Existing library records that predate this setting are read with the Japanese
default. Changing a game's profile updates only its library metadata and takes
effect the next time that game is launched; it does not reset its Wine prefix or
save files.

## Important compatibility boundary

A UTF-8 Linux locale and CJK fonts are foundational support, not a guarantee that
every old Windows engine uses the correct legacy code page. Some engines require
specific Windows NLS settings, an engine-specific override or a particular font
inside the Wine prefix. Those cases must be identified with real game tests rather
than inferred from the executable name.
