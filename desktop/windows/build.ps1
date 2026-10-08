param([string]$Output = '.tools/native-desktop/LanLink.exe')
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$framework = Join-Path $env:WINDIR 'Microsoft.NET/Framework64/v4.0.30319'
$outputPath = [IO.Path]::GetFullPath((Join-Path $root $Output))
New-Item -ItemType Directory -Force (Split-Path $outputPath) | Out-Null
$refs = @('System.dll','System.Core.dll','Microsoft.CSharp.dll','System.Xml.dll','System.Xaml.dll','System.Web.Extensions.dll','System.Windows.Forms.dll','System.Drawing.dll') | ForEach-Object { '/reference:' + (Join-Path $framework $_) }
$refs += @('WindowsBase.dll','PresentationCore.dll','PresentationFramework.dll') | ForEach-Object { '/reference:' + (Join-Path $framework ('WPF/' + $_)) }
$refs += (Join-Path $PSScriptRoot 'Theme.cs')
$refs += (Join-Path $PSScriptRoot 'EdgeGlow.cs')
$refs += (Join-Path $PSScriptRoot 'Startup.cs')
$refs += (Join-Path $PSScriptRoot 'Staging.cs')
$refs += (Join-Path $PSScriptRoot 'FileDragEdges.cs')
& (Join-Path $framework 'csc.exe') /nologo /target:winexe /platform:x64 /optimize+ "/out:$outputPath" "/win32manifest:$PSScriptRoot/app.manifest" "/win32icon:$root/assets/icons/LanLink.ico" @refs (Join-Path $PSScriptRoot 'Client.cs') (Join-Path $PSScriptRoot 'Main.cs') (Join-Path $PSScriptRoot 'Remote.cs') (Join-Path $PSScriptRoot 'WindowShell.cs') (Join-Path $PSScriptRoot 'Pairing.cs') (Join-Path $PSScriptRoot 'Adb.cs') (Join-Path $PSScriptRoot 'AdbGuide.cs') (Join-Path $PSScriptRoot 'Access.cs') (Join-Path $PSScriptRoot 'WindowSize.cs') (Join-Path $PSScriptRoot 'Permissions.cs')
if ($LASTEXITCODE -ne 0) { throw 'Native desktop build failed' }
Write-Output $outputPath
