param(
    [Parameter(Mandatory=$true)][string]$Installer,
    [string]$Extractor="$PSScriptRoot/../build/tools/innounp-2/innounp.exe"
)
$ErrorActionPreference='Stop'
if (-not (Test-Path -LiteralPath $Extractor -PathType Leaf)) { throw 'Provide innounp.exe using -Extractor to verify the actual installer payload.' }
$testRoot=Join-Path ([IO.Path]::GetTempPath()) ('musxi-package-services-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot | Out-Null
try {
    & $Extractor -x -y "-d$testRoot" $Installer '{app}\services\*' '{app}\runtime\*' *> (Join-Path $testRoot 'extract.log')
    if ($LASTEXITCODE -ne 0) { throw 'Cannot extract installer service payload.' }
    $node=Join-Path $testRoot '{app}/runtime/node.exe'
    $service=Join-Path $testRoot '{app}/services/bridge.cjs'
    # Unknown operation exercises real startup and JSON-lines IPC without accounts or network requests.
    $probe=@'
const {spawnSync}=require('node:child_process');
const child=spawnSync(process.execPath,[process.argv[1]],{input:'{"op":"__package_probe__"}\n',encoding:'utf8',timeout:15000,windowsHide:true});
let reply;try{reply=JSON.parse(child.stdout.trim());}catch{}
if(child.error || child.status!==0 || reply?.ok!==false || reply?.error!=='未知操作') {
  console.error('Packaged service startup/IPC failed; check included service modules and runtime dependencies.');process.exitCode=1;
}
'@
    & $node -e $probe $service
    if ($LASTEXITCODE -ne 0) { throw 'Packaged service startup/IPC verification failed.' }
    Write-Output 'Actual installer service startup and JSON-lines IPC passed without account access.'
} finally {
    $resolved=[IO.Path]::GetFullPath($testRoot)
    $tempPrefix=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')+'\'
    if (-not $resolved.StartsWith($tempPrefix,[StringComparison]::OrdinalIgnoreCase)) { throw 'Unexpected extraction cleanup path.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
