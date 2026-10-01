# Release readiness — 2026-10-01

Current decision: the publisher confirmed the LGPLv3 compliance route for the audited CEF-embedded FFmpeg portions on 2026-10-01. Matching application source publication and a new 0.2.0 installer are authorized; source ref `source-0.2.0-20261001` is selected separately from the historical `v0.2.0` tag. The source guide now uses fixed public source and build-recipe URLs, with an availability/fallback procedure. Check the final external validation record for publication and installer results. CEF original-build correspondence and complete modified-library relinking remain unconfirmed; this is not unconditional release clearance. The sections below record earlier checks and their status at the time.

No-contact continuation: upstream contact was declined; no message was sent. A [bounded byte-different runtime check](CEF-Source-Verification.md#no-contact-follow-up-runtime-replacement-boundary-2026-10-01) passed with installed executable/DLL basenames and isolated profile paths. The [recipient relink/installation instructions](../docs/release-source-delivery.md#recipient-relink-and-installation-route) now include build targets, compatible runtime/resource replacement and rollback. This check changed only a non-executable overlay, so it is not a modified-FFmpeg/relink test and does not close the source-sufficiency gate.

Latest check: [the fixed official Windows recipe comparison](CEF-Source-Verification.md#official-windows-recipe-comparison-and-narrowed-input-request-2026-10-01) identifies PGO/LTO/symbol/toolchain differences from the local preparation configuration and an accessible, not downloaded PGO object. An unsent request now identifies the exact binary and remaining build inputs. The [source revision inclusion check](../docs/release-source-delivery.md#source-revision-inclusion-check) found 13 selected required files still untracked; the current Git source snapshot is not the final matching source release. No full CEF rebuild is required by this check or performed.

## Application source candidate rebuild check

On 2026-10-01, a local working-tree snapshot was prepared under ignored `build/source-release-check/candidate-20261001-200659/`: `MusxiPlayer-Source-Candidate-0.2.0.zip` and `snapshot-manifest.json`. It contains 124 files selected from the tracked working tree and required new source/license records. ZIP integrity and each file's SHA-256 were checked. The real Git index was unchanged. The three untracked abandoned collector files (`prepare-cef-source.py`, `restore-cef-source.py`, `tests/cef-source-delivery-check.py`) were omitted from the candidate, but retained in the working tree. Downloaded SDKs, node_modules, build outputs and local account state were excluded. This is a local candidate, not a committed tag or published source asset.

The independently extracted source passed frontend dependency installation from its lockfile, TypeScript/Vite production build, 14 frontend tests and 27 service adapter tests. A new CMake/MSVC application build using the existing audited CEF/FFmpeg SDKs also succeeded; all 10 CTests registered in this default configuration passed, including decoder and CEF application/preview/Unicode smoke checks. Audible device checks were not enabled. The generated runtime's FFmpeg/CEF DLLs and copied license/source records passed `verify-ffmpeg.ps1`. CEF loaded the unchanged official binary; Chromium/CEF itself was not compiled. Existing unused-delay-import linker warnings were observed; there were no build errors.

The result validates this application's candidate source/build integration on the current SDK/toolchain, not an offline dependency kit, complete CEF corresponding source, cache-free full native setup or all interactive playback behavior. Snapshot files and a machine-readable local result record remain under the ignored check directory. Source/publication and installer gates above remain pending; no installer, commit, tag, push, source upload or upstream message was produced.

This is a technical evidence record, not an unconditional legal opinion. No commit, push or public upload has been performed. Old installer EXEs do not contain the current playback DLLs or notices.

## Current playback FFmpeg: resolved through a new build

The publisher authorized replacing the BtbN playback DLLs. `build-ffmpeg.ps1` successfully built source commit `a5923073bfd8f25b7300d93af3f8e690174ebd30` using MSVC 19.51.36257, GNU Make 4.4.1 and NASM 2.16.03. These are development tools, not codec libraries included in the application.

- All four DLL license APIs report LGPL version 2.1 or later.
- All four configuration APIs match the configuration in `FFmpeg-build.json`.
- GPL, nonfree, version3 and GPLv3 configuration macros are zero.
- External-library autodetection, networking, video tools and external codec libraries are disabled. Native audio decoding and the application's existing seven-container policy remain in use.
- SDK DLLs, MSVC import libraries, header, source archive, build script and license hashes are recorded. Import libraries were generated by the MSVC linker.
- The only source modification is generated VERSION metadata, recorded in the recipe. No C++ playback implementation or CEF bridge contract changed.
- Native/React build succeeded; all 12 CTest cases passed, including fixture generation, decoder/seek/resampling checks, WASAPI tests and CEF smoke tests.
- The publisher hash gate accepts the new SDK and rejects altered DLLs. It does not restrict users replacing compatible DLLs in an installed application.

The new source bundle is `build/ffmpeg-local/ffmpeg-source-materials-bundle.zip` (about 21 MB). Packaging stages it as `MusxiPlayer-FFmpeg-Source-Materials-0.2.zip`. It contains the complete fixed upstream source ZIP, tested build recipe, config headers, configure compiler checks, build log and original LGPL license. It has no Cargo or external codec dependency snapshots. Exact hash is recorded in `FFmpeg-build.json` and its checksum sidecar.

The former 1 GB BtbN bundle, historical audit, LGPLv3 text and broad external notices are retained for historical releases and the development-only fixture tool. The new runtime does not depend on resolving that build's post-Cargo-update lock or missing crate notices. Do not distribute the old DLLs using the new source bundle.

## Rebuilding the playback SDK

For building Musxi against the audited binary set, a 2.5 MB development SDK archive is prepared at `build/ffmpeg-local/MusxiPlayer-FFmpeg-SDK-0.2.zip`, including the matching DLLs, headers, MSVC import libraries and license. Packaging stages it as an optional developer release asset. It is not yet public. `setup-ffmpeg.ps1 -SdkArchive <ZIP>` verifies the pinned archive hash and extracts into a new SDK directory; it refuses to overwrite an existing SDK. Publish the matching source asset whenever distributing this SDK.

Obtain the exact source ZIP at https://api.github.com/repos/FFmpeg/FFmpeg/zipball/a5923073bfd8f25b7300d93af3f8e690174ebd30. SHA-256: `65474f81cf8a4e2529cca6c25877fca8b6f3340a67ff804a306bbc3940cd46f6`.

```powershell
./build-ffmpeg.ps1 -SourceArchive ./FFmpeg-source.zip `
  -VcVars '<Visual Studio>/VC/Auxiliary/Build/vcvars64.bat' `
  -Bash '<Git for Windows>/bin/bash.exe' `
  -Make '<GNU Make>/make.exe' -NasmDirectory '<NASM directory>'
```

MSYS2 GNU Make may use the MSYS runtime supplied by Git for Windows; the audited local combination was tested. For other environments follow https://ffmpeg.org/platform.html. Toolchain versions and output-prefix strings can change DLL bytes; the current distribution manifest intentionally pins the audited binary set. After a new build, recheck configuration, APIs, source/notice correspondence and tests, then update publisher inventory and source-bundle hashes. Do not remove hash checks to make an unrelated binary pass.

For application tests, provide `-FixtureTool <encoder-capable ffmpeg.exe>` to build.ps1/package.ps1. This tool must support libmp3lame/libvorbis/libopus fixture encoders. It is not installed by the application and is not the production SDK.

## Independent CEF/Chromium obligation: open

The unchanged official CEF `152.0.6+g708dc14` runtime embeds a separate Chromium FFmpeg copy. Its exact source, LGPLv2.1 text, original CREDITS, generated Windows configurations and CEF/Chromium main revisions have been obtained. This is useful evidence but the FFmpeg-only tarball is not a complete source/relink kit for libcef.dll.

Historical per-binary GN arguments are not recovered. Obtaining full source and build/install scripts for the relevant library and work linked with it is the substantive issue. Bit-for-bit reproducibility or a successful local rebuild is not itself stated as a universal license requirement. A rebuild is one practical way to validate the supplied route and to replace unavailable historical inputs with recorded inputs.

The authorized independent workspace `E:\Musxi-CEF-Source` now contains the fixed CEF/Chromium source and synchronized dependencies. All 115 official CEF patches applied successfully; hooks completed and GN generated 32,118 targets. An unused Chrome updater test binary was excluded after Windows security blocked it; it is absent from the libcef dependency graph. Exact revisions, the exclusion patch, toolchain settings and verification limits are recorded in [CEF-Source-Verification.md](CEF-Source-Verification.md). No CEF compilation or runtime replacement has completed. This progress does not recover the historical GN arguments or close the source/relink/public-delivery gate.

## Publication and installer: pending

1. Complete the independent CEF source/relink evidence.
2. Prepare matching Musxi GPL Corresponding Source for the final release revision, including build/install scripts; FFmpeg-only source assets do not cover the application.
3. Rebuild and extract the new installer, verify clean payload, hashes, notices and license files. Do not upload an existing BtbN installer with the new source bundle.
4. Make the matching application and third-party source materials publicly accessible through the selected license distribution method and verify anonymous downloads/checksums. Local files are not public publication.
5. Review the concrete release package before commit/push/upload, consistent with the user's review boundary.

Current status: playback-source/provenance gap resolved by replacement; CEF source/relink and final public release checks remain open.

## Step five: recipient source proposal prepared

The [source-delivery proposal](../docs/release-source-delivery.md) now records real fixed source URLs/hashes, four Windows supplement pins, patches, tool prerequisites and the tested preparation sequence. It replaces the rejected, incomplete custom ZIP/numbered-part instructions. No large new archive is generated. Concrete CEF/Chromium source directions and the installed verification/inventory references were added to THIRD_PARTY_NOTICES.md, which existing installer rules already copy with licenses/.

At step five this was a local reviewable proposal, not public delivery, with no source offer or version election. The later publisher confirmation and implementation are recorded below; original third-party license texts remain preserved. The proposal distinguishes GPLv3 third-party-server source access from the separate LGPLv2.1 library/relink obligations, and identifies later-version compliance as an option requiring review rather than assuming it applies automatically.

Pending at step five: method selection, historical Windows build-input clarification/source sufficiency, fresh acquisition, the final public Musxi revision/materials and a new installer. Method selection and the limited acquisition checks were subsequently completed as recorded below; source/publication checks remain open. Full CEF compilation remains outside the selected plan. Do not describe the current installer or this document as cleared for release.

The 2026-10-01 [official source-delivery investigation](CEF-Source-Verification.md#official-source-delivery-investigation-2026-10-01) found a version-specific Chromium source archive, published SHA-256 and fixed CEF automation support. This may avoid preparing the abandoned large snapshot collection. The archive has not yet been inspected or locally hash-verified, and upstream links alone do not close the public-delivery/license review.

Follow-up: the Spotify source archive's GET returned 403 despite HEAD success. A separate Google official Chromium archive and fixed GitHub CEF archive were fully downloaded and inspected instead; Chromium revision/DEPS and all 11,015 embedded FFmpeg files match the pinned sources. See [source archive verification](CEF-Source-Verification.md#source-archive-verification-follow-up-2026-10-01) for hashes, size and boundaries. The preceding uninspected-archive statement remains true for Spotify, not for the separately verified Google object. Historical Windows GN arguments and the final public-delivery arrangement remain open; no full rebuild or installer generation occurred.

Windows preparation follow-up: [archive preparation validation](CEF-Source-Verification.md#windows-archive-preparation-validation-2026-10-01) passed dependency sync, hooks, official patches and GN generation. The libcef dependency graph matches the Git-based graph exactly. However, Windows symlink extraction failed, and the Google archive needed four fixed Windows source/tool supplements. A declared-but-absent upstream CEF header remains documented. This advances the source recipe but does not justify publishing the archive link alone as a complete Windows source/rebuild solution, or closing final public-delivery/license review.

## Step six: public-source access and license-method review

Fresh anonymous acquisition passed for all four Windows supplement pins and depot_tools. A clean isolated depot_tools Windows Python/CIPD bootstrap passed without copied cached tools; the pinned Siso instance resolves. This closes the previously untested supplement/bootstrap acquisition checks, but not a complete cache-free Chromium tool/resource sync or future availability.

Fresh CEF archive download/hash passed. Chromium range access matches the previously fully verified Google object. Playback source instructions were corrected to the GitHub API zipball endpoint: it matches the existing source hash and expected directory, whereas GitHub's other ZIP variant has identical source files but different packaging. The build recipe and binary/source manifest hashes are unchanged.

The concrete [CEF embedded FFmpeg source-method review](CEF-Source-Method-Review.md) recommends reviewing a later-version LGPL compliance election with GPLv3 network source access, including combined-work/library requirements, preservation of original notices, required license texts and publisher availability responsibility. At step six it contained an unapproved draft. The publisher subsequently confirmed the route; the selected statement is now implemented. Final source/material completeness, public Musxi revision/materials and a new inspected installer remain pending; no written source offer is issued. No full CEF rebuild or publication occurred.

## Step seven: publisher selection implemented

On 2026-10-01 the publisher explicitly confirmed adopting the proposed LGPLv3 compliance route for the audited CEF-embedded FFmpeg portions under their existing LGPL-2.1-or-later grant. This decision is recorded in THIRD_PARTY_NOTICES.md, CEF-Source-Method-Review.md and FFmpeg-build.json. It does not alter upstream grants, other third-party licenses, project LICENSE, playback DLLs or FFmpeg configuration flags.

CEF-FFmpeg-COPYING.LGPLv3 was copied byte-for-byte from the pinned embedded FFmpeg source; SHA-256 `da7eabb7bafdf7d3ae5e9f223aa5bdc1eece45ac569dc21b3b037520b4464768`. Existing FFmpeg-COPYING.GPLv3 matches the same source's GPLv3 text. The verifier now requires the LGPLv3 text and selection record in the repository and checks corresponding staged runtime copies. The text hash is pinned in the existing license-material inventory.

The existing CMake cef_ui_assets target and both Inno scripts already copy licenses recursively; no packaging-rule change was necessary. The focused cef_ui_assets build passed frontend TypeScript/Vite production checks and copied notices/licenses to the existing native runtime. Verification accepted the staged current playback/CEF hashes, new license texts/selection record and existing source/SDK archives. No CEF library compilation or installer was performed.

The existing FFmpeg license test additionally exercises an isolated verifier copy and confirms that removing its LGPLv3 text is rejected, without changing repository or SDK files. Existing changed-DLL and SDK overwrite rejection checks passed too. Publication is still pending: suitable complete corresponding/relink materials, final public Musxi source revision and playback materials, sustained source availability and an inspected new installer. Publisher approval of a license method is not evidence that these conditions are fulfilled.

## Step eight: recipient build-material gaps corrected

The source/relink material audit found and corrected two concrete gaps: an executable preparation recipe existed only locally, and recipient CEF metadata instructions omitted fixed origin/master/full history. The small parameterized preparation script is now in licenses/ so existing CMake/Inno license-copy rules include it. The recipient guide documents exact fixed refs and full-history acquisition, GNU patch/Powershell prerequisites and safe initial-preparation limits. The distribution verifier also checks the helper and pinned .gclient are preserved in the staged runtime.

Fresh anonymous metadata acquisition produced the exact current SDK version string. Recipe replay passed sync/hooks, 115 already-applied patch skips, GN generation and the 59,105-action Ninja dry-run. Wrong depot_tools revision was rejected before changing .gclient; existing license/material rejection tests passed. Details and boundaries are in [CEF-Source-Verification.md](CEF-Source-Verification.md#recipient-recipe-and-cef-metadata-closure-2026-10-01).

Corrected: recipient recipe availability and version-metadata instructions. Still unresolved: corresponding-source/relink sufficiency for the official binary, historical Windows GN inputs, completely fresh full Chromium tool/resource acquisition, actual compilation/modified-library replacement (outside the selected no-full-rebuild plan), final public Musxi revision/materials and ongoing source availability. No installer, commit, push, upstream message or public upload was made; the release gate is not declared closed.
