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
