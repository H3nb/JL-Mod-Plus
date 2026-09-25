# Java ME compatibility changes

Use this guidance when changing Java ME APIs, JSRs, vendor APIs, or guest compatibility behavior. For host runtime presentation and LCDUI command boundaries, also read [Runtime UI](runtime-ui.md).

## Java ME compatibility

For changes to Java ME APIs, JSRs, vendor APIs, or compatibility behavior:

- Consult the applicable Java ME/JSR or vendor specification before editing compatibility-sensitive implementation; `https://github.com/shinovon/J2ME_Docs` is the repository's convenient reference set.
- Verify the relevant API contract, constants, state transitions, return values, exceptions, and edge cases. When the specification is incomplete, verified device/game behavior may provide compatibility evidence.
- Compare the specification and other verified compatibility evidence with existing JL-Mod/JL-Mod Plus behavior before changing compatibility-sensitive code.
- Do not silently "correct" known-compatible behavior just because Android or desktop Java behaves differently.
- If documentation is incomplete or ambiguous, prefer preserving known behavior and add a focused characterization/regression test instead of guessing.
