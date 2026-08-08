# SONiVOX 4 integration

JL-Mod Plus pins the active migration source to **EmbeddedSynth/sonivox v4.0.1** at commit
`2dac582929e545f6c085981100744753992addb8` through `app/src/main/cpp/sonivox_v4`.

The build intentionally does not copy upstream CMake defaults. Java ME/MMAPI compatibility keeps:

- wavetable synthesis, 64 voices, stereo, 16-bit output;
- 22.05 kHz synthesis during the compatibility-first migration;
- SMF, XMF, RMID, DLS and SF2;
- MMAPI ToneControl;
- iMelody, RTTTL and OTA parsers;
- reverb and chorus.

The first migration deliberately leaves FM/hybrid synthesis, JET, SONiVOX WAVE parsing and the SONiVOX IMA decoder disabled. WAV/IMA-ADPCM belongs to the dedicated `dr_wav` backend so file audio cannot enter the MIDI synth path.

TinySoundFont remains in-tree temporarily as an SF2 fallback until representative soundbank parity testing is complete. It is not the preferred SF2 backend once SONiVOX 4 is active.
