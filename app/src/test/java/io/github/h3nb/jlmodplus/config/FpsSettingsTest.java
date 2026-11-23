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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class FpsSettingsTest {
	private final ProfileFixtures gson = new ProfileFixtures();

	@Test
	public void legacyShowFpsRemainsEnabledWithoutOldMetricSettings() {
		ProfileModel legacy = gson.fromJson(
				"{\"ShowFps\":true,\"PerformanceOverlayMetrics\":0,\"PerformanceOverlayPosition\":3}",
				ProfileModel.class);
		ConfigFormState draft = ConfigFormState.fromProfile(legacy, "");
		ProfileModel saved = gson.fromJson(gson.toJson(draft.applyTo(legacy)), ProfileModel.class);

		assertTrue(saved.showFps);
		assertEquals(PerformanceOverlayOptions.BOTTOM_RIGHT, saved.performanceOverlayPosition);
		assertFalse(gson.toJson(saved).contains("PerformanceOverlayMetrics"));
		assertFalse(gson.fromJson("{}", ProfileModel.class).showFps);
	}

	@Test
	public void positionChangesAreEffectiveOnlyWhenEditedAndInvalidPositionsAreSanitized() {
		ProfileModel profile = new ProfileModel();
		ProfileModel baseline = ProfileConfigMatcher.copyConfig(profile);
		ConfigFormState draft = ConfigFormState.fromProfile(profile, "").toBuilder()
				.performanceOverlayPosition(PerformanceOverlayOptions.BOTTOM_LEFT).build();
		assertFalse(ProfileConfigMatcher.sameEffectiveConfig(profile, draft, baseline));
		assertTrue(ProfileConfigMatcher.sameEffectiveConfig(profile, draft.toBuilder()
				.performanceOverlayPosition(PerformanceOverlayOptions.TOP_LEFT).build(), baseline));

		ProfileModel malformed = gson.fromJson("{\"PerformanceOverlayPosition\":99}", ProfileModel.class);
		assertEquals(PerformanceOverlayOptions.TOP_LEFT,
				ConfigFormState.fromProfile(malformed, "").performanceOverlayPosition);
	}
}
