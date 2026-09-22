# Audio backend migration

## A1: MCI isolation

The active backend remains MCI. `IAudioBackend` exposes C++17 load, play,
pause/resume, stop, seek, volume, unload, snapshots and terminal events.
The concrete implementation and all MCI/Core Audio session calls live in
`src/audio/mci_backend.cpp`. Its private message-only window receives MCI
notifications; the application's existing Windows message loop must run.
No HWND, MCI, COM or CEF type is part of the interface.

Application creates the backend once and routes both local files and completed
cloud downloads through `loadAndPlayAudio`. PlayerService still provides its
existing command/event facade. Its snapshot callback reads backend state and
an explicit current audio track ID, not mutable queue indices. The old drawing
variables are refreshed copies only. Moving the complete Player controller out
of the legacy translation unit remains a later step, not an A1 claim.

Stop rewinds and retains the loaded file and duration. Resume then starts from
zero. Unload releases the file, clears position/duration, and retains volume.
Only Application deletes downloaded cache files, after successful unload.
The backend never changes queues, starts downloads, displays messages or
deletes caller-owned files. End/error events carry the load generation and are
consumed on the Application thread by the existing 150 ms timer. MCI resources
are closed on that thread before application COM shutdown.

Pause/resume are idempotent. Seek clamps to duration minus one millisecond and
preserves pause; if resuming after seek fails, the backend stays non-playing.
Query failures produce errors instead of replacing the position with zero.
Notification windows and queued messages are cleared when invalidated by
pause/seek/stop/unload; replacement loads discard prior events. Windows driver
behavior still requires real-device regression beyond the generated WAV tests.

Volume retains the legacy per-process Core Audio session behavior, including
best-effort application during load/play. It is not a new WASAPI renderer.
This limitation is isolated to MCI and is not the volume design for A3.

## Build and verification

`music_audio_mci` is C++17 with PRIVATE winmm/ole32/user32 dependencies.
Core remains independent of concrete audio backends, Windows audio headers and
CEF. Application composes Core and the MCI target. Existing CEF targets retain
C++20. Both CMake and the legacy MinGW build.ps1 include the new source.

`audio-backend-contract` links only the backend and checks Unicode paths,
load without autoplay, pause/resume, stop retention, seek, natural completion,
unload, failed loads and stale completion isolation. Existing native smoke
tests exercise the shared local/cloud playback entry and stable track identity
when the pending queue changes. The Native API and Vue files are unchanged.

## Confirmed next stages

- A2: FFmpeg local-file demux/decode/resampling and seek tests; no network streaming.
- A3: shared-mode event-driven WASAPI, bounded PCM buffer and output clock.
- A4: connect the new backend to Player/Application with asynchronous completion;
  select backend at startup, without automatic fallback.
- A5: run both backends through common behavior tests and real-account acceptance.
- A6: change defaults only after acceptance; remove MCI only after confirmation.

New backend acceptance targets Windows x64/MSVC. MinGW MCI remains supported.
Formats: MP3, FLAC, WAV, AAC/M4A, Ogg Vorbis/Opus, unprotected WMA. Device loss
or default-device changes stop output and prompt replay on the current default.
No EQ, DSP, ReplayGain, gapless, exclusive output or new queue algorithm in A1.
FFmpeg/WASAPI SDK integration, async command completion, and backend selection
are not implemented in this checkpoint.

A1 verification: MSVC/CEF Release build and 10/10 CTests passed; CEF-disabled
MinGW build and 6/6 CTests passed. Vue typecheck/production build also passed.
Audio checks use generated PCM WAV files, including the cloud download-result
adapter; they do not claim a new authenticated cloud playback or device-unplug
acceptance run. No installer or Git commit was produced for A1.
