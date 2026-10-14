# Desktop lyrics and playback time

The lyrics button at the right of the playback dock opens a separate, topmost
desktop lyrics window. It stays visible when the main player is minimized or
hidden to the tray. Closing it leaves playback and the main player running.

By default the window shows only text on a transparent background: the current
original line and, in dual mode, its translation below. A faint shadow keeps the
glyph edges readable on any wallpaper without an outline.

- **Right click** the lyrics to show or hide the control capsule; it hides by
  itself 2.5 s after the pointer leaves the window.
- **Drag** anywhere on the lyrics to move the window; drag the edges to resize.
  The minimum width is 480 DIP so the complete control capsule stays clickable.
- The capsule holds previous / play-pause / next, translation, font size,
  color, lock and close. Every control has a tooltip. Scroll the wheel over the
  size control (`Aa 30`) to change it in 2 px steps; hover the color swatch for
  the presets (theme color, white, dark).
- **Lock** makes the window click-through. Hovering a locked window shows only
  an unlock button at the top centre; the tray can also unlock it.

## Native window and bridge

The CEF host owns a windowless browser and a
`WS_EX_LAYERED | WS_EX_TOPMOST | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE` HWND that
presents CEF's premultiplied pixels with `UpdateLayeredWindow(ULW_ALPHA)`.
Unlocked pages keep a 2/255 alpha floor so the gaps between glyphs still
receive drag, wheel and right click. A locked window has `WS_EX_TRANSPARENT`;
an 80 ms pointer sample reports hover and clears click-through only over the
unlock button box (88x38 DIP, kept in sync with `.dl-unlock`).

Native owns all state and pushes it to every page (main, tray, desktop) through
`desktopLyrics.subscribe`. Commands take exactly one field and return
`{enabled, visible, locked, dual, panel, hover, fontSize, color}`:

| Command | Parameters |
| --- | --- |
| `desktopLyrics.getState` | `{}` |
| `desktopLyrics.setVisible` | `{ "visible": boolean }` |
| `desktopLyrics.setLocked` | `{ "locked": boolean }` |
| `desktopLyrics.setDual` | `{ "dual": boolean }` |
| `desktopLyrics.setPanel` | `{ "panel": boolean }` |
| `desktopLyrics.setFontSize` | `{ "fontSize": 18..48 }` |
| `desktopLyrics.setColor` | `{ "color": "theme" \| "light" \| "dark" }` |

The desktop page may read player state, library metadata and lyrics of the
playing song, and pause, resume and skip. It cannot browse or edit the library
or request other songs' lyrics. `desktop-lyrics.json` in the profile stores
placement, visibility, lock, dual, font size and color; `window.json` stores
the main window's placement. The default main window size is derived from the
monitor's DPI and work area.

Playback failures advance after three seconds, unless paused, cancelled or set
to repeat-one. Queue edits are tracked by song ID; random playback tries each
failed song at most once per pass. Manual playback replaces the pending skip.

## Playback time

The dock's first row holds elapsed time, the seek bar and total time; click the
total to show the remaining time. Hovering the seek bar shows the time under the
pointer. Times use the native duration/position, support `h:mm:ss` and show
`--:--` when unknown.
