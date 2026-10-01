param([Parameter(Mandatory=$true)][string]$FfmpegRoot)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
$manifest = Get-Content "$root/licenses/FFmpeg-build.json" -Raw | ConvertFrom-Json
$firstDll = ($manifest.files | Where-Object distributed | Select-Object -First 1).file
& "$root/verify-ffmpeg.ps1" -FfmpegRoot $FfmpegRoot
# Reject a changed distribution DLL without touching the SDK or an installed application.
$temporary = Join-Path ([IO.Path]::GetTempPath()) "musxi-ffmpeg-check-$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Path $temporary | Out-Null
try {
    [IO.File]::WriteAllText((Join-Path $temporary $firstDll), 'not the audited DLL')
    $rejected = $false
    try { & "$root/verify-ffmpeg.ps1" -FfmpegRoot $FfmpegRoot -RuntimeDirectory $temporary }
    catch {
        if ($_.Exception.Message -notlike "*FFmpeg hash mismatch*$firstDll*") { throw }
        $rejected = $true
    }
    if (-not $rejected) { throw 'Changed runtime DLL was accepted.' }
    # Exercise the release-license guard in an isolated copy, preserving repo/SDK files.
    $licenseSandbox = Join-Path $temporary 'license-sandbox'
    $sandboxLicenses = Join-Path $licenseSandbox 'licenses'
    New-Item -ItemType Directory -Path $sandboxLicenses | Out-Null
    foreach ($name in @('verify-ffmpeg.ps1','THIRD_PARTY_NOTICES.md',$manifest.build_script)) {
        Copy-Item -LiteralPath (Join-Path $root $name) -Destination $licenseSandbox
    }
    $licenseFiles = @('FFmpeg-build.json','FFmpeg-LICENSE.txt','FFmpeg-COPYING.GPLv3',
                     'CEF-FFmpeg-COPYING.LGPLv2.1','CEF-Source-Method-Review.md',
                     'prepare-cef-windows-source.ps1','CEF-source.gclient') +
                    @($manifest.license_materials_sha256.PSObject.Properties.Name)
    foreach ($name in ($licenseFiles | Select-Object -Unique)) {
        Copy-Item -LiteralPath (Join-Path $root "licenses/$name") -Destination $sandboxLicenses
    }
    & "$licenseSandbox/verify-ffmpeg.ps1" -FfmpegRoot $FfmpegRoot
    Remove-Item -LiteralPath "$sandboxLicenses/CEF-FFmpeg-COPYING.LGPLv3"
    $rejected = $false
    try { & "$licenseSandbox/verify-ffmpeg.ps1" -FfmpegRoot $FfmpegRoot }
    catch {
        if ($_.Exception.Message -notlike 'Missing license: CEF-FFmpeg-COPYING.LGPLv3') { throw }
        $rejected = $true
    }
    if (-not $rejected) { throw 'Missing CEF embedded FFmpeg LGPLv3 text was accepted.' }
    $extractedSdk = Join-Path $temporary 'ffmpeg-musxi-a5923073-msvc'
    $sdkArchive = Join-Path $root $manifest.sdk_archive_path
    & "$root/setup-ffmpeg.ps1" -SdkArchive $sdkArchive -FfmpegRoot $extractedSdk
    $rejected = $false
    try { & "$root/setup-ffmpeg.ps1" -SdkArchive $sdkArchive -FfmpegRoot $extractedSdk }
    catch {
        if ($_.Exception.Message -notlike 'SDK already exists*') { throw }
        $rejected = $true
    }
    if (-not $rejected) { throw 'Existing SDK was overwritten.' }
    Write-Host 'FFmpeg license check passed: original SDK accepted; changed DLL and missing LGPLv3 text rejected.'
} finally {
    if ($licenseSandbox -and (Test-Path -LiteralPath $licenseSandbox)) {
        $cleanup = (Resolve-Path -LiteralPath $licenseSandbox).Path
        if ($cleanup -ne "$temporary\license-sandbox") { throw 'Unsafe license test cleanup path.' }
        Remove-Item -LiteralPath $cleanup -Recurse -Force
    }
    # Resolve and bound the generated SDK directory before recursive cleanup.
    if (Test-Path -LiteralPath (Join-Path $temporary 'ffmpeg-musxi-a5923073-msvc')) {
        $cleanup = (Resolve-Path -LiteralPath (Join-Path $temporary 'ffmpeg-musxi-a5923073-msvc')).Path
        if ($cleanup -ne "$temporary\ffmpeg-musxi-a5923073-msvc") { throw 'Unsafe test cleanup path.' }
        Remove-Item -LiteralPath $cleanup -Recurse -Force
    }
    Remove-Item -LiteralPath (Join-Path $temporary $firstDll) -Force
    Remove-Item -LiteralPath $temporary
}
