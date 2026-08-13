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

package ru.playsoftware.j2meloader.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

@RunWith(AndroidJUnit4.class)
public class UriImportContractTest {
	@Test
	public void opaqueKjxUsesMagicBeforeMimeOrPath() {
		assertEquals("tmp.kjx", FileUtils.getTempFileName(
				Uri.parse("content://provider/opaque/42"),
				"application/octet-stream", new byte[]{'K', 'J', 'X'}, 3));
	}

	@Test
	public void shortJarUsesSafePrefixDetection() {
		assertEquals("tmp.jar", FileUtils.getTempFileName(
				Uri.parse("content://provider/opaque/42"),
				"application/octet-stream", new byte[]{'P', 'K'}, 2));
	}

	@Test
	public void pathAndMimeClassifyKnownExternalFormats() {
		assertEquals("tmp.jad", FileUtils.getTempFileName(
				Uri.parse("content://provider/tree/opaque/app.JAD"),
				null, new byte[]{'M'}, 1));
		assertEquals("tmp.jar", FileUtils.getTempFileName(
				Uri.parse("content://provider/opaque/42"),
				"application/java-archive", new byte[]{'M'}, 1));
	}

	@Test
	public void relativeJarResolvesAgainstPathBearingContentUri() {
		assertEquals(Uri.parse("content://provider/tree/opaque/app.jar"),
				FileUtils.resolveSiblingUri(
						Uri.parse("content://provider/tree/opaque/app.jad"),
						Uri.parse("app.jar")));
	}

	@Test
	public void rootLevelContentJadCanResolveSiblingJar() {
		assertEquals(Uri.parse("content://provider/app.jar"),
				FileUtils.resolveSiblingUri(
						Uri.parse("content://provider/app.jad"),
						Uri.parse("app.jar")));
	}

	@Test
	public void opaqueContentUriCannotInventSiblingPath() {
		assertNull(FileUtils.resolveSiblingUri(
				Uri.parse("content://provider/opaque-id"), Uri.parse("app.jar")));
	}

	@Test
	public void primaryStoragePathProducesSafTreeUri() {
		File directory = new File(Environment.getExternalStorageDirectory(), "JL-Mod Plus");
		Uri uri = FileUtils.getTreeUriForPath(directory.getPath());
		assertNotNull(uri);
		assertEquals("com.android.externalstorage.documents", uri.getAuthority());
		assertEquals("primary:JL-Mod Plus", DocumentsContract.getTreeDocumentId(uri));
	}

	@Test
	public void nonExternalPathDoesNotPretendToBeSafTree() {
		assertNull(FileUtils.getTreeUriForPath("/data/local/tmp/jl-mod-plus"));
	}
}
