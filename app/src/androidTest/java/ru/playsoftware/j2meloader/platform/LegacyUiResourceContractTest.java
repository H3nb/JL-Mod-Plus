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

package ru.playsoftware.j2meloader.platform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.widget.LinearLayout;
import androidx.compose.ui.platform.ComposeView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import javax.microedition.shell.RuntimeHostView;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Keeps the intentional runtime View boundaries constructible while host chrome is Compose-owned.
 */
@RunWith(AndroidJUnit4.class)
public class LegacyUiResourceContractTest {
	private final Context targetContext =
			InstrumentationRegistry.getInstrumentation().getTargetContext();

	@Test
	public void guestViewBoundariesRemainConstructible() {
		RuntimeHostView micro = new RuntimeHostView(targetContext);
		assertNotNull("Guest overlay must remain a View boundary", micro.overlay);
		assertNotNull("Guest display container must remain available", micro.displayableContainer);
		assertTrue("Runtime host menu must use the Compose-owned Material 3 toolbar",
				micro.toolbar instanceof ComposeView);
		assertEquals("Virtual display and overlay must remain direct root children",
				2, micro.root.getChildCount());
		assertSame(micro.virtualDisplay, micro.root.getChildAt(0));
		assertSame("Overlay must remain above the guest display", micro.overlay,
				micro.root.getChildAt(1));
		assertSame(micro.toolbar, micro.virtualDisplay.getChildAt(0));
		assertSame(micro.displayableContainer, micro.virtualDisplay.getChildAt(1));
		LinearLayout.LayoutParams displayParams =
				(LinearLayout.LayoutParams) micro.displayableContainer.getLayoutParams();
		assertEquals("Display container must retain weighted remaining height", 0,
				displayParams.height);
		assertEquals(1f, displayParams.weight, 0f);
	}
}
