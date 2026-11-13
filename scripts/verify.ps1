$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'Use PowerShell 7: pwsh -File scripts/verify.ps1' }
$root = Split-Path -Parent $PSScriptRoot
$started = [DateTime]::UtcNow
$runId = $started.ToString('yyyyMMddTHHmmssfffZ') + '-' + $PID
$out = Join-Path $root ".local/artifacts/verification/$runId"
New-Item -ItemType Directory -Path $out -Force | Out-Null
$summary = [ordered]@{
    schemaVersion = 1; startedAtUtc = $started.ToString('o'); head = $null
    workingTreeChanges = @(); java = $null; result = 'failed'
    task = ':verifyAndroid -Pmusxi.verify.fresh=true'
    tests = @(); lint = @(); apkSha256 = $null; deviceTestsExecuted = $false
}
function Get-SourceManifest {
    $paths = @(& git -C $root -c core.quotepath=false ls-files --cached --others --exclude-standard | Sort-Object -Unique)
    $result = [ordered]@{}
    foreach ($path in $paths) {
        $fullPath = Join-Path $root $path
        $result[$path] = if (Test-Path -LiteralPath $fullPath -PathType Leaf) {
            (Get-FileHash -LiteralPath $fullPath -Algorithm SHA256).Hash.ToLowerInvariant()
        } else { 'deleted' }
    }
    return $result
}
$buildLog = Join-Path $out 'build.log'
"Verification started: $($started.ToString('o'))" | Set-Content -LiteralPath $buildLog -Encoding utf8NoBOM
try {
    if (!$env:JAVA_HOME) { throw 'Set JAVA_HOME to JDK 17.' }
    $java = Join-Path $env:JAVA_HOME 'bin/java.exe'
    if (!(Test-Path -LiteralPath $java)) { throw 'JAVA_HOME does not contain bin/java.exe.' }
    $javaVersion = (& $java -version 2>&1 | Out-String).Trim()
    if ($javaVersion -notmatch 'version "17[.\"]') { throw 'Verification requires JDK 17.' }
    $summary.java = $javaVersion
    $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }
    if (!$sdk -or !(Test-Path -LiteralPath (Join-Path $sdk 'platforms/android-36/android.jar'))) {
        throw 'Set ANDROID_HOME to an SDK containing platforms;android-36.'
    }
    $head = (& git -C $root rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Verification requires a Git checkout.' }
    $changes = @(& git -C $root status --porcelain=v1 --untracked-files=all)
    $summary.head = $head
    $summary.workingTreeChanges = $changes
    $sourceJson = Get-SourceManifest | ConvertTo-Json -Depth 3 -Compress
    $sourceJson | Set-Content -LiteralPath (Join-Path $out 'source-files.json') -Encoding utf8NoBOM
    & (Join-Path $PSScriptRoot 'check-docs.ps1')
    # Collect under the same lock as compilation: another build cannot replace our reports.
    $collectEvidence = {
    $plan = Join-Path $root 'build/verification/modules.tsv'
    if (!(Test-Path -LiteralPath $plan) -or (Get-Item -LiteralPath $plan).LastWriteTimeUtc -lt $started) {
        throw 'Gradle did not produce the current verification module plan.'
    }
    $projects = @(Import-Csv -LiteralPath $plan -Delimiter "`t" | ForEach-Object {
        if ($_.kind -notin @('jvm', 'android')) { throw 'Unknown module type in verification plan.' }
        [pscustomobject]@{ Name = $_.module; FullName = Join-Path $root $_.directory; IsJvm = $_.kind -eq 'jvm' }
    })
    if (!$projects.Count) { throw 'Verification module plan is empty.' }
    foreach ($project in $projects) {
        $testFolder = Join-Path $project.FullName $(if ($project.IsJvm) { 'build/test-results/test' } else { 'build/test-results/testDebugUnitTest' })
        $xmlFiles = @(Get-ChildItem -LiteralPath $testFolder -File -Filter 'TEST-*.xml')
        if (!$xmlFiles.Count) { throw "No JVM test results for $($project.Name)." }
        $counts = [ordered]@{ module = $project.Name; tests = 0; failures = 0; errors = 0; skipped = 0 }
        $reportDestination = Join-Path $out "reports/$($project.Name)"
        New-Item -ItemType Directory -Path $reportDestination -Force | Out-Null
        foreach ($xmlFile in $xmlFiles) {
            if ($xmlFile.LastWriteTimeUtc -lt $started) { throw "Stale test result: $($project.Name)/$($xmlFile.Name)" }
            [xml]$report = Get-Content -LiteralPath $xmlFile.FullName -Raw -Encoding UTF8
            foreach ($key in @('tests', 'failures', 'errors', 'skipped')) { $counts[$key] += [int]$report.testsuite.GetAttribute($key) }
            Copy-Item -LiteralPath $xmlFile.FullName -Destination $reportDestination
        }
        if ($counts.tests -eq 0 -or $counts.failures -gt 0 -or $counts.errors -gt 0 -or $counts.skipped -gt 0) {
            throw "Incomplete or failed JVM tests in $($project.Name)."
        }
        $summary.tests += $counts
        if (!$project.IsJvm) {
            $lintPath = Join-Path $project.FullName 'build/reports/lint-results-debug.xml'
            [xml]$lint = Get-Content -LiteralPath $lintPath -Raw -Encoding UTF8
            $issues = @($lint.SelectNodes('/issues/issue'))
            $errors = @($issues | Where-Object { $_.severity -in @('Error', 'Fatal') }).Count
            $warnings = @($issues | Where-Object { $_.severity -eq 'Warning' }).Count
            if ($errors) { throw "Lint errors in $($project.Name)." }
            $summary.lint += [ordered]@{ module = $project.Name; errors = $errors; warnings = $warnings }
            Copy-Item -LiteralPath $lintPath -Destination $reportDestination
        }
    }
    $apk = Join-Path $root 'app/build/outputs/apk/debug/app-debug.apk'
    $summary.apkSha256 = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($head -ne (& git -C $root rev-parse HEAD).Trim() -or
        $sourceJson -ne (Get-SourceManifest | ConvertTo-Json -Depth 3 -Compress) -or
        (@($changes) -join "`n") -ne (@(& git -C $root status --porcelain=v1 --untracked-files=all) -join "`n")) {
        throw 'Source status changed during verification; rerun after editing stops.'
    }
    $summary.result = 'passed'
    }
    & (Join-Path $root 'build.ps1') -Verify -AfterBuild $collectEvidence *>&1 | Tee-Object -FilePath $buildLog -Append
} catch {
    $summary.result = 'failed'
    $summary.failure = $_.Exception.Message
    "Verification failed: $($_.Exception.Message)" | Add-Content -LiteralPath $buildLog -Encoding utf8NoBOM
    throw
} finally {
    $summary.finishedAtUtc = [DateTime]::UtcNow.ToString('o')
    $summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $out 'summary.json') -Encoding utf8NoBOM
    Write-Host "Verification evidence: $out"
}
