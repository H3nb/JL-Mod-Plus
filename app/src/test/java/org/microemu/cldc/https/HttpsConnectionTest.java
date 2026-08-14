/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.microemu.cldc.https;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.SocketException;

import javax.microedition.pki.CertificateException;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;

import org.junit.Test;

public class HttpsConnectionTest {
	@Test
	public void verifierCapturesProtocolAndMapsHostnameMismatch() {
		Connection connection = new Connection();
		SSLSession session = sessionWithProtocol("TLSv1.3");
		HostnameVerifier verifier = connection.wrapHostnameVerifier((hostname, candidate) -> false);

		assertFalse(verifier.verify("wrong.example", session));
		assertEquals("TLSv1.3", connection.getNegotiatedProtocol());

		IOException translated = connection.translateException(new IOException("hostname mismatch"));
		assertTrue(translated instanceof CertificateException);
		assertEquals(
				CertificateException.SITENAME_MISMATCH,
				((CertificateException) translated).getReason());
	}

	@Test
	public void successfulHostnameVerificationDoesNotRewriteNetworkFailures() {
		Connection connection = new Connection();
		SSLSession session = sessionWithProtocol("TLSv1.2");
		HostnameVerifier verifier = connection.wrapHostnameVerifier((hostname, candidate) -> true);
		SocketException failure = new SocketException("connection reset");

		assertTrue(verifier.verify("example.test", session));
		assertEquals("TLSv1.2", connection.getNegotiatedProtocol());
		assertSame(failure, connection.translateException(failure));
	}

	private static SSLSession sessionWithProtocol(String protocol) {
		return (SSLSession) Proxy.newProxyInstance(
				HttpsConnectionTest.class.getClassLoader(),
				new Class<?>[]{SSLSession.class},
				(proxy, method, args) -> {
					String name = method.getName();
					if ("getProtocol".equals(name)) {
						return protocol;
					}
					if ("getPeerCertificates".equals(name)) {
						throw new SSLPeerUnverifiedException("No peer certificate in test session");
					}
					if ("equals".equals(name)) {
						return proxy == args[0];
					}
					if ("hashCode".equals(name)) {
						return System.identityHashCode(proxy);
					}
					if ("toString".equals(name)) {
						return "TestSSLSession(" + protocol + ")";
					}

					Class<?> returnType = method.getReturnType();
					if (!returnType.isPrimitive()) {
						return null;
					}
					if (returnType == boolean.class) {
						return false;
					}
					if (returnType == byte.class) {
						return (byte) 0;
					}
					if (returnType == short.class) {
						return (short) 0;
					}
					if (returnType == int.class) {
						return 0;
					}
					if (returnType == long.class) {
						return 0L;
					}
					if (returnType == float.class) {
						return 0F;
					}
					if (returnType == double.class) {
						return 0D;
					}
					if (returnType == char.class) {
						return (char) 0;
					}
					return null;
				});
	}
}
