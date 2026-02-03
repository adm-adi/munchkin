$ErrorActionPreference = "Stop"

# 1. Setup Environment
$env:JAVA_HOME = "D:\Program Files\Android\jbr"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$ADB = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$PYTHON = "python" # Assumes python is in PATH

Write-Host "Starting Deployment..." -ForegroundColor Cyan

# 2. Git Operations (Initial Save)
Write-Host "Git: Adding and Pushing changes..." -ForegroundColor Yellow
& "C:\Program Files\Git\cmd\git.exe" add .
try {
    & "C:\Program Files\Git\cmd\git.exe" commit -m "Auto-deploy: Update server and client"
}
catch {
    Write-Host "No changes to commit." -ForegroundColor Gray
}
& "C:\Program Files\Git\cmd\git.exe" push

# 3. Android Installation or Release
Write-Host "Android: Checking devices..." -ForegroundColor Yellow

if (-not (Test-Path $ADB)) {
    Write-Error "ADB not found at $ADB"
}

$devices = & $ADB devices | Select-String -Pattern "\tdevice$"

if (-not $devices) {
    Write-Warning "No devices connected! Initiating Release Process..." -ForegroundColor Magenta
    
    # 3b. Release Process
    try {
        Write-Host "Bumping version..." -ForegroundColor Cyan
        $newVersion = & $PYTHON scripts/bump_version.py
        $newVersion = $newVersion.Trim()
        
        if (-not $newVersion) {
            throw "Failed to get new version"
        }
        
        Write-Host "New Version: $newVersion" -ForegroundColor Green
        
        # Build Verification
        Write-Host "Verifying Build (assembleRelease)..." -ForegroundColor Cyan
        cmd /c "gradlew.bat assembleRelease"
        if ($LASTEXITCODE -ne 0) {
            throw "Build Failed! Aborting release."
        }
        
        # Commit bump
        & "C:\Program Files\Git\cmd\git.exe" add app/build.gradle.kts
        & "C:\Program Files\Git\cmd\git.exe" commit -m "Release v$newVersion"
        
        # Tag
        Write-Host "Creating Tag v$newVersion..." -ForegroundColor Cyan
        & "C:\Program Files\Git\cmd\git.exe" tag -a "v$newVersion" -m "Release v$newVersion"
        
        # Push
        Write-Host "Pushing Release..." -ForegroundColor Cyan
        & "C:\Program Files\Git\cmd\git.exe" push origin main
        & "C:\Program Files\Git\cmd\git.exe" push origin "v$newVersion"
        
        Write-Host "✅ Release v$newVersion Published to GitHub!" -ForegroundColor Green
        
    }
    catch {
        Write-Error "Release failed: $_"
        # Reset changes to build.gradle.kts if failed?
        # & "C:\Program Files\Git\cmd\git.exe" checkout app/build.gradle.kts
    }
    
    exit
}

# 3a. Install on Devices
foreach ($devLine in $devices) {
    $deviceId = $devLine.ToString().Split("`t")[0]
    Write-Host "Installing on device: $deviceId" -ForegroundColor Green
    
    $env:ANDROID_SERIAL = $deviceId
    try {
        .\gradlew.bat installDebug
        Write-Host "Installation complete for $deviceId" -ForegroundColor Green
    }
    catch {
        Write-Error "Clean/Install failed for $deviceId"
    }
}

Write-Host "Deployment Finished!" -ForegroundColor Cyan
