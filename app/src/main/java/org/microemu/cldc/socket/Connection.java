/*
 *  MicroEmulator
 *  Copyright (C) 2001-2003 Bartek Teodorczyk <barteo@barteo.net>
 *  Copyright (C) 2017 Nikita Shakarun
 *  Modified for JL-Mod Plus to align Generic Connection Framework behavior with MIDP 2.0.
 *
 *  It is licensed under the following two licenses as alternatives:
 *    1. GNU Lesser General Public License (the "LGPL") version 2.1 or any newer version
 *    2. Apache License (the "AL") Version 2.0
 *
 *  You may not use this file except in compliance with at least one of
 *  the above two licenses.
 *
 *  You may obtain a copy of the LGPL at
 *      http://www.gnu.org/licenses/old-licenses/lgpl-2.1.txt
 *
 *  You may obtain a copy of the AL at
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the LGPL or the AL for the specific language governing permissions and
 *  limitations.
 */

package org.microemu.cldc.socket;

import org.microemu.cldc.ConnectionEndpoint;
import org.microemu.microedition.io.ConnectionImplementation;

import java.io.IOException;

import javax.microedition.io.Connector;

public class Connection implements ConnectionImplementation {

	@Override
	public javax.microedition.io.Connection openConnection(String name, int mode, boolean timeouts) throws IOException {
		if (!org.microemu.cldc.http.Connection.isAllowNetworkConnection()) {
			throw new IOException("No network");
		}
		validateMode(mode);

		ConnectionEndpoint endpoint = ConnectionEndpoint.parse(name, "socket");
		String host = endpoint.getHost();
		int port = endpoint.getPort();

		if (host.length() > 0) {
			if (port <= 0) {
				throw new IllegalArgumentException("Port missing");
			}
			return new SocketConnection(host, port, mode);
		}

		if (port == -1) {
			return new ServerSocketConnection(0, mode);
		}
		return new ServerSocketConnection(port, mode);
	}

	private static void validateMode(int mode) {
		if (mode != Connector.READ && mode != Connector.WRITE && mode != Connector.READ_WRITE) {
			throw new IllegalArgumentException("Invalid connection mode: " + mode);
		}
	}

	public void close() throws IOException {
		// Implemented in SocketConnection or ServerSocketConnection.
	}
}
