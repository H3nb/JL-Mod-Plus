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
package org.microemu.cldc.http;

import org.junit.Test;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;

import javax.net.ssl.SSLHandshakeException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ConnectionTest {
	@Test
	public void httpsHandshakeFailureUsesSecurityNotificationPath() throws Exception {
		TestConnection connection = new TestConnection();
		connection.setUrlConnection(new FakeUrlConnection(
				new URL("https://untrusted.example"),
				new SSLHandshakeException("untrusted certificate")
		));

		assertThrows(SSLHandshakeException.class, connection::getExpiration);
		assertTrue(connection.securityFailureHandled);
	}

	@Test
	public void successfulHttpsConnectionPreservesNormalBehavior() throws Exception {
		TestConnection connection = new TestConnection();
		connection.setUrlConnection(new FakeUrlConnection(
				new URL("https://secure.example"),
				null
		));

		connection.getExpiration();
		assertTrue(connection.connected);
		assertFalse(connection.securityFailureHandled);
	}

	private static final class TestConnection extends Connection {
		private boolean securityFailureHandled;

		private void setUrlConnection(URLConnection urlConnection) {
			cn = urlConnection;
		}

		@Override
		protected URLConnection createInsecureRetryConnectionIfAllowed(
				URLConnection connection,
				IOException error
		) {
			securityFailureHandled = true;
			return null;
		}
	}

	private static final class FakeUrlConnection extends URLConnection {
		private final IOException failure;

		private FakeUrlConnection(URL url, IOException failure) {
			super(url);
			this.failure = failure;
		}

		@Override
		public void connect() throws IOException {
			if (failure != null) {
				throw failure;
			}
			connected = true;
		}
	}
}
