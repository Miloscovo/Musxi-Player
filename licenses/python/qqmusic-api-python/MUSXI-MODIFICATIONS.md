# Musxi compatibility change (2026-10-06)

Upstream: qqmusic-api-python 0.8.1, QQMusicApi commit
`3fc57f02e4eddb68a069bba4e0e12a7893f2a814`, GPL-3.0-or-later.
Original license and copyright materials remain unchanged.

Modified file: `qqmusic_api/core/response.py`, function `snapshot_payload`.
Replace the name-based CookieJar loop with `dict(response.cookies.items())`.
Niquests raises KeyError when indexing an existing empty-value cookie;
QQ's QR confirmation response can contain such cookies. Pair iteration
preserves empty values and does not modify the transport's domain/path jar.

The exact, guarded patch recipe is in `services/setup_python_runtime.py`.
Run `setup-cloud.ps1` to obtain the hash-verified original wheel and apply
the patch. The runtime includes the full modified Python source. Regression
check: `services/qq_bridge_test.py`, CookieSnapshotTests.
