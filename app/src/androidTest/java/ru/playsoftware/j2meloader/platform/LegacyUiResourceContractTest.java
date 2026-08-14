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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.android.material.textfield.TextInputLayout;

import org.junit.Test;
import org.junit.runner.RunWith;

import ru.playsoftware.j2meloader.R;

/**
 * Keeps the remaining intentional XML/View boundaries loadable while the host UI is Compose-owned.
 */
@RunWith(AndroidJUnit4.class)
public class LegacyUiResourceContractTest {
	private final Context targetContext =
			InstrumentationRegistry.getInstrumentation().getTargetContext();

	@Test
	public void guestAndSoftKeyLayoutsRemainInflatable() {
		LayoutInflater inflater = LayoutInflater.from(targetContext);
		View micro = inflater.inflate(R.layout.activity_micro, new FrameLayout(targetContext), false);
		assertNotNull("Guest overlay must remain a View boundary", micro.findViewById(R.id.overlay));
		assertNotNull("Guest display container must remain available",
				micro.findViewById(R.id.displayable_container));

		View input = inflater.inflate(R.layout.dialog_input, new FrameLayout(targetContext), false);
		assertTrue("Guest input must retain the Material TextInputLayout contract",
				input instanceof TextInputLayout);
		assertNotNull("Guest input edit field must remain available",
				((TextInputLayout) input).getEditText());

		View softKeys = inflater.inflate(R.layout.soft_button_bar,
				new FrameLayout(targetContext), false);
		assertNotNull("Java ME soft-key bar must remain available",
				softKeys.findViewById(R.id.softBar));
		assertNotNull(softKeys.findViewById(R.id.softLeft));
		assertNotNull(softKeys.findViewById(R.id.softMiddle));
		assertNotNull(softKeys.findViewById(R.id.softRight));
	}

}
