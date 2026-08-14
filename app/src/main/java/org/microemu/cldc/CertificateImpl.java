/*
 *  MicroEmulator
 *  Copyright (C) 2006 Bartek Teodorczyk <barteo@barteo.net>
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

package org.microemu.cldc;

import java.security.cert.X509Certificate;

import javax.microedition.pki.Certificate;

public class CertificateImpl implements Certificate {

	private static final char[] HEX = "0123456789ABCDEF".toCharArray();

	private X509Certificate cert;

	public CertificateImpl(X509Certificate cert) {
		this.cert = cert;
	}

	@Override
	public String getIssuer() {
		return cert.getIssuerDN().getName();
	}

	@Override
	public long getNotAfter() {
		return cert.getNotAfter().getTime();
	}

	@Override
	public long getNotBefore() {
		return cert.getNotBefore().getTime();
	}

	@Override
	public String getSerialNumber() {
		byte[] serial = cert.getSerialNumber().toByteArray();
		StringBuilder result = new StringBuilder(serial.length == 0 ? 0 : serial.length * 3 - 1);
		for (int i = 0; i < serial.length; i++) {
			if (i != 0) {
				result.append(':');
			}
			int value = serial[i] & 0xff;
			result.append(HEX[value >>> 4]);
			result.append(HEX[value & 0x0f]);
		}
		return result.toString();
	}

	@Override
	public String getSigAlgName() {
		return cert.getSigAlgName();
	}

	@Override
	public String getSubject() {
		return cert.getSubjectDN().getName();
	}

	@Override
	public String getType() {
		return cert.getType();
	}

	@Override
	public String getVersion() {
		return Integer.toString(cert.getVersion());
	}

}
