# LANLink application icons

Approved design: blue monitor, violet phone and white pointer, centered between pale wireless waves above and below. The selected artwork is preserved in `source.png`; `lanlink-1024.png` is the normalized application master with a clean launcher silhouette.

Run `npm run icons` from any supported Node.js 22+ environment to rebuild all resources. The generator uses the approved artwork rather than synthesizing a replacement design, requires no graphics dependencies, and supports macOS and Windows build hosts.

- macOS: `LanLink.icns`, including standard and Retina resolutions. `scripts/package-macos.sh` installs it and declares `CFBundleIconFile`. The menu bar uses `MenuBarTemplate.png` and its 2x counterpart.
- Windows: `LanLink.ico`, with 16, 24, 32, 48, 64, 128 and 256 pixel images. `desktop/windows/build.ps1` embeds it into the executable; the tray reads the executable's icon.
- Android: generated artwork and monochrome resources under `android/app/src/main/res`, with adaptive launcher resources for API 26+ and themed icons for API 33+. The manifest uses the adaptive resource for both standard and round launchers. Notification icons are provided for five densities.

The small monochrome system variants retain the device/pointer silhouette and omit pale background waves for legibility. Android adapts the same approved artwork to the launcher's mask.

Validation: macOS packaging/signature and native ICNS decoding; Android debug build and Android 14 emulator launcher inspection; native ICO decoding. Windows executable/runtime verification requires a Windows host.
