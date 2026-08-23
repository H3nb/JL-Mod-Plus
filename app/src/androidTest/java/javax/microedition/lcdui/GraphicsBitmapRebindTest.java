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

package javax.microedition.lcdui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import android.graphics.Bitmap;
import android.graphics.Canvas;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class GraphicsBitmapRebindTest {
	private static final int RED = 0x00FF0000;
	private static final int BLUE = 0x000000FF;
	private static final int RGB_MASK = 0x00FFFFFF;

	@Test
	public void growthRebindsCachedGraphicsAndPreservesPixels() {
		Image image = Image.createImage(8, 8);
		Graphics graphics = image.getSingleGraphics();
		Canvas canvas = graphics.getCanvas();
		Bitmap oldBitmap = image.getBitmap();

		graphics.setColor(RED);
		graphics.fillRect(0, 0, 8, 8);

		image.setSize(16, 16);

		assertNotSame(oldBitmap, image.getBitmap());
		assertSame(canvas, graphics.getCanvas());
		assertEquals(RED, getPixel(image, 1, 1));

		graphics.setClip(8, 8, 8, 8);
		graphics.setColor(BLUE);
		graphics.fillRect(0, 0, 16, 16);

		assertEquals(BLUE, getPixel(image, 8, 8));
		assertEquals(BLUE, getPixel(image, 15, 15));
		assertEquals(RED, getPixel(image, 1, 1));
	}

	private int getPixel(Image image, int x, int y) {
		return image.getBitmap().getPixel(x, y) & RGB_MASK;
	}
}
