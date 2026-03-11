Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$rootDir = (Resolve-Path (Join-Path $scriptDir "..")).Path
$exampleDir = Join-Path $rootDir "example"
$dirs = @("agents", "teams", "models", "mcp-servers", "viewport-servers", "viewports", "tools", "skills", "schedules")

function Write-Log {
    param([string]$Message)
    Write-Host "[install-example][windows] $Message"
}

function Get-FileCount {
    param([string]$Dir)
    if (-not (Test-Path $Dir)) {
        return 0
    }
    return (Get-ChildItem -Path $Dir -Recurse -File -ErrorAction SilentlyContinue | Measure-Object).Count
}

Write-Log "example source: $exampleDir"
Write-Log "target root: $rootDir"

foreach ($dir in $dirs) {
    $src = Join-Path $exampleDir $dir
    $dest = Join-Path $rootDir $dir

    if (-not (Test-Path $src)) {
        Write-Log "skip missing source dir: $src"
        continue
    }

    New-Item -Path $dest -ItemType Directory -Force | Out-Null
    $srcCount = Get-FileCount -Dir $src

    $files = Get-ChildItem -Path $src -Recurse -File -Force | Where-Object { $_.Name -ne "README.md" }
    foreach ($file in $files) {
        $relativePath = $file.FullName.Substring($src.Length).TrimStart('\', '/')
        $targetFile = Join-Path $dest $relativePath
        $targetDir = Split-Path -Parent $targetFile
        if (-not (Test-Path $targetDir)) {
            New-Item -Path $targetDir -ItemType Directory -Force | Out-Null
        }
        Copy-Item -Path $file.FullName -Destination $targetFile -Force
    }

    $destCount = Get-FileCount -Dir $dest
    Write-Log "synced $dir (source files=$srcCount, target files=$destCount)"
}

Write-Log "done"
