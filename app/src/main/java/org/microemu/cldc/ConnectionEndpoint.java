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

/**
 * Parses the host/port portion of socket-like Generic Connection Framework URLs.
 * Unsupported trailing vendor parameters are intentionally ignored so legacy
 * MIDlets can keep using transports selected for other Java ME platforms.
 */
public final class ConnectionEndpoint {
	private final String host;
	private final int port;

	private ConnectionEndpoint(String host, int port) {
		this.host = host;
		this.port = port;
	}

	public static ConnectionEndpoint parse(String name, String scheme) {
		if (name == null || scheme == null) {
			throw new IllegalArgumentException("Connection URL is null");
		}

		String prefix = scheme + "://";
		if (!name.startsWith(prefix)) {
			throw new IllegalArgumentException("Invalid " + scheme + " URL");
		}

		String endpoint = name.substring(prefix.length());
		int parameterIndex = endpoint.indexOf(';');
		if (parameterIndex >= 0) {
			endpoint = endpoint.substring(0, parameterIndex);
		}

		if (endpoint.length() == 0) {
			return new ConnectionEndpoint("", -1);
		}

		String host;
		String portText;
		if (endpoint.charAt(0) == '[') {
			int closingBracket = endpoint.indexOf(']');
			if (closingBracket <= 1) {
				throw new IllegalArgumentException("Invalid IPv6 host");
			}
			host = endpoint.substring(1, closingBracket);
			if (closingBracket + 1 == endpoint.length()) {
				portText = "";
			} else {
				if (endpoint.charAt(closingBracket + 1) != ':') {
					throw new IllegalArgumentException("Invalid endpoint");
				}
				portText = endpoint.substring(closingBracket + 2);
			}
		} else {
			int firstColon = endpoint.indexOf(':');
			int lastColon = endpoint.lastIndexOf(':');
			if (firstColon != lastColon) {
				throw new IllegalArgumentException("IPv6 addresses must be enclosed in []");
			}
			if (lastColon < 0) {
				host = endpoint;
				portText = "";
			} else {
				host = endpoint.substring(0, lastColon);
				portText = endpoint.substring(lastColon + 1);
			}
		}

		int port = -1;
		if (portText.length() > 0) {
			try {
				port = Integer.parseInt(portText);
			} catch (NumberFormatException ex) {
				throw new IllegalArgumentException("Invalid port: " + portText);
			}
			if (port < 0 || port > 65535) {
				throw new IllegalArgumentException("Port out of range: " + port);
			}
		}

		return new ConnectionEndpoint(host, port);
	}

	public String getHost() {
		return host;
	}

	public int getPort() {
		return port;
	}
}
