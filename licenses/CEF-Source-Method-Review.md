# CEF embedded FFmpeg: source-distribution method review

Current redistribution scope: [CEF-Redistribution-Audit.md](CEF-Redistribution-Audit.md). CEF BSD requires matching license/credits; full official-build reconstruction is not required. Retained preparation directions concern embedded FFmpeg copyleft obligations only.

Date: 2026-10-01. **Publisher confirmed the LGPLv3 compliance route on 2026-10-01. This records the selection, not source delivery, a written offer or release clearance.** Original project and third-party license texts, copyrights, runtime DLLs and build flags are unchanged.

## Established evidence

- The fixed embedded FFmpeg notice [LICENSE.md](CEF-FFmpeg-LICENSE.md) states LGPL version 2.1 or later for the library, with separately described optional/exempt files. The fixed source configuration disables GPL/nonfree/version3/GPLv3. This is source/build evidence; the historical full official Windows DLL arguments remain unavailable.
- The original [LGPLv2.1 text](CEF-FFmpeg-COPYING.LGPLv2.1), section 13, allows following a later published LGPL version when the grant says 'or later'. Version selection must be distinguished from enabling version3-only source/features through FFmpeg configure.
- The pinned Chromium build rules embed FFmpeg in the non-component library configuration. Loading libcef.dll dynamically does not make its internal FFmpeg a separately replaceable DLL. A proposal must cover recombination/relinking of the relevant combined library/work, not only replacement of the four playback DLLs.
- Complete pinned FFmpeg files are verified in the Google Chromium archive; CEF and four Windows source/tool supplements are fixed. Preparation and Ninja dry-run passed. A compiler/linker execution and modified-library replacement test have not been performed, consistently with the no-full-rebuild scope.

## Selected route and remaining legal/source review

The publisher confirmed using the LGPLv3 terms permitted by the existing LGPL-2.1-or-later grant for the audited embedded FFmpeg portions. This selection is recorded in THIRD_PARTY_NOTICES.md and FFmpeg-build.json. Keep the original grants, license explanations, notices and per-file exceptions intact. This does not relicense Chromium/CEF or convert all third-party components to GPL.

For a Combined Work, evaluate LGPLv3 section 4(d)(0): provide the required corresponding library/application materials suitable for recombination/relinking, using GPLv3 section 6's source access method. Also evaluate section 5 if the actual library arrangement falls under Combined Libraries; do not assert that one provision automatically excludes the other.

For network binary distribution, GPLv3 section 6(d) expressly permits corresponding source on a different server, including one operated by a third party, if clear source directions are maintained next to the binary and equivalent access is provided. The distributor remains responsible for required availability. This is the reason to consider later-version terms, rather than assuming the same wording exists in LGPLv2.1.

Required publication content remains:

1. A final fixed Musxi source commit/tag with build/install scripts, lockfiles, this review outcome and the source inventory/recipe.
2. Direct fixed CEF and Google Chromium archive links and hashes, the four Windows supplement repository/commit pins, original patches, dependency inventory and documented preparation/build instructions.

   The [recipient preparation script](prepare-cef-windows-source.ps1) is now included with the license/source records. CEF version metadata also requires full history and fixed `origin/master` / `origin/7977` refs recorded in the guide; archive contents or a shallow CEF checkout alone are insufficient for the validated recipe. This executable material helps validate the route, but does not recover original Windows DLL build inputs or establish complete corresponding-source/relink sufficiency.
3. The original uncombined embedded FFmpeg source and notices, identified by commit; provide its staged source archive or another verified fixed complete-tree route as applicable. The source-only archive does not replace the combined-work materials.
4. Matching playback FFmpeg source/build materials, including its generated VERSION change; these cover a separate library set.
5. Copies of [LGPLv3](CEF-FFmpeg-COPYING.LGPLv3) and its incorporated [GPLv3](FFmpeg-COPYING.GPLv3) are now retained verbatim from the pinned embedded FFmpeg source, alongside LGPLv2.1/original notices. The existing BtbN LGPLv3 material is a historical record, not a declaration selecting these terms for CEF.
6. A practical source-availability maintenance/fallback arrangement owned by the publisher. Neither a local backup nor an upstream link alone is a guarantee of future public delivery.

### Publisher-selected statement

> For the audited FFmpeg portions incorporated into this CEF distribution that are licensed under LGPL-2.1-or-later, this distribution follows the LGPL version 3 terms allowed by that grant. Original third-party grants and notices are preserved. Corresponding source and recombination/relink materials are identified by the fixed release-specific source index accompanying the binary download, using the network source-access method in GPLv3 section 6(d) as incorporated by LGPLv3. This statement does not change the licenses of CEF, Chromium or other components.

The license-term selection above is implemented locally. Do not publish a binary with unresolved source/tag links or missing materials, or treat this statement as certification of the current package. The distribution method still requires complete suitable source/relink materials and sustained public access.

## Remaining review and alternatives

- Confirm suitability/completeness of the provided materials for the actual official libcef.dll, including the specific library modifications, build rules and recombination/relink material access. Recovering every historical GN argument, optimization profile or byte-identical official build is not a release requirement. Successful preparation and matching revisions are evidence, not proof of this conclusion. Bit-identical rebuilding is not stated as a universal license prerequisite; absence of a full rebuild alone should not be confused with missing corresponding source.
- Confirm the applicable LGPLv3 combined-work/combined-library requirements and the actual portions covered by the later-version grant. The selected version does not waive any requirement.
- Select a final source revision, publicly deliver the referenced materials, verify anonymous access at publication, and establish ongoing availability. None of these publication actions happened here.
- If the selected route cannot be fulfilled, stop binary publication and review an alternative under LGPLv2.1 sections 4/6/7 using a suitable source-access arrangement, or a publisher written offer only if all applicable conditions can be fulfilled. No binding offer is issued. A huge custom Release attachment is not assumed to be the only possible arrangement.

Primary texts: [LGPLv2.1 section 13](https://spdx.org/licenses/LGPL-2.1-or-later.html), [LGPLv3 sections 4/5](https://spdx.org/licenses/LGPL-3.0-or-later.html), [GPLv3 section 6(d)](https://spdx.org/licenses/GPL-3.0-or-later.html). Technical results: [CEF-Source-Verification.md](CEF-Source-Verification.md). Source index: [repository proposal](../docs/release-source-delivery.md); that guide is not installed by current installer rules.

## Current Release source assets (2026-10-01)

The embedded asset `MusxiPlayer-CEF-FFmpeg-Source-2b68d2babae73714846961fb0ee47e3b3d2e39a9.tar.gz` is now an enriched package, SHA-256 `85ec17c8230fab13a3adacb960841583e82e65ea0d9a35d8b4c09645b59501a8`: the unchanged pinned fork tar, selected Chromium FFmpeg integration/generation scripts, stub tools, GN configuration, Opus source/build inputs, NASM build definitions, relevant original CEF patches, per-file hashes and fixed-revision source-access instructions. Its manifest explicitly maps to libcef.dll SHA-256 `f5021c3477a84a9c96c0b6a01a038a568fe2cdb7d294ea84aaf6cd70b26c8a09`. The original fork archive hash is retained separately; it is no longer the hash of the outer Release asset. Remaining combined-work dependencies are obtained through the exact source/dependency pins and steps in [CEF-FFmpeg-Source-Access.md](CEF-FFmpeg-Source-Access.md), not master/main/latest. This supplement is not an independent Chromium GN project or a complete official-build reproduction claim.

The playback asset is `MusxiPlayer-FFmpeg-Source-n9.0.2-3-ga5923073bf-musxi-local-1.zip`, SHA-256 `241c0e2f4abb0f606ee6173bdb8da029d497825ea96afebf34f6f20bf5820a91`. It retains the exact source ZIP, generated VERSION metadata change, build recipe, configuration and licenses and maps only to the four playback DLLs in FFmpeg-build.json. The SDK is optional developer convenience. Both source packages remain distinct.

Build the final installer with package.ps1 -RequireCleanSource after committing the reviewed changes. The installed release-source-record.json identifies its exact Git commit and clean-source status. Publish that matching commit before binary distribution; the historical source-0.2.0-20261001 / v0.2.0 tags do not identify this revised installer and must not be moved. This finalization does not create tags, push, or upload assets.
