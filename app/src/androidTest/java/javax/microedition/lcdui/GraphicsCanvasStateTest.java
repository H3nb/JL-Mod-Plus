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

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class GraphicsCanvasStateTest {
	private static final int WHITE = 0x00FFFFFF;
	private static final int RED = 0x00FF0000;
	private static final int BLUE = 0x000000FF;
	private static final int RGB_MASK = 0x00FFFFFF;

	@Test
	public void resetRestoresTranslationAndRepaintClip() {
		Image image = Image.createImage(20, 20);
		Graphics graphics = image.getGraphics();

		graphics.translate(4, 5);
		graphics.setClip(1, 2, 5, 6);
		graphics.reset(3, 4, 13, 14);

		assertEquals(0, graphics.getTranslateX());
		assertEquals(0, graphics.getTranslateY());
		assertEquals(3, graphics.getClipX());
		assertEquals(4, graphics.getClipY());
		assertEquals(10, graphics.getClipWidth());
		assertEquals(10, graphics.getClipHeight());

		graphics.setColor(RED);
		graphics.fillRect(0, 0, 20, 20);

		assertEquals(WHITE, getPixel(image, 2, 4));
		assertEquals(RED, getPixel(image, 3, 4));
		assertEquals(RED, getPixel(image, 12, 13));
		assertEquals(WHITE, getPixel(image, 13, 14));
	}

	@Test
	public void setClipReplacesClipAfterTranslation() {
		Image image = Image.createImage(20, 20);
		Graphics graphics = image.getGraphics();

		graphics.translate(3, 4);
		graphics.setClip(0, 0, 4, 4);
		graphics.setColor(RED);
		graphics.fillRect(0, 0, 20, 20);

		graphics.setClip(6, 5, 4, 4);
		assertEquals(6, graphics.getClipX());
		assertEquals(5, graphics.getClipY());
		assertEquals(4, graphics.getClipWidth());
		assertEquals(4, graphics.getClipHeight());
		assertEquals(3, graphics.getTranslateX());
		assertEquals(4, graphics.getTranslateY());

		graphics.setColor(BLUE);
		graphics.fillRect(0, 0, 20, 20);

		assertEquals(RED, getPixel(image, 3, 4));
		assertEquals(BLUE, getPixel(image, 9, 9));
		assertEquals(BLUE, getPixel(image, 12, 12));
		assertEquals(WHITE, getPixel(image, 13, 13));
	}

	private int getPixel(Image image, int x, int y) {
		return image.getBitmap().getPixel(x, y) & RGB_MASK;
	}
}
