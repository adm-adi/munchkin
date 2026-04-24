$ErrorActionPreference = "Stop"

$remote = "root@23.88.48.58"
$key = "C:\Users\Alejandro\.ssh\hetzner_key"
$remoteRoot = "/opt/munchkin-backend"
$serviceName = "munchkin"
$archive = "backend\build\distributions\backend.zip"

Write-Host "Verifying backend..." -ForegroundColor Cyan
.\gradlew.bat :shared:test :backend:test :backend:distZip
if ($LASTEXITCODE -ne 0) {
    throw "Backend verification failed."
}

if (-not (Test-Path $archive)) {
    throw "Backend distribution archive was not created: $archive"
}

Write-Host "Uploading backend package..." -ForegroundColor Cyan
scp -i $key -o StrictHostKeyChecking=no $archive "${remote}:/tmp/munchkin-backend-prod.zip"
if ($LASTEXITCODE -ne 0) {
    throw "Backend package upload failed."
}

Write-Host "Installing and restarting backend..." -ForegroundColor Cyan
$remoteScript = @"
set -e
rm -rf $remoteRoot/app $remoteRoot/app_tmp
mkdir -p $remoteRoot/app $remoteRoot/app_tmp
unzip -oq /tmp/munchkin-backend-prod.zip -d $remoteRoot/app_tmp
if [ -d "$remoteRoot/app_tmp/backend" ]; then
  cp -a "$remoteRoot/app_tmp/backend/." "$remoteRoot/app/"
else
  cp -a "$remoteRoot/app_tmp/." "$remoteRoot/app/"
fi
rm -rf $remoteRoot/app_tmp
chmod +x "$remoteRoot/app/bin/backend"
if [ ! -f "$remoteRoot/backend.env" ]; then
  echo "Missing $remoteRoot/backend.env" >&2
  exit 1
fi
set -a
. "$remoteRoot/backend.env"
set +a
java -cp "$remoteRoot/app/lib/*" com.munchkin.backend.DatabaseMigration
cat >/etc/systemd/system/$serviceName.service <<'UNIT'
[Unit]
Description=Munchkin Ktor Backend
After=network.target docker.service
Requires=docker.service

[Service]
Type=simple
User=root
WorkingDirectory=/opt/munchkin-backend/app
EnvironmentFile=/opt/munchkin-backend/backend.env
ExecStart=/opt/munchkin-backend/app/bin/backend
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable $serviceName
systemctl restart $serviceName
systemctl --no-pager --full status $serviceName
"@

$remoteScript = $remoteScript -replace "`r", ""
$remoteScript | ssh -o StrictHostKeyChecking=no -i $key $remote "tr -d '\r' | bash -s"
if ($LASTEXITCODE -ne 0) {
    throw "Remote backend install failed."
}

Write-Host "Backend deployment finished." -ForegroundColor Cyan
