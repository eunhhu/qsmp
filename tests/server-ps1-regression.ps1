$ErrorActionPreference = "Stop"
$RootDir = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Write-Host "server.ps1 Java detection regression: SKIP (Java is not installed)"
    exit 0
}

$output = (& powershell.exe -NoProfile -ExecutionPolicy Bypass `
    -File (Join-Path $RootDir "server.ps1") check 2>&1 | Out-String)

if ($output -notmatch "Java: version \d+") {
    throw "Expected installed Java version detection, got:`n$output"
}

Write-Host "server.ps1 Java detection regression: PASS"
