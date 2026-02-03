$ErrorActionPreference = "Stop"

# 1. Setup Environment
$env:JAVA_HOME = "D:\Program Files\Android\jbr"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$ADB = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

Write-Host "Starting Deployment..." -ForegroundColor Cyan

# 2. Git Operations
Write-Host "Git: Adding and Pushing changes..." -ForegroundColor Yellow
& "C:\Program Files\Git\cmd\git.exe" add .
try {
    & "C:\Program Files\Git\cmd\git.exe" commit -m "Auto-deploy: Update server and client"
} catch {
    Write-Host "No changes to commit." -ForegroundColor Gray
}
& "C:\Program Files\Git\cmd\git.exe" push

# 3. Android Installation
Write-Host "Android: Checking devices..." -ForegroundColor Yellow

if (-not (Test-Path $ADB)) {
    Write-Error "ADB not found at $ADB"
}

$devices = & $ADB devices | Select-String -Pattern "\tdevice$"
if (-not $devices) {
    Write-Warning "No devices connected!"
    exit
}

foreach ($devLine in $devices) {
    $deviceId = $devLine.ToString().Split("`t")[0]
    Write-Host "Installing on device: $deviceId" -ForegroundColor Green
    
    $env:ANDROID_SERIAL = $deviceId
    try {
        .\gradlew.bat installDebug
        Write-Host "Installation complete for $deviceId" -ForegroundColor Green
    } catch {
        Write-Error "Clean/Install failed for $deviceId"
    }
}

Write-Host "Deployment Finished!" -ForegroundColor Cyan
