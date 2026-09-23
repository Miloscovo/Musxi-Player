param(
    [Parameter(Mandatory=$true)][string]$CefRoot,
    [Parameter(Mandatory=$true)][string]$FfmpegRoot,
    [string]$Compiler = "$PSScriptRoot\build\tools\package\bin\ISCC.exe"
)
$ErrorActionPreference='Stop'
Set-Location $PSScriptRoot
if (-not (Test-Path -LiteralPath $Compiler)) { throw 'Provide Inno Setup ISCC.exe using -Compiler.' }
if (-not (Test-Path -LiteralPath (Join-Path $FfmpegRoot 'LICENSE.txt'))) { throw 'FfmpegRoot must contain the validated LGPL shared SDK and LICENSE.txt.' }
foreach ($required in @('build/runtime/node.exe','build/services/node_modules','build/services/vendor')) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Missing $required; run setup-cloud.ps1 first." }
}
./build-cef.ps1 -CefRoot $CefRoot -Vue -Test -FfmpegRoot $FfmpegRoot
Copy-Item -LiteralPath (Join-Path $CefRoot 'LICENSE.txt') -Destination 'build/cef-license.txt'
Copy-Item -LiteralPath (Join-Path $FfmpegRoot 'LICENSE.txt') -Destination 'build/ffmpeg-license.txt'
& $Compiler installer/MusxiPlayerTest.iss
if ($LASTEXITCODE -ne 0) { throw 'Test installer compilation failed.' }
$artifact=Join-Path $PSScriptRoot 'dist/MusxiPlayer-Test-0.2-Setup-x64.exe'
$hash=Get-FileHash -LiteralPath $artifact -Algorithm SHA256
[System.IO.File]::WriteAllText("$artifact.sha256", "$($hash.Hash.ToLower())  $([System.IO.Path]::GetFileName($artifact))`n")
Write-Host "Test installer: $artifact"
