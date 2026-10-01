"""Bundle the pinned embedded fork and its small Chromium build/source supplements."""
import argparse
import gzip
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parent
CHROMIUM = '79460ebecaa5625e57a5fb679a735659e73dc687'
CEF = '708dc140cbc3286826a8abef89dc23a44ff9ea72'


def digest(data):
    return hashlib.sha256(data).hexdigest()


def verify(path, audit):
    with tarfile.open(path) as archive:
        info = json.load(archive.extractfile('manifest.json'))
        assert info['binary'] == {'file': 'libcef.dll', 'sha256': audit['cef']['libcef_sha256']}
        assert info['chromium_revision'] == CHROMIUM and info['cef_revision'] == CEF
        assert info['ffmpeg_revision'] == audit['cef']['ffmpeg_commit']
        for name, expected in info['files'].items():
            assert digest(archive.extractfile(name).read()) == expected, name
        inner = archive.extractfile(info['fork_archive']).read()
        assert digest(inner) == audit['cef']['source_archive_sha256']
        with tarfile.open(fileobj=io.BytesIO(inner)) as fork:
            for name in ('BUILD.gn', 'ffmpeg_generated.gni', 'README.chromium',
                         'chromium/patches/config_flag_changes.txt', 'COPYING.LGPLv3'):
                assert fork.extractfile(name).read(), name
            for branding in ('Chrome', 'Chromium'):
                config = fork.extractfile(f'chromium/config/{branding}/win/x64/config.h').read()
                for flag in ('GPL', 'NONFREE', 'VERSION3', 'GPLV3'):
                    assert f'#define CONFIG_{flag} 0'.encode() in config
        for name in ('chromium-build/media/ffmpeg/scripts/build_ffmpeg.py',
                     'chromium-build/build/gn_helpers.py', 'SOURCE-ACCESS.md'):
            assert name in info['files'], name
    return info


def create(source, output, audit):
    assert CHROMIUM in (source / 'build/util/LASTCHANGE').read_text()
    assert subprocess.check_output(['git', '-C', str(source / 'cef'), 'rev-parse', 'HEAD'], text=True).strip() == CEF
    inner = ROOT / f"build/cef-ffmpeg-source-materials/chromium-ffmpeg-{audit['cef']['ffmpeg_commit']}.tar.gz"
    assert digest(inner.read_bytes()) == audit['cef']['source_archive_sha256']
    paths = [source / p for p in ('LICENSE', 'AUTHORS', 'DEPS', '.gn', 'BUILD.gn',
             'build/buildflag_header.gni', 'build/write_buildflag_header.py', 'build/gn_helpers.py',
             'third_party/nasm/BUILD.gn', 'third_party/nasm/LICENSE',
             'third_party/nasm/nasm_assemble.gni', 'third_party/nasm/nasm_sources.gni',
             'third_party/nasm/README.chromium', 'third_party/opus/BUILD.gn',
             'third_party/opus/README.chromium', 'third_party/opus/DEPS',
             'third_party/opus/convert_rtcd_assembler.py')]
    for directory in ('media/ffmpeg', 'tools/generate_stubs', 'build/config', 'third_party/opus/src'):
        paths.extend(p for p in (source / directory).rglob('*') if p.is_file()
                     and not any(x in p.parts for x in ('.git', '__pycache__'))
                     and p.suffix != '.pyc' and p.name != 'gclient_args.gni')
    files = {'chromium-build/' + p.relative_to(source).as_posix(): p.read_bytes() for p in paths}
    # These are the already patched selected sources; retain the CEF patches that touch them.
    selected = {p.relative_to(source).as_posix() for p in paths}
    for patch in (source / 'cef/patch/patches').glob('*.patch'):
        text = patch.read_text(encoding='utf-8')
        if any(line[4:].split('\t')[0].removeprefix('b/') in selected
               for line in text.splitlines() if line.startswith('+++ ')):
            files['cef-patches/' + patch.name] = patch.read_bytes()
    files['cef-patches/patch.cfg'] = (source / 'cef/patch/patch.cfg').read_bytes()
    fork_name = 'upstream/' + inner.name
    files[fork_name] = inner.read_bytes()
    files['prepare-ffmpeg-sources.py'] = Path(__file__).read_bytes()
    files['licenses/embedded-binary.json'] = (json.dumps({'cef': {
        key: audit['cef'][key] for key in ('libcef_sha256', 'ffmpeg_commit', 'source_archive_sha256')
    }}, indent=2) + '\n').encode()
    files['SOURCE-ACCESS.md'] = (ROOT / 'licenses/CEF-FFmpeg-Source-Access.md').read_bytes()
    for name in ('CEF-LICENSE.txt', 'CEF-FFmpeg-LICENSE.md', 'CEF-FFmpeg-CREDITS.txt',
                 'CEF-FFmpeg-COPYING.LGPLv2.1', 'CEF-FFmpeg-COPYING.LGPLv3', 'FFmpeg-COPYING.GPLv3',
                 'CEF-source-revisions.json', 'CEF-source.gclient', 'prepare-cef-windows-source.ps1'):
        files['licenses/' + name] = (ROOT / 'licenses' / name).read_bytes()
    info = {'binary': {'file': 'libcef.dll', 'sha256': audit['cef']['libcef_sha256']},
            'cef_revision': CEF, 'chromium_revision': CHROMIUM,
            'ffmpeg_revision': audit['cef']['ffmpeg_commit'], 'fork_archive': fork_name,
            'supplements_state': 'fixed Chromium source with relevant original CEF patches applied; patches preserved',
            'files': {name: digest(data) for name, data in sorted(files.items())}}
    files['manifest.json'] = (json.dumps(info, indent=2) + '\n').encode()
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open('wb') as raw, gzip.GzipFile(fileobj=raw, mode='wb', filename='', mtime=0) as compressed, tarfile.open(fileobj=compressed, mode='w') as archive:
        for name, data in sorted(files.items()):
            entry = tarfile.TarInfo(name); entry.size = len(data); entry.mode = 0o644
            archive.addfile(entry, io.BytesIO(data))
    return verify(output, audit)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--chromium-source', type=Path, help='Existing pinned source; no download/checkout/build is performed')
    parser.add_argument('--archive', type=Path, required=True)
    args = parser.parse_args()
    metadata = ROOT / 'licenses/FFmpeg-build.json'
    if not metadata.exists():
        metadata = ROOT / 'licenses/embedded-binary.json'
    audit = json.loads(metadata.read_text())
    info = create(args.chromium_source, args.archive, audit) if args.chromium_source else verify(args.archive, audit)
    print(f"Verified embedded FFmpeg materials: {len(info['files'])} entries; binary {info['binary']['file']} {info['binary']['sha256']}")
