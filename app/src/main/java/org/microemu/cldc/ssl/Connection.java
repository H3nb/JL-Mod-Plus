/*
 *  MicroEmulator
 *  Copyright (C) 2006 Bartek Teodorczyk <barteo@barteo.net>
 *  Copyright (C) 2017 Nikita Shakarun
 *  Copyright 2026 H3NB
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
import org.microemu.cldc.SecureConnectionFailureNotifier;
import org.microemu.cldc.SecureConnectionPolicy;
import org.microemu.cldc.SecurityInfoImpl;
import org.microemu.microedition.io.ConnectionImplementation;

import java.io.IOException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

import javax.microedition.io.SecureConnection;
import javax.microedition.io.SecurityInfo;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public class Connection extends org.microemu.cldc.socket.SocketConnection implements SecureConnection, ConnectionImplementation {

	private SecurityInfo securityInfo;

	public Connection() {
		securityInfo = null;
	}

	@Override
	public javax.microedition.io.Connection openConnection(String name, int mode, boolean timeouts) throws IOException {

		if (!org.microemu.cldc.http.Connection.isAllowNetworkConnection()) {
			throw new IOException("No network");
		}

		int portSepIndex = name.lastIndexOf(':');
		int port = Integer.parseInt(name.substring(portSepIndex + 1));
		String host = name.substring("ssl://".length(), portSepIndex);

		if (SecureConnectionPolicy.getMode() == SecureConnectionPolicy.MODE_INSECURE) {
			socket = SecureConnectionPolicy.createInsecureSocket(host, port);
		} else {
			try {
				socket = createVerifiedSocket(host, port, (SSLSocketFactory) SSLSocketFactory.getDefault());
			} catch (IOException ex) {
				if (SecureConnectionFailureNotifier.handleTlsFailure(host, ex)) {
					socket = SecureConnectionPolicy.createInsecureSocket(host, port);
				} else {
					throw ex;
				}
			}
		}

		return this;
	}

	static SSLSocket createVerifiedSocket(String host, int port, SSLSocketFactory factory) throws IOException {
		return createVerifiedSocket(
				host,
				port,
				factory,
				HttpsURLConnection.getDefaultHostnameVerifier()
		);
	}

	static SSLSocket createVerifiedSocket(
			String host,
			int port,
			SSLSocketFactory factory,
			HostnameVerifier hostnameVerifier
	) throws IOException {
		SSLSocket sslSocket = null;
		try {
			sslSocket = (SSLSocket) factory.createSocket(host, port);
			sslSocket.startHandshake();
			if (!hostnameVerifier.verify(host, sslSocket.getSession())) {
				throw new SSLHandshakeException("Hostname verification failed for " + host);
			}
			return sslSocket;
		} catch (IOException | RuntimeException ex) {
			if (sslSocket != null) {
				try {
					sslSocket.close();
				} catch (IOException closeError) {
					ex.addSuppressed(closeError);
				}
			}
			throw ex;
		}
	}

	@Override
	public void close() throws IOException {
		// TODO fix differences between Java ME and Java SE

		if (socket != null) {
			socket.close();
		}
	}

	@Override
	public SecurityInfo getSecurityInfo() throws IOException {
		if (securityInfo == null) {
			SSLSession session = ((SSLSocket) socket).getSession();

			Certificate[] certs = session.getPeerCertificates();
			if (certs.length == 0) {
				throw new IOException();
			}

			securityInfo = new SecurityInfoImpl(
					session.getCipherSuite(),
					session.getProtocol(),
					new CertificateImpl((X509Certificate) certs[0]));
		}

		return securityInfo;
	}

}
