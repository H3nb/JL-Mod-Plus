/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.microemu.microedition.io;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import javax.microedition.io.Connection;
import javax.microedition.io.Connector;
import javax.microedition.io.InputConnection;
import javax.microedition.io.OutputConnection;

import org.junit.Test;

public class ConnectorAdapterTest {
	@Test
	public void inputConvenienceUsesReadModeAndClosesConnectionObject() throws Exception {
		RecordingAdapter adapter = new RecordingAdapter();

		InputStream input = adapter.openInputStream("test://resource");

		assertEquals(Connector.READ, adapter.lastMode);
		assertTrue(adapter.connection.closed);
		assertEquals(7, input.read());
	}

	@Test
	public void outputConvenienceUsesWriteModeAndClosesConnectionObject() throws Exception {
		RecordingAdapter adapter = new RecordingAdapter();

		OutputStream output = adapter.openOutputStream("test://resource");

		assertEquals(Connector.WRITE, adapter.lastMode);
		assertTrue(adapter.connection.closed);
		output.write(7);
	}

	private static final class RecordingAdapter extends ConnectorAdapter {
		int lastMode;
		final FakeConnection connection = new FakeConnection();

		@Override
		public Connection open(String name, int mode, boolean timeouts) {
			lastMode = mode;
			return connection;
		}
	}

	private static final class FakeConnection implements InputConnection, OutputConnection {
		boolean closed;

		@Override
		public InputStream openInputStream() {
			return new ByteArrayInputStream(new byte[]{7});
		}

		@Override
		public DataInputStream openDataInputStream() {
			return new DataInputStream(openInputStream());
		}

		@Override
		public OutputStream openOutputStream() {
			return new ByteArrayOutputStream();
		}

		@Override
		public DataOutputStream openDataOutputStream() {
			return new DataOutputStream(openOutputStream());
		}

		@Override
		public void close() throws IOException {
			closed = true;
		}
	}
}
