@echo off
setlocal
for %%I in ("%~dp0..") do set "ROOT=%%~fI"
if exist "%ROOT%\.tools\quic-bootstrap\cargo\bin\cargo.exe" (
  set "RUSTUP_HOME=%ROOT%\.tools\quic-bootstrap\rustup"
  set "CARGO_HOME=%ROOT%\.tools\quic-bootstrap\cargo"
)
if exist "%ROOT%\.tools\quic-bootstrap\libclang\libclang-18.1.1.data\platlib\clang\native\libclang.dll" set "LIBCLANG_PATH=%ROOT%\.tools\quic-bootstrap\libclang\libclang-18.1.1.data\platlib\clang\native"
set "PATH=%CARGO_HOME%\bin;%ROOT%\.tools\quic-bootstrap\cmake-4.4.3-windows-x86_64\bin;%ROOT%\.tools\quic-bootstrap\nasm-2.16.03;%PATH%"
if exist "%ROOT%\.tools\quic-buildtools\VC\Auxiliary\Build\vcvars64.bat" (
  call "%ROOT%\.tools\quic-buildtools\VC\Auxiliary\Build\vcvars64.bat" >nul
  if errorlevel 1 exit /b 1
)
set "RUSTFLAGS=-C target-feature=+crt-static"
cargo build --manifest-path "%ROOT%\native\quic\Cargo.toml" --release --locked %*
exit /b %errorlevel%
