# CEF binary redistribution and source delivery

Scope confirmed by the publisher on 2026-10-01. This record supersedes earlier
requests to reproduce the complete official CEF build. It does not change any
third-party license or waive obligations of components incorporated into CEF.

## A. CEF BSD binary materials

CEF `152.0.6+g708dc14`, commit
`708dc140cbc3286826a8abef89dc23a44ff9ea72`, bundles Chromium
`152.0.7977.83` / `79460ebecaa5625e57a5fb679a735659e73dc687`.
The current Standard Windows x64 distribution supplies `LICENSE.txt` and
`CREDITS.html`. Their verbatim installed copies are
`licenses/CEF-LICENSE.txt` and `licenses/CEF-Chromium-CREDITS.html`;
the installer also preserves the legacy root `cef-license.txt` copy.
`FFmpeg-build.json` pins their SHA-256 alongside the actual libcef.dll identity.
`verify-ffmpeg.ps1` checks repository, configured SDK and packaged runtime copies.

CEF's BSD binary condition requires copyright, conditions and disclaimer in
materials accompanying the binary. It does not require a CEF SDK/source
attachment, a Chromium checkout, or rebuilding/reproducing the official binary.
No official PDB, complete GN args, PGO/ThinLTO environment, or generated-file
comparison is a release gate.

## B. Chromium notices and component-specific duties

Keep the exact distribution's complete generated credits, not a hand-written
replacement. CEF runtime DLLs, resources, locales, ICU/V8 data and bootstrap are
selected by existing CMake/Inno rules. d3dcompiler_47.dll is excluded from the
installer; other component license notices remain intact. See
[Windows payload audit](CEF-Windows-Build-Audit.md) for individual identities.
The cross-platform credits list is not proof that every listed library occurs
in this Windows binary. Evaluate actual incorporated copyleft components
separately; CEF BSD does not override their LGPL/GPL/MPL or notice conditions.

## C. Chromium embedded FFmpeg

This is code inside libcef.dll, not the four playback DLLs. Chromium's fixed
DEPS/source map identifies revision
`2b68d2babae73714846961fb0ee47e3b3d2e39a9`:
https://chromium.googlesource.com/chromium/third_party/ffmpeg/+/2b68d2babae73714846961fb0ee47e3b3d2e39a9/.
The original grant is LGPL-2.1-or-later; the publisher previously selected the
permitted LGPLv3 route. Keep original LICENSE.md, CREDITS and LGPLv2.1, plus
LGPLv3 and its incorporated GPLv3 text. This election does not relicense CEF.
Both pinned Windows x64 branding configs disable GPL/nonfree/version3/GPLv3.
This is source-configuration evidence, not recovery of every historical flag.

The verified 18,560,293-byte source archive has SHA-256
`44e2a979772d63070dd354d22e32aecd209ec5689ea738c8ef81185fee3ebcfb`.
Packaging keeps it as
`MusxiPlayer-CEF-FFmpeg-Source-2b68d2babae73714846961fb0ee47e3b3d2e39a9.tar.gz`
with a checksum. It preserves Chromium's FFmpeg modifications, patches,
README.chromium, BUILD.gn, generated configuration/build rules and licenses.
No unmodified upstream FFmpeg tarball is substituted for this fork.

Retained limited corroboration: 186 observed FFmpeg source records in the
matched official libcef PDB agreed byte-for-byte with the pinned source;
the local FFmpeg target compiled its 476 actions. Neither observation is a
full official-build reproduction or a release prerequisite.

**Sufficiency boundary:** this archive supplies the uncombined FFmpeg tree and
its Chromium modifications. It is not by itself the entire combined-work
recombination/relink kit. Preserve the fixed dependency/source inventory,
Chromium/CEF build-rule and recipient replacement directions in
[the source index](../docs/release-source-delivery.md), and the existing
[method review](CEF-Source-Method-Review.md). LGPLv3 sections 4/5 must be applied
to the actual arrangement; if necessary materials or working public source
access are missing, they remain a delivery issue. No requirement to obtain
bit-identical output or to reconstruct the official whole-build environment
is imposed by this audit. Technical inventory checks are not legal clearance.

## D. Independent playback FFmpeg

Musxi still dynamically links avcodec-63.dll, avformat-63.dll, avutil-61.dll and
swresample-7.dll from `n9.0.2-3-ga5923073bf-musxi-local-1`, source
`a5923073bfd8f25b7300d93af3f8e690174ebd30`. It is LGPL-2.1-or-later, shared,
with GPL/nonfree/version3/autodetected external codecs disabled. Preserve
FFmpeg-LICENSE.txt / FFmpeg-LICENSE.md, the exact upstream ZIP, VERSION metadata
change, build-ffmpeg.ps1, and complete configuration in FFmpeg-build.json.
Compatible DLL replacement remains supported; publisher hash gates are not
installed-app restrictions. The separately staged 21,469,445-byte
`MusxiPlayer-FFmpeg-Source-Materials-0.2.zip` and checksum are retained.
These two FFmpeg archives have different revisions, modifications and binary
objects; they are not duplicates and must not be merged.

## E. Release files and source access

- Installer and SHA-256 sidecar. Licenses/notices are inside it.
- Fixed matching Musxi GPL source revision, with build/install scripts and
  lockfiles, explicitly identified beside the binary download. Current edits
  are not in the earlier immutable source tag; publish a reviewed revision
  before distributing the newly packaged material. No publication occurs here.
- Both FFmpeg source bundles/checksums, **or** verified fixed public source and
  build-material directions under the applicable chosen delivery method.
  Merely staging local files is not public delivery. Preserve access to required
  embedded recombination/relink materials and maintain source availability.
- The playback FFmpeg SDK ZIP is optional developer convenience, not a mandatory
  ordinary Release asset. A release-specific source index is recommended.

Do not upload full CEF/Chromium archives, complete SDKs/checkouts, PDB/tools/PGO
downloads or experimental build-evidence scripts as default Release assets.
Required copyleft material access is distinct from uploading those large files
as attachments; fixed upstream access can be used where the selected terms
permit it, with distributor responsibility for continued access.

## F. Removed experiments and retained records

The uncommitted CEF-Official-Build-Evidence.md/.json and
verify-cef-pdb-sources.py are removed from licenses/ so recursive packaging
cannot include them. The useful FFmpeg correspondence result is retained above.
Existing E:/CEF-Official-Build-Evidence files are left as inactive local
experiments outside repository/package inputs, not maintained release tools.
Existing source preparation recipes remain recipient assistance for embedded
copyleft material access; they do not initiate a CEF build in release checks.

Primary terms:
[LGPLv3 sections 4/5](https://spdx.org/licenses/LGPL-3.0-or-later.html),
[GPLv3 section 6(d)](https://spdx.org/licenses/GPL-3.0-or-later.html).
The original distribution license texts are preserved verbatim.

## Current Release source assets (2026-10-01)

The embedded asset `MusxiPlayer-CEF-FFmpeg-Source-2b68d2babae73714846961fb0ee47e3b3d2e39a9.tar.gz` is now an enriched package, SHA-256 `85ec17c8230fab13a3adacb960841583e82e65ea0d9a35d8b4c09645b59501a8`: the unchanged pinned fork tar, selected Chromium FFmpeg integration/generation scripts, stub tools, GN configuration, Opus source/build inputs, NASM build definitions, relevant original CEF patches, per-file hashes and fixed-revision source-access instructions. Its manifest explicitly maps to libcef.dll SHA-256 `f5021c3477a84a9c96c0b6a01a038a568fe2cdb7d294ea84aaf6cd70b26c8a09`. The original fork archive hash is retained separately; it is no longer the hash of the outer Release asset. Remaining combined-work dependencies are obtained through the exact source/dependency pins and steps in [CEF-FFmpeg-Source-Access.md](CEF-FFmpeg-Source-Access.md), not master/main/latest. This supplement is not an independent Chromium GN project or a complete official-build reproduction claim.

The playback asset is `MusxiPlayer-FFmpeg-Source-n9.0.2-3-ga5923073bf-musxi-local-1.zip`, SHA-256 `241c0e2f4abb0f606ee6173bdb8da029d497825ea96afebf34f6f20bf5820a91`. It retains the exact source ZIP, generated VERSION metadata change, build recipe, configuration and licenses and maps only to the four playback DLLs in FFmpeg-build.json. The SDK is optional developer convenience. Both source packages remain distinct.

Build the final installer with package.ps1 -RequireCleanSource after committing the reviewed changes. The installed release-source-record.json identifies its exact Git commit and clean-source status. Publish that matching commit before binary distribution; the historical source-0.2.0-20261001 / v0.2.0 tags do not identify this revised installer and must not be moved. This finalization does not create tags, push, or upload assets.
