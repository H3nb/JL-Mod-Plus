#!/usr/bin/env python3
from pathlib import Path

path = Path("app/src/main/java/javax/microedition/lcdui/keyboard/VirtualControlsKeyboard.java")
text = path.read_text(encoding="utf-8")

constant_anchor = "\tprivate static final int FEEDBACK_DURATION_MS = 50;\n"
if "ANALOG_ACCENT_BLUE" not in text:
    assert constant_anchor in text
    text = text.replace(
        constant_anchor,
        constant_anchor + '''
\t// Physical-controller-inspired palette. Existing profile colors subtly tint the hardware
\t// surfaces so custom themes remain recognizable without losing the reference identity.
\tprivate static final int CONTROL_SHELL_DARK = 0x11161D;
\tprivate static final int CONTROL_FACE_DARK = 0x202630;
\tprivate static final int CONTROL_INNER_DARK = 0x0A0E13;
\tprivate static final int CONTROL_EDGE_LIGHT = 0x8995A5;
\tprivate static final int CONTROL_ICON_LIGHT = 0xE1E7EF;
\tprivate static final int ANALOG_ACCENT_BLUE = 0x4D9FFF;
\tprivate static final int DPAD_ACCENT_RED = 0xFF4D5C;
''',
        1,
    )

field_anchor = "\tprivate final VirtualAnalogDirectionAdapter directionAdapter = new VirtualAnalogDirectionAdapter();\n"
if "paintRect = new RectF()" not in text:
    assert field_anchor in text
    text = text.replace(
        field_anchor,
        field_anchor + "\tprivate final RectF paintRect = new RectF();\n",
        1,
    )

start = text.index("\tprivate void paintDpad(CanvasWrapper graphics) {")
end = text.index("\tprivate VirtualDpadGeometry dpadGeometry()", start)

new_block = r'''\tprivate void paintDpad(CanvasWrapper graphics) {
\t\tVirtualDpadGeometry geometry = dpadGeometry();
\t\tfloat cx = geometry.getCenterX();
\t\tfloat cy = geometry.getCenterY();
\t\tfloat r = geometry.getRadius();
\t\tint alpha = controlAlpha();
\t\tboolean selected = editControl == EditControl.DPAD;
\t\tSet<VirtualDpadDirection> pressed = dpadToken == null
\t\t\t\t? java.util.Collections.emptySet()
\t\t\t\t: dpadController.state(dpadToken);

\t\tint shell = blendRgb(CONTROL_SHELL_DARK, settings.vkBgColor, 0.18f);
\t\tint face = blendRgb(CONTROL_FACE_DARK, settings.vkBgColor, 0.24f);
\t\tint edge = blendRgb(CONTROL_EDGE_LIGHT, settings.vkOutlineColor, 0.32f);
\t\tint icon = blendRgb(CONTROL_ICON_LIGHT, settings.vkFgColor, 0.34f);
\t\tint active = blendRgb(DPAD_ACCENT_RED, settings.vkBgColorSelected, 0.14f);

\t\t// A quiet circular cradle makes the four separated arms read as one controller control
\t\t// while leaving the corner zones visually open for diagonal slides.
\t\tif (selected) {
\t\t\tfillCircle(graphics, cx, cy, r * 1.10f,
\t\t\t\t\tcolorWithAlpha(settings.vkBgColorSelected, scaledAlpha(alpha, 0.16f)));
\t\t}
\t\tfillCircle(graphics, cx, cy, r * 1.03f,
\t\t\t\tcolorWithAlpha(shell, scaledAlpha(alpha, 0.30f)));
\t\tdrawCircle(graphics, cx, cy, r * 1.03f,
\t\t\t\tcolorWithAlpha(edge, scaledAlpha(alpha, 0.30f)));

\t\tfloat halfArm = r * 0.38f;
\t\tfloat centerGap = r * 0.11f;
\t\tint round = Math.max(5, Math.round(r * 0.14f));

\t\tpaintDpadButton(graphics,
\t\t\t\tcx - halfArm, cy - r, cx + halfArm, cy - centerGap,
\t\t\t\tround, pressed.contains(VirtualDpadDirection.UP), face, edge, active, alpha, r);
\t\tpaintDpadButton(graphics,
\t\t\t\tcx - halfArm, cy + centerGap, cx + halfArm, cy + r,
\t\t\t\tround, pressed.contains(VirtualDpadDirection.DOWN), face, edge, active, alpha, r);
\t\tpaintDpadButton(graphics,
\t\t\t\tcx - r, cy - halfArm, cx - centerGap, cy + halfArm,
\t\t\t\tround, pressed.contains(VirtualDpadDirection.LEFT), face, edge, active, alpha, r);
\t\tpaintDpadButton(graphics,
\t\t\t\tcx + centerGap, cy - halfArm, cx + r, cy + halfArm,
\t\t\t\tround, pressed.contains(VirtualDpadDirection.RIGHT), face, edge, active, alpha, r);

\t\t// Raised center pivot, matching the familiar physical D-pad silhouette.
\t\tfillCircle(graphics, cx, cy, r * 0.27f,
\t\t\t\tcolorWithAlpha(CONTROL_INNER_DARK, scaledAlpha(alpha, 0.92f)));
\t\tfillCircle(graphics, cx, cy, r * 0.21f,
\t\t\t\tcolorWithAlpha(face, scaledAlpha(alpha, 0.78f)));
\t\tdrawCircle(graphics, cx, cy, r * 0.27f,
\t\t\t\tcolorWithAlpha(edge, scaledAlpha(alpha, 0.34f)));

\t\tfloat textScale = clamp(r / 120.0f, 0.68f, 1.08f);
\t\tgraphics.setTextScale(textScale);
\t\tpaintDpadGlyph(graphics, "▲", cx, cy - r * 0.57f,
\t\t\t\tpressed.contains(VirtualDpadDirection.UP), icon, active, alpha);
\t\tpaintDpadGlyph(graphics, "▼", cx, cy + r * 0.57f,
\t\t\t\tpressed.contains(VirtualDpadDirection.DOWN), icon, active, alpha);
\t\tpaintDpadGlyph(graphics, "◀", cx - r * 0.57f, cy,
\t\t\t\tpressed.contains(VirtualDpadDirection.LEFT), icon, active, alpha);
\t\tpaintDpadGlyph(graphics, "▶", cx + r * 0.57f, cy,
\t\t\t\tpressed.contains(VirtualDpadDirection.RIGHT), icon, active, alpha);
\t\tgraphics.setTextScale(1.0f);
\t}

\tprivate void paintDpadButton(
\t\t\tCanvasWrapper graphics,
\t\t\tfloat left, float top, float right, float bottom,
\t\t\tint round, boolean active,
\t\t\tint face, int edge, int accent, int alpha, float radius) {
\t\tfloat shadowOffset = Math.max(1.0f, radius * 0.045f);
\t\tpaintRect.set(left, top + shadowOffset, right, bottom + shadowOffset);
\t\tgraphics.setFillColor(colorWithAlpha(0x000000, scaledAlpha(alpha, 0.48f)));
\t\tgraphics.fillRoundRect(paintRect, round, round);

\t\tif (active) {
\t\t\tfloat glow = Math.max(1.0f, radius * 0.055f);
\t\t\tpaintRect.set(left - glow, top - glow, right + glow, bottom + glow);
\t\t\tgraphics.setFillColor(colorWithAlpha(accent, scaledAlpha(alpha, 0.30f)));
\t\t\tgraphics.fillRoundRect(paintRect, round + Math.round(glow), round + Math.round(glow));
\t\t}

\t\tpaintRect.set(left, top, right, bottom);
\t\tint activeFace = active ? blendRgb(face, accent, 0.24f) : face;
\t\tgraphics.setFillColor(colorWithAlpha(activeFace, scaledAlpha(alpha, active ? 0.94f : 0.82f)));
\t\tgraphics.fillRoundRect(paintRect, round, round);
\t\tgraphics.setDrawColor(colorWithAlpha(active ? accent : edge,
\t\t\t\tscaledAlpha(alpha, active ? 0.98f : 0.60f)));
\t\tgraphics.drawRoundRect(paintRect, round, round);

\t\t// Narrow top highlight gives each arm a tactile, slightly raised surface without a bitmap.
\t\tfloat highlightHeight = Math.max(1.0f, radius * 0.018f);
\t\tfloat inset = Math.max(2.0f, radius * 0.10f);
\t\tpaintRect.set(left + inset, top + inset * 0.55f, right - inset,
\t\t\t\tMath.min(bottom, top + inset * 0.55f + highlightHeight));
\t\tgraphics.setFillColor(colorWithAlpha(0xFFFFFF,
\t\t\t\tscaledAlpha(alpha, active ? 0.18f : 0.10f)));
\t\tgraphics.fillRoundRect(paintRect, Math.max(1, round / 3), Math.max(1, round / 3));
\t}

\tprivate void paintDpadGlyph(
\t\t\tCanvasWrapper graphics,
\t\t\tString glyph,
\t\t\tfloat x, float y,
\t\t\tboolean active,
\t\t\tint icon, int accent, int alpha) {
\t\tgraphics.setTextColor(colorWithAlpha(active ? accent : icon,
\t\t\t\tscaledAlpha(alpha, active ? 1.0f : 0.82f)));
\t\tgraphics.drawString(glyph, x, y);
\t}

\tprivate void paintAnalog(CanvasWrapper graphics) {
\t\tVirtualAnalogVisualState visual = analogStick.visualState(viewport);
\t\tfloat centerX = screenBounds.left + visual.getCenterX();
\t\tfloat centerY = screenBounds.top + visual.getCenterY();
\t\tfloat radius = visual.getRadius();
\t\tfloat rawThumbX = screenBounds.left + visual.getThumbX();
\t\tfloat rawThumbY = screenBounds.top + visual.getThumbY();
\t\t// A physical stick knob cannot travel to the outer rim with its full diameter. Keep the
\t\t// input normalization untouched, but compress visual travel so the thumb stays inside the
\t\t// chassis like the reference controller.
\t\tfloat thumbX = centerX + (rawThumbX - centerX) * 0.50f;
\t\tfloat thumbY = centerY + (rawThumbY - centerY) * 0.50f;
\t\tboolean active = visual.getActive();
\t\tboolean selected = editControl == EditControl.ANALOG;
\t\tint alpha = controlAlpha();

\t\tint shell = blendRgb(CONTROL_SHELL_DARK, settings.vkBgColor, 0.18f);
\t\tint face = blendRgb(CONTROL_FACE_DARK, settings.vkBgColor, 0.24f);
\t\tint inner = blendRgb(CONTROL_INNER_DARK, settings.vkBgColor, 0.12f);
\t\tint edge = blendRgb(CONTROL_EDGE_LIGHT, settings.vkOutlineColor, 0.30f);
\t\tint accent = blendRgb(ANALOG_ACCENT_BLUE, settings.vkBgColorSelected, 0.16f);

\t\tif (selected) {
\t\t\tfillCircle(graphics, centerX, centerY, radius * 1.10f,
\t\t\t\t\tcolorWithAlpha(settings.vkBgColorSelected, scaledAlpha(alpha, 0.15f)));
\t\t}

\t\t// Outer translucent halo and chassis.
\t\tfillCircle(graphics, centerX, centerY, radius * 1.04f,
\t\t\t\tcolorWithAlpha(accent, scaledAlpha(alpha, active ? 0.18f : 0.10f)));
\t\tfillCircle(graphics, centerX, centerY, radius * 0.94f,
\t\t\t\tcolorWithAlpha(shell, scaledAlpha(alpha, 0.66f)));
\t\tdrawCircle(graphics, centerX, centerY, radius * 0.94f,
\t\t\t\tcolorWithAlpha(edge, scaledAlpha(alpha, 0.52f)));

\t\t// Blue guide ring, built from concentric discs so it stays visible at any density.
\t\tfillCircle(graphics, centerX, centerY, radius * 0.77f,
\t\t\t\tcolorWithAlpha(accent, scaledAlpha(alpha, active ? 0.74f : 0.46f)));
\t\tfillCircle(graphics, centerX, centerY, radius * 0.715f,
\t\t\t\tcolorWithAlpha(shell, scaledAlpha(alpha, 0.90f)));
\t\tfillCircle(graphics, centerX, centerY, radius * 0.63f,
\t\t\t\tcolorWithAlpha(inner, scaledAlpha(alpha, 0.76f)));

\t\tpaintAnalogTicks(graphics, centerX, centerY, radius, accent, alpha);

\t\tfloat thumbRadius = radius * 0.43f;
\t\t// Thumb shadow follows the moving knob and makes the control readable over bright games.
\t\tfillCircle(graphics, thumbX, thumbY + radius * 0.045f, thumbRadius * 1.02f,
\t\t\t\tcolorWithAlpha(0x000000, scaledAlpha(alpha, 0.52f)));
\t\tfillCircle(graphics, thumbX, thumbY, thumbRadius * 1.13f,
\t\t\t\tcolorWithAlpha(accent, scaledAlpha(alpha, active ? 0.32f : 0.13f)));
\t\tfillCircle(graphics, thumbX, thumbY, thumbRadius,
\t\t\t\tcolorWithAlpha(accent, scaledAlpha(alpha, active ? 0.86f : 0.54f)));
\t\tfillCircle(graphics, thumbX, thumbY, thumbRadius * 0.86f,
\t\t\t\tcolorWithAlpha(face, scaledAlpha(alpha, 0.96f)));
\t\tfillCircle(graphics, thumbX, thumbY, thumbRadius * 0.69f,
\t\t\t\tcolorWithAlpha(blendRgb(face, edge, 0.12f), scaledAlpha(alpha, 0.96f)));
\t\tdrawCircle(graphics, thumbX, thumbY, thumbRadius * 0.86f,
\t\t\t\tcolorWithAlpha(edge, scaledAlpha(alpha, 0.52f)));

\t\t// Subtle specular spot: enough depth to echo the reference without obscuring gameplay.
\t\tfillCircle(graphics,
\t\t\t\tthumbX - thumbRadius * 0.18f,
\t\t\t\tthumbY - thumbRadius * 0.20f,
\t\t\t\tthumbRadius * 0.30f,
\t\t\t\tcolorWithAlpha(0xFFFFFF, scaledAlpha(alpha, 0.07f)));
\t}

\tprivate void paintAnalogTicks(
\t\t\tCanvasWrapper graphics,
\t\t\tfloat cx, float cy, float radius,
\t\t\tint accent, int alpha) {
\t\tfloat offset = radius * 0.84f;
\t\tfloat length = Math.max(3.0f, radius * 0.105f);
\t\tfloat thickness = Math.max(1.5f, radius * 0.018f);
\t\tgraphics.setFillColor(colorWithAlpha(accent, scaledAlpha(alpha, 0.84f)));

\t\tpaintRect.set(cx - thickness / 2f, cy - offset - length / 2f,
\t\t\t\tcx + thickness / 2f, cy - offset + length / 2f);
\t\tgraphics.fillRect(paintRect);
\t\tpaintRect.set(cx - thickness / 2f, cy + offset - length / 2f,
\t\t\t\tcx + thickness / 2f, cy + offset + length / 2f);
\t\tgraphics.fillRect(paintRect);
\t\tpaintRect.set(cx - offset - length / 2f, cy - thickness / 2f,
\t\t\t\tcx - offset + length / 2f, cy + thickness / 2f);
\t\tgraphics.fillRect(paintRect);
\t\tpaintRect.set(cx + offset - length / 2f, cy - thickness / 2f,
\t\t\t\tcx + offset + length / 2f, cy + thickness / 2f);
\t\tgraphics.fillRect(paintRect);
\t}

\tprivate void fillCircle(CanvasWrapper graphics, float cx, float cy, float radius, int color) {
\t\tpaintRect.set(cx - radius, cy - radius, cx + radius, cy + radius);
\t\tgraphics.setFillColor(color);
\t\tgraphics.fillArc(paintRect, 0, 360);
\t}

\tprivate void drawCircle(CanvasWrapper graphics, float cx, float cy, float radius, int color) {
\t\tpaintRect.set(cx - radius, cy - radius, cx + radius, cy + radius);
\t\tgraphics.setDrawColor(color);
\t\tgraphics.drawArc(paintRect, 0, 360);
\t}

\tprivate static int colorWithAlpha(int rgb, int alpha) {
\t\treturn ((alpha & 0xFF) << 24) | (rgb & 0x00FFFFFF);
\t}

\tprivate static int scaledAlpha(int alpha, float factor) {
\t\treturn Math.max(0, Math.min(0xFF, Math.round(alpha * factor)));
\t}

\tprivate static int blendRgb(int first, int second, float secondWeight) {
\t\tfloat weight = clamp(secondWeight, 0.0f, 1.0f);
\t\tfloat firstWeight = 1.0f - weight;
\t\tint red = Math.round(((first >> 16) & 0xFF) * firstWeight + ((second >> 16) & 0xFF) * weight);
\t\tint green = Math.round(((first >> 8) & 0xFF) * firstWeight + ((second >> 8) & 0xFF) * weight);
\t\tint blue = Math.round((first & 0xFF) * firstWeight + (second & 0xFF) * weight);
\t\treturn (red << 16) | (green << 8) | blue;
\t}

'''
text = text[:start] + new_block + text[end:]
path.write_text(text, encoding="utf-8")

docs = Path("docs/gamepad-implementation-status.md")
if docs.exists():
    doc = docs.read_text(encoding="utf-8")
    if "physical-controller-inspired visual treatment" not in doc:
        doc += (
            "\n- The grouped virtual controls use a physical-controller-inspired visual treatment: "
            "a layered blue-ring analog well and four separated D-pad arms with a center pivot. "
            "Pressed D-pad arms light independently, so diagonal input visibly highlights both directions; "
            "profile opacity, normalized geometry, drag/pinch editing, and input hysteresis remain intact.\n"
        )
        docs.write_text(doc, encoding="utf-8")
