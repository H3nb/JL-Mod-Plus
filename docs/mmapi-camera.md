# MMAPI camera capture

The camera subset implements JSR-135 preview and still snapshots. Recording,
microphone capture, gallery saving and JSR-234 camera controls are outside this
subset. `capture://video` names a live camera source; it does not promise a
`RecordControl`.

For a new camera issue, start with the
[device evidence and troubleshooting guide](camera-pr175-device-investigation.md).
It identifies code owners, the proven Miami root cause, completed device checks
and the independent debug MIDlet. The
[historical study](camera-java-me-history.md) records rationale, not pending work.

## Camera selection

| Locator | Emulated camera |
| --- | --- |
| `capture://video` | Configured default camera; rear when unspecified; falls back if the preferred direction is unavailable |
| `capture://devcam0` | Rear camera |
| `capture://devcam1` | Front camera |

These are emulated device names, not Android camera IDs or physical lens numbers.
JSR-135 Manager defines a generic named capture device syntax and uses `devcam0`
as an example. The JSR-234 overview documents `camera.orientations` with the example
`devcam0:outwards devcam1:inwards`; this implementation deliberately adopts that
mapping. The index-to-direction mapping is not a universal JSR-135 guarantee.
Unknown device names and unsupported locator parameters fail with `MediaException`.

The runtime camera action lets the user override the requested direction for the
current MIDlet session. It changes preview and capture together. A single rear
direction does not enumerate ultrawide and telephoto lenses.

MIDlet config groups audio and camera settings under **Media**. Its **Camera**
section selects the default camera (rear/front) and default resolution (automatic,
480 x 640 portrait, or 640 x 480 landscape). These are profile/preset values.
Named `devcam0`/`devcam1` requests remain strict and take precedence over the preferred
default camera; a session swap remains temporary and does not rewrite the profile.
When the toolbar is shown, an active camera exposes a swap shortcut when both
directions are available. It is disabled during capture/rebinding; when it cannot
fit or the toolbar is hidden, the same action remains in the options menu.

## Geometry and snapshots

Preview and still capture share framing and rotation, with no horizontal mirroring.
Display size changes affect presentation, not the capture source. Snapshot dimensions are
independent of preview dimensions. A different output aspect ratio fits the full
framing with padding rather than introducing a hidden crop. The guarantee concerns
composition; moving subjects and successive camera exposures can differ in time.

The preview View fits the logical source aspect inside the guest's display rectangle.
CameraX fills that smaller View using the shared ViewPort crop; the surrounding
container supplies black padding. Fitting the cropped buffer into a differently
shaped PreviewView can otherwise expose pixels beyond that crop. Snapshot filtering
is likewise confined to a bitmap bounded by the shared crop before rotation/scaling.
Rotation is applied once from `ImageProxy.imageInfo.rotationDegrees`; encoded output
has upright pixels and does not require a second EXIF rotation by the MIDlet.

`getSnapshot(null)` uses the default supported snapshot encoding. JPEG and PNG
are supported. With automatic resolution selected, source and snapshot defaults
are selected once per MIDlet session from the full virtual screen: 480 x 640 for portrait, 640 x 480 for
landscape or square. With automatic screen dimensions, a fixed profile/phone-keypad
orientation takes precedence over the launch-time Android display; otherwise the
initial resolved virtual size is used. The manual resolution choice supplies that
same session default directly. Viewport resizing and host rotation do not
change this default. Advertised automatic encoding strings use the same dimensions;
explicit profile encoding strings and explicitly requested sizes retain precedence.
This is an emulator default policy, not a universal feature-phone requirement.
Explicit sizes require both positive width and height, with each
axis at most 1280 and at most 1,228,800 output pixels. JPEG quality is 1 through
100; PNG has no quality parameter. Unknown, duplicate or unsupported parameters
fail instead of silently changing the request. Source encoding is raw (`rgb888`
or `video/raw`); the source Player content type is `video/raw`, independently of
the snapshot file encoding.

## Capability and profile ownership

Camera capability properties are resolved in memory when launching a MIDlet.
The persisted default properties, profile version and preset linkage are not
changed. A complete legacy factory template inherits its obsolete
`supports.video.capture: false` from the emulator, so runtime discovery replaces
it with the detected capability. Customized properties retain explicit values.
If a customized legacy profile still contains false, its origin is ambiguous:
the implementation preserves it rather than discarding a possible user override.
Removing that camera property restores automatic discovery; `auto` is also
accepted for the three managed camera properties.

Automatic `video.encodings` and `video.snapshot.encodings` are removed when camera
capture is unavailable. Explicit encoding overrides retain precedence.
`supports.recording` is unchanged; snapshots do not imply video recording.

The relevant primary specifications are
[JSR-135](https://jcp.org/en/jsr/detail?id=135) (`Manager`, `Player`, `VideoControl`
and overview system properties) and
[JSR-234](https://jcp.org/en/jsr/detail?id=234) (overview camera properties).

## Validation and device qualification

JVM tests cover locator/encoding validation, source discovery, profile ownership
and contextual menu actions. Android instrumentation covers snapshot geometry
with asymmetric pixel fixtures, all four rotations, crop exclusion, padding and
JPEG decoding; it also checks Player state and cancellable guest waits without
requiring camera hardware. These tests are included in the existing manual
`runtime-smoke` selection. They do not qualify actual Android camera behavior.

Before merging, use a photo-capable MIDlet on physical devices to compare the
preview against decoded snapshot bytes with a stationary asymmetric target:

| Scenario | Expected result |
| --- | --- |
| Canvas and Form, rear and front | Same crop, orientation and handedness |
| Portrait, landscape and 180-degree display turns; output ratios 4:3, 3:4 and square | Full shared crop, black padding if needed, exact requested size |
| Switch front/rear repeatedly | New preview before capture readiness; matching source in returned bytes |
| Deny/regrant CAMERA permission | Retryable failure, responsive host, no microphone request |
| Hide preview, take snapshot | Capture works without waiting for a visible preview surface |
| Pause/resume, recreate host, close/deallocate during a wait | No leaked camera or stale snapshot; blocked guest released |
| Camera busy or absent, unsupported parameters | Controlled failure without terminating the Android host |

Implementation guidance: Android's [CameraX skill](https://github.com/android/skills/blob/5bda39f154fb859a08d45e2392f3568e049f83fb/camera/camerax/SKILL.md),
reviewed at upstream commit `5bda39f154fb859a08d45e2392f3568e049f83fb`.
