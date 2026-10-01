#requires -Version 7.0
# Prepare an already acquired, fixed source-archive workspace; never compile CEF.
param(
    [Parameter(Mandatory=$true)][string]$ChromiumSource,
    [Parameter(Mandatory=$true)][string]$DepotTools,
    [Parameter(Mandatory=$true)][string]$VisualStudioRoot,
    [Parameter(Mandatory=$true)][string]$VCRuntimeRoot,
    [Parameter(Mandatory=$true)][string]$WindowsSdkRoot,
    [string]$VisualStudioVersion='2026',
    [string]$WindowsSdkVersion='10.0.26100.0'
)
$ErrorActionPreference='Stop'
# CEF's archive patch fallback also resolves patch.exe from PATH.
Get-Command git,patch.exe -ErrorAction Stop | Out-Null
foreach ($directory in @($ChromiumSource,$DepotTools,$VisualStudioRoot,$VCRuntimeRoot,$WindowsSdkRoot)) {
    if (-not (Test-Path -LiteralPath $directory -PathType Container)) { throw "Missing prerequisite directory: $directory" }
}
$ChromiumSource=(Resolve-Path -LiteralPath $ChromiumSource).Path
$DepotTools=(Resolve-Path -LiteralPath $DepotTools).Path
if (Test-Path -LiteralPath "$ChromiumSource/.git") { throw 'Use a source-archive workspace, not a Chromium Git checkout.' }
$cef=Join-Path $ChromiumSource 'cef'
foreach ($pin in @(@($cef,'708dc140cbc3286826a8abef89dc23a44ff9ea72'),
                   @($DepotTools,'2da9ee6f6c86332551055bc44245fab94675272e'))) {
    $actual=(& git -C $pin[0] rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0 -or $actual -ne $pin[1]) { throw "Source revision mismatch: $($pin[0])" }
}
if ((& git -C $cef rev-parse --is-shallow-repository).Trim() -ne 'false') { throw 'CEF version generation needs full history, not a shallow checkout.' }
foreach ($reference in @(@('refs/remotes/origin/master','ff57d4eae16d36457895f2de115a71d502e85a08'),
                         @('refs/remotes/origin/7977','82a832e53b4c9f572d3d3e9bbf22c2f5161fe8be'))) {
    $actual=(& git -C $cef rev-parse $reference[0]).Trim()
    if ($LASTEXITCODE -ne 0 -or $actual -ne $reference[1]) { throw "CEF version metadata ref missing or different: $($reference[0])" }
}
$version=(Get-Content -LiteralPath "$ChromiumSource/chrome/VERSION" -Raw) -replace '\s',''
if ($version -ne 'MAJOR=152MINOR=0BUILD=7977PATCH=83') { throw 'Unexpected Chromium version.' }
$lastchange=Get-Content -LiteralPath "$ChromiumSource/build/util/LASTCHANGE" -Raw
if (-not $lastchange.Contains('79460ebecaa5625e57a5fb679a735659e73dc687')) { throw 'Unexpected Chromium source revision.' }
if (-not (Test-Path -LiteralPath "$ChromiumSource/build/util/LASTCHANGE.committime" -PathType Leaf)) {
    throw 'Missing archive-supplied Chromium commit timestamp.'
}
foreach ($inputFile in @('third_party/openxr/src/include/openxr/openxr.h',
                         'third_party/microsoft_dxheaders/src/src/dxguids.cpp',
                         'third_party/microsoft_webauthn/src/webauthn.h',
                         'third_party/gperf/bin/gperf.exe')) {
    if (-not (Test-Path -LiteralPath "$ChromiumSource/$inputFile" -PathType Leaf)) {
        throw "Missing Windows supplement: $inputFile. Restore its complete pinned tree; see CEF-Source-Verification.md."
    }
}
$gclientPath=Join-Path (Split-Path $ChromiumSource) '.gclient'
$configuration=(Get-Content -LiteralPath "$PSScriptRoot/CEF-source.gclient" -Raw).Replace("'source_tarball': False","'source_tarball': True")
if (Test-Path -LiteralPath $gclientPath) {
    $existing=Get-Content -LiteralPath $gclientPath -Raw
    if (($existing -replace '\r','').Trim() -ne ($configuration -replace '\r','').Trim()) {
        throw 'Existing .gclient differs from the fixed archive configuration; refusing to overwrite it.'
    }
}
$env:PATH="$DepotTools;$env:PATH"
$env:DEPOT_TOOLS_UPDATE='0'
$env:DEPOT_TOOLS_WIN_TOOLCHAIN='0'
$env:WIN_CUSTOM_TOOLCHAIN='1'
$env:CEF_VCVARS='none'
$env:GYP_MSVS_OVERRIDE_PATH=$VisualStudioRoot.Replace('\','/')
$env:GYP_MSVS_VERSION=$VisualStudioVersion
$env:VS_CRT_ROOT=$VCRuntimeRoot.Replace('\','/')
$env:SDK_ROOT=$WindowsSdkRoot.Replace('\','/')
$env:SDK_VERSION=$WindowsSdkVersion
$env:GN_OUT_CONFIGS='Release_GN_x64'
$env:GN_DEFINES='is_official_build=true symbol_level=0 chrome_pgo_phase=0 use_thin_lto=false proprietary_codecs=false ffmpeg_branding="Chromium"'
Push-Location $ChromiumSource
try {
    # Accept already applied exact patches; never reset source or force a failed patch.
    $patchFile="$cef/patch/patches/tarball_gclient.patch"
    & git -C $DepotTools apply -p0 --check $patchFile 2>$null
    if ($LASTEXITCODE -eq 0) {
        & git -C $DepotTools apply -p0 $patchFile
        if ($LASTEXITCODE -ne 0) { throw 'tarball_gclient patch failed.' }
    } else {
        & git -C $DepotTools apply -p0 --reverse --check $patchFile
        if ($LASTEXITCODE -ne 0) { throw 'depot_tools patch state is neither original nor correctly patched.' }
    }
    & "$DepotTools/python3.bat" cef/tools/patcher.py --patch-file tarball_deps --patch-dir $ChromiumSource
    if ($LASTEXITCODE -ne 0) { throw 'tarball_deps patch failed.' }
    & patch.exe --batch --forward --dry-run -p1 -i "$PSScriptRoot/CEF-excluded-updater-test.patch"
    if ($LASTEXITCODE -eq 0) {
        & patch.exe --batch --forward -p1 -i "$PSScriptRoot/CEF-excluded-updater-test.patch"
        if ($LASTEXITCODE -ne 0) { throw 'Updater test exclusion failed.' }
    } else {
        & patch.exe --batch --reverse --dry-run -p1 -i "$PSScriptRoot/CEF-excluded-updater-test.patch"
        if ($LASTEXITCODE -ne 0) { throw 'Updater exclusion patch state is unexpected.' }
    }
    if (-not (Test-Path -LiteralPath $gclientPath)) { Set-Content -LiteralPath $gclientPath -Value $configuration -Encoding utf8NoBOM }
    Set-Location (Split-Path $ChromiumSource)
    & "$DepotTools/gclient.bat" sync --nohooks --no-history -j4
    if ($LASTEXITCODE -ne 0) { throw 'Archive dependency sync failed.' }
    & "$DepotTools/gclient.bat" runhooks
    if ($LASTEXITCODE -ne 0) { throw 'Archive hooks failed.' }
    Set-Location $cef
    & "$DepotTools/python3.bat" tools/gclient_hook.py
    if ($LASTEXITCODE -ne 0) { throw 'CEF hooks/GN generation failed.' }
    Set-Location $ChromiumSource
    & ./third_party/ninja/ninja.exe -C out/Release_GN_x64 args_gn_source
    if ($LASTEXITCODE -ne 0) { throw 'Ninja build-file preparation failed.' }
    & ./third_party/ninja/ninja.exe -C out/Release_GN_x64 -n libcef
    if ($LASTEXITCODE -ne 0) { throw 'libcef dry-run failed.' }
} finally { Pop-Location }
Write-Host 'Source preparation and build-plan check completed; no CEF compilation or release clearance.'
