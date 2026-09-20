param(
    [Parameter(Mandatory=$true)][string]$CefRoot,
    [string]$Generator = 'Visual Studio 18 2026',
    [switch]$Test,
    [switch]$Vue
)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
if (-not (Test-Path (Join-Path $CefRoot 'cmake/FindCEF.cmake'))) {
    throw 'CefRoot must be an unpacked Windows x64 CEF SDK.'
}
$cmakeCommand = Get-Command cmake.exe -ErrorAction SilentlyContinue
$cmakePath = if ($cmakeCommand) { $cmakeCommand.Source } else {
    Join-Path $PSScriptRoot 'build/tools/cmake-python/cmake/data/bin/cmake.exe'
}
if (-not (Test-Path $cmakePath)) { throw 'Install CMake 3.20+ supporting your Visual Studio generator.' }
if ($Vue) {
    Push-Location (Join-Path $PSScriptRoot 'frontend')
    try {
        & npm.cmd ci
        if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency install failed.' }
    } finally { Pop-Location }
}
$vueOption = if ($Vue) { 'ON' } else { 'OFF' }
& $cmakePath -S . -B build/cef-msvc -G $Generator -A x64 -DMUSXI_ENABLE_CEF=ON "-DCEF_ROOT=$CefRoot" "-DMUSXI_BUILD_VUE_UI=$vueOption"
if ($LASTEXITCODE -ne 0) { throw 'CEF configure failed.' }
& $cmakePath --build build/cef-msvc --config Release --parallel 6
if ($LASTEXITCODE -ne 0) { throw 'CEF build failed.' }
if ($Test) {
    & (Join-Path (Split-Path $cmakePath) 'ctest.exe') --test-dir build/cef-msvc -C Release --output-on-failure
    if ($LASTEXITCODE -ne 0) { throw 'CEF/native regression tests failed.' }
}
Write-Host 'Preview: build/cef-msvc/src/cef/Release/MusxiPlayerWeb.exe'
if ($Vue) { Write-Host 'Vue preview: add --cef-vue to the executable arguments.' }
