$ErrorActionPreference = 'Stop'
$name = 'ffmpeg-n9.0.2-3-ga5923073bf-win64-lgpl-shared-9.0'
$sha256 = 'a7e62ca9b34c40145a2c7482f61a78063f6c8f8dbcf17effb27e62841fa6bbd9'
$folder = Join-Path $PSScriptRoot 'build/deps'
$archive = Join-Path $folder "$name.zip"
$sdk = Join-Path $folder $name
New-Item -ItemType Directory -Path $folder -Force | Out-Null
if (-not (Test-Path -LiteralPath $archive)) {
    Invoke-WebRequest -Uri "https://github.com/BtbN/FFmpeg-Builds/releases/download/autobuild-2026-09-21-13-55/$name.zip" -OutFile $archive
}
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $sha256) {
    throw 'FFmpeg archive checksum mismatch. No files were extracted; inspect the archive before retrying.'
}
if (-not (Test-Path -LiteralPath $sdk)) {
    Expand-Archive -LiteralPath $archive -DestinationPath $folder
}
foreach ($file in @('include/libavutil/ffversion.h', 'lib/avcodec.lib', 'bin/ffmpeg.exe')) {
    if (-not (Test-Path -LiteralPath (Join-Path $sdk $file))) {
        throw "Incomplete FFmpeg SDK: $sdk. Existing files were not overwritten."
    }
}
Write-Output "FFMPEG_ROOT=$sdk"
