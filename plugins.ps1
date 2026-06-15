[CmdletBinding()]
param(
    [ValidateSet("resolve", "update", "check", "self-test")]
    [string]$Action = "update"
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

Push-Location $RootDir
try {
    & $JavaCommand (Join-Path $RootDir "scripts\PluginManager.java") $Action
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
