Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$rootDir = (Resolve-Path (Join-Path $scriptDir "../..")).Path
$releaseDir = Join-Path $rootDir "release"
$dockerfile = Join-Path $rootDir "Dockerfile"

function Write-Log {
    param([string]$Message)
    Write-Host "[package] $Message"
}

function Require-Command {
    param([string]$CommandName)
    if (-not (Get-Command $CommandName -ErrorAction SilentlyContinue)) {
        throw "[package] missing required command: $CommandName"
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
    throw "[package] pom.xml not found"
}
if (-not (Test-Path $dockerfile)) {
    throw "[package] Dockerfile not found in project root"
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
        throw "[package] maven package failed with exit code $LASTEXITCODE"
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
    throw "[package] jar not found in target/"
}

Copy-Item $jar.FullName (Join-Path $releaseDir "app.jar") -Force
Copy-Item $dockerfile (Join-Path $releaseDir "Dockerfile") -Force
Copy-Item (Join-Path $rootDir "settings.xml") (Join-Path $releaseDir "settings.xml") -Force

foreach ($dir in @("agents", "viewports", "tools", "skills")) {
    $sourceDir = Join-Path $rootDir $dir
    if (Test-Path $sourceDir) {
        Copy-Item $sourceDir (Join-Path $releaseDir $dir) -Recurse -Force
        Write-Log "copied ${dir}/"
    }
}

$composeFile = @'
services:
  agent-platform:
    build:
      context: .
      dockerfile: Dockerfile
    container_name: agent-platform
    restart: unless-stopped
    ports:
      - "${SERVER_PORT:-8080}:8080"
    volumes:
      - ./agents:/opt/agents
      - ./viewports:/opt/viewports
      - ./tools:/opt/tools
      - ./skills:/opt/skills
      - ./chats:/opt/chats
      - ./application.yml:/opt/application.yml:ro
    env_file:
      - .env
'@
Set-Content -Path (Join-Path $releaseDir "docker-compose.yml") -Value $composeFile -Encoding utf8

$envExample = @'
# Server
SERVER_PORT=8080

# Auth
AGENT_AUTH_ENABLED=false
# AGENT_AUTH_JWKS_URI=
# AGENT_AUTH_ISSUER=

# LLM provider keys (configure in application.yml)

# Bash tool security (explicit allowlists required)
# AGENT_BASH_WORKING_DIRECTORY=/opt
# AGENT_BASH_ALLOWED_PATHS=/opt/agents,/opt/chats
# AGENT_BASH_ALLOWED_COMMANDS=ls,pwd,cat,head,tail,top,free,df,git
# AGENT_BASH_PATH_CHECKED_COMMANDS=ls,cat,head,tail,git
'@
Set-Content -Path (Join-Path $releaseDir ".env.example") -Value $envExample -Encoding utf8

$deployMd = @'
# Release Deployment

1. Copy this `release` directory to the target host.
2. Create environment and config files:

   cp .env.example .env
   # Edit .env with production values

   # Create application.yml with LLM provider API keys
   touch application.yml

3. Create data directory for chat memory:

   mkdir -p chats

4. Start with Docker Compose:

   docker compose up -d --build
'@
Set-Content -Path (Join-Path $releaseDir "DEPLOY.md") -Value $deployMd -Encoding utf8

Write-Log "release package generated:"
Write-Log "  $releaseDir/app.jar"
Write-Log "  $releaseDir/Dockerfile"
Write-Log "  $releaseDir/settings.xml"
Write-Log "  $releaseDir/docker-compose.yml"
Write-Log "  $releaseDir/.env.example"
Write-Log "  $releaseDir/DEPLOY.md"
foreach ($dir in @("agents", "viewports", "tools", "skills")) {
    if (Test-Path (Join-Path $releaseDir $dir)) {
        Write-Log "  $releaseDir/$dir/"
    }
}
