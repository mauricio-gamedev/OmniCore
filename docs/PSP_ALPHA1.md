# OmniCore PSP Alpha 1

## Scope

This branch integrates the upstream PPSSPP libretro core as a separate PSP backend. It is an experimental integration, and **device validation is pending**. This is not a public stable release.

## Isolation contract

- All new PSP runtime code is placed under core/psp, cpp/psp, PSPEmulationActivity, PSPGamepadOverlayView and PSP-owned storage.
- The PSP activity runs in Android process :psp; it loads only omnicore_psp_runtime. The PPSSPP core is dynamically loaded by that runtime.
- Existing PS1, N64 and PS2 implementations and touch overlays are protected by the workflow's baseline diff guard. Do not refactor those components as part of this integration.
- Changes to the registry, import classifier, build version and CMake target list are limited to routing and packaging the new PSP backend.

## Pinned upstream

- Project: https://github.com/hrydgard/ppsspp
- Tag: v1.20.4
- Commit: fa50bb1976065c4f8b1b47af227d367fe9771555
- Frontend: upstream libretro port, Android ARM64 and ARMv7
- Renderer: OpenGL ES 2 hardware context through the dedicated EGL surface
- CPU: PPSSPP libretro JIT option requested; upstream/runtime may reject JIT on devices/platforms where unsupported.

## Conservative initial profile

- Internal rendering resolution starts at native PSP resolution (1×, 480×272).
- Frame skipping remains disabled. No host CPU/GPU clocks, Android system properties or hidden APIs are written.
- This branch does not add frame-timing hacks, game-memory patches or automatic quality promotion.
- Runtime data uses independent app-private filesDir/psp paths; original game files are never overwritten.

## Supported import formats

ISO, CSO and PBP. Because extensions overlap with other systems, users should explicitly choose PSP for ambiguous ISO/CSO/PBP files. The library import classifier does not redirect those extensions to a different core based only on suffix.

## Controls

| Control | PPSSPP libretro ID |
|---|---:|
| Cross | 0 |
| Square | 1 |
| Select | 2 |
| Start | 3 |
| D-pad Up / Down / Left / Right | 4 / 5 / 6 / 7 |
| Circle | 8 |
| Triangle | 9 |
| L / R | 10 / 11 |
| Left analog | X / Y |

The first overlay exposes the core PSP controls in a lightweight multitouch layout. Save states and advanced per-game graphics settings are not part of this first integration increment.

## Assets, rights and test gates

- CI copies assets from the pinned PPSSPP source snapshot, including compat.ini, and embeds the upstream license text in the APK.
- CI packages the source snapshot used for the GPL-covered PPSSPP backend alongside the test APK.
- No commercial games, PSP firmware dumps or other proprietary console content are bundled.
- CI verifies package identity and signer, preservation of the existing backend libraries, and APK/ELF 16 KB alignment.
- Physical device launch, controller feel, audio continuity, suspend/resume, thermal behavior and game compatibility must still be validated on hardware before this is considered stable.
