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

// Modified for JL-Mod Plus.

package ru.playsoftware.j2meloader.platform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.view.WindowManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

import javax.microedition.shell.MicroActivity;

import ru.playsoftware.j2meloader.config.ConfigActivity;
import ru.playsoftware.j2meloader.filepicker.FilteredFilePickerActivity;

@RunWith(AndroidJUnit4.class)
public class PlatformCompatManifestTest {
	@Test
	public void imeResizeManifestContractRemainsScopedToHostActivities() throws Exception {
		Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		PackageManager packageManager = context.getPackageManager();

		assertAdjustResize(activityInfo(packageManager, context, ConfigActivity.class));
		assertAdjustResize(activityInfo(packageManager, context, FilteredFilePickerActivity.class));
	}

	@Test
	public void gameCategoryAndGuestOrientationPolicyRemainExplicit() throws Exception {
		Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			assertEquals(ApplicationInfo.CATEGORY_GAME, context.getApplicationInfo().category);
		}
		ActivityInfo info = activityInfo(context.getPackageManager(), context, MicroActivity.class);
		assertEquals("Guest activity must leave orientation policy to the runtime",
				android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, info.screenOrientation);
	}

	@Test
	public void opaqueOctetStreamContentUriResolvesToMainActivity() {
		Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		Intent intent = new Intent(Intent.ACTION_VIEW,
				Uri.parse("content://example.provider/opaque/42"))
				.setType("application/octet-stream")
				.addCategory(Intent.CATEGORY_DEFAULT)
				.addCategory(Intent.CATEGORY_BROWSABLE)
				.setPackage(context.getPackageName());
		List<ResolveInfo> matches = context.getPackageManager()
				.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
		assertTrue("Opaque JAR/KJX content URI must resolve to MainActivity",
				resolvesToMainActivity(matches));
	}

	@Test
	public void uriOnlyHttpJadResolvesToMainActivity() {
		Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		Intent intent = new Intent(Intent.ACTION_VIEW,
				Uri.parse("https://example.test/apps/legacy.jad"))
				.addCategory(Intent.CATEGORY_DEFAULT)
				.addCategory(Intent.CATEGORY_BROWSABLE)
				.setPackage(context.getPackageName());
		List<ResolveInfo> matches = context.getPackageManager()
				.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
		assertTrue("URI-only HTTP JAD must resolve to MainActivity",
				resolvesToMainActivity(matches));
	}

	@Test
	public void uriOnlyContentArchiveResolvesToMainActivity() {
		Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		Intent intent = new Intent(Intent.ACTION_VIEW,
				Uri.parse("content://example.provider/files/game.jar"))
				.addCategory(Intent.CATEGORY_DEFAULT)
				.addCategory(Intent.CATEGORY_BROWSABLE)
				.setPackage(context.getPackageName());
		List<ResolveInfo> matches = context.getPackageManager()
				.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
		assertTrue("URI-only content archive must resolve to MainActivity",
				resolvesToMainActivity(matches));
	}

	@Test
	public void uriOnlyFileArchiveResolvesToMainActivity() {
		Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		Intent intent = new Intent(Intent.ACTION_VIEW,
				Uri.parse("file:///storage/emulated/0/Download/game.kjx"))
				.addCategory(Intent.CATEGORY_DEFAULT)
				.addCategory(Intent.CATEGORY_BROWSABLE)
				.setPackage(context.getPackageName());
		List<ResolveInfo> matches = context.getPackageManager()
				.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
		assertTrue("URI-only file archive must resolve to MainActivity",
				resolvesToMainActivity(matches));
	}

	private static boolean resolvesToMainActivity(List<ResolveInfo> matches) {
		for (ResolveInfo match : matches) {
			if ("ru.playsoftware.j2meloader.MainActivity".equals(match.activityInfo.name)) {
				return true;
			}
		}
		return false;
	}

	private static ActivityInfo activityInfo(PackageManager packageManager, Context context,
			Class<?> activityClass) throws PackageManager.NameNotFoundException {
		return packageManager.getActivityInfo(new ComponentName(context, activityClass), 0);
	}

	private static void assertAdjustResize(ActivityInfo activityInfo) {
		int mode = activityInfo.softInputMode & WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST;
		assertTrue("Activity must resize above the IME", mode == WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
	}
}
