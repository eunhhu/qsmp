$ErrorActionPreference = "Stop"
$rootDir = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$installer = Join-Path $rootDir "setup-java.ps1"

$originalArchitecture = $env:PROCESSOR_ARCHITECTURE
$originalWowArchitecture = $env:PROCESSOR_ARCHITEW6432
try {
    $env:PROCESSOR_ARCHITECTURE = ""
    $env:PROCESSOR_ARCHITEW6432 = ""
    $output = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $installer -DetectArchitecture 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Architecture detection failed: $output"
    }
    if (($output | Out-String).Trim() -notin @("x64", "aarch64")) {
        throw "Unexpected architecture result: $output"
    }
}
finally {
    $env:PROCESSOR_ARCHITECTURE = $originalArchitecture
    $env:PROCESSOR_ARCHITEW6432 = $originalWowArchitecture
}

Write-Host "setup-java.ps1 architecture regression: PASS"
