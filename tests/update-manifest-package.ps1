param([Parameter(Mandatory=$true)][string]$Root)
$ErrorActionPreference='Stop'
$testDir=Join-Path ([IO.Path]::GetTempPath()) ('musxi-manifest-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testDir | Out-Null
try {
    $artifact=Join-Path $testDir 'MusxiPlayer-Setup-0.5.0.exe'
    [IO.File]::WriteAllText($artifact, 'Manifest test payload, not executable.')
    $manifest=& "$Root/scripts/write-update-manifest.ps1" -Installer $artifact -Version '0.5.0' -OutputDirectory $testDir
    $data=Get-Content -LiteralPath $manifest -Raw | ConvertFrom-Json
    if ($data.schemaVersion -ne 1 -or $data.assetName -cne 'MusxiPlayer-Setup-0.5.0.exe' -or
        $data.bytes -ne (Get-Item -LiteralPath $artifact).Length -or
        $data.sha256 -cne (Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash.ToLowerInvariant()) { throw 'Wrong package manifest.' }
    $rejected=$false
    try { & "$Root/scripts/write-update-manifest.ps1" -Installer $artifact -Version '0.6.0' -OutputDirectory $testDir | Out-Null } catch { $rejected=$true }
    if (-not $rejected) { throw 'Mismatched release version accepted.' }
    Write-Output 'Package manifest hashes the actual matching installer.'
} finally { Remove-Item -LiteralPath $testDir -Recurse -Force }
