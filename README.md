# OmniCore

> Android-first multi-system emulation hub focused on isolated console runtimes, stable frame delivery, precise touch input and device-aware performance.

[![Android](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![Stable DEV](https://img.shields.io/badge/stable%20DEV-0.9.4-57D8FF)](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.9.4-dev)
[![N64 Alpha](https://img.shields.io/badge/N64%20alpha-0.10.23%20Alpha%2024-9879FF)](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.10.23-n64-alpha24)
[![PS2 Alpha](https://img.shields.io/badge/PS2%20alpha-0.11.5%20Alpha%206-FF6A5F)](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.11.5-ps2-alpha6)
[![PSP Alpha](https://img.shields.io/badge/PSP%20preview-0.12.0%20Alpha%201-F6C85F)](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.12.0-psp-alpha1)
[![Native](https://img.shields.io/badge/native-16%20KB%20ready-3DDC84)](app/src/main/cpp/)
[![Copyright](https://img.shields.io/badge/copyright-%C2%A9%202026%20%40mauricio--gamedev-6C63FF)](COPYRIGHT.md)

OmniCore is a **multi-system Android emulation hub**. Each console owns its runtime, settings, storage, input policy and performance logic while sharing one Android library experience.

<!-- OMNICORE_PUBLIC_STATUS_START -->
## Current status

| Channel | Version | Status |
|---|---:|---|
| Stable DEV | **0.9.4** | PS1 gameplay, video, audio and controls device validated |
| N64 maintenance | **0.10.23 Alpha 24** | Gameplay baseline protected; startup-only audio polish remains non-blocking |
| PS2 device-test | **0.11.5 Alpha 6** | PCSX2/ARMSX2 foundation and Visibility v1 telemetry validated in CI; physical-device testing is next |
| PSP integration preview | **0.12.0 Alpha 1** | PPSSPP v1.20.4 preview published; first build has a known ROM-import/discovery issue; fix is in PR #1 and CI is pending |

### Latest experimental integration — PSP Alpha 1 preview

**OmniCore v0.12.0 PSP Alpha 1** introduces an isolated PPSSPP v1.20.4/libretro runtime in Android process `com.omnicore.emulator:psp`, with PSP-specific native hosting, touch controls, app-private save/storage paths and ARM64 + ARMv7 packaging. The initial profile uses native PSP resolution (1×) and keeps frame skipping disabled.

> **Known issue in the downloadable preview:** the first Alpha 1 build did not reliably discover/import PSP images from the library. The fix now in [PR #1](https://github.com/mauricio-gamedev/OmniCore/pull/1) adds an explicit **Import as → PSP** choice for shared ISO/CSO/PBP formats and a bounded recursive scan when a PSP folder is selected. That corrected build has not yet completed CI/device validation, so the previous APK is published only as a **known-issue prerelease**.

The PSP integration is deliberately isolated: PS1, N64 and PS2 runtime implementations are not being refactored as part of this work. No games, PSP firmware or proprietary content are bundled.

- [Previous Alpha 1 preview download / release tag](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.12.0-psp-alpha1)
- [Previous APK — known import issue](https://github.com/mauricio-gamedev/OmniCore/releases/download/v0.12.0-psp-alpha1/OmniCore-v0.12.0-psp-alpha1-pre-import-fix.apk)
- [Current import-fix CI run](https://github.com/mauricio-gamedev/OmniCore/actions/runs/38826858199)
- [PSP integration pull request](https://github.com/mauricio-gamedev/OmniCore/pull/1)

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

### Downloads

**PSP Alpha 1 preview — known ROM import/discovery issue**  
https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.12.0-psp-alpha1

**Previous PSP Alpha 1 APK**  
https://github.com/mauricio-gamedev/OmniCore/releases/download/v0.12.0-psp-alpha1/OmniCore-v0.12.0-psp-alpha1-pre-import-fix.apk

**Corrected PSP import build**  
[Build/validation status in GitHub Actions](https://github.com/mauricio-gamedev/OmniCore/actions/runs/38826858199) — do not treat it as downloadable until CI produces a new artifact and device testing confirms the fix.

**PS2 Alpha 6 prerelease**  
https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.11.5-ps2-alpha6

**Direct PS2 Alpha 6 APK**  
https://github.com/mauricio-gamedev/OmniCore/releases/download/v0.11.5-ps2-alpha6/OmniCore-v0.11.5-ps2-alpha6.apk

**N64 Alpha 24 release**  
https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.10.23-n64-alpha24

**Stable PS1 DEV**  
https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.9.4-dev

### Public information automation

Public release status is mirrored in `config/public-status.json`. Documentation-only public-status updates use `[skip ci]` to avoid unnecessary build loops.
<!-- OMNICORE_PUBLIC_STATUS_END -->

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
| PSP | PPSSPP v1.20.4 / libretro | **0.12.0 Alpha 1 preview; ROM import fix pending CI/device validation** |
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

PPSSPP PSP pin: `fa50bb1976065c4f8b1b47af227d367fe9771555` (Alpha 1 preview; import correction pending validation)

Corresponding source/license material is kept with applicable builds and repository notices.

## Content and firmware policy

OmniCore does **not** include ROMs, game images, BIOS files, firmware, console encryption keys or proprietary game assets. Users are responsible for supplying content and firmware they are legally entitled to use.

For PS2 Alpha 6, a real user-supplied PS2 BIOS is required. OmniCore does not bundle or download Sony firmware.

## Ownership, copyright and third-party licenses

**Copyright © 2026 [@mauricio-gamedev](https://github.com/mauricio-gamedev).** Original OmniCore project identity, documentation, branding and original project material are protected by copyright except where an applicable source/component license grants additional rights.

Public repository visibility does not place original OmniCore material in the public domain. See **[COPYRIGHT.md](COPYRIGHT.md)**.

Third-party components remain governed by their own licenses. PCSX-ReARMed, Mupen64Plus-Next, PCSX2/ARMSX2 and their dependencies retain the rights and obligations granted by their respective licenses. See **[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)**.

## Immediate development priorities

1. Complete the PSP ROM-import fix CI run, then validate file selection, folder scanning, launch, touch input and audio on a real device.
2. Preserve the isolated PS1/N64/PS2 baselines while the PSP backend is integrated.
3. Complete physical-device testing of the PS2 Alpha 6 PCSX2/ARMSX2 build.
4. Capture Visibility v1 telemetry in a repeatable heavy gameplay scene.
5. Measure CULL, FOG, ALPHA, GS and synchronization behavior before enabling additional draw rejection, preserving visual correctness and the 1.0× quality floor.

---

**OmniCore — original project authorship and rights: [@mauricio-gamedev](https://github.com/mauricio-gamedev). Repository maintained at `mauricio-gamedev/OmniCore`.**
