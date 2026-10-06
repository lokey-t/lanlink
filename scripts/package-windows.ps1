param([string]$NodeRuntime = '', [string]$VideoRuntime = '', [switch]$NoZip)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$version = (Get-Content (Join-Path $root 'package.json') -Raw | ConvertFrom-Json).version
if (-not $NodeRuntime) { $NodeRuntime = Split-Path (Get-Command node).Source }
$runtime = (Resolve-Path $NodeRuntime).Path
if (-not (Test-Path -LiteralPath (Join-Path $runtime 'node.exe'))) { throw 'Node runtime missing' }
if (-not (Test-Path -LiteralPath (Join-Path $root 'node_modules/werift/package.json'))) {throw 'Run npm ci --ignore-scripts before packaging the WebRTC dependencies.'}
$output = Join-Path $root "dist/LanLink-$version-windows-x64"
if (Test-Path -LiteralPath $output) { throw 'Output already exists; preserve the previous build before rebuilding.' }
$license = @((Join-Path $runtime 'NODE-LICENSE'), (Join-Path $runtime 'LICENSE'), (Join-Path $runtime '../LICENSE')) | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $license) { throw 'Node license missing: use a complete official runtime directory.' }
New-Item -ItemType Directory -Force (Join-Path $output 'runtime') | Out-Null
Copy-Item -LiteralPath (Join-Path $runtime 'node.exe') -Destination (Join-Path $output 'runtime/node.exe')
Copy-Item -LiteralPath $license -Destination (Join-Path $output 'runtime/NODE-LICENSE')
if ($VideoRuntime) {
    $video = (Resolve-Path $VideoRuntime).Path
    foreach ($file in @('bin/ffmpeg.exe', 'LICENSE.txt')) { if (-not (Test-Path -LiteralPath (Join-Path $video $file))) { throw "Video runtime missing $file" } }
    Copy-Item -LiteralPath (Join-Path $video 'bin/ffmpeg.exe') -Destination (Join-Path $output 'runtime/ffmpeg.exe')
    Copy-Item -LiteralPath (Join-Path $video 'LICENSE.txt') -Destination (Join-Path $output 'runtime/FFMPEG-LICENSE.txt')
    if (Test-Path -LiteralPath (Join-Path $root 'vendor/ffmpeg')) { Copy-Item -LiteralPath (Join-Path $root 'vendor/ffmpeg') -Destination (Join-Path $output 'runtime/ffmpeg-notices') -Recurse }
}
foreach ($name in @('src','vendor','docs','node_modules')) { Copy-Item -LiteralPath (Join-Path $root $name) -Destination $output -Recurse }
New-Item -ItemType Directory -Force (Join-Path $output 'native/quic') | Out-Null
foreach($name in @('windows','macos')){Copy-Item -LiteralPath (Join-Path $root "native/$name") -Destination (Join-Path $output 'native') -Recurse}
foreach($name in @('src','Cargo.toml','Cargo.lock')){Copy-Item -LiteralPath (Join-Path $root "native/quic/$name") -Destination (Join-Path $output 'native/quic') -Recurse}
$quicDll=Join-Path $root 'native/quic/target/release/lanlink_quic.dll'
if(!(Test-Path -LiteralPath $quicDll)){throw 'Build the QUIC native library before packaging.'}
Copy-Item -LiteralPath $quicDll -Destination (Join-Path $output 'runtime/lanlink_quic.dll')
New-Item -ItemType Directory -Force (Join-Path $output 'desktop/windows') | Out-Null
Copy-Item -LiteralPath (Join-Path $root 'desktop/windows/Theme.xaml') -Destination (Join-Path $output 'desktop/windows/Theme.xaml')
foreach ($name in @('package.json','package-lock.json','README.md','THIRD_PARTY_NOTICES.md')) { Copy-Item -LiteralPath (Join-Path $root $name) -Destination $output }
& (Join-Path $root 'desktop/windows/build.ps1') -Output "dist/LanLink-$version-windows-x64/LanLink.exe"
if (-not $NoZip) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::CreateFromDirectory($output,"$output.zip")
    Get-Item -LiteralPath "$output.zip" | Select-Object FullName,Length
    Get-FileHash -LiteralPath "$output.zip" -Algorithm SHA256
}
