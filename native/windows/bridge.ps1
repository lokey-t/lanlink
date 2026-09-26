$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Web.Extensions
Add-Type -Path (Join-Path $PSScriptRoot 'Bridge.cs') -ReferencedAssemblies System.Windows.Forms,System.Drawing,System.Web.Extensions
[LanLink.Bridge]::Run()
