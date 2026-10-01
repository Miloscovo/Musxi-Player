"""Reject changed source bytes, wrong binary mappings and unsafe output paths."""
import copy
import json
from pathlib import Path
import runpy
import subprocess
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

# Evaluate the real packaging selection without building or touching an installer.
subprocess.run(['pwsh', '-NoProfile', '-Command', r'''
$ErrorActionPreference='Stop'
$tokens=$null; $errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PWD 'package.ps1'),[ref]$tokens,[ref]$errors)
if ($errors.Count) { throw 'Packaging syntax errors' }
$selection=$ast.Find({param($node) $node -is [Management.Automation.Language.AssignmentStatementAst] -and $node.Left.Extent.Text -eq '$releaseAssets'},$true).Extent.Text
$embeddedMaterials='embedded.tar.gz'; $sourceBundlePath='playback.zip'; $cefFfmpegCommit='fixed'
$ffmpegManifest=@{source_asset_name='playback.zip'}
$StageSourceBundles=$false; Invoke-Expression $selection
if (@($releaseAssets).Count -ne 0) { throw 'Default unexpectedly stages source assets' }
$StageSourceBundles=$true; Invoke-Expression $selection
if (@($releaseAssets).Count -ne 2) { throw 'Optional source delivery lost one FFmpeg instance' }
$record=$ast.Find({param($node) $node -is [Management.Automation.Language.AssignmentStatementAst] -and $node.Left.Extent.Text -eq '$uploadRecord'},$true).Extent.Text
$artifact='MusxiPlayer-Setup-0.2.0.exe'; $PublicSourceRevision='fixed'
Invoke-Expression $record
if (@($uploadRecord.assets).Count -ne 1 -or $uploadRecord.assets[0] -ne $artifact) { throw 'Default upload must be installer only' }
Write-Host 'PASS: installer-only upload list and both optional source bundles'
'''], cwd=root, check=True)
