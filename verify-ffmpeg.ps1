param(
    [Parameter(Mandatory=$true)][string]$FfmpegRoot,
    [string]$CefRoot,
    [string]$RuntimeDirectory,
    [string]$PublicReleaseTag,
    [switch]$SourceMaterials
)
$ErrorActionPreference = 'Stop'
$manifest = Get-Content -LiteralPath "$PSScriptRoot/licenses/FFmpeg-build.json" -Raw | ConvertFrom-Json
function Assert-Hash([string]$Path, [string]$Expected) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Missing FFmpeg material: $Path" }
    if ((Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash -ne $Expected) {
        throw "FFmpeg hash mismatch: $Path. Re-audit the binary, source and notices before distribution."
    }
}
# Distribution-time checks only; installed applications can use compatible replacement DLLs.
foreach ($file in $manifest.files) {
    $relative = if ($file.sdk_path) { $file.sdk_path } else { "bin/$($file.file)" }
    Assert-Hash (Join-Path $FfmpegRoot $relative) $file.sha256
}
Assert-Hash (Join-Path $FfmpegRoot 'LICENSE.txt') $manifest.license_sha256
Assert-Hash "$PSScriptRoot/licenses/FFmpeg-LICENSE.txt" $manifest.license_sha256
Assert-Hash (Join-Path $FfmpegRoot 'include/libavutil/ffversion.h') $manifest.header_sha256
$notices = Get-Content -LiteralPath "$PSScriptRoot/THIRD_PARTY_NOTICES.md" -Raw
Assert-Hash "$PSScriptRoot/$($manifest.build_script)" $manifest.build_script_sha256
foreach ($value in @($manifest.version, $manifest.ffmpeg_commit,
                     $manifest.source_bundle_sha256, $manifest.license)) {
    if (-not $notices.Contains($value)) { throw "FFmpeg notices are missing audited value: $value" }
}
foreach ($license in @('FFmpeg-COPYING.GPLv3', 'CEF-FFmpeg-COPYING.LGPLv2.1', 'CEF-FFmpeg-COPYING.LGPLv3', 'CEF-Source-Method-Review.md', 'prepare-cef-windows-source.ps1', 'CEF-source.gclient')) {
    if (-not (Test-Path -LiteralPath "$PSScriptRoot/licenses/$license")) { throw "Missing license: $license" }
}
foreach ($file in $manifest.license_materials_sha256.PSObject.Properties) {
    Assert-Hash "$PSScriptRoot/licenses/$($file.Name)" $file.Value
}
foreach ($value in @($manifest.cef.version, $manifest.cef.chromium_version, $manifest.cef.ffmpeg_commit)) {
    if (-not $notices.Contains($value)) { throw "CEF FFmpeg notices are missing audited value: $value" }
}
# BSD binary redistribution materials; no CEF reproduction/PDB/GN/PGO gate.
Assert-Hash "$PSScriptRoot/licenses/CEF-LICENSE.txt" $manifest.cef.license_sha256
Assert-Hash "$PSScriptRoot/licenses/CEF-Chromium-CREDITS.html" $manifest.cef.credits_sha256
if ($CefRoot) {
    Assert-Hash (Join-Path $CefRoot 'Release/libcef.dll') $manifest.cef.libcef_sha256
    Assert-Hash (Join-Path $CefRoot 'LICENSE.txt') $manifest.cef.license_sha256
    Assert-Hash (Join-Path $CefRoot 'CREDITS.html') $manifest.cef.credits_sha256
}
if ($RuntimeDirectory) {
    $runtimeFiles = @($manifest.files | Where-Object distributed)
    foreach ($file in $runtimeFiles) { Assert-Hash (Join-Path $RuntimeDirectory $file.file) $file.sha256 }
    Assert-Hash (Join-Path $RuntimeDirectory 'libcef.dll') $manifest.cef.libcef_sha256
    Assert-Hash (Join-Path $RuntimeDirectory 'LICENSE') (Get-FileHash "$PSScriptRoot/LICENSE").Hash
    Assert-Hash (Join-Path $RuntimeDirectory 'licenses/CEF-LICENSE.txt') $manifest.cef.license_sha256
    Assert-Hash (Join-Path $RuntimeDirectory 'licenses/CEF-Chromium-CREDITS.html') $manifest.cef.credits_sha256
    foreach ($file in Get-ChildItem -LiteralPath $RuntimeDirectory -File) {
        if ($file.Name -match '^(av(codec|format|util|device|filter)-.*\.dll|sw(resample|scale)-.*\.dll|ff(mpeg|probe|play)\.exe)$' -and
            $file.Name -notin $runtimeFiles.file) { throw "Unexpected FFmpeg runtime file: $($file.Name)" }
    }
    Assert-Hash (Join-Path $RuntimeDirectory 'THIRD_PARTY_NOTICES.md') (Get-FileHash "$PSScriptRoot/THIRD_PARTY_NOTICES.md").Hash
    foreach ($file in @('FFmpeg-build.json','FFmpeg-LICENSE.txt','FFmpeg-COPYING.GPLv3','CEF-FFmpeg-COPYING.LGPLv2.1','CEF-FFmpeg-COPYING.LGPLv3','CEF-Source-Method-Review.md','prepare-cef-windows-source.ps1','CEF-source.gclient')) {
        Assert-Hash (Join-Path $RuntimeDirectory "licenses/$file") (Get-FileHash "$PSScriptRoot/licenses/$file").Hash
    }
    foreach ($file in $manifest.license_materials_sha256.PSObject.Properties) {
        Assert-Hash (Join-Path $RuntimeDirectory "licenses/$($file.Name)") $file.Value
    }
}
if ($SourceMaterials) {
    Assert-Hash "$PSScriptRoot/$($manifest.cef.source_materials_path)" $manifest.cef.source_materials_sha256
    Assert-Hash "$PSScriptRoot/$($manifest.source_bundle_path)" $manifest.source_bundle_sha256
    Assert-Hash "$PSScriptRoot/$($manifest.sdk_archive_path)" $manifest.sdk_archive_sha256
    Assert-Hash "$PSScriptRoot/build/cef-ffmpeg-source-materials/chromium-ffmpeg-$($manifest.cef.ffmpeg_commit).tar.gz" $manifest.cef.source_archive_sha256
}
if ($PublicReleaseTag) {
    # Read-only verification of the existing companion-asset publication method.
    $tag = [Uri]::EscapeDataString($PublicReleaseTag)
    $release = Invoke-RestMethod -Uri "https://api.github.com/repos/Miloscovo/Musxi-Player/releases/tags/$tag" -TimeoutSec 30
    if ($release.draft) { throw 'The source release is not public.' }
    $expected = @{
        $manifest.source_asset_name = $manifest.source_bundle_sha256
        "MusxiPlayer-CEF-FFmpeg-Source-$($manifest.cef.ffmpeg_commit).tar.gz" = $manifest.cef.source_materials_sha256
    }
    foreach ($name in $expected.Keys) {
        $asset = @($release.assets | Where-Object name -EQ $name)
        if ($asset.Count -ne 1 -or $asset[0].digest -ne "sha256:$($expected[$name])") {
            throw "Public FFmpeg source asset missing or unverified: $name"
        }
    }
    Write-Host "Public FFmpeg source asset metadata verified: $PublicReleaseTag"
}
Write-Host "FFmpeg distribution evidence verified: $($manifest.version), $($manifest.license)"
