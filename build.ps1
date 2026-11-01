param(
    [switch]$Release,
    [switch]$Test,
    [switch]$Lint,
    [switch]$Install
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($Release -and ($Test -or $Lint)) {
    throw '-Release cannot be combined with -Test or -Lint; release verification runs automatically.'
}

function Get-AndroidSdk {
    $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }
    if (!$sdk) { throw 'Set ANDROID_HOME or ANDROID_SDK_ROOT.' }
    if (!(Test-Path -LiteralPath $sdk -PathType Container)) {
        throw 'The Android SDK path does not exist.'
    }
    return $sdk
}

function Get-AndroidBuildTools([string]$Sdk) {
    $root = Join-Path $Sdk 'build-tools'
    if (!(Test-Path -LiteralPath $root -PathType Container)) {
        throw 'Android SDK build-tools are not installed.'
    }
    $directories = @(Get-ChildItem -LiteralPath $root -Directory | Sort-Object {
        try { [version]$_.Name } catch { [version]'0.0' }
    } -Descending)
    foreach ($directory in $directories) {
        $apksigner = Join-Path $directory.FullName 'apksigner.bat'
        $aapt = Join-Path $directory.FullName 'aapt.exe'
        if ((Test-Path -LiteralPath $apksigner) -and (Test-Path -LiteralPath $aapt)) {
            return [pscustomobject]@{ Apksigner = $apksigner; Aapt = $aapt }
        }
    }
    throw 'No Android build-tools version contains both apksigner.bat and aapt.exe.'
}

function Write-Utf8NoBom([string]$Path, [string]$Text) {
    [IO.File]::WriteAllText($Path, $Text, [Text.UTF8Encoding]::new($false))
}

# aapt on Windows cannot open a Unicode absolute filename. Use its ASCII leaf name
# from the APK directory; hashes/copies continue to use the original full path.
function Invoke-ApkInspection([string]$Tool, [string[]]$Before, [string]$Apk, [string[]]$After = @()) {
    Push-Location -LiteralPath (Split-Path -Parent $Apk)
    try {
        $output = @(& $Tool @Before (Split-Path -Leaf $Apk) @After 2>&1)
        return [pscustomobject]@{ Output = $output; ExitCode = $LASTEXITCODE }
    } finally { Pop-Location }
}

function Get-ReleaseApkMetadata([string]$Apk, [string]$Aapt) {
    $inspection = Invoke-ApkInspection -Tool $Aapt -Before @('dump', 'badging') -Apk $Apk
    $badgingOutput = $inspection.Output
    if ($inspection.ExitCode -ne 0) {
        throw "aapt could not read the release APK:`n$($badgingOutput -join "`n")"
    }
    $badging = $badgingOutput -join "`n"
    $packageMatch = [regex]::Match(
        $badging,
        "(?m)^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'"
    )
    $minSdkMatch = [regex]::Match($badging, "(?m)^sdkVersion:'([^']+)'")
    if (!$packageMatch.Success -or !$minSdkMatch.Success) {
        throw 'aapt did not report package, versionCode, versionName, and minSdk.'
    }
    $packageName = $packageMatch.Groups[1].Value
    $versionCodeText = $packageMatch.Groups[2].Value
    $versionName = $packageMatch.Groups[3].Value
    $minSdkText = $minSdkMatch.Groups[1].Value
    if ($packageName -ne 'tech.musxi.player') {
        throw "Release package name is '$packageName', expected 'tech.musxi.player'."
    }
    if ($versionName -notmatch '^[A-Za-z0-9._-]+$') {
        throw "Release versionName '$versionName' is unsafe for artifact naming."
    }
    $versionCode = 0
    $minSdk = 0
    if (![int]::TryParse($versionCodeText, [ref]$versionCode) -or ![int]::TryParse($minSdkText, [ref]$minSdk)) {
        throw 'aapt returned a non-numeric versionCode or minSdk.'
    }
    return [pscustomobject]@{
        PackageName = $packageName
        VersionName = $versionName
        VersionCode = $versionCode
        MinSdk = $minSdk
    }
}

function Invoke-ReleaseVerification(
    [string]$Apk,
    [string]$Apksigner,
    [string]$Aapt,
    [string]$ArtifactDirectory,
    [object]$Metadata
) {
    $inspection = Invoke-ApkInspection -Tool $Apksigner -Before @('verify', '--verbose', '--print-certs') -Apk $Apk
    $signatureOutput = $inspection.Output
    if ($inspection.ExitCode -ne 0) {
        throw "apksigner verification failed:`n$($signatureOutput -join "`n")"
    }
    $signatureText = $signatureOutput -join "`n"
    if ($signatureText -notmatch '(?im)^Verified using v2 scheme \(APK Signature Scheme v2\): true\r?$') {
        throw 'apksigner did not verify APK Signature Scheme v2.'
    }
    $signerMatches = [regex]::Matches(
        $signatureText,
        '(?im)^Signer #\d+ certificate SHA-256 digest:\s*([0-9a-f:]+)\s*\r?$'
    )
    $signerFingerprints = @(
        $signerMatches |
            ForEach-Object { $_.Groups[1].Value.Replace(':', '').ToLowerInvariant() } |
            Select-Object -Unique
    )
    if ($signerFingerprints.Count -ne 1 -or $signerFingerprints[0] -notmatch '^[0-9a-f]{64}$') {
        throw 'Expected exactly one APK signing certificate with a SHA-256 fingerprint.'
    }
    $signerSha256 = $signerFingerprints[0]

    $mergedRoot = Join-Path $PSScriptRoot 'app/build/intermediates/merged_manifests/release'
    if (!(Test-Path -LiteralPath $mergedRoot -PathType Container)) {
        throw 'The merged release manifest directory was not produced.'
    }
    $mergedCandidates = @(Get-ChildItem -LiteralPath $mergedRoot -Recurse -File -Filter AndroidManifest.xml)
    if ($mergedCandidates.Count -ne 1) {
        throw "Expected one merged release AndroidManifest.xml, found $($mergedCandidates.Count)."
    }
    $mergedManifest = Get-Content -LiteralPath $mergedCandidates[0].FullName -Raw -Encoding UTF8
    if ($mergedManifest -match '(?is)android:debuggable\s*=') {
        throw 'The merged release manifest contains android:debuggable.'
    }
    if ($mergedManifest -match '(?is)android:testOnly\s*=') {
        throw 'The merged release manifest contains android:testOnly.'
    }
    if ($mergedManifest -match '(?is)<(?:application|activity|activity-alias|receiver|service|provider)\b[^>]*\bandroid:name\s*=\s*["''][^"'']*(?:Debug|Developer|Validation|GlassPreview)[^"'']*["'']') {
        throw 'The merged release manifest contains a debug-only component or application.'
    }
    if ($mergedManifest -notmatch '(?is)<application\b[^>]*\bandroid:name\s*=\s*["''](?:\.MusxiApplication|tech\.musxi\.player\.MusxiApplication)["'']') {
        throw 'The merged release manifest does not use the production application entry.'
    }

    $inspection = Invoke-ApkInspection -Tool $Aapt -Before @('dump', 'xmltree') -Apk $Apk -After @('AndroidManifest.xml')
    $apkManifestOutput = $inspection.Output
    if ($inspection.ExitCode -ne 0) {
        throw "aapt could not inspect the APK manifest:`n$($apkManifestOutput -join "`n")"
    }
    $apkManifest = $apkManifestOutput -join "`n"
    if ($apkManifest -match '(?is)debuggable' -or $apkManifest -match '(?is)testOnly') {
        throw 'The release APK manifest contains a debug or test flag.'
    }
    foreach ($debugEntry in @(
        'DebugMusxiApplication',
        'DeveloperActivity',
        'ValidationFocusActivity',
        'QqWebLoginValidationActivity',
        'KugouWebLoginValidationActivity',
        'GlassPreviewActivity',
        'ValidationReceiver'
    )) {
        if ($apkManifest.Contains($debugEntry)) {
            throw "The release APK manifest contains debug entry '$debugEntry'."
        }
    }

    $apkName = "Musxi-Player-$($Metadata.VersionName).apk"
    $destinationApk = Join-Path $ArtifactDirectory $apkName
    $sourceHash = (Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash.ToLowerInvariant()
    Copy-Item -LiteralPath $Apk -Destination $destinationApk -Force
    $sha256 = (Get-FileHash -LiteralPath $destinationApk -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($sha256 -ne $sourceHash) {
        throw 'Copied release APK hash differs from the verified build output.'
    }
    Write-Utf8NoBom (Join-Path $ArtifactDirectory 'SHA256SUMS.txt') "$sha256  $apkName`n"

    $manifest = [ordered]@{
        schemaVersion = 1
        packageName = $Metadata.PackageName
        versionName = $Metadata.VersionName
        versionCode = $Metadata.VersionCode
        minSdk = $Metadata.MinSdk
        assetName = $apkName
        bytes = (Get-Item -LiteralPath $destinationApk).Length
        sha256 = $sha256
        signerSha256 = $signerSha256
    }
    $expectedKeys = @(
        'schemaVersion',
        'packageName',
        'versionName',
        'versionCode',
        'minSdk',
        'assetName',
        'bytes',
        'sha256',
        'signerSha256'
    )
    $actualKeys = @($manifest.Keys)
    $keyDifference = @(Compare-Object -ReferenceObject $expectedKeys -DifferenceObject $actualKeys)
    if ($keyDifference.Count -ne 0) {
        throw 'The generated update manifest does not match schemaVersion 1.'
    }
    $manifestJson = ($manifest | ConvertTo-Json -Compress) + "`n"
    if ([Text.Encoding]::UTF8.GetByteCount($manifestJson) -gt 4096) {
        throw 'The generated update manifest exceeds 4 KiB.'
    }
    Write-Utf8NoBom (Join-Path $ArtifactDirectory 'update-manifest.json') $manifestJson

    Write-Host "Release APK: $destinationApk"
    Write-Host "Release APK SHA-256: $sha256"
    Write-Host "Release signing certificate SHA-256 (public): $signerSha256"
    Write-Host "Update manifest: $(Join-Path $ArtifactDirectory 'update-manifest.json')"
    return [pscustomobject]@{
        Apk = $destinationApk
        VersionName = $Metadata.VersionName
        VersionCode = $Metadata.VersionCode
        MinSdk = $Metadata.MinSdk
    }
}

if (!$env:JAVA_HOME) { throw 'Set JAVA_HOME to a JDK 17 installation.' }
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
if (!(Test-Path -LiteralPath $java)) { throw 'JAVA_HOME does not contain bin/java.exe.' }

$androidSdk = if ($Release -or $Install) { Get-AndroidSdk } else { $null }
$androidTools = if ($Release) { Get-AndroidBuildTools $androidSdk } else { $null }
[string[]]$tasks = @(if ($Release) { ':app:assembleRelease' } else { ':app:assembleDebug' })
if ($Release) { $tasks += '--no-configuration-cache' }
if (!$Release -and $Test) {
    $tasks += @(
        ':app:testDebugUnitTest',
        ':accounts:testDebugUnitTest',
        ':core:test',
        ':platform-kugou:testDebugUnitTest',
        ':platform-qq:testDebugUnitTest',
        ':platform-netease:testDebugUnitTest',
        ':playback:testDebugUnitTest'
    )
}
if (!$Release -and $Lint) {
    $tasks += @(
        ':app:lintDebug',
        ':accounts:lintDebug',
        ':platform-kugou:lintDebug',
        ':platform-qq:lintDebug',
        ':platform-netease:lintDebug',
        ':playback:lintDebug'
    )
}

$originalReleasePassword = $env:MUSXI_RELEASE_STORE_PASSWORD
$setReleasePassword = $false
Push-Location $PSScriptRoot
try {
    if ($Release -and [string]::IsNullOrEmpty($env:MUSXI_RELEASE_STORE_PASSWORD)) {
        $securePassword = Read-Host 'Release keystore password' -AsSecureString
        if ($securePassword.Length -eq 0) {
            throw 'The release keystore password cannot be empty.'
        }
        $passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
        try {
            $env:MUSXI_RELEASE_STORE_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
            $setReleasePassword = $true
        } finally {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
        }
    }

    & $java '-cp' 'gradle/wrapper/gradle-wrapper.jar' 'org.gradle.wrapper.GradleWrapperMain' @tasks '--console=plain'
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed ($LASTEXITCODE)." }

    if ($Release) {
        $sourceApk = Join-Path $PSScriptRoot 'app/build/outputs/apk/release/app-release.apk'
        if (!(Test-Path -LiteralPath $sourceApk -PathType Leaf)) {
            throw 'Signed release APK app/build/outputs/apk/release/app-release.apk was not produced.'
        }
        $metadata = Get-ReleaseApkMetadata -Apk $sourceApk -Aapt $androidTools.Aapt
        $versionedArtifactDirectory = Join-Path $PSScriptRoot ".local/artifacts/release-$($metadata.VersionName)"
        New-Item -ItemType Directory -Path $versionedArtifactDirectory -Force | Out-Null
        $releaseResult = Invoke-ReleaseVerification -Apk $sourceApk -Apksigner $androidTools.Apksigner -Aapt $androidTools.Aapt -ArtifactDirectory $versionedArtifactDirectory -Metadata $metadata
        $apk = $releaseResult.Apk
        $launchPackage = 'tech.musxi.player'
    } else {
        $apk = Join-Path $PSScriptRoot 'app/build/outputs/apk/debug/app-debug.apk'
        $debugHash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
        Write-Host "Debug APK SHA-256: $debugHash"
        $launchPackage = 'tech.musxi.player.dev'
    }

    if ($Install) {
        $adb = Join-Path $androidSdk 'platform-tools/adb.exe'
        if (!(Test-Path -LiteralPath $adb)) { throw 'Android SDK platform-tools/adb.exe was not found.' }
        & $adb install -r $apk
        if ($LASTEXITCODE -ne 0) { throw 'adb install failed.' }
        & $adb shell am start --display 0 -n "$launchPackage/tech.musxi.player.MainActivity"
        if ($LASTEXITCODE -ne 0) { throw 'Could not open MainActivity.' }
    }
} finally {
    if ($setReleasePassword) {
        if ($null -eq $originalReleasePassword) {
            [Environment]::SetEnvironmentVariable('MUSXI_RELEASE_STORE_PASSWORD', $null, 'Process')
        } else {
            [Environment]::SetEnvironmentVariable('MUSXI_RELEASE_STORE_PASSWORD', $originalReleasePassword, 'Process')
        }
        $originalReleasePassword = $null
    }
    Pop-Location
}
