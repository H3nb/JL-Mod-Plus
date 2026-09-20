/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package javax.microedition.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class EditorDonePlacementTest {
	private static final float EPS = 0.01f;

	@Test
	public void portraitEmptyPrefersCenter() {
		EditorDonePlacement.Box p = place(box(0, 0, 360, 800), Collections.emptyList(), null);
		assertEquals(180f, p.centerX(), EPS);
		assertEquals(400f, p.centerY(), EPS);
	}

	@Test
	public void landscapeEmptyPrefersCenter() {
		EditorDonePlacement.Box p = place(box(0, 0, 800, 360), Collections.emptyList(), null);
		assertEquals(400f, p.centerX(), EPS);
		assertEquals(180f, p.centerY(), EPS);
	}

	@Test
	public void occupiedCenterUsesSafeAlternate() {
		EditorDonePlacement.Box usable = box(0, 0, 400, 800);
		EditorDonePlacement.Box center = box(140, 376, 260, 424);
		EditorDonePlacement.Box p = place(usable, List.of(center), null);
		assertNotEquals(center.centerY(), p.centerY(), EPS);
		assertZeroOverlap(p, center);
	}

	@Test
	public void centerAndFourCornersStillUseAnotherPreferredAnchor() {
		EditorDonePlacement.Box usable = box(0, 0, 400, 400);
		List<EditorDonePlacement.Box> obstacles = List.of(
				box(140, 176, 260, 224),
				box(0, 0, 130, 80),
				box(270, 0, 400, 80),
				box(0, 320, 130, 400),
				box(270, 320, 400, 400));
		EditorDonePlacement.Box p = place(usable, obstacles, null);
		assertTrue(p.centerX() < 140f || p.centerX() > 260f ||
				p.centerY() < 150f || p.centerY() > 250f);
	}

	@Test
	public void allPreferredAnchorsOccupiedFallsBackToBroaderSearch() {
		EditorDonePlacement.Box usable = box(0, 0, 500, 500);
		List<EditorDonePlacement.Box> obstacles = new ArrayList<>();
		float[][] centers = {
				{250,250}, {60,40}, {250,40}, {440,40},
				{60,250}, {440,250}, {60,460}, {250,460}, {440,460},
		};
		for (float[] c : centers) obstacles.add(box(c[0]-58,c[1]-34,c[0]+58,c[1]+34));
		EditorDonePlacement.Box p = place(usable, obstacles, null);
		for (EditorDonePlacement.Box obstacle : obstacles) assertZeroOverlap(p, obstacle);
	}

	@Test
	public void denseLayoutUsesDeterministicMinimumOverlap() {
		EditorDonePlacement.Box usable = box(0, 0, 220, 140);
		List<EditorDonePlacement.Box> obstacles = List.of(usable);
		assertEquals(place(usable, obstacles, null), place(usable, obstacles, null));
	}

	@Test
	public void currentSafePositionIsKept() {
		EditorDonePlacement.Box usable = box(0, 0, 400, 800);
		EditorDonePlacement.Box current = box(20, 100, 140, 148);
		assertEquals(current, place(usable, Collections.emptyList(), current));
	}

	@Test
	public void occupiedCurrentPositionRelocates() {
		EditorDonePlacement.Box usable = box(0, 0, 400, 800);
		EditorDonePlacement.Box current = box(20, 100, 140, 148);
		EditorDonePlacement.Box relocated = place(usable, List.of(current), current);
		assertNotEquals(current, relocated);
	}

	@Test
	public void changedOrientationWithoutCurrentRecomputesCenter() {
		EditorDonePlacement.Box landscape = box(0, 0, 800, 360);
		EditorDonePlacement.Box p = place(landscape, Collections.emptyList(), null);
		assertEquals(400f, p.centerX(), EPS);
		assertEquals(180f, p.centerY(), EPS);
	}

	@Test
	public void tinyViewportClampsButtonInsideBounds() {
		EditorDonePlacement.Box usable = box(10, 20, 60, 50);
		EditorDonePlacement.Box p = EditorDonePlacement.place(
				usable, 120, 48, 10, Collections.emptyList(), null);
		assertTrue(p.left >= usable.left - EPS);
		assertTrue(p.top >= usable.top - EPS);
		assertTrue(p.right <= usable.right + EPS);
		assertTrue(p.bottom <= usable.bottom + EPS);
	}

	private static EditorDonePlacement.Box place(
			EditorDonePlacement.Box usable,
			List<EditorDonePlacement.Box> obstacles,
			EditorDonePlacement.Box current) {
		return EditorDonePlacement.place(usable, 120, 48, 10, obstacles, current);
	}

	private static EditorDonePlacement.Box box(float l, float t, float r, float b) {
		return new EditorDonePlacement.Box(l, t, r, b);
	}

	private static void assertZeroOverlap(EditorDonePlacement.Box a, EditorDonePlacement.Box b) {
		float w = Math.min(a.right, b.right) - Math.max(a.left, b.left);
		float h = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
		assertTrue(w <= 0 || h <= 0);
	}
}
