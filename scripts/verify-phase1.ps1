$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

Write-Host "Backend tests"
Set-Location "$root\backend"
mvn test
if ($LASTEXITCODE -ne 0) { throw "Backend tests failed" }

Write-Host "Frontend tests"
Set-Location "$root\frontend"
npm test
if ($LASTEXITCODE -ne 0) { throw "Frontend tests failed" }

Write-Host "ML tests"
Set-Location "$root\ml-service"
if (Test-Path ".\.venv\Scripts\Activate.ps1") {
  . .\.venv\Scripts\Activate.ps1
}
pytest
if ($LASTEXITCODE -ne 0) { throw "ML tests failed" }

Write-Host "Phase 1 automated tests passed"
