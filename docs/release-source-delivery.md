# Musxi 0.2.0 source delivery proposal

## Status

Prepared for publisher review on 2026-10-01; **not an unconditional legal clearance**. Keep the official CEF runtime. The publisher selected the LGPLv3 compliance route for the audited CEF-embedded FFmpeg portions on 2026-10-01, and subsequently authorized publishing matching application source and generating a new installer. No upstream contact or full CEF rebuild is selected. Source ref `source-0.2.0-20261001` identifies this packaging revision; verify its public availability before binary distribution. It is separate from the existing historical `v0.2.0` tag, which must not be moved.

The rejected large custom snapshot collection is not the selected method. Its numbered ZIP parts and manifest were never completed. Do not instruct recipients to restore those nonexistent assets. Existing local source workspaces are retained for evidence and fallback preparation.

Proposed delivery: a release-specific source index next to the binary download, identifying fixed public sources, Windows supplements, patches and build instructions. This avoids a custom full-CEF Release attachment; it does not eliminate recipients' downloads or the publisher's source obligations. The selected method's source-completeness and public availability checks remain prerequisites below.

## Fixed source index

| Material | Source and identity | Evidence / limitation |
|---|---|---|
| Musxi Player | [Fixed packaging source](https://github.com/Miloscovo/Musxi-Player/tree/source-0.2.0-20261001), ref `source-0.2.0-20261001`. | Contains application source, lockfiles, build/install scripts and referenced source/license records. Verify the published ref and archive against the final local Git tree. A homepage or floating branch alone is insufficient. |
| Playback FFmpeg | [Fixed ZIP](https://api.github.com/repos/FFmpeg/FFmpeg/zipball/a5923073bfd8f25b7300d93af3f8e690174ebd30), commit `a5923073bfd8f25b7300d93af3f8e690174ebd30`. | SHA-256 `65474f81cf8a4e2529cca6c25877fca8b6f3340a67ff804a306bbc3940cd46f6`. Use `build-ffmpeg.ps1` and [build manifest](../licenses/FFmpeg-build.json), including the generated VERSION change. |
| Playback build materials | [Fixed build recipe](https://github.com/Miloscovo/Musxi-Player/blob/source-0.2.0-20261001/build-ffmpeg.ps1), [configuration and binary/source inventory](https://github.com/Miloscovo/Musxi-Player/blob/source-0.2.0-20261001/licenses/FFmpeg-build.json), and the exact upstream source ZIP above. | The recipe records the generated VERSION change and all build flags. Compiler-check/config outputs can be regenerated from these source inputs; logs are evidence, not a substitute for source. The optional local 21,469,445-byte bundle has SHA-256 `241c0e2f4abb0f606ee6173bdb8da029d497825ea96afebf34f6f20bf5820a91` and is still staged by packaging. Publishing this fixed source/recipe route avoids requiring that bundle as a Release attachment; verify access before distribution. |
| CEF | [Fixed archive](https://codeload.github.com/chromiumembedded/cef/tar.gz/708dc140cbc3286826a8abef89dc23a44ff9ea72), commit `708dc140cbc3286826a8abef89dc23a44ff9ea72`, version `152.0.6+g708dc14`. | 3,572,838 bytes; SHA-256 `69fbd8385da491757a862c7ada70318b691cb8aee4e9ea0499a63baa90cb76e7`. All 1,881 files match Git blobs. Use fixed Git checkout/history for version generation; archive-only generation is untested. |
| Chromium and bundled dependencies | [Google archive](https://commondatastorage.googleapis.com/chromium-browser-official/chromium-152.0.7977.83.tar.xz), version `152.0.7977.83`, commit `79460ebecaa5625e57a5fb679a735659e73dc687`. | 5,916,162,416 bytes; SHA-256 `8064dd693f3eccb58d00e2f264c582b1e869c2883b28655787fe21e16e538ada`. Downloaded/inspected. Needs Windows supplements; no historical `tarball_args.gn`. |
| Embedded FFmpeg | [Fixed tree](https://chromium.googlesource.com/chromium/third_party/ffmpeg/+/2b68d2babae73714846961fb0ee47e3b3d2e39a9/), commit `2b68d2babae73714846961fb0ee47e3b3d2e39a9`. | All 11,015 files in the Google archive match Git blobs. Separate from playback FFmpeg. A FFmpeg-only archive does not replace CEF/Chromium build/relink materials. |
| Build tools / dependency inventory | [depot_tools](https://chromium.googlesource.com/chromium/tools/depot_tools.git), commit `2da9ee6f6c86332551055bc44245fab94675272e`; [inventory](../licenses/CEF-source-revisions.json). | Siso instance `TupZUdv9YTinXM5vf61WKpINxNrllerRprrKorxAVqEC`. Bootstrap/tool downloads remain necessary. A fresh anonymous depot_tools checkout and isolated Windows Python/CIPD bootstrap passed on 2026-10-01; a full cache-free Chromium dependency sync remains untested. |

Do not substitute the Spotify tarball: GET returned 403, size/hash differ, and contents were not inspected. Google is a verified alternative, not a byte-identical mirror.

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

Original Windows per-binary GN arguments remain unavailable. Their absence and the untested compile/relink route must be considered when evaluating completeness. Bit-identical rebuilding is not stated as a universal prerequisite; build preparation alone does not establish source sufficiency.

## Before making a binary public

### Source revision inclusion check

The 2026-10-01 working-tree check found all 27 selected application build/source-delivery prerequisites present and eligible for Git, with no broken local links in the four current source/readiness records. This is an inclusion check, not a complete corresponding-source certification. Thirteen of those prerequisites are still **untracked**, so `git archive HEAD` or GitHub's current automatic source archive would omit them:

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
