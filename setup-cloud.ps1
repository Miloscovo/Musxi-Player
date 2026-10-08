param([switch]$Package, [switch]$SkipInstall)
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
New-Item -ItemType Directory build/runtime -Force | Out-Null
Copy-Item -LiteralPath $node.Source -Destination build/runtime/node.exe -Force
& $node.Source -e "fetch('https://raw.githubusercontent.com/nodejs/node/'+process.version+'/LICENSE').then(r=>{if(!r.ok)throw Error(r.status);return r.text()}).then(t=>require('fs').writeFileSync('build/runtime/LICENSE',t)).catch(e=>{console.error(e.message);process.exit(1)})"
if ($LASTEXITCODE -ne 0) { throw 'Could not obtain the matching Node.js license.' }
if ($Package) {
    # Stage services for build.ps1/build-cef.ps1, which deploy build/services next to MusxiPlayerWeb.exe.
    New-Item -ItemType Directory build/services -Force | Out-Null
    Copy-Item services/bridge.cjs,services/package.json,services/package-lock.json -Destination build/services -Force
    Copy-Item -LiteralPath services/node_modules -Destination build/services -Recurse -Force
    New-Item -ItemType Directory build/services/vendor -Force | Out-Null
    Copy-Item -LiteralPath $vendor -Destination build/services/vendor -Recurse -Force
    Write-Host 'Services staged in build/services; build/runtime contains node.exe.'
}
Write-Host 'Cloud integration ready. Build with ./build.ps1, then run build/cef-msvc/src/cef/Release/MusxiPlayerWeb.exe.'
