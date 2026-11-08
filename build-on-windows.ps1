$ErrorActionPreference = "Stop"

if (-not $env:ANDROID_HOME -and (Test-Path "$env:LOCALAPPDATA\Android\Sdk")) {
    $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
}
if (-not $env:JAVA_HOME) {
    $jdkCandidates = @(
        Get-ChildItem -Path "D:\*\jdk-17" -Directory -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName
    ) + @("C:\Program Files\Android\Android Studio\jbr")
    foreach ($candidate in $jdkCandidates) {
        if (Test-Path "$candidate\bin\java.exe") {
            $env:JAVA_HOME = $candidate
            break
        }
    }
}

$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ProjectDir

& "$ProjectDir\core-go\build.ps1"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

# core-go/build.ps1 changes the process working directory. Gradle must run from
# the repository root, otherwise it treats core-go as an unrelated build.
Set-Location $ProjectDir

& "$ProjectDir\gradlew.bat" `
    :core:test `
    :tunnel:test `
    :app:testDebugUnitTest `
    lintDebug `
    lintVitalRelease `
    assembleRelease
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "Release APK files are in app\build\outputs\apk\release"
