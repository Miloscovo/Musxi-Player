param([switch]$Test, [switch]$Lint, [switch]$Install)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (!$env:JAVA_HOME) { throw 'Set JAVA_HOME to a JDK 17 installation.' }
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
if (!(Test-Path -LiteralPath $java)) { throw 'JAVA_HOME does not contain bin/java.exe.' }
$tasks = @(':app:assembleDebug')
if ($Test) { $tasks += @(':app:testDebugUnitTest', ':core:test', ':platform-kugou:testDebugUnitTest', ':platform-qq:testDebugUnitTest', ':platform-netease:testDebugUnitTest', ':playback:testDebugUnitTest') }
if ($Lint) { $tasks += @(':app:lintDebug', ':accounts:lintDebug', ':platform-kugou:lintDebug', ':platform-qq:lintDebug', ':platform-netease:lintDebug', ':playback:lintDebug') }
Push-Location $PSScriptRoot
try {
    & $java '-cp' 'gradle/wrapper/gradle-wrapper.jar' 'org.gradle.wrapper.GradleWrapperMain' @tasks '--console=plain'
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed ($LASTEXITCODE)." }
    $apk = Join-Path $PSScriptRoot 'app/build/outputs/apk/debug/app-debug.apk'
    Get-FileHash -LiteralPath $apk -Algorithm SHA256
    if ($Install) {
        $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }
        if (!$sdk) { throw 'Set ANDROID_HOME for adb installation.' }
        $adb = Join-Path $sdk 'platform-tools/adb.exe'
        & $adb install -r $apk
        if ($LASTEXITCODE -ne 0) { throw 'adb install failed.' }
        & $adb shell am start --display 0 -n 'tech.musxi.player.dev/tech.musxi.player.MainActivity'
        if ($LASTEXITCODE -ne 0) { throw 'Could not open MainActivity.' }
    }
} finally { Pop-Location }
