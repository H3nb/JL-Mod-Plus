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

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;
import java.util.Locale;

final class LocalBluetoothAddress {
	private static final String PREFERENCES = "jsr82";
	private static final String ADDRESS_KEY = "local_bluetooth_address";

	private LocalBluetoothAddress() {
	}

	static synchronized String get(Context context) {
		SharedPreferences preferences =
				context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
		String existing = preferences.getString(ADDRESS_KEY, null);
		if (isValid(existing)) {
			return existing;
		}

		byte[] bytes = new byte[6];
		new SecureRandom().nextBytes(bytes);
		bytes[0] = (byte) ((bytes[0] | 0x02) & 0xFE);
		String generated = format(bytes);
		preferences.edit().putString(ADDRESS_KEY, generated).apply();
		return generated;
	}

	static String format(byte[] address) {
		if (address == null || address.length != 6) {
			throw new IllegalArgumentException("Bluetooth address must contain 6 bytes");
		}
		StringBuilder result = new StringBuilder(12);
		for (byte value : address) {
			result.append(String.format(Locale.ROOT, "%02X", value & 0xFF));
		}
		return result.toString();
	}

	static boolean isValid(String address) {
		return address != null && address.matches("[0-9A-F]{12}");
	}
}
