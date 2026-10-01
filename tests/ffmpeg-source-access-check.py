"""Reject changed source bytes, wrong binary mappings and unsafe output paths."""
import copy
import json
from pathlib import Path
import runpy
import tempfile

root = Path(__file__).resolve().parents[1]
code = runpy.run_path(str(root / 'get-ffmpeg-sources.py'))
index = json.loads((root / 'licenses/CEF-FFmpeg-source-index.json').read_text())
audit = json.loads((root / 'licenses/FFmpeg-build.json').read_text())
code['validate_index'](index, audit)
for kind in ('binary', 'path', 'fork'):
    bad = copy.deepcopy(index)
    if kind == 'binary':
        bad['binary']['sha256'] = '0' * 64
    elif kind == 'path':
        bad['files']['../outside'] = {'sha256': '0' * 64}
    else:
        bad['files'][bad['fork_archive']]['sha256'] = '0' * 64
    try:
        code['validate_index'](bad, audit)
    except ValueError:
        pass
    else:
        raise AssertionError('Invalid source index accepted: ' + kind)
with tempfile.TemporaryDirectory() as folder:
    path = Path(folder) / 'source.txt'
    data = b'original source\n'
    expected = code['digest'](data)
    code['checked_write'](path, b'original source\r\n', expected)
    assert path.read_bytes() == data
    try:
        code['checked_write'](path, b'changed source\n', expected)
    except ValueError:
        pass
    else:
        raise AssertionError('Changed source bytes accepted')
    assert path.read_bytes() == data
print('PASS: content hashes, binary mapping, fork identity and safe paths enforced')
