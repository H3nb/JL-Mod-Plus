/*
 *  MicroEmulator
 *  Copyright (C) 2006 Bartek Teodorczyk <barteo@barteo.net>
 *  Modified for JL-Mod Plus to align HTTPS security behavior with MIDP 2.0.
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

package org.microemu.cldc.https;

import org.microemu.cldc.CertificateImpl;
import org.microemu.cldc.SecurityInfoImpl;
import org.microemu.cldc.TlsExceptionMapper;

import java.io.IOException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

import javax.microedition.io.HttpsConnection;
import javax.microedition.io.SecurityInfo;
import javax.net.ssl.HttpsURLConnection;

public class Connection extends org.microemu.cldc.http.Connection implements HttpsConnection {

	private SecurityInfo securityInfo;

	@Override
	public SecurityInfo getSecurityInfo() throws IOException {
		if (securityInfo != null) {
			return securityInfo;
		}

		ensureConnected();
		HttpsURLConnection https = (HttpsURLConnection) cn;
		try {
			Certificate[] certificates = https.getServerCertificates();
			if (certificates.length == 0 || !(certificates[0] instanceof X509Certificate)) {
				throw new IOException("HTTPS peer did not provide an X.509 certificate");
			}
			securityInfo = new SecurityInfoImpl(
					https.getCipherSuite(),
					"TLS",
					new CertificateImpl((X509Certificate) certificates[0]));
			return securityInfo;
		} catch (IOException ex) {
			throw translateException(ex);
		}
	}

	@Override
	protected IOException translateException(IOException exception) {
		return TlsExceptionMapper.translate(exception, null);
	}

	@Override
	public String getProtocol() {
		return "https";
	}

	@Override
	public int getPort() {
		if (cn == null) {
			return -1;
		}
		int port = cn.getURL().getPort();
		return port == -1 ? 443 : port;
	}
}
