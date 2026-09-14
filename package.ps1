param([string]$Compiler = "$PSScriptRoot\build\tools\package\bin\ISCC.exe")
$ErrorActionPreference='Stop'
Set-Location $PSScriptRoot
if (-not (Test-Path -LiteralPath $Compiler)) { throw 'Provide the path to Inno Setup ISCC.exe using -Compiler.' }
./build.ps1
if ($LASTEXITCODE -ne 0) { throw 'Player compilation failed.' }
& $Compiler installer/MintPlayer.iss
if ($LASTEXITCODE -ne 0) { throw 'Installer compilation failed.' }
$artifact=Join-Path $PSScriptRoot 'dist\MusxiPlayer-0.1-Setup-x64.exe'
$hash=Get-FileHash -LiteralPath $artifact -Algorithm SHA256
[System.IO.File]::WriteAllText("$artifact.sha256", "$($hash.Hash.ToLower())  $([System.IO.Path]::GetFileName($artifact))`n")
Write-Host "Installer: $artifact"
