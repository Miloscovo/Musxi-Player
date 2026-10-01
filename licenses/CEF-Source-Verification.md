# CEF source and build-project verification — 2026-10-01

This records a source acquisition and GN generation test, not a completed CEF build or recovery of the official binary's historical build arguments. The existing application still uses the official CEF runtime.

## Verified inputs

| Input | Actual revision |
|---|---|
| CEF | `708dc140cbc3286826a8abef89dc23a44ff9ea72` |
| Chromium | `79460ebecaa5625e57a5fb679a735659e73dc687`, tag `152.0.7977.83` |
| Embedded FFmpeg | `2b68d2babae73714846961fb0ee47e3b3d2e39a9` |
| depot_tools used for acquisition | `2da9ee6f6c86332551055bc44245fab94675272e` |
| Source helper | CEF's unmodified `tools/automate/automate-git.py` at the CEF revision above; SHA-256 `fe0c880fd2a91ac3ab4c82301f596295cecc1901e503507e36300a5b58578dcd` |

The independent workspace is `E:\Musxi-CEF-Source`. It contains the CEF and Chromium trees, dependency checkouts, CIPD/GCS inputs, official CEF patches, generated toolchain files and logs. The captured [CEF-source-revisions.json](CEF-source-revisions.json) records 238 dependency entries, including 153 Git repositories. Its SHA-256 is `d9bec19f8ceea894776903d8414c669e5797bf1c21733c72223a380728b4d1eb`. These are actual synchronization results, not versions inferred from component names.

The acquisition helper requires the Chromium checkout argument in tag form here. Passing the raw Chromium commit failed in its tag-name parsing; the resulting checkout was separately verified against the full commit above.

```powershell
python automate-git.py --download-dir=E:\Musxi-CEF-Source --branch=7977 `
  --checkout=708dc140cbc3286826a8abef89dc23a44ff9ea72 `
  --chromium-checkout=refs/tags/152.0.7977.83 `
  --no-chromium-history --no-depot-tools-update --no-build --no-distrib
```

Bootstrap depot_tools using its `bootstrap/win_tools.bat` when required. Set `DEPOT_TOOLS_WIN_TOOLCHAIN=0` to use the installed Microsoft toolchain and `DEPOT_TOOLS_UPDATE=0` to avoid changing the recorded depot_tools revision. The local network needed the system proxy and per-process Git `http.version=HTTP/1.1` and `core.longpaths=true`; no global Git settings were changed.

## One excluded test-only download

Windows security blocked the Chromium updater's `UpdaterSetup_test.exe` download. Security checks were not disabled and the file was not restored. In this independent workspace, only `src/third_party/updater/chrome_win_x86/cipd` was excluded by changing its `DEPS` condition from `checkout_win` to `False`; the exact [CEF-excluded-updater-test.patch](CEF-excluded-updater-test.patch) is retained.

The pinned `third_party/updater/BUILD.gn` uses this binary in `copy("old_updater")`, marked `testonly=true`, for Windows Chrome-branded updater tests. It is absent from the generated `//cef:libcef` dependency graph. This workspace is a CEF rebuild workspace, not a complete environment for all Chrome updater tests. A `custom_deps` override alone did not exclude this CIPD download.

After this exclusion, `gclient sync --nohooks --no-history -j4` and `gclient runhooks` completed successfully. The source remains at the recorded commits with this explicit dependency-filter patch and the official CEF patches applied.

## GN verification

Run `cef/tools/gclient_hook.py` from `chromium/src/cef` using depot_tools on PATH and these environment settings, with paths adjusted for the installed toolchain:

```powershell
$env:DEPOT_TOOLS_WIN_TOOLCHAIN='0'
$env:DEPOT_TOOLS_UPDATE='0'
$env:WIN_CUSTOM_TOOLCHAIN='1'
$env:CEF_VCVARS='none'
$env:GYP_MSVS_OVERRIDE_PATH='<Visual Studio BuildTools directory>'
$env:GYP_MSVS_VERSION='2026'
$env:VS_CRT_ROOT='<Visual Studio>/VC/Redist/MSVC/14.51.36231/x64/Microsoft.VC145.CRT'
$env:SDK_ROOT='C:\Program Files (x86)\Windows Kits\10'
$env:SDK_VERSION='10.0.26100.0'
$env:GN_OUT_CONFIGS='Release_GN_x64'
$env:GN_DEFINES='is_official_build=true symbol_level=0 chrome_pgo_phase=0 use_thin_lto=false proprietary_codecs=false ffmpeg_branding="Chromium"'
python tools/gclient_hook.py
```

The actual installed MSVC toolset was 14.51.36231. All 115 official CEF patches applied with zero failures. GN generated 32,118 targets from 4,952 files successfully. CEF's issue #1999 Ninja adjustment was applied by the official hook. Disabling PGO and thin LTO is a deliberate new validation configuration; it is not a claim about the original official DLL.

The generated `args.gn` SHA-256 is `1ef6c5d29cd1e1929ef64dc6fefa64e5d7aff31679c7bcb002c0de8b4706e0c5`. A successful `gn desc out/Release_GN_x64 //cef:libcef deps --all --format=json` captured 18,509 dependency targets, including `//third_party/ffmpeg:ffmpeg` and its internal/NASM targets. The graph is retained locally as `libcef-deps.json`, SHA-256 `08ed069bf0ff8e86de0329778795defdc16e4f544fbbe6eea2715f1d0e3ea181`. The custom-toolchain environment must also be present for `gn desc`.

## Remaining boundaries

- No CEF compilation, modified-FFmpeg relink test, replacement runtime test or new CEF binary distribution has completed.
- The official DLL's historical per-binary GN arguments remain unavailable. This new recipe is distinct from them.
- Local acquisition and successful GN generation do not make the materials publicly available or constitute a complete legal clearance. Source/build material delivery and corresponding-source sufficiency still require final review.
- The FFmpeg-only archive cannot be described as a full CEF/Chromium source or relink kit. Retain the CEF license, Chromium credits and separate embedded FFmpeg license regardless of source-delivery method.

See the [official CEF build instructions](https://chromiumembedded.github.io/cef/branches_and_building) and [Release-Readiness.md](Release-Readiness.md).

## Official source-delivery investigation (2026-10-01)

Scope: retain the official runtime; no full CEF rebuild, installer generation, publication or upstream contact. The abandoned large snapshot collection is not required by this investigation.

### Verified official download route

The actual HTML/JavaScript of the [official binary download page](https://cef-builds.spotifycdn.com/index.html) constructs both a CEF branch-source link and a version-specific Chromium source link. This is more specific than a repository homepage, but does not by itself establish license compliance for a downstream release.

| Material | Official location and observation |
|---|---|
| Chromium source candidate | https://cef-builds.spotifycdn.com/chromium-152.0.7977.83.tar.xz — anonymous HTTP HEAD returned 200 and Content-Length 1,912,176,748 bytes (about 1.78 GiB). Full archive contents have **not** been downloaded, hashed or inspected in this investigation. |
| Published source checksum | https://cef-builds.spotifycdn.com/chromium-152.0.7977.83.tar.xz.sha256 — anonymous GET returned `74c0190493833685772f1ff91ea6171c389cd301cd0c74cf37cf187464398bfa`. This is the distributor's published checksum, not a locally verified archive hash. The `.sha1` sidecar returned 404. |
| Fixed CEF source | https://bitbucket.org/chromiumembedded/cef/get/708dc140cbc3286826a8abef89dc23a44ff9ea72.tar.bz2 — anonymous HEAD returned 200. The matching Git checkout was already acquired and inspected locally. The download page's `7977` branch archive is mutable; prefer the exact commit instead. |
| Runtime catalog | https://cef-builds.spotifycdn.com/152_windows64.json — anonymous GET lists signed runtime `152.0.6+g708dc14+chromium-152.0.7977.83`, timestamp `2026-09-07T08:20:30.158Z` and archive SHA-1 `b1d66325bd6538fe2dca1035718e12d17cd52f97`. This signed-archive checksum is **not** the libcef.dll checksum or a claim that the local SDK was downloaded from that signed archive. |

The inspected, fixed CEF source contains:

- `CHROMIUM_BUILD_COMPATIBILITY.txt`, selecting `refs/tags/152.0.7977.83`;
- `tools/automate/automate-git.py`, accepting `--chromium-archive` and `--chromium-archive-sha256`, setting `source_tarball`, extracting the archive, applying the official `tarball_deps` / `tarball_gclient` patches and running dependency synchronization;
- `patch/patches/tarball_deps.patch` and `tarball_gclient.patch`, supporting a source tree without ordinary checkout history;
- `tools/gn_args.py`, reading `tarball_args.gn` when present.

See the [fixed official automation script](https://github.com/chromiumembedded/cef/blob/708dc140cbc3286826a8abef89dc23a44ff9ea72/tools/automate/automate-git.py) and [GN argument handling](https://github.com/chromiumembedded/cef/blob/708dc140cbc3286826a8abef89dc23a44ff9ea72/tools/gn_args.py). These establish an upstream-supported source-archive route, **not** that this particular archive contains historical Windows arguments or every dependency. The script can still download further dependencies/tools; it is not an offline build kit.

### License reading and limits

The authoritative text is retained in [CEF-FFmpeg-COPYING.LGPLv2.1](CEF-FFmpeg-COPYING.LGPLv2.1), also available as [LGPL-2.1-or-later](https://spdx.org/licenses/LGPL-2.1-or-later.html). Section 0 includes source modules, interface definitions and compilation/installation scripts. Section 4 addresses distribution of library object code and equivalent source access from the same place. Section 6 provides alternatives for a work linked with the library, including a suitable shared mechanism, a distributor's written offer valid for at least three years, or equivalent online access to the relevant materials. Section 7 addresses combining library facilities. Which provisions cover the embedded FFmpeg/libcef arrangement requires review of that arrangement; the app loading libcef dynamically does not prove FFmpeg is separately replaceable inside libcef.

Interpretation for this project: a huge custom Release attachment and a successful full rebuild are not universally mandated by these clauses. However, upstream URL availability alone does not discharge Musxi's obligations, establish corresponding-source completeness, guarantee future availability, or create a source offer binding the upstream distributor. No release-specific written source offer or original per-binary Windows GN argument record was located in the inspected SDK README/LICENSE, download catalog/page, or fixed automation sources. License boilerplate mentioning a written offer is not an actual offer. This is a bounded search result, not proof that no such material exists elsewhere.

### Remaining checks and proposed upstream questions

Before selecting the smaller official-source route:

1. Download the official Chromium archive once, verify its published SHA-256, and inspect version/revision metadata, embedded FFmpeg, DEPS, original licenses and Windows build/source files against the fixed inventory. Record omissions and archive-specific GN settings. Do not extract over the existing verified checkout.
2. Confirm which additional fixed source dependencies and patches are necessary for libcef/embedded FFmpeg, and distinguish them from downloadable compiler tools. The previous successful Git-based sync/GN generation does not validate the archive-based route.
3. Obtain an authoritative clarification of historical build inputs where needed, and review whether the selected public delivery arrangement satisfies the applicable clauses. If direct upstream references are insufficient, use a verified publisher-controlled source delivery route or an explicitly approved written offer; do not promise delivery before its materials and operating process exist.

Draft for the CEF binary distributor/maintainer (not sent):

> For Windows x64 CEF 152.0.6+g708dc14 / Chromium 152.0.7977.83, does your chromium-152.0.7977.83.tar.xz include the exact source dependencies and modifications used for the distributed libcef.dll, including FFmpeg revision 2b68d2babae73714846961fb0ee47e3b3d2e39a9? Where are the corresponding Windows build/install scripts and GN arguments (including ffmpeg_branding, proprietary_codecs, component linkage, toolchain and PGO/LTO inputs)? Is there a release-specific source offer or documented downstream redistribution/source-access arrangement? How long are the fixed source archive and checksum retained, and what is the fallback if they become unavailable?

**Result:** the official source archive plus fixed CEF source/build scripts is a credible smaller-delivery candidate. The archive itself and downstream delivery sufficiency remain unverified; this investigation does not close the CEF release gate. No runtime, business code, packaging configuration or project LICENSE changed, and no upstream message was sent.

## Source archive verification follow-up (2026-10-01)

This follow-up supersedes the preceding archive-availability observation, not its license limits. Actual GET requests to the Spotify CDN source archive returned **403** (curl and PowerShell, including range/referrer tests), although the previous HEAD and checksum requests succeeded. It is not currently a verified recipient download route from this environment. Do not equate HEAD success with successful source delivery.

### Downloaded and inspected official alternatives

| Input | Verification |
|---|---|
| Google Chromium source | [Official Chromium object](https://commondatastorage.googleapis.com/chromium-browser-official/chromium-152.0.7977.83.tar.xz), 5,916,162,416 bytes (about 5.51 GiB); completely downloaded with resume. |
| Object provenance | [Google object metadata](https://storage.googleapis.com/storage/v1/b/chromium-browser-official/o/chromium-152.0.7977.83.tar.xz), generation `1788469597508994`, created `2026-09-03T21:06:37.606Z`. The published MD5 `bvsq+dx0Ac1KByNsnZwt9Q==` matches downloaded bytes (`6efb2af9dc7401cd4a07236c9d9c2df5`). MD5 here checks object consistency; it is not a claim of cryptographic authenticity. |
| Locally computed Chromium SHA-256 | `8064dd693f3eccb58d00e2f264c582b1e869c2883b28655787fe21e16e538ada`. This is **not** the Spotify checksum; these are different archive objects. |
| Fixed CEF source | [GitHub fixed-commit archive](https://codeload.github.com/chromiumembedded/cef/tar.gz/708dc140cbc3286826a8abef89dc23a44ff9ea72), 3,572,838 bytes; locally computed SHA-256 `69fbd8385da491757a862c7ada70318b691cb8aee4e9ea0499a63baa90cb76e7`. All 1,881 archive files match the fixed CEF Git blob IDs. |

The Chromium archive was fully listed with native tar and scanned with Python's standard-library tarfile/lzma, without extracting the whole tree. It contains 1,144,138 regular files and 1,306,305 total file/directory paths. Inspection established:

- `chrome/VERSION` is exactly `152.0.7977.83`.
- `build/util/LASTCHANGE` identifies `79460ebecaa5625e57a5fb679a735659e73dc687`, branch-heads/7977 position 2323.
- `DEPS` matches the original fixed Chromium commit byte-for-byte and selects FFmpeg `2b68d2babae73714846961fb0ee47e3b3d2e39a9`.
- All **11,015** extracted embedded FFmpeg files match that commit's Git blob IDs, with no differing or missing tracked files. This includes the original licenses, credits, GN files and generated Windows configurations. Git blob comparisons avoid false differences caused by Windows checkout CRLF conversion.
- The extracted Chromium/win/x64 configuration has `CONFIG_GPL`, `CONFIG_NONFREE`, `CONFIG_VERSION3` and `CONFIG_GPLV3` all set to 0. This verifies source configuration, not independently recovered historical DLL GN arguments.
- Every one of the **3,430** distinct dependency target directories in the previous local `//cef:libcef` GN graph is present (excluding CEF itself, supplied separately). Directory coverage does **not** establish that every required file/tool exists or that every third-party file was individually checked.
- No `tarball_args.gn` exists in the archive. The archive does not supply the missing original Windows DLL GN argument record.

All downloaded archives, metadata, the inspector and detailed results remain in `E:\Musxi-CEF-Source`: `official-archive-audit.json`, `official-cef-archive-audit.json`, `official-ffmpeg-archive-audit.json` and `official-archive-directory-audit.json`. Only the embedded FFmpeg subtree was extracted into the separate `official-google-extracted` directory. The earlier synchronized checkout was not overwritten. These local materials are not GitHub Release assets or publicly delivered Musxi sources.

### Consequence for delivery selection

There is now a tested anonymous download route to a version/commit-matched Chromium source archive and exact CEF source, with individually verified embedded FFmpeg sources. This avoids having to *create* the abandoned custom snapshot collection. It does not make the source smaller for recipients, establish that Google and Spotify archives are interchangeable, or automatically satisfy Musxi's distribution obligations.

Still open: archive-based dependency/hooks/GN validation on Windows; original official build-input clarification where necessary; and review/implementation of a durable publisher source-delivery arrangement under the applicable license clauses. The existing Git-based GN validation remains valid evidence but is a different route. No full CEF build, modified-library relink test, installer build, commit, push, binding written offer or upstream message was performed in this step.

## Windows archive preparation validation (2026-10-01)

The Chromium source archive was independently unpacked into `E:\CEF-Archive-Validation\chromium\src`, preserving the earlier Git-based workspace. The fixed CEF checkout and depot_tools were cloned locally without hardlinks; CEF's `origin/7977` reference was retained for version generation. The already installed depot_tools Python/CIPD bootstrap was copied as a build-tool cache. **The CEF tarball alone was not tested as a replacement for the Git metadata used by CEF's version scripts.** Neither workspace is a newly compiled runtime.

### Extraction limitation

Windows bsdtar 3.7.2 returned a nonzero exit status for Unix symbolic links, including Linux sysroot, test and documentation links. A targeted PowerShell symlink creation attempt confirmed that administrator privilege was required in this environment; no elevation or security setting change was performed. The original archive remains intact. This is **not** a successful complete Windows extraction or an offline, all-platform build environment. Successful GN generation below does not erase this limitation.

### Windows-specific missing materials and correction

The version-matched Google archive is not sufficient by itself for this Windows configuration. Checking GN-declared sources/inputs/scripts/data found 38 absent paths even though GN succeeded. Official source-tarball support skips Git source synchronization, so missing Windows Git dependencies need separate fixed checkouts. The following complete repository trees were added from their already verified Git revisions using `git archive`; original licenses/copyright files were retained, and no upstream business code was rewritten:

| Checkout relative to Chromium `src` | Source URL | Fixed revision |
|---|---|---|
| `third_party/openxr/src` | https://chromium.googlesource.com/external/github.com/KhronosGroup/OpenXR-SDK | `75c53b6e853dc12c7b3c771edc9c9c841b15faaa` |
| `third_party/microsoft_dxheaders/src` | https://chromium.googlesource.com/external/github.com/microsoft/DirectX-Headers.git | `62c23d5ec700659453c6fe89d296554b2a5e7edc` |
| `third_party/microsoft_webauthn/src` | https://chromium.googlesource.com/external/github.com/microsoft/webauthn.git | `ef82c157125a0490e05f6ea82a7adb1b8e1bad08` |
| `third_party/gperf` | https://chromium.googlesource.com/chromium/deps/gperf.git | `e9eeea862a18e77b945d98eff7e1bf065d3daf8e` |

These pins come from the same verified dependency inventory/Chromium DEPS, not newer branch tips. A recipient can acquire them as fixed Git checkouts, rather than needing a custom full-CEF Release archive. The local validation used previously acquired sources and cached build tools; it does not prove a completely fresh, cache-free network installation. gperf is a build tool, not an added application runtime dependency.

After supplementation, the 37 missing Windows source/tool paths were resolved. One remaining path, `cef/libcef_dll/ptr_util.h`, is declared in CEF's `cef_paths2.gypi` but absent from the fixed upstream CEF tree and earlier Git workspace too. It was not found in generated Ninja file references or local `libcef_dll` includes. No substitute header was invented. The raw GN declaration check therefore still reports this upstream inconsistency; do not describe it as 99,313 present files or a fully clean source-list audit.

### Commands and results

1. Apply the original `tarball_gclient.patch` to the independent depot_tools clone with `git apply -p0`. Apply `tarball_deps.patch` using CEF's official `tools/patcher.py --patch-file tarball_deps --patch-dir <src>`; without Chromium Git metadata it uses GNU patch. Git for Windows supplied GNU patch 2.7.6 on PATH.
2. Apply the recorded updater test-download exclusion. Set `source_tarball=True` in the existing pinned `.gclient` configuration, retaining the resolved Siso instance. Run `gclient sync --nohooks --no-history -j4` and `gclient runhooks`: both returned zero. The Windows tool/resource acquisition remains part of the recipe; it is not contained entirely in the source archive. `archive-sync.log` is empty, so its contents are not evidence of an individual download inventory; success is recorded by the guarded command sequence.
3. With the previously documented custom MSVC/SDK environment and GN flags, run `cef/tools/gclient_hook.py`. Result: **115 patches, 114 applied, 1 already applied/skipped, 0 failures; 32,117 GN targets from 4,952 files**. The archive path has one fewer overall target than the Git path, but the selected library dependency graph is identical.
4. `gn desc out/Release_GN_x64 //cef:libcef deps --all --format=json` returned zero and identified **18,509 dependency targets**, including FFmpeg and excluding the updater test binary. The graph SHA-256 is `08ed069bf0ff8e86de0329778795defdc16e4f544fbbe6eea2715f1d0e3ea181`, identical to the earlier Git-based graph.
5. Narrow `gn desc` inventories for `sources`, `inputs`, `script` and `data` checked 99,313 distinct non-output paths across libcef and its dependencies; 64,000 generated output paths were excluded because they are expected to be created during a build. The remaining declared missing header is explained above. This presence check is not a content hash verification of every file, a compiler execution, or a substitute for the actual build.
6. The initial `ninja -n libcef` stopped at build-file regeneration and was not counted as a full plan check. Execute only `ninja args_gn_source` (CEF's existing no-op/build-file preparation action), then run `ninja -n libcef` again. The second dry-run returned zero and planned **59,105 actions**, ending with the libcef DLL link. No compiler/linker actions ran, and no libcef.dll was produced. The stale declared header did not block Ninja's actual build plan. The logs are `archive-ninja-prepare.log` and `archive-ninja-dry-run.log`; these results still do not prove compiler/linker success.

The generated `args.gn` SHA-256 is `214fb3258c8a3212588b337c531b61ba4d45a5913a35f3e1c4c49842b95de953`. Its only differences from the earlier validation args are slash direction in the local Visual Studio/SDK paths. These are still a new recorded validation configuration, **not** recovered historical official DLL arguments.

Local scripts/logs and the supplement inventory are retained in `E:\CEF-Archive-Validation`, including `validate.ps1`, `archive-hooks.log`, `archive-gn.log`, `archive-libcef-deps.json`, `windows-source-supplements.json`, `archive-missing-inputs-before.json` and `archive-static-inputs.json`. Only build preparation and source/license evidence were changed; the application, original runtime, packaging rules, project LICENSE and previous working-tree changes were preserved. Public source delivery, historical build-input clarification and final license review remain separate release checks.

## Anonymous acquisition and fresh bootstrap follow-up (2026-10-01)

A new workspace, `E:\CEF-Public-Source-Check\20261001-160142`, retrieved complete fixed checkouts for OpenXR, DirectX-Headers, Microsoft WebAuthn, gperf and depot_tools from the official URLs recorded above. All five HEAD commits match their pins. Git credential helpers and prompts were disabled; global/system Git configuration was excluded. Direct connection timed out; the existing system proxy succeeded. This verifies anonymous retrieval, not universal connectivity or future availability.

The newly fetched depot_tools successfully ran its original `bootstrap/win_tools.bat` in a fresh directory, using an isolated new CIPD cache and isolated Git configuration that disables global Git changes. No old bootstrap binaries were copied. Both bootstrap and `python3.bat --version` returned zero; Python is 3.11.8, matching the fixed manifest. The fresh CIPD client anonymously resolved `build/siso/windows-amd64` instance `TupZUdv9YTinXM5vf61WKpINxNrllerRprrKorxAVqEC`. Resolution is not a fresh download/execution of Siso or all Chromium tools; full cache-free Chromium sync/hooks were not repeated.

A fresh complete CEF archive download matched SHA-256 `69fbd8385da491757a862c7ada70318b691cb8aee4e9ea0499a63baa90cb76e7`. Google's Chromium archive responded to an anonymous range GET with 206, object generation `1788469597508994`, stored length 5,916,162,416 and the same MD5/ETag as the earlier complete download. Its first 1,024 bytes match the locally full-hash-verified archive. The range test does not replace a new full download/hash verification; the previous full verification remains the source evidence.

A source-direction defect was corrected: GitHub `/archive/<commit>.zip` returns playback FFmpeg SHA-256 `9da21157f8f6c033c1445a6e69a0650d2bee8a7b669bd1eecb23d79c7fd1baf5` and root `FFmpeg-a5923073bfd8f25b7300d93af3f8e690174ebd30/`. The build recipe was written for the API zipball variant, root `FFmpeg-FFmpeg-a592307/`. All 10,424 normalized file paths and file contents in both variants match with no added/missing/changed files. A new anonymous download from `https://api.github.com/repos/FFmpeg/FFmpeg/zipball/a5923073bfd8f25b7300d93af3f8e690174ebd30` exactly matched the recipe's existing SHA-256 `65474f81cf8a4e2529cca6c25877fca8b6f3340a67ff804a306bbc3940cd46f6`. Recipient directions now use that URL; no hash gate, source recipe, binary or manifest hash was weakened or changed.

Logs/results are `public-source-check.json`, `bootstrap-result.json`, `bootstrap.log`, `python-version.log`, `siso-description.json`, `archive-result.json`, `playback-source-comparison.json` and `chromium-range.headers` in the new workspace. [Source-method review](CEF-Source-Method-Review.md) explains the proposed later-version compliance route, its unapproved draft statement and remaining obligations. No license election, source offer, full CEF compile, installer, commit, push or upload occurred.

## Recipient recipe and CEF metadata closure (2026-10-01)

The previously local-only executable preparation sequence is now shipped as [prepare-cef-windows-source.ps1](prepare-cef-windows-source.ps1). It parameterizes workspace/toolchain paths and uses the same original upstream patcher/hooks, guarded archive configuration and Ninja dry-run. It requires already verified/extracted sources and Windows supplements, PowerShell 7, Git and GNU patch.exe on PATH. It neither downloads the large archive nor compiles/relinks CEF. Initial replay exposed a missing GNU patch.exe PATH prerequisite; the helper now rejects that before any source changes and the guide explicitly supplies the Git usr/bin PATH step.

CEF version generation requires more than its source archive or origin/7977. The validated fixed non-shallow metadata consists of HEAD `708dc140cbc3286826a8abef89dc23a44ff9ea72` (3,589 commits), origin/master `ff57d4eae16d36457895f2de115a71d502e85a08` (3,631 commits) and origin/7977 `82a832e53b4c9f572d3d3e9bbf22c2f5161fe8be` (3,593 commits). HEAD/master merge-base is `89749b7e67efd2f38c4da72f4091282140a2fe75`. CEF's `git cherry` version calculation has six branch entries; archive-supplied Chromium LASTCHANGE and LASTCHANGE.committime are retained by the tarball hooks.

These exact refs/history were newly fetched anonymously from official GitHub CEF into `E:\CEF-Public-Source-Check\cef-fixed-metadata-20261001`, with credential helpers/prompts and system/global Git configuration excluded. The checkout is non-shallow. The fixed upstream cef_version.py, using the verified Chromium VERSION metadata, produced `152.0.6+g708dc14+chromium-152.0.7977.83`, matching the SDK identity. This closes the version-generation metadata acquisition/instruction gap; it does not recover historical official GN flags.

The checked-in recipe replayed successfully on the independent archive workspace: sync/hooks completed, 115 patches were already applied/skipped with zero failures, GN generated 32,117 targets, Ninja build-file preparation completed, and dry-run planned 59,105 actions through the DLL link. No compiler/linker ran; libcef.dll is still absent in that source workspace. The log is `build/ffmpeg-local/cef-recipient-recipe-check.log`, an ignored local evidence file. A runnable guard check, tests/cef-source-preparation-check.ps1, rejects a wrong depot_tools revision and verifies .gclient remains unchanged. Existing FFmpeg/source-material/license checks passed.

Upstream hooks regenerate translations/GN settings and may partially apply conflicting source patches; the helper is for initial pristine preparation, not for resetting a recipient's modified library. The recipient guide instructs keeping modified-library development separate and using its own GN/Ninja commands after preparation. No blanket source completeness, historical binary reproducibility, full compilation, modified-library replacement or public delivery assertion is made.

## Official Windows recipe comparison and narrowed input request (2026-10-01)

The [Windows x64 example at the fixed CEF revision](https://github.com/chromiumembedded/cef/blob/708dc140cbc3286826a8abef89dc23a44ff9ea72/docs/automated_build_setup.md) sets `GN_DEFINES=is_official_build=true`, `GYP_MSVS_VERSION=2022`, and passes `--x64-build --with-pgo-profiles`. Combining that example with the pinned Chromium defaults gives the following **recipe-derived Release settings**, not recovered settings for the downloaded DLL:

| Input | Fixed documented example/default | Local archive preparation |
|---|---|---|
| `chrome_pgo_phase` | `2` for official Windows Release with DCHECK disabled | `0` |
| `use_thin_lto` | `true` for the default Clang official Windows build | `false` |
| `symbol_level` | Windows default `2` | `0` |
| Visual Studio selection | `2022` | `2026`, toolset `14.51.36231` |
| `ffmpeg_branding`, `proprietary_codecs` | No explicit override in the example | Explicit `"Chromium"`, `false` |

Defaults were read from `build/config/compiler/pgo/pgo.gni` and `build/config/compiler/compiler.gni` in the verified Chromium tree. Debug has different DCHECK/PGO behavior and must not be conflated with Release. These differences show why successful local preparation cannot be represented as recovery of the official binary's original inputs. They do not, by themselves, prove a codec/license change or make byte-identical rebuilding a license requirement.

The pinned `chrome/build/win64.pgo.txt` identifies `chrome-win64-7977-1788347003-b01cd62502e391222764f4bbd4a160d4a813af02-64138f5f7ae77be1598461fef66a266b751a9fbe.profdata`. Chromium's `DEPS` and `tools/update_pgo_profiles.py` locate it in `chromium-optimization-profiles/pgo_profiles`. An anonymous [object HEAD request](https://storage.googleapis.com/chromium-optimization-profiles/pgo_profiles/chrome-win64-7977-1788347003-b01cd62502e391222764f4bbd4a160d4a813af02-64138f5f7ae77be1598461fef66a266b751a9fbe.profdata) returned HTTP 200, generation `1788353453892620`, stored gzip size `105304847`, and server-reported MD5 `d43ec3f2b72c9d47df99cf60c8810eab`. No profile body was downloaded or locally hashed. It is absent from the no-PGO validation workspace; its use in the historical official binary remains unconfirmed. Do not treat the filename's hashes or a HEAD response as a verified content hash.

### Specific upstream request draft — not sent

Contact route checked on 2026-10-01: CEF's [issue-template configuration](https://github.com/chromiumembedded/cef/blob/master/.github/ISSUE_TEMPLATE/config.yml) directs general usage questions to the [official support forum](https://www.magpcss.org/ceforum/), rather than the GitHub issue tracker. The [2019 build-flags discussion](https://www.magpcss.org/ceforum/viewtopic.php?f=10&t=17138) describes the documented configuration for contemporary Spotify builds; it is historical context, not a version-specific confirmation for this 2026 Windows binary. No public answer located in this bounded search supplies the exact per-binary missing inputs below. Do not file this support question as a GitHub bug or treat the old discussion as recovery of current build arguments.

> We redistribute the Standard Windows x64 CEF 152.0.6+g708dc14+chromium-152.0.7977.83 SDK: binary archive SHA-256 a32f0f552112825b2b63fc127c96c09b2b14de8f9b968a1169385bf93021eba4; libcef.dll SHA-256 f5021c3477a84a9c96c0b6a01a038a568fe2cdb7d294ea84aaf6cd70b26c8a09. Could you identify the release-specific GN_DEFINES/args.gn, compiler/SDK pins, PGO profile and any patches beyond CEF commit 708dc140cbc3286826a8abef89dc23a44ff9ea72 and Chromium commit 79460ebecaa5625e57a5fb679a735659e73dc687? The fixed Windows example enables PGO, while our preparation disables it; is the win64.pgo.txt profile above the actual input, or was it overridden? Are there additional source or generated build inputs needed to rebuild the corresponding library? Your chromium-152.0.7977.83.tar.xz GET returns 403 in our environment although HEAD/checksum work; where can recipients obtain the corresponding source and build-input records? We verified Google's same-version source archive plus the four pinned Windows supplements, but do not assume it includes every input of your binary.

This replaces the earlier broad question with concrete binary identities and missing input categories. No upstream message or retention promise was issued. A source/relink sufficiency review remains separate from the technical request. Do not add an unconditional full-rebuild requirement or mark source sufficiency resolved solely on the basis of the documented default recipe.

## No-contact follow-up: runtime replacement boundary (2026-10-01)

The publisher chose to continue without contacting upstream. The request remains unsent; its existence is not a remaining authorization request. Lack of contact is neither a license violation by itself nor confirmation of source completeness.

Project CMake calls `verify-ffmpeg.ps1` during publisher configuration/build/packaging; native startup does not call it. The fixed CEF bootstrap source checks the signing status of chrome_elf and the client DLL; the current SDK bootstrap/libcef are unsigned, and the local Musxi client DLL is unsigned. These observations do not establish behavior for future signed distributions or arbitrary modified libraries.

A bounded check copied the previously built candidate runtime to `build/cef-runtime-replacement-check/check-20261001-202434/runtime/`, appended a non-executable overlay marker to its libcef.dll, and renamed the launcher/client pair to installed names `MusxiPlayer.exe` / `MusxiPlayer.dll`. The original runtime/SDK was unchanged and the test redirected APPDATA/LOCALAPPDATA to separate directories. Original libcef SHA-256: `f5021c3477a84a9c96c0b6a01a038a568fe2cdb7d294ea84aaf6cd70b26c8a09`; copy SHA-256: `565241b5614acb352e5eb05b1fccbb9a1f6c412c572af3d88e9888b40b50c546`. `--test-app --cef-smoke` returned zero with the changed whole-file hash. The ignored runnable check and result.json remain in that check workspace.

This demonstrates acceptance of that byte-different runtime through real CEF startup/UI/bridge smoke checks, and separates publisher inventory verification from installed startup. It does **not** modify executable FFmpeg code, test relinking, prove acceptance of all interface-compatible modifications, or establish corresponding-source sufficiency. An overlay can leave image/signature-related identities unchanged; the result must not be expanded into a universal absence-of-integrity-check claim.

The [recipient guide](../docs/release-source-delivery.md#recipient-relink-and-installation-route) now supplies actual relink target names, runtime/resource replacement, executable/DLL basename, rollback and smoke-test steps. Compilation/packaging commands are explicitly unexecuted. Original Windows build-input/source differences and public delivery remain open; full CEF compilation and upstream contact remain outside the selected scope.
