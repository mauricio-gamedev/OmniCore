# OmniCore PSP Alpha 1 — corrected device-test candidate

## Status

This is a corrective candidate for the experimental PPSSPP v1.20.4 runtime. The original Alpha 1 build launched the game but displayed the PSP frame in a small area near the lower-left corner. The candidate below changes OmniCore's own graphics host to render into an off-screen framebuffer and present a centered, aspect-preserving image. The updated build still needs successful CI validation and visual testing on physical hardware before the image fix is considered verified.

## What changed in the corrective pass

- OmniCore creates a PSP-owned OpenGL ES framebuffer and texture instead of handing PPSSPP the default Android surface framebuffer.
- The presenter scales the rendered image to the largest rectangle that preserves the PSP 480×272 aspect ratio, centers it, and uses black letterboxing where needed. It does not stretch the image or crop PSP lines.
- The host supplies the framebuffer requested by the PPSSPP hardware-render callback and attaches depth storage when requested, with an explicit error if the requested depth/stencil layout cannot be supported.
- A lightweight presentation shader runs in the same PSP-owned process. It does not read frames back to the CPU.
- The initial smart profile uses 1× internal rendering on devices with limited memory or severe thermal pressure. 2× internal resolution is selected only for devices with at least 8 GiB total RAM, at least 3 GiB currently available, no low-RAM classification, and thermal status below severe.
- The smart profile is selected before launch and can be inspected from the in-game menu. It does not dynamically rewrite renderer options during gameplay.
- JIT/OpenGL ES hardware rendering remain the requested baseline; fixed and automatic frame skipping stay disabled. Crop-to-16:9 and visual/performance hacks that can remove effects are explicitly disabled.

## Isolation contract

- PSP uses Android process `:psp` and loads only `omnicore_psp_runtime`; PPSSPP is dynamically loaded inside that process.
- This work is restricted to PSP-owned runtime files and PSP launch integration.
- Existing PS1, N64 and PS2 core/runtime source paths and touch overlays remain protected by the build workflow's baseline guard. Do not refactor these systems as part of the PSP work.

## Pinned upstream

- Project: https://github.com/hrydgard/ppsspp
- Tag: `v1.20.4`
- Commit: `fa50bb1976065c4f8b1b47af227d367fe9771555`
- Frontend: PPSSPP libretro port for Android ARM64 and ARMv7
- Renderer: OpenGL ES hardware context through the dedicated EGL surface

## Import and game files

Supported formats are ISO, CSO and PBP. The library supports an explicit **Import as → PSP** target for these shared extensions and bounded recursive scan when a PSP folder is selected. In automatic mode, ambiguous disc extensions are not guessed to be PSP from the filename alone.

Game images remain user-supplied. The app does not bundle games or proprietary firmware; original game files are not overwritten.

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

## CI and physical-device gates

The PSP-only workflow builds the pinned PPSSPP core for ARM64 and ARMv7, builds/signs the APK, validates retained PS1/N64 libraries, checks the development signer and 16 KB alignment, and uploads the test artifact. The candidate is not considered complete until those checks pass.

Physical-device checks still required: visible game image fills the intended viewport without distortion, 3D depth remains correct in scenes such as God of War, touch and physical input work, audio stays continuous, and pause/resume/relaunch do not leave a black surface. Performance gains must be measured on-device; no FPS increase is claimed from code inspection alone.
