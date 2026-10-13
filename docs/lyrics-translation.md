# Lyrics translations (PR5)

When the platform provides a translation, the lyrics page and the matching
preview show each original line with its translation underneath. **显示翻译**
hides or shows translations everywhere (`localStorage` key
`musxi-lyrics-translation`, default on). Hiding is a display preference only.
Lines without a translation, and songs without one, show the original as before.
KuGou translations, AI translation, romanization and word highlighting are out
of scope.

## Sources

- NetEase: `tlyric.lyric` from the existing `lyric` module response.
- QQ: `client.lyric.get_lyric(mid, trans=True)` in the pinned 0.8.1 SDK.
- KuGou: not requested yet (its translations live in KRC; follow-up PR5b).

Services return `translationState`: `available` with raw text, `none` for an
empty/missing translation, or `unknown` when the platform does not report one or
the translation is damaged (oversized, NUL, undecrypted hex). A damaged
translation never rejects the original lyrics.

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
