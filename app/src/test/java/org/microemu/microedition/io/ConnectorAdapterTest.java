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
import static org.junit.Assert.fail;

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
		RecordingAdapter adapter = new RecordingAdapter(new FakeConnection());

		InputStream input = adapter.openInputStream("test://resource");

		assertEquals(Connector.READ, adapter.lastMode);
		assertTrue(((FakeConnection) adapter.connection).closed);
		assertEquals(1, ((FakeConnection) adapter.connection).rawInputCalls);
		assertEquals(0, ((FakeConnection) adapter.connection).dataInputCalls);
		assertEquals(7, input.read());
	}

	@Test
	public void outputConvenienceUsesWriteModeAndClosesConnectionObject() throws Exception {
		RecordingAdapter adapter = new RecordingAdapter(new FakeConnection());

		OutputStream output = adapter.openOutputStream("test://resource");

		assertEquals(Connector.WRITE, adapter.lastMode);
		assertTrue(((FakeConnection) adapter.connection).closed);
		assertEquals(1, ((FakeConnection) adapter.connection).rawOutputCalls);
		assertEquals(0, ((FakeConnection) adapter.connection).dataOutputCalls);
		output.write(7);
	}

	@Test
	public void typeMismatchStillClosesOpenedConnection() throws Exception {
		CloseOnlyConnection connection = new CloseOnlyConnection();
		RecordingAdapter adapter = new RecordingAdapter(connection);

		try {
			adapter.openInputStream("test://resource");
			fail("Expected IOException");
		} catch (IOException expected) {
			assertTrue(connection.closed);
		}
	}

	private static final class RecordingAdapter extends ConnectorAdapter {
		int lastMode;
		final Connection connection;

		RecordingAdapter(Connection connection) {
			this.connection = connection;
		}

		@Override
		public Connection open(String name, int mode, boolean timeouts) {
			lastMode = mode;
			return connection;
		}
	}

	private static final class CloseOnlyConnection implements Connection {
		boolean closed;

		@Override
		public void close() {
			closed = true;
		}
	}

	private static final class FakeConnection implements InputConnection, OutputConnection {
		boolean closed;
		int rawInputCalls;
		int dataInputCalls;
		int rawOutputCalls;
		int dataOutputCalls;

		@Override
		public InputStream openInputStream() {
			rawInputCalls++;
			return new ByteArrayInputStream(new byte[]{7});
		}

		@Override
		public DataInputStream openDataInputStream() {
			dataInputCalls++;
			return new DataInputStream(new ByteArrayInputStream(new byte[]{7}));
		}

		@Override
		public OutputStream openOutputStream() {
			rawOutputCalls++;
			return new ByteArrayOutputStream();
		}

		@Override
		public DataOutputStream openDataOutputStream() {
			dataOutputCalls++;
			return new DataOutputStream(new ByteArrayOutputStream());
		}

		@Override
		public void close() {
			closed = true;
		}
	}
}
