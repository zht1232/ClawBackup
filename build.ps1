# ============================================================
#  ClawBackup one-click build script
#  Usage: run  .\build.ps1  in the project root
#  Compiles sources, packages a jar, and copies it to the Desktop.
#  Version is read automatically from plugin.yml
# ============================================================

$ErrorActionPreference = "Stop"

# ===== Configuration =====
# Server libraries directory (compile deps: paper-api and its transitive deps)
$LibrariesDir = "C:\Users\Administrator\Downloads\MSLX-Daemon_v1.5.5.1_win-x64\DaemonData\Servers\1\libraries"
# Output directory (default: Desktop)
$OutputDir = [Environment]::GetFolderPath("Desktop")

# ===== Project paths (no need to change) =====
$ProjectDir = $PSScriptRoot
$SrcDir     = Join-Path $ProjectDir "src\main\java"
$ResDir     = Join-Path $ProjectDir "src\main\resources"
$BuildDir   = Join-Path $ProjectDir "build_tmp"
$ClassesDir = Join-Path $BuildDir "classes"

# ===== Read version from plugin.yml =====
$PluginYml = Join-Path $ResDir "plugin.yml"
if (-not (Test-Path $PluginYml)) { throw "plugin.yml not found: $PluginYml" }
$Version = (Select-String -Path $PluginYml -Pattern "^version:\s*(\S+)").Matches.Groups[1].Value
if (-not $Version) { $Version = "dev" }
$JarName = "ClawBackup-$Version.jar"

Write-Host "==== ClawBackup build v$Version ===="

# 1. Compile
Write-Host "[1/4] Compiling (Java 8 bytecode)..."
if (-not (Test-Path $LibrariesDir)) { throw "Libraries dir not found: $LibrariesDir" }
$Cp = @(Get-ChildItem -Path $LibrariesDir -Recurse -Filter *.jar | ForEach-Object { $_.FullName }) -join ';'
if (Test-Path $ClassesDir) { Remove-Item -Recurse -Force $ClassesDir }
New-Item -ItemType Directory -Force -Path $ClassesDir | Out-Null
$Files = @(Get-ChildItem -Path $SrcDir -Recurse -Filter *.java | ForEach-Object { $_.FullName })
if ($Files.Count -eq 0) { throw "No .java files found under src/main/java" }
javac --release 8 -cp $Cp -d $ClassesDir -encoding UTF-8 $Files
if ($LASTEXITCODE -ne 0) { throw "Compile failed (exit=$LASTEXITCODE)" }

# 2. Copy resources (plugin.yml / config.yml etc.)
Write-Host "[2/4] Copying resources..."
Copy-Item -Path (Join-Path $ResDir "*") -Destination $ClassesDir -Recurse -Force

# 3. Package jar
Write-Host "[3/4] Packaging jar..."
$JarTmp = Join-Path $BuildDir $JarName
if (Test-Path $JarTmp) { Remove-Item -Force $JarTmp }
jar cf $JarTmp -C $ClassesDir .
if ($LASTEXITCODE -ne 0) { throw "Package failed (exit=$LASTEXITCODE)" }

# 4. Copy to output directory
Write-Host "[4/4] Copying to output directory..."
$Target = Join-Path $OutputDir $JarName
Copy-Item -Force $JarTmp $Target

# Cleanup
Remove-Item -Recurse -Force $BuildDir

Write-Host ""
Write-Host "==== Build OK ===="
Write-Host "  Version: $Version"
Write-Host "  Output : $Target"
Write-Host "  Size   : $([math]::Round((Get-Item $Target).Length/1KB,1)) KB"
Write-Host "=================="
