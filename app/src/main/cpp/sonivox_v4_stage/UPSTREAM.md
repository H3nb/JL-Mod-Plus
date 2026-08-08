# SONiVOX 4 integration

JL-Mod Plus pins the active EAS synth source to **EmbeddedSynth/sonivox v4.0.1** at commit
`2dac582929e545f6c085981100744753992addb8` through `app/src/main/cpp/sonivox_v4`.
`mmapi_eas` links the `sonivox_v4` static module defined by this integration directory.

The build intentionally does not copy upstream CMake defaults. Java ME/MMAPI compatibility currently keeps:

- wavetable synthesis, 64 voices, stereo, 16-bit output;
- 22.05 kHz synthesis for the compatibility-first core migration;
- SMF, XMF, RMID, DLS and SF2;
- live `device://midi` and MMAPI ToneControl;
- reverb and chorus.

The optional upstream iMelody, RTTTL and OTA parser sources are temporarily excluded from the ARM64 v4 integration. In v4.0.1 they still implement the older parser callback ABI with `EAS_I32` where the current `S_FILE_PARSER_INTERFACE` requires pointer-sized `EAS_IPTR`, and their state callback definitions also disagree with the current `EAS_STATE` declaration. Compiling them by suppressing the diagnostics would retain pointer truncation on 64-bit devices, so legacy ringtone parser restoration is deferred to a dedicated pointer-width-safe compatibility patch rather than weakening native safety. These formats are not advertised by `Manager.getSupportedContentTypes()` during this core migration.

The core migration deliberately leaves FM/hybrid synthesis, JET, SONiVOX WAVE parsing and the SONiVOX IMA decoder disabled. WAV/IMA-ADPCM belongs to the dedicated `dr_wav` backend so file audio cannot enter the MIDI synth path.

Soundbanks are configured per `LibEAS`/native Player instance. DLS and supported SF2 use SONiVOX first; TinySoundFont remains in-tree only as a temporary SF2 compatibility fallback until representative soundbank parity testing is complete.

The optional 44.1 kHz synthesis mode, restoration of the three legacy ringtone parsers, and removal of TinySoundFont are intentionally deferred until this 22.05 kHz core passes CI and real-game/audio corpus validation. That keeps sample-rate changes, legacy-parser repair, and synth-parity decisions separate from the engine/routing migration.
