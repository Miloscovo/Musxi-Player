# Online lyrics

`library.lyrics({source, id})` retains the existing pending/ready response and
line-level lyrics format. The native Application resolves trusted track metadata;
services never receive a caller-selected download URL. Online lyrics use the
actual recording's platform ID (KuGou hash, NetEase numeric ID, or QQ MID), not a
merged title or playlist-row ID. Local tracks use an explicitly saved match, then
sidecar LRC and embedded tags. User-confirmed search for unidentified local audio
is described in [Local lyrics matching](local-lyrics-match.md).

## Request ownership

The Application has one lyrics future and a dedicated `cloud::Bridge` running
`services/lyrics.cjs`. This process lazily creates only the requested platform
adapter. Each request carries that platform's saved session snapshot and recording
metadata; it does not perform login validation, playlist synchronization, or
account mutations. Services return decoded original LRC and optional platform
translations or explicit absence; the C++ lyrics library owns parsing and caching.
[Lyrics translations](lyrics-translation.md) describes NetEase/QQ/KRC alignment,
background refresh and upgrade compatibility. Word highlighting is not requested.

The native network budget is 15 seconds for the whole service call, including QQ
child startup or KuGou's search/download steps. KuGou original LRC and background
KRC translation have independent calls; previews optionally fetch both formats
within the same budget. The UI polls pending results for up to 20 seconds,
shows failures separately from absence, and offers retry. Changing the requested
song, changing the playing song during a lookup, logout, and application shutdown
cancel old work. Generation checks discard stale responses, including A → B → A.
The bridge is reaped after its future completes; its process job also contains QQ
children. Playback and cover bridges are independent.

## Cache

`<profile>/lyrics-cache/` contains versioned JSON with the full recording key,
fetch time, original UTF-8 LRC text, and an explicit absence flag. Filenames use a
compact hash, but reads verify the full key, so a collision cannot return another
song's lyrics. Test and normal profiles remain separate. No session credentials,
access keys, or platform response bodies are cached.

- Positive cache: 30 days; expired positive lyrics remain a fallback if fetching
  fails. The returned origin is `cache`.
- Explicit absence: 24 hours; expired negative entries are not an offline fallback.
- Network, authentication, malformed response, and decode errors are not negative
  entries. Failed cache writes do not prevent displaying fetched lyrics.
- Maximum text size: 1 MiB. The directory is trimmed to 256 entries / 16 MiB on
  successful writes, oldest writes first. Windows replacement uses `MoveFileExW`.
- A completed in-memory reply is reused for at most 60 seconds when requested;
  an error is consumed once so the next user retry starts another lookup.

## Validation

Services use mocked platform responses in `lyrics.test.cjs` and
`qq_bridge_test.py`. `lyrics-cache` runs in the Core-only CMake configuration.
The FFmpeg configuration also builds `lyrics-online`, checking offline fallback,
error/absence distinction, cancellation and request generations. When Node is on
PATH at configure time it also tests real pipes and child processes against an
offline fixture, including independent playback requests, timeout, restart and
online-to-cache round trips. No live account is required by those tests.

For a complete runtime, use the existing `setup-cloud.ps1` staging flow before the
CEF build. It copies the new `.cjs` entry point and the added pinned NetEase module
along with the existing Python runtime. Live service availability is validated
separately from deterministic tests.
