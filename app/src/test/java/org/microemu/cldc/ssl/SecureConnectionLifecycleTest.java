/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.microemu.cldc.ssl;

import static org.junit.Assert.assertTrue;

import java.io.OutputStream;

import javax.microedition.io.Connector;
import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;

import org.junit.Test;

public class SecureConnectionLifecycleTest {
	@Test
	public void secureOutputCloseUsesProviderHalfClose() throws Exception {
		TrackingSslSocket socket = new TrackingSslSocket();
		TestConnection connection = new TestConnection();
		connection.initializeForTest(socket);

		OutputStream output = connection.openOutputStream();
		connection.close();
		output.close();

		assertTrue(socket.outputShutdown);
		assertTrue(socket.closed);
	}

	private static final class TestConnection extends Connection {
		void initializeForTest(SSLSocket socket) {
			initialize(socket, Connector.READ_WRITE, false);
		}
	}

	private static final class TrackingSslSocket extends SSLSocket {
		boolean outputShutdown;
		boolean closed;
		private final OutputStream output = new OutputStream() {
			@Override
			public void write(int value) {
			}
		};

		@Override
		public OutputStream getOutputStream() {
			return output;
		}

		@Override
		public void shutdownOutput() {
			outputShutdown = true;
		}

		@Override
		public boolean isOutputShutdown() {
			return outputShutdown;
		}

		@Override
		public boolean isClosed() {
			return closed;
		}

		@Override
		public void close() {
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
		public void startHandshake() {
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
