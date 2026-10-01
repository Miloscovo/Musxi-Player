param(
    [string]$FfmpegRoot="$PSScriptRoot/build/deps/ffmpeg-musxi-a5923073-msvc",
    [string]$SdkArchive
)
$ErrorActionPreference='Stop'
if ($SdkArchive) {
    $manifest=Get-Content "$PSScriptRoot/licenses/FFmpeg-build.json" -Raw | ConvertFrom-Json
    if ((Get-FileHash -LiteralPath $SdkArchive -Algorithm SHA256).Hash -ne $manifest.sdk_archive_sha256) {
        throw 'Audited FFmpeg SDK archive checksum mismatch.'
    }
    if (Test-Path -LiteralPath $FfmpegRoot) { throw 'SDK already exists; do not overwrite a local or modified SDK.' }
    if ((Split-Path $FfmpegRoot -Leaf) -ne 'ffmpeg-musxi-a5923073-msvc') { throw 'Use the audited SDK directory name.' }
    Expand-Archive -LiteralPath $SdkArchive -DestinationPath (Split-Path $FfmpegRoot)
}
if (-not (Test-Path -LiteralPath "$FfmpegRoot/include/libavutil/ffversion.h")) {
    throw 'Provide the audited SDK ZIP using -SdkArchive. For source rebuilds and updating publisher hashes see licenses/Release-Readiness.md. The former BtbN production SDK is no longer used.'
}
& "$PSScriptRoot/verify-ffmpeg.ps1" -FfmpegRoot $FfmpegRoot
Write-Output "FFMPEG_ROOT=$FfmpegRoot"
