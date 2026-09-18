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
to src/cef, entered only with MUSXI_ENABLE_CEF=ON. cef_bridge and cef_host
are optional C++20 static adapter targets, not a finished Web UI executable.
The CEF wrapper also uses C++20. No browser, renderer IPC or React UI is
connected in this adjustment; these remain stage-two work after an SDK build.

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
restriction; a future IPC handler must marshal there before calling the API.

Native verification:

```powershell
cmake -S . -B build/native -G "MinGW Makefiles" -DMUSXI_ENABLE_CEF=OFF
cmake --build build/native --target music_core music_application MintPlayer
cmake --build build/native
ctest --test-dir build/native --output-on-failure
```

Future SDK build (from a matching Visual Studio developer environment):

```powershell
cmake -S . -B build/cef -DMUSXI_ENABLE_CEF=ON -DCEF_ROOT=C:/SDK/cef
cmake --build build/cef --config Release --target music_core music_application cef_bridge cef_host
```

Verified locally: independent native library builds with CEF disabled, original
player linkage, playback/interface/frame tests, and a C++20 consumer linked
against both C++17 libraries (4 tests passed). The compile database confirms
-std=c++17 on both native libraries and -std=c++20 on the boundary consumer.
CEF adapter compilation is NOT verified: this machine has neither a CEF SDK
nor an MSVC toolchain. Static adapter compilation will also not substitute for
the eventual executable link, runtime resource deployment and process tests.
