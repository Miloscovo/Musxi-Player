param([Parameter(Mandatory=$true)][string]$ChromiumSource)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot
$temporary=Join-Path ([IO.Path]::GetTempPath()) ('musxi-cef-source-check-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temporary | Out-Null
$gclientPath=Join-Path (Split-Path $ChromiumSource) '.gclient'
$before=(Get-FileHash -LiteralPath $gclientPath).Hash
try {
    & git init $temporary | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Test Git initialization failed.' }
    Set-Content -LiteralPath "$temporary/test.txt" -Value 'not the pinned depot_tools source'
    & git -C $temporary add test.txt
    if ($LASTEXITCODE -ne 0) { throw 'Test Git add failed.' }
    & git -C $temporary -c user.name=License-Test -c user.email=test@example.invalid -c commit.gpgsign=false commit -m fixture | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Test Git commit failed.' }
    $rejected=$false
    try {
        & "$root/licenses/prepare-cef-windows-source.ps1" -ChromiumSource $ChromiumSource `
            -DepotTools $temporary -VisualStudioRoot $temporary -VCRuntimeRoot $temporary -WindowsSdkRoot $temporary
    } catch {
        if ($_.Exception.Message -ne "Source revision mismatch: $temporary") { throw }
        $rejected=$true
    }
    if (-not $rejected) { throw 'Unpinned depot_tools was accepted.' }
    if ((Get-FileHash -LiteralPath $gclientPath).Hash -ne $before) { throw 'Preflight rejection modified .gclient.' }
    Write-Host 'CEF preparation guard passed: wrong depot_tools revision rejected before config changes.'
} finally {
    $cleanup=(Resolve-Path -LiteralPath $temporary).Path
    if ($cleanup -ne $temporary) { throw 'Unsafe test cleanup path.' }
    Remove-Item -LiteralPath $cleanup -Recurse -Force
}
