package javax.microedition.media;

final class FfmpegAudioConversion {
	private FfmpegAudioConversion() {
	}

	static boolean requiresPcmU8Conversion(String codec) {
		return codec.contains("adpcm");
	}

	static String buildPcmU8Command(String inputPath, String outputPath) {
		return "-i " + inputPath + " -acodec pcm_u8 -ar 16000 -y " + outputPath;
	}
}
