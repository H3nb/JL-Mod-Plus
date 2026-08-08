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

package javax.microedition.media.camera;

import org.junit.Test;

import javax.microedition.media.MediaException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class CaptureLocatorParserTest {
	@Test
	public void parsesDefaultVideoLocator() throws Exception {
		CaptureRequest request = CaptureLocatorParser.parse("capture://video");

		assertEquals("capture://video", request.getLocator());
		assertEquals(CaptureRequest.DEVICE_VIDEO, request.getDevice());
		assertEquals(LogicalCameraDevice.DEFAULT, request.getLogicalCameraDevice());
		assertEquals(CaptureRequest.VIDEO_RECORDING_CONTENT_TYPE, request.getEncoding());
		assertEquals(640, request.getWidth());
		assertEquals(480, request.getHeight());
		assertFalse(request.hasExplicitDimensions());
	}

	@Test
	public void mapsNamedAndCompatibilityDevices() throws Exception {
		CaptureRequest rear = CaptureLocatorParser.parse("capture://devcam0");
		CaptureRequest front = CaptureLocatorParser.parse("capture://devcam1");
		CaptureRequest image = CaptureLocatorParser.parse("capture://image");

		assertEquals(LogicalCameraDevice.REAR, rear.getLogicalCameraDevice());
		assertEquals(LogicalCameraDevice.FRONT, front.getLogicalCameraDevice());
		assertEquals(LogicalCameraDevice.DEFAULT, image.getLogicalCameraDevice());
		assertEquals(CaptureRequest.VIDEO_RECORDING_CONTENT_TYPE, rear.getEncoding());
		assertEquals(CaptureRequest.VIDEO_RECORDING_CONTENT_TYPE, front.getEncoding());
		assertEquals(CaptureRequest.JPEG_ENCODING, image.getEncoding());
	}

	@Test
	public void parsesExplicitJpegDimensions() throws Exception {
		CaptureRequest request = CaptureLocatorParser.parse(
				"capture://video?encoding=jpeg&width=1280&height=960");

		assertEquals(CaptureRequest.JPEG_ENCODING, request.getEncoding());
		assertEquals(1280, request.getWidth());
		assertEquals(960, request.getHeight());
		assertTrue(request.hasExplicitDimensions());
	}

	@Test
	public void acceptsAdvertisedMp4EncodingForVideoRecording() throws Exception {
		CaptureRequest request = CaptureLocatorParser.parse("capture://video?encoding=video%2Fmp4");

		assertEquals(CaptureRequest.VIDEO_RECORDING_CONTENT_TYPE, request.getEncoding());
		assertFalse(request.hasExplicitDimensions());
	}

	@Test
	public void rejectsExplicitMp4RecordingDimensionsUntilTheyCanBeExact() {
		assertThrows(MediaException.class, () -> CaptureLocatorParser.parse(
				"capture://video?encoding=video%2Fmp4&width=640&height=480"));
		assertThrows(MediaException.class, () -> CaptureLocatorParser.parse(
				"capture://video?width=640&height=480"));
		assertThrows(MediaException.class, () -> CaptureLocatorParser.parse(
				"capture://audio_video?width=640&height=480"));
	}

	@Test
	public void decodesEncodedParameterValues() throws Exception {
		CaptureRequest request = CaptureLocatorParser.parse(
				"capture://video?encoding=%6A%70%65%67&width=640&height=480");

		assertEquals(CaptureRequest.JPEG_ENCODING, request.getEncoding());
	}

	@Test
	public void rejectsDuplicateAndUnknownParameters() {
		assertThrows(IllegalArgumentException.class, () ->
				CaptureLocatorParser.parse("capture://video?width=640&width=640&height=480"));
		assertThrows(IllegalArgumentException.class, () ->
				CaptureLocatorParser.parse("capture://video?width=640&WIDTH=640&height=480"));
		assertThrows(IllegalArgumentException.class, () ->
				CaptureLocatorParser.parse("capture://video?quality=90"));
	}

	@Test
	public void rejectsUnsupportedEncodingAndDevice() {
		assertThrows(MediaException.class, () ->
				CaptureLocatorParser.parse("capture://video?encoding=gray8"));
		assertThrows(MediaException.class, () ->
				CaptureLocatorParser.parse("capture://image?encoding=video%2Fmp4"));
		assertThrows(MediaException.class, () ->
				CaptureLocatorParser.parse("capture://devcam9"));
	}

	@Test
	public void parsesCombinedAudioVideoLocator() throws Exception {
		CaptureRequest request = CaptureLocatorParser.parse("capture://audio_video");

		assertEquals(CaptureRequest.DEVICE_AUDIO_VIDEO, request.getDevice());
		assertEquals(CaptureRequest.VIDEO_RECORDING_CONTENT_TYPE, request.getEncoding());
		assertTrue(request.isAudioVideo());
	}

	@Test
	public void canonicalizesLegacyMp4AliasForAudioVideo() throws Exception {
		CaptureRequest request = CaptureLocatorParser.parse("capture://audio_video?encoding=mp4");
		assertEquals(CaptureRequest.VIDEO_RECORDING_CONTENT_TYPE, request.getEncoding());
	}

	@Test
	public void rejectsInvalidDimensionsAndUnenforceableFps() {
		assertThrows(IllegalArgumentException.class, () ->
				CaptureLocatorParser.parse("capture://video?width=0&height=480"));
		assertThrows(IllegalArgumentException.class, () ->
				CaptureLocatorParser.parse("capture://video?width=640"));
		assertThrows(MediaException.class, () ->
				CaptureLocatorParser.parse("capture://video?fps=30"));
		assertThrows(MediaException.class, () ->
				CaptureLocatorParser.parse("capture://video?encoding=jpeg&width=4096&height=2160"));
	}
}
