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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public class LoaderResourceSliceTest {

	@Test
	public void bmpSlicesAndScanlineOrientationsProduceTheSamePixels() throws IOException {
		for (boolean topDown : new boolean[] {true, false}) {
			byte[] bmp = bmp3x5(topDown, false);
			byte[] wrapped = withPrefixAndSuffix(bmp, 32, 16);
			assertArrayEquals(pixels(Loader.loadBmpData(bmp, 0, bmp.length)),
					pixels(Loader.loadBmpData(wrapped, 32, bmp.length)));
		}
		byte[] topDown = bmp3x5(true, false);
		byte[] bottomUp = bmp3x5(false, false);
		assertArrayEquals(pixels(Loader.loadBmpData(topDown, 0, topDown.length)),
					pixels(Loader.loadBmpData(bottomUp, 0, bottomUp.length)));
	}

	@Test
	public void environmentTextureRespectsScanlineOrientationAndResourceSlice() throws IOException {
		byte[] topDown = bmp3x5(true, true);
		byte[] bottomUp = bmp3x5(false, true);
		byte[] wrapped = withPrefixAndSuffix(bottomUp, 32, 16);

		byte[] expected = pixels(Loader.loadBmpData(topDown, 0, topDown.length, true));
		assertArrayEquals(expected, pixels(Loader.loadBmpData(bottomUp, 0, bottomUp.length, true)));
		assertArrayEquals(expected, pixels(Loader.loadBmpData(wrapped, 32, bottomUp.length, true)));
	}

	@Test
	public void coreHeaderBmpUsesThreeBytePaletteWithSamePixelOutput() throws IOException {
		byte[] coreBmp = coreBmp3x5(256);
		byte[] windowsBmp = bmp3x5(false, false);
		byte[] wrapped = withPrefixAndSuffix(coreBmp, 32, 16);

		byte[] expected = pixels(Loader.loadBmpData(windowsBmp, 0, windowsBmp.length));
		assertArrayEquals(expected, pixels(Loader.loadBmpData(coreBmp, 0, coreBmp.length)));
		assertArrayEquals(expected, pixels(Loader.loadBmpData(wrapped, 32, coreBmp.length)));
	}

	@Test
	public void coreHeaderBmpAllowsAValidShortRgbTriplePalette() throws IOException {
		byte[] bmp = coreBmp3x5(16);
		byte[] wrapped = withPrefixAndSuffix(bmp, 32, 16);
		byte[] expected = pixels(Loader.loadBmpData(bmp3x5(false, false), 0, bmp3x5(false, false).length));
		assertArrayEquals(expected, pixels(Loader.loadBmpData(bmp, 0, bmp.length)));
		assertArrayEquals(expected, pixels(Loader.loadBmpData(wrapped, 32, bmp.length)));
	}

	@Test
	public void bmpCannotReadIntoPrefixOrSuffixOutsideItsSlice() {
		byte[] bmp = bmp3x5(true, false);
		byte[] wrapped = withPrefixAndSuffix(bmp, 32, 16);
		assertThrows(EOFException.class, () -> Loader.loadBmpData(wrapped, 32, bmp.length - 1));
		assertThrows(EOFException.class, () -> Loader.loadBmpData(wrapped, 32, 20));
		assertThrows(ArrayIndexOutOfBoundsException.class,
				() -> Loader.loadBmpData(wrapped, -1, bmp.length));
		assertThrows(ArrayIndexOutOfBoundsException.class,
				() -> Loader.loadBmpData(wrapped, 32, -1));
		assertThrows(ArrayIndexOutOfBoundsException.class,
				() -> Loader.loadBmpData(wrapped, 32, Integer.MAX_VALUE));
	}

	@Test
	public void bmpRejectsPaletteIndexOutsideDeclaredPalette() {
		byte[] bmp = bmp3x5(true, false);
		ByteBuffer.wrap(bmp).order(ByteOrder.LITTLE_ENDIAN).putInt(46, 2);
		assertThrows(IOException.class, () -> Loader.loadBmpData(bmp, 0, bmp.length));
	}

	@Test
	public void mbacAndMtraCannotConsumeFollowingArrayBytes() {
		byte[] mbac = withPrefixAndSuffix(new byte[] {'M', 'B', 2, 0}, 32, 64);
		byte[] mtra = withPrefixAndSuffix(new byte[] {'M', 'T', 2, 0}, 32, 64);
		assertThrows(EOFException.class, () -> Loader.loadMbacData(mbac, 32, 4));
		assertThrows(EOFException.class, () -> Loader.loadMtraData(mtra, 32, 4));
	}

	@Test
	public void emptyValidMbacAndMtraParseIdenticallyInsideASlice() throws IOException {
		byte[] mbac = new byte[12];
		mbac[0] = 'M';
		mbac[1] = 'B';
		mbac[2] = 2;
		byte[] mtra = new byte[28];
		mtra[0] = 'M';
		mtra[1] = 'T';
		mtra[2] = 2;
		// Version 2 permits zero vertices, polygons, bones and actions.
		byte[] wrappedMbac = withPrefixAndSuffix(mbac, 32, 16);
		byte[] wrappedMtra = withPrefixAndSuffix(mtra, 32, 16);
		assertEquals(Loader.loadMbacData(mbac, 0, mbac.length).numVerticesPolyT,
				Loader.loadMbacData(wrappedMbac, 32, mbac.length).numVerticesPolyT);
		assertEquals(Loader.loadMtraData(mtra, 0, mtra.length).length,
				Loader.loadMtraData(wrappedMtra, 32, mtra.length).length);
	}

	@Test
	public void publicSliceConstructorsRejectNegativeAndOverflowingLengths() {
		byte[] bmp = bmp3x5(true, false);
		assertThrows(ArrayIndexOutOfBoundsException.class, () -> new TextureImpl(bmp, 10, -1));
		assertThrows(ArrayIndexOutOfBoundsException.class, () -> new TextureImpl(bmp, 10, Integer.MAX_VALUE));
		assertThrows(ArrayIndexOutOfBoundsException.class, () -> new FigureImpl(bmp, 10, -1));
		assertThrows(ArrayIndexOutOfBoundsException.class, () -> new ActTableImpl(bmp, 10, Integer.MAX_VALUE));
	}

	@Test
	public void mascotEnvironmentDiscardsIndexZeroRgbAndPadsTo64x64() {
		byte[] bmp = bmp3x5(true, true);
		com.mascotcapsule.micro3d.v3.Texture model =
				new com.mascotcapsule.micro3d.v3.Texture(bmp, true);
		com.mascotcapsule.micro3d.v3.Texture environment =
				new com.mascotcapsule.micro3d.v3.Texture(bmp, false);

		assertEquals(3, model.impl.image.width);
		assertEquals(5, model.impl.image.height);
		assertEquals(64, environment.impl.image.width);
		assertEquals(64, environment.impl.image.height);

		// Palette entry zero is bright red, but must not add red to the environment map.
		assertArrayEquals(new byte[] {(byte) 255, 0, 0, 0},
				Arrays.copyOfRange(pixels(model.impl.image), 0, 4));
		byte[] envPixels = pixels(environment.impl.image);
		assertArrayEquals(new byte[] {0, 0, 0, 0}, Arrays.copyOfRange(envPixels, 0, 4));
		// The second pixel has palette index 2: RGB(2, 2, 2), not index 1.
		assertArrayEquals(new byte[] {2, 2, 2, (byte) 255},
				Arrays.copyOfRange(envPixels, 4, 8));
		assertArrayEquals(new byte[] {0, 0, 0, 0},
				Arrays.copyOfRange(envPixels, (63 * 64 + 63) * 4, 64 * 64 * 4));
	}

	private static byte[] pixels(TextureData texture) {
		ByteBuffer raster = texture.getRaster();
		byte[] result = new byte[raster.remaining()];
		raster.get(result);
		return result;
	}

	private static byte[] withPrefixAndSuffix(byte[] resource, int prefix, int suffix) {
		byte[] data = new byte[prefix + resource.length + suffix];
		Arrays.fill(data, (byte) 0xFC);
		System.arraycopy(resource, 0, data, prefix, resource.length);
		return data;
	}

	private static byte[] coreBmp3x5(int paletteEntries) {
		final int width = 3;
		final int height = 5;
		final int stride = 4;
		final int rasterOffset = 14 + 12 + paletteEntries * 3;
		byte[] bytes = new byte[rasterOffset + height * stride];
		ByteBuffer bmp = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
		bmp.putShort((short) 0x4D42);
		bmp.putInt(bytes.length);
		bmp.putInt(0);
		bmp.putInt(rasterOffset);
		bmp.putInt(12); // BITMAPCOREHEADER
		bmp.putShort((short) width);
		bmp.putShort((short) height);
		bmp.putShort((short) 1);
		bmp.putShort((short) 8);
		for (int index = 0; index < paletteEntries; index++) {
			int value = index == 0 || index == 1 ? 255 : index;
			bmp.put((byte) (index == 0 ? 0 : value));
			bmp.put((byte) (index == 0 ? 0 : value));
			bmp.put((byte) value);
		}
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				bytes[rasterOffset + (height - 1 - y) * stride + x] = (byte) (1 + y * width + x);
			}
		}
		return bytes;
	}

	private static byte[] bmp3x5(boolean topDown, boolean firstPixelTransparent) {
		final int width = 3;
		final int height = 5;
		final int stride = 4;
		final int rasterOffset = 14 + 40 + 256 * 4;
		byte[] bytes = new byte[rasterOffset + height * stride];
		ByteBuffer bmp = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
		bmp.putShort((short) 0x4D42);
		bmp.putInt(bytes.length);
		bmp.putInt(0);
		bmp.putInt(rasterOffset);
		bmp.putInt(40);
		bmp.putInt(width);
		bmp.putInt(topDown ? -height : height);
		bmp.putShort((short) 1);
		bmp.putShort((short) 8);
		bmp.putInt(0);
		bmp.putInt(stride * height);
		bmp.putInt(0);
		bmp.putInt(0);
		bmp.putInt(256);
		bmp.putInt(0);
		for (int index = 0; index < 256; index++) {
			int value = index == 0 ? 255 : index == 1 ? 255 : index;
			bmp.put((byte) (index == 0 ? 0 : value)); // Blue
			bmp.put((byte) (index == 0 ? 0 : value)); // Green
			bmp.put((byte) value); // Red; entry zero is red, entry one is white
			bmp.put((byte) 0);
		}
		for (int y = 0; y < height; y++) {
			int physicalRow = topDown ? y : height - 1 - y;
			for (int x = 0; x < width; x++) {
				int index = 1 + y * width + x;
				if (firstPixelTransparent && x == 0 && y == 0) {
					index = 0;
				}
				bytes[rasterOffset + physicalRow * stride + x] = (byte) index;
			}
		}
		return bytes;
	}
}
