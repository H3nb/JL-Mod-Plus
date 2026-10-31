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

package javax.microedition.shell.transform;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

/**
 * Universal compatibility marker for an installed MIDlet's transformed bytecode.
 *
 * <p>Increment {@link #TRANSFORM_VERSION} whenever the converter changes emitted bytecode or a
 * runtime bridge contract used by that bytecode. Installed MIDlets with an older or missing marker
 * are then rebuilt by the normal app reconversion path when their retained source is available.</p>
 */
public final class MidletTransformMetadata {
	/** Version 2 also publishes managed-memory static-initializer tails. */
	public static final int TRANSFORM_VERSION = 2;
	public static final String TRANSFORM_VERSION_ATTRIBUTE = "JLMod-Transform-Version";
	/** Fingerprint of the published DEX, not the retained source JAR. */
	public static final String DEX_SHA256_ATTRIBUTE = "JLMod-Dex-SHA256";
	private static final String LEGACY_TIMING_TRANSFORM_ATTRIBUTE =
			"JLMod-Timing-Transform-Version";
	private static final String LEGACY_TIMING_BRIDGE_ATTRIBUTE = "JLMod-Timing-Bridge-ABI";

	private MidletTransformMetadata() {
	}

	public static void mark(Map<String, String> attributes) {
		if (attributes == null) {
			throw new NullPointerException("attributes");
		}
		attributes.remove(DEX_SHA256_ATTRIBUTE);
		attributes.remove(LEGACY_TIMING_TRANSFORM_ATTRIBUTE);
		attributes.remove(LEGACY_TIMING_BRIDGE_ATTRIBUTE);
		attributes.put(TRANSFORM_VERSION_ATTRIBUTE, Integer.toString(TRANSFORM_VERSION));
	}

	public static boolean isCompatible(Map<String, String> attributes) {
		return attributes != null
				&& Integer.toString(TRANSFORM_VERSION).equals(
						attributes.get(TRANSFORM_VERSION_ATTRIBUTE));
	}
	/** Binds the installed transform to the payload the converter just produced. */
	public static void recordPayload(Map<String, String> attributes, File dexPayload) throws IOException {
		if (attributes == null) throw new NullPointerException("attributes");
		attributes.put(DEX_SHA256_ATTRIBUTE, sha256(dexPayload));
	}

	/**
	 * Runtime check, deliberately separate from the launcher's reconversion policy.
	 * Existing installations without a digest receive a conservative DEX inspection.
	 */
	public static boolean isRuntimeCompatible(Map<String, String> attributes, File dexPayload)
			throws IOException {
		if (!isCompatible(attributes) || dexPayload == null || !dexPayload.isFile()
				|| dexPayload.length() == 0L) return false;
		String expected = attributes.get(DEX_SHA256_ATTRIBUTE);
		if (expected != null) {
			return expected.length() == 64 && expected.equalsIgnoreCase(sha256(dexPayload));
		}
		return MidletDexInspection.hasNoContradictoryLegacyEvidence(dexPayload);
	}

	private static String sha256(File file) throws IOException {
		final MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException error) {
			throw new IllegalStateException("SHA-256 is required on Android", error);
		}
		try (FileInputStream input = new FileInputStream(file)) {
			byte[] buffer = new byte[8192];
			int bytes;
			while ((bytes = input.read(buffer)) != -1) {
				digest.update(buffer, 0, bytes);
			}
		}
		char[] result = new char[64];
		char[] digits = "0123456789abcdef".toCharArray();
		byte[] hash = digest.digest();
		for (int i = 0; i < hash.length; i++) {
			result[i * 2] = digits[(hash[i] & 0xff) >>> 4];
			result[i * 2 + 1] = digits[hash[i] & 15];
		}
		return new String(result);
	}

}
