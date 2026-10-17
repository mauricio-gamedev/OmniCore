# OmniCore

> Android-first multi-system emulation hub focused on isolated console runtimes, stable frame delivery, precise touch input and device-aware performance.

[![Android](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![Stable DEV](https://img.shields.io/badge/stable%20DEV-0.9.4-57D8FF)](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.9.4-dev)
[![N64 Alpha](https://img.shields.io/badge/N64%20alpha-0.10.23%20Alpha%2024-9879FF)](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.10.23-n64-alpha24)
[![PS2 Alpha](https://img.shields.io/badge/PS2%20alpha-0.11.5%20Alpha%206-FF6A5F)](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.11.5-ps2-alpha6)
[![PSP Alpha](https://img.shields.io/badge/PSP%20preview-0.12.0%20Alpha%201-F6C85F)](https://github.com/mauricio-gamedev/OmniCore/releases/tag/AlphaExperimental)
[![Native](https://img.shields.io/badge/native-16%20KB%20ready-3DDC84)](app/src/main/cpp/)
[![Copyright](https://img.shields.io/badge/copyright-%C2%A9%202026%20%40mauricio--gamedev-6C63FF)](COPYRIGHT.md)

OmniCore is a **multi-system Android emulation hub**. Each console owns its runtime, settings, storage, input policy and performance logic while sharing one Android library experience.

<!-- OMNICORE_PUBLIC_STATUS_START -->
## Current status

| Channel | Version | Status |
|---|---:|---|
| Stable DEV | **0.9.4** | PS1 gameplay, video, audio and controls device validated |
| N64 maintenance | **0.10.23 Alpha 24** | Gameplay baseline protected; startup-only audio polish remains non-blocking |
| PS2 device-test | **0.11.5 Alpha 6** | PCSX2/ARMSX2 foundation and Visibility v1 telemetry validated in CI; physical-device testing remains ongoing |
| PSP experimental preview | **0.12.0 Alpha 1** | Older preview published with known image/import issues; new PSP-only graphics/touch candidate is in CI and awaits physical-device validation. |

### Latest milestone — PSP Alpha 1 — original experimental preview

**OmniCore 0.12.0 Alpha 1** is the older PPSSPP v1.20.4/libretro preview. It does not contain the current framebuffer/presentation correction or the new graphics/touch settings.

### Next PSP update — graphics and touch-control configuration

**Status:** In development — latest follow-up CI running; physical-device validation pending.

The candidate adds aspect-preserving image presentation, conservative smart internal-resolution selection, a graphics menu with automatic / 1× / 2× options, and persistent touch-control size, opacity and intelligent-opacity settings. Fixed and automatic frame skipping stay disabled.

**Vulkan is not implemented yet.** The native host still requests OpenGL ES. Vulkan requires a separate, real backend and must not be advertised as supported before build and device validation.

### Downloads

**Published PSP Alpha 1 preview (known issues)**  
[Release page](https://github.com/mauricio-gamedev/OmniCore/releases/tag/AlphaExperimental) · [Direct APK download](https://github.com/mauricio-gamedev/OmniCore/releases/download/AlphaExperimental/OmniCore.PSP.Alpha.1.Preview)

The published tag is currently `AlphaExperimental`, not the canonical versioned tag `v0.12.0-psp-alpha1`. The uploaded asset also lacks the `.apk` suffix. The downloadable APK is the older preview, not the corrective candidate.

**Current corrective candidate**  
[Latest PSP CI run](https://github.com/mauricio-gamedev/OmniCore/actions/runs/39040941978) · [PSP integration PR](https://github.com/mauricio-gamedev/OmniCore/pull/1)

Do not treat the corrective build as ready for distribution until CI and physical-device validation pass.

### Public information automation

The status and latest published-release links are maintained in `config/public-status.json` and synchronized by `tools/sync_public_info.py`. This block is intentionally kept small; detailed PSP/PS2 engineering notes below stay outside the generated section.
<!-- OMNICORE_PUBLIC_STATUS_END -->

### PSP Alpha 1 — published preview and next candidate

The currently published PSP preview is the older Alpha 1 APK, built with PPSSPP v1.20.4/libretro in the isolated `com.omnicore.emulator:psp` process. That downloadable build still has known **image scaling/positioning** and **library import/discovery** issues.

- [Published release](https://github.com/mauricio-gamedev/OmniCore/releases/tag/AlphaExperimental)
- [Direct APK download](https://github.com/mauricio-gamedev/OmniCore/releases/download/AlphaExperimental/OmniCore.PSP.Alpha.1.Preview)
- [PSP integration PR](https://github.com/mauricio-gamedev/OmniCore/pull/1)
- [Latest corrective CI run](https://github.com/mauricio-gamedev/OmniCore/actions/runs/39040941978)

The published release uses the generic tag `AlphaExperimental`, not the repository's versioned pattern `v0.12.0-psp-alpha1`, and its APK asset name lacks the `.apk` suffix. The release currently targets `main`; the original preview's documented build commit is `73ec758b800a2721089256a8923c1dc33e053395`. The tag/target and filename need normalization so the source snapshot and binary are clearly associated.

The current PSP-only candidate adds:
- an OmniCore-owned OpenGL ES presentation pass intended to scale and center the PSP image while preserving 480×272 aspect ratio and depth;
- smart internal-resolution selection with automatic / 1× / 2× choices;
- a graphics menu with persistent resolution preference;
- configurable touch-control size and opacity, plus intelligent opacity that dims unused controls.

These features are **not in the published APK**. CI passed for the earlier graphics/touch menu commit; the latest follow-up that applies the saved automatic / 1× / 2× choice is currently being built. Image geometry, 3D depth, launch/restart, audio, mult-touch and button-release behavior still need physical-device verification.

**Vulkan is not supported yet.** The current native PSP host still requests OpenGL ES. Vulkan needs a real native backend and cannot be advertised until its context creation, synchronization, presentation, fallback and device behavior are implemented and verified.

The integration remains isolated from PS1, N64 and PS2. No games, PSP firmware or proprietary content are bundled.

### Historical downloads

- [PS2 Alpha 6 release](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.11.5-ps2-alpha6)
- [N64 Alpha 24 release](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.10.23-n64-alpha24)
- [Stable PS1 DEV release](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.9.4-dev)

### PS2 Alpha 6 baseline — PCSX2 Foundation

**OmniCore 0.11.5 PS2 Alpha 6** is the current PlayStation 2 device-test build. Alpha 6 replaces the Play! runtime used by Alphas 1–5 with a pinned **PCSX2/ARMSX2 Android ARM64** foundation and moves PS2 optimization toward measured, source-level GS work rather than hidden quality reductions.

The PS2 Alpha 6 track currently provides:

- isolated Android process: `com.omnicore.emulator:ps2`;
- pinned PCSX2/ARMSX2 revision `7f0ae7a6c689b5b36eccc61b7adb480f65c7a3a3`;
- dedicated `PS2Backend` boundary between OmniCore and PCSX2;
- real user-supplied PS2 BIOS requirement — no Sony BIOS is bundled or downloaded;
- Direct Boot through PCSX2 Fast Boot and Classic BIOS boot through real firmware with Fast Boot disabled;
- automatic renderer selection delegated to PCSX2 instead of an OmniCore renderer-forcing loop;
- SmartPerf measurement-only policy: no automatic renderer, limiter or internal-resolution mutation and no cycle skipping;
- protected **1.0× minimum internal-resolution quality floor**;
- source-built ARM64 PCSX2 emucore with 4 KB and 16 KB page-size variants;
- Visibility v1 GS telemetry for primitive culling, draw batches, fog workload and alpha workload;
- BALANCED lockstep measurement path without re-enabling the visually unsafe pipelined path;
- PS2 touch/controller layer, save-state UX and per-system settings isolation;
- stable OmniCore DEV signer and native/APK 16 KB page-size compatibility validation;
- PS1/N64 runtime source paths protected by CI.

### Visibility v1 status

The latest Alpha 6 build keeps visibility work **measurement-first**. CULL, FOG and ALPHA information is collected from the source-built GS path, but OmniCore does not yet discard additional draws based on that telemetry.

The next physical-device pass is intended to measure how much genuinely invisible primitive work exists in real gameplay before any active visibility optimization is enabled. This avoids trading image correctness for synthetic performance gains.

Alpha 6 does **not** lower the requested PS2 internal resolution below 1.0×, enable EE cycle skipping, disable fog, remove alpha effects or use aggressive affinity pinning to manufacture higher FPS.

### Latest CI validation

GitHub Actions run **32401858158** completed successfully for commit `308ea7abf9d1499bcb07622c38d20d54e69387ad` after the Android system-SONAME validation fix.

The successful pipeline validated the pinned PCSX2/ARMSX2 source build, protected PS1/N64 cores, the custom JNI bridge and Visibility v1 symbol, signed release APK assembly, the expected development signer, 16 KB native/APK compatibility and corresponding GPL source artifacts before publishing the Alpha 6 prerelease.

Physical-device validation of this newest Alpha 6 build is **still pending**; runtime performance or visibility gains are not marked solved until they are measured on hardware.

## Runtime isolation

- **PS1** remains on its validated PCSX-ReARMed path.
- **N64** runs in `com.omnicore.emulator:n64` and keeps the protected Alpha 24 gameplay baseline.
- **PS2** runs in `com.omnicore.emulator:ps2` on the pinned PCSX2/ARMSX2 foundation, with its own backend, lifecycle, settings and performance path.
- **PSP** is being added in the experimental `com.omnicore.emulator:psp` process through pinned PPSSPP v1.20.4; the first public preview has a known import issue, with a fix being validated separately.

PS2 CI rejects accidental changes to protected PS1/N64 runtime-owned source paths.

## Protected N64 baseline

The N64 maintenance channel preserves:

- Intelligent **1.5×** internal resolution: `960×720` at 4:3 / `960×540` widescreen;
- framebuffer emulation protected;
- Dynarec-first CPU path;
- HLE RSP default;
- PrecisionGovernor v2.1;
- CruiseGuard / MicroBurstShield / NonBlockingTelemetry;
- CadencePolish + DirectPresenter;
- AudioBackend Auto + AudioHealthWatch;
- StartupAudioGate + SmoothAudioResampler + SyncSlew + ElasticAudioBridge + TransitionAudioShield;
- Smart Analog / Kirby profile;
- RacingComfort v2 / Mario Kart 64 profile;
- editable touch-control positions and sizing;
- optional D-pad visibility;
- USB/Bluetooth controller input;
- save RAM and save states;
- StarUI Smooth;
- offline Achievements v2 / Constelação OmniCore.

## Multi-system roadmap

| System | Backend direction | Status |
|---|---|---|
| PlayStation 1 | PCSX-ReARMed / libretro | **Functional / device validated** |
| Nintendo 64 | Mupen64Plus-Next / libretro | **Alpha 24 / maintenance** |
| PlayStation 2 | PCSX2/ARMSX2 behind OmniCore `PS2Backend` | **0.11.5 Alpha 6 / active device testing** |
| PSP | PPSSPP v1.20.4 / libretro | **0.12.0 Alpha 1 published known-issue preview; graphics/settings candidate in validation** |
| Wii / GameCube | Dolphin | Planned |
| Nintendo Switch | Experimental backend evaluation | Long-term |

## Android / build baseline

- `compileSdk` / `targetSdk`: 36
- `minSdk`: 26
- OmniCore Android NDK: `28.2.13676358`
- Native runtime: C++20
- App/core packaging retains ARM64 + ARMv7 where supported; the Alpha 6 PCSX2/ARMSX2 PS2 emucore is ARM64
- native ELF/APK **16 KB page-size compatibility** verified in CI
- stable DEV signing certificate verified in release CI

## Reproducible cores

PCSX-ReARMed pin: `da2cb8ecd17fd0932ab6d94774c0522beebce6e3`

Mupen64Plus-Next pin: `f275caf4b2bfa1e6d1c51636746ea793f3d80320`

PCSX2/ARMSX2 PS2 pin: `7f0ae7a6c689b5b36eccc61b7adb480f65c7a3a3`

PPSSPP PSP pin: `fa50bb1976065c4f8b1b47af227d367fe9771555` (published Alpha 1 preview; image/settings candidate needs device validation; Vulkan not implemented)

Corresponding source/license material is kept with applicable builds and repository notices.

## Content and firmware policy

OmniCore does **not** include ROMs, game images, BIOS files, firmware, console encryption keys or proprietary game assets. Users are responsible for supplying content and firmware they are legally entitled to use.

For PS2 Alpha 6, a real user-supplied PS2 BIOS is required. OmniCore does not bundle or download Sony firmware.

## Ownership, copyright and third-party licenses

**Copyright © 2026 [@mauricio-gamedev](https://github.com/mauricio-gamedev).** Original OmniCore project identity, documentation, branding and original project material are protected by copyright except where an applicable source/component license grants additional rights.

Public repository visibility does not place original OmniCore material in the public domain. See **[COPYRIGHT.md](COPYRIGHT.md)**.

Third-party components remain governed by their own licenses. PCSX-ReARMed, Mupen64Plus-Next, PCSX2/ARMSX2 and their dependencies retain the rights and obligations granted by their respective licenses. See **[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)**.

## Immediate development priorities

1. Complete CI for the PSP graphics/touch-settings candidate, then validate image aspect/depth, launch/restart, import, audio and controls on a real device.
2. Implement a real PSP Vulkan backend separately after the OpenGL ES path is stable and regression-covered.
3. Preserve the isolated PS1/N64/PS2 baselines while the PSP backend is integrated.
3. Complete physical-device testing of the PS2 Alpha 6 PCSX2/ARMSX2 build.
4. Capture Visibility v1 telemetry in a repeatable heavy gameplay scene.
5. Measure CULL, FOG, ALPHA, GS and synchronization behavior before enabling additional draw rejection, preserving visual correctness and the 1.0× quality floor.

---

**OmniCore — original project authorship and rights: [@mauricio-gamedev](https://github.com/mauricio-gamedev). Repository maintained at `mauricio-gamedev/OmniCore`.**
