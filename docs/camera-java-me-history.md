# Java ME camera capabilities and orientation: research rationale

Research on 2026-10-18, following the physical-device investigation. This study
corrects the interpretation that Miami Nights 2's additional rotation alone proves
a game defect. Its observed path expects portrait geometry; the investigated PR
head supplied an implicit landscape default. The later approved policy is recorded
below. Use [MMAPI camera](mmapi-camera.md) for the current contract and the
[device/troubleshooting handoff](camera-pr175-device-investigation.md) for code
entry points, measured evidence and reproducible tests. This research is not a
list of pending implementation steps.

## Decision in force

The user approved a virtual-screen-derived automatic default: portrait 480 x 640,
landscape/square 640 x 480, fixed for the MIDlet session. Media configuration can
override that default and select rear/front. Explicit MIDlet sizes and profile
encoding defaults retain precedence. This is an emulator compatibility policy;
the historical sources below do not make it a universal JSR-135 requirement.

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

Returning an upright portrait image avoids that branch. The user subsequently
confirmed expected Miami behavior after installing the approved default update.
That confirmation is separate from the earlier instrumented capture evidence.
Simply changing output width/height while keeping the current landscape ViewPort
would retain the wrong implicit framing and add padding; that is not a complete
geometry correction.

## Alternative considered, not adopted

An initial proposal derived implicit source aspect from the upright CameraX buffer,
bounded the default output's longest edge at 640, and advertised formats without
dimension pairs. The user chose the virtual-screen policy instead. No sensor-derived
default or removal of advertised dimensions was implemented. Reopening that choice
would be a substantive compatibility change for MIDlets using omitted sizes and
parsing capability strings; it requires new evidence and an explicit proposal.

Both approaches require one coherent source/ViewPort/default-output geometry and
keep display resizing separate. Neither justifies counter-rotating all pixels,
identifying a particular game, or deriving a universal phone rule from a viewport.
Use the current qualification matrix and measured results linked above when
investigating a new failure; do not implement the discarded proposal as unfinished work.
