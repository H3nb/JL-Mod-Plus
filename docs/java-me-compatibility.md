# Java ME compatibility changes

Use this guidance when changing Java ME APIs, JSRs, vendor APIs, or guest compatibility behavior. For host runtime presentation and LCDUI command boundaries, also read [Runtime UI](runtime-ui.md).

## Java ME compatibility

For changes to Java ME APIs, JSRs, vendor APIs, or compatibility behavior:

- Consult `https://github.com/shinovon/J2ME_Docs` before editing the implementation.
- Verify the relevant API contract, constants, state transitions, return values, exceptions, and edge cases.
- Compare the specification with existing JL-Mod/JL-Mod Plus behavior before changing compatibility-sensitive code.
- Do not silently "correct" known-compatible behavior just because Android or desktop Java behaves differently.
- If documentation is incomplete or ambiguous, prefer preserving known behavior and add a focused characterization/regression test instead of guessing.
