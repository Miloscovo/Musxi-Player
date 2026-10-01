# FFmpeg follow-up evidence and remaining limits

Review date: 2026-10-01. The BtbN playback-build findings below are historical after the self-built replacement; current status is recorded in Release-Readiness.md. This supplements `THIRD_PARTY_NOTICES.md`. It records evidence, not a legal clearance. No SDK was replaced, no installation package was generated, and no Git commit, push, public source upload or upstream message was performed.

## Historical BtbN binary and build evidence

- The [release API](https://api.github.com/repos/BtbN/FFmpeg-Builds/releases/tags/autobuild-2026-09-21-13-55) gives the exact Windows LGPL shared ZIP digest `a7e62ca9b34c40145a2c7482f61a78063f6c8f8dbcf17effb27e62841fa6bbd9`, matching the local archive.
- [Workflow run 35598481244](https://github.com/BtbN/FFmpeg-Builds/actions/runs/35598481244) ran on 2026-09-21 at commit `3e6685eda92f9288c15ac320139622dcedca09a4` and completed successfully. The matching [win64 LGPL shared 9.0 job](https://github.com/BtbN/FFmpeg-Builds/actions/runs/35598481244/job/106346185999) is identified. This strengthens the date/target/script chain; it does not prove individual container digests.
- The pinned `scripts.d/50-rav1e.sh` runs `cargo update cc`; the original source lock and the post-update build lock are different kinds of evidence. No post-update lock was found in the binary archive or existing source materials. The public workflow log download request returned HTTP 403; the available GitHub connector also rejected the job-log endpoint. No credentials were extracted or authorization bypass attempted.
- The pinned Dockerfiles use mutable tags; `.github/workflows/build.yml` sets `provenance: false` and cleans untagged GHCR images. A current `latest` digest would not identify the historical build and has not been substituted for it.

**Status:** binary identity, source commit, script commit and matching successful job are verified. Exact post-update Cargo resolution and historical container identity remain unavailable. These are provenance/reconstruction limits; missing a container digest alone is not proof of a license violation. An applicable corresponding-source review must still assess whether available sources and scripts are sufficient. Do not claim bit-for-bit reproduction.

## External-library notice preservation

[FFmpeg-External-NOTICES.txt](FFmpeg-External-NOTICES.txt) preserves 1,330 upstream files without rewriting their bytes. [FFmpeg-External-Materials.json](FFmpeg-External-Materials.json) records their source archives/revisions, SHA-256 values and byte offsets, including:

- 105 source references deduplicated to 92 distinct source archives;
- 14 recursive-submodule archives;
- 578 crate archives from the original librsvg/rav1e locks;
- the pinned FFmpeg core license explanation and three JPEG-derived source files.

Every archive was checked against its existing source/lock checksum. Twelve additional upstream license files were obtained at commits recorded in the relevant crate's `.cargo_vcs_info.json`. The fixed upstream files and original source bytes are indexed separately. The original source bundle was not rewritten; these additional notice files are distributed through the existing installer `licenses/` rule.

The material list is deliberately broader than a binary inventory. It contains build helpers, disabled features, other targets and host/dev crates. Static script-option matching only labels candidates; it does not establish a DLL link map, Rust feature graph, license election or complete transitive runtime inclusion. LGPL, GPL and custom texts present in this broad source collection must not all be described as shipped library licenses.

The official [Mozilla MPL-2.0 text](https://www.mozilla.org/media/MPL/2.0/index.txt) is preserved in [FFmpeg-MPL-2.0.txt](FFmpeg-MPL-2.0.txt) for `selectors 0.40.0`; its original source carries MPL-2.0 notices. The original crates declare the following licenses but did not contain a matched complete notice, and an exact-version upstream notice was not established:

| Crate | Declared license | Evidence limit |
|---|---|---|
| av-metrics 0.9.1 | MIT | No crate VCS commit; rav1e declares it as an optional binary-feature dependency, not evidence of DLL inclusion |
| block 0.1.6 | MIT | No crate VCS commit; target/use graph not reconstructed |
| difflib 0.4.0 | MIT | No crate VCS commit; target/use graph not reconstructed |
| malloc_buf 0.0.6 | MIT | No crate VCS commit; target/use graph not reconstructed |
| objc-foundation 0.1.1 | MIT | No crate VCS commit; target/use graph not reconstructed |
| objc_id 0.1.1 | MIT | No crate VCS commit; target/use graph not reconstructed |
| simd_helpers 0.1.0 | MIT | Exact upstream commit `ca1a2f84aa386d758e98f8a609d990263932fb85` has no standalone license; rav1e lists this dependency, so do not silently dismiss the issue |
| winapi-i686-pc-windows-gnu 0.4.0 | MIT/Apache-2.0 | No crate VCS commit; archive is a different target, not proof of Windows x64 DLL inclusion |
| winapi-x86_64-pc-windows-gnu 0.4.0 | MIT/Apache-2.0 | No crate VCS commit; precise target/library inclusion not established |

No latest-branch license was represented as an exact-version license. These missing notices must be resolved or reliably excluded using build/feature evidence before a comprehensive external-notice clearance can be asserted. In particular, Cargo build/proc-macro dependencies must be distinguished from code linked into the DLLs.

Inspection of `simd_helpers` confirms `proc-macro = true`: its only exported macro adds `cfg_attr(target_arch, cold)` to the caller's supplied item. It is a compiler-side attribute helper, not a runtime library that should automatically enter the binary license inventory. Its exact source and original MIT declaration are retained; this narrows the missing-text issue to source/build-material review rather than evidence that a missing MIT notice belongs to the shipped DLLs. An attempted match of the two winapi import-library crates against the official winapi 0.3.9 tree did not establish identical packaged files, so that tentative exact-version attribution was not accepted.

The source collection also preserves AMD AMF's standards/patent disclaimer, LCEVC's Clear BSD terms, OpenH264's BSD terms, LGPL library terms, and available nested notices. Recording copyright licenses is not a patent grant. No independent patent clearance is asserted.

This software is based in part on the work of the Independent JPEG Group. The original pinned FFmpeg JPEG-derived files are preserved in the external-notice collection. Musxi makes no modifications to these downloaded FFmpeg DLLs; this does not assert that upstream FFmpeg never modified its JPEG-derived code.

## CEF configuration and a source/relink route

The exact Chromium FFmpeg tree at `2b68d2babae73714846961fb0ee47e3b3d2e39a9` contains these Windows x64 headers:

- `chromium/config/Chromium/win/x64/config.h`
- `chromium/config/Chrome/win/x64/config.h`

Both explicitly set `CONFIG_GPL`, `CONFIG_NONFREE`, `CONFIG_VERSION3` and `CONFIG_GPLV3` to `0`. `ffmpeg_options.gni` restricts supported branding to Chromium or Chrome. These source configurations reinforce the LGPL-2.1-or-later standard-build finding even though Chrome branding additionally enables AAC/H.264. Proprietary codec branding and GPL licensing are separate questions. The configuration string is intentionally elided from the binary, and no usable configuration string was found in the audited `libcef.dll`; the original binary's complete GN arguments remain unproven.

[CEF-FFmpeg-CREDITS.txt](CEF-FFmpeg-CREDITS.txt) and [CEF-FFmpeg-LICENSE.md](CEF-FFmpeg-LICENSE.md) are verbatim files from that exact Chromium FFmpeg archive, supplementing the already preserved LGPLv2.1 text and full CEF/Chromium credits.

Pinned source inputs for a recipient rebuilding the library:

| Input | Fixed revision/source |
|---|---|
| CEF | [`708dc140cbc3286826a8abef89dc23a44ff9ea72`](https://github.com/chromiumembedded/cef/tree/708dc140cbc3286826a8abef89dc23a44ff9ea72), including `patch/` and `tools/` |
| Chromium | [`79460ebecaa5625e57a5fb679a735659e73dc687`](https://chromium.googlesource.com/chromium/src/+/79460ebecaa5625e57a5fb679a735659e73dc687/), tag `152.0.7977.83`; its DEPS supplies the nested source revisions |
| CEF compatibility file | [`CHROMIUM_BUILD_COMPATIBILITY.txt`](https://raw.githubusercontent.com/chromiumembedded/cef/708dc140cbc3286826a8abef89dc23a44ff9ea72/CHROMIUM_BUILD_COMPATIBILITY.txt), explicitly selecting `refs/tags/152.0.7977.83` |
| Source acquisition helper | [`tools/automate/automate-git.py`](https://raw.githubusercontent.com/chromiumembedded/cef/708dc140cbc3286826a8abef89dc23a44ff9ea72/tools/automate/automate-git.py); supports `--checkout` and `--chromium-checkout` |
| Embedded FFmpeg | Exact Chromium fork above, with `BUILD.gn`, generated platform config, downstream patches and `CREDITS.chromium`; last upstream merge recorded in README.chromium: `a5e6c0175a7245fc1a9f4639da2810156128aa8b` |

The fixed helper can acquire the matching trees, for example:

```powershell
python automate-git.py --download-dir=D:\cef-rebuild --branch=7977 --checkout=708dc140cbc3286826a8abef89dc23a44ff9ea72 --chromium-checkout=refs/tags/152.0.7977.83 --no-chromium-history --no-depot-tools-update --no-build --no-distrib
```

Follow the [official CEF build instructions](https://chromiumembedded.github.io/cef/branches_and_building). Inspect the resulting CEF patches, Chromium DEPS and FFmpeg revision before building. The subsequent [source verification](CEF-Source-Verification.md) completed full CEF/Chromium dependency synchronization, official patch application and GN generation with a documented test-only download exclusion. CEF compilation has not completed; the new validation arguments are not the official binary's historical arguments.

The application loads the shared `libcef.dll`; a recipient can rebuild CEF at compatible interfaces with a modified embedded FFmpeg and replace the compatible CEF runtime set. The four BtbN DLLs cannot replace this embedded copy. The SDK's `libcef.lib` is an import library, not the full relinkable Chromium object set. Exact main source links plus an FFmpeg-only tarball must not be represented as a complete offline CEF rebuild kit. A successful modified-library rebuild/replacement test and corresponding-source sufficiency review remain outstanding.

## Public source availability and the release boundary

The anonymous [Musxi release API](https://api.github.com/repos/Miloscovo/Musxi-Player/releases) returned only published `v0.1.0`, with an installer asset. It did not expose a published 0.2.0 release or either prepared source asset. This finding concerns the current 0.2.0 materials; it does not infer that the old 0.1.0 installer used FFmpeg. Draft/private release contents were not inspected.

The local materials are ready to stage but are **not confirmed publicly available**. Before publishing the current installer:

1. Resolve the missing external notice/runtime-inclusion evidence, build-lock/container provenance as needed, and CEF source/relink sufficiency.
2. Publish the two matching source assets through the chosen license distribution method, or prepare and verify an equivalent public exact-source repository with all required build/dependency material. A source repository homepage alone is not sufficient.
3. Put concrete source links and FFmpeg license information in the application download/release description. Verify anonymous access, asset versions and checksums.
4. Regenerate the installer after these notice updates, inspect its clean payload and matching application Corresponding Source, and verify that its license/source materials match the published release.

This follow-up did not publish materials because the task explicitly retains the review-before-commit/push boundary. An upstream supplier record or actual rebuild is needed for facts that public/source evidence cannot reconstruct; a publisher's assertion alone cannot recover missing technical data.

For the existing companion-release-asset method, `verify-ffmpeg.ps1 -FfmpegRoot <SDK> -PublicReleaseTag <published-tag>` now performs an anonymous read-only check that both expected source assets exist and their GitHub-reported SHA-256 digests match the pinned materials. It does not upload, modify a release, or replace the complete corresponding-source sufficiency review. This check is optional because packaging prepares assets before publication and other valid source distribution methods may be used.
