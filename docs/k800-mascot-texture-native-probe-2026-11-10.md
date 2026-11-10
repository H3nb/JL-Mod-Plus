# K800 Mascot texture palette-index-zero probe (2026-11-10)

**Status:** historical, bounded native observation; not a Java API or GPU rendering specification.

## Provenance and boundary

- Firmware: Sony Ericsson K800 R8BF003 MAIN `K800_R8BF003_MAIN_GENERIC_WI_RED52.mbn`, SHA-256 `8fd0b35d3fffa58f906c984985f017784bc0426b4ea3a4d3a2eb74f8df1a4c6d`.
- Research packet: user-supplied `Firmware-Reference.zip`, `reference_3d/tools/texture_probe.py`, `probe_wrappers.py`, and `firmware_input.py`; compare `reference_3d/cases/MC-TEX-001.md` and `MC-TEX-002.md`.
- Execution: the original decoder's ARM/Thumb instructions under Unicorn emulation with controlled stream and allocator/ABI hooks. The allocated storage was initially filled with `0xA5`. This was **not** a complete firmware/JVM boot, a Java constructor invocation, or a GPU draw.
- Do not distribute or commit the firmware, extracted native code, or proprietary runtime assets.

## Reproducible input

Use the packet's `bmp(3, 5, 8, top_down=..., patterned=True)`: an uncompressed 8-bit BMP with a 256-entry BGRA palette, 1,098 bytes total, 1,078-byte pixel offset, and 4-byte physical row stride. Replace palette entry 0 with **red** (`00 00 FF 00`) and entry 1 with **white** (`FF FF FF 00`); set the **logical** first three raster indices to `00 01 02`. Leave the remaining pixels from the packet's patterned generator unchanged. For controls, replace palette entry 0 with white or black while preserving the same raster.

Run in the packet's configured Python environment with the SHA-matched MAIN available through `JL_FIRMWARE_MAIN` or its documented local input path:

```python
import hashlib
import struct
from texture_probe import bmp, probe

for top_down in (True, False):
    for palette_zero in (bytes.fromhex("0000ff00"),
                         bytes.fromhex("ffffff00"),
                         bytes.fromhex("00000000")):
        data = bytearray(bmp(3, 5, 8, top_down=top_down, patterned=True))
        pixel_offset = struct.unpack_from("<I", data, 10)[0]
        data[54:58] = palette_zero
        data[58:62] = bytes.fromhex("ffffff00")
        logical_top = pixel_offset if top_down else pixel_offset + 4 * 4
        data[logical_top:logical_top + 3] = bytes((0, 1, 2))
        result = probe(bytes(data), False)
        print(top_down, palette_zero.hex(), hashlib.sha256(data).hexdigest(),
              result["status"], result.get("result"),
              result.get("decoded_first_6_rows_8_pixels", [None])[0])
```

SHA-256 of representative, byte-exact BMP inputs:

| Orientation | Palette entry 0 | SHA-256 |
| --- | --- | --- |
| top-down | red | `f4bee1424d836e598a067f9eb5d8ebba512ff4ae103fd7a0ac2b4cdad9e32a46` |
| bottom-up | red | `fab7c67b8801606cdeb35a90aef4464c690c9c6e7e8ae45b6bba59a164f3d644` |
| top-down | white (control) | `a9362f3c2aaf76d0c2df8d89f5b84f2bb48363dc1f2a49661012eacb3c5d7899` |
| top-down | black (control) | `081daa45abef2a620e862c9d20aad3beeefff8714d8b3b01c964b7ed10972c86` |

## Observed native results

All listed environment decodes returned `status=returned`, `result=1`, `stream_error=0`, and a **64×64** storage domain with stride 64, backed by an 8,192-byte allocation (native 16-bit samples). For red, white, **and** black palette entry 0, the first decoded row begins with the same eight bytes:

```text
00 00  DF FF  01 00  00 00
```

Interpreted as little-endian 16-bit samples, the first three values are **`0x0000`**, **`0xFFDF`**, and **`0x0001`**. The first is zero despite red or white palette entry 0; the second is nonzero for white palette entry 1. Both scanline orientations produced identical sampled rows for the red case. Inspected padding remained zero despite the prefilled allocation.

The same red-input BMP decoded with the model flag selected a **256×256** storage domain, with first-row raw bytes `00 01 02 00 00 00 00 00` and allocation size 81,920 bytes. The model path's representation is **not** the same as the environment path's 16-bit color samples. Do not equate these native buffer formats with JL-Mod Plus's RGBA upload or infer texture-coordinate behavior from them.

**Interpretation:** this specific native decoder suppresses palette-index-zero output in the tested environment path. The Android GLES instrumentation test additionally verifies the JL-Mod Plus upload-and-sampling behavior, but does not establish full firmware rendering parity or all Java exception semantics.
