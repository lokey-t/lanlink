param([string]$Output = '.tools/native-desktop/LanLink.exe')
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$framework = Join-Path $env:WINDIR 'Microsoft.NET/Framework64/v4.0.30319'
$outputPath = [IO.Path]::GetFullPath((Join-Path $root $Output))
New-Item -ItemType Directory -Force (Split-Path $outputPath) | Out-Null
$refs = @('System.dll','System.Core.dll','Microsoft.CSharp.dll','System.Xml.dll','System.Xaml.dll','System.Web.Extensions.dll','System.Windows.Forms.dll','System.Drawing.dll') | ForEach-Object { '/reference:' + (Join-Path $framework $_) }
$refs += @('WindowsBase.dll','PresentationCore.dll','PresentationFramework.dll') | ForEach-Object { '/reference:' + (Join-Path $framework ('WPF/' + $_)) }
$refs += (Join-Path $PSScriptRoot 'Theme.cs')
$refs += (Join-Path $PSScriptRoot 'UiText.cs')
$refs += (Join-Path $PSScriptRoot 'SharedLayout.cs')
$refs += (Join-Path $PSScriptRoot 'LanguageRules.cs')
$refs += (Join-Path $PSScriptRoot 'Locale.cs')
$refs += (Join-Path $PSScriptRoot 'UiLocale.cs')
$refs += (Join-Path $PSScriptRoot 'EdgeGlow.cs')
$refs += (Join-Path $PSScriptRoot 'Startup.cs')
$refs += (Join-Path $PSScriptRoot 'Staging.cs')
$refs += (Join-Path $PSScriptRoot 'FileDragEdges.cs')
$version = (Get-Content (Join-Path $root 'package.json') -Raw | ConvertFrom-Json).version
if ($version -notmatch '^\d+\.\d+\.\d+$') { throw 'Native version must use major.minor.patch format.' }
$informationalVersion = $version
$buildInfoPath = Join-Path $root 'build-info.json'
if (Test-Path -LiteralPath $buildInfoPath) {
    $buildInfo = Get-Content $buildInfoPath -Raw | ConvertFrom-Json
    if ($buildInfo.sourceFingerprint -match '^[a-f0-9]{12,}$') { $informationalVersion += '+' + $buildInfo.sourceFingerprint.Substring(0,12) }
}
$versionFile = Join-Path ([IO.Path]::GetTempPath()) ('LanLink-version-' + [Guid]::NewGuid().ToString('N') + '.cs')
try {
    @"
using System.Reflection;
[assembly: AssemblyTitle("LanLink")]
[assembly: AssemblyProduct("LanLink")]
[assembly: AssemblyVersion("$version.0")]
[assembly: AssemblyFileVersion("$version.0")]
[assembly: AssemblyInformationalVersion("$informationalVersion")]
"@ | Set-Content -LiteralPath $versionFile -Encoding UTF8
    & (Join-Path $framework 'csc.exe') /nologo /target:winexe /platform:x64 /optimize+ "/out:$outputPath" "/win32manifest:$PSScriptRoot/app.manifest" "/win32icon:$root/assets/icons/LanLink.ico" @refs $versionFile (Join-Path $PSScriptRoot 'Client.cs') (Join-Path $PSScriptRoot 'Main.cs') (Join-Path $PSScriptRoot 'Remote.cs') (Join-Path $PSScriptRoot 'WindowShell.cs') (Join-Path $PSScriptRoot 'Pairing.cs') (Join-Path $PSScriptRoot 'Adb.cs') (Join-Path $PSScriptRoot 'AdbGuide.cs') (Join-Path $PSScriptRoot 'Access.cs') (Join-Path $PSScriptRoot 'WindowSize.cs') (Join-Path $PSScriptRoot 'Permissions.cs')
    if ($LASTEXITCODE -ne 0) { throw 'Native desktop build failed' }
} finally {
    Remove-Item -LiteralPath $versionFile -ErrorAction SilentlyContinue
}
Write-Output $outputPath
