# LanLink agent notes

## Build / verify

- Windows desktop: `powershell -ExecutionPolicy Bypass -File desktop\windows\build.ps1 [-Output path\LanLink.exe]` (uses .NET Framework `csc.exe`).
- Core: `npm test`, `npm run check`.
- The shell in this workspace is PowerShell; source files are UTF-8 without BOM — read them with `-Encoding UTF8`.

## Shared UI design tokens (Windows / macOS / Android)

Keep the three native apps on the same palette. Windows maps the light hex below to its dark value in
`desktop/windows/Theme.cs` (`DarkPalette`); macOS uses `Palette` in `desktop/macos/LanLink.swift`;
Android uses `Ui` in `android/app/src/main/java/com/lanlink/app/Ui.java`.

| Token | Light | Dark | Use |
|---|---|---|---|
| accent | `#5B6BD5` | `#A9B4F5` | primary actions, selection, focus, links |
| accentHover | `#4E5DC4` | `#B8C1F7` | primary hover |
| accentPressed | `#4453B5` | `#C6CDF9` | primary pressed |
| accentSoft | `#ECEEFC` | `#2E3456` | soft buttons, selected rows, hover |
| accentSoftPressed | `#E1E4FA` | `#383F66` | soft pressed / selected nav |
| accentLine | `#C7CDF2` | `#4A5285` | selected borders |
| onAccent | `#FFFFFF` | `#141B27` | text on accent |
| master | `#7B70C5` | `#B3A8F0` | layout master device marker only |
| ink | `#202C43` | `#E4EAF4` | primary text |
| muted | `#65748B` | `#A8B5CA` | secondary text (>= 4.5:1 on card) |
| line | `#DFE5EF` | `#3B4960` | borders, dividers |
| card | `#FFFFFF` | `#222C3C` | cards, inputs |
| canvas | `#F1F5FC` | `#111722` | window background (desktop) |
| surface | `#F9FBFE` | `#171E2B` | sidebar / secondary surface |
| success / soft | `#2F8566` / `#E3F3ED` | `#7AC6A6` / `#203E36` | online, granted |
| warning / soft | `#9A6E1F` / `#FBF1DE` | `#E0B865` / `#3D3420` | not ready, limited |
| danger / soft | `#C04A5C` / `#FBE9EC` | `#EF96A5` / `#45262E` | errors, unauthorized |

Radius scale: control 10, card 16, large surface 20, sheet / phone card 24.
Do not introduce new near-duplicate greys or blues; reuse a token instead.
