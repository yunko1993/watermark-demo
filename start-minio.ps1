param([string]$MinioExe = 'C:\develop\minio.exe')
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
if (-not (Test-Path -LiteralPath $MinioExe)) { throw "MinIO executable not found: $MinioExe" }
if (Get-NetTCPConnection -State Listen -LocalPort 9000 -ErrorAction SilentlyContinue) {
    throw 'Port 9000 is already in use. Reuse the existing MinIO or stop it manually.'
}
New-Item -ItemType Directory -Force -Path '.local/minio-data' | Out-Null
if (-not $env:MINIO_ROOT_USER) { $env:MINIO_ROOT_USER = 'minioadmin' }
if (-not $env:MINIO_ROOT_PASSWORD) { $env:MINIO_ROOT_PASSWORD = 'minioadmin' }
Write-Host 'MinIO API: http://127.0.0.1:9000 | Console: http://127.0.0.1:9001'
& $MinioExe server "$PSScriptRoot\.local\minio-data" --address 127.0.0.1:9000 --console-address 127.0.0.1:9001
