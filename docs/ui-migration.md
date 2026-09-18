# UI migration: phase 1

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

Deliberately deferred: CEF, React, JSON wire protocol, library extraction,
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
Renderer/Browser IPC. React UI migration remains stage three.

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
parallel migration preview, not the final React player.

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

Next: React/TypeScript build, typed frontend service, then incremental command
and event migration. Audio, queue and library algorithms remain native.
