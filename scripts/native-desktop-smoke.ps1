param([string]$Executable = '', [switch]$Native)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$version = (Get-Content (Join-Path $root 'package.json') -Raw | ConvertFrom-Json).version
if (-not $Executable) { $Executable = Join-Path $root "dist/LanLink-$version-windows-x64/LanLink.exe" }
$exe = (Resolve-Path $Executable).Path
$testData = Join-Path $root ('.tools/native-test-' + [guid]::NewGuid().ToString())
$arguments = @('--self-test','--data',('"' + $testData + '"'))
if ($Native) { $arguments += '--native' }
$process = Start-Process -FilePath $exe -ArgumentList $arguments -PassThru -WindowStyle Hidden
if (-not $process.WaitForExit(45000)) { Stop-Process -Id $process.Id -Force; throw "Native UI test timeout; data: $testData" }
if ($process.ExitCode -ne 0) {
    if (Test-Path -LiteralPath (Join-Path $testData 'failure.txt')) { Get-Content -LiteralPath (Join-Path $testData 'failure.txt') }
    throw "Native UI test failed; data: $testData"
}
Get-Content -LiteralPath (Join-Path $testData 'passed.txt')
foreach ($view in @('preview','menu','settings','settings-compact','pairing','pairing-qr','pairing-join','compact','remote-phone','consent')) {
    Copy-Item -LiteralPath (Join-Path $testData ("native-$view.png")) -Destination (Join-Path $root ("dist/native-$version-$view.png")) -Force
}
foreach ($view in @('controlled','adb','adb-compact','permissions','permissions-local','information','adb-hub')) {
    $preview = Join-Path $testData ("native-$view.png")
    if (Test-Path -LiteralPath $preview) { Copy-Item -LiteralPath $preview -Destination (Join-Path $root ("dist/native-$version-$view.png")) -Force }
}
Write-Output "Test evidence: $testData"
