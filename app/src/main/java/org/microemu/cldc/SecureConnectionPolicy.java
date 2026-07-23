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
package org.microemu.cldc;

import android.annotation.SuppressLint;

import java.io.IOException;
import java.net.URLConnection;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public final class SecureConnectionPolicy {
	public static final int MODE_ANDROID = 0;
	public static final int MODE_ASK = 1;
	public static final int MODE_INSECURE = 2;

	private static volatile int mode = MODE_ANDROID;
	private static volatile SSLSocketFactory insecureSocketFactory;

	private SecureConnectionPolicy() {
	}

	public static void setMode(int requestedMode) {
		if (requestedMode < MODE_ANDROID || requestedMode > MODE_INSECURE) {
			mode = MODE_ANDROID;
		} else {
			mode = requestedMode;
		}
		SecureConnectionFailureNotifier.resetForMidlet();
	}

	public static int getMode() {
		return mode;
	}

	public static void configureIfInsecure(URLConnection connection) throws IOException {
		if (mode != MODE_INSECURE || !(connection instanceof HttpsURLConnection https)) {
			return;
		}
		configureInsecure(https);
		SecureConnectionFailureNotifier.warnInsecureMode(https.getURL().getHost());
	}

	public static SSLSocket createInsecureSocket(String host, int port) throws IOException {
		SecureConnectionFailureNotifier.warnInsecureMode(host);
		SSLSocket socket = (SSLSocket) getInsecureSocketFactory().createSocket(host, port);
		try {
			socket.startHandshake();
			return socket;
		} catch (IOException | RuntimeException ex) {
			try {
				socket.close();
			} catch (IOException closeError) {
				ex.addSuppressed(closeError);
			}
			throw ex;
		}
	}

	@SuppressLint("BadHostnameVerifier")
	public static void configureInsecure(HttpsURLConnection connection) throws IOException {
		connection.setSSLSocketFactory(getInsecureSocketFactory());
		HostnameVerifier acceptAnyHostname = (hostname, session) -> true;
		connection.setHostnameVerifier(acceptAnyHostname);
	}

	private static SSLSocketFactory getInsecureSocketFactory() throws IOException {
		SSLSocketFactory factory = insecureSocketFactory;
		if (factory != null) {
			return factory;
		}
		synchronized (SecureConnectionPolicy.class) {
			factory = insecureSocketFactory;
			if (factory == null) {
				factory = createInsecureSocketFactory();
				insecureSocketFactory = factory;
			}
		}
		return factory;
	}

	@SuppressLint({"CustomX509TrustManager", "TrustAllX509TrustManager"})
	private static SSLSocketFactory createInsecureSocketFactory() throws IOException {
		TrustManager[] trustManagers = {
				new X509TrustManager() {
					@Override
					public X509Certificate[] getAcceptedIssuers() {
						return new X509Certificate[0];
					}

					@Override
					public void checkClientTrusted(X509Certificate[] chain, String authType) {
					}

					@Override
					public void checkServerTrusted(X509Certificate[] chain, String authType) {
					}
				}
		};
		try {
			SSLContext context = SSLContext.getInstance("TLS");
			context.init(null, trustManagers, new SecureRandom());
			return context.getSocketFactory();
		} catch (GeneralSecurityException ex) {
			throw new IOException("Unable to initialize the explicitly insecure TLS mode", ex);
		}
	}
}
