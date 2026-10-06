@echo off
setlocal
for %%I in ("%~dp0..") do set "ROOT=%%~fI"
if exist "%ROOT%\.tools\quic-bootstrap\cargo\bin\cargo.exe" (
  set "RUSTUP_HOME=%ROOT%\.tools\quic-bootstrap\rustup"
  set "CARGO_HOME=%ROOT%\.tools\quic-bootstrap\cargo"
)
if not defined ANDROID_NDK_HOME set "ANDROID_NDK_HOME=%ROOT%\.tools\quic-bootstrap\android-ndk-r27d"
set "ANDROID_NDK_HOME=%ANDROID_NDK_HOME:\=/%"
if exist "%ROOT%\.tools\quic-bootstrap\libclang\libclang-18.1.1.data\platlib\clang\native\libclang.dll" set "LIBCLANG_PATH=%ROOT%\.tools\quic-bootstrap\libclang\libclang-18.1.1.data\platlib\clang\native"
set "PATH=%CARGO_HOME%\bin;%ANDROID_NDK_HOME%\toolchains\llvm\prebuilt\windows-x86_64\bin;%ROOT%\.tools\quic-bootstrap\cmake-4.4.3-windows-x86_64\bin;%ROOT%\.tools\quic-bootstrap\nasm-2.16.03;%PATH%"
if exist "%ROOT%\.tools\quic-buildtools\VC\Auxiliary\Build\vcvars64.bat" (
  call "%ROOT%\.tools\quic-buildtools\VC\Auxiliary\Build\vcvars64.bat" >nul
  if errorlevel 1 exit /b 1
)
set "PATH=%ROOT%\.tools\quic-buildtools\Common7\IDE\CommonExtensions\Microsoft\CMake\Ninja;%PATH%"
set "CMAKE_GENERATOR=Ninja"
set "CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=aarch64-linux-android29-clang.cmd"
set "CC_aarch64_linux_android=%ANDROID_NDK_HOME%/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe"
set "CXX_aarch64_linux_android=%ANDROID_NDK_HOME%/toolchains/llvm/prebuilt/windows-x86_64/bin/clang++.exe"
set "CFLAGS_aarch64_linux_android=--target=aarch64-linux-android29"
set "CXXFLAGS_aarch64_linux_android=--target=aarch64-linux-android29"
set "AR_aarch64_linux_android=llvm-ar.exe"
set "CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER=x86_64-linux-android29-clang.cmd"
set "CC_x86_64_linux_android=%ANDROID_NDK_HOME%/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe"
set "CXX_x86_64_linux_android=%ANDROID_NDK_HOME%/toolchains/llvm/prebuilt/windows-x86_64/bin/clang++.exe"
set "CFLAGS_x86_64_linux_android=--target=x86_64-linux-android29"
set "CXXFLAGS_x86_64_linux_android=--target=x86_64-linux-android29"
set "AR_x86_64_linux_android=llvm-ar.exe"
cargo build --manifest-path "%ROOT%\native\quic\Cargo.toml" --release --locked --target aarch64-linux-android --target x86_64-linux-android %*
if errorlevel 1 exit /b 1
if not exist "%ROOT%\android\app\src\main\jniLibs\arm64-v8a" mkdir "%ROOT%\android\app\src\main\jniLibs\arm64-v8a"
if not exist "%ROOT%\android\app\src\main\jniLibs\x86_64" mkdir "%ROOT%\android\app\src\main\jniLibs\x86_64"
copy /y "%ROOT%\native\quic\target\aarch64-linux-android\release\liblanlink_quic.so" "%ROOT%\android\app\src\main\jniLibs\arm64-v8a\liblanlink_quic.so" >nul
copy /y "%ROOT%\native\quic\target\x86_64-linux-android\release\liblanlink_quic.so" "%ROOT%\android\app\src\main\jniLibs\x86_64\liblanlink_quic.so" >nul
copy /y "%ANDROID_NDK_HOME%\toolchains\llvm\prebuilt\windows-x86_64\sysroot\usr\lib\aarch64-linux-android\libc++_shared.so" "%ROOT%\android\app\src\main\jniLibs\arm64-v8a\libc++_shared.so" >nul
copy /y "%ANDROID_NDK_HOME%\toolchains\llvm\prebuilt\windows-x86_64\sysroot\usr\lib\x86_64-linux-android\libc++_shared.so" "%ROOT%\android\app\src\main\jniLibs\x86_64\libc++_shared.so" >nul
