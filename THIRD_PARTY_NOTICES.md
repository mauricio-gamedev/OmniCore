# Third-party notices

OmniCore development builds can bundle separately built emulator backends. Console runtimes remain isolated native components and are distributed with the notices/source material required by their licenses.

## PCSX-ReARMed

OmniCore uses PCSX-ReARMed as the PlayStation 1 backend.

- Project: PCSX-ReARMed
- Upstream: https://github.com/libretro/pcsx_rearmed
- Pinned revision: `da2cb8ecd17fd0932ab6d94774c0522beebce6e3`
- Upstream license: GPL-2.0
- Local GPL text: `third_party/licenses/GPL-2.0.txt`

The Android release workflow archives the exact checked-out PCSX-ReARMed source tree used to build the distributed APK.

## Mupen64Plus-Next

OmniCore uses Mupen64Plus-Next as the Nintendo 64 backend.

- Project: Mupen64Plus-Next / mupen64plus-libretro-nx
- Upstream: https://github.com/libretro/mupen64plus-libretro-nx
- Pinned revision: `f275caf4b2bfa1e6d1c51636746ea793f3d80320`
- Upstream license: GPL-2.0
- Local GPL text: `third_party/licenses/GPL-2.0.txt`

The Android N64 workflow fetches that exact revision, applies only the OmniCore Android packaging/build adjustments required for core coexistence and 16 KB ELF alignment, builds the ARM64 and ARMv7 shared libraries, and publishes the corresponding source archive beside applicable APKs.

## PPSSPP — PlayStation Portable Alpha 1 backend

OmniCore PSP Alpha 1 uses PPSSPP through its upstream libretro core, isolated behind the PSP-only native runtime and Android process.

- Project: PPSSPP
- Upstream: https://github.com/hrydgard/ppsspp
- Pinned release: v1.20.4
- Pinned commit: fa50bb1976065c4f8b1b47af227d367fe9771555
- Upstream license: GNU GPL version 2 or later, as declared in LICENSE.TXT
- Local license text: third_party/licenses/PPSSPP-LICENSE.TXT

The PSP workflow builds both Android ABIs from the exact upstream revision and publishes a source archive including initialized upstream submodules beside the APK artifact. PPSSPP assets are taken from the same pinned source snapshot. No commercial PSP games or user firmware dumps are bundled.

## PCSX2 via ARMSX2 — current PS2 Alpha 6 backend

OmniCore PS2 Alpha 6 uses the Android ARM64 PCSX2 emucore maintained by the ARMSX2 project behind OmniCore's `PS2Backend` boundary. The ARMSX2 application UI is not embedded; OmniCore supplies its own library, controls, lifecycle and console-isolation layer while using the pinned native emucore and required runtime resources.

- Project: ARMSX2 / PCSX2 Android port
- Upstream: https://github.com/ARMSX2/ARMSX2
- Pinned revision: `7f0ae7a6c689b5b36eccc61b7adb480f65c7a3a3`
- Core lineage: PCSX2
- Upstream license for the emucore/project: GNU GPL v3 or later as declared by upstream
- Packaged ABI: `arm64-v8a`
- Packaged page-size variants: `libemucore_4k.so`, `libemucore_16k.so`

The Alpha 6 workflow fetches the exact pinned tree, builds the Android emucore from source, embeds the GPLv3 license text from that same source snapshot, and publishes the corresponding source archive beside the APK. OmniCore does not bundle a Sony BIOS; a valid user-owned BIOS is required by this backend.

Where the resulting distributed work is subject to the GNU GPL, the GPL controls the rights and obligations for that affected work as stated in `COPYRIGHT.md`.

## Play! — legacy PS2 Alpha 1–5 backend

Earlier PS2 development builds used Play! as the backend behind `PS2Backend`. Alpha 6 no longer packages `libPlay.so`.

- Project: Play!
- Upstream: https://github.com/jpd002/Play-
- Last OmniCore pin: `04bde0df87ee7c0e2f0151b51bb2cc22c88541da`
- Upstream license: BSD 2-Clause style
- Local license text: `third_party/licenses/Play-BSD-2-Clause.txt`

## libretro ABI declarations

`app/src/main/cpp/libretro_abi.h` and the N64-specific ABI declarations under `app/src/main/cpp/n64/` contain the subset of libretro API declarations needed by OmniCore's native frontend hosts.

Canonical upstream libretro API header:

https://github.com/libretro/libretro-common/blob/master/include/libretro.h

## Proprietary content

OmniCore does not include Sony BIOS files, Nintendo ROMs, games, firmware, console keys or other proprietary console/game content. Users must provide content and firmware they are legally entitled to use.
