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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

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
	public void connectorModeDoesNotRestrictSocketDirections() throws Exception {
		try (ServerSocket server = loopbackServer()) {
			SocketConnection client = new SocketConnection(
					"127.0.0.1", server.getLocalPort(), Connector.READ, false);
			try (Socket accepted = server.accept()) {
				OutputStream output = client.openOutputStream();
				output.write(11);
				output.flush();
				assertEquals(11, accepted.getInputStream().read());

				accepted.getOutputStream().write(12);
				accepted.getOutputStream().flush();
				assertEquals(12, client.openInputStream().read());
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
				try {
					client.setSocketOption(javax.microedition.io.SocketConnection.DELAY, -1);
					fail("Expected IllegalArgumentException");
				} catch (IllegalArgumentException expected) {
					// MIDP rejects negative socket option values.
				}
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

	@Test
	public void closingInputFromAnotherThreadUnblocksRead() throws Exception {
		try (ServerSocket server = loopbackServer()) {
			SocketConnection client = new SocketConnection("127.0.0.1", server.getLocalPort());
			try (Socket accepted = server.accept()) {
				InputStream input = client.openInputStream();
				CountDownLatch started = new CountDownLatch(1);
				FutureTask<Boolean> readTask = new FutureTask<>(() -> {
					started.countDown();
					try {
						return input.read() == -1;
					} catch (IOException expected) {
						return true;
					}
				});
				Thread reader = new Thread(readTask, "j2me-socket-blocked-read");
				reader.setDaemon(true);
				reader.start();

				assertTrue(started.await(1, TimeUnit.SECONDS));
				input.close();
				assertTrue(readTask.get(2, TimeUnit.SECONDS));
			} finally {
				client.close();
			}
		}
	}

	@Test
	public void outputCloseStillCleansUpWhenFlushFails() throws Exception {
		TrackingSocket socket = new TrackingSocket();
		SocketConnection connection = new SocketConnection(socket);
		OutputStream output = connection.openOutputStream();
		connection.close();

		try {
			output.close();
			fail("Expected IOException");
		} catch (IOException expected) {
			assertEquals("flush failed", expected.getMessage());
		}

		assertTrue(socket.outputShutdown);
		assertTrue(socket.closed);
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

	private static final class TrackingSocket extends Socket {
		boolean closed;
		boolean outputShutdown;
		private final OutputStream output = new OutputStream() {
			@Override
			public void write(int value) {
			}

			@Override
			public void flush() throws IOException {
				throw new IOException("flush failed");
			}
		};

		@Override
		public OutputStream getOutputStream() {
			return output;
		}

		@Override
		public boolean isClosed() {
			return closed;
		}

		@Override
		public boolean isOutputShutdown() {
			return outputShutdown;
		}

		@Override
		public void shutdownOutput() {
			outputShutdown = true;
		}

		@Override
		public void close() {
			closed = true;
		}
	}
}
