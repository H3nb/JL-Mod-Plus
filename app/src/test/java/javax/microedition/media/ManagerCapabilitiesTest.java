// SPDX-License-Identifier: Apache-2.0
package javax.microedition.media;

import static org.junit.Assert.*;

import java.util.Arrays;

import org.junit.Test;

public class ManagerCapabilitiesTest {
	@Test public void unsupportedLocatorsAndContentAreNotAdvertised() {
		assertArrayEquals(new String[0], Manager.getSupportedContentTypes("http"));
		assertArrayEquals(new String[0], Manager.getSupportedContentTypes("unknown"));
		assertArrayEquals(new String[0], Manager.getSupportedProtocols("video/unknown"));
		assertFalse(Arrays.asList(Manager.getSupportedProtocols(null)).contains("http"));
	}

	@Test public void interactiveDeviceCapabilitiesDoNotAdvertiseFileOnlyMedia() {
		assertArrayEquals(new String[]{"audio/midi", "audio/x-tone-seq"}, Manager.getSupportedContentTypes("device"));
		assertArrayEquals(new String[]{"device", "file", "resource"}, Manager.getSupportedProtocols("audio/midi"));
		assertArrayEquals(new String[]{"file", "resource"}, Manager.getSupportedProtocols("VIDEO/MP4"));
		for (String type : Manager.getSupportedContentTypes(null)) {
			if ("video/raw".equals(type) || "audio/raw".equals(type)) continue;
			assertTrue(Arrays.asList(Manager.getSupportedContentTypes("file")).contains(type));
			assertTrue(Arrays.asList(Manager.getSupportedContentTypes("resource")).contains(type));
			assertTrue(Arrays.asList(Manager.getSupportedProtocols(type)).contains("file"));
		}
	}

	@Test public void rawCaptureSourcesAreDiscoveredSeparatelyFromRecordedAndSnapshotFormats() {
		assertArrayEquals(new String[]{"video/raw", "audio/raw"}, Manager.getSupportedContentTypes("capture"));
		assertArrayEquals(new String[]{"capture"}, Manager.getSupportedProtocols("VIDEO/RAW"));
		assertArrayEquals(new String[]{"capture"}, Manager.getSupportedProtocols("AUDIO/RAW"));
		assertTrue(Arrays.asList(Manager.getSupportedProtocols(null)).contains("capture"));
		assertTrue(Arrays.asList(Manager.getSupportedContentTypes(null)).contains("video/raw"));
		assertTrue(Arrays.asList(Manager.getSupportedContentTypes(null)).contains("audio/raw"));
		assertFalse(Arrays.asList(Manager.getSupportedContentTypes("file")).contains("video/raw"));
		assertFalse(Arrays.asList(Manager.getSupportedContentTypes("file")).contains("audio/raw"));
		assertFalse(Arrays.asList(Manager.getSupportedContentTypes("resource")).contains("video/raw"));
		assertFalse(Arrays.asList(Manager.getSupportedContentTypes("resource")).contains("audio/raw"));
		assertArrayEquals(new String[0], Manager.getSupportedProtocols("image/jpeg"));
		assertArrayEquals(new String[0], Manager.getSupportedProtocols("image/png"));
	}

	@Test public void protocolAndContentTypeDiscoveryAgreeInBothDirections() {
		for (String protocol : Manager.getSupportedProtocols(null)) {
			for (String type : Manager.getSupportedContentTypes(protocol)) {
				assertTrue(Arrays.asList(Manager.getSupportedContentTypes(null)).contains(type));
				assertTrue(Arrays.asList(Manager.getSupportedProtocols(type)).contains(protocol));
			}
		}
	}
}
