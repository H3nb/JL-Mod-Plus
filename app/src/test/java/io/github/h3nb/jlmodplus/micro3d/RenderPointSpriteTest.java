/*
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

package io.github.h3nb.jlmodplus.micro3d;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.mascotcapsule.micro3d.v3.Graphics3D;

import org.junit.Test;

public class RenderPointSpriteTest {
	private static final float EPSILON = 0.0001f;

	@Test
	public void localSizeAxisScalePreservesRigidTransforms() {
		float[] identity = affine(
				1, 0, 0,
				0, 1, 0,
				0, 0, 1);
		assertScale(identity, 1, 1);

		identity[9] = 17;
		identity[10] = -23;
		identity[11] = 41;
		assertScale(identity, 1, 1);

		float[] rotationZ90 = affine(
				0, -1, 0,
				1, 0, 0,
				0, 0, 1);
		assertScale(rotationZ90, 1, 1);
	}

	@Test
	public void localSizeAxisScaleTracksScaleAndReflection() {
		assertScale(affine(
				0.25f, 0, 0,
				0, 0.25f, 0,
				0, 0, 0.25f), 0.25f, 0.25f);
		assertScale(affine(
				2, 0, 0,
				0, 2, 0,
				0, 0, 2), 2, 2);
		assertScale(affine(
				-1, 0, 0,
				0, 1, 0,
				0, 0, 1), 1, 1);
	}

	@Test
	public void localSizeAxisScaleHandlesRotationWithNonUniformScale() {
		float[] rotationZ90AfterScale = affine(
				0, -0.5f, 0,
				2, 0, 0,
				0, 0, 1);

		assertScale(rotationZ90AfterScale, 2, 0.5f);
	}

	@Test
	public void pixelSizeDoesNotInheritAffineScale() {
		float dimension = 80;

		assertEquals(20, Render.scalePointSpriteDimension(
				dimension,
				Graphics3D.POINT_SPRITE_LOCAL_SIZE | Graphics3D.POINT_SPRITE_PERSPECTIVE,
				0.25f), EPSILON);
		assertEquals(20, Render.scalePointSpriteDimension(
				dimension,
				Graphics3D.POINT_SPRITE_LOCAL_SIZE | Graphics3D.POINT_SPRITE_NO_PERS,
				0.25f), EPSILON);
		assertEquals(dimension, Render.scalePointSpriteDimension(
				dimension,
				Graphics3D.POINT_SPRITE_PIXEL_SIZE | Graphics3D.POINT_SPRITE_PERSPECTIVE,
				0.25f), EPSILON);
		assertEquals(dimension, Render.scalePointSpriteDimension(
				dimension,
				Graphics3D.POINT_SPRITE_PIXEL_SIZE | Graphics3D.POINT_SPRITE_NO_PERS,
				0.25f), EPSILON);
	}

	@Test
	public void pointSpriteTextureRangePreservesForwardCoordinates() {
		assertTextureRange(10, 20, 10, 19);
		assertTextureRange(10, 11, 10, 10);
	}

	@Test
	public void pointSpriteTextureRangeMirrorsReversedCoordinates() {
		assertTextureRange(20, 10, 19, 10);
		assertTextureRange(11, 10, 10, 10);
	}

	@Test
	public void pointSpriteTextureRangeDoesNotWrapAtZero() {
		assertTextureRange(1, 0, 0, 0);
	}

	@Test
	public void pointSpriteTextureRangePreservesEqualCaseBehavior() {
		assertTextureRange(10, 10, 10, 9);
	}

	@Test
	public void pointSpriteTextureRangePreservesDirectionAndSpan() {
		int[][] ranges = {
				{2, 9},
				{9, 2},
				{0, 1},
				{1, 0},
				{31, 64},
				{64, 31}
		};

		for (int[] range : ranges) {
			int adjustedStart = Render.adjustPointSpriteTextureStart(range[0], range[1]);
			int adjustedEnd = Render.adjustPointSpriteTextureEnd(range[0], range[1]);
			if (range[0] < range[1]) {
				assertTrue(adjustedStart <= adjustedEnd);
			} else {
				assertTrue(adjustedStart >= adjustedEnd);
			}
			assertEquals(Math.abs(range[1] - range[0]) - 1,
					Math.abs(adjustedEnd - adjustedStart));
		}
	}

	private static void assertScale(float[] matrix, float scaleX, float scaleY) {
		assertEquals(scaleX, Render.pointSpriteAxisScale(matrix, 0), EPSILON);
		assertEquals(scaleY, Render.pointSpriteAxisScale(matrix, 3), EPSILON);
	}

	private static void assertTextureRange(int start, int end, int expectedStart, int expectedEnd) {
		assertEquals(expectedStart, Render.adjustPointSpriteTextureStart(start, end));
		assertEquals(expectedEnd, Render.adjustPointSpriteTextureEnd(start, end));
	}

	private static float[] affine(float m00, float m01, float m02,
								  float m10, float m11, float m12,
								  float m20, float m21, float m22) {
		return new float[] {
				m00, m10, m20,
				m01, m11, m21,
				m02, m12, m22,
				0, 0, 0
		};
	}
}
