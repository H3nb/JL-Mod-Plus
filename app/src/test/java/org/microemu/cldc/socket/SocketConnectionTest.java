/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.microemu.cldc.socket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

import javax.microedition.io.Connector;
import javax.microedition.io.ServerSocketConnection;

import org.junit.Test;

public class SocketConnectionTest {
	@Test
	public void dynamicServerSocketIsBoundToSystemSelectedPort() throws Exception {
		ServerSocketConnection connection = (ServerSocketConnection) new Connection()
				.openConnection("socket://", Connector.READ_WRITE, false);
		try {
			assertTrue(connection.getLocalPort() > 0);
		} finally {
			connection.close();
		}
	}

	@Test
	public void vendorParameterIsIgnoredForTcpConnection() throws Exception {
		try (ServerSocket server = loopbackServer()) {
			SocketConnection client = (SocketConnection) new Connection().openConnection(
					"socket://127.0.0.1:" + server.getLocalPort() + ";deviceside=true",
					Connector.READ_WRITE,
					false);
			try (Socket accepted = server.accept()) {
				assertEquals(server.getLocalPort(), client.getPort());
			} finally {
				client.close();
			}
		}
	}

	@Test
	public void delayOptionMapsToTcpNoDelayWithMidpSemantics() throws Exception {
		try (ServerSocket server = loopbackServer()) {
			SocketConnection client = new SocketConnection("127.0.0.1", server.getLocalPort());
			try (Socket accepted = server.accept()) {
				client.setSocketOption(javax.microedition.io.SocketConnection.DELAY, 0);
				assertEquals(0, client.getSocketOption(javax.microedition.io.SocketConnection.DELAY));
				client.setSocketOption(javax.microedition.io.SocketConnection.DELAY, 1);
				assertEquals(1, client.getSocketOption(javax.microedition.io.SocketConnection.DELAY));
			} finally {
				client.close();
			}
		}
	}

	@Test
	public void streamCanOnlyBeOpenedOncePerDirection() throws Exception {
		try (ServerSocket server = loopbackServer()) {
			SocketConnection client = new SocketConnection("127.0.0.1", server.getLocalPort());
			try (Socket accepted = server.accept()) {
				OutputStream output = client.openOutputStream();
				expectIOException(client::openOutputStream);
				output.close();
				expectIOException(client::openOutputStream);
			} finally {
				client.close();
			}
		}
	}

	@Test
	public void closingOutputKeepsInputDirectionUsable() throws Exception {
		try (ServerSocket server = loopbackServer()) {
			SocketConnection client = new SocketConnection("127.0.0.1", server.getLocalPort());
			try (Socket accepted = server.accept()) {
				accepted.setSoTimeout(2000);
				OutputStream output = client.openOutputStream();
				output.write(7);
				output.close();

				assertEquals(7, accepted.getInputStream().read());
				assertEquals(-1, accepted.getInputStream().read());

				accepted.getOutputStream().write(9);
				accepted.getOutputStream().flush();
				InputStream input = client.openInputStream();
				assertEquals(9, input.read());
				input.close();
			} finally {
				client.close();
			}
		}
	}

	@Test
	public void openedStreamSurvivesConnectionObjectClose() throws Exception {
		try (ServerSocket server = loopbackServer()) {
			SocketConnection client = new SocketConnection("127.0.0.1", server.getLocalPort());
			try (Socket accepted = server.accept()) {
				accepted.setSoTimeout(2000);
				OutputStream output = client.openOutputStream();
				client.close();
				output.write(42);
				output.flush();
				assertEquals(42, accepted.getInputStream().read());
				output.close();
			}
		}
	}

	private static ServerSocket loopbackServer() throws IOException {
		ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
		server.setSoTimeout(2000);
		return server;
	}

	private static void expectIOException(IoOperation operation) throws Exception {
		try {
			operation.run();
			fail("Expected IOException");
		} catch (IOException expected) {
			// Expected by the MIDP StreamConnection contract.
		}
	}

	private interface IoOperation {
		void run() throws Exception;
	}
}
