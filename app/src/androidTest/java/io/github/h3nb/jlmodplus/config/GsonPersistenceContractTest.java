/*
 * Copyright 2026 H3NB
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

import android.util.SparseIntArray;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.junit.Test;

import io.github.h3nb.jlmodplus.util.SparseIntArrayAdapter;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Locks the JSON persistence shape used by profiles, shader metadata, and key
 * mappings while allowing Gson to ignore forward-compatible unknown fields.
 */
public class GsonPersistenceContractTest {

	@Test
	public void profileFixtureKeepsNamedFieldsAndIgnoresUnknownNullFields() {
		String fixture = "{" +
				"\"Version\":2," +
				"\"ScreenWidth\":240," +
				"\"ScreenHeight\":320," +
				"\"ScreenBackgroundImage\":null," +
				"\"ScreenScaleRatio\":-1," +
				"\"FpsLimit\":2147483647," +
				"\"KeyMappings\":{\"19\":21,\"82\":23}," +
				"\"Shader\":{\"name\":\"CRT\",\"author\":\"JL-Mod\",\"fragment\":\"crt.frag\",\"vertex\":\"quad.vert\"}," +
				"\"UnknownFutureField\":{\"enabled\":true}" +
				"}";

		ProfileModel profile = new Gson().fromJson(fixture, ProfileModel.class);

		assertNotNull(profile);
		assertEquals(2, profile.version);
		assertEquals(240, profile.screenWidth);
		assertEquals(320, profile.screenHeight);
		assertEquals(-1, profile.screenScaleRatio);
		assertEquals(Integer.MAX_VALUE, profile.fpsLimit);
		assertNull(profile.screenBackgroundImage);
		assertNotNull(profile.keyMappings);
		assertEquals(21, profile.keyMappings.get(19));
		assertEquals(23, profile.keyMappings.get(82));
		assertNotNull(profile.shader);
		assertEquals("CRT", profile.shader.toString());

		String saved = new GsonBuilder().setPrettyPrinting().create().toJson(profile);
		assertTrue(saved.contains("\n"));
		assertTrue(saved.contains("\"Version\": 2"));
		assertTrue(saved.contains("\"KeyMappings\": {"));
		assertFalse(saved.contains("UnknownFutureField"));
		assertFalse(saved.contains("ScreenBackgroundImage"));

		ProfileModel roundTrip = new Gson().fromJson(saved, ProfileModel.class);
		assertEquals(profile.version, roundTrip.version);
		assertEquals(profile.screenScaleRatio, roundTrip.screenScaleRatio);
		assertEquals(profile.keyMappings.size(), roundTrip.keyMappings.size());
		assertEquals(profile.keyMappings.get(19), roundTrip.keyMappings.get(19));
	}

	@Test
	public void sparseIntArrayAdapterPreservesEdgeValuesAndLegacyStringFixture() {
		Gson gson = new GsonBuilder()
				.registerTypeAdapter(SparseIntArray.class, new SparseIntArrayAdapter())
				.create();
		SparseIntArray original = new SparseIntArray();
		original.put(-1, Integer.MIN_VALUE);
		original.put(Integer.MAX_VALUE, 0);

		String json = gson.toJson(original, SparseIntArray.class);
		assertEquals("{\"-1\":-2147483648,\"2147483647\":0}", json);
		SparseIntArray decoded = gson.fromJson(json, SparseIntArray.class);
		assertEquals(2, decoded.size());
		assertEquals(Integer.MIN_VALUE, decoded.get(-1));
		assertEquals(0, decoded.get(Integer.MAX_VALUE));

		String legacy = "\"[{\\\"key\\\":19,\\\"value\\\":21},{\\\"key\\\":82,\\\"value\\\":23}]\"";
		SparseIntArray legacyDecoded = gson.fromJson(legacy, SparseIntArray.class);
		assertEquals(2, legacyDecoded.size());
		assertEquals(21, legacyDecoded.get(19));
		assertEquals(23, legacyDecoded.get(82));

		assertEquals("null", gson.toJson(null, SparseIntArray.class));
		assertNull(gson.fromJson("null", SparseIntArray.class));
	}

	@Test
	public void shaderFixtureKeepsMetadataAndSettingsValues() {
		String fixture = "{" +
				"\"name\":\"CRT\",\"author\":\"JL-Mod\"," +
				"\"fragment\":\"crt.frag\",\"vertex\":\"quad.vert\"," +
				"\"Settings\":[-1.0,0.0,3.4028235E38]," +
				"\"unknown\":\"ignored\"}";

		ShaderInfo shader = new Gson().fromJson(fixture, ShaderInfo.class);

		assertEquals("CRT", shader.name);
		assertEquals("JL-Mod", shader.author);
		assertEquals("crt.frag", shader.fragment);
		assertEquals("quad.vert", shader.vertex);
		assertArrayEquals(new float[]{-1.0f, 0.0f, 3.4028235E38f}, shader.values, 0.0f);
		String saved = new Gson().toJson(shader);
		assertTrue(saved.contains("\"fragment\":\"crt.frag\""));
		assertTrue(saved.contains("\"Settings\":[-1.0,0.0,3.4028235E38]"));
		assertFalse(saved.contains("unknown"));
	}
}
