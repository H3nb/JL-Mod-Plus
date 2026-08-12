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

import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.view.WindowManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Method;

import javax.microedition.shell.MicroActivity;

import ru.playsoftware.j2meloader.config.ConfigActivity;
import ru.playsoftware.j2meloader.filepicker.FilteredFilePickerActivity;

@RunWith(AndroidJUnit4.class)
public class PlatformCompatManifestTest {
	@Test
	public void modernBackAndImeManifestContractsRemainScopedToHostActivities() throws Exception {
		Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		PackageManager packageManager = context.getPackageManager();

		ActivityInfo microActivity = activityInfo(packageManager, context, MicroActivity.class);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			Method callbackEnabled = ActivityInfo.class.getMethod("isOnBackInvokedCallbackEnabled");
			assertTrue("MicroActivity must opt into AndroidX system Back dispatch",
					(Boolean) callbackEnabled.invoke(microActivity));
		}

		assertAdjustResize(activityInfo(packageManager, context, ConfigActivity.class));
		assertAdjustResize(activityInfo(packageManager, context, FilteredFilePickerActivity.class));
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
