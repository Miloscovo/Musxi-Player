# Embedded FFmpeg source and build material access

This archive corresponds to libcef.dll from CEF 152.0.6+g708dc14 / Chromium
152.0.7977.83, SHA-256
f5021c3477a84a9c96c0b6a01a038a568fe2cdb7d294ea84aaf6cd70b26c8a09.
It is unrelated to Musxi's four independent playback FFmpeg DLLs.

1. Extract the outer source archive. Check manifest.json and each file hash with
   `python prepare-ffmpeg-sources.py --archive <archive>` from the matching
   Musxi source revision. Extract upstream/chromium-ffmpeg-*.tar.gz separately.
   This is the modified Chromium FFmpeg fork, not an unmodified upstream tarball.
2. The fork is fixed at
   https://chromium.googlesource.com/chromium/third_party/ffmpeg/+/2b68d2babae73714846961fb0ee47e3b3d2e39a9/.
   README.chromium records upstream merge
   a5e6c0175a7245fc1a9f4639da2810156128aa8b. Original patched code, Chromium
   configs, generated source lists, GN rules, license/credits and flag changes
   are preserved. Do not substitute a pristine upstream checkout.
3. chromium-build/ supplies the FFmpeg integration/generation scripts, stub
   generator, GN configuration, NASM build definitions and Opus source/build
   inputs. Relevant original CEF patches are in cef-patches/. Selected files
   reflect the already patched fixed source state; do not reapply these patches
   over the supplemented files. Per-file copyright/license headers are retained.
4. Remaining Chromium interfaces/build dependencies, if needed for recombination,
   are available by exact path at the fixed revision:
   https://chromium.googlesource.com/chromium/src/+/79460ebecaa5625e57a5fb679a735659e73dc687/.
   For a file, append its relative path and `?format=TEXT`; decode the base64
   response to recover original bytes. For a directory, `?format=JSON` lists
   entries (remove Gitiles' initial XSSI line); fetch needed files at this same
   revision. The included DEPS and licenses/CEF-source-revisions.json identify
   separately versioned repositories; never substitute branch tips.
   Generated gclient_args.gni is regenerated from DEPS by upstream preparation;
   it is not presented as a recovered official CEF build configuration.
5. CEF integration/build rules and original patch controls are pinned at
   https://github.com/chromiumembedded/cef/tree/708dc140cbc3286826a8abef89dc23a44ff9ea72.
   Retrieve needed files with
   https://raw.githubusercontent.com/chromiumembedded/cef/708dc140cbc3286826a8abef89dc23a44ff9ea72/<path>.
   NASM's separate source revision is 525a09a813be0f75b646ee93fc2a31c27b87d722
   at https://chromium.googlesource.com/chromium/deps/nasm/.
   Opus's Chromium-vendored source is included; its README records upstream
   revision 55513e81d8f606bd75d0ff773d2144e5f2a732f5. Original licenses govern
   these tools/libraries separately; not everything here is LGPL.
6. The retained recipient preparation/replacement instructions in
   docs/release-source-delivery.md identify compatible runtime replacement,
   library targets, resources, permissions and rollback. They are assistance
   for actual embedded-library recombination obligations, not a demand to
   reproduce the official CEF binary. The supplement is not advertised as a
   standalone complete Chromium GN project. Required remaining combined-work
   materials are available through the fixed source/dependency directions.

Keep LGPLv2.1/original FFmpeg notices and the publisher-selected LGPLv3/GPLv3
texts. Preserve the original upstream grants and Chromium/CEF licenses.
Publish these exact directions beside the binary download. Under the selected
network delivery method the distributor remains responsible for equivalent,
continued source access; if a required route fails, restore exact public access
before continuing binary distribution. A local backup is not public delivery.
No binding written offer or whole-build reproducibility claim is made.
