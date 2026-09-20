# UI migration: CEF + Vue 3 + TypeScript + Vite

Current checkpoint: stage four adds native commands and event subscriptions.
Stages five and six remain planned. The original native UI and the
framework-free phase-two page remain available independently.

## Stage one: application interface preparation

The C++17 PlayerService facade is transport-independent and contains no HWND,
GDI+, CEF or JSON types. main.cpp provides adapters to the existing MCI and
Core Audio implementation. Existing UI pause/resume, seek and volume controls
now use the facade. No audio decoder or queue algorithm has been replaced.

Commands: Pause, Resume, Seek (milliseconds), SetVolume (0..100), GetState.
Pause and Resume are idempotent. Seek clamps to duration minus one millisecond,
preserving the prior behavior. Errors distinguish not-ready, invalid argument,
backend failure and wrong-thread calls.

Snapshots expose opened/playing, milliseconds, volume and an opaque track ID.
The optional event sink receives stateChanged, trackChanged, positionChanged
and volumeChanged with snapshots. Publication also runs from the existing
native timer, covering async cloud playback and MCI completion. This is a
single-subscriber internal event outlet, not yet a renderer subscription API.
All service access belongs to the application thread; a later CEF transport
must post commands to that thread. The callbacks must not reenter the service.

Deliberately deferred in stage one: CEF, Web UI, JSON wire protocol, library extraction,
queue commands and asynchronous play requests. Their completion/cancellation
contracts must be introduced with the cloud controller extraction; a queued
download must not be reported as successful playback. Existing global state,
UI notices and library lifecycle remain in the legacy adapter in phase 1.

Validation: existing native smoke suite, frame smoke suite and the new
player-interface contract test. Cloud adapter behavior remains unchanged.

## Per-target language isolation

The default build remains the native player. CMake now compiles PlayerService
out-of-line into music_core (C++17), and the existing native implementation
into music_application (C++17). A thin legacy_entry.cpp preserves the original
Windows entry and calls runNativeApplication. This is incremental separation:
audio, cloud and library implementations still reside in the legacy application
translation unit; they have NOT been rewritten or fully extracted into Core.

Both native libraries assert the exact C++17 language mode at compile time.
There is no global CMAKE_CXX_STANDARD setting. SDK configuration is confined
to src/cef, entered only with MUSXI_ENABLE_CEF=ON. cef_bridge is C++20 and
cef_host is a C++20 DLL loaded by the SDK's sandbox-enabled bootstrap executable.
The wrapper also uses C++20. Stage two now connects a local HTML preview and
Renderer/Browser IPC. Stage three adds an opt-in Vue 3 + TypeScript + Vite page.

All SDK includes and links are PRIVATE. The dependency direction is
cef_host -> cef_bridge -> music_application -> music_core. Static archives
retain link-only dependencies for the final executable; these do not propagate
CEF compile requirements backwards into the native libraries.
No native target links to CEF, and disabling CEF does not inspect its directory.
CEF_ROOT must point to a current unpacked Windows SDK; we do not pin an old
Chromium to preserve C++17. Official SDK CMake integration follows:
https://github.com/chromiumembedded/cef-project/blob/master/CMakeLists.txt

PlayerState uses C++17 value types (including std::string). This is an internal
same-toolchain static-library boundary, not a stable cross-compiler DLL ABI.
Build ALL targets using the same MSVC toolset, architecture, configuration and
CRT (/MT in Release, /MTd in Debug). Never mix MinGW archives with MSVC CEF
archives. The MinGW cross-standard test does not certify the MSVC SDK ABI.
No CEF types cross Application/Core. State reads retain the application-thread
restriction. The current host uses a single-threaded CEF Browser UI loop on
the native Application thread. A future multi-threaded CEF loop must marshal
requests back to that thread before calling the service.

Native verification:

```powershell
cmake -S . -B build/native -G "MinGW Makefiles" -DMUSXI_ENABLE_CEF=OFF
cmake --build build/native --target music_core music_application MintPlayer
cmake --build build/native
ctest --test-dir build/native --output-on-failure
```

SDK build:

```powershell
./build-cef.ps1 -CefRoot 'D:/develop/cef_binary_152.0.6+g708dc14+chromium-152.0.7977.83_windows64' -Test
```

Verified locally: independent native library builds with CEF disabled, original
player linkage, playback/interface/frame tests, and a C++20 consumer linked
against both C++17 libraries (4 tests passed). The compile database confirms
-std=c++17 on both native libraries and -std=c++20 on the boundary consumer.
## Stage two: CEF host and read-only IPC

Built against CEF 152.0.6 / Chromium 152.0.7977.83, using Visual Studio 2026
MSVC 19.51 x64. The native and CEF targets share /MT Release runtime.
The generated projects confirm C++17 on music_core/music_application and C++20
on the wrapper/bridge/host. CEF include paths do not appear in native targets.

Run build/cef-msvc/src/cef/Release/MusxiPlayerWeb.exe. It starts the original
native player alongside a CEF preview window. The preview shows the same
Application instance's playback status, progress and volume, refreshed every
500 ms. The existing native-only executable remains available. This is a
parallel migration preview, not the final Vue player.

The SDK bootstrap loads MusxiPlayerWeb.dll with sandbox support enabled.
Child processes enter CefExecuteProcess before starting the native UI/backend.
Renderer CefMessageRouter -> CEF IPC -> Browser CefMessageRouter -> C++17
applicationPlayerState -> PlayerService is the only frontend state access path.
CEF Browser callbacks run on the native main thread, pumped every 10 ms via
optional HostHooks. MCI notifications and native timers keep their old path.
Closing the native window waits for asynchronous CEF browser destruction
before destroying the player and calling CefShutdown. Closing only the preview
leaves the native player open.

Protocol v1 request: {"version":1,"command":"player.getState","params":{}}.
Success: {"version":1,"result":PlayerState}. CEF router query IDs correlate
requests and replies. Errors: 400 invalid JSON/envelope/params, 403 untrusted
frame, 404 unknown command, 500 application error. JS times out and cancels
requests after 5 seconds. Type definitions live in frontend/phase2/native.d.ts;
transport is centralized in native.js. No playback commands or subscriptions
are exposed yet; polling is intentionally limited to this stage.

Only the exact local preview main-frame URL can use the Bridge. Other
navigations, frames and popups are rejected; the local page has a restrictive
CSP and no remote scripts. Browser credentials/filesystem/Node backend are
not exposed to JavaScript.

The optional cef-ipc-smoke CTest launches a real sandboxed renderer, validates
state response and rejection of unknown command/invalid parameters, then
closes both windows and checks process exit. Native cloud startup is disabled
only for this smoke test. Normal startup retains existing account behavior.
Native playback/interface/frame/cross-standard tests also pass under MSVC.
Existing native conversion/shadowing warnings and SDK unused-delay-load linker
warnings remain non-fatal.

Runtime CEF resources are copied by the SDK CMake helpers. Existing
build/services and build/runtime are copied when available for native KuGou
support; prepare them with the existing setup-cloud.ps1 workflow if missing.
No account secrets are copied into frontend assets. CEF cache/logs and all
build outputs remain under ignored build/. No SDK files are vendored.

## Frontend decision and architecture

The phase-two audit found no frontend framework dependencies, components or
framework-specific build configuration. frontend/phase2 contains only HTML,
CSS, JavaScript and a TypeScript declaration file. services/package.json is
the existing Node-based cloud adapter, not a frontend package. The CEF host,
renderer router, browser handler and JSON protocol have no framework coupling.
No phase-two implementation needs replacement for this frontend decision.

Target call direction:

```text
Vue 3 + TypeScript components (Composition API, <script setup lang="ts">)
  -> composable / optional store
  -> Native API Client
  -> CEF Renderer router -> IPC -> CEF Browser bridge (C++20)
  -> Application Service (C++17) -> Core (C++17)
```

CEF knows only trusted Web content and IPC, never Vue components or reactivity.
Components must not access cefQuery, CefV8Context, IPC or native objects.
frontend/src/native/client.ts is the sole application-facing native API
entry point. Low-level transport access stays private to that directory.
Its initial public method is native.player.getState(), mapping the
existing protocol v1 command and response without changing the C++ handler.
types.ts defines command/parameter/result/error types; replies are validated
at runtime as well as at compile time. events.ts is reserved for typed event
subscriptions when native events are actually exposed in stage four.
Do not advertise unsupported playback methods or pretend mock state is native.

Vue owns page, dialog, sidebar, theme, hover and animation state. Playing,
current track, position, duration, volume, queue, library and audio backend
remain native-owned. Composable/store snapshots are UI projections only.
On mount or renderer reload, read a fresh native snapshot. Cancel outstanding
requests and remove timers/listeners on unmount; ignore stale responses after
teardown. A frontend reload must never reset the native queue or playback.
Keep the current 500 ms polling approach until event delivery is implemented.
Pinia is optional only when shared UI state justifies it; start with a simple
composable. No second frontend framework is needed.

Frontend structure (optional directories are deferred until needed):

```text
frontend/
  phase2/                 # retain the independent technical-validation fixture
  src/
    components/           # deferred until multiple components need extraction
    views/                # deferred until multiple views exist
    composables/          # lifecycle and native state projection
    services/             # deferred orchestration, using native/client.ts
    native/
      client.ts
      types.ts
      events.ts           # introduce with stage-four subscriptions
    stores/               # create only if shared state needs it
    App.vue
    main.ts
  index.html
  package.json            # Vue 3, TypeScript, Vite, Vue plugin, vue-tsc
  tsconfig.json
  vite.config.ts
```

## Stage three implementation and remaining acceptance gates

### Stage three: minimal Vue frontend and read-only native client

Create the Vue 3/TypeScript/Vite project beside phase2, with Composition API
and script setup. Add a minimal state view and composable backed by the typed
Native API Client. No full player UI migration or new playback commands yet.
Install only the required frontend dependencies and keep their lockfile in source control.
Add frontend output/dependency ignores when introducing the build.

Configure relative production asset paths and copy the Vite output to the
host's ui directory via an explicit CMake build option/step. Keep phase2 as
the default regression fixture until the Vue path passes validation. Test
production JS modules under the actual CEF local-file origin: Vite dev-server
success does not prove file-origin compatibility. Preserve CSP, exact trusted
main-frame checks and sandboxing. If file-origin module loading requires an
adapter change, scope it to static resource delivery; do not disable browser
security globally. A development server must not implicitly gain native access.

Acceptance: frontend typecheck and production build pass; native and CEF
targets still compile; both phase2 IPC smoke and the Vue production page work;
mount/reload recovers native state; no CEF calls occur in components. Stop for
review before stage four.

### Stage four: native playback commands and events

Expose existing Application/PlayerService operations through versioned typed
commands: pause, resume, seek and volume. Define play/queue asynchronous
completion and cancellation semantics before exposing them; do not report a
queued download as successful playback. Implement native event transport for
stateChanged, trackChanged, positionChanged and volumeChanged, with snapshot
resynchronization and subscription cleanup. Keep all service calls on the
Application thread. Preserve the existing internal single-subscriber event
contract or add explicit fan-out only where needed.

Acceptance: native contract tests and real Renderer IPC tests cover valid and
invalid commands, errors, teardown and reload; UI reflects authoritative native
state; existing playback behavior remains intact. Stop for review.

### Stage five: library, account and player UI migration

Incrementally move views for library/playlists, discovery/search, account,
covers, favourites and song actions to Vue. Expose the necessary native
Application interfaces without rewriting audio, library or queue algorithms.
Keep filesystem, network credentials, database and audio work native-owned;
the frontend invokes typed services only. Add shared stores only as required.
Keep the legacy UI available while each workflow reaches feature parity.

Acceptance: regression checks for migrated workflows, state synchronization,
error/loading states, themes and existing native playback; builds remain
runnable at each increment. Stop for review.

### Stage six: integration, packaging and controlled switchover

Verify window controls, DPI, keyboard, resource deployment, CEF sandbox,
renderer recovery, startup/shutdown and release packaging. Package the Vite
production assets and required CEF resources; keep secrets out of Web assets.
Select the Web UI as the default only after feature parity and acceptance.
Remove legacy UI only after explicit approval; preserve the native core and
the independent CEF-disabled build.

Acceptance: install/run/uninstall validation, native and IPC regression tests,
frontend typecheck/build, reload/crash recovery, and verified C++17/C++20
target isolation. Each stage ends in a compilable, preferably runnable state.

## Change boundaries and risks

The frontend-decision adjustment changed README.md and this plan only. src/cef/*,
frontend/phase2/*, CMakeLists.txt, build-cef.ps1, Application/Core and tests
were unchanged at that checkpoint. There were no unused framework packages or configs to delete.

Stage-three changes are limited to frontend source/configuration,
frontend build/deployment integration and related documentation/tests. Preserve
the working CEF initialization, child-process entry, process routing, message
loop, lifetime and read-only state path. The native facade is still incremental:
most audio/cloud/library code remains in src/main.cpp and must not be described
as already extracted modules.

Key risks are local-file module loading/CSP, packaging stale frontend assets,
renderer reload and event-subscription leaks, application-thread affinity,
asynchronous cloud completion, and accidentally propagating SDK requirements
across targets. Validate these at the relevant stage without widening native
dependencies or changing core language standards.

## Running the stage-three preview

The following notes record stage three. The launch command still applies;
stage four below supersedes the read-only API and polling behavior.

```powershell
./build-cef.ps1 -CefRoot 'D:/develop/cef_binary_152.0.6+g708dc14+chromium-152.0.7977.83_windows64' -Vue -Test
./build/cef-msvc/src/cef/Release/MusxiPlayerWeb.exe --cef-vue
```

Without --cef-vue the host still loads the phase-two fixture. The -Vue switch
installs the locked frontend dependencies and enables MUSXI_BUILD_VUE_UI.
Direct CMake users run npm ci in frontend first, then configure that option ON.
An always-run asset target builds/typechecks and deploys the Vue page to ui-vue;
phase2 continues to deploy to ui. Changes to frontend files do not require a
C++ relink to reach the runtime folder. Node/npm are unnecessary when this
option is OFF or when CEF is disabled.

The production Vite build deliberately emits one classic IIFE script with Vue
bundled and precompiled templates, plus external CSS and a restrictive CSP.
This avoids file-origin module restrictions without changing the Bridge,
allowlisting a dev server or disabling CEF security. The root index.html remains
a normal Vite development entry; the build emits its local-file variant.
Build settings follow https://vite.dev/guide/build and
https://vite.dev/config/build-options ; Vue SFC syntax follows
https://vuejs.org/api/sfc-script-setup .

frontend/src/native/transport.ts is private transport with timeout, abort,
native error propagation and protocol checks. client.ts exposes only
player.getState; types.ts validates every PlayerState field. usePlayerState
polls without overlapping requests and cancels on teardown/pagehide. An error
is shown explicitly; a last successful snapshot may remain visible as stale
data but no fabricated playback state is supplied. There is no Pinia, router,
playback mutation API, library API or event subscription yet.

The cef-vue-smoke test loads the real production bundle, checks Vue-rendered
state, reloads the same page, fetches native state again, compares track/opened/
volume and reports completion through the existing test-only IPC command.
It complements cef-ipc-smoke, which retains the original invalid-command and
invalid-parameter coverage. It does not exercise live cloud playback across a
reload; that needs a selected track in the normal preview.

Frontend checks: npm run build and npm test in frontend. The transport suite
covers malformed responses, missing host, native errors, cancellation, timeout
and late callbacks. TypeScript is pinned to 5.9.3 because the tested vue-tsc
version cannot load the changed compiler entry in TypeScript 7. No C++ language
standards or Core/Application implementation changed for stage three.

Stage-three verification (2026-09-20): frontend production build/typecheck and
7 transport tests passed; MSVC CEF build and all 6 CTests passed, including the
phase-two IPC fixture and Vue production reload smoke. CEF-disabled MinGW
build and all 4 native CTests passed. Generated MSVC projects retain C++17
Core/Application without SDK includes and C++20 Host/Bridge. Existing SDK
unused-delay-load linker warnings remain. This was the stage-three checkpoint.

## Stage four: playback control and authoritative events

The same --cef-vue preview now supports pause, resume, seek and volume.
Components call the composable, which calls native.player methods; only the
native client/transport owns CEF queries. Core source and audio algorithms are
unchanged. Application adds two C++17 facade functions: command dispatch and
the existing single-observer setter. The CEF Browser/UI and Application thread
remain the same thread. No CEF types cross the native boundary.

Protocol v1 commands return an authoritative PlayerState after execution:

| Command | Parameters | Result |
| --- | --- | --- |
| player.getState | {} | PlayerState |
| player.pause | {} | PlayerState |
| player.resume | {} | PlayerState |
| player.seek | {positionMs: unsigned integer} | PlayerState |
| player.setVolume | {volumePercent: integer 0..100} | PlayerState |

Seek retains the native clamp to duration minus one millisecond. Pause/resume
retain native idempotence. Errors are 400 for invalid parameters, 403 for an
untrusted frame, 404 for unsupported commands, 409 for no opened track and
500 for a native failure. A timeout or renderer cancellation cancels delivery,
not a synchronous native operation already executed; commands are never
automatically replayed. The frontend resynchronizes from native state.

player.subscribe uses a persistent router query and empty params. Each message
is {version:1,event,state}; the event is player.stateChanged,
player.trackChanged, player.positionChanged or player.volumeChanged. The first
message carries a complete snapshot. A new/reloaded renderer subscribes again
and gets current state, never resets playback. The Browser fans the existing
single PlayerService observer out to at most 16 subscriptions. CEF cancellation,
navigation, renderer termination and browser close release the router callbacks;
browser close also clears the application observer before its captured host dies.
Observer callbacks serialize provided snapshots only and never reenter Core.

Vue no longer polls every 500 ms. It reads on mount/manual refresh/command
completion and receives ongoing native timer updates through events. An event
generation counter prevents an older pending read from overwriting a newer
event. Failed subscriptions reconnect after 1.5 seconds; teardown cancels reads,
commands, subscriptions and retry timers. Slider drafts are transient UI state;
displayed playback values continue to come from C++. No Pinia is introduced.

Selecting a new track, queue edits and cloud download/play remain deferred to
stage five. Before exposing these, use an explicit native operation ID and
pending/completed/failed/cancelled status. Acceptance of a queued download is
not playback success: completion must mean the selected audio is actually open
and the native playback operation succeeded. New selection supersedes old
pending selection; late completions must check that operation ID. Renderer
reload should query the current operation/state, not enqueue it again. These
are future contract requirements, not currently exposed APIs or rewritten logic.

Tests now cover typed command mapping, event validation/cancellation and late
callbacks; real CEF tests exercise invalid parameters, no-track errors, volume
command/event/UI synchronization, unsubscribe and reload resubscription. The
original phase-two unknown-command test now uses player.unknown because pause
is a supported command. Real MCI playback regression remains in the native suite.
Cloud playback from a Vue-selected track is not claimed or tested in this stage.

Stage-four validation: frontend typecheck/production build passed, 10/10 frontend
tests passed, MSVC CEF build with 6/6 CTests passed, and the CEF-disabled native
build with 4/4 CTests passed. Existing native/SDK warnings remain non-fatal.
No CMake language standards changed and src/player remains unchanged. Stage
five has not started; no Git commit was created as part of this stage.
