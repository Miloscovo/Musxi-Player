param(
    [Parameter(Mandatory=$true)][string]$Installer,
    [Parameter(Mandatory=$true)][string]$Version,
    [Parameter(Mandatory=$true)][string]$OutputDirectory
)
$ErrorActionPreference='Stop'
if ($Version -cnotmatch '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$') {
    throw 'Invalid release version.'
}
$artifact=Get-Item -LiteralPath $Installer
if ($artifact.Name -cne "MusxiPlayer-Setup-$Version.exe" -or $artifact.Length -le 0 -or $artifact.Length -ge 2147483648) {
    throw 'Installer name or size does not match the official Windows x64 release contract.'
}
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$manifest=[ordered]@{ schemaVersion=1; version=$Version; architecture='x64'; assetName=$artifact.Name;
    bytes=$artifact.Length; sha256=(Get-FileHash -LiteralPath $artifact.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
$destination=Join-Path $OutputDirectory "MusxiPlayer-Update-$Version.json"
[IO.File]::WriteAllText($destination, ($manifest | ConvertTo-Json -Compress) + "`n", [Text.UTF8Encoding]::new($false))
Write-Output $destination
