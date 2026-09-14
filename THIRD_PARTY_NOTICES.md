# Third-party components

Mint Player's KuGou integration uses the following components:

## KuGouMusicApi

- Author/repository: MakcRe, https://github.com/MakcRe/KuGouMusicApi
- Pinned revision: `a5a98013cce79fe0ae2ad65fc84b68176ebcfc1e` (also the API submodule revision observed in MoeKoeMusic).
- License: MIT; full upstream license is included in `services/vendor/KuGouMusicApi-a5a98013cce79fe0ae2ad65fc84b68176ebcfc1e/LICENSE`.
- Upstream source is unmodified. Mint Player invokes only a small allowlist of login, user-library and playback modules, with `platform=lite`.
- This is a third-party client integration. It does not represent an official KuGou partnership or an OpenAPI application approval. See the upstream README for the project's own usage statements.

## nlohmann/json

- Author: Niels Lohmann and contributors.
- Version: 3.12.0; https://github.com/nlohmann/json
- Copyright notice is embedded at the top of `third_party/json.hpp`; the full MIT license is in `third_party/LICENSE-json.txt`, also copied to `LICENSE-json.txt` in the portable build.

## Node.js and npm dependencies

- Node.js runtime: https://nodejs.org/ . The optional portable build copies the locally installed runtime; its accompanying license is in `build/runtime/LICENSE`.
- The adapter's exact dependency versions and package integrity values are recorded in `services/package-lock.json`.
- Individual dependency licenses are included in the corresponding `services/node_modules` packages.

The C++ UI and adapter are implemented for Mint Player. No Vue/Electron UI source from MoeKoeMusic has been copied; that project was used as an architectural reference.
