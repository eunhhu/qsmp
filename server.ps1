[CmdletBinding()]
param(
    [ValidateSet("start", "update", "check")]
    [string]$Action = "start"
)

$ErrorActionPreference = "Stop"
$RootDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ConfigFile = Join-Path $RootDir "server.env"
$LockFile = Join-Path $RootDir ".server-running"
$InstallFile = Join-Path $RootDir ".purpur-install"
$RequiredJavaMajor = 25
$UserAgent = "qsmp-bootstrap/1.0 (Purpur server setup)"

function Read-ServerConfig {
    if (-not (Test-Path -LiteralPath $ConfigFile)) {
        throw "Missing $ConfigFile"
    }

    $config = @{}
    foreach ($line in Get-Content -LiteralPath $ConfigFile) {
        if ($line -match "^\s*#" -or [string]::IsNullOrWhiteSpace($line)) {
            continue
        }

        if ($line -notmatch "^\s*([A-Z0-9_]+)\s*=(.*)\s*$") {
            throw "Invalid server.env line: $line"
        }

        $config[$Matches[1]] = $Matches[2].Trim().Trim('"').Trim("'")
    }

    $defaults = @{
        PURPUR_BUILD = "latest"
        JAVA_CMD = "auto"
        MIN_MEMORY = "2G"
        MAX_MEMORY = "4G"
        SERVER_JAR = "server.jar"
    }

    foreach ($entry in $defaults.GetEnumerator()) {
        if (-not $config.ContainsKey($entry.Key) -or [string]::IsNullOrWhiteSpace($config[$entry.Key])) {
            $config[$entry.Key] = $entry.Value
        }
    }

    if (-not $config.ContainsKey("MC_VERSION") -or [string]::IsNullOrWhiteSpace($config["MC_VERSION"])) {
        throw "MC_VERSION is required in server.env"
    }

    return $config
}

function Resolve-JavaCommand {
    param([string]$ConfiguredCommand)

    if ($ConfiguredCommand -ne "auto") {
        return $ConfiguredCommand
    }

    $candidates = @(
        (Join-Path $RootDir "runtime\java\bin\java.exe"),
        (Join-Path $RootDir "runtime/java/bin/java")
    )
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return $candidate
        }
    }
    return "java"
}

function Test-Java {
    param([string]$JavaCommand)

    try {
        $versionOutput = (& $JavaCommand --version 2>&1 | Out-String)
    }
    catch {
        Write-Host "Java: missing ($JavaCommand)"
        return $false
    }

    if ($versionOutput -notmatch '(?m)^(?:openjdk|java)\s+(?:version\s+")?(?<major>\d+)') {
        Write-Host "Java: unable to read version"
        return $false
    }

    $major = [int]$Matches["major"]
    if ($major -lt $RequiredJavaMajor) {
        Write-Host "Java: version $major found; Java $RequiredJavaMajor+ is required"
        return $false
    }

    Write-Host "Java: version $major (OK)"
    return $true
}

function Assert-Java {
    param([string]$JavaCommand)

    if (-not (Test-Java -JavaCommand $JavaCommand)) {
        throw "Install Java 25, then run this command again."
    }
}

function Test-ServerRunning {
    if (-not (Test-Path -LiteralPath $LockFile)) {
        return $false
    }

    $lockPid = 0
    $pidText = (Get-Content -LiteralPath $LockFile -Raw).Trim()
    if ([int]::TryParse($pidText, [ref]$lockPid)) {
        if (Get-Process -Id $lockPid -ErrorAction SilentlyContinue) {
            return $true
        }
    }

    Remove-Item -LiteralPath $LockFile -Force
    return $false
}

function Assert-ServerStopped {
    if (Test-ServerRunning) {
        throw "The server appears to be running. Stop it before updating."
    }
}

function Test-JarFile {
    param([string]$Path)

    $stream = [System.IO.File]::OpenRead($Path)
    try {
        if ($stream.Length -lt 4) {
            return $false
        }

        $signature = New-Object byte[] 4
        [void]$stream.Read($signature, 0, 4)
        return $signature[0] -eq 0x50 -and
            $signature[1] -eq 0x4B -and
            $signature[2] -eq 0x03 -and
            $signature[3] -eq 0x04
    }
    finally {
        $stream.Dispose()
    }
}

function Update-Server {
    param(
        [hashtable]$Config,
        [string]$JarPath
    )

    Assert-ServerStopped

    $backupDir = Join-Path $RootDir "backups"
    $tempPath = "$JarPath.download"
    New-Item -ItemType Directory -Path $backupDir -Force | Out-Null
    Remove-Item -LiteralPath $tempPath -Force -ErrorAction SilentlyContinue

    $version = $Config["MC_VERSION"]
    $build = $Config["PURPUR_BUILD"]
    $url = "https://api.purpurmc.org/v2/purpur/$version/$build/download"

    Write-Host "Downloading Purpur $version build $build..."
    try {
        Invoke-WebRequest -Uri $url -OutFile $tempPath -Headers @{ "User-Agent" = $UserAgent }
    }
    catch {
        Remove-Item -LiteralPath $tempPath -Force -ErrorAction SilentlyContinue
        throw "Purpur download failed: $($_.Exception.Message)"
    }

    if (-not (Test-JarFile -Path $tempPath)) {
        Remove-Item -LiteralPath $tempPath -Force -ErrorAction SilentlyContinue
        throw "Downloaded file is not a valid JAR."
    }

    if (Test-Path -LiteralPath $JarPath) {
        $currentHash = (Get-FileHash -LiteralPath $JarPath -Algorithm SHA256).Hash
        $newHash = (Get-FileHash -LiteralPath $tempPath -Algorithm SHA256).Hash
        if ($currentHash -eq $newHash) {
            Remove-Item -LiteralPath $tempPath -Force
            Write-Host "Purpur is already up to date."
            return
        }

        $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
        $backupName = "purpur-$version-$timestamp.jar"
        Copy-Item -LiteralPath $JarPath -Destination (Join-Path $backupDir $backupName)
    }

    Move-Item -LiteralPath $tempPath -Destination $JarPath -Force
    @(
        "version=$version"
        "build=$build"
        "updated_at=$([DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ'))"
    ) | Set-Content -LiteralPath $InstallFile -Encoding ASCII
    Write-Host "Installed $JarPath"
}

function Test-EulaAccepted {
    $eulaPath = Join-Path $RootDir "eula.txt"
    if (-not (Test-Path -LiteralPath $eulaPath)) {
        return $false
    }

    return [bool](Select-String -LiteralPath $eulaPath -Pattern "^\s*eula=true\s*$" -Quiet)
}

function Test-Server {
    param(
        [hashtable]$Config,
        [string]$JarPath
    )

    $status = 0
    Write-Host "Minecraft: $($Config['MC_VERSION'])"
    Write-Host "Purpur build: $($Config['PURPUR_BUILD'])"
    Write-Host "Memory: $($Config['MIN_MEMORY']) to $($Config['MAX_MEMORY'])"

    if (-not (Test-Java -JavaCommand $Config["JAVA_CMD"])) {
        $status = 1
    }

    if (Test-Path -LiteralPath $JarPath) {
        Write-Host "Server JAR: present ($($Config['SERVER_JAR']))"
    }
    else {
        Write-Host "Server JAR: missing (run: .\server.ps1 update)"
        $status = 1
    }

    if (Test-EulaAccepted) {
        Write-Host "EULA: accepted"
    }
    else {
        Write-Host "EULA: not accepted (edit eula.txt after reading the EULA)"
        $status = 1
    }

    if (Test-ServerRunning) {
        Write-Host "Server process: running"
    }
    else {
        Write-Host "Server process: stopped"
    }

    return $status
}

function Start-Server {
    param(
        [hashtable]$Config,
        [string]$JarPath
    )

    if (-not (Test-Path -LiteralPath $JarPath)) {
        Update-Server -Config $Config -JarPath $JarPath
    }

    Assert-Java -JavaCommand $Config["JAVA_CMD"]
    if (-not (Test-EulaAccepted)) {
        throw "Read https://aka.ms/MinecraftEULA and set eula=true in eula.txt."
    }
    if (Test-ServerRunning) {
        throw "The server appears to already be running."
    }

    & $Config["JAVA_CMD"] (Join-Path $RootDir "scripts\CustomPluginBuilder.java")
    if ($LASTEXITCODE -ne 0) {
        throw "Custom plugin build failed with code $LASTEXITCODE."
    }

    & $Config["JAVA_CMD"] (Join-Path $RootDir "scripts\WorldManager.java") inject
    if ($LASTEXITCODE -ne 0) {
        throw "Datapack injection failed with code $LASTEXITCODE."
    }

    Set-Content -LiteralPath $LockFile -Value $PID -Encoding ASCII
    Push-Location $RootDir
    try {
        Write-Host "Starting Purpur $($Config['MC_VERSION']) with $($Config['MIN_MEMORY']) to $($Config['MAX_MEMORY']) RAM..."
        & $Config["JAVA_CMD"] "-Xms$($Config['MIN_MEMORY'])" "-Xmx$($Config['MAX_MEMORY'])" `
            -jar $Config["SERVER_JAR"] --nogui
        if ($LASTEXITCODE -ne 0) {
            throw "Purpur exited with code $LASTEXITCODE."
        }
    }
    finally {
        Pop-Location
        Remove-Item -LiteralPath $LockFile -Force -ErrorAction SilentlyContinue
    }
}

try {
    $config = Read-ServerConfig
    $config["JAVA_CMD"] = Resolve-JavaCommand -ConfiguredCommand $config["JAVA_CMD"]
    $jarPath = Join-Path $RootDir $config["SERVER_JAR"]

    switch ($Action) {
        "start" { Start-Server -Config $config -JarPath $jarPath }
        "update" { Update-Server -Config $config -JarPath $jarPath }
        "check" {
            $status = Test-Server -Config $config -JarPath $jarPath
            exit $status
        }
    }
}
catch {
    Write-Error $_.Exception.Message
    exit 1
}
