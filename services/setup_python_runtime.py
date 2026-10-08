"""Install the fixed Windows wheels without pip or executing package hooks."""
import hashlib
import json
import sys
import urllib.request
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parent
manifest = json.loads((root / 'python-runtime.json').read_text(encoding='utf-8'))
runtime = Path(sys.argv[1]).resolve()
cache = Path(sys.argv[2]).resolve()
cache.mkdir(parents=True, exist_ok=True)
site = runtime / 'Lib' / 'site-packages'
site.mkdir(parents=True, exist_ok=True)
for wheel in manifest['wheels']:
    archive = cache / wheel['filename']
    if not archive.exists() or hashlib.sha256(archive.read_bytes()).hexdigest() != wheel['sha256']:
        with urllib.request.urlopen(wheel['url'], timeout=120) as response:
            data = response.read()
        if hashlib.sha256(data).hexdigest() != wheel['sha256']:
            raise RuntimeError('Wheel checksum mismatch: ' + wheel['name'])
        archive.write_bytes(data)
    with zipfile.ZipFile(archive) as source:
        for entry in source.infolist():
            target = (site / entry.filename).resolve()
            if not target.is_relative_to(site.resolve()):
                raise RuntimeError('Unsafe wheel member')
        source.extractall(site)

# qqmusic-api-python 0.8.1 indexes each cookie by name. Niquests raises KeyError
# for an existing empty-value cookie, which QQ returns after QR confirmation.
# Snapshot the jar's actual pairs; leave the transport's domain/path jar intact.
response_source = site / 'qqmusic_api' / 'core' / 'response.py'
response_text = response_source.read_text(encoding='utf-8')
original = '''    cookies: dict[str, str] = {}
    # RequestsCookieJar 迭代产出 Cookie 对象而非键名, 必须经 keys() 取名值.
    for name in response.cookies.keys():  # noqa: SIM118
        cookies[name] = response.cookies[name]
'''
fixed = '''    # Musxi: preserve empty-value cookies without name-based CookieJar lookup.
    cookies = dict(response.cookies.items())
'''
if response_text.count(original) != 1:
    raise RuntimeError('Unexpected QQMusicApi cookie snapshot source; review compatibility patch')
response_source.write_text(response_text.replace(original, fixed, 1), encoding='utf-8')
(runtime / 'python313._pth').write_text('python313.zip\n.\nLib/site-packages\nimport site\n', encoding='utf-8')
print('Verified and installed', len(manifest['wheels']), 'fixed Python dependencies.')
