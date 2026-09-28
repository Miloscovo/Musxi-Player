param(
    [Parameter(Mandatory=$true)][string]$CefRoot,
    [Parameter(Mandatory=$true)][string]$FfmpegRoot,
    [string]$Compiler="$PSScriptRoot\build\tools\package\bin\ISCC.exe"
)
$ErrorActionPreference='Stop'
Set-Location $PSScriptRoot
if (-not (Test-Path -LiteralPath $Compiler)) { throw 'Provide Inno Setup ISCC.exe using -Compiler.' }
if (-not (Test-Path -LiteralPath (Join-Path $FfmpegRoot 'LICENSE.txt'))) { throw 'FfmpegRoot must contain the validated LGPL shared SDK and LICENSE.txt.' }
foreach ($required in @('LICENSE','THIRD_PARTY_NOTICES.md','licenses/Microsoft-Windows-SDK-LICENSE.rtf','licenses/CEF-Chromium-CREDITS.html','licenses/FFmpeg-LICENSE.txt','build/ffmpeg-source-materials/ffmpeg-source-materials-bundle.zip','build/ffmpeg-source-materials/ffmpeg-source-materials-bundle.zip.sha256')) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Missing release license/source material: $required" }
}
$cefFfmpegCommit='2b68d2babae73714846961fb0ee47e3b3d2e39a9'
$cefFfmpegSourceDir=Join-Path $PSScriptRoot 'build/cef-ffmpeg-source-materials'
$cefFfmpegSource=Join-Path $cefFfmpegSourceDir "chromium-ffmpeg-$cefFfmpegCommit.tar.gz"
if (-not (Test-Path -LiteralPath $cefFfmpegSource)) {
    New-Item -ItemType Directory -Force -Path $cefFfmpegSourceDir | Out-Null
    $downloadPath="${cefFfmpegSource}.$([guid]::NewGuid().ToString('N')).download"
    try {
        Invoke-WebRequest -UseBasicParsing -TimeoutSec 300 -Uri "https://chromium.googlesource.com/chromium/third_party/ffmpeg/+archive/$cefFfmpegCommit.tar.gz" -OutFile $downloadPath
        Move-Item -LiteralPath $downloadPath -Destination $cefFfmpegSource
    } catch {
        $googleArchiveError=$_.Exception.Message
        if (Test-Path -LiteralPath $downloadPath) { Remove-Item -LiteralPath $downloadPath -Force }
        try {
            $sourceRepo=Join-Path $cefFfmpegSourceDir 'git-cache'
            if (-not (Test-Path -LiteralPath (Join-Path $sourceRepo '.git'))) {
                & git init $sourceRepo | Out-Null
                if ($LASTEXITCODE -ne 0) { throw 'git init failed.' }
            }
            & git -C $sourceRepo fetch --depth=1 https://gitlab.com/chromiumsrc/ffmpeg.git $cefFfmpegCommit
            if ($LASTEXITCODE -ne 0) { throw 'GitLab mirror fetch failed.' }
            $fetchedCommit=(& git -C $sourceRepo rev-parse 'FETCH_HEAD^{commit}').Trim()
            if ($LASTEXITCODE -ne 0 -or $fetchedCommit -ne $cefFfmpegCommit) { throw "Fetched commit mismatch: $fetchedCommit" }
            & git -C $sourceRepo archive --format=tar.gz --output=$downloadPath $cefFfmpegCommit
            if ($LASTEXITCODE -ne 0) { throw 'git archive failed.' }
            Move-Item -LiteralPath $downloadPath -Destination $cefFfmpegSource
        } catch {
            if (Test-Path -LiteralPath $downloadPath) { Remove-Item -LiteralPath $downloadPath -Force }
            throw "Could not retrieve CEF's pinned FFmpeg source ($cefFfmpegCommit); refusing to package without the LGPL source asset. Chromium archive: $googleArchiveError GitLab mirror: $($_.Exception.Message)"
        }
    }
}
$cefSourceEntries=@(& tar.exe -tzf $cefFfmpegSource)
if ($LASTEXITCODE -ne 0) { throw 'The pinned CEF FFmpeg source archive is not a valid gzip tar archive.' }
$cefSourceEntries=$cefSourceEntries | ForEach-Object { $_ -replace '^\./','' }
foreach ($sourceEntry in @('README.chromium','COPYING.LGPLv2.1','BUILD.gn','ffmpeg_generated.gni','chromium/patches/config_flag_changes.txt')) {
    if ($cefSourceEntries -notcontains $sourceEntry) { throw "CEF FFmpeg source archive is missing $sourceEntry." }
}
$sourceBundlePath=Join-Path $PSScriptRoot 'build/ffmpeg-source-materials/ffmpeg-source-materials-bundle.zip'
$sourceBundleExpected=((Get-Content -Raw "${sourceBundlePath}.sha256") -split '\s+')[0].ToLowerInvariant()
$sourceBundleActual=(Get-FileHash -Algorithm SHA256 -LiteralPath $sourceBundlePath).Hash.ToLowerInvariant()
if ($sourceBundleActual -ne $sourceBundleExpected) { throw 'The BtbN FFmpeg source-materials bundle does not match its checksum sidecar.' }
$githubAssetLimit=[long]2147483648
foreach ($sourceAsset in @($cefFfmpegSource,$sourceBundlePath)) {
    if ((Get-Item -LiteralPath $sourceAsset).Length -ge $githubAssetLimit) { throw "Source asset exceeds GitHub's 2 GiB per-file limit: $sourceAsset" }
}
foreach ($required in @('build/runtime/node.exe','build/services/node_modules','build/services/vendor')) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Missing $required; run setup-cloud.ps1 first." }
}
& "$PSScriptRoot\build.ps1" -CefRoot $CefRoot -FfmpegRoot $FfmpegRoot -Test
Copy-Item -LiteralPath (Join-Path $CefRoot 'LICENSE.txt') -Destination 'build/cef-license.txt'
Copy-Item -LiteralPath (Join-Path $FfmpegRoot 'LICENSE.txt') -Destination 'build/ffmpeg-license.txt'
& $Compiler installer/MusxiPlayerUpgrade.iss
if ($LASTEXITCODE -ne 0) { throw 'Installer compilation failed.' }
$artifact=Join-Path $PSScriptRoot 'dist/MusxiPlayer-0.2-Test-Upgrade-Setup-x64.exe'
$hash=Get-FileHash -LiteralPath $artifact -Algorithm SHA256
[System.IO.File]::WriteAllText("$artifact.sha256", "$($hash.Hash.ToLower())  $([System.IO.Path]::GetFileName($artifact))`n")
$releaseAssets=@(
    @{ Source=$cefFfmpegSource; Name="MusxiPlayer-CEF-FFmpeg-Source-$cefFfmpegCommit.tar.gz" },
    @{ Source=$sourceBundlePath; Name='MusxiPlayer-FFmpeg-Source-Materials-0.2.zip' }
)
foreach ($asset in $releaseAssets) {
    $destination=Join-Path $PSScriptRoot "dist/$($asset.Name)"
    Copy-Item -LiteralPath $asset.Source -Destination $destination -Force
    $assetInfo=Get-Item -LiteralPath $destination
    if ($assetInfo.Length -ge $githubAssetLimit) { throw "GitHub release asset exceeds the 2 GiB per-file limit: $($assetInfo.Name)" }
    $assetHash=Get-FileHash -LiteralPath $destination -Algorithm SHA256
    [System.IO.File]::WriteAllText("${destination}.sha256", "$($assetHash.Hash.ToLower())  $($assetInfo.Name)`n")
}
if ((Get-Item -LiteralPath $artifact).Length -ge $githubAssetLimit) { throw 'The installer exceeds GitHub release asset size limits.' }
Write-Host "Test upgrade installer: $artifact"
Write-Host 'GitHub release assets: installer, CEF FFmpeg source, and BtbN FFmpeg source-materials bundle.'
