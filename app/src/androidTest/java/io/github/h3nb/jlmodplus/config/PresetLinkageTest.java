/*
 * Modified for JL-Mod Plus.
 *
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

package io.github.h3nb.jlmodplus.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.UUID;

public class PresetLinkageTest {
	private SharedPreferences preferences;
	private File configDir;
	private PresetLinkage linkage;

	@Before
	public void setUp() {
		Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		String id = UUID.randomUUID().toString();
		preferences = context.getSharedPreferences("preset-linkage-test-" + id, Context.MODE_PRIVATE);
		configDir = new File(context.getCacheDir(), "preset-linkage-" + id);
		linkage = new PresetLinkage(preferences, configDir);
	}

	@After
	public void tearDown() {
		preferences.edit().clear().commit();
	}

	@Test
	public void legacyOriginWithoutLinkMarkerIsUnlinked() {
		preferences.edit()
				.putString(PresetLinkage.originPreferenceKey(configDir), "K800i")
				.remove(PresetLinkage.linkedPreferenceKey(configDir))
				.commit();

		assertEquals("K800i", linkage.getOrigin());
		assertFalse(linkage.isLinked());
	}

	@Test
	public void linkingRecordsOriginAndLinkedStateTogether() {
		linkage.linkTo("K800i");

		assertEquals("K800i", linkage.getOrigin());
		assertTrue(linkage.isLinked());
		assertEquals("K800i",
				preferences.getString(PresetLinkage.originPreferenceKey(configDir), null));
		assertTrue(preferences.getBoolean(PresetLinkage.linkedPreferenceKey(configDir), false));
	}

	@Test
	public void detachPreservesOrigin() {
		linkage.linkTo("K800i");

		linkage.detach();

		assertEquals("K800i", linkage.getOrigin());
		assertFalse(linkage.isLinked());
	}

	@Test
	public void provenanceChangeDoesNotKeepLiveLink() {
		linkage.linkTo("K800i");

		linkage.setOrigin("N95");

		assertEquals("N95", linkage.getOrigin());
		assertFalse(linkage.isLinked());
	}

	@Test
	public void clearRemovesOriginAndLinkMarker() {
		linkage.linkTo("K800i");

		linkage.clear();

		assertNull(linkage.getOrigin());
		assertFalse(linkage.isLinked());
		assertFalse(preferences.contains(PresetLinkage.originPreferenceKey(configDir)));
		assertFalse(preferences.contains(PresetLinkage.linkedPreferenceKey(configDir)));
	}
}
