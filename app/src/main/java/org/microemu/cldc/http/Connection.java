/**
 * MicroEmulator
 * Copyright (C) 2001,2002 Bartek Teodorczyk <barteo@barteo.net>
 * Modified for JL-Mod Plus to align Generic Connection Framework behavior with MIDP 2.0.
 * <p>
 * It is licensed under the following two licenses as alternatives:
 * 1. GNU Lesser General Public License (the "LGPL") version 2.1 or any newer version
 * 2. Apache License (the "AL") Version 2.0
 * <p>
 * You may not use this file except in compliance with at least one of
 * the above two licenses.
 * <p>
 * You may obtain a copy of the LGPL at
 * http://www.gnu.org/licenses/old-licenses/lgpl-2.1.txt
 * <p>
 * You may obtain a copy of the AL at
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the LGPL or the AL for the specific language governing permissions and
 * limitations.
 */

package org.microemu.cldc.http;

import org.microemu.microedition.io.ConnectionImplementation;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.Map;
import java.util.TreeMap;

import javax.microedition.io.Connector;
import javax.microedition.io.HttpConnection;

public class Connection implements HttpConnection, ConnectionImplementation {

	private enum State {
		SETUP,
		OUTPUT_OPEN,
		CONNECTED,
		CLOSED
	}

	protected HttpURLConnection cn;
	protected boolean connected;

	private State state = State.CLOSED;
	private int mode = Connector.READ_WRITE;
	private String requestMethod = HttpConnection.GET;
	private final Map<String, String> requestProperties =
			new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
	private boolean inputOpened;
	private boolean inputClosed;
	private boolean outputOpened;
	private boolean outputClosed;

	protected static boolean allowNetworkConnection = true;

	@Override
	public javax.microedition.io.Connection openConnection(String name, int mode, boolean timeouts) throws IOException {
		if (!isAllowNetworkConnection()) {
			throw new IOException("No network");
		}
		validateMode(mode);

		URL url;
		try {
			url = new URL(name);
		} catch (MalformedURLException ex) {
			throw new IOException(ex.toString());
		}
		if (url.getHost() == null || url.getHost().length() == 0) {
			throw new IllegalArgumentException("missing host in URL");
		}

		URLConnection opened = url.openConnection();
		if (!(opened instanceof HttpURLConnection)) {
			throw new IOException("Not an HTTP connection");
		}

		cn = (HttpURLConnection) opened;
		cn.setInstanceFollowRedirects(false);
		cn.setRequestProperty("Accept-Encoding", "identity");

		this.mode = mode;
		requestMethod = HttpConnection.GET;
		requestProperties.clear();
		requestProperties.put("Accept-Encoding", "identity");
		state = State.SETUP;
		connected = false;
		inputOpened = false;
		inputClosed = false;
		outputOpened = false;
		outputClosed = false;
		return this;
	}

	@Override
	public synchronized void close() throws IOException {
		if (state == State.CLOSED) {
			return;
		}
		state = State.CLOSED;
		disconnectIfUnused();
	}

	@Override
	public String getURL() {
		return isClosed() || cn == null ? null : cn.getURL().toString();
	}

	@Override
	public String getProtocol() {
		return "http";
	}

	@Override
	public String getHost() {
		return isClosed() || cn == null ? null : cn.getURL().getHost();
	}

	@Override
	public String getFile() {
		if (isClosed() || cn == null) {
			return null;
		}
		String path = cn.getURL().getPath();
		return path == null || path.length() == 0 ? null : path;
	}

	@Override
	public String getRef() {
		return isClosed() || cn == null ? null : cn.getURL().getRef();
	}

	@Override
	public String getQuery() {
		return isClosed() || cn == null ? null : cn.getURL().getQuery();
	}

	@Override
	public int getPort() {
		if (isClosed() || cn == null) {
			return -1;
		}
		int port = cn.getURL().getPort();
		return port == -1 ? 80 : port;
	}

	@Override
	public String getRequestMethod() {
		return isClosed() ? null : requestMethod;
	}

	@Override
	public synchronized void setRequestMethod(String method) throws IOException {
		ensureConnectionOpen();
		if (state == State.OUTPUT_OPEN) {
			return;
		}
		if (state == State.CONNECTED) {
			throw new IOException("Connection already established");
		}
		if (!HttpConnection.GET.equals(method)
				&& !HttpConnection.POST.equals(method)
				&& !HttpConnection.HEAD.equals(method)) {
			throw new IOException("Invalid HTTP method: " + method);
		}

		cn.setRequestMethod(method);
		cn.setDoOutput(HttpConnection.POST.equals(method));
		requestMethod = method;
	}

	@Override
	public String getRequestProperty(String key) {
		return isClosed() ? null : requestProperties.get(key);
	}

	@Override
	public synchronized void setRequestProperty(String key, String value) throws IOException {
		ensureConnectionOpen();
		if (state == State.OUTPUT_OPEN) {
			return;
		}
		if (state == State.CONNECTED) {
			throw new IOException("Connection already established");
		}

		cn.setRequestProperty(key, value);
		requestProperties.put(key, value);
	}

	@Override
	public int getResponseCode() throws IOException {
		ensureConnected();
		try {
			return cn.getResponseCode();
		} catch (IOException ex) {
			throw translateException(ex);
		}
	}

	@Override
	public String getResponseMessage() throws IOException {
		ensureConnected();
		try {
			return cn.getResponseMessage();
		} catch (IOException ex) {
			throw translateException(ex);
		}
	}

	@Override
	public long getExpiration() throws IOException {
		ensureConnected();
		return cn.getExpiration();
	}

	@Override
	public long getDate() throws IOException {
		ensureConnected();
		return cn.getDate();
	}

	@Override
	public long getLastModified() throws IOException {
		ensureConnected();
		return cn.getLastModified();
	}

	@Override
	public String getHeaderField(String name) throws IOException {
		ensureConnected();
		return cn.getHeaderField(name);
	}

	@Override
	public int getHeaderFieldInt(String name, int def) throws IOException {
		ensureConnected();
		return cn.getHeaderFieldInt(name, def);
	}

	@Override
	public long getHeaderFieldDate(String name, long def) throws IOException {
		ensureConnected();
		return cn.getHeaderFieldDate(name, def);
	}

	@Override
	public String getHeaderField(int n) throws IOException {
		ensureConnected();
		return cn.getHeaderField(getImplIndex(n));
	}

	@Override
	public String getHeaderFieldKey(int n) throws IOException {
		ensureConnected();
		return cn.getHeaderFieldKey(getImplIndex(n));
	}

	private int getImplIndex(int index) {
		if (cn.getHeaderFieldKey(0) == null && cn.getHeaderField(0) != null) {
			index++;
		}
		return index;
	}

	@Override
	public synchronized InputStream openInputStream() throws IOException {
		ensureConnectionOpen();
		if (mode == Connector.WRITE) {
			throw new IOException("Connection is write-only");
		}
		if (inputOpened) {
			throw new IOException("Input stream already opened");
		}

		ensureConnected();
		InputStream input;
		try {
			input = cn.getInputStream();
		} catch (IOException ex) {
			InputStream errorStream = cn.getErrorStream();
			if (errorStream == null) {
				throw translateException(ex);
			}
			input = errorStream;
		}
		inputOpened = true;
		return new ManagedInputStream(input);
	}

	@Override
	public DataInputStream openDataInputStream() throws IOException {
		return new DataInputStream(openInputStream());
	}

	@Override
	public synchronized OutputStream openOutputStream() throws IOException {
		ensureConnectionOpen();
		if (mode == Connector.READ) {
			throw new IOException("Connection is read-only");
		}
		if (outputOpened) {
			throw new IOException("Output stream already opened");
		}
		if (state != State.SETUP) {
			throw new IOException("Connection already established");
		}

		try {
			if (HttpConnection.GET.equals(requestMethod)) {
				requestMethod = HttpConnection.POST;
				cn.setRequestMethod(HttpConnection.POST);
			}
			cn.setDoOutput(true);
			OutputStream output = cn.getOutputStream();
			outputOpened = true;
			state = State.OUTPUT_OPEN;
			return new ManagedOutputStream(output);
		} catch (IOException ex) {
			throw translateException(ex);
		}
	}

	@Override
	public DataOutputStream openDataOutputStream() throws IOException {
		return new DataOutputStream(openOutputStream());
	}

	@Override
	public String getType() {
		try {
			return getHeaderField("content-type");
		} catch (IOException ex) {
			return null;
		}
	}

	@Override
	public String getEncoding() {
		try {
			return getHeaderField("content-encoding");
		} catch (IOException ex) {
			return null;
		}
	}

	@Override
	public long getLength() {
		try {
			String value = getHeaderField("content-length");
			if (value == null) {
				return -1;
			}
			try {
				return Long.parseLong(value.trim());
			} catch (NumberFormatException ex) {
				return -1;
			}
		} catch (IOException ex) {
			return -1;
		}
	}

	protected synchronized void ensureConnected() throws IOException {
		ensureConnectionOpen();
		if (state == State.CONNECTED) {
			return;
		}
		try {
			cn.connect();
			markConnected();
		} catch (IOException ex) {
			throw translateException(ex);
		}
	}

	protected IOException translateException(IOException exception) {
		return exception;
	}

	private synchronized void markConnected() {
		connected = true;
		if (state != State.CLOSED) {
			state = State.CONNECTED;
		}
	}

	private synchronized void onInputClosed() throws IOException {
		if (inputClosed) {
			return;
		}
		inputClosed = true;
		disconnectIfUnused();
	}

	private synchronized void onOutputFlushed() {
		markConnected();
	}

	private synchronized void onOutputClosed() throws IOException {
		if (outputClosed) {
			return;
		}
		outputClosed = true;
		markConnected();
		disconnectIfUnused();
	}

	private void disconnectIfUnused() {
		if (state != State.CLOSED) {
			return;
		}
		boolean inputActive = inputOpened && !inputClosed;
		boolean outputActive = outputOpened && !outputClosed;
		if (!inputActive && !outputActive && cn != null) {
			cn.disconnect();
			cn = null;
		}
	}

	private void ensureConnectionOpen() throws IOException {
		if (state == State.CLOSED || cn == null) {
			throw new IOException("Connection is closed");
		}
	}

	private boolean isClosed() {
		return state == State.CLOSED;
	}

	private static void validateMode(int mode) {
		if (mode != Connector.READ && mode != Connector.WRITE && mode != Connector.READ_WRITE) {
			throw new IllegalArgumentException("Invalid connection mode: " + mode);
		}
	}

	public static boolean isAllowNetworkConnection() {
		return allowNetworkConnection;
	}

	public static void setAllowNetworkConnection(boolean allowNetworkConnection) {
		Connection.allowNetworkConnection = allowNetworkConnection;
	}

	private final class ManagedInputStream extends InputStream {
		private final InputStream input;
		private boolean closed;

		ManagedInputStream(InputStream input) {
			this.input = input;
		}

		@Override
		public int read() throws IOException {
			ensureStreamOpen();
			return input.read();
		}

		@Override
		public int read(byte[] buffer, int offset, int length) throws IOException {
			ensureStreamOpen();
			return input.read(buffer, offset, length);
		}

		@Override
		public long skip(long count) throws IOException {
			ensureStreamOpen();
			return input.skip(count);
		}

		@Override
		public int available() throws IOException {
			ensureStreamOpen();
			return input.available();
		}

		@Override
		public void close() throws IOException {
			if (closed) {
				return;
			}
			closed = true;
			try {
				input.close();
			} finally {
				onInputClosed();
			}
		}

		private void ensureStreamOpen() throws IOException {
			if (closed) {
				throw new IOException("Input stream is closed");
			}
		}
	}

	private final class ManagedOutputStream extends OutputStream {
		private final OutputStream output;
		private boolean closed;

		ManagedOutputStream(OutputStream output) {
			this.output = output;
		}

		@Override
		public void write(int value) throws IOException {
			ensureStreamOpen();
			output.write(value);
		}

		@Override
		public void write(byte[] buffer, int offset, int length) throws IOException {
			ensureStreamOpen();
			output.write(buffer, offset, length);
		}

		@Override
		public void flush() throws IOException {
			ensureStreamOpen();
			output.flush();
			onOutputFlushed();
		}

		@Override
		public void close() throws IOException {
			if (closed) {
				return;
			}
			try {
				output.close();
			} finally {
				closed = true;
				onOutputClosed();
			}
		}

		private void ensureStreamOpen() throws IOException {
			if (closed) {
				throw new IOException("Output stream is closed");
			}
		}
	}
}
