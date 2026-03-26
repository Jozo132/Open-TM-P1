# setup.ps1 — Download JDK 17 + Android SDK (user-local, no admin needed)
$ErrorActionPreference = 'Continue'

$SDK_ROOT  = "$env:LOCALAPPDATA\Android\Sdk"
$JDK_ROOT  = "$env:LOCALAPPDATA\Programs\microsoft-jdk-17"

# ── JDK 17 ──────────────────────────────────────────────────────────
if (Test-Path "$JDK_ROOT\bin\javac.exe") {
    Write-Host "[OK] JDK 17 already installed at $JDK_ROOT"
} else {
    Write-Host "[DL] Downloading Microsoft OpenJDK 17 ..."
    $jdkUrl  = "https://aka.ms/download-jdk/microsoft-jdk-17.0.14-windows-x64.zip"
    $jdkZip  = "$env:TEMP\ms-jdk17.zip"
    $jdkTmp  = "$env:TEMP\jdk17-extract"

    Invoke-WebRequest -Uri $jdkUrl -OutFile $jdkZip -UseBasicParsing
    Write-Host "[OK] Downloaded JDK 17 archive"

    if (Test-Path $jdkTmp) { Remove-Item $jdkTmp -Recurse -Force }
    Expand-Archive -Path $jdkZip -DestinationPath $jdkTmp -Force
    Write-Host "[OK] Extracted JDK 17"

    # The zip extracts to a subfolder like jdk-17.0.14+7
    $inner = Get-ChildItem $jdkTmp -Directory | Select-Object -First 1
    if (-not $inner) { throw "Could not find JDK folder inside archive" }

    if (Test-Path $JDK_ROOT) { Remove-Item $JDK_ROOT -Recurse -Force }
    New-Item -ItemType Directory -Path (Split-Path $JDK_ROOT) -Force | Out-Null
    Move-Item $inner.FullName $JDK_ROOT
    Remove-Item $jdkZip -Force -ErrorAction SilentlyContinue
    Remove-Item $jdkTmp -Recurse -Force -ErrorAction SilentlyContinue
    Write-Host "[OK] JDK 17 installed to $JDK_ROOT"
}

# ── Android SDK Command-Line Tools ──────────────────────────────────
$cmdlineDir = "$SDK_ROOT\cmdline-tools\latest"
if (Test-Path "$cmdlineDir\bin\sdkmanager.bat") {
    Write-Host "[OK] Android cmdline-tools already installed"
} else {
    Write-Host "[DL] Downloading Android SDK command-line tools ..."
    $clUrl = "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
    $clZip = "$env:TEMP\android-cmdline-tools.zip"
    $clTmp = "$env:TEMP\android-cmdline-extract"

    Invoke-WebRequest -Uri $clUrl -OutFile $clZip -UseBasicParsing
    Write-Host "[OK] Downloaded command-line tools"

    if (Test-Path $clTmp) { Remove-Item $clTmp -Recurse -Force }
    Expand-Archive -Path $clZip -DestinationPath $clTmp -Force

    New-Item -ItemType Directory -Path $cmdlineDir -Force | Out-Null
    # The archive has a 'cmdline-tools' folder inside
    $src = "$clTmp\cmdline-tools"
    if (-not (Test-Path $src)) { $src = (Get-ChildItem $clTmp -Directory | Select-Object -First 1).FullName }
    Copy-Item "$src\*" $cmdlineDir -Recurse -Force
    Remove-Item $clZip -Force -ErrorAction SilentlyContinue
    Remove-Item $clTmp -Recurse -Force -ErrorAction SilentlyContinue
    Write-Host "[OK] Command-line tools installed to $cmdlineDir"
}

# ── Accept licences & install required SDK packages ─────────────────
$env:JAVA_HOME    = $JDK_ROOT
$env:ANDROID_HOME = $SDK_ROOT
$sdkmanager       = "$cmdlineDir\bin\sdkmanager.bat"

Write-Host "[..] Accepting SDK licences ..."
$yeses = ("y`n" * 10)
$yeses | & $sdkmanager --licenses 2>&1 | Out-Null
Write-Host "[OK] Licences accepted"

Write-Host "[..] Installing platform-tools, build-tools;34.0.0, platforms;android-34 ..."
& $sdkmanager "platform-tools" "build-tools;34.0.0" "platforms;android-34" 2>&1 | ForEach-Object { Write-Host "     $_" }
Write-Host "[OK] SDK packages installed"

# ── Summary ─────────────────────────────────────────────────────────
Write-Host ""
Write-Host "========================================="
Write-Host "  Build environment ready!"
Write-Host "  JAVA_HOME    = $JDK_ROOT"
Write-Host "  ANDROID_HOME = $SDK_ROOT"
Write-Host "========================================="
Write-Host ""
Write-Host "Run:  npm run build   — to compile the APK"
Write-Host "      npm run deploy  — to install on device"
