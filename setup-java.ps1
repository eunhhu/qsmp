[CmdletBinding()]
param(
    [switch]$DetectArchitecture
)

$ErrorActionPreference = "Stop"
$RootDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RuntimeDir = Join-Path $RootDir "runtime"
$JavaDir = Join-Path $RuntimeDir "java"
$StageDir = Join-Path $RuntimeDir "java-stage"
$Archive = Join-Path $RuntimeDir "temurin-25.zip"
$UserAgent = "qsmp-runtime-manager/1.0"

function Remove-RuntimePath {
    param([string]$Path)

    $resolvedRuntime = [System.IO.Path]::GetFullPath($RuntimeDir)
    $resolvedTarget = [System.IO.Path]::GetFullPath($Path)
    $prefix = $resolvedRuntime.TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
    if (-not $resolvedTarget.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to remove path outside runtime: $resolvedTarget"
    }
    Remove-Item -LiteralPath $resolvedTarget -Recurse -Force -ErrorAction SilentlyContinue
}

function Get-AdoptiumArchitecture {
    switch ([System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString()) {
        "X64" { return "x64" }
        "Arm64" { return "aarch64" }
        default { throw "Unsupported Windows architecture: $_" }
    }
}

$architecture = Get-AdoptiumArchitecture
if ($DetectArchitecture) {
    Write-Output $architecture
    exit 0
}

$existingJava = Join-Path $JavaDir "bin\java.exe"
if (Test-Path -LiteralPath $existingJava -PathType Leaf) {
    $version = (& $existingJava --version | Select-Object -First 1)
    if ($version -match '^(?:openjdk|java)\s+(?<major>\d+)' -and [int]$Matches["major"] -ge 25) {
        Write-Host "Portable Java is already installed: $version"
        exit 0
    }
}

New-Item -ItemType Directory -Path $RuntimeDir -Force | Out-Null
Remove-RuntimePath -Path $StageDir
Remove-Item -LiteralPath $Archive -Force -ErrorAction SilentlyContinue

$metadataUrl = "https://api.adoptium.net/v3/assets/latest/25/hotspot?architecture=$architecture&image_type=jdk&os=windows&vendor=eclipse"
$release = Invoke-RestMethod -Uri $metadataUrl -Headers @{ "User-Agent" = $UserAgent }
if (-not $release -or -not $release[0].binary.package.link) {
    throw "No Eclipse Temurin Java 25 package found."
}

$package = $release[0].binary.package
if ($package.link -notmatch '^https://github\.com/adoptium/temurin25-binaries/') {
    throw "Unexpected Java download host."
}

Write-Host "Downloading Eclipse Temurin $($release[0].version.semver)..."
Invoke-WebRequest -Uri $package.link -OutFile $Archive -Headers @{ "User-Agent" = $UserAgent }
$actualHash = (Get-FileHash -LiteralPath $Archive -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actualHash -ne $package.checksum.ToLowerInvariant()) {
    Remove-Item -LiteralPath $Archive -Force
    throw "Java archive SHA-256 verification failed."
}

New-Item -ItemType Directory -Path $StageDir -Force | Out-Null
Expand-Archive -LiteralPath $Archive -DestinationPath $StageDir -Force
$extracted = Get-ChildItem -LiteralPath $StageDir -Directory |
    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName "bin\java.exe") } |
    Select-Object -First 1
if (-not $extracted) {
    throw "Extracted Java runtime was not found."
}

$candidate = Join-Path $RuntimeDir "java-new"
Remove-RuntimePath -Path $candidate
Move-Item -LiteralPath $extracted.FullName -Destination $candidate
$candidateJava = Join-Path $candidate "bin\java.exe"
$candidateVersion = (& $candidateJava --version | Select-Object -First 1)
if ($candidateVersion -notmatch '^(?:openjdk|java)\s+(?<major>\d+)' -or [int]$Matches["major"] -lt 25) {
    throw "Downloaded runtime is not Java 25 or newer."
}

Remove-RuntimePath -Path $JavaDir
Move-Item -LiteralPath $candidate -Destination $JavaDir
Remove-RuntimePath -Path $StageDir
Remove-Item -LiteralPath $Archive -Force
Write-Host "Installed $candidateVersion in $JavaDir"
