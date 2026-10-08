param([switch]$Package, [switch]$SkipInstall)
# -Package remains accepted for existing build commands; services are always staged.
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$node = Get-Command node.exe -ErrorAction SilentlyContinue
$npm = Get-Command npm.cmd -ErrorAction SilentlyContinue
if (-not $node -or -not $npm) { throw 'Install Node.js LTS from https://nodejs.org first.' }
$revision = 'a5a98013cce79fe0ae2ad65fc84b68176ebcfc1e'
$vendor = Join-Path $PSScriptRoot "services/vendor/KuGouMusicApi-$revision"
if (-not (Test-Path (Join-Path $vendor 'main.js'))) {
    New-Item -ItemType Directory services/vendor -Force | Out-Null
    & $node.Source -e "fetch('https://codeload.github.com/MakcRe/KuGouMusicApi/zip/$revision').then(r=>{if(!r.ok)throw Error(r.status);return r.arrayBuffer()}).then(b=>require('fs').writeFileSync('services/kugou-api.zip',Buffer.from(b))).catch(e=>{console.error(e.message);process.exit(1)})"
    if ($LASTEXITCODE -ne 0) { throw 'Could not download pinned KuGouMusicApi source.' }
    Expand-Archive -LiteralPath services/kugou-api.zip -DestinationPath services/vendor -Force
}
Push-Location services
try {
    if (-not $SkipInstall) {
        & $npm.Source ci --ignore-scripts --no-audit --no-fund --cache "$PSScriptRoot/build/npm-cache"
        if ($LASTEXITCODE -ne 0) { throw 'Dependency installation failed.' }
    }
    & $npm.Source test
    if ($LASTEXITCODE -ne 0) { throw 'Cloud adapter tests failed.' }
} finally { Pop-Location }
& "$PSScriptRoot/setup-platforms.ps1"
$previousProfile=$env:MUSXI_PROFILE_DIR
try {
    $env:MUSXI_PROFILE_DIR="$PSScriptRoot/build/multi-platform-audit/python-test-profile"
    & "$PSScriptRoot/build/runtime/python/python.exe" "$PSScriptRoot/services/qq_bridge_test.py"
    if ($LASTEXITCODE -ne 0) { throw 'QQ adapter tests failed.' }
} finally { $env:MUSXI_PROFILE_DIR=$previousProfile }
$servicesPath=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'build/services'))
$backupPath=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot "build/service-backups/$([guid]::NewGuid().ToString('N'))"))
$buildRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'build'))+[IO.Path]::DirectorySeparatorChar
if (-not $servicesPath.StartsWith($buildRoot) -or -not $backupPath.StartsWith($buildRoot)) { throw 'Service staging paths must remain within build/.' }
if (Test-Path -LiteralPath $servicesPath) {
    New-Item -ItemType Directory -Path (Split-Path $backupPath) -Force | Out-Null
    Move-Item -LiteralPath $servicesPath -Destination $backupPath
}
New-Item -ItemType Directory -Path $servicesPath -Force | Out-Null
Copy-Item services/*.cjs,services/*.py,services/package.json,services/package-lock.json,services/python-*.json,services/python-*.lock -Destination build/services -Force
Copy-Item -LiteralPath services/node_modules -Destination build/services -Recurse -Force
New-Item -ItemType Directory build/services/vendor -Force | Out-Null
Copy-Item -LiteralPath $vendor -Destination build/services/vendor -Recurse -Force
Copy-Item -LiteralPath "$PSScriptRoot/services/vendor/NeteaseCloudMusicApi-2aab9957dfd5231e5b192aecdb177f93ace4c92e" -Destination build/services/vendor -Recurse -Force
New-Item -ItemType Directory build/runtime -Force | Out-Null
Copy-Item -LiteralPath $node.Source -Destination build/runtime/node.exe -Force
& $node.Source -e "fetch('https://raw.githubusercontent.com/nodejs/node/'+process.version+'/LICENSE').then(r=>{if(!r.ok)throw Error(r.status);return r.text()}).then(t=>require('fs').writeFileSync('build/runtime/LICENSE',t)).catch(e=>{console.error(e.message);process.exit(1)})"
if ($LASTEXITCODE -ne 0) { throw 'Could not obtain the matching Node.js license.' }
Write-Host 'Three-platform services staged. Build with ./build.ps1, then run build/cef-msvc/src/cef/Release/MusxiPlayerWeb.exe.'
