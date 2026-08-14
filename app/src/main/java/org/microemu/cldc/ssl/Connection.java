/*
 *  MicroEmulator
 *  Copyright (C) 2006 Bartek Teodorczyk <barteo@barteo.net>
 *  Copyright (C) 2017 Nikita Shakarun
 *  Modified for JL-Mod Plus to delegate secure transport to the Android TLS stack.
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

package org.microemu.cldc.ssl;

import org.microemu.cldc.CertificateImpl;
import org.microemu.cldc.ConnectionEndpoint;
import org.microemu.cldc.SecurityInfoImpl;
import org.microemu.cldc.TlsExceptionMapper;
import org.microemu.microedition.io.ConnectionImplementation;

import java.io.IOException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

import javax.microedition.io.Connector;
import javax.microedition.io.SecureConnection;
import javax.microedition.io.SecurityInfo;
import javax.microedition.pki.CertificateException;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public class Connection extends org.microemu.cldc.socket.SocketConnection
		implements SecureConnection, ConnectionImplementation {

	private SecurityInfo securityInfo;

	@Override
	public javax.microedition.io.Connection openConnection(String name, int mode, boolean timeouts) throws IOException {
		if (!org.microemu.cldc.http.Connection.isAllowNetworkConnection()) {
			throw new IOException("No network");
		}
		if (mode != Connector.READ && mode != Connector.WRITE && mode != Connector.READ_WRITE) {
			throw new IllegalArgumentException("Invalid connection mode: " + mode);
		}

		ConnectionEndpoint endpoint = ConnectionEndpoint.parse(name, "ssl");
		String host = endpoint.getHost();
		int port = endpoint.getPort();
		if (host.length() == 0 || port <= 0) {
			throw new IllegalArgumentException("Host and port are required");
		}

		SSLSocket sslSocket = null;
		javax.microedition.pki.Certificate midpCertificate = null;
		try {
			SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
			sslSocket = (SSLSocket) factory.createSocket(host, port);
			sslSocket.startHandshake();

			SSLSession session = sslSocket.getSession();
			Certificate[] peerCertificates = session.getPeerCertificates();
			if (peerCertificates.length == 0 || !(peerCertificates[0] instanceof X509Certificate)) {
				throw new IOException("TLS peer did not provide an X.509 certificate");
			}
			midpCertificate = new CertificateImpl((X509Certificate) peerCertificates[0]);

			if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)) {
				throw new CertificateException(
						"TLS certificate does not match " + host,
						midpCertificate,
						CertificateException.SITENAME_MISMATCH);
			}

			initialize(sslSocket, mode);
			securityInfo = new SecurityInfoImpl(
					session.getCipherSuite(), session.getProtocol(), midpCertificate);
			return this;
		} catch (IOException ex) {
			if (sslSocket != null) {
				try {
					sslSocket.close();
				} catch (IOException ignored) {
					// Preserve the original connection failure.
				}
			}
			throw TlsExceptionMapper.translate(ex, midpCertificate);
		}
	}

	@Override
	public SecurityInfo getSecurityInfo() throws IOException {
		ensureConnectionOpen();
		if (securityInfo == null) {
			throw new IOException("Security information is unavailable");
		}
		return securityInfo;
	}
}
