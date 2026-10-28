param([string]$PayloadZip='')
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$version=(Get-Content (Join-Path $root 'package.json') -Raw|ConvertFrom-Json).version
if(!$PayloadZip){$PayloadZip=Join-Path $root "dist/LanLink-$version-windows-x64.zip"}
$PayloadZip=(Resolve-Path -LiteralPath $PayloadZip).Path
$output=Join-Path $root "dist/LanLink-$version-windows-x64-setup.exe"
if(Test-Path -LiteralPath $output){throw 'Setup already exists; do not overwrite release assets.'}
$stage=Join-Path $root ('.tools/setup-build/'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $stage -Force|Out-Null
$assembly=Join-Path $stage 'AssemblyInfo.cs'
[IO.File]::WriteAllText($assembly,"using System.Reflection;[assembly:AssemblyVersion(`"$version.0`")][assembly:AssemblyFileVersion(`"$version.0`")]")
$framework=Join-Path $env:WINDIR 'Microsoft.NET/Framework64/v4.0.30319'
& (Join-Path $framework 'csc.exe') /nologo /target:winexe /platform:x64 "/out:$output" "/resource:$PayloadZip,payload.zip" /reference:System.dll /reference:System.Core.dll /reference:Microsoft.CSharp.dll /reference:System.Windows.Forms.dll /reference:System.Drawing.dll /reference:System.IO.Compression.dll /reference:System.IO.Compression.FileSystem.dll $assembly (Join-Path $PSScriptRoot 'WindowsSetup.cs')
if($LASTEXITCODE -ne 0){throw 'Setup compilation failed'}
$verify=Join-Path $stage 'verify'
$process=Start-Process -FilePath $output -ArgumentList @('--verify-payload',$verify) -WindowStyle Hidden -Wait -PassThru
if($process.ExitCode -ne 0){throw 'Embedded setup payload validation failed'}
Get-FileHash -LiteralPath $output -Algorithm SHA256
