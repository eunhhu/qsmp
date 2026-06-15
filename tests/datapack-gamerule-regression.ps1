$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$version = (Select-String -LiteralPath (Join-Path $root "server.env") -Pattern "^MC_VERSION=(.+)$").Matches[0].Groups[1].Value.Trim()
$serverJar = Join-Path $root "versions\$version\purpur-$version.jar"
$localJavap = Join-Path $root "runtime\java\bin\javap.exe"
$javap = if (Test-Path -LiteralPath $localJavap -PathType Leaf) { $localJavap } else { "javap" }
$functionFile = Join-Path $root "datapacks\30-qsmp-rules\data\qsmp\function\load.mcfunction"

if (-not (Test-Path -LiteralPath $serverJar -PathType Leaf)) {
    throw "Purpur runtime JAR not found: $serverJar"
}

$registry = (& $javap -classpath $serverJar -c -p net.minecraft.world.level.gamerules.GameRules 2>&1) -join "`n"
if ($LASTEXITCODE -ne 0) {
    throw "Unable to inspect the Purpur gamerule registry.`n$registry"
}

$rules = Get-Content -LiteralPath $functionFile |
    ForEach-Object {
        if ($_ -match "^\s*gamerule\s+(\S+)") {
            $Matches[1]
        }
    }

foreach ($rule in $rules) {
    if ($registry -notmatch "// String $([regex]::Escape($rule))(\s|$)") {
        throw "Datapack uses gamerule '$rule', but Purpur $version does not register that ID."
    }
}

Write-Output "Datapack gamerule compatibility: PASS ($($rules.Count) rule(s))"
