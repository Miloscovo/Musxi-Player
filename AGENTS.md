# Development guidance for Musxi Player

## Project shape

- The UI is React + TypeScript, built with Vite from `frontend/`. Keep UI work in the existing React components, hooks, native API client, and CSS unless the task requires a wider change.
- The UI runs inside CEF and calls the native application through the existing CEF bridge. C++ owns playback and library state. Treat the bridge method names, payloads, and behavior as compatibility contracts.
- The native core and application use C++17; the CEF host uses C++20. The project targets Windows x64 with MSVC.
- Some CMake and script names still say `Vue` for compatibility. The actual frontend is React; do not reintroduce Vue or rename compatibility settings as unrelated cleanup.
- Song rows use `@tanstack/react-virtual`. Keep virtualization limited to the song list; other collections are ordinary React lists.

## Change boundaries

- First trace the affected UI or native flow and inspect its callers before editing. Make the smallest change that fully fixes the requested behavior.
- Keep UI-only requests in `frontend/`. Do not change C++, audio/FFmpeg/WASAPI, CEF host code, or bridge contracts to achieve a visual result.
- For native or bridge work, preserve existing public interfaces and behavior unless the task explicitly requires a change. Explain any necessary compatibility change before implementing it.
- Avoid unrelated refactors, dependency additions, generated-file edits, project-structure changes, and packaging or installation work unless requested.
- Preserve unrelated working-tree changes. Do not reset, revert, or overwrite user changes outside the task scope.
- Keep theme transparency on background colors only. Do not lower the opacity of containers that hold text, controls, or album art.
- Use the shared `--hover-surface` token for hover backgrounds. Dark and translucent dark themes use a translucent white highlight, not green. Preserve controls that intentionally have no hover background.
- Do not log credentials, session data, or private service responses.

## Frontend and CEF constraints

- Use the existing React/TypeScript patterns and CSS. Keep player and library state sourced through the existing native API client rather than duplicating native state in the UI.
- Preserve the current Vite output expected by CEF: relative asset paths, the local-file content security policy, and the classic `app.js` bundle. Check `frontend/vite.config.ts` before changing build behavior.
- Keep CEF bridge calls behind `frontend/src/native/`; do not call native APIs directly from unrelated components.
- Preserve song row height, interactions, and virtual-scroll measurements when changing song-list rendering or styles.

## Build and verification

- Frontend type-check and production build: run `npm run build` from `frontend/`.
- Frontend tests: run `npm test` from `frontend/` when the change affects tested frontend behavior.
- The Windows CEF build requires the configured CEF and FFmpeg SDKs. `build.ps1` accepts `-CefRoot` and `-FfmpegRoot`; it builds the frontend and native targets. `-Test` also runs CTest.
- When the existing build tree is configured, a focused native rebuild can use `cmake --build build/cef-msvc --config Release --target cef_host`; relevant CTest cases can be selected from that build tree.
- Choose checks that cover the changed behavior. Do not build installers or publish artifacts unless asked.

## Third-party license policy

When adding a new production dependency:

- Check its license before adding it; do not assume a license from memory.
- Preserve required copyright, `LICENSE`, `COPYING`, and `NOTICE` files.
- Update `THIRD_PARTY_NOTICES.md` when necessary.
- Add required license texts to `licenses/`.
- Distinguish development-only dependencies from dependencies distributed with the application.
- Flag GPL, AGPL, SSPL, nonfree, proprietary, or unclear licenses for manual review before adding the dependency.
- Do not change the project's own `LICENSE` unless explicitly requested.

## AI collaboration attribution for future commits

- For new commits containing development work actually assisted by OpenAI Codex, explicitly record that AI contribution in the commit message.
- Preserve the actual developer's Git Author identity. Do not rewrite existing commit history or apply this policy retroactively.
- Prefer a standard `Co-authored-by` trailer only when a Codex-associated email address has been verified. Do not invent or use unverified OpenAI email addresses or GitHub identities, and do not present Codex as an independent human contributor.
- If no verified Codex-associated email is available, add the exact line `AI-assisted-by: OpenAI Codex` to the commit body.
