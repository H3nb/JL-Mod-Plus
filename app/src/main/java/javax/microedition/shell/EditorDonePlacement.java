/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package javax.microedition.shell;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure geometry policy for the editor-only Done button. */
public final class EditorDonePlacement {
	private static final float EPS = 0.01f;

	private EditorDonePlacement() {}

	public static final class Box {
		public final float left;
		public final float top;
		public final float right;
		public final float bottom;

		public Box(float left, float top, float right, float bottom) {
			this.left = left;
			this.top = top;
			this.right = Math.max(left, right);
			this.bottom = Math.max(top, bottom);
		}

		public float width() { return right - left; }
		public float height() { return bottom - top; }
		public float centerX() { return (left + right) * 0.5f; }
		public float centerY() { return (top + bottom) * 0.5f; }

		@Override
		public boolean equals(Object other) {
			if (this == other) return true;
			if (!(other instanceof Box box)) return false;
			return Float.compare(left, box.left) == 0 &&
					Float.compare(top, box.top) == 0 &&
					Float.compare(right, box.right) == 0 &&
					Float.compare(bottom, box.bottom) == 0;
		}

		@Override
		public int hashCode() {
			int result = Float.hashCode(left);
			result = 31 * result + Float.hashCode(top);
			result = 31 * result + Float.hashCode(right);
			result = 31 * result + Float.hashCode(bottom);
			return result;
		}
	}

	public static Box place(
			Box usable,
			float desiredWidth,
			float desiredHeight,
			float safetyMargin,
			List<Box> obstacles,
			Box current) {
		if (usable == null || usable.width() <= 0.0f || usable.height() <= 0.0f) return null;
		List<Box> safeObstacles = obstacles == null ? Collections.emptyList() : obstacles;
		float width = Math.min(Math.max(1.0f, desiredWidth), usable.width());
		float height = Math.min(Math.max(1.0f, desiredHeight), usable.height());

		if (current != null) {
			float left = clamp(current.left, usable.left, usable.right - width);
			float top = clamp(current.top, usable.top, usable.bottom - height);
			Box kept = box(left, top, width, height);
			if (Math.abs(current.width() - width) <= EPS &&
					Math.abs(current.height() - height) <= EPS &&
					overlapScore(kept, safeObstacles, safetyMargin) <= EPS) {
				return kept;
			}
		}

		ArrayList<Box> candidates = preferred(usable, width, height, safetyMargin);
		for (Box candidate : candidates) {
			if (overlapScore(candidate, safeObstacles, safetyMargin) <= EPS) return candidate;
		}

		addGridCandidates(candidates, usable, width, height);
		Box best = null;
		float bestOverlap = Float.POSITIVE_INFINITY;
		float bestDistance = Float.POSITIVE_INFINITY;
		for (Box candidate : candidates) {
			float overlap = overlapScore(candidate, safeObstacles, safetyMargin);
			if (overlap <= EPS) return candidate;
			float dx = candidate.centerX() - usable.centerX();
			float dy = candidate.centerY() - usable.centerY();
			float distance = dx * dx + dy * dy;
			if (best == null ||
					overlap < bestOverlap - EPS ||
					(Math.abs(overlap - bestOverlap) <= EPS && distance < bestDistance - EPS) ||
					(Math.abs(overlap - bestOverlap) <= EPS &&
							Math.abs(distance - bestDistance) <= EPS &&
							(candidate.top < best.top - EPS ||
									(Math.abs(candidate.top - best.top) <= EPS &&
											candidate.left < best.left - EPS)))) {
				best = candidate;
				bestOverlap = overlap;
				bestDistance = distance;
			}
		}
		return best;
	}

	private static ArrayList<Box> preferred(Box u, float w, float h, float margin) {
		ArrayList<Box> result = new ArrayList<>(9);
		float edgeX = Math.min(Math.max(0.0f, margin), Math.max(0.0f, (u.width() - w) * 0.5f));
		float edgeY = Math.min(Math.max(0.0f, margin), Math.max(0.0f, (u.height() - h) * 0.5f));
		float left = u.left + edgeX;
		float centerX = u.centerX() - w * 0.5f;
		float right = u.right - edgeX - w;
		float top = u.top + edgeY;
		float centerY = u.centerY() - h * 0.5f;
		float bottom = u.bottom - edgeY - h;

		// Center is the product default. The other eight anchors are only preferred fallbacks.
		result.add(box(centerX, centerY, w, h));
		result.add(box(left, top, w, h));
		result.add(box(centerX, top, w, h));
		result.add(box(right, top, w, h));
		result.add(box(left, centerY, w, h));
		result.add(box(right, centerY, w, h));
		result.add(box(left, bottom, w, h));
		result.add(box(centerX, bottom, w, h));
		result.add(box(right, bottom, w, h));
		return result;
	}

	private static void addGridCandidates(ArrayList<Box> out, Box u, float w, float h) {
		float rangeX = Math.max(0.0f, u.width() - w);
		float rangeY = Math.max(0.0f, u.height() - h);
		int columns = Math.max(2, Math.min(25,
				(int) Math.ceil(rangeX / Math.max(1.0f, w * 0.5f)) + 1));
		int rows = Math.max(2, Math.min(25,
				(int) Math.ceil(rangeY / Math.max(1.0f, h * 0.5f)) + 1));
		for (int row = 0; row < rows; row++) {
			float top = rows == 1 ? u.top : u.top + rangeY * row / (rows - 1);
			for (int column = 0; column < columns; column++) {
				float left = columns == 1 ? u.left : u.left + rangeX * column / (columns - 1);
				out.add(box(left, top, w, h));
			}
		}
	}

	private static float overlapScore(Box candidate, List<Box> obstacles, float margin) {
		float score = 0.0f;
		float safeMargin = Math.max(0.0f, margin);
		for (Box obstacle : obstacles) {
			if (obstacle == null) continue;
			float left = Math.max(candidate.left, obstacle.left - safeMargin);
			float top = Math.max(candidate.top, obstacle.top - safeMargin);
			float right = Math.min(candidate.right, obstacle.right + safeMargin);
			float bottom = Math.min(candidate.bottom, obstacle.bottom + safeMargin);
			if (right > left && bottom > top) score += (right - left) * (bottom - top);
		}
		return score;
	}

	private static Box box(float left, float top, float width, float height) {
		return new Box(left, top, left + width, top + height);
	}

	private static float clamp(float value, float min, float max) {
		return Math.max(min, Math.min(max, value));
	}
}
