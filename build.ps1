param(
    [Parameter(Mandatory=$true)][string]$CefRoot,
    [Parameter(Mandatory=$true)][string]$FfmpegRoot,
    [switch]$Test,
    [string]$FixtureTool
)
$ErrorActionPreference='Stop'
& "$PSScriptRoot\build-cef.ps1" -CefRoot $CefRoot -FfmpegRoot $FfmpegRoot -Test:$Test -FixtureTool $FixtureTool
if ($LASTEXITCODE -ne 0) { throw 'React/CEF build failed.' }
