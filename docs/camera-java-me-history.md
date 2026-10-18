# Java ME camera capabilities and orientation: PR 175 follow-up

Research on 2026-10-18, following the physical-device investigation. This study
corrects the interpretation that Miami Nights 2's additional rotation alone proves
a game defect. Its observed path expects portrait geometry; the investigated PR
head supplied an implicit landscape default. The later approved policy is recorded
below and in [MMAPI camera](mmapi-camera.md).

## What the APIs actually expose

| Mechanism | Meaning | Does it select portrait/landscape? |
| --- | --- | --- |
| `video.snapshot.encodings` | Space-separated supported still-image encoding strings; first entry is the default | Can include ordered width/height pairs; no universal required default dimensions |
| `getSnapshot(imageType)` | Requests still format/resolution; null selects the advertised default | Explicit width/height specify image geometry, but dimensions alone cannot tell whether scene pixels are sideways |
| `video.encodings`, `capture://video?...` | Source capture encoding/parameters | Source dimensions are distinct from still-image encoding and display dimensions |
| `getSourceWidth/Height()` | Source video dimensions | Describes source geometry |
| `setDisplaySize`, fullscreen | Presentation region and implementation-specific scaling/clipping | Does not specify an automatic snapshot-size or rotation rule |
| JSR-234 `CameraControl` | Optional camera controls, supported still/video size pairs and selection by index | Also exposes camera rotation; it is not part of baseline JSR-135 |
| Vendor UI orientation APIs/JAD attributes | Application/display orientation | A separate mechanism; it does not itself specify the snapshot byte format |

The [JSR-135 overview](https://nikita36078.github.io/J2ME_Docs/docs/jsr135/overview-summary.html)
defines the capability properties. [Manager](https://nikita36078.github.io/J2ME_Docs/docs/jsr135/javax/microedition/media/Manager.html)
defines encoding-string syntax, including optional width/height parameters and
capture locators. [VideoControl](https://docs.oracle.com/javame/config/cldc/opt-pkgs/api/mm/jsr135/javax/microedition/media/control/VideoControl.html)
defines the null default and display methods. None of these establishes a rule
that a portrait Canvas must produce portrait snapshot dimensions, nor a rule that
every camera must return 640 x 480.

The optional [JSR-234 CameraControl](https://wupp.cc/j2meapi/JSR_234_Advanced_Multimedia_Supplements_1_1/javax/microedition/amms/control/camera/CameraControl.html)
returns supported sizes as x/y pairs and selects them by index. `getCameraRotation`
reports NONE/LEFT/RIGHT/UNKNOWN; it is not a baseline MMAPI setter for image
orientation. Its exposure modes named portrait/landscape describe photographic
scenes, not width/height orientation. Likewise, `camera.orientations` in the
[JSR-234 overview](https://wupp.cc/j2meapi/JSR_234_Advanced_Multimedia_Supplements_1_1/overview-summary.html)
describes outward/inward camera direction, not portrait/landscape photos.

## Historical evidence and its limits

Sony Ericsson's February 2010 *Developers guidelines: Java ME, CLDC-MIDP 2*,
document 1233-5081.3, covers Satio/Vivaz/Vivaz pro. Its camera section, printed
page 20, documents JPEG snapshots, a resolution list queried through
`video.snapshot.encodings`, and passing a selected entry to `getSnapshot`.
The list is ascending on that platform, so null chooses its lowest resolution.
It gives a 640 x 480 encoding-string example and says the JSR-234 Camera extension
is unsupported. This is a vendor-specific policy, not a rule that every MMAPI
implementation sorts sizes or uses the same default.
[Vendor document, archived HTML transcription](https://manualzz.com/doc/23656675/sony-ericsson-satio--vivaz--vivaz-pro-phones-developers-g...).

The indexed extraction of Sony Ericsson's March 2007 *Developers guidelines:
Java ME, CLDC-MIDP 2.0 for feature and entry level phones* includes portrait size
headings such as 240 x 320 and 480 x 640. A page-42 footnote distinguishes the
resolution available to `VideoControl.getSnapshot` on JP-7 from other sizes exposed
through JSR-234. However, the [PDF mirror](https://bombus.jrudevels.org/docs/dg_java_me_r20a.pdf)
could not be retrieved locally or opened by the browser fetcher. Only search-index
text was available. The table layout, model-to-size cells, and actual pixel/EXIF
orientation are therefore unverified; do not use them to prescribe a global
portrait default or a fixed quarter-turn.

Nokia's [Orientation API documentation](https://nikita36078.github.io/J2ME_Docs/docs/nokiaapi2/com/nokia/mid/ui/orientation/package-summary.html)
describes `Nokia-MIDlet-App-Orientation` values portrait, landscape and manual.
It explicitly distinguishes application orientation and display orientation;
manual mode requires the application to request subsequent changes. This is
evidence of an explicit UI-orientation mechanism, not evidence that resizing a
camera viewport changes still-image dimensions. Availability varies by platform.

An actual open-source MIDlet provides a useful capability-selection example:
[ShareNav GuiCamera](https://github.com/sharenav/sharenav/blob/f62f4c5e73dfaae32f36dc8bbc393f7bb6b64fda/src/net/sharenav/sharenav/ui/GuiCamera.java)
splits `video.snapshot.encodings` into selectable entries, permits a custom
encoding string, and passes the selection to `getSnapshot`. Its optional AMMS path
queries `getSupportedStillResolutions` and selects an index. This demonstrates
application selection, without claiming that every MIDlet follows that strategy.

These sources establish multiple selection mechanisms and vendor-specific defaults.
They do not establish one historical camera orientation convention for all phones.
No original Java ME phone has been physically tested in this investigation.
Native camera application behavior must not be substituted for MMAPI behavior.

## Reinterpretation of the physical evidence

Miami Nights 2 does not select an encoding entry in the observed capture path:
it calls `getSnapshot(null)`. It uses a 480 x 640 working photo and 48 x 64
thumbnail, and rotates decoded images when height is less than width. Its portrait
viewport agrees with that expectation. The [device report](camera-pr175-device-investigation.md)
records the exact CameraX metadata and executed MIDP transform.

The investigated PR head published 640 x 480 in both automatic properties and replaced
omitted source/snapshot dimensions with that same pair in `CameraCaptureOptions`.
The source pair also controls the shared ViewPort. Thus the implicit landscape
choice precedes the encoder and the game. Rotation normalization then puts an
upright scene into a landscape rectangle; the game rotates that rectangle.
The first additional pixel rotation is in the game, but the earliest compatibility
policy mismatch is the emulator's implicit geometry versus the game's expectation.

Returning an upright portrait image would avoid that branch. This is a prediction
from the inspected branch, not a completed final-build game test of a new policy.
Simply changing output width/height while keeping the current landscape ViewPort
would retain the wrong implicit framing and add padding; that is not a complete
geometry correction.

## Original proposal and adopted policy

The sensor-derived default below was the initial proposal. The user subsequently
approved a virtual-screen-derived default instead: portrait 480 x 640, landscape
or square 640 x 480, fixed for the MIDlet session. Automatic capability properties,
unspecified source dimensions and unspecified snapshot dimensions share that choice.
Explicit dimensions/profile defaults keep precedence. The implemented policy is
described in [MMAPI camera](mmapi-camera.md); no sensor-derived default or removal
of advertised dimensions has been adopted. The following retains the earlier
proposal for the investigation's reasoning, not as an implementation requirement.

Replace the unconditional landscape default with a resolved camera geometry:

1. Preserve whether source/output dimensions were explicitly supplied. Explicit
   dimensions and explicit profile encoding defaults remain authoritative.
2. For an unspecified source, derive its aspect from the selected CameraX buffer
   in target orientation. Use CameraX metadata rather than assuming sensor mounting
   or adding a fixed rotation. Resolve the shared crop, preview aspect and reported
   source dimensions from that one geometry.
3. For a snapshot without dimensions, fit that upright source aspect within a
   bounded default size, proposed longest edge 640. For a 3:4 source this yields
   480 x 640; for 4:3 it yields 640 x 480. Explicit snapshot dimensions keep exact
   requested sizes and the existing shared-framing/padding policy.
4. Advertise automatic snapshot formats without a forced dimension pair, for example
   `encoding=jpeg encoding=png`, and automatic source encoding as `encoding=rgb888`,
   so the advertised defaults remain truthful across resolved geometry.
   This syntax is permitted by Manager's grammar.
   Capability discovery, parsing and actual capture must agree.
5. A guest display-region resize remains a presentation change. It must not silently
   rotate/reselect the source. Continue to use the current CameraX target-display
   rotation policy unless separate evidence supports changing that policy.

This recommendation is an emulator policy choice supported by the current game
evidence and the sensor-oriented approach in [J2ME Loader](https://github.com/nikita36078/J2ME-Loader/blob/13e29830632464719a643043e1113310fe6e64cc/app/src/main/java/javax/microedition/media/CameraController.java),
not a claim that JSR-135 mandates it. It needs agreement before implementation:
MIDlets using null or omitted sizes will observe different default dimensions and
framing, and MIDlets parsing the automatic property will no longer see a fixed size.
Source dimensions must be coherent at the existing API state boundaries, including
realize/prefetch, a lens switch and display rotation; lifecycle changes cannot be
treated as a mere encoder edit.

Acceptance requires the independent MIDlet and Miami retested on-device with null,
format-only and explicit sizes; source-dimension/capability readback; front/rear and
rotation/aspect coverage; display resizing without source changes; profile-default
precedence; and the existing failure/lifecycle checks. Do not counter-rotate pixels,
identify the game, or infer a universal old-phone rule from a portrait viewport.
