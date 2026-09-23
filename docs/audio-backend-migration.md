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
WASAPI integration, async command completion, and backend selection remain
future stages. A2 adds the opt-in decoder described below.

A1 verification: MSVC/CEF Release build and 10/10 CTests passed; CEF-disabled
MinGW build and 6/6 CTests passed. Vue typecheck/production build also passed.
Audio checks use generated PCM WAV files, including the cloud download-result
adapter; they do not claim a new authenticated cloud playback or device-unplug
acceptance run. No installer or Git commit was produced for A1.

## A2: independent FFmpeg decoder

`src/audio/ffmpeg_decoder.hpp/.cpp` provides synchronous open/read/seek/close,
source metadata and bounded interleaved float32 PCM blocks. The PImpl boundary
contains no FFmpeg, device, Windows, CEF or UI types. There is no second player
state machine, audio output, worker, queue or new Native API in A2. Existing
MCI, PlayerService, Application, Vue and CEF source files are unchanged.

`music_audio_decoder` is C++17 and links FFmpeg privately. Core/Application do
not link it; disabling `MUSXI_ENABLE_FFMPEG` removes all FFmpeg requirements.
The executable that consumes the decoder is responsible for DLL deployment;
only the offline test executable currently does so. Decoding uses the libraries,
not an ffmpeg.exe subprocess (the executable only generates test fixtures).

### Reproducible build

Windows x64/MSVC, using the installed CMake/Visual Studio generator:

```powershell
./setup-ffmpeg.ps1
$sdk = "$PWD/build/deps/ffmpeg-n9.0.2-3-ga5923073bf-win64-lgpl-shared-9.0"
cmake -S . -B build/audio-a2 -G "Visual Studio 18 2026" -A x64 -DMUSXI_ENABLE_FFMPEG=ON "-DFFMPEG_ROOT=$sdk"
cmake --build build/audio-a2 --config Release --parallel 4
ctest --test-dir build/audio-a2 -C Release --output-on-failure
```

The script explicitly downloads the pinned [BtbN shared LGPL SDK](https://github.com/BtbN/FFmpeg-Builds/releases/tag/autobuild-2026-09-21-13-55),
archive `ffmpeg-n9.0.2-3-ga5923073bf-win64-lgpl-shared-9.0.zip`.
SHA256: `a7e62ca9b34c40145a2c7482f61a78063f6c8f8dbcf17effb27e62841fa6bbd9`.
The archive is checked before extraction; an existing SDK is not overwritten.
`build/deps` is already Git-ignored. CMake never downloads dependencies or
searches PATH for an SDK. Libraries: avformat 63, avcodec 63, avutil 61,
swresample 7. Header/runtime major versions are checked when opening audio.
The SDK reports LGPL v3 or later (`ffmpeg -L`); it is a third-party build,
not an FFmpeg-project binary. Future distribution must retain the applicable
license notices and supply corresponding source/relinking compliance materials.
A2 makes no installer/license-distribution completion claim.

### Contract and limits

- Local regular files only, opened using Unicode Windows paths and custom AVIO.
  Demuxer probing uses content, not filename extension. Only WAV, MP3, FLAC,
  MOV/M4A, AAC, Ogg and ASF containers are admitted; no network/playlist input.
- PCM rate 8–192 kHz, 1–8 channels, optional native speaker mask. Zero mask
  selects FFmpeg's default layout. Reads return up to 65,536 frames; the internal
  converted block is capped at 262,144 frames. This bounds our PCM staging,
  not every internal FFmpeg allocation. Whole files are not retained in memory.
- One owner/thread per decoder; A3 must put it on a decoding worker, never on
  the Vue/CEF UI or WASAPI output thread. No thread safety is promised here.
- PCM starts at frame zero. A seek flushes decoder/resampler state and discards
  preroll to the requested output frame. Seeking to the reported end produces
  EOF; duration can be estimated, especially raw AAC. Unknown duration is -1.
- Near the beginning, reopen instead of seeking to zero so AAC priming/edit
  metadata is preserved. ASF/WMA currently reopens and decodes from the start
  for accurate positioning: seek cost grows with position. A3 must account
  for cancellation/latency before wiring it into user playback.
- EOF drains both decoder and resampler. A final nonempty block precedes the
  EOF block; repeated EOF is stable. Midstream format changes are rejected.
- Failures throw `DecodeError` with operation and native error code; standard
  allocation/filesystem exceptions may also propagate. Decode/seek failures
  release the stream. Invalid read size/negative seek leave a valid stream
  intact. Failed open leaves the decoder closed. A4 maps errors to existing
  player events; the decoder itself never shows dialogs or advances tracks.

### A2 verification

MSVC Release builds Core/Application/MCI and the separate decoder. Eight CTests
pass: the six existing native checks plus fixture generation and decoder checks.
Generated stereo chirps test WAV, MP3, FLAC, AAC/M4A, Vorbis, Opus and WMA;
full PCM/frame counts match FFmpeg CLI reference output. Repeated seeks are
compared with full-decode samples (lossy preroll uses an RMS tolerance).
Additional checks cover mono/5.1 conversion, Unicode/misleading filenames,
EOF/tails, file-handle release, corrupt/missing input and invalid arguments.
These are deterministic generated fixtures, not an exhaustive collection of
real-world encoders, damaged files or authenticated cloud songs.

A3 remains WASAPI shared-mode output and a bounded producer/consumer PCM buffer;
A4 connects commands/state/events and startup backend selection; A5 performs
real-device/account regression; A6 changes defaults only after acceptance.
No WASAPI output, automatic fallback, UI change, installer or commit in A2.

## A3: standalone WASAPI output

`WasapiPlayer` in `src/audio/wasapi_player.hpp/.cpp` connects the A2 decoder to
shared-mode, event-driven WASAPI. It is an independent test pipeline, not yet
an `IAudioBackend` adapter or an Application dependency. Core, MCI, Vue, Native
Bridge and the installed application are unchanged. `music_audio_wasapi` uses
C++17 with private decoder/ole32/uuid links. No Windows/COM types cross its header.

The output thread owns all COM objects, commands, snapshots and terminal events.
One decoder thread owns FFmpeg and feeds a fixed 500 ms circular PCM buffer.
The producer waits when full; the consumer uses a nonblocking mutex attempt,
never waits for decoding and never reads files. A 50 ms wake timeout services
control/health checks even when device callbacks cease. Audio writes follow the
device event and available padding; command/decode wakes can also prefill it.
Default endpoint identity is checked every 250 ms. This is deliberately polling,
not seamless device switching: a change or WASAPI failure stops output and
reports an error; replay opens the then-current default endpoint.

The decoder resamples to the device's mix rate/layout. The renderer accepts
float32 or signed 16/24/32-bit PCM mix formats (including valid-bit alignment),
1–8 channels and 8–192 kHz; other formats fail explicitly. Per-stream WASAPI
volume avoids changing system or other application volume. The 500 ms queue is
a memory bound, not a promise of end-to-end latency.

Public control calls serialize onto the output thread and wait for command
acknowledgment. Source open/decode/seek processing happens asynchronously. Seek
requires source metadata to be ready. A4 must dispatch control calls away from
the UI and map this pipeline's snapshots/errors onto the existing Native API;
this phase does not change the old synchronous `IAudioBackend` contract.

Pause stops the audio clock without discarding queued audio. Stop resets to
zero and retains the path; play resumes/restarts. Seek stops/resets the endpoint,
cancels/joins the previous producer, clears PCM, increments the generation and
starts fresh decoding at the target. Old terminal events are discarded on
seek/stop/load/unload. Device-clock consumption, clamped to submitted media,
drives position; decoding ahead does not move the visible position. EOF is
reported exactly once only after software PCM, endpoint padding and the final
device-clock frames drain. Unload/destruction cancels decoding and joins threads
before releasing their resources. There is no automatic MCI fallback.

Underruns stop/reset output and wait for a small prefill (at least one endpoint
buffer or 50 ms). No fabricated silence advances media time. Refill stalls fail
after five seconds once source metadata is ready. Decode probe/read loops and
custom AVIO now check an optional atomic cancellation flag, including WMA's
linear seek discard. OS-level blocking file reads remain subject to storage
driver responsiveness; this is not a hard real-time cancellation guarantee.

### Build and run A3

Use the A2 SDK/configuration above, then explicitly opt into audible tests:

```powershell
cmake -S . -B build/audio-a2 -DMUSXI_TEST_AUDIO_DEVICE=ON
cmake --build build/audio-a2 --config Release --parallel 4
ctest --test-dir build/audio-a2 -C Release --output-on-failure
# Or run the standalone verifier against the generated fixtures:
./build/audio-a2/Release/audio-output.exe ./build/audio-a2/audio-fixtures
```

The device test plays generated short clips at 10% stream volume. It fails if
no working output device exists rather than silently passing. The default
`MUSXI_TEST_AUDIO_DEVICE=OFF` keeps ordinary CTest runs silent; it still builds
the standalone executable when FFmpeg is enabled. No installer is produced.

### A3 validation and remaining acceptance

The MSVC Release build and all nine CTests passed, including real WASAPI output
on the current default device. Output checks cover all eight fixture formats,
paused clock stability, paused/playing seek, stop retention, volume, drain/end
events, full-buffer cancellation, repeated starts/stops, unloading during a long
WMA seek, errors and subsequent reload. Offline decoder checks additionally
cancel a 170-second WMA seek from another thread and reopen after cancellation.

Automated device success verifies API behavior, not subjective listening quality.
Physical unplug/default-device changes, other device mix formats, deliberately
induced starvation and long listening sessions remain manual/A5 acceptance.
At this checkpoint, the independent pipeline did not yet drive the Vue player;
the following A4 stage adds that integration.
Implementation follows Microsoft's [IAudioClient](https://learn.microsoft.com/en-us/windows/win32/api/audioclient/nn-audioclient-iaudioclient)
and [IAudioClock::GetPosition](https://learn.microsoft.com/en-us/windows/win32/api/audioclient/nf-audioclient-iaudioclock-getposition)
contracts.

## A4: Application and Vue integration

`music_audio_wasapi` now also builds `ffmpeg_backend.cpp`. This adapter
implements the existing C++17 `IAudioBackend` and is selected once at startup.
The native executable defaults to MCI; `--audio-backend=ffmpeg` explicitly
selects FFmpeg. A CEF `--test-app` source build configured with
`MUSXI_ENABLE_FFMPEG=ON` selects FFmpeg by default; `--audio-backend=mci`
chooses MCI. A build without the SDK reports an error if FFmpeg is requested.
There is no silent fallback after a device or decoding failure. Core and
Application remain C++17; CEF targets retain their independent C++20 setting.

Application-thread audio calls change a small desired-state record and return
without waiting for disk probes, decoding, or WASAPI commands. A worker owns
`WasapiPlayer`, publishes snapshots/errors and serializes control commands.
Rapid track replacement and seek use monotonically increasing revisions;
superseded snapshots and terminal events are ignored. Pause/play intent is
preserved through asynchronous seek and download completion. The existing
`PlayerService` sends state events when phase, error, pending, or requested-play
intent changes. State reload through the Native API still obtains the current
C++ snapshot. Vue parses optional phase/error fields for old protocol clients
and displays buffering/seeking/failure with a manual play retry.

Cloud `audio` operations remain pending until the selected track is ready
and actually playing (or ready and paused by explicit user intent). An accepted
load command is not recorded as successful playback. The Application owns
cache deletion: it retires a downloaded file on unload and deletes it only
after the backend reports that no decode thread can still read it. Shutdown
joins the backend before draining retired files. Neither the decoder nor CEF
deletes caller-owned media.

Use `./setup-ffmpeg.ps1` first, then a CEF source build such as:

```powershell
./build-cef.ps1 -CefRoot 'D:/develop/cef_binary_152.0.6+g708dc14+chromium-152.0.7977.83_windows64' -Vue -FfmpegRoot "$PWD/build/deps/ffmpeg-n9.0.2-3-ga5923073bf-win64-lgpl-shared-9.0" -Test
./build/cef-msvc/src/cef/Release/MusxiPlayerWeb.exe --test-app
```

The build copies matching FFmpeg DLLs next to the executable; imported SDK
headers and libraries remain private to audio targets. `build-cef.ps1` without
`-FfmpegRoot` explicitly disables the optional backend. The old MinGW MCI
build continues without an FFmpeg SDK. A4 does not produce or upgrade an
installer; the existing packaging script does not yet opt into the new SDK.
FFmpeg binary redistribution and further real-account/device testing belong
to A5 before creating a new test installer.

A4 validation: MSVC FFmpeg build 10/10 CTests, CEF/Vue build 14/14 CTests,
and the MinGW MCI build passed. The Application test covers paused seek,
last-request-wins track replacement, asynchronous error/retry, cloud completion
and release-before-delete cache handling using generated local media. The
CEF smoke tests validate page load, typed API and state rehydration. These
do not substitute for a real Kugou account, device unplug/replug, listening
assessment, or new installer acceptance. No commit is created for A4.

## A5: test installer and acceptance

The 0.2 test installer is built with the pinned FFmpeg shared SDK enabled.
`package-test.ps1` requires `-FfmpegRoot` so a routine package build cannot
silently revert the test app to MCI. The installer includes the four linked
FFmpeg DLLs, their LGPL v3-or-later license text and the FFmpeg notice in
`THIRD_PARTY_NOTICES.md`. It keeps the existing test-app ID and isolated data
directory, upgrading an installed test version without touching the legacy
native release. The test shortcut starts `--test-app`, which selects FFmpeg;
`--audio-backend=mci` remains available for comparison.

On 2026-09-23, the Windows x64/MSVC CEF build and all 14 tests passed before
packaging. Inno Setup produced `dist/MusxiPlayer-Test-0.2-Setup-x64.exe` with
SHA-256 `63cf8c1eacf3748ffc8c1f751c5d875d6f7a6967eb77dc7681ed443286f248fa`.
The installer completed locally with exit code 0; the installed directory
contains the four FFmpeg DLLs and `LICENSE-FFmpeg.txt`.

The user scanned into the test profile, which synchronized three playlists.
On an account track, they confirmed audible playback, pause/resume, seek,
next-track and volume behavior. Switching the Windows default output while
playing stopped playback and showed the expected prompt; manual replay worked
on the new device and again after switching back. The same account and song
were played through the explicit MCI override, with no obvious difference
reported for the same controls. The running test app was restored to the
default FFmpeg backend afterward. No account credentials or track names are
recorded here.

This acceptance covers the machine's normal/default-device-switch path.
Physical device unplug, protected media, every supported codec on this account,
long listening sessions, and multi-display UI behavior were not exercised.
The legacy native default remains MCI; switching the default is an A6 decision.
