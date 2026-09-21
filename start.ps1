$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) { throw 'Maven is required. Add Maven/bin to PATH.' }
Write-Host 'Starting watermark demo: http://127.0.0.1:8088'
& mvn spring-boot:run
exit $LASTEXITCODE
