param([switch]$Test)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$compiler = Get-Command g++.exe -ErrorAction SilentlyContinue
$compilerPath = if ($compiler) { $compiler.Source } else { $null }
if (-not $compiler -and (Test-Path 'D:\msys64\ucrt64\bin\g++.exe')) {
    $compilerPath = 'D:\msys64\ucrt64\bin\g++.exe'
}
if (-not $compilerPath) { throw 'Please install MinGW-w64, or use CMake with Visual Studio.' }
New-Item -ItemType Directory -Path build -Force | Out-Null
$resourceCompiler = Join-Path (Split-Path $compilerPath) 'windres.exe'
& $resourceCompiler src/version.rc -O coff -o build/version.o
if ($LASTEXITCODE -ne 0) { throw 'Version resource compilation failed.' }
& $compilerPath -std=c++17 -O2 -Wall -Wextra -Wpedantic -DUNICODE -D_UNICODE -DNOMINMAX -municode -mwindows src/main.cpp build/version.o -o build/MusxiPlayer.exe -static -static-libgcc -static-libstdc++ -lgdiplus -lgdi32 -lwinmm -lcomdlg32 -lshell32 -lole32 -ldwmapi -lcrypt32
if ($LASTEXITCODE -ne 0) { throw 'Build failed.' }
Write-Host 'Built: build/MusxiPlayer.exe'
if ($Test) {
    & $compilerPath -std=c++17 -O2 -Wall -Wextra -Wpedantic -DUNICODE -D_UNICODE -DNOMINMAX -municode tests/smoke.cpp -o build/smoke.exe -static -static-libgcc -static-libstdc++ -lgdiplus -lgdi32 -lwinmm -lcomdlg32 -lshell32 -lole32 -ldwmapi -lcrypt32
    if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed.' }
    & ./build/smoke.exe
    if ($LASTEXITCODE -ne 0) { throw 'Playback integration tests failed.' }
}
