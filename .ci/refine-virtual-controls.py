#!/usr/bin/env python3
from pathlib import Path

path = Path("app/src/main/java/javax/microedition/lcdui/keyboard/VirtualControlsKeyboard.java")
text = path.read_text(encoding="utf-8")

palette_start = text.index("\t// Physical-controller-inspired palette.")
palette_end = text.index("\n\tprivate enum EditControl", palette_start)
text = text[:palette_start] + text[palette_end + 1:]

start = text.index("\tprivate void paintDpad(CanvasWrapper graphics) {")
end = text.index("\tprivate VirtualDpadGeometry dpadGeometry()", start)

new_block = """	private void paintDpad(CanvasWrapper graphics) {
		VirtualDpadGeometry geometry = dpadGeometry();
		float cx = geometry.getCenterX();
		float cy = geometry.getCenterY();
		float r = geometry.getRadius();
		int alpha = controlAlpha();
		boolean selected = editControl == EditControl.DPAD;
		Set<VirtualDpadDirection> pressed = dpadToken == null
				? java.util.Collections.emptySet()
				: dpadController.state(dpadToken);

		int base = controlBaseColor();
		int accent = controlAccentColor();
		int outline = controlOutlineColor();
		int icon = controlIconColor();
		int selectedIcon = controlSelectedIconColor();
		int shell = darkenRgb(base, 0.30f);
		int face = blendRgb(base, outline, 0.14f);
		int edge = blendRgb(outline, icon, 0.10f);
		int active = blendRgb(accent, outline, 0.08f);
		int activeFace = blendRgb(face, accent, 0.24f);

		// The cradle is deliberately quiet: it unifies the four buttons without becoming a large
		// fifth control over the game image.
		if (selected) {
			fillCircle(graphics, cx, cy, r * 1.03f,
					colorWithAlpha(accent, scaledAlpha(alpha, 0.08f)));
		}
		fillCircle(graphics, cx, cy, r * 0.94f,
				colorWithAlpha(shell, scaledAlpha(alpha, 0.12f)));
		drawCircle(graphics, cx, cy, r * 0.94f,
				colorWithAlpha(edge, scaledAlpha(alpha, 0.18f)));

		// Compact proportions keep the four arms visually connected to the center pivot.
		float halfArm = r * 0.32f;
		float centerGap = r * 0.055f;
		int round = Math.max(5, Math.round(r * 0.15f));

		paintDpadButton(graphics,
				cx - halfArm, cy - r, cx + halfArm, cy - centerGap,
				round, pressed.contains(VirtualDpadDirection.UP),
				face, activeFace, edge, active, alpha, r);
		paintDpadButton(graphics,
				cx - halfArm, cy + centerGap, cx + halfArm, cy + r,
				round, pressed.contains(VirtualDpadDirection.DOWN),
				face, activeFace, edge, active, alpha, r);
		paintDpadButton(graphics,
				cx - r, cy - halfArm, cx - centerGap, cy + halfArm,
				round, pressed.contains(VirtualDpadDirection.LEFT),
				face, activeFace, edge, active, alpha, r);
		paintDpadButton(graphics,
				cx + centerGap, cy - halfArm, cx + r, cy + halfArm,
				round, pressed.contains(VirtualDpadDirection.RIGHT),
				face, activeFace, edge, active, alpha, r);

		// A stronger two-layer pivot visually locks the separate arms into one physical D-pad.
		fillCircle(graphics, cx, cy, r * 0.245f,
				colorWithAlpha(darkenRgb(base, 0.50f), scaledAlpha(alpha, 0.96f)));
		fillCircle(graphics, cx, cy, r * 0.185f,
				colorWithAlpha(face, scaledAlpha(alpha, 0.94f)));
		drawCircle(graphics, cx, cy, r * 0.245f,
				colorWithAlpha(edge, scaledAlpha(alpha, 0.30f)));

		float textScale = clamp(r / 116.0f, 0.72f, 1.12f);
		graphics.setTextScale(textScale);
		paintDpadGlyph(graphics, "▲", cx, cy - r * 0.58f,
				pressed.contains(VirtualDpadDirection.UP), icon, selectedIcon, active, alpha);
		paintDpadGlyph(graphics, "▼", cx, cy + r * 0.58f,
				pressed.contains(VirtualDpadDirection.DOWN), icon, selectedIcon, active, alpha);
		paintDpadGlyph(graphics, "◀", cx - r * 0.58f, cy,
				pressed.contains(VirtualDpadDirection.LEFT), icon, selectedIcon, active, alpha);
		paintDpadGlyph(graphics, "▶", cx + r * 0.58f, cy,
				pressed.contains(VirtualDpadDirection.RIGHT), icon, selectedIcon, active, alpha);
		graphics.setTextScale(1.0f);
	}

	private void paintDpadButton(
			CanvasWrapper graphics,
			float left, float top, float right, float bottom,
			int round, boolean active,
			int face, int activeFace, int edge, int accent, int alpha, float radius) {
		float shadowOffset = Math.max(1.0f, radius * 0.030f);
		paintRect.set(left, top + shadowOffset, right, bottom + shadowOffset);
		graphics.setFillColor(colorWithAlpha(darkenRgb(face, 0.72f), scaledAlpha(alpha, 0.34f)));
		graphics.fillRoundRect(paintRect, round, round);

		if (active) {
			float glow = Math.max(1.0f, radius * 0.035f);
			paintRect.set(left - glow, top - glow, right + glow, bottom + glow);
			graphics.setFillColor(colorWithAlpha(accent, scaledAlpha(alpha, 0.16f)));
			graphics.fillRoundRect(paintRect, round + Math.round(glow), round + Math.round(glow));
		}

		paintRect.set(left, top, right, bottom);
		graphics.setFillColor(colorWithAlpha(active ? activeFace : face,
				scaledAlpha(alpha, active ? 0.96f : 0.91f)));
		graphics.fillRoundRect(paintRect, round, round);
		graphics.setDrawColor(colorWithAlpha(active ? accent : edge,
				scaledAlpha(alpha, active ? 0.82f : 0.48f)));
		graphics.drawRoundRect(paintRect, round, round);

		float highlightHeight = Math.max(1.0f, radius * 0.012f);
		float inset = Math.max(2.0f, radius * 0.11f);
		paintRect.set(left + inset, top + inset * 0.50f, right - inset,
				Math.min(bottom, top + inset * 0.50f + highlightHeight));
		graphics.setFillColor(colorWithAlpha(lightenRgb(face, 0.42f),
				scaledAlpha(alpha, active ? 0.10f : 0.065f)));
		graphics.fillRoundRect(paintRect, Math.max(1, round / 3), Math.max(1, round / 3));
	}

	private void paintDpadGlyph(
			CanvasWrapper graphics,
			String glyph,
			float x, float y,
			boolean active,
			int icon, int selectedIcon, int accent, int alpha) {
		int activeIcon = blendRgb(selectedIcon, accent, 0.18f);
		graphics.setTextColor(colorWithAlpha(active ? activeIcon : icon,
				scaledAlpha(alpha, active ? 0.98f : 0.90f)));
		graphics.drawString(glyph, x, y);
	}

	private void paintAnalog(CanvasWrapper graphics) {
		VirtualAnalogVisualState visual = analogStick.visualState(viewport);
		float centerX = screenBounds.left + visual.getCenterX();
		float centerY = screenBounds.top + visual.getCenterY();
		float radius = visual.getRadius();
		float rawThumbX = screenBounds.left + visual.getThumbX();
		float rawThumbY = screenBounds.top + visual.getThumbY();
		// Preserve full input range but compress visual travel so the thumb never appears to leave
		// its physical well.
		float thumbX = centerX + (rawThumbX - centerX) * 0.47f;
		float thumbY = centerY + (rawThumbY - centerY) * 0.47f;
		boolean active = visual.getActive();
		boolean selected = editControl == EditControl.ANALOG;
		int alpha = controlAlpha();

		int base = controlBaseColor();
		int accent = controlAccentColor();
		int outline = controlOutlineColor();
		int icon = controlIconColor();
		int shell = darkenRgb(base, 0.34f);
		int face = blendRgb(base, outline, 0.18f);
		int inner = darkenRgb(base, 0.48f);
		int edge = blendRgb(outline, icon, 0.12f);
		int accentSoft = blendRgb(accent, outline, 0.10f);

		if (selected) {
			fillCircle(graphics, centerX, centerY, radius * 1.02f,
					colorWithAlpha(accent, scaledAlpha(alpha, 0.08f)));
		}

		// One clear chassis, one accent ring and one inner bowl: fewer competing circles than the
		// previous renderer, while retaining enough depth to read over light or dark games.
		fillCircle(graphics, centerX, centerY, radius * 0.93f,
				colorWithAlpha(shell, scaledAlpha(alpha, 0.84f)));
		drawCircle(graphics, centerX, centerY, radius * 0.93f,
				colorWithAlpha(edge, scaledAlpha(alpha, 0.44f)));

		fillCircle(graphics, centerX, centerY, radius * 0.73f,
				colorWithAlpha(accentSoft, scaledAlpha(alpha, active ? 0.72f : 0.43f)));
		fillCircle(graphics, centerX, centerY, radius * 0.67f,
				colorWithAlpha(shell, scaledAlpha(alpha, 0.96f)));
		fillCircle(graphics, centerX, centerY, radius * 0.57f,
				colorWithAlpha(inner, scaledAlpha(alpha, 0.91f)));
		drawCircle(graphics, centerX, centerY, radius * 0.57f,
				colorWithAlpha(edge, scaledAlpha(alpha, 0.20f)));

		paintAnalogTicks(graphics, centerX, centerY, radius, accent, alpha);

		float thumbRadius = radius * 0.395f;
		fillCircle(graphics, thumbX, thumbY + radius * 0.035f, thumbRadius * 1.02f,
				colorWithAlpha(darkenRgb(face, 0.78f), scaledAlpha(alpha, 0.38f)));
		fillCircle(graphics, thumbX, thumbY, thumbRadius * 1.075f,
				colorWithAlpha(accentSoft, scaledAlpha(alpha, active ? 0.23f : 0.09f)));
		fillCircle(graphics, thumbX, thumbY, thumbRadius,
				colorWithAlpha(face, scaledAlpha(alpha, 0.97f)));
		fillCircle(graphics, thumbX, thumbY, thumbRadius * 0.82f,
				colorWithAlpha(lightenRgb(face, 0.065f), scaledAlpha(alpha, 0.98f)));
		drawCircle(graphics, thumbX, thumbY, thumbRadius,
				colorWithAlpha(edge, scaledAlpha(alpha, 0.34f)));
		fillCircle(graphics,
				thumbX - thumbRadius * 0.16f,
				thumbY - thumbRadius * 0.18f,
				thumbRadius * 0.23f,
				colorWithAlpha(lightenRgb(face, 0.60f), scaledAlpha(alpha, 0.08f)));
	}

	private void paintAnalogTicks(
			CanvasWrapper graphics,
			float cx, float cy, float radius,
			int accent, int alpha) {
		float offset = radius * 0.83f;
		float length = Math.max(3.0f, radius * 0.082f);
		float thickness = Math.max(1.2f, radius * 0.014f);
		graphics.setFillColor(colorWithAlpha(accent, scaledAlpha(alpha, 0.68f)));

		paintRect.set(cx - thickness / 2f, cy - offset - length / 2f,
				cx + thickness / 2f, cy - offset + length / 2f);
		graphics.fillRect(paintRect);
		paintRect.set(cx - thickness / 2f, cy + offset - length / 2f,
				cx + thickness / 2f, cy + offset + length / 2f);
		graphics.fillRect(paintRect);
		paintRect.set(cx - offset - length / 2f, cy - thickness / 2f,
				cx - offset + length / 2f, cy + thickness / 2f);
		graphics.fillRect(paintRect);
		paintRect.set(cx + offset - length / 2f, cy - thickness / 2f,
				cx + offset + length / 2f, cy + thickness / 2f);
		graphics.fillRect(paintRect);
	}

	private void fillCircle(CanvasWrapper graphics, float cx, float cy, float radius, int color) {
		paintRect.set(cx - radius, cy - radius, cx + radius, cy + radius);
		graphics.setFillColor(color);
		graphics.fillArc(paintRect, 0, 360);
	}

	private void drawCircle(CanvasWrapper graphics, float cx, float cy, float radius, int color) {
		paintRect.set(cx - radius, cy - radius, cx + radius, cy + radius);
		graphics.setDrawColor(color);
		graphics.drawArc(paintRect, 0, 360);
	}

	private int controlBaseColor() {
		return settings.vkBgColor & 0x00FFFFFF;
	}

	private int controlAccentColor() {
		return settings.vkBgColorSelected & 0x00FFFFFF;
	}

	private int controlOutlineColor() {
		return settings.vkOutlineColor & 0x00FFFFFF;
	}

	private int controlIconColor() {
		return settings.vkFgColor & 0x00FFFFFF;
	}

	private int controlSelectedIconColor() {
		return settings.vkFgColorSelected & 0x00FFFFFF;
	}

	private static int colorWithAlpha(int rgb, int alpha) {
		return ((alpha & 0xFF) << 24) | (rgb & 0x00FFFFFF);
	}

	private static int scaledAlpha(int alpha, float factor) {
		return Math.max(0, Math.min(0xFF, Math.round(alpha * factor)));
	}

	private static int blendRgb(int first, int second, float secondWeight) {
		float weight = clamp(secondWeight, 0.0f, 1.0f);
		float firstWeight = 1.0f - weight;
		int red = Math.round(((first >> 16) & 0xFF) * firstWeight + ((second >> 16) & 0xFF) * weight);
		int green = Math.round(((first >> 8) & 0xFF) * firstWeight + ((second >> 8) & 0xFF) * weight);
		int blue = Math.round((first & 0xFF) * firstWeight + (second & 0xFF) * weight);
		return (red << 16) | (green << 8) | blue;
	}

	private static int lightenRgb(int rgb, float amount) {
		float weight = clamp(amount, 0.0f, 1.0f);
		int red = Math.round(((rgb >> 16) & 0xFF) + (0xFF - ((rgb >> 16) & 0xFF)) * weight);
		int green = Math.round(((rgb >> 8) & 0xFF) + (0xFF - ((rgb >> 8) & 0xFF)) * weight);
		int blue = Math.round((rgb & 0xFF) + (0xFF - (rgb & 0xFF)) * weight);
		return (red << 16) | (green << 8) | blue;
	}

	private static int darkenRgb(int rgb, float amount) {
		float weight = 1.0f - clamp(amount, 0.0f, 1.0f);
		int red = Math.round(((rgb >> 16) & 0xFF) * weight);
		int green = Math.round(((rgb >> 8) & 0xFF) * weight);
		int blue = Math.round((rgb & 0xFF) * weight);
		return (red << 16) | (green << 8) | blue;
	}

"""

text = text[:start] + new_block + text[end:]
path.write_text(text, encoding="utf-8")
