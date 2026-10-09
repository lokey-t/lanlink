param(
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [string]$SdkPlatform = '',
    [string]$BuildTools = '',
    [string]$Zxing = ''
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$version = (Get-Content (Join-Path $root 'package.json') -Raw | ConvertFrom-Json).version
$gradle = Get-Content (Join-Path $root 'android/app/build.gradle') -Raw
if ($gradle -notmatch "versionName '$([regex]::Escape($version))'") { throw 'Android and desktop versions must agree before packaging.' }
if ($SdkPlatform -or $BuildTools -or $Zxing) { Write-Warning 'The complete Android app now uses Gradle and its locked dependency versions; configure the Android SDK in android/local.properties or ANDROID_HOME.' }
$oldJava = $env:JAVA_HOME
try {
    $env:JAVA_HOME = (Resolve-Path $JavaHome).Path
    Push-Location (Join-Path $root 'android')
    try { & .\gradlew.bat --no-daemon :app:assembleDebug; if ($LASTEXITCODE -ne 0) { throw 'Android build failed' } }
    finally { Pop-Location }
    New-Item -ItemType Directory -Force (Join-Path $root 'dist') | Out-Null
    $output = Join-Path $root "dist/LanLink-$version-android.apk"
    if (Test-Path -LiteralPath $output) { throw 'The versioned APK already exists; preserve it before rebuilding.' }
    Copy-Item (Join-Path $root 'android/app/build/outputs/apk/debug/app-debug.apk') $output
    Get-FileHash -LiteralPath $output -Algorithm SHA256
} finally { $env:JAVA_HOME = $oldJava }
