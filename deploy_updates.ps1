param(
    [switch]$AllowDirty
)

$ErrorActionPreference = "Stop"

function Resolve-Tool {
    param(
        [string]$CommandName,
        [string[]]$Fallbacks = @()
    )
    $cmd = Get-Command $CommandName -ErrorAction SilentlyContinue
    if ($cmd) {
        return $cmd.Source
    }
    foreach ($candidate in $Fallbacks) {
        if ($candidate -and (Test-Path $candidate)) {
            return $candidate
        }
    }
    throw "Required tool '$CommandName' not found."
}

function Invoke-Checked {
    param(
        [string]$Label,
        [scriptblock]$Action
    )
    Write-Host "Running: $Label..." -ForegroundColor Cyan
    & $Action
    if ($LASTEXITCODE -ne 0) {
        throw "$Label failed with exit code $LASTEXITCODE"
    }
}

# 1. Setup Environment
if (-not $env:JAVA_HOME -and (Test-Path "D:\Program Files\Android\jbr")) {
    $env:JAVA_HOME = "D:\Program Files\Android\jbr"
}
if ($env:JAVA_HOME) {
    $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
}

$GIT = Resolve-Tool -CommandName "git" -Fallbacks @("C:\Program Files\Git\cmd\git.exe")
$NPM = Resolve-Tool -CommandName "npm"
$PYTHON = Resolve-Tool -CommandName "python" -Fallbacks @("python3")
$ADB = Resolve-Tool -CommandName "adb" -Fallbacks @(
    "$env:ANDROID_HOME\platform-tools\adb.exe",
    "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
)

Write-Host "Starting Deployment..." -ForegroundColor Cyan

# 2. Verify clean tracked git tree unless -AllowDirty
if (-not $AllowDirty) {
    $dirtyTracked = & $GIT status --porcelain --untracked-files=no
    if ($LASTEXITCODE -ne 0) {
        throw "git status failed with exit code $LASTEXITCODE"
    }
    if ($dirtyTracked) {
        throw "Tracked working tree has uncommitted changes. Commit/stash them or pass -AllowDirty."
    }
}

# 3. Check connected ADB devices
Write-Host "Checking connected Android devices..." -ForegroundColor Yellow
$devices = & $ADB devices | Select-String -Pattern "\tdevice$"
if ($LASTEXITCODE -ne 0) {
    throw "adb devices failed with exit code $LASTEXITCODE"
}

if (-not $devices) {
    Write-Host "No devices connected. Initiating verified release flow..." -ForegroundColor Magenta

    Invoke-Checked -Label "Server syntax & type check" -Action { & $NPM --prefix server run check }
    Invoke-Checked -Label "Server unit & integration tests" -Action { & $NPM --prefix server test }
    Invoke-Checked -Label "Android unit tests & lint" -Action { .\gradlew.bat :app:testDebugUnitTest :app:lintDebug --no-daemon }

    Write-Host "Bumping synchronized version across Android & server..." -ForegroundColor Cyan
    $newVersion = (& $PYTHON scripts/bump_version.py | Select-Object -Last 1).Trim()
    if ($LASTEXITCODE -ne 0 -or -not $newVersion) {
        throw "scripts/bump_version.py failed with exit code $LASTEXITCODE"
    }
    Write-Host "New Version: $newVersion" -ForegroundColor Green

    Invoke-Checked -Label "Android Release APK verification" -Action { .\gradlew.bat :app:assembleRelease --no-daemon }

    Invoke-Checked -Label "Stage version bump files" -Action {
        & $GIT add app/build.gradle.kts server/package.json server/package-lock.json
    }
    Invoke-Checked -Label "Commit version bump" -Action {
        & $GIT commit -m "Release v$newVersion"
    }
    Invoke-Checked -Label "Create annotated release tag v$newVersion" -Action {
        & $GIT tag -a "v$newVersion" -m "Release v$newVersion"
    }

    $currentBranch = (& $GIT rev-parse --abbrev-ref HEAD).Trim()
    if ($LASTEXITCODE -ne 0 -or -not $currentBranch) {
        throw "Could not determine current git branch"
    }

    Invoke-Checked -Label "Push commit to origin/$currentBranch" -Action { & $GIT push origin $currentBranch }
    Invoke-Checked -Label "Push tag v$newVersion to origin" -Action { & $GIT push origin "v$newVersion" }

    Write-Host "Release v$newVersion published to GitHub!" -ForegroundColor Green
    exit 0
}

# 4. Install Release build on connected Android devices
Invoke-Checked -Label "Server syntax & type check" -Action { & $NPM --prefix server run check }
Invoke-Checked -Label "Server unit & integration tests" -Action { & $NPM --prefix server test }
Invoke-Checked -Label "Android unit tests, lint & Release APK build" -Action {
    .\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleRelease --no-daemon
}

foreach ($devLine in $devices) {
    $deviceId = $devLine.ToString().Split("`t")[0]
    Write-Host "Installing on device: $deviceId" -ForegroundColor Green
    $env:ANDROID_SERIAL = $deviceId

    Write-Host "Uninstalling previous package (if present)..." -ForegroundColor Yellow
    & $ADB -s $deviceId uninstall com.munchkin.app | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Package not previously installed or uninstall skipped ($LASTEXITCODE)." -ForegroundColor Gray
    }

    Invoke-Checked -Label "Install Release APK on $deviceId" -Action { .\gradlew.bat installRelease --no-daemon }
    Write-Host "Installation complete for $deviceId" -ForegroundColor Green
}

Write-Host "Deployment Finished!" -ForegroundColor Cyan
