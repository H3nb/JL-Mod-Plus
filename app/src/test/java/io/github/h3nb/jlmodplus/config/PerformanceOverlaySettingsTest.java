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

package io.github.h3nb.jlmodplus.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;

import org.junit.Test;

import java.nio.file.Files;

public class PerformanceOverlaySettingsTest {
	private final Gson gson = new Gson();

	@Test
	public void legacyShowFpsUsesStandardWithoutChangingEnablement() {
		ProfileModel legacy = gson.fromJson("{\"ShowFps\":true}", ProfileModel.class);
		ConfigFormState draft = ConfigFormState.fromProfile(legacy, "");
		ProfileModel saved = gson.fromJson(gson.toJson(draft.applyTo(legacy)), ProfileModel.class);

		assertTrue(saved.showFps);
		assertEquals(PerformanceOverlayOptions.STANDARD, saved.performanceOverlayMetrics);
		assertEquals(PerformanceOverlayOptions.TOP_LEFT, saved.performanceOverlayPosition);
		assertFalse(gson.fromJson("{}", ProfileModel.class).showFps);
	}

	@Test
	public void customAndEmptySelectionsSurviveDraftEditingAndPersistence() throws Exception {
		ProfileModel profile = new ProfileModel();
		profile.dir = Files.createTempDirectory("jlmod-perf-overlay").toFile();
		profile.dir.deleteOnExit();
		profile.version = ProfileModel.VERSION;
		int custom = PerformanceOverlayOptions.CPU | PerformanceOverlayOptions.BATTERY_TEMP;
		ConfigFormState draft = ConfigFormState.fromProfile(profile, "")
				.toBuilder().performanceOverlayMetrics(custom)
				.performanceOverlayPosition(PerformanceOverlayOptions.BOTTOM_RIGHT)
				.showFps(false).build();
		assertTrue(ProfilesManager.saveConfig(draft.applyTo(profile)));
		ProfileModel saved = ProfilesManager.loadConfig(profile.dir);
		ConfigFormState reloaded = ConfigFormState.fromProfile(saved, "").toBuilder()
				.fpsLimit("60").build();
		assertEquals(custom, reloaded.performanceOverlayMetrics);
		assertEquals(PerformanceOverlayOptions.BOTTOM_RIGHT, reloaded.performanceOverlayPosition);
		assertFalse(reloaded.showFps);

		ProfileModel empty = reloaded.toBuilder().showFps(true).performanceOverlayMetrics(0)
				.build().applyTo(saved);
		assertTrue(ProfilesManager.saveConfig(empty));
		ProfileModel emptyReloaded = ProfilesManager.loadConfig(profile.dir);
		assertTrue(emptyReloaded.showFps);
		assertEquals(0, ConfigFormState.fromProfile(emptyReloaded, "").performanceOverlayMetrics);
	}

	@Test
	public void overlayEditsAreEffectivePresetChangesAndRestoringIsANoOp() {
		ProfileModel profile = new ProfileModel();
		ProfileModel baseline = ProfileConfigMatcher.copyConfig(profile);
		ConfigFormState draft = ConfigFormState.fromProfile(profile, "").toBuilder()
				.performanceOverlayMetrics(PerformanceOverlayOptions.MINIMAL).build();
		assertFalse(ProfileConfigMatcher.sameEffectiveConfig(profile, draft, baseline));
		draft = draft.toBuilder().performanceOverlayMetrics(PerformanceOverlayOptions.STANDARD)
				.performanceOverlayPosition(PerformanceOverlayOptions.BOTTOM_LEFT).build();
		assertFalse(ProfileConfigMatcher.sameEffectiveConfig(profile, draft, baseline));
		draft = draft.toBuilder().performanceOverlayPosition(PerformanceOverlayOptions.TOP_LEFT).build();
		assertTrue(ProfileConfigMatcher.sameEffectiveConfig(profile, draft, baseline));
	}

	@Test
	public void frameMetricsAreRequiredOnlyForFrameTrafficSelections() {
		assertFalse(PerformanceOverlayOptions.requiresFrameMetrics(0));
		assertFalse(PerformanceOverlayOptions.requiresFrameMetrics(
				PerformanceOverlayOptions.CAP
						| PerformanceOverlayOptions.SPEED
						| PerformanceOverlayOptions.CPU
						| PerformanceOverlayOptions.RAM
						| PerformanceOverlayOptions.FRAME_INTERVAL
						| PerformanceOverlayOptions.P95_INTERVAL
						| PerformanceOverlayOptions.MAX_INTERVAL
						| PerformanceOverlayOptions.PAINT
						| PerformanceOverlayOptions.COPY
						| PerformanceOverlayOptions.SUBMIT
						| PerformanceOverlayOptions.INPUT_QUEUE
						| PerformanceOverlayOptions.FRAME_QUEUE));
		assertTrue(PerformanceOverlayOptions.requiresFrameMetrics(
				PerformanceOverlayOptions.FPS));
		assertTrue(PerformanceOverlayOptions.requiresFrameMetrics(
				PerformanceOverlayOptions.RENDER_FPS));
		assertTrue(PerformanceOverlayOptions.requiresFrameMetrics(
				PerformanceOverlayOptions.COALESCED));
	}

	@Test
	public void rendererMetricsAreRequiredOnlyForRenderConsumptionSelections() {
		assertFalse(PerformanceOverlayOptions.requiresRendererMetrics(0));
		assertFalse(PerformanceOverlayOptions.requiresRendererMetrics(
				PerformanceOverlayOptions.FPS
						| PerformanceOverlayOptions.CPU
						| PerformanceOverlayOptions.RAM
						| PerformanceOverlayOptions.SUBMIT
						| PerformanceOverlayOptions.FRAME_QUEUE));
		assertTrue(PerformanceOverlayOptions.requiresRendererMetrics(
				PerformanceOverlayOptions.RENDER_FPS));
		assertTrue(PerformanceOverlayOptions.requiresRendererMetrics(
				PerformanceOverlayOptions.COALESCED));
		assertTrue(PerformanceOverlayOptions.requiresRendererMetrics(
				PerformanceOverlayOptions.FPS | PerformanceOverlayOptions.COALESCED));
	}

	@Test
	public void malformedPersistedValuesAreSanitizedAtTheFormBoundary() {
		ProfileModel profile = gson.fromJson(
				"{\"PerformanceOverlayMetrics\":1073741825,\"PerformanceOverlayPosition\":99}",
				ProfileModel.class);
		ConfigFormState draft = ConfigFormState.fromProfile(profile, "");
		assertEquals(PerformanceOverlayOptions.FPS, draft.performanceOverlayMetrics);
		assertEquals(PerformanceOverlayOptions.TOP_LEFT, draft.performanceOverlayPosition);
		ProfileModel saved = draft.applyTo(profile);
		assertEquals(PerformanceOverlayOptions.FPS, saved.performanceOverlayMetrics);
		assertEquals(PerformanceOverlayOptions.TOP_LEFT, saved.performanceOverlayPosition);
	}
}
