package javax.microedition.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FfmpegAudioConversionTest {
	@Test
	public void adpcmCodecsRequirePcmU8Conversion() {
		assertTrue(FfmpegAudioConversion.requiresPcmU8Conversion("adpcm_yamaha"));
		assertTrue(FfmpegAudioConversion.requiresPcmU8Conversion("adpcm_ima_wav"));
		assertFalse(FfmpegAudioConversion.requiresPcmU8Conversion("pcm_u8"));
		assertFalse(FfmpegAudioConversion.requiresPcmU8Conversion("aac"));
	}

	@Test
	public void pcmU8CommandPreservesCurrentConversionContract() {
		assertEquals(
				"-i /tmp/input.mmf -acodec pcm_u8 -ar 16000 -y /tmp/output.wav",
				FfmpegAudioConversion.buildPcmU8Command("/tmp/input.mmf", "/tmp/output.wav")
		);
	}
}
