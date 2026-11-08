$ErrorActionPreference = "Stop"

$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ProjectDir

if (-not $env:ANDROID_HOME) {
    $DefaultSdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
    if (Test-Path $DefaultSdk) { $env:ANDROID_HOME = $DefaultSdk }
}
if (-not $env:ANDROID_HOME) {
    throw "ANDROID_HOME is not set. Point it to the Android SDK directory."
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
if (-not $env:JAVA_HOME) {
    throw "JAVA_HOME is not set. JDK 17 (Android Studio JBR is suitable) is required."
}
if (-not (Get-Command go -ErrorAction SilentlyContinue)) {
    $goCandidate = Get-ChildItem -Path "D:\*\go\bin\go.exe" -File -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($goCandidate) {
        $env:Path = "$($goCandidate.DirectoryName);$env:Path"
    }
}

$GoPath = (& go env GOPATH).Trim()
if ($LASTEXITCODE -ne 0 -or -not $GoPath) {
    throw "Go is unavailable. Install Go 1.26+ and add it to PATH."
}
$env:Path = "$env:JAVA_HOME\bin;$GoPath\bin;$env:Path"

$env:ANDROID_NDK_HOME = Join-Path $env:ANDROID_HOME "ndk\26.1.10909125"
if (-not (Test-Path $env:ANDROID_NDK_HOME)) {
    throw "Android NDK 26.1.10909125 was not found at $env:ANDROID_NDK_HOME"
}

$MobileVersion = "v0.0.0-20260821190718-4776eadac327"
go install "golang.org/x/mobile/cmd/gomobile@$MobileVersion"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
go install "golang.org/x/mobile/cmd/gobind@$MobileVersion"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
go get "golang.org/x/mobile@$MobileVersion"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

New-Item -ItemType Directory -Force -Path "libs" | Out-Null
gomobile bind `
    -androidapi 21 `
    -target "android/arm64,android/arm,android/amd64" `
    -ldflags "-s -w -extldflags=-Wl,-z,max-page-size=16384" `
    -o "libs/nexuscore.aar" `
    ./mobile
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "Built core-go/libs/nexuscore.aar"
