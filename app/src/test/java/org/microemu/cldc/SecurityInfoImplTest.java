/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.microemu.cldc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;

import javax.microedition.pki.CertificateException;

import org.junit.Test;

public class SecurityInfoImplTest {
	@Test
	public void reportsTlsProtocolVersionsFromHostSession() {
		assertVersion("TLSv1", "3.1");
		assertVersion("TLSv1.1", "3.2");
		assertVersion("TLSv1.2", "3.3");
		assertVersion("TLSv1.3", "3.4");
		assertVersion("SSLv3", "3.0");
	}

	@Test
	public void unknownSecureProtocolMetadataDoesNotCrashMidlet() {
		SecurityInfoImpl info = new SecurityInfoImpl("cipher", "FutureTLS", null);

		assertEquals("FutureTLS", info.getProtocolName());
		assertEquals("0.0", info.getProtocolVersion());
	}

	@Test
	public void mapsKnownCertificateValidityFailures() {
		IOException expired = new IOException("expired", new CertificateExpiredException());
		IOException future = new IOException("future", new CertificateNotYetValidException());

		assertEquals(
				CertificateException.EXPIRED,
				((CertificateException) TlsExceptionMapper.translate(expired, null)).getReason());
		assertEquals(
				CertificateException.NOT_YET_VALID,
				((CertificateException) TlsExceptionMapper.translate(future, null)).getReason());
	}

	@Test
	public void certificateExceptionProvidesMidpReasonMessage() {
		assertEquals(
				"Certificate is expired",
				new CertificateException(null, CertificateException.EXPIRED).getMessage());
		assertEquals(
				"Certificate does not contain the correct site name",
				new CertificateException(null, CertificateException.SITENAME_MISMATCH).getMessage());
		assertEquals(
				"Certificate failed verification",
				new CertificateException(null, CertificateException.VERIFICATION_FAILED).getMessage());
	}

	@Test
	public void leavesNonCertificateNetworkFailureUntouched() {
		SocketException failure = new SocketException("connection reset");

		assertSame(failure, TlsExceptionMapper.translate(failure, null));
	}

	@Test
	public void timeoutHintControlsOnlyExceptionTypeNotDuration() {
		SocketTimeoutException timeout = new SocketTimeoutException("timed out");

		IOException enabled = GcfIoExceptionMapper.translate(timeout, true);
		IOException disabled = GcfIoExceptionMapper.translate(timeout, false);

		assertSame(timeout, enabled);
		assertTrue(enabled instanceof java.io.InterruptedIOException);
		assertFalse(disabled instanceof java.io.InterruptedIOException);
		assertEquals("timed out", disabled.getMessage());
		assertSame(timeout, disabled.getCause());
	}

	private static void assertVersion(String hostProtocol, String expected) {
		SecurityInfoImpl info = new SecurityInfoImpl("cipher", hostProtocol, null);
		assertEquals(expected, info.getProtocolVersion());
	}
}
