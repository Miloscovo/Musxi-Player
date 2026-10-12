# Local lyrics matching (PR4)

The lyrics page offers **搜索歌词** / **选择 / 替换歌词** for local audio.
Search NetEase, QQ or KuGou by editable title/artist, compare recording names,
albums and durations, preview with the playing local audio, then explicitly save.
Existing sidecar or embedded lyrics can be replaced. Previewing or closing the
dialog does not save; **恢复自动选择** removes the binding and restores sidecar LRC
then embedded tags. Original audio and LRC files are never rewritten.

## Native ownership and protocol

`library.lyricsMatch` accepts a local catalog `id` and an `action`:

- `begin`: read file identity and title/artist/album tags in the worker; filename
  stem is the title fallback, preserving version markers such as Live. Return a
  metadata suggestion and any saved preview.
- `search`: `session`, `platform`, `keywords`, `page` (1–10); at most 30 candidates
  per page. Services reuse each platform's search without merging recordings.
- `preview`: `session`, opaque candidate `key`; retrieve lyrics for the native
  candidate's trusted recording identity. Neither raw text nor URLs come from UI.
- `adjust`: `session`, integer `offsetMs`; update only the temporary preview.
- `save`: `session`, integer `offsetMs`; persist the selected lyrics and offset.
- `reset` / `cancel`: `session`; remove a binding / discard the dialog session.
- `poll`: `session`, `job`; retrieve a pending operation's result.

Responses carry `status`, local `id`, `session` and `job`. Ready results contain
public candidate metadata, `hasMore`, `selectedName`, platform and nullable lyrics
`preview`. No local file path, credentials or platform response body is exposed.
The frontend validates song, session and job tokens. Errors remain distinct from
an empty search or a candidate without lyrics.

A separate future and `cloud::Bridge` run `services/lyrics.cjs` with a 15-second
network budget. UI polling allows 20 seconds. Playback, normal lyric lookup and
main-library search remain independent. Closing, switching the playing track,
logout and shutdown cancel old work; tokens reject stale polls or saves. A new
session waits until the old worker drains. Save/reset invalidates the normal
in-memory lyric result so the page reloads immediately.

## Binding and offset

`<profile>/lyrics-matches/` stores version-1 JSON snapshots independently of the
evictable online cache. Each contains canonical file path, byte size, modification
time, trusted platform/recording metadata, normalized original UTF-8 text and user
offset. Windows path comparison is case-normalized. Hashed filenames are checked
against the full stored identity, and saves use atomic replacement.

The source order is **explicit match → sidecar LRC → embedded tags**. Catalog ID
changes do not lose a binding; replacing a file with different size/mtime does
not reuse it. Identity is checked on reads and before saves. Moving or renaming
files may require matching again. This is metadata identity, not content hashing;
an external replacement that preserves both size and mtime cannot be detected.
The existing completed lyric reply may remain in memory for up to 60 seconds.

Offset range is ±600 seconds. Positive means later. Parsing always starts from
the saved original text (including any LRC header offset), then applies the user
offset once and clamps negative timestamps to zero. Preview highlighting, line
seeking and saved display use that same timeline. Plain lyrics remain plain.
One overall offset cannot fix edits, speed changes or a different recording.
No automatic batch matching or audio fingerprint recognition is included.

## Tests

`lyrics-match-store` runs in Core-only builds and checks persistence, identity,
replacement, offsets and original text preservation. FFmpeg builds additionally
test metadata and the `lyrics-match` application lifecycle; with Node available,
a real pipe fixture covers search, preview, explicit save, override, restoring
sidecar lyrics, changed catalog IDs and stale sessions. Frontend tests cover
client contracts, polling and the demo workflow. Service tests verify platform
isolation, version preservation and input bounds.

## Planned PR5: platform translations

Add platform-provided original/translation pairs with a display toggle. NetEase
uses `tlyric`; QQ requests `trans=True`; KuGou's translation carrier needs further
investigation. C++ will align translation timestamps to original lyrics rather
than pair by line number. Missing translations leave the original usable.

Cache and explicit-match snapshots should preserve both raw texts from the same
recording, with backward-compatible reading of PR4 snapshots. The user's timing
offset applies to the combined original/translation timeline. AI translation,
romanization and word-by-word highlighting are outside this PR5 proposal.
