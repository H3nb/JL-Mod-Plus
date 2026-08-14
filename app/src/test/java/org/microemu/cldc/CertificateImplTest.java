/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.microemu.cldc;

import static org.junit.Assert.assertEquals;

import java.math.BigInteger;
import java.security.Principal;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.Set;

import javax.security.auth.x500.X500Principal;

import org.junit.Test;

public class CertificateImplTest {
	@Test
	public void serialNumberUsesMidpPrintableHexFormat() {
		CertificateImpl certificate = new CertificateImpl(
				new StubX509Certificate(new BigInteger("0C56FA80", 16)));

		assertEquals("0C:56:FA:80", certificate.getSerialNumber());
	}

	private static final class StubX509Certificate extends X509Certificate {
		private final BigInteger serialNumber;

		StubX509Certificate(BigInteger serialNumber) {
			this.serialNumber = serialNumber;
		}

		@Override
		public void checkValidity() {
		}

		@Override
		public void checkValidity(Date date) {
		}

		@Override
		public int getVersion() {
			return 3;
		}

		@Override
		public BigInteger getSerialNumber() {
			return serialNumber;
		}

		@Override
		public Principal getIssuerDN() {
			return new X500Principal("CN=Issuer");
		}

		@Override
		public Principal getSubjectDN() {
			return new X500Principal("CN=Subject");
		}

		@Override
		public Date getNotBefore() {
			return new Date(0);
		}

		@Override
		public Date getNotAfter() {
			return new Date(1);
		}

		@Override
		public byte[] getTBSCertificate() {
			return new byte[0];
		}

		@Override
		public byte[] getSignature() {
			return new byte[0];
		}

		@Override
		public String getSigAlgName() {
			return "NONE";
		}

		@Override
		public String getSigAlgOID() {
			return "0.0";
		}

		@Override
		public byte[] getSigAlgParams() {
			return null;
		}

		@Override
		public boolean[] getIssuerUniqueID() {
			return null;
		}

		@Override
		public boolean[] getSubjectUniqueID() {
			return null;
		}

		@Override
		public boolean[] getKeyUsage() {
			return null;
		}

		@Override
		public int getBasicConstraints() {
			return -1;
		}

		@Override
		public byte[] getEncoded() {
			return new byte[0];
		}

		@Override
		public void verify(PublicKey key) {
		}

		@Override
		public void verify(PublicKey key, String sigProvider) {
		}

		@Override
		public String toString() {
			return "StubX509Certificate";
		}

		@Override
		public PublicKey getPublicKey() {
			return null;
		}

		@Override
		public boolean hasUnsupportedCriticalExtension() {
			return false;
		}

		@Override
		public Set<String> getCriticalExtensionOIDs() {
			return null;
		}

		@Override
		public Set<String> getNonCriticalExtensionOIDs() {
			return null;
		}

		@Override
		public byte[] getExtensionValue(String oid) {
			return null;
		}
	}
}
