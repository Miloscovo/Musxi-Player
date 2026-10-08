$ErrorActionPreference = 'Stop'
$manifest = Get-Content "$PSScriptRoot/services/python-runtime.json" -Raw | ConvertFrom-Json
$runtime = Join-Path $PSScriptRoot 'build/runtime/python'
$cache = Join-Path $PSScriptRoot 'build/multi-platform-audit/python-wheels'
$archive = Join-Path $PSScriptRoot "build/python-$($manifest.python.version)-embed-amd64.zip"
New-Item -ItemType Directory -Path $runtime,$cache -Force | Out-Null
if (-not (Test-Path -LiteralPath $archive) -or (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $manifest.python.sha256) {
    Invoke-WebRequest -UseBasicParsing -Uri $manifest.python.url -OutFile $archive -TimeoutSec 300
}
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $manifest.python.sha256) { throw 'Python runtime checksum mismatch.' }
Expand-Archive -LiteralPath $archive -DestinationPath $runtime -Force
& "$runtime/python.exe" "$PSScriptRoot/services/setup_python_runtime.py" $runtime $cache
if ($LASTEXITCODE -ne 0) { throw 'Python runtime installation failed.' }
