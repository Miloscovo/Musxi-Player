# Lyrics translations (PR5 / PR5b)

When the platform provides a translation, the lyrics page and the matching
preview show each original line with its translation underneath. **显示翻译**
hides or shows translations everywhere (`localStorage` key
`musxi-lyrics-translation`, default on). Hiding is a display preference only.
Lines without a translation, and songs without one, show the original as before.
AI translation, romanization and word highlighting are out of scope.

## Sources

- NetEase: `tlyric.lyric` from the existing `lyric` module response.
- QQ: `client.lyric.get_lyric(mid, trans=True)` in the pinned 0.8.1 SDK.
- KuGou (PR5b): KRC version-1 `language` metadata, `content` block with
  `type: 1, language: 0` (Chinese translations in verified samples). `type: 0`
  contains romanization and is ignored. Other translation languages/types,
  future versions and ambiguous duplicate translation blocks remain `unknown`.

Services return `translationState`: `available` with raw text, `none` for an
empty/missing translation, or `unknown` when the platform does not report one or
the translation is damaged (oversized, NUL, undecrypted hex). A damaged
translation never rejects the original lyrics.

KuGou keeps the existing hash-scoped search and LRC download for original lyrics.
After the original is visible, its separate translation worker requests KRC with
the internal `translationOnly: true` service flag. Matching previews use
`includeTranslation: true`: LRC and optional KRC downloads use the same selected
lyric ID/access key; damaged or timed-out KRC leaves the LRC preview usable.
These flags do not change the public CEF API. Each service call shares a 13.5 s
deadline across its network steps (native budget 15 s); optional preview KRC
waits at most 4 s within that deadline. Native cancellation reaps the worker
process, including requests still draining after an optional timeout.

KRC downloads bypass the vendor's unbounded decoder (`decode: false`). The
Node adapter checks canonical base64 and the `krc1` header, XOR-decodes the
compressed bytes and uses built-in zlib with a 1 MiB output limit during
inflation. Compressed input is also limited to 1 MiB, with strict UTF-8/NUL
validation. No dependency or vendor source changes are required. The upgrade
installer's service manifest includes the new `krc.cjs` helper.

C++ extracts metadata with strict base64, a 256 KiB decoded metadata limit,
JSON nesting at most 16, at most 16 language blocks, 5,000 original rows and
16 KiB per translated row. A translation block must contain one string per
original KRC row, including blank/credit/untranslated slots. Index mapping happens
before sorting and negative-time clamping; unused slots become placeholders.
Absent metadata, romanization only, or a valid all-empty/copied translation means
`none`; malformed metadata, unknown schemas and row-count mismatches mean
`unknown`. Platforms may change these structures; the verified scope is Chinese
translations in version-1 `content`, not arbitrary future multilingual schemas.

Before adding KRC translations to an LRC cache or user snapshot, C++ requires all
original rows to have the same text (after existing word-tag removal and edge
trimming), equal row counts and corresponding unclamped times within 100 ms.
Translations are then retimed to those verified LRC rows before the existing
matcher, avoiding exchanges between closely spaced rounded timestamps. This is
deliberately conservative: even credit/text edits can leave a translation
`unknown`. The user's original and correction offset are preserved, even if
the new KRC has similar timing but different words. Stored translations use the
existing v2 original/translation fields; full KRC metadata and access keys are
not persisted. Generic local LRC/embedded lyrics still require a trusted platform
binding before automatic translation lookup.

## Alignment (C++)

`parse(original, translation, shiftMs)` parses both texts, each with its own
`[offset:]`, then attaches translated rows to synced original lines by timestamp,
never by line index. Real NetEase and QQ samples share the original timestamps
exactly; a 100 ms window absorbs 2- vs 3-digit fraction rounding. Equal
timestamps pair in file order. Empty rows, QQ's `//` placeholders and credits
copied verbatim are skipped. Plain-text translations are not glued to synced
lyrics. Alignment happens before negative times are clamped to 0, and the user's
correction offset is applied afterwards, so original and translation move
together and highlighting/seeking keep using the original line time.

A translation is accepted only when at least 80% of its rows land on an original
line. Fewer leaves the translation `unknown` so it can be retried. This is a
timing compatibility check, not proof of semantic identity: different lyrics
with identical timing cannot be distinguished by alignment alone.

## Storage and upgrade from v0.4

The online cache and match snapshots are written as version 2 with
`translationState` and the raw `translation`. Version 1 records from v0.4 are
still read; their translation state is `unknown`, not `none`. A stored
translation is re-validated against its original on every read.
Damaged translation fields are discarded independently, keeping a valid original
and user offset. Writes are serialized within the process, and snapshot updates
compare the saved choice under the same lock as dialog saves/resets.

- Online: an `available` translation stays with the 30-day original; `none` is
  rechecked after 7 days. A failed lookup is never written as `none`, and a
  transient translation failure keeps a translation already verified for the same
  original.
- Snapshots: only the translation is added. The user's confirmed original and
  offset are never replaced by newer server text; the translation must align with
  the saved original, otherwise the snapshot is left unchanged.

## Background refresh

Lyrics are never delayed by a translation lookup. When a shown result has an
unknown or stale translation (v0.4 cache, v0.4 snapshot), `library.lyrics`
replies immediately with the original and `translationPending: true`. A separate
future and `cloud::Bridge` fetch the translation for the saved recording
identity. The UI keeps polling quietly and swaps the result in place when it is
ready. Each song is automatically refreshed at most once per run; cancelled jobs
may restart when the song is reopened. **重试获取翻译** explicitly retries a failed
lookup without restarting the player, while keeping the original on screen.
It sends the optional boolean `retryTranslation` with `library.lyrics`; old
`{source,id}` requests remain valid. Responses may also contain
`translationRetryable` for supported local bindings. Switching songs, a new
lookup, logout and shutdown cancel the refresh; a stale result never replaces the
current one, and a failure leaves the shown original untouched.

## Validation

`lyrics-parser` covers alignment, offsets, clamping, duplicates and placeholders.
`lyrics-cache` and `lyrics-match-store` cover v1 compatibility, the acceptance
threshold and invalid states. `lyrics-online` (with Node) runs the background
refresh against the offline fixture for both a v0.4 cache entry and a v0.4
snapshot, including a newer server original that must not replace the user's
choice. Service tests cover NetEase/QQ payloads and damaged translations. Frontend
tests cover reply validation, the refresh loop and the preference.

PR5b adds `lyrics-krc` in Core-only and full builds for raw-order mapping,
blank/duplicate rows, offsets, romanization exclusion, malformed/bounded metadata,
strict original compatibility and close-timestamp retiming. Service tests cover
bounded decompression, candidate identity, optional download fallback and timeout.
The IPC fixture covers new/legacy KuGou online originals, snapshot refresh,
same-time wrong-text rejection, confirmed absence and damaged metadata;
`lyrics-match` covers translated KuGou preview, save and restoration with offsets.
Nine public live samples (Shape of You, Let Her Go, Lemon and alternate/Live
versions) independently passed KRC extraction and LRC compatibility/retiming.
