$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$stage = Join-Path $root ('.tools/source-package/' + [guid]::NewGuid().ToString())
New-Item -ItemType Directory -Force $stage | Out-Null
foreach ($name in @('src','web','desktop','native','vendor','docs','tests','scripts')) {
    Copy-Item -LiteralPath (Join-Path $root $name) -Destination $stage -Recurse -Force
}
# Include Android sources and the wrapper, without machine-specific Studio settings,
# SDK paths, Gradle caches, or generated APKs.
$androidStage = Join-Path $stage 'android'
$appStage = Join-Path $androidStage 'app'
New-Item -ItemType Directory -Force $appStage | Out-Null
foreach ($name in @('build.gradle','settings.gradle','gradle.properties','gradlew','gradlew.bat','gradle')) {
    Copy-Item -LiteralPath (Join-Path $root "android/$name") -Destination $androidStage -Recurse -Force
}
foreach ($name in @('src','build.gradle')) {
    Copy-Item -LiteralPath (Join-Path $root "android/app/$name") -Destination $appStage -Recurse -Force
}
foreach ($name in @('package.json','README.md','THIRD_PARTY_NOTICES.md','start-windows.cmd','start-macos.command','.gitignore')) {
    Copy-Item -LiteralPath (Join-Path $root $name) -Destination $stage -Force
}
$version = (Get-Content (Join-Path $root 'package.json') -Raw | ConvertFrom-Json).version
$zip = Join-Path $root "dist/LanLink-$version-source.zip"
if (Test-Path -LiteralPath $zip) { throw 'Source package already exists' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::CreateFromDirectory($stage,$zip)
Get-Item -LiteralPath $zip | Select-Object FullName,Length
