param(
    [Parameter(Mandatory=$true)][string]$Compiler,
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [switch]$Execute
)
# Opt-in integration probe: reuses production Setup directives with an isolated
# AppId, inert payload and no shortcuts/Run entries. Never installs the product.
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$probeRoot=[IO.Path]::GetFullPath($OutputDirectory)
if ($probeRoot.Length -lt 12 -or (Test-Path -LiteralPath $probeRoot)) { throw 'Provide a new dedicated probe directory.' }
$probeId=[guid]::NewGuid().ToString().ToUpperInvariant()
$app=Join-Path $probeRoot '自定义目录\Musxi Upgrade Probe'
$profile=Join-Path $probeRoot 'independent-profile'
New-Item -ItemType Directory -Path $probeRoot,$profile | Out-Null
$source=Get-Content -LiteralPath "$repo/installer/MusxiPlayerUpgrade.iss" -Raw
$setup=[regex]::Match($source,'(?ms)^\[Setup\]\r?\n(.*?)(?=^\[)').Groups[1].Value
if (-not $setup -or $setup -notmatch 'PrivilegesRequired=lowest' -or $setup -notmatch 'CloseApplications=yes') { throw 'Unexpected production installer contract.' }
$registry="HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\{${probeId}}_is1"
$sentinel=Join-Path $profile 'playback-history.json'
[IO.File]::WriteAllText($sentinel,'{"probe":"preserve user data"}')
$before=(Get-FileHash -LiteralPath $sentinel).Hash
$utf8=[Text.UTF8Encoding]::new($true)
try {
foreach ($version in @('0.4.0','0.5.0')) {
    $payload=Join-Path $probeRoot "payload-$version.bin"
    [IO.File]::WriteAllText($payload, "Inert upgrade probe payload $version")
    $directives=$setup.Replace('AppVersion={#AppVersion}',"AppVersion=$version")
    $directives=[regex]::Replace($directives,'(?m)^AppId=.*$',"AppId={{$probeId}")
    $directives=[regex]::Replace($directives,'(?m)^AppName=.*$','AppName=Musxi isolated upgrade probe')
    $directives=[regex]::Replace($directives,'(?m)^VersionInfoVersion=.*$',"VersionInfoVersion=$version.0")
    $directives=[regex]::Replace($directives,'(?m)^DefaultDirName=.*$',"DefaultDirName=$app")
    $directives=[regex]::Replace($directives,'(?m)^DefaultGroupName=.*$','DefaultGroupName=Musxi isolated upgrade probe')
    $directives=[regex]::Replace($directives,'(?m)^OutputDir=.*$',"OutputDir=$probeRoot")
    $directives=[regex]::Replace($directives,'(?m)^OutputBaseFilename=.*$',"OutputBaseFilename=upgrade-probe-$version")
    $directives=[regex]::Replace($directives,'(?m)^UninstallDisplayIcon=.*\r?\n','')
    $iss=Join-Path $probeRoot "probe-$version.iss"
    [IO.File]::WriteAllText($iss,"[Setup]`n$directives`n[Files]`nSource: `"$payload`"; DestDir: `"{app}`"; DestName: `"payload.bin`"; Flags: ignoreversion`n",$utf8)
    & $Compiler /Qp $iss
    if ($LASTEXITCODE -ne 0) { throw 'Probe installer compilation failed.' }
    if ($Execute) {
        $installer=Join-Path $probeRoot "upgrade-probe-$version.exe"
        # Second install intentionally omits /DIR to check remembered custom path.
        $arguments=@('/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART','/SP-',"/LOG=`"$probeRoot/install-$version.log`"")
        if ($version -eq '0.4.0') { $arguments+="/DIR=`"$app`"" }
        $process=Start-Process -FilePath $installer -ArgumentList $arguments -WindowStyle Hidden -PassThru
        if (-not $process.WaitForExit(60000)) { throw 'Probe installer timed out; inspect the isolated probe manually.' }
        if ($process.ExitCode -ne 0) { throw "Probe install failed: $($process.ExitCode)" }
        if ((Get-Content -LiteralPath (Join-Path $app 'payload.bin') -Raw) -cne "Inert upgrade probe payload $version") { throw 'Wrong installed payload.' }
        $installed=Get-ItemProperty -LiteralPath $registry
        if ($installed.DisplayVersion -cne $version -or $installed.InstallLocation.TrimEnd('\') -ine $app.TrimEnd('\')) { throw 'Upgrade identity or remembered directory changed.' }
        if ((Get-FileHash -LiteralPath $sentinel).Hash -cne $before) { throw 'Independent profile changed.' }
    }
}
} finally {
if ($Execute -and (Test-Path -LiteralPath (Join-Path $app 'unins000.exe'))) {
    $uninstaller=Join-Path $app 'unins000.exe'
    $process=Start-Process -FilePath $uninstaller -ArgumentList '/VERYSILENT /SUPPRESSMSGBOXES /NORESTART' -WindowStyle Hidden -PassThru
    if (-not $process.WaitForExit(60000) -or $process.ExitCode -ne 0) { throw 'Probe uninstall failed.' }
    if (Test-Path -LiteralPath $registry) { throw 'Probe uninstall registry key remains.' }
    if ((Get-FileHash -LiteralPath $sentinel).Hash -cne $before) { throw 'Independent profile changed during uninstall.' }
}
}
[ordered]@{ executed=[bool]$Execute; productionSetup='installer/MusxiPlayerUpgrade.iss'; isolatedAppId=$probeId;
    versions=@('0.4.0','0.5.0'); customDirectory=$app; profileSha256=$before;
    limitation='Inert payload and isolated identity; does not validate real product processes, playback, login or profile migrations.' } |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $probeRoot 'result.json') -Encoding UTF8
Write-Output "Probe evidence: $probeRoot"
