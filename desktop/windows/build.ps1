param([string]$Output = '.tools/native-desktop/LanLink.exe')
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$framework = Join-Path $env:WINDIR 'Microsoft.NET/Framework64/v4.0.30319'
$outputPath = [IO.Path]::GetFullPath((Join-Path $root $Output))
New-Item -ItemType Directory -Force (Split-Path $outputPath) | Out-Null
$refs = @('System.dll','System.Core.dll','System.Xaml.dll','System.Web.Extensions.dll','System.Windows.Forms.dll','System.Drawing.dll') | ForEach-Object { '/reference:' + (Join-Path $framework $_) }
$refs += @('WindowsBase.dll','PresentationCore.dll','PresentationFramework.dll') | ForEach-Object { '/reference:' + (Join-Path $framework ('WPF/' + $_)) }
& (Join-Path $framework 'csc.exe') /nologo /target:winexe /platform:x64 /optimize+ "/out:$outputPath" "/win32manifest:$PSScriptRoot/app.manifest" @refs (Join-Path $PSScriptRoot 'Client.cs') (Join-Path $PSScriptRoot 'Main.cs') (Join-Path $PSScriptRoot 'Remote.cs') (Join-Path $PSScriptRoot 'WindowShell.cs') (Join-Path $PSScriptRoot 'Pairing.cs') (Join-Path $PSScriptRoot 'Adb.cs') (Join-Path $PSScriptRoot 'Permissions.cs')
if ($LASTEXITCODE -ne 0) { throw 'Native desktop build failed' }
Write-Output $outputPath
