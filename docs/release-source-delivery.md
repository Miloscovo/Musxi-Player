# Musxi fixed FFmpeg source delivery

## v0.3.0

The v0.3.0 installer uses the same audited CEF and two FFmpeg binary sets as
v0.2.0. The fixed third-party source entry and retrieval instructions below
continue to apply. Application source is available at the v0.3.0 tag; the
installed `release-source-record.json` records its exact clean Git commit.
The v0.2.0 identities below are historical and do not identify the v0.3.0
application binary. Default v0.3.0 Release uploads contain only the installer.

## Current release identity and status — 2026-10-02

The published v0.2.0 installer corresponds to Musxi commit
[`0d7ba2d5741cba5d13ed0a3de089235e31dc53e4`](https://github.com/Miloscovo/Musxi-Player/tree/0d7ba2d5741cba5d13ed0a3de089235e31dc53e4).
Its installed `release-source-record.json` identifies this exact clean revision.
Use the full commit in source links; do not use a floating branch, a project
homepage, or assume a tag can never move. This review does not move the tag,
change the installed binary or remove any existing Release attachment.

There are **two independent FFmpeg instances**. Playback source is not a
substitute for Chromium's FFmpeg fork, and a pristine upstream FFmpeg checkout
is not a substitute for the Chromium fork and its necessary integration files.
The existing two source archives remain available during this migration.

Both fixed public routes were anonymously recovered on 2026-10-02 from
[`efd72195e2202325c8a8af8b79068f63ccd1cd18`](https://github.com/Miloscovo/Musxi-Player/tree/efd72195e2202325c8a8af8b79068f63ccd1cd18).
The fresh check downloaded that commit's getter/index/manifest, used Python
isolated mode and disabled Git credentials; no local source bundle, SDK or CEF
workspace was an input. All 1,045 embedded file hashes and the reconstructed
fork archive matched; playback source and recipe matched their recorded hashes.
This is source-access verification, not unconditional legal clearance or an
official CEF rebuild. Existing Release attachments remain untouched.

**Immutable recipient entry:** [verified source guide](https://github.com/Miloscovo/Musxi-Player/blob/efd72195e2202325c8a8af8b79068f63ccd1cd18/docs/release-source-delivery.md).
That frozen guide describes the retrieval gate before it was passed; this
dated result records its successful execution. Download these three files from
the same commit, preserving the `licenses/` directory:

- [get-ffmpeg-sources.py](https://raw.githubusercontent.com/Miloscovo/Musxi-Player/efd72195e2202325c8a8af8b79068f63ccd1cd18/get-ffmpeg-sources.py)
- [CEF-FFmpeg-source-index.json](https://raw.githubusercontent.com/Miloscovo/Musxi-Player/efd72195e2202325c8a8af8b79068f63ccd1cd18/licenses/CEF-FFmpeg-source-index.json)
- [FFmpeg-build.json](https://raw.githubusercontent.com/Miloscovo/Musxi-Player/efd72195e2202325c8a8af8b79068f63ccd1cd18/licenses/FFmpeg-build.json)

Only Python 3 and Git plus network access are needed for retrieval. Compilation
requires the separately documented tools; retrieval does not compile anything.

## Fixed source index for the existing installer

| Instance / material | Fixed public location and identity | Correspondence |
|---|---|---|
| Musxi application, build and installed notices | [Musxi commit 0d7ba2d](https://github.com/Miloscovo/Musxi-Player/tree/0d7ba2d5741cba5d13ed0a3de089235e31dc53e4); [manifest at that commit](https://github.com/Miloscovo/Musxi-Player/blob/0d7ba2d5741cba5d13ed0a3de089235e31dc53e4/licenses/FFmpeg-build.json) | Application revision recorded by the existing installer; manifest names/hashes both FFmpeg binary sets. |
| Independent playback FFmpeg | [Official API ZIP at a5923073bfd8f25b7300d93af3f8e690174ebd30](https://api.github.com/repos/FFmpeg/FFmpeg/zipball/a5923073bfd8f25b7300d93af3f8e690174ebd30) | SHA-256 `65474f81cf8a4e2529cca6c25877fca8b6f3340a67ff804a306bbc3940cd46f6`; 23,180,740 bytes. This is the archive variant expected by the recipe. |
| Playback recipe and Musxi modification | [build-ffmpeg.ps1 at 0d7ba2d](https://github.com/Miloscovo/Musxi-Player/blob/0d7ba2d5741cba5d13ed0a3de089235e31dc53e4/build-ffmpeg.ps1) | SHA-256 `ba5aea7519c7e5faa5285572c1f13bd28cbc0d595fcc9b11633b803cf1830099`; creates the only Musxi-added source file, `VERSION`, and records configure flags. |
| CEF embedded FFmpeg fork | [Chromium FFmpeg fixed tree 2b68d2babae73714846961fb0ee47e3b3d2e39a9](https://chromium.googlesource.com/chromium/third_party/ffmpeg/+/2b68d2babae73714846961fb0ee47e3b3d2e39a9/) | Fork `README.chromium` records upstream merge `a5e6c0175a7245fc1a9f4639da2810156128aa8b`. Preserve Chromium's patches, configuration, generated source lists, license and credits. |
| Embedded Chromium integration/build files | [Chromium fixed commit 79460ebecaa5625e57a5fb679a735659e73dc687](https://chromium.googlesource.com/chromium/src/+/79460ebecaa5625e57a5fb679a735659e73dc687/) | Retrieve selected files by exact path/revision, together with the separately pinned dependency sources. A fork-only tarball is insufficient for this supplement. |
| Original CEF patch controls | [CEF fixed commit 708dc140cbc3286826a8abef89dc23a44ff9ea72](https://github.com/chromiumembedded/cef/tree/708dc140cbc3286826a8abef89dc23a44ff9ea72) | Original selected CEF patches explain supplemented files' patched state. These are upstream integration changes, not Musxi codec modifications. |
| Embedded source/relink directions already public | [CEF-FFmpeg-Source-Access.md at 0d7ba2d](https://github.com/Miloscovo/Musxi-Player/blob/0d7ba2d5741cba5d13ed0a3de089235e31dc53e4/licenses/CEF-FFmpeg-Source-Access.md) and [dependency pins](https://github.com/Miloscovo/Musxi-Player/blob/0d7ba2d5741cba5d13ed0a3de089235e31dc53e4/licenses/CEF-source-revisions.json) | Identify fixed remaining source dependencies and compatible runtime replacement; retained during migration. |

### Independent playback FFmpeg: exact modifications and build inputs

`n9.0.2-3-ga5923073bf-musxi-local-1` supplies `avcodec-63.dll`,
`avformat-63.dll`, `avutil-61.dll` and `swresample-7.dll`, dynamically linked by
Musxi. All four DLL hashes, component versions and actual configuration API
outputs match the fixed public manifest. Each DLL reports LGPL version 2.1 or
later. The installed program does not enforce the publisher's original DLL
hashes against users' compatible replacement libraries.

The official source ZIP has no `VERSION` file. Musxi's public recipe creates
it with exact contents `n9.0.2-3-ga5923073bf\n`, then uses
`--extra-version=musxi-local-1`. The suffix identifies Musxi's local build;
it does not identify an undisclosed decoder patch. A comparison of all 10,424
upstream files against the actual build source found no changed or missing
files; `VERSION` was the only addition. It is generated in the publicly pinned
recipe, so the modification does not exist only in a local build directory.

The recipe builds shared Windows x64 libraries with MSVC, disables static
libraries, GPL, nonfree, version3, autodetected external dependencies,
networking and standalone programs. Exact configure parameters are in the
fixed recipe and manifest, including enabled demuxers/parsers/decoders.
Audited tools were MSVC 19.51.36257, GNU Make 4.4.1 and NASM 2.16.03; the
application's Windows SDK record is 10.0.26100.0. Obtain separately licensed
tools locally; do not bundle proprietary SDK/toolchain binaries as source.

The optional playback source ZIP contains the unchanged official source ZIP,
recipe, generated `config.h`, `config_components.h`, `ffbuild/config.mak`,
`ffbuild/config.log`, `libavutil/ffversion.h`, build log and original licenses.
The upstream configure/Makefile/version.sh plus the public recipe regenerate
these configuration/version outputs; logs are evidence, not hidden source
changes. Different toolchain versions or prefixes can change output bytes;
this is not a byte-identical rebuild promise.

### CEF embedded FFmpeg: separate binary and source materials

The embedded fork corresponds to `libcef.dll` from CEF
`152.0.6+g708dc14` / Chromium `152.0.7977.83`, SHA-256
`f5021c3477a84a9c96c0b6a01a038a568fe2cdb7d294ea84aaf6cd70b26c8a09`.
Its original LGPL-2.1-or-later notices remain intact; the publisher selected
LGPLv3 compliance for the audited embedded portions. No Musxi FFmpeg decoder
patch is introduced. The required Chromium fork modifications and selected
original CEF integration patches remain distinct from Musxi's playback VERSION
addition. LGPL/GPL original texts, FFmpeg LICENSE/CREDITS and original CEF /
Chromium notices must be retained.

The existing enriched archive includes the exact fork and selected Chromium
FFmpeg scripts, GN/configuration files, stub generators, Opus sources, NASM
build definitions, original applicable CEF patches, file hashes and fixed
source-access instructions. The migration's
[CEF-FFmpeg-source-index.json](../licenses/CEF-FFmpeg-source-index.json)
records those exact supplemented files' public sources and reconstruction
rules. Patches/generation rules must recreate the expected file hashes before
this replaces the archive; fetching only a vanilla fork does not suffice.
Remaining combined-work dependencies/relink directions stay available through
the fixed pins above and the retained recipient instructions below.

## Public retrieval and Release migration gate

From a new directory, use the reviewed public `get-ffmpeg-sources.py` and its
matching source index from an immutable Musxi commit:

```powershell
python get-ffmpeg-sources.py --component playback --output ./playback-source
python get-ffmpeg-sources.py --component embedded --output ./embedded-source
# Or obtain both instances into a fresh destination:
python get-ffmpeg-sources.py --component all --output ./ffmpeg-sources
```

The destination must be new/empty. The public check must use anonymous fixed
URLs without local Chromium/CEF workspaces, source bundles, SDKs or credentials.
It verifies source hashes, original patches/reconstruction, Musxi's VERSION
recipe and the manifest's binary/revision mapping. It does not download full
Chromium/CEF or rebuild CEF. A completed transfer alone is not the verifier's
success condition.

Only after the embedded and playback retrieval checks both pass **and the
matching index/helper are publicly available at a fixed commit**, announce a
direct immutable source-index link next to the binary download. Then future
default Release assets may consist only of `MusxiPlayer-Setup-<version>.exe`.
GitHub's automatic application source ZIP/tar.gz remain, but do not contain the
upstream FFmpeg source trees by themselves. Local compliance/source archives
and their checks remain supported, with optional on-demand bundles; checksums
may remain local rather than a default Release attachment.

Current v0.2.0 assets are not deleted by this migration. The public index must
remain available while its matching binaries are distributed. Keep independent
verified backups; a local backup is not public delivery. If a required URL
fails, restore equivalent exact public access and update the download-page
directions before continuing binary distribution. No written source offer or
upstream retention guarantee is invented here.

### Publisher packaging defaults

`package.ps1 -RequireCleanSource` retains SDK/binary/license/source-bundle
validation and the existing local archive preparation. It verifies the public
getter, source index and binary/build manifest at the full pinned source commit
before building. A changed binary/source record or getter requires a newly
published and anonymously verified source revision; pass it explicitly with
`-PublicSourceRevision <full-commit>` after completing that review. The quick
packaging check verifies the published mapping, not every upstream URL: run the
full anonymous getter again when reviewing source changes or availability.

`dist/release-assets.json` lists the default upload: installer EXE only, and the
fixed source-guide URL to announce next to it. GitHub's automatic source ZIP and
tar.gz remain. SHA256SUMS and EXE checksum sidecars stay local; `-StageSourceBundles`
optionally copies the two source archives to dist. The developer SDK remains a
local optional developer artifact. Do not upload a dist wildcard: existing
archives are deliberately not deleted. No script in this change uploads assets
or removes existing ones. `verify-ffmpeg.ps1 -PublicReleaseTag` remains available
for the existing attachment route; `-PublicSourceRevision` checks the new route.

CEF's BSD license requires the matching CEF LICENSE and Chromium credits in the
installed package. It does not require uploading full CEF/Chromium source or
reproducing official GN/PGO/ThinLTO builds. Installed Musxi GPL license,
THIRD_PARTY_NOTICES and FFmpeg source/version/license records remain required;
this migration removes no license text.

## Historical optional recipient/relink appendix

The following 2026-10-01 preparation results and large-source directions are
retained for recipients who elect to recombine/replace the embedded library.
They are historical evidence and optional instructions, **not new CEF BSD
Release gates**, not the default source acquisition route, and not a request to
repeat a full CEF/Chromium audit or upload a full source checkout.

### Optional historical inputs (not default Release assets)

These pins explain the historical preparation steps below; the current public
FFmpeg getter does not acquire full CEF/Chromium trees.

| Historical material | Fixed identity / source | Recorded evidence |
|---|---|---|
| CEF source | [Archive at 708dc140cbc3286826a8abef89dc23a44ff9ea72](https://codeload.github.com/chromiumembedded/cef/tar.gz/708dc140cbc3286826a8abef89dc23a44ff9ea72) | 3,572,838 bytes; SHA-256 `69fbd8385da491757a862c7ada70318b691cb8aee4e9ea0499a63baa90cb76e7`. |
| Chromium source/dependencies | [Google archive 152.0.7977.83](https://commondatastorage.googleapis.com/chromium-browser-official/chromium-152.0.7977.83.tar.xz), commit `79460ebecaa5625e57a5fb679a735659e73dc687` | 5,916,162,416 bytes; SHA-256 `8064dd693f3eccb58d00e2f264c582b1e869c2883b28655787fe21e16e538ada`. Optional recipient acquisition; not a required publisher upload. |
| depot_tools | [Fixed tree 2da9ee6f6c86332551055bc44245fab94675272e](https://chromium.googlesource.com/chromium/tools/depot_tools/+/2da9ee6f6c86332551055bc44245fab94675272e/) | Siso instance `TupZUdv9YTinXM5vf61WKpINxNrllerRprrKorxAVqEC`; dependency pins retained in [inventory](../licenses/CEF-source-revisions.json). |

### Windows supplements

Restore complete fixed trees into these paths relative to Chromium `src`, preserving original licenses/per-file notices. Do not use branch tips or copy only missing headers.

| Destination | Official repository | Commit |
|---|---|---|
| `third_party/openxr/src` | https://chromium.googlesource.com/external/github.com/KhronosGroup/OpenXR-SDK | `75c53b6e853dc12c7b3c771edc9c9c841b15faaa` |
| `third_party/microsoft_dxheaders/src` | https://chromium.googlesource.com/external/github.com/microsoft/DirectX-Headers.git | `62c23d5ec700659453c6fe89d296554b2a5e7edc` |
| `third_party/microsoft_webauthn/src` | https://chromium.googlesource.com/external/github.com/microsoft/webauthn.git | `ef82c157125a0490e05f6ea82a7adb1b8e1bad08` |
| `third_party/gperf` | https://chromium.googlesource.com/chromium/deps/gperf.git | `e9eeea862a18e77b945d98eff7e1bf065d3daf8e` |

Pins already occur in the fixed inventory. All four complete trees were additionally fetched anonymously into new directories on 2026-10-01; credential helpers/prompts and system/global Git configuration were disabled. Direct connection timed out; the existing system proxy succeeded. gperf is a build tool, not an application production dependency.

## Recipient preparation sequence

Use a new workspace, sufficient space, Git for Windows/GNU patch, Python/depot_tools and separately licensed compatible MSVC/Windows SDK. Do not redistribute proprietary SDK/toolchain binaries as source materials.

1. Download the Google archive, verify SHA-256, and extract so `chromium/src/chrome/VERSION` exists. Check extraction errors: Windows symlink creation failed locally. Do not call partial extraction complete or disable security protections. Required Unix links may need a suitable extraction environment.
2. Obtain CEF at the exact commit in `chromium/src/cef`, retaining the `7977` branch/history needed by version scripts. Obtain depot_tools at its exact commit in a separate directory. Set `DEPOT_TOOLS_UPDATE=0`; fetching history must not change the checked-out commit. Preserve original patches/notices.

   CEF version generation also needs `origin/master`, not only `7977`. In a **new** CEF repository, fetch full history for `ff57d4eae16d36457895f2de115a71d502e85a08` into `refs/remotes/origin/master` and `82a832e53b4c9f572d3d3e9bbf22c2f5161fe8be` into `refs/remotes/origin/7977`, then check out `708dc140cbc3286826a8abef89dc23a44ff9ea72` detached. Do not use `--depth` or overwrite an existing recipient repository's refs. These are fixed metadata inputs for the new validation recipe, not a recovered historical official build record.

   ```powershell
   git init <new Chromium src/cef directory>
   git -C <CEF directory> remote add origin https://github.com/chromiumembedded/cef.git
   git -C <CEF directory> fetch origin ff57d4eae16d36457895f2de115a71d502e85a08:refs/remotes/origin/master 82a832e53b4c9f572d3d3e9bbf22c2f5161fe8be:refs/remotes/origin/7977
   git -C <CEF directory> checkout --detach 708dc140cbc3286826a8abef89dc23a44ff9ea72
   ```
3. Restore the four Windows trees above. Bootstrap depot_tools using its fixed instructions; add depot_tools and Git's GNU patch to PATH. The separate fresh Python/CIPD bootstrap passed; this does not establish a complete cache-free Chromium build environment.
4. Apply CEF's `patch/patches/tarball_gclient.patch` to depot_tools with `git apply -p0`. From Chromium `src`, use `cef/tools/patcher.py --patch-file tarball_deps --patch-dir <absolute Chromium src path>`. Apply [updater test exclusion](../licenses/CEF-excluded-updater-test.patch) using GNU patch `-p1` after a successful dry-run. These change source acquisition, not application behavior.
5. Copy [CEF-source.gclient](../licenses/CEF-source.gclient) to `chromium/.gclient`, changing only `source_tarball` to `True`; retain the Siso pin. The checked-in file remains `False` for the Git route. From `chromium`, run `gclient sync --nohooks --no-history -j4` and `gclient runhooks`; check exit statuses and compare revisions/packages with the inventory.
6. Set the [documented custom toolchain environment](../licenses/CEF-Source-Verification.md) using the recipient's paths. Validation used MSVC toolset `14.51.36231`, SDK `10.0.26100.0`, `DEPOT_TOOLS_WIN_TOOLCHAIN=0`, `WIN_CUSTOM_TOOLCHAIN=1`, `CEF_VCVARS=none`, `GN_OUT_CONFIGS=Release_GN_x64`. Configure `GYP_MSVS_OVERRIDE_PATH`, `GYP_MSVS_VERSION`, `VS_CRT_ROOT`, `SDK_ROOT`, `SDK_VERSION`. GN settings:

   ```text
   is_official_build=true symbol_level=0 chrome_pgo_phase=0 use_thin_lto=false proprietary_codecs=false ffmpeg_branding="Chromium"
   ```

7. Run `python tools/gclient_hook.py` from `chromium/src/cef`. Local result: 114 patches applied, one already applied/skipped, 32,117 GN targets. `gn desc out/Release_GN_x64 //cef:libcef deps --all --format=json` yielded the same 18,509-dependency graph as the Git route. Settings are a new validation configuration, not recovered official DLL arguments.
8. From Chromium `src`, run `third_party/ninja/ninja.exe -C out/Release_GN_x64 args_gn_source`, then `third_party/ninja/ninja.exe -C out/Release_GN_x64 -n libcef`. Dry-run planned 59,105 actions through DLL linking; no compilation/linking or DLL output. Actual compilation omits `-n` and remains untested; it is outside this task. Follow fixed `cef/tools/make_distrib.py --help` and [official instructions](https://chromiumembedded.github.io/cef/branches_and_building) for a complete compatible SDK/resources.

CEF declares absent `libcef_dll/ptr_util.h` in `cef_paths2.gypi`. It is absent in fixed upstream and both local routes, but not referenced by generated Ninja inputs or local library includes. Do not invent a replacement or claim a fully clean declared-input audit. Dry-run success does not establish compiler/linker or modified-library replacement success.

### Checked-in preparation recipe

After acquiring and verifying the archive, fixed CEF/depot_tools metadata and complete Windows supplements, [prepare-cef-windows-source.ps1](../licenses/prepare-cef-windows-source.ps1) executes steps 4–8 with explicit exit checks. It requires PowerShell 7, Git and Git for Windows' GNU `patch.exe` on PATH. It does not download/extract the big source archive or supply missing Windows Git trees, and its representative-file checks are not a full content/revision audit of those trees. Verify the pinned sources as described above first.

```powershell
$env:PATH='<Git for Windows>/usr/bin;' + $env:PATH
./licenses/prepare-cef-windows-source.ps1 `
  -ChromiumSource '<workspace>/chromium/src' -DepotTools '<workspace>/depot_tools' `
  -VisualStudioRoot '<Visual Studio BuildTools>' `
  -VCRuntimeRoot '<Visual Studio>/VC/Redist/MSVC/14.51.36231/x64/Microsoft.VC145.CRT' `
  -WindowsSdkRoot '<Windows Kits>/10'
```

Configure any required network proxy in your process environment, rather than copying the publisher's local proxy address. The helper refuses a Chromium Git checkout, mismatched CEF/depot_tools/metadata refs or a different existing `.gclient`. Already applied exact acquisition patches are accepted; it never resets source/refs or forces patch conflicts. Original CEF hooks still apply their own patches and regenerate translations/GN configuration, so use this as **initial preparation of pristine inputs**; keep modified-library development in a separate working copy and use its own GN/Ninja commands after initial preparation. No compiler/linker actions run: the final command is `ninja -n libcef`.

### Recipient relink and installation route

The preparation helper stops before compilation. After making an embedded FFmpeg change in a separate prepared working copy, a recipient can use the fixed Chromium/CEF GN build rules to compile/relink the combined library. For example, from Chromium `src`, with the same toolchain environment, the actual build command is:

```powershell
./third_party/ninja/ninja.exe -C out/Release_GN_x64 libcef bootstrap
```

This command is provided for recipients and **has not been executed in this review**. `libcef` and `bootstrap` are targets in the fixed CEF BUILD.gn. Do not rerun the acquisition helper over modified sources; it invokes hooks that may apply patches/regenerate files. Review codec/ABI configuration and preserve all applicable licenses when changing the library. The version/revision metadata must remain suitable for the CEF API expected by Musxi. A newly built library need not have the publisher's original SHA-256; it must be interface-compatible and have matching required resources/dependencies.

Use the fixed CEF `tools/make_distrib.py` and its documented options to collect a consistent runtime/SDK/resource set after compilation. The packaging operation is also untested here. Do not mix a new DLL with mismatched ICU, V8 snapshot, .pak/locales, chrome_elf or graphics runtime files. Keep original third-party notices, adding notices for any new dependencies. The publisher's `verify-ffmpeg.ps1` is a distribution inventory gate, not an installed-application permission check; a modified runtime is expected to fail the publisher's original manifest checks and needs its own inventory if redistributed.

For installation, exit Musxi and its child processes, back up the original runtime outside its installation directory, and replace the compatible CEF runtime/resource set in a user-writable copy first. The installed launcher/client DLL pair is named `MusxiPlayer.exe` / `MusxiPlayer.dll` (the build tree uses `MusxiPlayerWeb.exe` / `MusxiPlayerWeb.dll`). Preserve that matching basename convention. Do not replace the separate playback FFmpeg DLLs merely to change CEF's embedded FFmpeg. Preserve Windows permissions required by CEF sandbox processes; do not disable the sandbox or security protections. Normal permissions on a protected installation folder may require administrator access; a user-writable copy avoids changing the system installation.

Run `./MusxiPlayer.exe --test-app --cef-smoke` in the copy and confirm exit code zero, then check normal startup, navigation, playback and shutdown. Restore the backup if any check fails. This is an installation/testing procedure, not a completed modified-library relink test or a guarantee that arbitrary CEF builds are compatible.

The inspected current bootstrap, libcef.dll and locally built application DLL are unsigned. Fixed bootstrap code verifies matching signing status for chrome_elf/client DLLs; it does not require a Musxi-owned signing key for the current unsigned set. A future signed distribution needs this replacement/installation review again rather than assuming today's result applies.

## Selected license method: conditions remain

Musxi is GPL-3.0-or-later. Both FFmpeg copies retain upstream LGPL-2.1-or-later notices. CEF/Chromium and other components retain individual licenses; do not treat the entire delivery as one license group.

- [GPLv3 section 6(d)](https://spdx.org/licenses/GPL-3.0-or-later.html) permits source on another server, including a third party, with clear directions next to the binary and continued distributor responsibility for availability. This supports a fixed index for Musxi's GPL source; a homepage alone is insufficient.
- [LGPLv2.1 sections 4, 6, 7](https://spdx.org/licenses/LGPL-2.1-or-later.html) impose library/source and relinking conditions. Do not assume GPL's third-party-server wording alone resolves CEF's embedded FFmpeg distribution under LGPLv2.1.
- The publisher has selected LGPLv3 compliance for the audited embedded FFmpeg portions under their existing later-version grant. The [selection record](../licenses/CEF-Source-Method-Review.md) and original [LGPLv3](../licenses/CEF-FFmpeg-COPYING.LGPLv3)/[GPLv3](../licenses/FFmpeg-COPYING.GPLv3) texts are retained. Apply the actual combined-work/library requirements, preserve original notices and supply complete corresponding/relink materials with continued source availability. Selection does not change binary flags or other third-party grants.
- A publisher written source offer is another possible arrangement only if applicable conditions can actually be fulfilled. No offer, recipient-contact promise, upstream commitment or retention guarantee is issued here.

Check the specific copyleft library source, modifications and suitable recombination/relink materials. Full official build reconstruction, original GN/PGO/ThinLTO recovery, generated-file matching and bit-identical output are not release gates. The FFmpeg-only archive must not be described as the entire combined-work relink kit.

## Before making a binary public

### Historical source revision inclusion check (superseded by published tag)

The 2026-10-01 working-tree check found all 27 selected application build/source-delivery prerequisites present and eligible for Git, with no broken local links in the four current source/readiness records. This is an inclusion check, not a complete corresponding-source certification. At that historical check, thirteen of those prerequisites were **untracked**, so `git archive HEAD` or GitHub's current automatic source archive would omit them:

- `build-ffmpeg.ps1`, `verify-ffmpeg.ps1`, this guide and `licenses/FFmpeg-build.json`;
- `licenses/prepare-cef-windows-source.ps1`, `CEF-source.gclient`, `CEF-source-revisions.json` and `CEF-excluded-updater-test.patch` (all in `licenses/`);
- `licenses/CEF-Source-Method-Review.md`, `CEF-Source-Verification.md`, `CEF-FFmpeg-COPYING.LGPLv2.1`, `CEF-FFmpeg-COPYING.LGPLv3` and `FFmpeg-COPYING.GPLv3` (all in `licenses/`).

Include these and the reviewed existing source/script changes in the final source revision before identifying it beside a binary. Recheck the actual committed tree, not merely local file existence. Frontend/services lockfiles are already tracked. Keep downloaded SDKs, local credentials/caches and build outputs excluded; the ignored playback source/SDK archives require their own verified public delivery route. No files were staged or committed by this check.

### Publication checklist

1. Complete the source/relink sufficiency and delivery checks for the publisher-selected LGPLv3 network source-access method. Do not close the gate solely because URLs work.
2. Select the final public Musxi commit/tag containing required source, lockfiles, retrieval pins, build/install scripts and notices. Identify that revision next to the binary, not floating `main`.
3. Publish matching playback build materials as a small source asset or fixed public source repository. Preserve the VERSION change, recipe and evidence if switching from the staged asset to a repository.
4. Recheck fixed routes at publication; fresh Windows supplement retrieval and Python/CIPD bootstrap have passed, but complete Chromium tool/resource acquisition has not been repeated without caches. Retain a practical fallback if upstream removes required sources. Local copies are evidence, not a public fallback or commitment.
5. Generate a new installer only when requested; inspect current playback/CEF hashes and notices in its payload. Old BtbN installers cannot be published with new playback-source materials.
6. Put direct source directions beside the binary and retain installed source/license records. Existing installer rules copy `THIRD_PARTY_NOTICES.md` and `licenses/` recursively, but not this guide. Notices therefore contain concrete source URLs and reference installed verification records.

The fixed source ref above is selected for the authorized publication; public availability must be checked rather than inferred from the link. Source/relink sufficiency remains a bounded review item and is not certified by this index.

### Availability and fallback procedure

The publisher remains responsible for source access while the corresponding binary is available. Before making a binary public, check this fixed source ref, its required source/build/license files, and each pinned upstream route anonymously. Retain verified source archives, supplements and manifests independently of the installed application; local copies already exist in the audited workspaces. If a required route fails, pause binary downloads until equivalent exact materials are publicly served from a publisher-controlled replacement location and the directions beside the binary are updated. Recheck size/hash and source identity after relocation. This is an operating procedure, not an upstream retention guarantee, a binding written source offer, or proof of future availability.


## Existing Release source assets (2026-10-01; retained during migration)

The embedded asset `MusxiPlayer-CEF-FFmpeg-Source-2b68d2babae73714846961fb0ee47e3b3d2e39a9.tar.gz` is now an enriched package, SHA-256 `85ec17c8230fab13a3adacb960841583e82e65ea0d9a35d8b4c09645b59501a8`: the unchanged pinned fork tar, selected Chromium FFmpeg integration/generation scripts, stub tools, GN configuration, Opus source/build inputs, NASM build definitions, relevant original CEF patches, per-file hashes and fixed-revision source-access instructions. Its manifest explicitly maps to libcef.dll SHA-256 `f5021c3477a84a9c96c0b6a01a038a568fe2cdb7d294ea84aaf6cd70b26c8a09`. The original fork archive hash is retained separately; it is no longer the hash of the outer Release asset. Remaining combined-work dependencies are obtained through the exact source/dependency pins and steps in [CEF-FFmpeg-Source-Access.md](../licenses/CEF-FFmpeg-Source-Access.md), not master/main/latest. This supplement is not an independent Chromium GN project or a complete official-build reproduction claim.

The playback asset is `MusxiPlayer-FFmpeg-Source-n9.0.2-3-ga5923073bf-musxi-local-1.zip`, SHA-256 `241c0e2f4abb0f606ee6173bdb8da029d497825ea96afebf34f6f20bf5820a91`. It retains the exact source ZIP, generated VERSION metadata change, build recipe, configuration and licenses and maps only to the four playback DLLs in FFmpeg-build.json. The SDK is optional developer convenience. Both source packages remain distinct.

The currently published v0.2.0 installer records commit `0d7ba2d5741cba5d13ed0a3de089235e31dc53e4`. Future installers use package.ps1 -RequireCleanSource and identify their own exact public commit. The historical source-0.2.0-20261001 ref is not this installer's identity. This migration does not move v0.2.0, change historical commits or remove existing source assets.
