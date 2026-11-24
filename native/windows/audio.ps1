param([switch]$Capture)
$ErrorActionPreference = 'Stop'
Add-Type -Path (Join-Path $PSScriptRoot 'Audio.cs')
[LanLink.RemoteAudio]::Run($Capture.IsPresent)
