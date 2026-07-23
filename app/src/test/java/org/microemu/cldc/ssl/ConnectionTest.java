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
package org.microemu.cldc.ssl;

import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;

import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ConnectionTest {
	@Test
	public void createVerifiedSocketStartsHandshakeAndChecksHost() throws IOException {
		FakeSslSocket socket = new FakeSslSocket(null);

		SSLSocket result = Connection.createVerifiedSocket(
				"secure.example",
				443,
				new FakeSslSocketFactory(socket),
				(host, session) -> host.equals("secure.example")
		);

		assertSame(socket, result);
		assertTrue(socket.handshakeStarted);
		assertFalse(socket.closed);
	}

	@Test
	public void createVerifiedSocketRejectsAndClosesFailedHandshake() {
		SSLHandshakeException failure = new SSLHandshakeException("untrusted certificate");
		FakeSslSocket socket = new FakeSslSocket(failure);

		SSLHandshakeException thrown = assertThrows(
				SSLHandshakeException.class,
				() -> Connection.createVerifiedSocket(
						"untrusted.example",
						443,
						new FakeSslSocketFactory(socket),
						(host, session) -> true
				)
		);

		assertSame(failure, thrown);
		assertTrue(socket.handshakeStarted);
		assertTrue(socket.closed);
	}

	@Test
	public void createVerifiedSocketRejectsAndClosesMismatchedHostname() {
		FakeSslSocket socket = new FakeSslSocket(null);

		SSLHandshakeException thrown = assertThrows(
				SSLHandshakeException.class,
				() -> Connection.createVerifiedSocket(
						"wrong.example",
						443,
						new FakeSslSocketFactory(socket),
						(host, session) -> false
				)
		);

		assertTrue(thrown.getMessage().contains("wrong.example"));
		assertTrue(socket.handshakeStarted);
		assertTrue(socket.closed);
	}

	private static final class FakeSslSocketFactory extends SSLSocketFactory {
		private final FakeSslSocket socket;

		private FakeSslSocketFactory(FakeSslSocket socket) {
			this.socket = socket;
		}

		@Override
		public String[] getDefaultCipherSuites() {
			return new String[0];
		}

		@Override
		public String[] getSupportedCipherSuites() {
			return new String[0];
		}

		@Override
		public Socket createSocket(String host, int port) {
			return socket;
		}

		@Override
		public Socket createSocket(String host, int port, InetAddress localHost, int localPort) {
			return socket;
		}

		@Override
		public Socket createSocket(InetAddress host, int port) {
			return socket;
		}

		@Override
		public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) {
			return socket;
		}

		@Override
		public Socket createSocket(Socket socket, String host, int port, boolean autoClose) {
			return this.socket;
		}
	}

	private static final class FakeSslSocket extends SSLSocket {
		private final IOException handshakeFailure;
		private boolean handshakeStarted;
		private boolean closed;

		private FakeSslSocket(IOException handshakeFailure) {
			this.handshakeFailure = handshakeFailure;
		}

		@Override
		public void startHandshake() throws IOException {
			handshakeStarted = true;
			if (handshakeFailure != null) {
				throw handshakeFailure;
			}
		}

		@Override
		public synchronized void close() {
			closed = true;
		}

		@Override
		public String[] getSupportedCipherSuites() {
			return new String[0];
		}

		@Override
		public String[] getEnabledCipherSuites() {
			return new String[0];
		}

		@Override
		public void setEnabledCipherSuites(String[] suites) {
		}

		@Override
		public String[] getSupportedProtocols() {
			return new String[0];
		}

		@Override
		public String[] getEnabledProtocols() {
			return new String[0];
		}

		@Override
		public void setEnabledProtocols(String[] protocols) {
		}

		@Override
		public SSLSession getSession() {
			return null;
		}

		@Override
		public void addHandshakeCompletedListener(HandshakeCompletedListener listener) {
		}

		@Override
		public void removeHandshakeCompletedListener(HandshakeCompletedListener listener) {
		}

		@Override
		public void setUseClientMode(boolean mode) {
		}

		@Override
		public boolean getUseClientMode() {
			return true;
		}

		@Override
		public void setNeedClientAuth(boolean need) {
		}

		@Override
		public boolean getNeedClientAuth() {
			return false;
		}

		@Override
		public void setWantClientAuth(boolean want) {
		}

		@Override
		public boolean getWantClientAuth() {
			return false;
		}

		@Override
		public void setEnableSessionCreation(boolean flag) {
		}

		@Override
		public boolean getEnableSessionCreation() {
			return true;
		}
	}
}
