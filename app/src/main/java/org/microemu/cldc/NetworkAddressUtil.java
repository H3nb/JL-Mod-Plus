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

package org.microemu.cldc;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.SocketException;
import java.util.Enumeration;

/** Numeric-address formatting and wildcard server-address discovery for GCF sockets. */
public final class NetworkAddressUtil {
	private NetworkAddressUtil() {
	}

	public static String format(InetAddress address) {
		if (address == null) {
			return null;
		}
		String value = address.getHostAddress();
		if (value != null && value.indexOf(':') >= 0 && !value.startsWith("[")) {
			return "[" + value + "]";
		}
		return value;
	}

	public static String getServerLocalAddress(ServerSocket serverSocket) throws IOException {
		InetAddress bound = serverSocket.getInetAddress();
		if (bound != null && !bound.isAnyLocalAddress()) {
			return format(bound);
		}

		try {
			InetAddress candidate = findBestExternalAddress();
			if (candidate != null) {
				return format(candidate);
			}
		} catch (SocketException ignored) {
			// Interface inspection is best-effort; getLocalAddress() should not fail
			// solely because one Android network interface changed concurrently.
		}

		// There is no externally usable address when the device has no active
		// interface. Loopback is a safer deterministic fallback than 0.0.0.0/::.
		return format(InetAddress.getLoopbackAddress());
	}

	static InetAddress findBestExternalAddress() throws SocketException {
		Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
		InetAddress best = null;
		int bestScore = Integer.MAX_VALUE;
		while (interfaces != null && interfaces.hasMoreElements()) {
			NetworkInterface networkInterface = interfaces.nextElement();
			if (!networkInterface.isUp() || networkInterface.isLoopback()) {
				continue;
			}
			Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
			while (addresses.hasMoreElements()) {
				InetAddress address = addresses.nextElement();
				int score = score(address);
				if (score < bestScore) {
					best = address;
					bestScore = score;
				}
			}
		}
		return best;
	}

	static int score(InetAddress address) {
		if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress()
				|| address.isMulticastAddress()) {
			return Integer.MAX_VALUE;
		}
		if (address instanceof Inet4Address) {
			return address.isSiteLocalAddress() ? 0 : 1;
		}
		if (address instanceof Inet6Address) {
			if (address.isSiteLocalAddress()) {
				return 2;
			}
			return address.isLinkLocalAddress() ? 4 : 3;
		}
		return 5;
	}
}
