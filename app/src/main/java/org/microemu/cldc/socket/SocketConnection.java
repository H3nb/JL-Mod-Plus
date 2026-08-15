/*
 *  MicroEmulator
 *  Copyright (C) 2001-2003 Bartek Teodorczyk <barteo@barteo.net>
 *  Modified for JL-Mod Plus to align Generic Connection Framework behavior with MIDP 2.0.
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

package org.microemu.cldc.socket;

import org.microemu.cldc.GcfIoExceptionMapper;
import org.microemu.cldc.NetworkAddressUtil;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

import javax.microedition.io.Connector;

public class SocketConnection implements javax.microedition.io.SocketConnection {

	protected Socket socket;
	private boolean timeouts;
	private boolean connectionClosed;
	private boolean inputOpened;
	private boolean inputClosed;
	private boolean outputOpened;
	private boolean outputClosed;

	public SocketConnection() {
	}

	public SocketConnection(String host, int port) throws IOException {
		this(host, port, Connector.READ_WRITE, false);
	}

	public SocketConnection(String host, int port, int mode) throws IOException {
		this(host, port, mode, false);
	}

	public SocketConnection(String host, int port, int mode, boolean timeouts) throws IOException {
		validateMode(mode);
		Socket created = new Socket();
		try {
			created.connect(new InetSocketAddress(host, port));
			initialize(created, mode, timeouts);
		} catch (IOException ex) {
			try {
				created.close();
			} catch (IOException ignored) {
				// Preserve the original connection failure.
			}
			throw translate(ex, timeouts);
		}
	}

	public SocketConnection(Socket socket) {
		this(socket, Connector.READ_WRITE, false);
	}

	public SocketConnection(Socket socket, int mode) {
		this(socket, mode, false);
	}

	public SocketConnection(Socket socket, int mode, boolean timeouts) {
		initialize(socket, mode, timeouts);
	}

	protected final void initialize(Socket socket, int mode) {
		initialize(socket, mode, false);
	}

	protected final void initialize(Socket socket, int mode, boolean timeouts) {
		validateMode(mode);
		if (socket == null) {
			throw new NullPointerException("socket");
		}
		this.socket = socket;
		this.timeouts = timeouts;
		connectionClosed = false;
		inputOpened = false;
		inputClosed = false;
		outputOpened = false;
		outputClosed = false;
	}

	@Override
	public String getAddress() throws IOException {
		ensureConnectionOpen();
		return NetworkAddressUtil.format(socket.getInetAddress());
	}

	@Override
	public String getLocalAddress() throws IOException {
		ensureConnectionOpen();
		return NetworkAddressUtil.format(socket.getLocalAddress());
	}

	@Override
	public int getLocalPort() throws IOException {
		ensureConnectionOpen();
		return socket.getLocalPort();
	}

	@Override
	public int getPort() throws IOException {
		ensureConnectionOpen();
		return socket.getPort();
	}

	@Override
	public int getSocketOption(byte option) throws IllegalArgumentException, IOException {
		ensureConnectionOpen();
		switch (option) {
			case DELAY:
				return socket.getTcpNoDelay() ? 0 : 1;
			case LINGER:
				int value = socket.getSoLinger();
				return value == -1 ? 0 : value;
			case KEEPALIVE:
				return socket.getKeepAlive() ? 1 : 0;
			case RCVBUF:
				return socket.getReceiveBufferSize();
			case SNDBUF:
				return socket.getSendBufferSize();
			default:
				throw new IllegalArgumentException("Unknown socket option: " + option);
		}
	}

	@Override
	public void setSocketOption(byte option, int value) throws IllegalArgumentException, IOException {
		ensureConnectionOpen();
		if (value < 0) {
			throw new IllegalArgumentException("Negative socket option value");
		}
		switch (option) {
			case DELAY:
				socket.setTcpNoDelay(value == 0);
				break;
			case LINGER:
				socket.setSoLinger(value != 0, value);
				break;
			case KEEPALIVE:
				socket.setKeepAlive(value != 0);
				break;
			case RCVBUF:
				if (value <= 0) {
					throw new IllegalArgumentException("Invalid receive buffer size");
				}
				socket.setReceiveBufferSize(value);
				break;
			case SNDBUF:
				if (value <= 0) {
					throw new IllegalArgumentException("Invalid send buffer size");
				}
				socket.setSendBufferSize(value);
				break;
			default:
				throw new IllegalArgumentException("Unknown socket option: " + option);
		}
	}

	@Override
	public synchronized void close() throws IOException {
		if (connectionClosed) {
			return;
		}
		connectionClosed = true;
		closeSocketIfUnused();
	}

	@Override
	public synchronized InputStream openInputStream() throws IOException {
		ensureConnectionOpen();
		if (inputOpened) {
			throw new IOException("Input stream already opened");
		}
		try {
			InputStream input = socket.getInputStream();
			inputOpened = true;
			return new ManagedInputStream(input);
		} catch (IOException ex) {
			throw translate(ex);
		}
	}

	@Override
	public DataInputStream openDataInputStream() throws IOException {
		return new DataInputStream(openInputStream());
	}

	@Override
	public synchronized OutputStream openOutputStream() throws IOException {
		ensureConnectionOpen();
		if (outputOpened) {
			throw new IOException("Output stream already opened");
		}
		try {
			OutputStream output = socket.getOutputStream();
			outputOpened = true;
			return new ManagedOutputStream(output);
		} catch (IOException ex) {
			throw translate(ex);
		}
	}

	@Override
	public DataOutputStream openDataOutputStream() throws IOException {
		return new DataOutputStream(openOutputStream());
	}

	protected final void ensureConnectionOpen() throws IOException {
		if (connectionClosed || socket == null || socket.isClosed()) {
			throw new IOException("Connection is closed");
		}
	}

	protected void shutdownInputDirection() throws IOException {
		socket.shutdownInput();
	}

	protected void shutdownOutputDirection() throws IOException {
		// Keep half-close provider-owned. SSLSocket overrides this transport path
		// without requiring JL-Mod Plus to implement TLS shutdown itself.
		socket.shutdownOutput();
	}

	private synchronized void onInputClosed() throws IOException {
		if (inputClosed) {
			return;
		}
		inputClosed = true;
		IOException failure = null;
		if (!socket.isClosed() && !socket.isInputShutdown()) {
			try {
				shutdownInputDirection();
			} catch (IOException ex) {
				failure = translate(ex);
			}
		}
		try {
			closeSocketIfUnused();
		} catch (IOException ex) {
			failure = merge(failure, translate(ex));
		}
		if (failure != null) {
			throw failure;
		}
	}

	private synchronized void onOutputClosed() throws IOException {
		if (outputClosed) {
			return;
		}
		outputClosed = true;
		IOException failure = null;
		if (!socket.isClosed() && !socket.isOutputShutdown()) {
			try {
				shutdownOutputDirection();
			} catch (IOException ex) {
				failure = translate(ex);
			}
		}
		try {
			closeSocketIfUnused();
		} catch (IOException ex) {
			failure = merge(failure, translate(ex));
		}
		if (failure != null) {
			throw failure;
		}
	}

	private void closeSocketIfUnused() throws IOException {
		if (!connectionClosed) {
			return;
		}
		boolean inputActive = inputOpened && !inputClosed;
		boolean outputActive = outputOpened && !outputClosed;
		if (!inputActive && !outputActive && socket != null && !socket.isClosed()) {
			socket.close();
		}
	}

	private IOException translate(IOException exception) {
		return translate(exception, timeouts);
	}

	private static IOException translate(IOException exception, boolean timeouts) {
		return GcfIoExceptionMapper.translate(exception, timeouts);
	}

	private static IOException merge(IOException first, IOException second) {
		if (first == null) {
			return second;
		}
		if (second != null && second != first) {
			first.addSuppressed(second);
		}
		return first;
	}

	private static void validateMode(int mode) {
		if (mode != Connector.READ && mode != Connector.WRITE && mode != Connector.READ_WRITE) {
			throw new IllegalArgumentException("Invalid connection mode: " + mode);
		}
	}

	private final class ManagedInputStream extends InputStream {
		private final InputStream input;
		private volatile boolean closed;

		ManagedInputStream(InputStream input) {
			this.input = input;
		}

		@Override
		public int read() throws IOException {
			ensureStreamOpen();
			try {
				return input.read();
			} catch (IOException ex) {
				throw translate(ex);
			}
		}

		@Override
		public int read(byte[] buffer, int offset, int length) throws IOException {
			ensureStreamOpen();
			try {
				return input.read(buffer, offset, length);
			} catch (IOException ex) {
				throw translate(ex);
			}
		}

		@Override
		public long skip(long count) throws IOException {
			ensureStreamOpen();
			try {
				return input.skip(count);
			} catch (IOException ex) {
				throw translate(ex);
			}
		}

		@Override
		public int available() throws IOException {
			ensureStreamOpen();
			try {
				return input.available();
			} catch (IOException ex) {
				throw translate(ex);
			}
		}

		@Override
		public void close() throws IOException {
			if (closed) {
				return;
			}
			closed = true;
			onInputClosed();
		}

		private void ensureStreamOpen() throws IOException {
			if (closed) {
				throw new IOException("Input stream is closed");
			}
		}
	}

	private final class ManagedOutputStream extends OutputStream {
		private final OutputStream output;
		private volatile boolean closed;

		ManagedOutputStream(OutputStream output) {
			this.output = output;
		}

		@Override
		public void write(int value) throws IOException {
			ensureStreamOpen();
			try {
				output.write(value);
			} catch (IOException ex) {
				throw translate(ex);
			}
		}

		@Override
		public void write(byte[] buffer, int offset, int length) throws IOException {
			ensureStreamOpen();
			try {
				output.write(buffer, offset, length);
			} catch (IOException ex) {
				throw translate(ex);
			}
		}

		@Override
		public void flush() throws IOException {
			ensureStreamOpen();
			try {
				output.flush();
			} catch (IOException ex) {
				throw translate(ex);
			}
		}

		@Override
		public void close() throws IOException {
			if (closed) {
				return;
			}
			closed = true;
			IOException failure = null;
			try {
				output.flush();
			} catch (IOException ex) {
				failure = translate(ex);
			}
			try {
				onOutputClosed();
			} catch (IOException ex) {
				failure = merge(failure, ex);
			}
			if (failure != null) {
				throw failure;
			}
		}

		private void ensureStreamOpen() throws IOException {
			if (closed) {
				throw new IOException("Output stream is closed");
			}
		}
	}
}
