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

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

import javax.microedition.io.Connector;

public class SocketConnection implements javax.microedition.io.SocketConnection {

	protected Socket socket;
	private int mode = Connector.READ_WRITE;
	private boolean connectionClosed;
	private boolean inputOpened;
	private boolean inputClosed;
	private boolean outputOpened;
	private boolean outputClosed;

	public SocketConnection() {
	}

	public SocketConnection(String host, int port) throws IOException {
		this(host, port, Connector.READ_WRITE);
	}

	public SocketConnection(String host, int port, int mode) throws IOException {
		initialize(new Socket(host, port), mode);
	}

	public SocketConnection(Socket socket) {
		this(socket, Connector.READ_WRITE);
	}

	public SocketConnection(Socket socket, int mode) {
		initialize(socket, mode);
	}

	protected final void initialize(Socket socket, int mode) {
		if (mode != Connector.READ && mode != Connector.WRITE && mode != Connector.READ_WRITE) {
			throw new IllegalArgumentException("Invalid connection mode: " + mode);
		}
		if (socket == null) {
			throw new NullPointerException("socket");
		}
		this.socket = socket;
		this.mode = mode;
		connectionClosed = false;
		inputOpened = false;
		inputClosed = false;
		outputOpened = false;
		outputClosed = false;
	}

	@Override
	public String getAddress() throws IOException {
		ensureConnectionOpen();
		return socket.getInetAddress().getHostAddress();
	}

	@Override
	public String getLocalAddress() throws IOException {
		ensureConnectionOpen();
		return socket.getLocalAddress().getHostAddress();
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
				return socket.getTcpNoDelay() ? 1 : 0;
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
		switch (option) {
			case DELAY:
				socket.setTcpNoDelay(value != 0);
				break;
			case LINGER:
				if (value < 0) {
					throw new IllegalArgumentException("Negative linger");
				}
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
		if (mode == Connector.WRITE) {
			throw new IOException("Connection is write-only");
		}
		if (inputOpened) {
			throw new IOException("Input stream already opened");
		}
		inputOpened = true;
		return new ManagedInputStream(socket.getInputStream());
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
		outputOpened = true;
		return new ManagedOutputStream(socket.getOutputStream());
	}

	@Override
	public DataOutputStream openDataOutputStream() throws IOException {
		return new DataOutputStream(openOutputStream());
	}

	private void ensureConnectionOpen() throws IOException {
		if (connectionClosed || socket == null || socket.isClosed()) {
			throw new IOException("Connection is closed");
		}
	}

	private synchronized void onInputClosed() throws IOException {
		if (inputClosed) {
			return;
		}
		inputClosed = true;
		if (!socket.isClosed() && !socket.isInputShutdown()) {
			socket.shutdownInput();
		}
		closeSocketIfUnused();
	}

	private synchronized void onOutputClosed() throws IOException {
		if (outputClosed) {
			return;
		}
		outputClosed = true;
		if (!socket.isClosed() && !socket.isOutputShutdown()) {
			socket.shutdownOutput();
		}
		closeSocketIfUnused();
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
		}

		@Override
		public void close() throws IOException {
			if (closed) {
				return;
			}
			output.flush();
			closed = true;
			onOutputClosed();
		}

		private void ensureStreamOpen() throws IOException {
			if (closed) {
				throw new IOException("Output stream is closed");
			}
		}
	}
}
