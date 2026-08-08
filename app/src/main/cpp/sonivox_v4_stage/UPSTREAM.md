# SONiVOX 4 integration

JL-Mod Plus pins the active EAS synth source to **EmbeddedSynth/sonivox v4.0.1** at commit
`2dac582929e545f6c085981100744753992addb8` through `app/src/main/cpp/sonivox_v4`.
`mmapi_eas` links the `sonivox_v4` static module defined by this integration directory.

The build intentionally does not copy upstream CMake defaults. Java ME/MMAPI compatibility currently keeps:

- wavetable synthesis, 64 voices, stereo, 16-bit output;
- 22.05 kHz synthesis for the compatibility-first core migration;
- SMF, XMF, RMID, DLS and SF2;
- iMelody, RTTTL/RTX and Nokia OTA ringtone parsing;
- live `device://midi` and MMAPI ToneControl;
- reverb and chorus.

## Legacy ringtone parser ABI compatibility

SONiVOX v4 changed the generic parser interface so `pfState` returns through `EAS_STATE *` and parser values that may carry pointers use `EAS_IPTR`. `EAS_IPTR` is based on `intptr_t`; this distinction is required on LP64/ARM64. The pinned v4.0.1 iMelody, RTTTL and OTA source files retained their older `EAS_I32` state/set/get callback definitions. Their metadata callback value and synth-handle return therefore cannot be passed through the old callbacks safely on a 64-bit process.

JL-Mod Plus restores those formats without changing their parsing algorithms. `eas_imelody_arm64_compat.c`, `eas_rtttl_arm64_compat.c` and `eas_ota_arm64_compat.c` include the exact parser implementation from the pinned submodule behind a private description of the historical callback ABI. Each translation unit then publishes a current `S_FILE_PARSER_INTERFACE` that:

- widens the legacy state result into `EAS_STATE`;
- receives metadata pointers as `EAS_IPTR` and copies the callback descriptor without narrowing the pointer;
- returns the synth handle as `EAS_IPTR`;
- preserves the parser's original file-type and gain-offset behavior.

The temporary `EAS_STATE` preprocessor mapping is applied only after the SONiVOX headers and parser data structures have already been included. It exists solely to make the inconsistent forward declaration and definition inside the legacy source agree with their historical 32-bit implementation; it does not alter SONiVOX public types. The legacy parser interface object is private to the compatibility translation unit and is never registered. `eas_config.c` sees only the pointer-width-safe interface.

`api_smoke.c` asserts that `EAS_IPTR` exactly matches the platform pointer width and retains references to all three restored v4 parser interfaces. The module also treats incompatible function-pointer assignments as build errors. These guards are intentionally kept with the compatibility layer so a future SONiVOX update cannot silently reintroduce the old ABI mismatch.

These local bridges should be removed when upstream migrates the three parser source files themselves to the current parser ABI. Until then, keeping the parser algorithms in the submodule avoids maintaining a forked copy of the legacy format logic.

The core migration deliberately leaves FM/hybrid synthesis, JET, SONiVOX WAVE parsing and the SONiVOX IMA decoder disabled. WAV/IMA-ADPCM belongs to the dedicated `dr_wav` backend so file audio cannot enter the MIDI synth path.

Soundbanks are configured per `LibEAS`/native Player instance. DLS and supported SF2 use SONiVOX first; TinySoundFont remains in-tree only as a temporary SF2 compatibility fallback until representative soundbank parity testing is complete.

The optional 44.1 kHz synthesis mode and removal of TinySoundFont remain separate follow-up phases. Keeping them separate from legacy-parser restoration makes regressions attributable and keeps the 22.05 kHz compatibility baseline stable while the restored ringtone formats are validated.
