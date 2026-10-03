param(
    [Parameter(Mandatory=$true)][string]$CefRoot,
    [Parameter(Mandatory=$true)][string]$FfmpegRoot,
    [string]$Compiler="$PSScriptRoot\build\tools\package\bin\ISCC.exe",
    [string]$FixtureTool,
    [string]$Python="python.exe",
    [switch]$RequireCleanSource,
    [string]$PublicSourceRevision='efd72195e2202325c8a8af8b79068f63ccd1cd18',
    [switch]$StageSourceBundles
)
$ErrorActionPreference='Stop'
Set-Location $PSScriptRoot
if ($RequireCleanSource -and @(& git status --porcelain).Count -gt 0) {
    throw 'Release requires a clean committed source revision; review/commit changes before packaging.'
}
$ffmpegManifest=Get-Content "$PSScriptRoot/licenses/FFmpeg-build.json" -Raw | ConvertFrom-Json
if (-not $PublicSourceRevision) { throw 'Provide a verified fixed public source revision; source delivery cannot be disabled.' }
& "$PSScriptRoot/verify-ffmpeg.ps1" -FfmpegRoot $FfmpegRoot -CefRoot $CefRoot -PublicSourceRevision $PublicSourceRevision
if (-not (Test-Path -LiteralPath $Compiler)) { throw 'Provide Inno Setup ISCC.exe using -Compiler.' }
if (-not (Test-Path -LiteralPath (Join-Path $FfmpegRoot 'LICENSE.txt'))) { throw 'FfmpegRoot must contain the validated LGPL shared SDK and LICENSE.txt.' }
foreach ($required in @('LICENSE','THIRD_PARTY_NOTICES.md','licenses/Microsoft-Windows-SDK-LICENSE.rtf','licenses/CEF-Chromium-CREDITS.html','licenses/FFmpeg-LICENSE.txt',$ffmpegManifest.source_bundle_path,"$($ffmpegManifest.source_bundle_path).sha256")) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Missing release license/source material: $required" }
}
$cefFfmpegCommit=$ffmpegManifest.cef.ffmpeg_commit
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
$embeddedMaterials=Join-Path $PSScriptRoot $ffmpegManifest.cef.source_materials_path
& $Python "$PSScriptRoot/prepare-ffmpeg-sources.py" --archive $embeddedMaterials
if ($LASTEXITCODE -ne 0) { throw 'Embedded FFmpeg source/build material validation failed.' }
$sourceBundlePath=Join-Path $PSScriptRoot $ffmpegManifest.source_bundle_path
$sourceBundleExpected=((Get-Content -Raw "${sourceBundlePath}.sha256") -split '\s+')[0].ToLowerInvariant()
$sourceBundleActual=(Get-FileHash -Algorithm SHA256 -LiteralPath $sourceBundlePath).Hash.ToLowerInvariant()
if ($sourceBundleActual -ne $sourceBundleExpected) { throw 'The playback FFmpeg source-materials bundle does not match its checksum sidecar.' }
& "$PSScriptRoot/verify-ffmpeg.ps1" -FfmpegRoot $FfmpegRoot -CefRoot $CefRoot -SourceMaterials
$githubAssetLimit=[long]2147483648
foreach ($sourceAsset in @($cefFfmpegSource,$sourceBundlePath)) {
    if ((Get-Item -LiteralPath $sourceAsset).Length -ge $githubAssetLimit) { throw "Source asset exceeds GitHub's 2 GiB per-file limit: $sourceAsset" }
}
foreach ($required in @('build/runtime/node.exe','build/services/node_modules','build/services/vendor')) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Missing $required; run setup-cloud.ps1 first." }
}
& "$PSScriptRoot\build.ps1" -CefRoot $CefRoot -FfmpegRoot $FfmpegRoot -Test -FixtureTool $FixtureTool
Copy-Item -LiteralPath (Join-Path $CefRoot 'LICENSE.txt') -Destination 'build/cef-license.txt'
Copy-Item -LiteralPath (Join-Path $FfmpegRoot 'LICENSE.txt') -Destination 'build/ffmpeg-license.txt'
$commit=(& git rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Cannot identify application Git revision.' }
$status=@(& git status --porcelain)
if ($LASTEXITCODE -ne 0) { throw 'Cannot verify application source status.' }
if ($RequireCleanSource -and $status.Count -gt 0) { throw 'Source changed during build; refusing to package a dirty revision.' }
$sourceRecord=@{ base_commit=$commit; working_tree_dirty=($status.Count -gt 0);
    publication_status=if ($status.Count -gt 0) { 'candidate: commit reviewed changes and rebuild from a clean fixed revision before publication' } else { 'built from recorded clean revision; verify public source access before publication' } }
[IO.File]::WriteAllText("$PSScriptRoot/build/release-source-record.json", ($sourceRecord | ConvertTo-Json) + "`n")
if ($status.Count -gt 0) { Write-Warning 'Uncommitted source: this installer is a review candidate, not a binary matching the old published tag.' }
& $Compiler installer/MusxiPlayerUpgrade.iss
if ($LASTEXITCODE -ne 0) { throw 'Installer compilation failed.' }
$appVersion=(Get-Content "$PSScriptRoot/frontend/package.json" -Raw | ConvertFrom-Json).version
$artifact=Join-Path $PSScriptRoot "dist/MusxiPlayer-Setup-$appVersion.exe"
$hash=Get-FileHash -LiteralPath $artifact -Algorithm SHA256
[System.IO.File]::WriteAllText("$artifact.sha256", "$($hash.Hash.ToLower())  $([System.IO.Path]::GetFileName($artifact))`n")
$releaseAssets=if ($StageSourceBundles) { @(
    @{ Source=$embeddedMaterials; Name="MusxiPlayer-CEF-FFmpeg-Source-$cefFfmpegCommit.tar.gz" },
    @{ Source=$sourceBundlePath; Name=$ffmpegManifest.source_asset_name }
) } else { @() }
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
$checksums=@($artifact) + @($releaseAssets | ForEach-Object { Join-Path $PSScriptRoot "dist/$($_.Name)" })
$lines=$checksums | ForEach-Object { "$((Get-FileHash -LiteralPath $_).Hash.ToLowerInvariant())  $([IO.Path]::GetFileName($_))" }
[IO.File]::WriteAllText("$PSScriptRoot/dist/SHA256SUMS.txt", ($lines -join "`n") + "`n")
$uploadRecord=@{ assets=@([IO.Path]::GetFileName($artifact)); source_revision=$PublicSourceRevision;
    source_url="https://github.com/Miloscovo/Musxi-Player/blob/$PublicSourceRevision/docs/release-source-delivery.md" }
[IO.File]::WriteAllText("$PSScriptRoot/dist/release-assets.json", ($uploadRecord | ConvertTo-Json) + "`n")
Write-Host "Default upload: $([IO.Path]::GetFileName($artifact)) only; use dist/release-assets.json, never a dist wildcard. Local checksums/source bundles are not default uploads. Announce the fixed source URL beside the download. -StageSourceBundles stages optional bundles without removing existing files."
