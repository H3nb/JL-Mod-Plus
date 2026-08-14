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

package ru.playsoftware.j2meloader.filepicker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.io.File;
import java.util.Arrays;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import ru.playsoftware.j2meloader.util.PickDirResultContract;

@RunWith(AndroidJUnit4.class)
public class FilePickerIntentContractTest {
	@Test
	public void directoryContractUsesAppOwnedPickerAndStableResultShape() {
		Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		Intent request = new PickDirResultContract().createIntent(context, "/storage/emulated/0");

		assertEquals(FilteredFilePickerActivity.class.getName(), request.getComponent().getClassName());
		assertEquals(FilePickerContract.MODE_DIR,
				request.getIntExtra(FilePickerContract.EXTRA_MODE, -1));
		assertTrue(request.getBooleanExtra(FilePickerContract.EXTRA_ALLOW_CREATE_DIR, false));
		assertFalse(request.getBooleanExtra(FilePickerContract.EXTRA_ALLOW_MULTIPLE, true));
		assertEquals("/storage/emulated/0",
				request.getStringExtra(FilePickerContract.EXTRA_START_PATH));

		Uri selected = Uri.fromFile(new java.io.File("/storage/emulated/0"));
		Intent result = new Intent().setData(selected);
		assertEquals(selected, new PickDirResultContract().parseResult(Activity.RESULT_OK, result));

		FilePickerRequest pickerRequest = new FilePickerRequest(
				"/storage/emulated/0",
				FilePickerContract.MODE_DIR,
				false,
				false,
				true,
				false);
		Intent pickerResult = FilteredFilePickerActivityKt.createFilePickerResult(
				context,
				pickerRequest,
				Arrays.asList(new File("/storage/emulated/0")));
		assertEquals(selected, pickerResult.getData());
	}

	@Test
	public void multipleResultKeepsRawPathExtrasAndClipDataShape() {
		Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		FilePickerRequest request = new FilePickerRequest(
				"/storage/emulated/0",
				FilePickerContract.MODE_FILE,
				true,
				false,
				false,
				true);
		Intent result = FilteredFilePickerActivityKt.createFilePickerResult(
				context,
				request,
				Arrays.asList(
						new File("/storage/emulated/0/one.jar"),
						new File("/storage/emulated/0/two.jad")));

		assertTrue(result.getBooleanExtra(FilePickerContract.EXTRA_ALLOW_MULTIPLE, false));
		assertNull(result.getData());
		assertEquals(
				Arrays.asList(
						"file:///storage/emulated/0/one.jar",
						"file:///storage/emulated/0/two.jad"),
				result.getStringArrayListExtra(FilePickerContract.EXTRA_PATHS));
		assertEquals(2, result.getClipData().getItemCount());
		assertEquals("Paths", result.getClipData().getDescription().getLabel());
	}
}
