# OmniCore

> Android-first multi-system emulation hub focused on isolated console runtimes, stable frame delivery, precise touch input and device-aware performance.

[![Android](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![Stable DEV](https://img.shields.io/badge/stable%20DEV-0.9.4-57D8FF)](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.9.4-dev)
[![N64 Alpha](https://img.shields.io/badge/N64%20alpha-0.10.23%20Alpha%2024-9879FF)](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.10.23-n64-alpha24)
[![PS2 Alpha](https://img.shields.io/badge/PS2%20alpha-0.11.4%20Alpha%205-FF6A5F)](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.11.4-ps2-alpha5)
[![Native](https://img.shields.io/badge/native-16%20KB%20ready-3DDC84)](app/src/main/cpp/)
[![Copyright](https://img.shields.io/badge/copyright-%C2%A9%202026%20%40mauricio--gamedev-6C63FF)](COPYRIGHT.md)

OmniCore is a **multi-system Android emulation hub**. Each console owns its runtime, settings, storage, input policy and performance logic while sharing one Android library experience.

<!-- OMNICORE_PUBLIC_STATUS_START -->
## Current status

| Channel | Version | Status |
|---|---:|---|
| Stable DEV | **0.9.4** | PS1 gameplay, video, audio and controls device validated |
| N64 maintenance | **0.10.23 Alpha 24** | Gameplay baseline protected; startup-only audio polish remains non-blocking |
| PS2 device-test | **0.11.4 Alpha 5** | Corrective build published and CI validated; physical-device retest in progress |

### Latest milestone — PS2 Alpha 5 corrective build

**OmniCore 0.11.4 PS2 Alpha 5** is the current PlayStation 2 device-test build. The latest corrective pass keeps the Alpha 5 version/tag while addressing physical-device feedback without moving to Alpha 6.

The PS2 track currently provides:

- isolated Android process: `com.omnicore.emulator:ps2`;
- pinned Play! backend revision `04bde0df87ee7c0e2f0151b51bb2cc22c88541da`;
- dedicated `PS2Backend` boundary between OmniCore and Play!;
- real Play! VM lifecycle, Android `Surface` rendering and content-URI disk boot;
- Play! `GameConfig.xml` compatibility database pinned and packaged;
- ARM64 + ARMv7 Play! builds with full LTO;
- real frame/draw-call telemetry JNI;
- Android frame-rate pacing and sustained-performance support where available;
- PS2 touch/controller layer, save-state UX and per-system settings isolation;
- user-owned PS2 BIOS picker/storage validation groundwork while the current Play! runtime still uses HLE BIOS;
- stable OmniCore DEV signer and native/APK 16 KB page-size compatibility validation.

### Alpha 5 corrective pass

The latest Alpha 5 rebuild specifically addresses three physical-device issues reported during testing:

- **renderer fallback black screen:** the previous measured tuning could persist an alternate Vulkan/GLES renderer after sustained low FPS and force it on the next launch. Automatic persistent renderer switching is now disabled, and stale per-game forced renderer state is cleared instead of being reused;
- **PS2 runtime stutter pressure:** memory/thermal telemetry remains available, but expensive device-pressure sampling is cached instead of being refreshed on every short sampler interval, reducing work on the gameplay path;
- **hub/UI scrolling stutter:** the always-running animated starfield behind the main Compose hub was replaced by a lightweight static starfield, reducing continuous drawing/overdraw while keeping the same visual identity.

This corrective build **does not lower the selected PS2 internal resolution, enable cycle skipping or trade away the 1.0× quality floor** to hide performance problems.

### SmartPerf policy for PS2

Optimization is part of the PS2 architecture, not a late-stage add-on. SmartPerf uses measured information only when the backend exposes it, and unknown timing fields remain unknown instead of being guessed.

Alpha 5 keeps the **1.0× minimum quality floor**, keeps cycle skipping disabled, and no longer changes the per-game renderer automatically on a later boot. Renderer changes remain explicit/user-controlled until a safer compatibility-aware policy is proven on hardware.

### Downloads

**PS2 Alpha 5 release**
https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.11.4-ps2-alpha5

**Direct PS2 Alpha 5 APK**
https://github.com/mauricio-gamedev/OmniCore/releases/download/v0.11.4-ps2-alpha5/OmniCore-v0.11.4-ps2-alpha5-debug.apk

**N64 Alpha 24 release**
https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.10.23-n64-alpha24

**Stable PS1 DEV**
https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.9.4-dev

### Latest validation

GitHub Actions run **32278321118** completed successfully for the corrected PS2 Alpha 5. It passed protected PS1/N64 rebuilds, Alpha 5 source contract validation, Android toolchains, stable DEV signing preparation, pinned Play! + GameConfig fetch, dual-ABI full-LTO build, telemetry JNI validation, signed APK assembly, signer verification, native/APK 16 KB compatibility, GPL source archival and prerelease publication.

Physical-device validation of the corrective build is **in progress**; runtime behavior is not marked solved until the new APK is retested on hardware.
<!-- OMNICORE_PUBLIC_STATUS_END -->

## Runtime isolation

- **PS1** remains on its validated PCSX-ReARMed path.
- **N64** runs in `com.omnicore.emulator:n64` and keeps the protected Alpha 24 gameplay baseline.
- **PS2** runs in `com.omnicore.emulator:ps2`, with its own backend, lifecycle and performance path.

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
| PlayStation 2 | Play! behind OmniCore `PS2Backend` | **0.11.4 Alpha 5 / active device testing** |
| PSP | PPSSPP v1.20.4 / libretro | **Alpha 1 / device validation pending** |
| Wii / GameCube | Dolphin | Planned |
| Nintendo Switch | Experimental backend evaluation | Long-term |

## Android / build baseline

- `compileSdk` / `targetSdk`: 36
- `minSdk`: 26
- OmniCore Android NDK: `28.2.13676358`
- Play! Android build NDK: `29.0.14206865`
- Native runtime: C++20
- ABIs: `arm64-v8a`, `armeabi-v7a`
- native ELF/APK **16 KB page-size compatibility** verified in CI
- stable DEV signing certificate verified in release CI

## Reproducible cores

PCSX-ReARMed pin: `da2cb8ecd17fd0932ab6d94774c0522beebce6e3`

Mupen64Plus-Next pin: `f275caf4b2bfa1e6d1c51636746ea793f3d80320`

Play! PS2 pin: `04bde0df87ee7c0e2f0151b51bb2cc22c88541da`

Corresponding source/license material is kept with applicable builds and repository notices.

## Content and firmware policy

OmniCore does **not** include ROMs, game images, BIOS files, firmware, console encryption keys or proprietary game assets. Users are responsible for supplying content and firmware they are legally entitled to use.

## Ownership, copyright and third-party licenses

**Copyright © 2026 [@mauricio-gamedev](https://github.com/mauricio-gamedev).** Original OmniCore project identity, documentation, branding and original project material are protected by copyright except where an applicable source/component license grants additional rights.

Public repository visibility does not place original OmniCore material in the public domain. See **[COPYRIGHT.md](COPYRIGHT.md)**.

Third-party components remain governed by their own licenses. PCSX-ReARMed, Mupen64Plus-Next, Play! and their dependencies retain the rights and obligations granted by their respective licenses. See **[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)**.

## Immediate development priorities

1. Complete physical-device retest of the corrected PS2 Alpha 5 build.
2. Verify repeated game launch after sustained slow-motion no longer produces a black screen.
3. Measure remaining PS2 stutter/slow-motion without lowering the requested internal resolution.
4. Verify hub tab/scroll fluidity after removing continuous background animation.
5. Keep PS1/N64 protected while applying only evidence-driven PS2 corrections.

---

**OmniCore — original project authorship and rights: [@mauricio-gamedev](https://github.com/mauricio-gamedev). Repository maintained at `mauricio-gamedev/OmniCore`.**
