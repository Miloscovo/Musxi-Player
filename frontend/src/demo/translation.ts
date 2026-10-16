import type { Lyrics, TranslationLookup } from '../native/library.ts';

// Demo translation lookups: each song number shows one case of the native
// contract, and a check (retryTranslation) settles about 1.5 s later.
type Outcome = { translated: boolean; translation: 'available' | 'none' | 'unknown'; lookup: TranslationLookup };
const ok = (translated: boolean, reason?: 'not_provided'): Outcome => ({ translated, translation: translated ? 'available' : 'none',
  lookup: { status: 'succeeded', canRefresh: true, ...(reason ? { reason } : {}) } });
const failed = (translated: boolean, reason: TranslationLookup['reason']): Outcome => ({ translated,
  translation: translated ? 'available' : 'unknown', lookup: { status: 'failed', reason, canRefresh: true } });
// Before and after a check. Odd/even keeps the old demo split of translated songs.
const cases: Record<number, [Outcome, Outcome]> = {
  1: [failed(false, 'network'), ok(true)],
  2: [{ ...ok(true), lookup: { status: 'idle', canRefresh: true } }, ok(true, 'not_provided')],
  3: [ok(false, 'not_provided'), ok(false, 'not_provided')],
  4: [failed(true, 'timeout'), ok(true)],
  5: [failed(false, 'version_mismatch'), failed(false, 'version_mismatch')],
  6: [{ translated: false, translation: 'unknown', lookup: { status: 'pending', canRefresh: true } }, ok(true)],
  7: [failed(false, 'timeout'), failed(false, 'timeout')],
  8: [ok(true), failed(true, 'platform_error')],
};

export function createDemoTranslation() {
  const checked = new Map<string, { at: number; shown: Outcome }>();
  return (lyrics: Omit<Lyrics, 'translation'>, retry: boolean): Lyrics => {
    const n = Number(lyrics.id.replace(/\D/g, ''));
    const strip = { ...lyrics, lines: lyrics.lines.map(({ translation, ...line }) => { void translation; return line; }) };
    if (lyrics.id.startsWith('local:') || lyrics.kind !== 'synced') return { ...strip, translation: 'none', translationRetryable: false,
      translationLookup: { status: 'unsupported', reason: lyrics.kind === 'none' ? 'no_lyrics' : lyrics.kind === 'plain' ? 'not_synced' : 'not_bound', canRefresh: false } };
    const [before, after] = cases[n] || cases[3];
    const now = Date.now();
    let check = checked.get(lyrics.id);
    // Finish an elapsed check even if no poll observed its completion before
    // another click. The next check keeps that result on screen.
    if (check && check.at <= now) check.shown = after;
    // Repeated checks while one runs are merged, as in the Core.
    if (retry && (!check || check.at <= now)) {
      check = { at: now + 1500, shown: check?.shown ?? before };
      checked.set(lyrics.id, check);
    }
    // Song 6 starts pending and settles on its own, like a background lookup on first open.
    if (n === 6 && !check) {
      check = { at: now + 3000, shown: before };
      checked.set(lyrics.id, check);
    }
    const pending = !!check && check.at > now;
    const shown = check?.shown ?? before;
    const reply: Lyrics = { ...(shown.translated ? lyrics : strip), translation: shown.translation, translationRetryable: true,
      translationLookup: pending ? { status: 'pending', canRefresh: true } : shown.lookup };
    if (pending) reply.translationPending = true;
    return reply;
  };
}
