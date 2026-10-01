param(
    [Parameter(Mandatory=$true)][string]$SourceArchive,
    [Parameter(Mandatory=$true)][string]$VcVars,
    [Parameter(Mandatory=$true)][string]$Bash,
    [Parameter(Mandatory=$true)][string]$Make,
    [Parameter(Mandatory=$true)][string]$NasmDirectory,
    [string]$WorkDirectory="$PSScriptRoot/build/ffmpeg-local",
    [string]$Prefix="$PSScriptRoot/build/deps/ffmpeg-musxi-a5923073-msvc"
)
$ErrorActionPreference='Stop'
if ((Get-FileHash -LiteralPath $SourceArchive -Algorithm SHA256).Hash -ne '65474f81cf8a4e2529cca6c25877fca8b6f3340a67ff804a306bbc3940cd46f6') {
    throw 'Expected the exact FFmpeg a5923073 source ZIP; checksum mismatch.'
}
foreach ($tool in @($VcVars,$Bash,$Make,(Join-Path $NasmDirectory 'nasm.exe'))) {
    if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) { throw "Missing build tool: $tool" }
}
function Bash-Path([string]$Path) {
    $absolute=[IO.Path]::GetFullPath($Path).Replace('\','/')
    if ($absolute -notmatch '^[A-Za-z]:/' -or $absolute -match "['`"`r`n]") { throw 'Use an absolute drive path without quotes or line breaks.' }
    return '/' + $absolute.Substring(0,1).ToLowerInvariant() + '/' + $absolute.Substring(3)
}
$source=Join-Path $WorkDirectory 'source/FFmpeg-FFmpeg-a592307'
if (-not (Test-Path -LiteralPath "$source/configure")) {
    Expand-Archive -LiteralPath $SourceArchive -DestinationPath "$WorkDirectory/source"
}
# Archive builds otherwise pick up the parent Musxi Git revision. This is the only source change.
[IO.File]::WriteAllText("$source/VERSION", "n9.0.2-3-ga5923073bf`n")
$object=Join-Path $WorkDirectory 'obj'
New-Item -ItemType Directory -Force -Path $object | Out-Null
$shell=@"
#!/usr/bin/env bash
set -euo pipefail
cd '$(Bash-Path $object)'
'$(Bash-Path $source)/configure' --toolchain=msvc --arch=x86_64 --target-os=win32 --prefix='$(Bash-Path $Prefix)' --enable-shared --disable-static --disable-gpl --disable-nonfree --disable-version3 --disable-autodetect --disable-debug --disable-doc --disable-programs --disable-avdevice --disable-avfilter --disable-swscale --disable-network --disable-everything --enable-avcodec --enable-avformat --enable-avutil --enable-swresample --enable-demuxer=wav,mp3,flac,mov,aac,ogg,asf --enable-protocol=file --enable-parser=aac,aac_latm,ac3,dca,flac,mpegaudio,opus,vorbis --enable-decoder='aac,aac_fixed,aac_latm,alac,flac,mp3,mp3float,mp2,mp2float,vorbis,opus,wmav1,wmav2,wmapro,wmalossless,wmavoice,pcm*,adpcm*,ac3,eac3,dca,ape,wavpack,tak,tta' --extra-version=musxi-local-1
'$(Bash-Path $Make)' -j6
'$(Bash-Path $Make)' install
"@
$shellPath=Join-Path $WorkDirectory 'build.sh'
[IO.File]::WriteAllText($shellPath, $shell.Replace("`r`n","`n"))
$gitBin=Join-Path (Split-Path (Split-Path $Bash)) 'usr/bin'
$launcher=Join-Path $WorkDirectory 'build.cmd'
$batch=@"
@echo off
call "$VcVars"
if errorlevel 1 exit /b 1
set "PATH=$NasmDirectory;%PATH%;$gitBin"
"$Bash" "$(Bash-Path $shellPath)"
"@
[IO.File]::WriteAllText($launcher,$batch)
& $env:ComSpec /d /c $launcher *> "$WorkDirectory/build.log"
if ($LASTEXITCODE -ne 0) { throw "FFmpeg build failed; see $WorkDirectory/build.log" }
Copy-Item -LiteralPath "$source/COPYING.LGPLv2.1" -Destination "$Prefix/LICENSE.txt"
New-Item -ItemType Directory -Force -Path "$Prefix/lib" | Out-Null
foreach ($component in @('avcodec','avformat','avutil','swresample')) {
    Copy-Item -LiteralPath "$Prefix/bin/$component.lib" -Destination "$Prefix/lib/$component.lib"
}
Write-Host "FFmpeg shared SDK: $Prefix"
