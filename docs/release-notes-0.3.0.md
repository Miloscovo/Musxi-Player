# Musxi Player 0.3.0

Windows x64 installer: **MusxiPlayer-Setup-0.3.0.exe**.

## What's new

- Import local music folders recursively, browse each folder as a playlist, and access all local songs in one place.
- Manage the playback queue with play, remove, clear, and play-next actions. Removing the current song or clearing the queue keeps it playing.
- Play a single song without enqueueing its entire playlist, or use the playlist Play button to enqueue the full playlist.
- Switch between sequential playback, shuffle, and repeat-one; select available online audio qualities while preserving playback position.
- Restore the current song, playback position, queue, playback mode, and quality after restarting. Playback remains paused until resumed.
- Minimize to the system tray while music continues playing; show/hide or exit from the tray menu.
- Updated shadcn/ui controls, a fully collapsible sidebar, themed menus and notifications, and refined playlist/player layouts.

## Fixes

- Fixed closing-window freezes and unwanted browser context menus.
- Corrected online quality detection, menu sizing, dark-theme hover colors, and playback-cache retention on restart.
- Removed separators between song rows.

## Source and licenses

Application source is available through this Release's source downloads and the
`v0.3.0` tag. The installer also includes the exact Git revision in
`release-source-record.json`, the project license, third-party notices, CEF
license, Chromium credits, and FFmpeg license/source information.

CEF and both FFmpeg binary sets are unchanged. Their corresponding sources,
Musxi modification recipe, and required build information remain available
through the [fixed public FFmpeg source guide](https://github.com/Miloscovo/Musxi-Player/blob/efd72195e2202325c8a8af8b79068f63ccd1cd18/docs/release-source-delivery.md).

Local songs use their original quality and cannot be added to online favorites
or online playlists. Online playback and quality availability depend on the
signed-in account and service availability.
