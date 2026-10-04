# Sonivox snapshot

EmbeddedSynth/sonivox v4.0.2, commit
`bb8668b91118318b0e6017aca59ce34e7bc93bf8` from
https://github.com/EmbeddedSynth/sonivox.

`host_src/`, `lib_src/`, and `src/` are the upstream `arm-wt-22k/`
directories; `fakes/` is copied from upstream. Core changes are marked
`Modified for JL-Mod Plus.`:

- `lib_src/eas_sf2.c`: zero preset articulations before parsing.
- `lib_src/eas_sf2.c`: channel-pressure vibrato targets the vibrato LFO.
- `lib_src/eas_dlssynth.c`: update pan for held DLS/SF2 voices.
- `lib_src/eas_mdls.c`: use the correct RIFF/LIST payload boundaries and reject
  invalid or overflowing chunk lengths. A valid generated DLS with its final
  wave at EOF exposed the upstream extra four bytes in nested LIST parsing.
- `lib_src/eas_public.c`: MMAPI ToneControl uses the correct reporting signature,
  closes host cursors on allocation/recognition failure, and retrieves optional
  format pointers through pointer-width storage. Initialization failures now
  perform partial shutdown rather than losing the unreturned context/host.
  This upstream build normally
  leaves MMAPI_SUPPORT disabled; the emulator enables it for ToneControl.
- `lib_src/eas_imelody.c`, `eas_ota.c`, and `eas_rtttl.c`: retained parsers use
  pointer-width parameter values and the declared EAS_STATE type, matching the
  current parser interface on ARM64 and 32-bit targets.
- `fakes/log/log.h`: silence the additional debug macros used by the retained
  OTA parser, keeping all core logging out of the real-time callback.

The checked-in Android.mk and generated-equivalent `eas_options.h`,
`eas_version.h`, and `eas_visibility.h` are local build integration. The
snapshot keeps upstream whitespace and inherited notices. `LICENSE-2.0.txt`
and `NOTICE` retain the upstream attribution.

The adapter and memory-only host live in `../mmapi_eas/`, outside the core.
Every core allocation belongs to its host instance. Normal frees unlink in
constant time; host shutdown also reclaims parser temporary blocks orphaned by
upstream failure returns. This covers incomplete initialization and bank-load
allocation failures without sharing mutable context state.
The adapter deliberately uses the pinned private `S_EAS_STREAM` parser pointer
to suspend sequencing while rendering interactive MIDI at PREFETCHED. It
restores the pointer immediately after each render. Upgrading the snapshot
requires reviewing that coupling and rerunning native qualification.

The build enables the WT `wt_200k_G` embedded sample bank, 44.1 kHz, stereo,
256-frame render blocks, 64 voices, DLS/SF2, ToneControl, OTA, RTTTL, iMelody,
and uncompressed XMF. The optional new upstream zlib unpacker is disabled,
matching the previous emulator build. Built-in sample data resides in `lib_src/wt_200k_G.c`;
external user soundbanks are not vendored. SF2 support remains partial.

Each Player owns its own EAS context and custom collection. Limits are 16 live
Players per runtime process, 16 MiB encoded media per Player, 128 MiB encoded
bank, 128 memory host cursors per context, and a 16 KiB MIDI queue. Queue writes
serialize producers and fail atomically when capacity is unavailable. The
callback consumes at most 4096 MIDI bytes per render block. There is no shared
mutable collection and no silently substituted soundbank on load failure.

Audio callbacks copy fixed render blocks through a staging cursor, accepting
arbitrary positive output frame counts. Oboe performs sample-rate conversion;
the adapter contains no second resampler. Controls pause/quiesce the callback
before accessing EAS. Stop and output recovery preserve voices and remaining
PCM; seek discards staging data. Native events are polled on Java management
threads, with generation checks. Native code retains no JNI global references
and creates no dispatch/recovery thread. Each output instance owns its callback
error/enabled/progress facts and retains the Player until those callbacks end.
Closing output retires that instance; delayed errors cannot mutate a replacement.
Management permits three recovery attempts without healthy output. At least
4,410 rendered frames (100 ms at 44.1 kHz) on the replacement replenish the
budget for a later episode; open/start success alone does not. Host suspension
does not render or replenish the budget. Recovery never restarts media.
