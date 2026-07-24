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

package javax.bluetooth;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class J2MEServiceRecordTest {
	@Test
	public void sppConnectionUrlPreservesRequestedSecurity() {
		UUID uuid = new UUID("12345678123456781234567812345678", false);
		J2MEServiceRecord record = new J2MEServiceRecord(null, uuid, true, false);

		String url = record.getConnectionURL(ServiceRecord.AUTHENTICATE_NOENCRYPT, false);

		assertEquals(
				"btspp://localhost:12345678123456781234567812345678"
						+ ";authenticate=true;encrypt=false;master=false;skipAfterWrite=true",
				url
		);
	}

	@Test
	public void l2capTunnelRecordUsesL2capScheme() {
		UUID uuid = new UUID("1001", false);
		J2MEServiceRecord record = new J2MEServiceRecord(null, uuid, false, true);

		String url = record.getConnectionURL(ServiceRecord.NOAUTHENTICATE_NOENCRYPT, false);

		assertTrue(url.startsWith("btl2cap://localhost:1001"));
	}

	@Test
	public void invalidSecurityValueIsRejected() {
		J2MEServiceRecord record =
				new J2MEServiceRecord(null, new UUID("1101", false), false, false);

		assertThrows(
				IllegalArgumentException.class,
				() -> record.getConnectionURL(99, false)
		);
	}
}
