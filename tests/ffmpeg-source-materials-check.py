"""One positive archive check and rejection of a wrong binary/source mapping."""
import io
import json
from pathlib import Path
import runpy
import tarfile
import tempfile

root = Path(__file__).resolve().parents[1]
verify = runpy.run_path(str(root / 'prepare-ffmpeg-sources.py'))['verify']
audit = json.loads((root / 'licenses/FFmpeg-build.json').read_text())
archive = root / audit['cef']['source_materials_path']
verify(archive, audit)
with tarfile.open(archive) as source:
    manifest = json.load(source.extractfile('manifest.json'))
manifest['binary']['sha256'] = '0' * 64
with tempfile.TemporaryDirectory() as folder:
    bad = Path(folder) / 'wrong-source.tar.gz'
    with tarfile.open(bad, 'w:gz') as target:
        data = json.dumps(manifest).encode()
        entry = tarfile.TarInfo('manifest.json'); entry.size = len(data)
        target.addfile(entry, io.BytesIO(data))
    try:
        verify(bad, audit)
    except AssertionError:
        pass
    else:
        raise AssertionError('Wrong embedded binary mapping was accepted')
print('PASS: valid embedded materials accepted, wrong binary correspondence rejected')
