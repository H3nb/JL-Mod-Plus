/*
 * Copyright 2026 JL-Mod Plus contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package javax.microedition.m3g;

import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Runtime characterization for the public JSR-184 surface that crosses the
 * Android/JNI/native M3G boundary. The regular Android CI still compiles the
 * supported ARM64 artifact; this suite executes the same native code on a
 * hosted 64-bit Android Emulator so pointer-width and JNI assumptions are
 * exercised at runtime as well.
 */
@RunWith(AndroidJUnit4.class)
public class M3GRuntimeTest {
	private static final String TAG = "JLModM3GRuntime";
	private static final int SIZE = 64;
	private static final int RGB_MASK = 0x00FFFFFF;
	private static final int BACKGROUND_ARGB = 0xFF17324D;
	private static final float EPSILON = 0.0001f;

	private Graphics3D g3d;

	@Before
	public void setUp() {
		g3d = Graphics3D.getInstance();
		cleanupContext();
	}

	@After
	public void tearDown() {
		cleanupContext();
	}

	@Test
	public void cameraAndLightTransformsCrossJniWithJsr184Semantics() {
		Camera camera = new Camera();
		camera.setPerspective(60.0f, 1.0f, 1.0f, 100.0f);
		Transform cameraTransform = new Transform();
		cameraTransform.postTranslate(1.25f, -2.5f, 3.75f);
		cameraTransform.postRotate(37.0f, 0.0f, 1.0f, 0.0f);

		runtimeStep("camera: set/get transform");
		g3d.setCamera(camera, cameraTransform);
		Transform returnedCameraTransform = new Transform();
		assertSame(camera, g3d.getCamera(returnedCameraTransform));
		assertTransformEquals(cameraTransform, returnedCameraTransform);

		Light light = new Light();
		light.setMode(Light.DIRECTIONAL);
		Transform lightTransform = new Transform();
		lightTransform.postTranslate(-3.0f, 2.0f, 5.0f);
		lightTransform.postRotate(23.0f, 1.0f, 0.0f, 0.0f);

		runtimeStep("light: add/get transform");
		int lightIndex = g3d.addLight(light, lightTransform);
		assertEquals(1, g3d.getLightCount());
		Transform returnedLightTransform = new Transform();
		assertSame(light, g3d.getLight(lightIndex, returnedLightTransform));
		assertDirectionalLightEquivalent(lightTransform, returnedLightTransform);

		Transform replacementTransform = new Transform();
		replacementTransform.postTranslate(0.5f, 1.5f, -2.5f);
		replacementTransform.postRotate(11.0f, 0.0f, 0.0f, 1.0f);

		runtimeStep("light: set/get replacement transform");
		g3d.setLight(lightIndex, light, replacementTransform);
		Transform returnedReplacement = new Transform();
		assertSame(light, g3d.getLight(lightIndex, returnedReplacement));
		assertDirectionalLightEquivalent(replacementTransform, returnedReplacement);
	}

	@Test
	public void translatedGraphicsTargetPreservesViewportAndClearOutput() {
		Image translatedImage = Image.createImage(SIZE, SIZE);
		Graphics translatedGraphics = translatedImage.getGraphics();
		translatedGraphics.translate(5, 7);
		int bindOffsetX = translatedGraphics.getTranslateX();
		int bindOffsetY = translatedGraphics.getTranslateY();
		int initialClipX = translatedGraphics.getClipX();
		int initialClipY = translatedGraphics.getClipY();
		int initialClipWidth = translatedGraphics.getClipWidth();
		int initialClipHeight = translatedGraphics.getClipHeight();
		Background background = createBackground(BACKGROUND_ARGB);

		runtimeStep("graphics target: bind translated target");
		g3d.bindTarget(translatedGraphics, true,
				Graphics3D.TRUE_COLOR | Graphics3D.OVERWRITE);
		try {
			assertSame(translatedGraphics, g3d.getTarget());
			assertEquals(initialClipX, g3d.getViewportX());
			assertEquals(initialClipY, g3d.getViewportY());
			assertEquals(initialClipWidth, g3d.getViewportWidth());
			assertEquals(initialClipHeight, g3d.getViewportHeight());

			g3d.setViewport(2, 3, 10, 10);
			assertEquals(2, g3d.getViewportX());
			assertEquals(3, g3d.getViewportY());
			assertEquals(10, g3d.getViewportWidth());
			assertEquals(10, g3d.getViewportHeight());

			// The Graphics origin is captured by bindTarget. Later translation
			// must not move the M3G viewport or its physical bitmap position.
			translatedGraphics.translate(100, 100);
			assertEquals(2, g3d.getViewportX());
			assertEquals(3, g3d.getViewportY());
			runtimeStep("graphics target: clear translated viewport");
			g3d.clear(background);
		} finally {
			runtimeStep("graphics target: release translated target");
			g3d.releaseTarget();
		}

		int translatedCenterX = bindOffsetX + 2 + 5;
		int translatedCenterY = bindOffsetY + 3 + 5;
		assertEquals(BACKGROUND_ARGB & RGB_MASK,
				readPixel(translatedImage, translatedCenterX, translatedCenterY) & RGB_MASK);
		assertEquals(0x00FFFFFF, readPixel(translatedImage, 0, 0) & RGB_MASK);

		Image clippedImage = Image.createImage(SIZE, SIZE);
		Graphics clippedGraphics = clippedImage.getGraphics();
		clippedGraphics.setClip(8, 8, 12, 12);

		runtimeStep("graphics target: bind clipped target");
		g3d.bindTarget(clippedGraphics, true,
				Graphics3D.TRUE_COLOR | Graphics3D.OVERWRITE);
		try {
			g3d.setViewport(0, 0, 20, 20);
			runtimeStep("graphics target: clear clipped viewport");
			g3d.clear(background);
		} finally {
			runtimeStep("graphics target: release clipped target");
			g3d.releaseTarget();
		}

		assertEquals(BACKGROUND_ARGB & RGB_MASK,
				readPixel(clippedImage, 9, 9) & RGB_MASK);
		assertEquals(0x00FFFFFF, readPixel(clippedImage, 4, 4) & RGB_MASK);
	}

	@Test
	public void immediateRenderingExecutesOn64BitNativeBackend() {
		TriangleFixture triangle = createTriangleFixture();
		Camera camera = new Camera();
		camera.setPerspective(60.0f, 1.0f, 1.0f, 20.0f);
		Background background = createBackground(BACKGROUND_ARGB);
		Image image = Image.createImage(SIZE, SIZE);

		runtimeStep("immediate: bind target");
		g3d.bindTarget(image.getGraphics(), true,
				Graphics3D.TRUE_COLOR | Graphics3D.OVERWRITE);
		try {
			runtimeStep("immediate: set camera");
			g3d.setCamera(camera, null);
			runtimeStep("immediate: clear");
			g3d.clear(background);
			runtimeStep("immediate: render triangle");
			g3d.render(triangle.vertices, triangle.indices,
					triangle.appearance, null);
		} finally {
			runtimeStep("immediate: release target");
			g3d.releaseTarget();
		}

		runtimeStep("immediate: verify rendered pixels");
		assertRenderedCenter(image);
	}

	@Test
	public void retainedMorphingWorldAndPickingExecuteOn64BitNativeBackend() {
		TriangleFixture triangle = createTriangleFixture();
		Camera camera = new Camera();
		camera.setPerspective(60.0f, 1.0f, 1.0f, 20.0f);
		Background background = createBackground(BACKGROUND_ARGB);

		VertexBuffer morphTarget = createVertexBuffer(new short[]{
				-2, -1, -4,
				 2, -1, -4,
				 0,  2, -4
		});
		MorphingMesh morphingMesh = new MorphingMesh(
				triangle.vertices,
				new VertexBuffer[]{morphTarget},
				triangle.indices,
				triangle.appearance);
		morphingMesh.setWeights(new float[]{0.25f});
		float[] returnedWeights = new float[1];
		morphingMesh.getWeights(returnedWeights);
		assertEquals(0.25f, returnedWeights[0], EPSILON);
		assertEquals(1, morphingMesh.getMorphTargetCount());
		assertSame(morphTarget, morphingMesh.getMorphTarget(0));

		World world = new World();
		world.setBackground(background);
		world.addChild(camera);
		world.addChild(morphingMesh);
		world.setActiveCamera(camera);

		runtimeStep("retained: pick world");
		assertTrue(world.pick(-1,
				0.0f, 0.0f, 0.0f,
				0.0f, 0.0f, -1.0f,
				null));

		Image image = Image.createImage(SIZE, SIZE);
		runtimeStep("retained: bind target");
		g3d.bindTarget(image.getGraphics(), true,
				Graphics3D.TRUE_COLOR | Graphics3D.OVERWRITE);
		try {
			runtimeStep("retained: render world");
			g3d.render(world);
		} finally {
			runtimeStep("retained: release target");
			g3d.releaseTarget();
		}

		runtimeStep("retained: verify rendered pixels");
		assertRenderedCenter(image);
	}

	@Test
	public void objectReferenceArraysAndDuplicateRoundTripThroughJni() {
		Group root = new Group();
		Group child = new Group();
		Object rootMarker = new Object();
		Object childMarker = new Object();
		root.setUserObject(rootMarker);
		child.setUserObject(childMarker);
		root.addChild(child);

		runtimeStep("object3d: count references");
		int referenceCount = root.getReferences(null);
		assertTrue(referenceCount > 0);

		Object3D[] references = new Object3D[referenceCount];
		runtimeStep("object3d: populate references");
		assertEquals(referenceCount, root.getReferences(references));
		assertContainsSame(child, references);

		runtimeStep("object3d: duplicate subtree");
		Group duplicate = (Group) root.duplicate();
		assertNotSame(root, duplicate);
		assertSame(rootMarker, duplicate.getUserObject());
		assertEquals(1, duplicate.getChildCount());

		Node duplicateChild = duplicate.getChild(0);
		assertNotSame(child, duplicateChild);
		assertSame(childMarker, duplicateChild.getUserObject());
	}

	@Test
	public void mutableImage2DTargetBindsClearsAndReleases() {
		Background background = createBackground(0xFF224466);

		for (int format : new int[]{Image2D.RGB, Image2D.RGBA}) {
			Image2D target = new Image2D(format, 32, 32);
			assertTrue(target.isMutable());
			assertEquals(format, target.getFormat());

			runtimeStep("image2d: bind " + format + " target");
			g3d.bindTarget(target, true, Graphics3D.TRUE_COLOR);
			try {
				assertSame(target, g3d.getTarget());
				g3d.setViewport(1, 2, 24, 23);
				assertEquals(1, g3d.getViewportX());
				assertEquals(2, g3d.getViewportY());
				runtimeStep("image2d: clear " + format + " target");
				g3d.clear(background);
			} finally {
				runtimeStep("image2d: release " + format + " target");
				g3d.releaseTarget();
			}
			assertEquals(null, g3d.getTarget());
		}
	}

	@Test
	public void rejectsImmutableAndUnsupportedImage2DTargets() {
		Image2D immutable = new Image2D(Image2D.RGB, 1, 1, new byte[]{0, 0, 0});
		assertFalse(immutable.isMutable());
		assertIllegalImage2DTarget(immutable);

		Image2D unsupportedFormat = new Image2D(Image2D.LUMINANCE, 1, 1);
		assertTrue(unsupportedFormat.isMutable());
		assertIllegalImage2DTarget(unsupportedFormat);
	}

	private void assertIllegalImage2DTarget(Image2D target) {
		try {
			g3d.bindTarget(target, true, Graphics3D.TRUE_COLOR);
			fail("Expected Image2D target rejection");
		} catch (IllegalArgumentException expected) {
			assertEquals(null, g3d.getTarget());
		}
	}

	private void cleanupContext() {
		if (g3d == null) {
			return;
		}
		if (g3d.getTarget() != null) {
			g3d.releaseTarget();
		}
		g3d.resetLights();
		g3d.setCamera(null, null);
	}

	private static TriangleFixture createTriangleFixture() {
		VertexBuffer vertices = createVertexBuffer(new short[]{
				-2, -2, -4,
				 2, -2, -4,
				 0,  2, -4
		});
		TriangleStripArray indices = new TriangleStripArray(0, new int[]{3});
		Appearance appearance = new Appearance();
		PolygonMode polygonMode = new PolygonMode();
		polygonMode.setCulling(PolygonMode.CULL_NONE);
		appearance.setPolygonMode(polygonMode);
		return new TriangleFixture(vertices, indices, appearance);
	}

	private static VertexBuffer createVertexBuffer(short[] positionValues) {
		VertexArray positions = new VertexArray(3, 3, 2);
		positions.set(0, 3, positionValues);

		VertexArray colors = new VertexArray(3, 4, 1);
		colors.set(0, 3, new byte[]{
				(byte) 0xFF, 0, 0, (byte) 0xFF,
				(byte) 0xFF, 0, 0, (byte) 0xFF,
				(byte) 0xFF, 0, 0, (byte) 0xFF
		});

		VertexBuffer vertices = new VertexBuffer();
		vertices.setPositions(positions, 1.0f, null);
		vertices.setColors(colors);
		return vertices;
	}

	private static Background createBackground(int argb) {
		Background background = new Background();
		background.setColor(argb);
		background.setColorClearEnable(true);
		background.setDepthClearEnable(true);
		return background;
	}

	private static void assertTransformEquals(Transform expected, Transform actual) {
		float[] expectedValues = new float[16];
		float[] actualValues = new float[16];
		expected.get(expectedValues);
		actual.get(actualValues);
		assertArrayEquals(expectedValues, actualValues, EPSILON);
	}

	private static void assertDirectionalLightEquivalent(
			Transform expected, Transform actual) {
		float[] expectedDirection = {0.0f, 0.0f, -1.0f, 0.0f};
		float[] actualDirection = {0.0f, 0.0f, -1.0f, 0.0f};
		expected.transform(expectedDirection);
		actual.transform(actualDirection);
		normalizeDirection(expectedDirection);
		normalizeDirection(actualDirection);
		assertArrayEquals(expectedDirection, actualDirection, EPSILON);
	}

	private static void normalizeDirection(float[] direction) {
		float length = (float) Math.sqrt(
				direction[0] * direction[0]
						+ direction[1] * direction[1]
						+ direction[2] * direction[2]);
		assertTrue(length > 0.0f);
		direction[0] /= length;
		direction[1] /= length;
		direction[2] /= length;
		direction[3] = 0.0f;
	}

	private static void assertContainsSame(Object3D expected, Object3D[] actual) {
		for (Object3D candidate : actual) {
			if (candidate == expected) {
				return;
			}
		}
		throw new AssertionError("Expected Object3D reference was not returned");
	}

	private static void assertRenderedCenter(Image image) {
		int center = readPixel(image, SIZE / 2, SIZE / 2) & RGB_MASK;
		int corner = readPixel(image, 1, 1) & RGB_MASK;
		assertEquals(BACKGROUND_ARGB & RGB_MASK, corner);
		assertNotEquals(BACKGROUND_ARGB & RGB_MASK, center);
	}

	private static int readPixel(Image image, int x, int y) {
		int[] pixel = new int[1];
		image.getRGB(pixel, 0, 1, x, y, 1, 1);
		return pixel[0];
	}

	private static void runtimeStep(String step) {
		Log.i(TAG, step);
	}

	private static final class TriangleFixture {
		final VertexBuffer vertices;
		final TriangleStripArray indices;
		final Appearance appearance;

		TriangleFixture(VertexBuffer vertices,
						TriangleStripArray indices,
						Appearance appearance) {
			this.vertices = vertices;
			this.indices = indices;
			this.appearance = appearance;
		}
	}
}
