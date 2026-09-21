param([switch]$NoBrowser)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$healthUrl = 'http://127.0.0.1:9000/minio/health/live'
function Test-MinioReady {
    try { return (Invoke-WebRequest -Uri $healthUrl -UseBasicParsing -TimeoutSec 2).StatusCode -eq 200 }
    catch { return $false }
}
if (Test-MinioReady) {
    Write-Host 'MinIO is already running. Reusing the existing service.'
} else {
    if (Get-NetTCPConnection -State Listen -LocalPort 9000 -ErrorAction SilentlyContinue) {
        throw 'Port 9000 is occupied but MinIO is not healthy. Check the existing service.'
    }
    New-Item -ItemType Directory -Force -Path '.local' | Out-Null
    $scriptPath = Join-Path $PSScriptRoot 'start-minio.ps1'
    $launcher = Start-Process -FilePath 'powershell.exe' -ArgumentList ('-NoProfile -ExecutionPolicy Bypass -File "{0}"' -f $scriptPath) -WorkingDirectory $PSScriptRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $PSScriptRoot '.local/minio.out.log') -RedirectStandardError (Join-Path $PSScriptRoot '.local/minio.err.log') -PassThru
    $launcher.Id | Set-Content '.local/minio-launcher.pid'
    $ready = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        if (Test-MinioReady) { $ready = $true; break }
        $launcher.Refresh()
        if ($launcher.HasExited) { break }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw 'MinIO startup failed. Check .local/minio.err.log and .local/minio.out.log.' }
    Write-Host 'MinIO started in the background. Closing this window will not stop it.'
}
Write-Host 'API: http://127.0.0.1:9000 | Console: http://127.0.0.1:9001'
if (-not $NoBrowser) { Start-Process 'http://127.0.0.1:9001' }
