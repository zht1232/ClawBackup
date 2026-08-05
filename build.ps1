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
$LibsDir    = Join-Path $ProjectDir "libs"

# ===== Ensure bundled deps (downloaded once; cached in ./libs) =====
$MailJar = Join-Path $LibsDir "javax.mail-1.6.2.jar"
$ActJar  = Join-Path $LibsDir "activation-1.1.1.jar"
if (-not (Test-Path $MailJar)) {
    Write-Host "[deps] Downloading javax.mail-1.6.2.jar ..."
    New-Item -ItemType Directory -Force -Path $LibsDir | Out-Null
    Invoke-WebRequest -Uri "https://repo1.maven.org/maven2/com/sun/mail/javax.mail/1.6.2/javax.mail-1.6.2.jar" -OutFile $MailJar -UseBasicParsing
}
if (-not (Test-Path $ActJar)) {
    Write-Host "[deps] Downloading activation-1.1.1.jar ..."
    Invoke-WebRequest -Uri "https://repo1.maven.org/maven2/javax/activation/activation/1.1.1/activation-1.1.1.jar" -OutFile $ActJar -UseBasicParsing
}
# H2 driver (MineStock integration: runtime JDBC access to its AUTO_SERVER=TRUE database)
$H2Jar = Join-Path $LibsDir "h2-2.2.224.jar"
if (-not (Test-Path $H2Jar)) {
    Write-Host "[deps] Downloading h2-2.2.224.jar ..."
    New-Item -ItemType Directory -Force -Path $LibsDir | Out-Null
    Invoke-WebRequest -Uri "https://repo1.maven.org/maven2/com/h2database/h2/2.2.224/h2-2.2.224.jar" -OutFile $H2Jar -UseBasicParsing
}

# ===== Read version from plugin.yml =====
$PluginYml = Join-Path $ResDir "plugin.yml"
if (-not (Test-Path $PluginYml)) { throw "plugin.yml not found: $PluginYml" }
$Version = (Select-String -Path $PluginYml -Pattern "^version:\s*(\S+)").Matches.Groups[1].Value
if (-not $Version) { $Version = "dev" }
$JarName = "ClawBackup-$Version.jar"

Write-Host "==== ClawBackup build v$Version ===="

# 1. Compile (use javac @argfile to avoid Windows command-line length limits)
Write-Host "[1/4] Compiling (Java 8 bytecode)..."
if (-not (Test-Path $LibrariesDir)) { throw "Libraries dir not found: $LibrariesDir" }
$AllJars = @(Get-ChildItem -Path $LibrariesDir -Recurse -Filter *.jar | ForEach-Object { $_.FullName })
if (Test-Path $LibsDir) {
    $AllJars += @(Get-ChildItem -Path $LibsDir -Filter *.jar | ForEach-Object { $_.FullName })
}
$Cp = $AllJars -join ';'
# Optional compile-only dependency: CustomNameplates API (compile-time only, NOT bundled into the jar)
$CustomNameplatesJar = "C:\Users\Administrator\Downloads\MSLX-Daemon_v1.5.5.1_win-x64\DaemonData\Servers\1\plugins\CustomNameplates-Bukkit-3.0.42.jar"
if (Test-Path $CustomNameplatesJar) {
    $Cp = "$Cp;$CustomNameplatesJar"
    Write-Host "[deps] CustomNameplates API jar found (compile-only, not bundled)"
} else {
    Write-Host "[deps] WARNING: CustomNameplates API jar not found - CustomNameplates integration will NOT compile"
}
if (Test-Path $ClassesDir) { Remove-Item -Recurse -Force $ClassesDir }
New-Item -ItemType Directory -Force -Path $ClassesDir | Out-Null
$Files = @(Get-ChildItem -Path $SrcDir -Recurse -Filter *.java | ForEach-Object { $_.FullName })
if ($Files.Count -eq 0) { throw "No .java files found under src/main/java" }
$ArgFile = Join-Path $BuildDir "javac.args"
$ArgLines = @("--release", "8", "-cp", $Cp, "-d", $ClassesDir, "-encoding", "UTF-8") + @($Files)
$ArgLines | Out-File -FilePath $ArgFile -Encoding ascii
javac "@$ArgFile"
if ($LASTEXITCODE -ne 0) { throw "Compile failed (exit=$LASTEXITCODE)" }

# 2. Copy resources (plugin.yml / config.yml etc.)
Write-Host "[2/4] Copying resources..."
Copy-Item -Path (Join-Path $ResDir "*") -Destination $ClassesDir -Recurse -Force

# 3. Package jar (bundle libs deps into the jar)
Write-Host "[3/4] Packaging jar (bundling deps)..."
if (Test-Path $LibsDir) {
    Get-ChildItem -Path $LibsDir -Filter *.jar | ForEach-Object {
        Write-Host "    bundling $($_.Name)"
        Push-Location $ClassesDir
        jar xf $_.FullName
        Pop-Location
    }
    # Remove jar signature files to avoid SecurityException at runtime
    Get-ChildItem -Path $ClassesDir -Recurse -Include *.SF,*.RSA,*.DSA -ErrorAction SilentlyContinue | Remove-Item -Force
    # Drop bundled META-INF so a clean manifest is regenerated
    Remove-Item -Recurse -Force (Join-Path $ClassesDir "META-INF") -ErrorAction SilentlyContinue
}
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
