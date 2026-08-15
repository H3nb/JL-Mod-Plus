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

	@Test
	public void versionUsesRfc2459EncodedValue() {
		CertificateImpl certificate = new CertificateImpl(
				new StubX509Certificate(BigInteger.ONE));

		assertEquals("2", certificate.getVersion());
	}

	@Test
	public void subjectAndIssuerUseStrictMidpPrintableDn() {
		X500Principal subject = new X500Principal(
				"CN=www.anycompany.com,O=Any Company\\, Inc.,C=US");
		X500Principal issuer = new X500Principal(
				"CN=Example Root,O=Example CA,C=GB");
		CertificateImpl certificate = new CertificateImpl(
				new StubX509Certificate(BigInteger.ONE, issuer, subject));

		assertEquals(
				"C=US;O=Any Company, Inc.;CN=www.anycompany.com",
				certificate.getSubject());
		assertEquals(
				"C=GB;O=Example CA;CN=Example Root",
				certificate.getIssuer());
	}

	private static final class StubX509Certificate extends X509Certificate {
		private final BigInteger serialNumber;
		private final X500Principal issuer;
		private final X500Principal subject;

		StubX509Certificate(BigInteger serialNumber) {
			this(
					serialNumber,
					new X500Principal("CN=Issuer"),
					new X500Principal("CN=Subject"));
		}

		StubX509Certificate(
				BigInteger serialNumber, X500Principal issuer, X500Principal subject) {
			this.serialNumber = serialNumber;
			this.issuer = issuer;
			this.subject = subject;
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
			return issuer;
		}

		@Override
		public Principal getSubjectDN() {
			return subject;
		}

		@Override
		public X500Principal getIssuerX500Principal() {
			return issuer;
		}

		@Override
		public X500Principal getSubjectX500Principal() {
			return subject;
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
