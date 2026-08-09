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

package ru.woesss.j2me.mmapi.audio;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class MediaRouterTest {
	@Test
	public void contentSignatureWinsOverConflictingMime() {
		assertEquals(MediaRouter.Backend.WAV,
				MediaRouter.route(ContentProbe.Kind.WAV, "audio/midi"));
		assertEquals(MediaRouter.Backend.SYNTH,
				MediaRouter.route(ContentProbe.Kind.MIDI, "audio/wav"));
		assertEquals(MediaRouter.Backend.SYNTH,
				MediaRouter.route(ContentProbe.Kind.SMAF, "audio/midi"));
	}

	@Test
	public void sequencedMediaUsesSynthBackend() {
		assertEquals(MediaRouter.Backend.SYNTH,
				MediaRouter.route(ContentProbe.Kind.MIDI, null));
		assertEquals(MediaRouter.Backend.SYNTH,
				MediaRouter.route(ContentProbe.Kind.XMF, null));
		assertEquals(MediaRouter.Backend.SYNTH,
				MediaRouter.route(ContentProbe.Kind.RMID, null));
		assertEquals(MediaRouter.Backend.SYNTH,
				MediaRouter.route(ContentProbe.Kind.IMELODY, null));
		assertEquals(MediaRouter.Backend.SYNTH,
				MediaRouter.route(ContentProbe.Kind.RTTTL, null));
		assertEquals(MediaRouter.Backend.SYNTH,
				MediaRouter.route(ContentProbe.Kind.NOKIA_OTA, null));
		assertEquals(MediaRouter.Backend.SYNTH,
				MediaRouter.route(ContentProbe.Kind.SMAF, null));
		assertEquals(MediaRouter.Backend.SYNTH,
				MediaRouter.route(ContentProbe.Kind.UNKNOWN, "audio/x-tone-seq"));
	}

	@Test
	public void wavUsesDedicatedDecoderAndCompressedMediaUsesPlatform() {
		assertEquals(MediaRouter.Backend.WAV,
				MediaRouter.route(ContentProbe.Kind.WAV, null));
		assertEquals(MediaRouter.Backend.PLATFORM_AUDIO,
				MediaRouter.route(ContentProbe.Kind.MP3, null));
		assertEquals(MediaRouter.Backend.PLATFORM_AUDIO,
				MediaRouter.route(ContentProbe.Kind.AAC, null));
		assertEquals(MediaRouter.Backend.PLATFORM_AUDIO,
				MediaRouter.route(ContentProbe.Kind.AMR, null));
		assertEquals(MediaRouter.Backend.PLATFORM_AUDIO,
				MediaRouter.route(ContentProbe.Kind.AMR_WB, null));
		assertEquals(MediaRouter.Backend.PLATFORM_AUDIO,
				MediaRouter.route(ContentProbe.Kind.MP4, null));
		assertEquals(MediaRouter.Backend.PLATFORM_AUDIO,
				MediaRouter.route(ContentProbe.Kind.ASF, "audio/midi"));
		assertEquals(MediaRouter.Backend.PLATFORM_AUDIO,
				MediaRouter.route(ContentProbe.Kind.QCP, "audio/midi"));
	}

	@Test
	public void mimeOnlyFallbackIsConservative() {
		assertEquals(MediaRouter.Backend.SYNTH,
				MediaRouter.route(ContentProbe.Kind.UNKNOWN, " Audio/MIDI; charset=binary "));
		assertEquals(MediaRouter.Backend.WAV,
				MediaRouter.route(ContentProbe.Kind.UNKNOWN, "audio/x-wav"));
		assertEquals(MediaRouter.Backend.UNKNOWN,
				MediaRouter.route(ContentProbe.Kind.UNKNOWN, "audio/mmf"));
		assertEquals(MediaRouter.Backend.UNKNOWN,
				MediaRouter.route(ContentProbe.Kind.UNKNOWN, "application/vnd.smaf"));
		assertEquals(MediaRouter.Backend.UNKNOWN,
				MediaRouter.route(ContentProbe.Kind.UNKNOWN, "audio/ogg"));
		assertEquals(MediaRouter.Backend.UNKNOWN,
				MediaRouter.route(ContentProbe.Kind.UNKNOWN, null));
	}
}
