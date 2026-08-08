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

package ru.woesss.j2me.mmapi.synth;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class SynthPluginFactoryTest {
	@Test
	public void selectsEasForDlsAndSf2ByDefault() {
		assertEquals(SynthPluginFactory.Backend.EAS,
				SynthPluginFactory.backendFor(SoundBankResolver.Format.DLS, false));
		assertEquals(SynthPluginFactory.Backend.EAS,
				SynthPluginFactory.backendFor(SoundBankResolver.Format.SF2, false));
	}

	@Test
	public void debugTsfOverrideAffectsSf2Only() {
		assertEquals(SynthPluginFactory.Backend.TSF,
				SynthPluginFactory.backendFor(SoundBankResolver.Format.SF2, true));
		assertEquals(SynthPluginFactory.Backend.EAS,
				SynthPluginFactory.backendFor(SoundBankResolver.Format.DLS, true));
		assertEquals(SynthPluginFactory.Backend.BUILTIN,
				SynthPluginFactory.backendFor(null, true));
	}

	@Test
	public void keepsTsfOnlyAsTemporarySf2Fallback() {
		assertNull(SynthPluginFactory.fallbackBackendFor(SoundBankResolver.Format.DLS));
		assertEquals(SynthPluginFactory.Backend.TSF,
				SynthPluginFactory.fallbackBackendFor(SoundBankResolver.Format.SF2));
		assertNull(SynthPluginFactory.fallbackBackendFor(null));
	}

	@Test
	public void missingFormatMeansBuiltInBackend() {
		assertEquals(SynthPluginFactory.Backend.BUILTIN,
				SynthPluginFactory.backendFor(null, false));
	}
}
