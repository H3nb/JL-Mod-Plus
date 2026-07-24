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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class LocalBluetoothAddressTest {
	@Test
	public void formatProducesJsr82Address() {
		byte[] bytes = {
				(byte) 0x02,
				(byte) 0xAB,
				(byte) 0x00,
				(byte) 0x7F,
				(byte) 0x80,
				(byte) 0xFF
		};

		assertEquals("02AB007F80FF", LocalBluetoothAddress.format(bytes));
	}

	@Test
	public void formatRejectsInvalidLength() {
		assertThrows(
				IllegalArgumentException.class,
				() -> LocalBluetoothAddress.format(new byte[5])
		);
	}

	@Test
	public void validationRequiresTwelveUppercaseHexCharacters() {
		assertTrue(LocalBluetoothAddress.isValid("02AB007F80FF"));
		assertFalse(LocalBluetoothAddress.isValid("02ab007f80ff"));
		assertFalse(LocalBluetoothAddress.isValid("02:AB:00:7F:80:FF"));
		assertFalse(LocalBluetoothAddress.isValid(null));
	}

	@Test
	public void remoteAddressConversionAddsAndroidSeparators() {
		assertEquals(
				"02:AB:00:7F:80:FF",
				RemoteDevice.javaToAndroidAddress("02AB007F80FF")
		);
	}

	@Test
	public void remoteAddressConversionRejectsMalformedValues() {
		assertThrows(
				IllegalArgumentException.class,
				() -> RemoteDevice.javaToAndroidAddress("02:AB:00:7F:80:FF")
		);
	}
}
