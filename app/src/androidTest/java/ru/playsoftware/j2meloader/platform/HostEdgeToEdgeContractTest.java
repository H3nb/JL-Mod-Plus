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

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.net.Uri;
import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

import ru.playsoftware.j2meloader.config.ConfigActivity;
import ru.playsoftware.j2meloader.config.ProfileModel;
import ru.playsoftware.j2meloader.config.ProfilesManager;
import ru.playsoftware.j2meloader.settings.KeyMapperActivity;
import ru.playsoftware.j2meloader.settings.SettingsActivity;
import ru.playsoftware.j2meloader.util.Constants;

@RunWith(AndroidJUnit4.class)
public class HostEdgeToEdgeContractTest {
	private File fixtureRoot;

	@After
	public void tearDown() {
		deleteRecursively(fixtureRoot);
	}

	@Test
	public void settingsContentAndActionBarStayInsideSafeArea() {
		try (ActivityScenario<SettingsActivity> scenario = ActivityScenario.launch(SettingsActivity.class)) {
			InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			scenario.onActivity(HostEdgeToEdgeContractTest::assertHostUiInsideSafeArea);
		}
	}

	@Test
	public void configMenuAndScrollContentStayInsideSafeArea() {
		Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		fixtureRoot = new File(context.getFilesDir(), "host-edge-to-edge-fixture");
		File appDir = new File(new File(fixtureRoot, "converted"), "fixture");
		assertTrue("Unable to create host inset fixture", appDir.mkdirs() || appDir.isDirectory());
		Intent intent = new Intent(Constants.ACTION_EDIT, Uri.parse(appDir.getAbsolutePath()),
				context, ConfigActivity.class);
		intent.putExtra(Constants.KEY_MIDLET_NAME, "Host Insets Fixture");

		try (ActivityScenario<ConfigActivity> scenario = ActivityScenario.launch(intent)) {
			InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			scenario.onActivity(HostEdgeToEdgeContractTest::assertHostUiInsideSafeArea);
		}
	}

	@Test
	public void keyMapperDoesNotApplyLegacyInsetsTwice() {
		Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		fixtureRoot = new File(context.getFilesDir(), "host-edge-to-edge-keymap-fixture");
		assertTrue("Unable to create key mapper inset fixture",
				fixtureRoot.mkdirs() || fixtureRoot.isDirectory());
		assertTrue("Unable to save key mapper inset fixture",
				ProfilesManager.saveConfig(new ProfileModel(fixtureRoot)));
		Intent intent = new Intent(context, KeyMapperActivity.class)
				.setData(Uri.parse(fixtureRoot.getAbsolutePath()));

		try (ActivityScenario<KeyMapperActivity> scenario = ActivityScenario.launch(intent)) {
			InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			scenario.onActivity(activity -> {
				assertNotNull("Key mapper must host the Compose visual layer",
						activity.findViewById(ru.playsoftware.j2meloader.R.id.key_mapper_compose_root));
				assertHostUiInsideSafeArea(activity);
			});
		}
	}

	private static void assertHostUiInsideSafeArea(Activity activity) {
		View decor = activity.getWindow().getDecorView();
		View content = activity.findViewById(android.R.id.content);
		View actionBarContainer = activity.findViewById(androidx.appcompat.R.id.action_bar_container);
		WindowInsetsCompat windowInsets = ViewCompat.getRootWindowInsets(content);
		assertNotNull("Host window must expose root insets", windowInsets);
		Insets safe = windowInsets.getInsetsIgnoringVisibility(
				WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
		Rect decorBounds = boundsInWindow(decor);
		Rect contentBounds = boundsInWindow(content);
		Rect actionBarBounds = boundsInWindow(actionBarContainer);
		int safeTop = Math.max(decorBounds.top + safe.top, actionBarBounds.bottom);
		int protectedLeft = contentBounds.left + content.getPaddingLeft();
		int protectedTop = contentBounds.top + content.getPaddingTop();
		int protectedRight = contentBounds.right - content.getPaddingRight();
		int protectedBottom = contentBounds.bottom - content.getPaddingBottom();

		assertTrue("Host content must start below the ActionBar and status/cutout inset: protectedTop="
				+ protectedTop + ", safeTop=" + safeTop + ", content=" + contentBounds
				+ ", actionBar=" + actionBarBounds + ", paddingTop=" + content.getPaddingTop(),
				protectedTop >= safeTop);
		assertTrue("Host content must avoid the left display cutout",
				protectedLeft >= decorBounds.left + safe.left);
		assertTrue("Host content must avoid the right display cutout",
				protectedRight <= decorBounds.right - safe.right);
		assertTrue("Host content must avoid the navigation bar",
				protectedBottom <= decorBounds.bottom - safe.bottom);

		assertTrue("ActionBar controls must avoid the left display cutout",
				actionBarBounds.left + actionBarContainer.getPaddingLeft()
						>= decorBounds.left + safe.left);
		assertTrue("ActionBar controls must avoid the right display cutout",
				actionBarBounds.right - actionBarContainer.getPaddingRight()
						<= decorBounds.right - safe.right);
	}

	private static Rect boundsInWindow(View view) {
		int[] location = new int[2];
		view.getLocationInWindow(location);
		return new Rect(location[0], location[1],
				location[0] + view.getWidth(), location[1] + view.getHeight());
	}

	private static void deleteRecursively(File file) {
		if (file == null || !file.exists()) {
			return;
		}
		File[] children = file.listFiles();
		if (children != null) {
			for (File child : children) {
				deleteRecursively(child);
			}
		}
		if (!file.delete() && file.exists()) {
			throw new AssertionError("Unable to delete " + file);
		}
	}
}
