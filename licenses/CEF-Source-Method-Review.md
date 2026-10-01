# CEF embedded FFmpeg: source-distribution method review

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

- Confirm suitability/completeness of the provided materials for the actual official libcef.dll, including the missing historical Windows build arguments and any source/build differences. Successful preparation and matching revisions are evidence, not proof of this conclusion. Bit-identical rebuilding is not stated as a universal license prerequisite; absence of a full rebuild alone should not be confused with missing corresponding source.
- Confirm the applicable LGPLv3 combined-work/combined-library requirements and the actual portions covered by the later-version grant. The selected version does not waive any requirement.
- Select a final source revision, publicly deliver the referenced materials, verify anonymous access at publication, and establish ongoing availability. None of these publication actions happened here.
- If the selected route cannot be fulfilled, stop binary publication and review an alternative under LGPLv2.1 sections 4/6/7 using a suitable source-access arrangement, or a publisher written offer only if all applicable conditions can be fulfilled. No binding offer is issued. A huge custom Release attachment is not assumed to be the only possible arrangement.

Primary texts: [LGPLv2.1 section 13](https://spdx.org/licenses/LGPL-2.1-or-later.html), [LGPLv3 sections 4/5](https://spdx.org/licenses/LGPL-3.0-or-later.html), [GPLv3 section 6(d)](https://spdx.org/licenses/GPL-3.0-or-later.html). Technical results: [CEF-Source-Verification.md](CEF-Source-Verification.md). Source index: [repository proposal](../docs/release-source-delivery.md); that guide is not installed by current installer rules.
