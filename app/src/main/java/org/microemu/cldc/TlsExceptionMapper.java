/*
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

import java.io.IOException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;

import javax.microedition.pki.Certificate;
import javax.microedition.pki.CertificateException;
import javax.net.ssl.SSLPeerUnverifiedException;

/** Maps platform certificate failures to the reasons exposed by MIDP. */
public final class TlsExceptionMapper {
	private TlsExceptionMapper() {
	}

	public static IOException translate(IOException exception, Certificate certificate) {
		if (exception instanceof CertificateException) {
			return exception;
		}

		boolean certificateFailure = false;
		Throwable cause = exception;
		while (cause != null) {
			if (cause instanceof CertificateExpiredException) {
				return new CertificateException(exception.getMessage(), certificate, CertificateException.EXPIRED);
			}
			if (cause instanceof CertificateNotYetValidException) {
				return new CertificateException(exception.getMessage(), certificate, CertificateException.NOT_YET_VALID);
			}
			if (cause instanceof java.security.cert.CertificateException
					|| cause instanceof SSLPeerUnverifiedException) {
				certificateFailure = true;
			}
			cause = cause.getCause();
		}

		if (certificateFailure) {
			return new CertificateException(
					exception.getMessage(), certificate, CertificateException.VERIFICATION_FAILED);
		}
		return exception;
	}
}
