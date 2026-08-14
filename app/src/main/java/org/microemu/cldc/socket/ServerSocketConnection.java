/*
 *  MicroEmulator
 *  Copyright (C) 2006 Bartek Teodorczyk <barteo@barteo.net>
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

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;

import javax.microedition.io.Connector;
import javax.microedition.io.StreamConnection;

public class ServerSocketConnection implements javax.microedition.io.ServerSocketConnection {

	private final ServerSocket serverSocket;
	private final int mode;
	private boolean closed;

	public ServerSocketConnection() throws IOException {
		this(0, Connector.READ_WRITE);
	}

	public ServerSocketConnection(int port) throws IOException {
		this(port, Connector.READ_WRITE);
	}

	public ServerSocketConnection(int port, int mode) throws IOException {
		if (mode != Connector.READ && mode != Connector.WRITE && mode != Connector.READ_WRITE) {
			throw new IllegalArgumentException("Invalid connection mode: " + mode);
		}
		serverSocket = new ServerSocket(port);
		this.mode = mode;
	}

	@Override
	public String getLocalAddress() throws IOException {
		ensureOpen();
		InetAddress localHost = InetAddress.getLocalHost();
		return localHost.getHostAddress();
	}

	@Override
	public int getLocalPort() throws IOException {
		ensureOpen();
		return serverSocket.getLocalPort();
	}

	@Override
	public StreamConnection acceptAndOpen() throws IOException {
		ensureOpen();
		return new SocketConnection(serverSocket.accept(), mode);
	}

	@Override
	public void close() throws IOException {
		if (closed) {
			return;
		}
		closed = true;
		serverSocket.close();
	}

	private void ensureOpen() throws IOException {
		if (closed || serverSocket.isClosed()) {
			throw new IOException("Connection is closed");
		}
	}
}
