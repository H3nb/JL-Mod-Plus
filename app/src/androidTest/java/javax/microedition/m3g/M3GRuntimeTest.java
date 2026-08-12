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

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Runtime characterization for the public JSR-184 surface that crosses the
 * Android/JNI/native M3G boundary. The regular Android CI still compiles the
 * supported ARM64 artifact; this suite executes the same native code on a
 * hosted 64-bit Android Emulator so pointer-width and JNI assumptions are
 * exercised at runtime as well.
 */
@RunWith(AndroidJUnit4.class)
public class M3GRuntimeTest {
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
	public void cameraAndLightTransformsRoundTripThroughJni() {
		Camera camera = new Camera();
		camera.setPerspective(60.0f, 1.0f, 1.0f, 100.0f);
		Transform cameraTransform = new Transform();
		cameraTransform.postTranslate(1.25f, -2.5f, 3.75f);
		cameraTransform.postRotate(37.0f, 0.0f, 1.0f, 0.0f);

		g3d.setCamera(camera, cameraTransform);
		Transform returnedCameraTransform = new Transform();
		assertSame(camera, g3d.getCamera(returnedCameraTransform));
		assertTransformEquals(cameraTransform, returnedCameraTransform);

		Light light = new Light();
		light.setMode(Light.DIRECTIONAL);
		Transform lightTransform = new Transform();
		lightTransform.postTranslate(-3.0f, 2.0f, 5.0f);
		lightTransform.postRotate(23.0f, 1.0f, 0.0f, 0.0f);

		int lightIndex = g3d.addLight(light, lightTransform);
		assertEquals(1, g3d.getLightCount());
		Transform returnedLightTransform = new Transform();
		assertSame(light, g3d.getLight(lightIndex, returnedLightTransform));
		assertTransformEquals(lightTransform, returnedLightTransform);

		Transform replacementTransform = new Transform();
		replacementTransform.postTranslate(0.5f, 1.5f, -2.5f);
		replacementTransform.postRotate(11.0f, 0.0f, 0.0f, 1.0f);
		g3d.setLight(lightIndex, light, replacementTransform);
		Transform returnedReplacement = new Transform();
		assertSame(light, g3d.getLight(lightIndex, returnedReplacement));
		assertTransformEquals(replacementTransform, returnedReplacement);
	}

	@Test
	public void translatedGraphicsTargetPreservesViewportAndClearOutput() {
		Image translatedImage = Image.createImage(SIZE, SIZE);
		Graphics translatedGraphics = translatedImage.getGraphics();
		translatedGraphics.translate(5, 7);

		g3d.bindTarget(translatedGraphics, true,
				Graphics3D.TRUE_COLOR | Graphics3D.OVERWRITE);
		try {
			g3d.setViewport(2, 3, 31, 29);
			assertSame(translatedGraphics, g3d.getTarget());
			assertEquals(2, g3d.getViewportX());
			assertEquals(3, g3d.getViewportY());
			assertEquals(31, g3d.getViewportWidth());
			assertEquals(29, g3d.getViewportHeight());
		} finally {
			g3d.releaseTarget();
		}

		Image clearImage = Image.createImage(SIZE, SIZE);
		Graphics clearGraphics = clearImage.getGraphics();
		Background background = createBackground(BACKGROUND_ARGB);

		g3d.bindTarget(clearGraphics, true,
				Graphics3D.TRUE_COLOR | Graphics3D.OVERWRITE);
		try {
			g3d.clear(background);
		} finally {
			g3d.releaseTarget();
		}

		assertEquals(BACKGROUND_ARGB & RGB_MASK,
				readPixel(clearImage, SIZE / 2, SIZE / 2) & RGB_MASK);
	}

	@Test
	public void immediateAndRetainedRenderingExecuteOn64BitNativeBackend() {
		TriangleFixture triangle = createTriangleFixture();
		Camera camera = new Camera();
		camera.setPerspective(60.0f, 1.0f, 1.0f, 20.0f);
		Background background = createBackground(BACKGROUND_ARGB);

		Image immediateImage = Image.createImage(SIZE, SIZE);
		g3d.bindTarget(immediateImage.getGraphics(), true,
				Graphics3D.TRUE_COLOR | Graphics3D.OVERWRITE);
		try {
			g3d.setCamera(camera, null);
			g3d.clear(background);
			g3d.render(triangle.vertices, triangle.indices,
					triangle.appearance, null);
		} finally {
			g3d.releaseTarget();
		}
		assertRenderedCenter(immediateImage);

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

		assertTrue(world.pick(-1,
				0.0f, 0.0f, 0.0f,
				0.0f, 0.0f, -1.0f,
				null));

		Image retainedImage = Image.createImage(SIZE, SIZE);
		g3d.bindTarget(retainedImage.getGraphics(), true,
				Graphics3D.TRUE_COLOR | Graphics3D.OVERWRITE);
		try {
			g3d.render(world);
		} finally {
			g3d.releaseTarget();
		}
		assertRenderedCenter(retainedImage);
	}

	@Test
	public void mutableImage2DTargetBindsClearsAndReleases() {
		Image2D target = new Image2D(Image2D.RGBA, 32, 32);
		assertTrue(target.isMutable());
		Background background = createBackground(0xFF224466);

		g3d.bindTarget(target, true, Graphics3D.TRUE_COLOR);
		try {
			assertSame(target, g3d.getTarget());
			g3d.setViewport(1, 2, 24, 23);
			assertEquals(1, g3d.getViewportX());
			assertEquals(2, g3d.getViewportY());
			g3d.clear(background);
		} finally {
			g3d.releaseTarget();
		}
		assertEquals(null, g3d.getTarget());
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
