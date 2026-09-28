param(
    [Parameter(Mandatory=$true)][string]$CefRoot,
    [Parameter(Mandatory=$true)][string]$FfmpegRoot,
    [switch]$Test
)
$ErrorActionPreference='Stop'
& "$PSScriptRoot\build-cef.ps1" -CefRoot $CefRoot -FfmpegRoot $FfmpegRoot -Vue -Test:$Test
if ($LASTEXITCODE -ne 0) { throw 'Vue/CEF build failed.' }
