$ErrorActionPreference = "Stop"

$env:JAVA_HOME = "D:\Program Files\Android\jbr"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

Write-Host "Verifying project..." -ForegroundColor Cyan
.\gradlew.bat :shared:test :backend:test :app:compileDebugKotlin

if (-not (Test-Path $adb)) {
    Write-Warning "ADB not found at $adb. Skipping device install."
    exit 0
}

$devices = & $adb devices | Select-String -Pattern "\tdevice$"
if (-not $devices) {
    Write-Host "No Android devices connected. Built and tested only." -ForegroundColor Yellow
    exit 0
}

foreach ($devLine in $devices) {
    $deviceId = $devLine.ToString().Split("`t")[0]
    Write-Host "Installing debug build on $deviceId..." -ForegroundColor Cyan
    $env:ANDROID_SERIAL = $deviceId
    .\gradlew.bat :app:installDebug
}

Write-Host "Deployment finished." -ForegroundColor Cyan
