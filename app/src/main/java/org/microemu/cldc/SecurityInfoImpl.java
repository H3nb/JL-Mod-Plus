/*
 *  MicroEmulator
 *  Copyright (C) 2006 Bartek Teodorczyk <barteo@barteo.net>
 *  Modified for JL-Mod Plus to report negotiated host TLS versions when available.
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

import javax.microedition.io.SecurityInfo;
import javax.microedition.pki.Certificate;

public class SecurityInfoImpl implements SecurityInfo {

	private final String cipherSuite;
	private final String protocolName;
	private final Certificate certificate;

	public SecurityInfoImpl(String cipherSuite, String protocolName, Certificate certificate) {
		this.cipherSuite = cipherSuite;
		this.protocolName = protocolName == null ? "" : protocolName;
		this.certificate = certificate;
	}

	@Override
	public String getCipherSuite() {
		return cipherSuite;
	}

	@Override
	public String getProtocolName() {
		if (protocolName.startsWith("TLS")) {
			return "TLS";
		}
		if (protocolName.startsWith("SSL")) {
			return "SSL";
		}
		return protocolName;
	}

	@Override
	public String getProtocolVersion() {
		if ("TLSv1.3".equals(protocolName)) {
			return "3.4";
		}
		if ("TLSv1.2".equals(protocolName)) {
			return "3.3";
		}
		if ("TLSv1.1".equals(protocolName)) {
			return "3.2";
		}
		if ("TLSv1".equals(protocolName) || "TLSv1.0".equals(protocolName)
				|| "TLS".equals(protocolName)) {
			return "3.1";
		}
		if (protocolName.startsWith("SSL")) {
			return "3.0";
		}
		// Unknown provider protocol names should not crash a MIDlet querying
		// metadata. "0.0" is a stable sentinel outside the defined MIDP values.
		return "0.0";
	}

	@Override
	public Certificate getServerCertificate() {
		return certificate;
	}
}
