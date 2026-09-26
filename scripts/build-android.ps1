param(
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [string]$SdkPlatform = '.tools/platform/android-35',
    [string]$BuildTools = '.tools/build-tools/android-15',
    [string]$Zxing = '.tools/downloads/zxing-core-3.5.3.jar'
)
$ErrorActionPreference = 'Stop'
$version = (Get-Content (Join-Path $PSScriptRoot '../package.json') -Raw | ConvertFrom-Json).version
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $projectRoot
try {
    $java = Join-Path $JavaHome 'bin/java.exe'
    $javac = Join-Path $JavaHome 'bin/javac.exe'
    $keytool = Join-Path $JavaHome 'bin/keytool.exe'
    $platformJar = Join-Path (Resolve-Path $SdkPlatform).Path 'android.jar'
    $aapt = Join-Path (Resolve-Path $BuildTools).Path 'aapt2.exe'
    $d8 = Join-Path (Resolve-Path $BuildTools).Path 'lib/d8.jar'
    $signer = Join-Path (Resolve-Path $BuildTools).Path 'lib/apksigner.jar'
    $work = Join-Path $projectRoot ('.tools/android-build/' + [guid]::NewGuid().ToString())
    New-Item -ItemType Directory -Force $work,"$work/classes","$work/generated",'dist' | Out-Null
    [xml]$manifest = Get-Content 'android/app/src/main/AndroidManifest.xml'
    $manifest.manifest.SetAttribute('package','com.lanlink.app')
    $manifest.Save("$work/AndroidManifest.xml")
    & $aapt compile --dir 'android/app/src/main/res' -o "$work/resources.zip"
    if ($LASTEXITCODE) { throw 'Android resource compilation failed' }
    & $aapt link -I $platformJar --manifest "$work/AndroidManifest.xml" --java "$work/generated" --min-sdk-version 29 --target-sdk-version 35 --version-code 4 --version-name $version --auto-add-overlay -A 'android/app/src/main/assets' -o "$work/unsigned.apk" -R "$work/resources.zip"
    if ($LASTEXITCODE) { throw 'Android manifest/resource linking failed' }
    $sources = @(Get-ChildItem 'android/app/src/main/java',"$work/generated" -Recurse -Filter '*.java' | ForEach-Object FullName)
    & $javac --release 17 -proc:none -encoding UTF-8 -classpath "$platformJar;$((Resolve-Path $Zxing).Path)" -d "$work/classes" @sources
    if ($LASTEXITCODE) { throw 'Android Java compilation failed' }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::CreateFromDirectory("$work/classes","$work/classes.jar")
    & $java -cp $d8 com.android.tools.r8.D8 --lib $platformJar --min-api 29 --output "$work/dex.zip" "$work/classes.jar" $Zxing
    if ($LASTEXITCODE) { throw 'Android DEX compilation failed' }
    $apkArchive = [System.IO.Compression.ZipFile]::Open("$work/unsigned.apk",[System.IO.Compression.ZipArchiveMode]::Update)
    $dexArchive = [System.IO.Compression.ZipFile]::OpenRead("$work/dex.zip")
    try {
        foreach ($entry in $dexArchive.Entries) {
            if ($entry.Name -notmatch '^classes[0-9]*\.dex$') { continue }
            $added = $apkArchive.CreateEntry($entry.Name)
            $readStream = $entry.Open(); $writeStream = $added.Open()
            try { $readStream.CopyTo($writeStream) } finally { $readStream.Dispose(); $writeStream.Dispose() }
        }
    } finally { $dexArchive.Dispose(); $apkArchive.Dispose() }
    $key = Join-Path $projectRoot '.tools/android-debug.p12'
    if (-not (Test-Path -LiteralPath $key)) {
        & $keytool -genkeypair -keystore $key -storetype PKCS12 -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 3650 -dname 'CN=Android Debug,O=LanLink,C=CN'
        if ($LASTEXITCODE) { throw 'Debug signing key generation failed' }
    }
    & $java -jar $signer sign --ks $key --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out "dist/LanLink-$version-debug.apk" "$work/unsigned.apk"
    if ($LASTEXITCODE) { throw 'APK signing failed' }
    & $java -jar $signer verify --verbose "dist/LanLink-$version-debug.apk"
    if ($LASTEXITCODE) { throw 'APK verification failed' }
    Get-FileHash "dist/LanLink-$version-debug.apk" -Algorithm SHA256
} finally { Pop-Location }
