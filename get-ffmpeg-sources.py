"""Retrieve pinned FFmpeg source materials without Release attachments or a Chromium checkout."""
import argparse
import base64
from concurrent.futures import ThreadPoolExecutor
import hashlib
import http.client
import json
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parent


def digest(data):
    return hashlib.sha256(data).hexdigest()


def fetch(url, encoding=None):
    for attempt in range(4):
        try:
            with urllib.request.urlopen(url, timeout=60) as response:
                data = response.read()
            return base64.b64decode(data) if encoding == 'gitiles-base64' else data
        except (OSError, http.client.HTTPException):
            if attempt == 3:
                raise
            time.sleep(2 ** attempt)


def checked_write(path, data, expected):
    # Git/Windows checkouts may change line endings; retain the published bytes.
    if digest(data) != expected:
        lf = data.replace(b'\r\n', b'\n')
        for candidate in (lf, lf.replace(b'\n', b'\r\n')):
            if digest(candidate) == expected:
                data = candidate
                break
    if digest(data) != expected:
        raise ValueError(f'Source checksum mismatch: {path}')
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)


def validate_index(index, audit):
    cef = audit['cef']
    if (index['binary'] != {'file': 'libcef.dll', 'sha256': cef['libcef_sha256']}
            or index['cef_revision'] != '708dc140cbc3286826a8abef89dc23a44ff9ea72'
            or index['chromium_revision'] != '79460ebecaa5625e57a5fb679a735659e73dc687'
            or index['ffmpeg_revision'] != cef['ffmpeg_commit']):
        raise ValueError('Embedded binary/source mapping mismatch')
    for name in index['files']:
        if name.startswith('/') or ':' in name or '\\' in name or '..' in name.split('/'):
            raise ValueError(f'Unsafe source path: {name}')
    fork = index['files'][index['fork_archive']]
    if fork['sha256'] != cef['source_archive_sha256']:
        raise ValueError('Embedded fork checksum mismatch')


def embedded(output):
    index = json.loads((ROOT / 'licenses/CEF-FFmpeg-source-index.json').read_text(encoding='utf-8'))
    validate_index(index, json.loads((ROOT / 'licenses/FFmpeg-build.json').read_text(encoding='utf-8')))
    output.mkdir(parents=True, exist_ok=True)
    source = output / 'chromium-build'
    # An independent empty Git repository prevents git apply from inheriting a parent repository.
    if not (source / '.git').exists():
        subprocess.run(['git', 'init', str(source)], check=True, stdout=subprocess.DEVNULL)

    def acquire(item):
        name, entry = item
        destination = output / name
        if not entry.get('patched') and destination.exists() and digest(destination.read_bytes()) == entry['sha256']:
            return
        if 'git_archive' in entry:
            repo = output / '.fork-git'
            if not repo.exists():
                subprocess.run(['git', 'init', '--bare', str(repo)], check=True, stdout=subprocess.DEVNULL)
            pin = entry['git_archive']
            subprocess.run(['git', '-C', str(repo), 'fetch', '--depth=1', pin['url'], pin['revision']], check=True)
            actual = subprocess.check_output(['git', '-C', str(repo), 'rev-parse', 'FETCH_HEAD'], text=True).strip()
            if actual != pin['revision']:
                raise ValueError('FFmpeg fetched revision mismatch')
            destination.parent.mkdir(parents=True, exist_ok=True)
            subprocess.run(['git', '-C', str(repo), 'archive', '--format=tar.gz', f'--output={destination.resolve()}', actual], check=True)
            checked_write(destination, destination.read_bytes(), entry['sha256'])
            return
        if 'literal' in entry:
            data = entry['literal'].encode()
        else:
            try:
                data = fetch(entry['url'], entry.get('encoding'))
            except (OSError, http.client.HTTPException):
                if 'fallback_url' not in entry:
                    raise
                data = fetch(entry['fallback_url'], 'gitiles-base64')
        if entry.get('patched'):
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(data.replace(b'\r\n', b'\n'))
        else:
            checked_write(destination, data, entry['sha256'])

    with ThreadPoolExecutor(max_workers=6) as pool:
        list(pool.map(acquire, index['files'].items()))
    # Apply only patches to selected files, never the entire Chromium source tree.
    for number, patch in enumerate(index['patches']):
        if 'file' in patch:
            path = output / patch['file']
        else:
            path = output / '.patches' / f'{number}.patch'
            checked_write(path, fetch(patch['url']), patch['sha256'])
        normalized = output / '.patches' / f'{number}-lf.patch'
        normalized.parent.mkdir(parents=True, exist_ok=True)
        normalized.write_bytes(path.read_bytes().replace(b'\r\n', b'\n'))
        subprocess.run(['git', '-c', 'core.autocrlf=false', '-C', str(source), 'apply',
                        f"-p{patch['strip']}", *[f'--include={p}' for p in patch['include']],
                        str(normalized.resolve())], check=True)
    for name, entry in index['files'].items():
        path = output / name
        checked_write(path, path.read_bytes(), entry['sha256'])
    manifest = {key: value for key, value in index.items() if key not in ('patches', 'scope', 'files')}
    manifest['files'] = {name: entry['sha256'] for name, entry in index['files'].items()}
    (output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    print(f"Verified embedded FFmpeg: {len(index['files'])} files; libcef.dll {index['binary']['sha256']}")


def playback(output):
    # These immutable public files describe the already released playback DLLs.
    revision = '0d7ba2d5741cba5d13ed0a3de089235e31dc53e4'
    base = f'https://raw.githubusercontent.com/Miloscovo/Musxi-Player/{revision}/'
    raw = fetch(base + 'licenses/FFmpeg-build.json')
    checked_write(output / 'FFmpeg-build.json', raw,
                  '49dc9ab18c6484922b677890a85a4beff4e1130f7e1ffc05cd200707a93a99c2')
    audit = json.loads(raw)
    local = json.loads((ROOT / 'licenses/FFmpeg-build.json').read_text(encoding='utf-8'))
    for key in ('version', 'ffmpeg_commit', 'files', 'build_script_sha256', 'source_archive_sha256', 'configuration'):
        if audit[key] != local[key]:
            raise ValueError('Playback binary/source mapping mismatch')
    checked_write(output / 'build-ffmpeg.ps1', fetch(base + audit['build_script']), audit['build_script_sha256'])
    archive = fetch('https://api.github.com/repos/FFmpeg/FFmpeg/zipball/' + audit['ffmpeg_commit'])
    checked_write(output / 'FFmpeg-source.zip', archive, audit['source_archive_sha256'])
    # The upstream ZIP has no VERSION. The public recipe adds exactly this file.
    (output / 'VERSION').write_bytes(b'n9.0.2-3-ga5923073bf\n')
    (output / 'SOURCE-ACCESS.txt').write_text(
        'Use build-ffmpeg.ps1 -SourceArchive FFmpeg-source.zip with the documented MSVC/Bash/Make/NASM tools.\n'
        'The recipe writes VERSION into the extracted source and supplies --extra-version=musxi-local-1.\n'
        'Generated config/version headers and build logs are regenerated, not unpublished source patches.\n'
        f'Application source: https://github.com/Miloscovo/Musxi-Player/tree/{revision}\n'
        'Full configuration and the four distributed DLL hashes: FFmpeg-build.json.\n', encoding='utf-8')
    print(f"Verified playback FFmpeg: {audit['version']}; source {audit['ffmpeg_commit']}")


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--component', choices=('embedded', 'playback', 'all'), required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    if output.exists() and any(output.iterdir()):
        parser.error('Use a new empty output directory; existing files are never overwritten.')
    if args.component == 'all':
        playback(output / 'playback')
        embedded(output / 'embedded')
    elif args.component == 'embedded':
        embedded(output)
    else:
        playback(output)
