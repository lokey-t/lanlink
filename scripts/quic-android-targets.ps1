$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$manifest=Get-Content (Join-Path $root '.tools/quic-bootstrap/rust-stable.toml') -Raw
$toolchain=Join-Path $root '.tools/quic-bootstrap/rustup/toolchains/stable-x86_64-pc-windows-msvc'
foreach($target in @('aarch64-linux-android','x86_64-linux-android')) {
  $section=[regex]::Match($manifest,'(?ms)^\[pkg\.rust-std\.target\.'+[regex]::Escape($target)+'\]\r?\n(.*?)(?=^\[)')
  $url=[regex]::Match($section.Groups[1].Value,'xz_url = "([^"]+)"').Groups[1].Value
  $hash=[regex]::Match($section.Groups[1].Value,'xz_hash = "([^"]+)"').Groups[1].Value
  if(!$url -or !$hash){throw "Missing official manifest target $target"}
  $archive=Join-Path $root ".tools/quic-bootstrap/$target.tar.xz"
  if(!(Test-Path $archive)){Invoke-WebRequest $url -OutFile $archive}
  if((Get-FileHash $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $hash){throw 'Rust target checksum mismatch'}
  $destination=Join-Path $root ".tools/quic-bootstrap/std-$target"
  New-Item -ItemType Directory -Force $destination | Out-Null
  tar -xf $archive -C $destination
  if($LASTEXITCODE){throw 'Rust target extraction failed'}
  $lib=Get-ChildItem $destination -Directory | Select-Object -First 1
  Copy-Item -LiteralPath (Join-Path $lib.FullName "rust-std-$target/lib/rustlib/$target") -Destination (Join-Path $toolchain 'lib/rustlib') -Recurse -Force
}
