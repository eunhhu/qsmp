[CmdletBinding()]
param(
    [ValidateSet("inject", "check", "reset", "self-test")]
    [string]$Action = "check",
    [switch]$ConfirmReset
)

$ErrorActionPreference = "Stop"
$RootDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$JavaCommand = "auto"
foreach ($line in Get-Content -LiteralPath (Join-Path $RootDir "server.env")) {
    if ($line -match "^\s*JAVA_CMD\s*=(.*)\s*$") {
        $JavaCommand = $Matches[1].Trim().Trim('"').Trim("'")
    }
}

if ($JavaCommand -eq "auto") {
    $localJava = Join-Path $RootDir "runtime\java\bin\java.exe"
    $localJavaUnix = Join-Path $RootDir "runtime/java/bin/java"
    if (Test-Path -LiteralPath $localJava -PathType Leaf) {
        $JavaCommand = $localJava
    }
    elseif (Test-Path -LiteralPath $localJavaUnix -PathType Leaf) {
        $JavaCommand = $localJavaUnix
    }
    else {
        $JavaCommand = "java"
    }
}

$arguments = @((Join-Path $RootDir "scripts\WorldManager.java"), $Action)
if ($Action -eq "reset") {
    if (-not $ConfirmReset) {
        throw "Map reset requires -ConfirmReset."
    }
    $arguments += "CONFIRM"
}

Push-Location $RootDir
try {
    & $JavaCommand $arguments
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
