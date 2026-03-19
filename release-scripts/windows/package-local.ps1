Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$rootDir = (Resolve-Path (Join-Path $scriptDir "../..")).Path
$releaseDir = Join-Path $rootDir "release-local"

function Write-Log {
    param([string]$Message)
    Write-Host "[package-local] $Message"
}

function Require-Command {
    param([string]$CommandName)
    if (-not (Get-Command $CommandName -ErrorAction SilentlyContinue)) {
        throw "[package-local] missing required command: $CommandName"
    }
}

function Import-DotEnv {
    param([string]$FilePath)
    foreach ($lineRaw in Get-Content $FilePath) {
        $line = $lineRaw.Trim()
        if (-not $line -or $line.StartsWith("#")) {
            continue
        }

        $eqIndex = $line.IndexOf("=")
        if ($eqIndex -le 0) {
            continue
        }

        $key = $line.Substring(0, $eqIndex).Trim()
        $value = $line.Substring($eqIndex + 1).Trim()
        if (($value.StartsWith("'") -and $value.EndsWith("'")) -or ($value.StartsWith('"') -and $value.EndsWith('"'))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        [Environment]::SetEnvironmentVariable($key, $value, "Process")
    }
}

Require-Command "mvn"

$pomPath = Join-Path $rootDir "pom.xml"
if (-not (Test-Path $pomPath)) {
    throw "[package-local] pom.xml not found"
}

$envPath = Join-Path $rootDir ".env"
if (Test-Path $envPath) {
    Write-Log "load environment from $envPath"
    Import-DotEnv -FilePath $envPath
}

Write-Log "clean release directory: $releaseDir"
if (Test-Path $releaseDir) {
    Remove-Item -Path $releaseDir -Recurse -Force
}
New-Item -Path $releaseDir -ItemType Directory -Force | Out-Null

Write-Log "build backend jar"
Push-Location $rootDir
try {
    & mvn -q -DskipTests package
    if ($LASTEXITCODE -ne 0) {
        throw "[package-local] maven package failed with exit code $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}

$jar = Get-ChildItem (Join-Path $rootDir "target") -Filter "*.jar" -File |
    Where-Object { $_.Name -notlike "*original*.jar" } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $jar) {
    throw "[package-local] jar not found in target/"
}

Copy-Item $jar.FullName (Join-Path $releaseDir "app.jar") -Force

foreach ($dir in @("agents", "teams", "models", "providers", "mcp-servers", "viewport-servers", "viewports", "tools", "skills", "schedules", "data", "configs")) {
    $sourceDir = Join-Path $rootDir $dir
    if (Test-Path $sourceDir) {
        Copy-Item $sourceDir (Join-Path $releaseDir $dir) -Recurse -Force
        Write-Log "copied ${dir}/"
    }
}

$startPs1 = @'
param(
    [switch]$Daemon
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Import-DotEnv {
    param([string]$FilePath)
    foreach ($lineRaw in Get-Content $FilePath) {
        $line = $lineRaw.Trim()
        if (-not $line -or $line.StartsWith("#")) {
            continue
        }

        $eqIndex = $line.IndexOf("=")
        if ($eqIndex -le 0) {
            continue
        }

        $key = $line.Substring(0, $eqIndex).Trim()
        $value = $line.Substring($eqIndex + 1).Trim()
        if (($value.StartsWith("'") -and $value.EndsWith("'")) -or ($value.StartsWith('"') -and $value.EndsWith('"'))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        [Environment]::SetEnvironmentVariable($key, $value, "Process")
    }
}

$appDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$pidFile = Join-Path $appDir "app.pid"
$jarFile = Join-Path $appDir "app.jar"
$logFile = Join-Path $appDir "app.log"
$errLogFile = Join-Path $appDir "app.err.log"

if (-not (Test-Path $jarFile)) {
    throw "[start] app.jar not found in $appDir"
}

if (Test-Path $pidFile) {
    $oldPidText = (Get-Content $pidFile -Raw).Trim()
    $oldPid = 0
    if ([int]::TryParse($oldPidText, [ref]$oldPid)) {
        if (Get-Process -Id $oldPid -ErrorAction SilentlyContinue) {
            throw "[start] already running (PID $oldPid). Use stop.ps1 first."
        }
    }
    Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
}

$runtimeEnvPath = Join-Path $appDir ".env"
if (Test-Path $runtimeEnvPath) {
    Write-Host "[start] loading $runtimeEnvPath"
    Import-DotEnv -FilePath $runtimeEnvPath
}

if (-not $env:JAVA_OPTS) {
    $env:JAVA_OPTS = "-server -Xms256m -Xmx512m"
}

if (-not $env:CONFIGS_DIR) { $env:CONFIGS_DIR = Join-Path $appDir "configs" }
if (-not $env:AGENTS_DIR) { $env:AGENTS_DIR = Join-Path $appDir "agents" }
if (-not $env:TEAMS_DIR) { $env:TEAMS_DIR = Join-Path $appDir "teams" }
if (-not $env:MODELS_DIR) { $env:MODELS_DIR = Join-Path $appDir "models" }
if (-not $env:PROVIDERS_DIR) { $env:PROVIDERS_DIR = Join-Path $appDir "providers" }
if (-not $env:TOOLS_DIR) { $env:TOOLS_DIR = Join-Path $appDir "tools" }
if (-not $env:MCP_SERVERS_DIR) { $env:MCP_SERVERS_DIR = Join-Path $appDir "mcp-servers" }
if (-not $env:VIEWPORT_SERVERS_DIR) { $env:VIEWPORT_SERVERS_DIR = Join-Path $appDir "viewport-servers" }
if (-not $env:VIEWPORTS_DIR) { $env:VIEWPORTS_DIR = Join-Path $appDir "viewports" }
if (-not $env:SKILLS_DIR) { $env:SKILLS_DIR = Join-Path $appDir "skills" }
if (-not $env:SCHEDULES_DIR) { $env:SCHEDULES_DIR = Join-Path $appDir "schedules" }
if (-not $env:DATA_DIR) { $env:DATA_DIR = Join-Path $appDir "data" }
if (-not $env:CHATS_DIR) { $env:CHATS_DIR = Join-Path $appDir "chats" }

$argLine = "$($env:JAVA_OPTS) -jar `"$jarFile`""

if ($Daemon) {
    Write-Host "[start] starting in background, log: $logFile, err: $errLogFile"
    $process = Start-Process -FilePath "java" -ArgumentList $argLine -RedirectStandardOutput $logFile -RedirectStandardError $errLogFile -PassThru
    Set-Content -Path $pidFile -Value $process.Id -Encoding ascii
    Write-Host "[start] started (PID $($process.Id))"
    exit 0
}

Write-Host "[start] starting in foreground (Ctrl+C to stop)"
$process = Start-Process -FilePath "java" -ArgumentList $argLine -NoNewWindow -PassThru
Set-Content -Path $pidFile -Value $process.Id -Encoding ascii
try {
    $process.WaitForExit()
    exit $process.ExitCode
}
finally {
    Remove-Item -Path $pidFile -Force -ErrorAction SilentlyContinue
}
'@
Set-Content -Path (Join-Path $releaseDir "start.ps1") -Value $startPs1 -Encoding utf8

$stopPs1 = @'
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$appDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$pidFile = Join-Path $appDir "app.pid"
$waitSeconds = 30

if (-not (Test-Path $pidFile)) {
    Write-Host "[stop] no PID file found, nothing to stop."
    exit 0
}

$pidText = (Get-Content $pidFile -Raw).Trim()
$appPid = 0
if (-not [int]::TryParse($pidText, [ref]$appPid)) {
    Write-Host "[stop] invalid PID file content, cleaning up PID file."
    Remove-Item -Path $pidFile -Force -ErrorAction SilentlyContinue
    exit 0
}

$proc = Get-Process -Id $appPid -ErrorAction SilentlyContinue
if (-not $proc) {
    Write-Host "[stop] process $appPid not running, cleaning up PID file."
    Remove-Item -Path $pidFile -Force -ErrorAction SilentlyContinue
    exit 0
}

Write-Host "[stop] sending stop signal to $appPid ..."
Stop-Process -Id $appPid -ErrorAction SilentlyContinue

$elapsed = 0
while ($elapsed -lt $waitSeconds) {
    if (-not (Get-Process -Id $appPid -ErrorAction SilentlyContinue)) {
        break
    }
    Start-Sleep -Seconds 1
    $elapsed += 1
}

if (Get-Process -Id $appPid -ErrorAction SilentlyContinue) {
    Write-Host "[stop] process $appPid did not exit after ${waitSeconds}s, forcing stop ..."
    Stop-Process -Id $appPid -Force -ErrorAction SilentlyContinue
}

Remove-Item -Path $pidFile -Force -ErrorAction SilentlyContinue
Write-Host "[stop] stopped."
'@
Set-Content -Path (Join-Path $releaseDir "stop.ps1") -Value $stopPs1 -Encoding utf8

$deployMd = @'
# Local Deployment Guide

## Prerequisites

- **Java 21+** (required)
- **PowerShell 7+** (recommended on Windows)
- **Python 3** (optional, for skills that use Python scripts)

## Quick Start (Windows PowerShell)

```powershell
# 1. Prepare runtime .env and configs under release-local

# 2. Create chat memory directory
New-Item -ItemType Directory -Force chats | Out-Null

# 3. Start (foreground)
.\start.ps1

# 3a. Or start in background
.\start.ps1 -Daemon

# 4. Stop (when running in background)
.\stop.ps1
```
'@
Set-Content -Path (Join-Path $releaseDir "DEPLOY.md") -Value $deployMd -Encoding utf8

Write-Log "release package generated:"
Write-Log "  $releaseDir/app.jar"
Write-Log "  $releaseDir/start.ps1"
Write-Log "  $releaseDir/stop.ps1"
Write-Log "  $releaseDir/DEPLOY.md"
foreach ($dir in @("agents", "teams", "models", "providers", "mcp-servers", "viewport-servers", "viewports", "tools", "skills", "schedules", "data", "configs")) {
    if (Test-Path (Join-Path $releaseDir $dir)) {
        Write-Log "  $releaseDir/$dir/"
    }
}
