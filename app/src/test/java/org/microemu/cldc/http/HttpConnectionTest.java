/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.microemu.cldc.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import javax.microedition.io.Connector;
import javax.microedition.io.HttpConnection;

import org.junit.Test;

public class HttpConnectionTest {
	@Test
	public void outputAndResponseUseOneHttpTransaction() throws Exception {
		try (ServerSocket server = loopbackServer()) {
			List<CapturedRequest> requests = new ArrayList<>();
			FutureTask<Void> task = startServer(server, requests, 200, "OK");

			Connection connection = new Connection();
			connection.openConnection(
					"http://127.0.0.1:" + server.getLocalPort() + "/login?world=1",
					Connector.READ_WRITE,
					false);
			assertEquals("world=1", connection.getQuery());
			assertEquals("identity", connection.getRequestProperty("Accept-Encoding"));
			connection.setRequestProperty("Content-Length", "3");

			OutputStream output = connection.openOutputStream();
			output.write("abc".getBytes(StandardCharsets.US_ASCII));
			output.close();
			assertEquals(HttpConnection.POST, connection.getRequestMethod());
			assertEquals(200, connection.getResponseCode());
			assertEquals("OK", readAll(connection.openInputStream()));
			connection.close();

			task.get(3, TimeUnit.SECONDS);
			assertEquals(1, requests.size());
			assertEquals("POST /login?world=1 HTTP/1.1", requests.get(0).requestLine);
			assertEquals("abc", requests.get(0).body);
		}
	}

	@Test
	public void connectorModeDoesNotRestrictHttpDirections() throws Exception {
		try (ServerSocket server = loopbackServer()) {
			List<CapturedRequest> requests = new ArrayList<>();
			FutureTask<Void> task = startServer(server, requests, 200, "reply");

			Connection connection = new Connection();
			connection.openConnection(
					"http://127.0.0.1:" + server.getLocalPort() + "/mode",
					Connector.READ,
					false);
			connection.setRequestProperty("Content-Length", "1");
			OutputStream output = connection.openOutputStream();
			output.write('x');
			output.close();
			assertEquals("reply", readAll(connection.openInputStream()));
			connection.close();

			task.get(3, TimeUnit.SECONDS);
			assertEquals("x", requests.get(0).body);
		}
	}

	@Test
	public void outputStateFollowsMidpFlushAndConnectedTransitions() throws Exception {
		try (ServerSocket server = loopbackServer()) {
			List<CapturedRequest> requests = new ArrayList<>();
			FutureTask<Void> task = startServer(server, requests, 200, "done");

			Connection connection = new Connection();
			connection.openConnection(
					"http://127.0.0.1:" + server.getLocalPort() + "/state",
					Connector.READ_WRITE,
					false);
			connection.setRequestProperty("Content-Length", "2");
			OutputStream output = connection.openOutputStream();

			connection.setRequestProperty("X-Ignored", "before-flush");
			assertEquals(null, connection.getRequestProperty("X-Ignored"));

			output.write('a');
			output.flush();

			try {
				connection.setRequestProperty("X-Too-Late", "after-flush");
				fail("Expected IOException after request parameters are sent");
			} catch (IOException expected) {
				// MIDP setters fail after request parameters have been sent.
			}

			output.write('b');
			assertEquals(200, connection.getResponseCode());
			try {
				output.write('c');
				fail("Expected output stream to be closed after entering Connected state");
			} catch (IOException expected) {
				// Response access finalizes the request and closes the output stream.
			}

			assertEquals("done", readAll(connection.openInputStream()));
			connection.close();

			task.get(3, TimeUnit.SECONDS);
			assertEquals("ab", requests.get(0).body);
		}
	}

	@Test
	public void explicitGetWithOutputUsesAndroidCompatiblePostPromotion() throws Exception {
		try (ServerSocket server = loopbackServer()) {
			List<CapturedRequest> requests = new ArrayList<>();
			FutureTask<Void> task = startServer(server, requests, 200, "");

			Connection connection = new Connection();
			connection.openConnection(
					"http://127.0.0.1:" + server.getLocalPort() + "/get-body",
					Connector.READ_WRITE,
					false);
			connection.setRequestMethod(HttpConnection.GET);
			connection.setRequestProperty("Content-Length", "1");
			OutputStream output = connection.openOutputStream();
			output.write('g');
			output.close();
			connection.getResponseCode();
			assertEquals(HttpConnection.POST, connection.getRequestMethod());
			connection.close();

			task.get(3, TimeUnit.SECONDS);
			assertEquals("POST /get-body HTTP/1.1", requests.get(0).requestLine);
			assertEquals("g", requests.get(0).body);
		}
	}

	@Test
	public void urlMetadataKeepsPathAndQuerySeparate() throws Exception {
		Connection connection = new Connection();
		connection.openConnection(
				"http://127.0.0.1/path/file?world=1#fragment",
				Connector.READ,
				false);
		try {
			assertEquals("/path/file", connection.getFile());
			assertEquals("world=1", connection.getQuery());
			assertEquals("fragment", connection.getRef());
		} finally {
			connection.close();
		}
	}

	@Test
	public void missingHttpHostIsRejectedDuringOpen() throws Exception {
		try {
			new Connection().openConnection("http:///path", Connector.READ, false);
			fail("Expected IllegalArgumentException");
		} catch (IllegalArgumentException expected) {
			// MIDP requires an HTTP host to be present.
		}
	}

	@Test
	public void contentLengthSupportsValuesAboveIntegerRange() throws Exception {
		try (ServerSocket server = loopbackServer()) {
			long declaredLength = 4294967296L;
			FutureTask<Void> task = startLengthServer(server, declaredLength);

			Connection connection = new Connection();
			connection.openConnection(
					"http://127.0.0.1:" + server.getLocalPort() + "/large",
					Connector.READ,
					false);
			assertEquals(declaredLength, connection.getLength());
			connection.close();

			task.get(3, TimeUnit.SECONDS);
		}
	}

	@Test
	public void redirectIsExposedToMidletInsteadOfFollowed() throws Exception {
		try (ServerSocket server = loopbackServer()) {
			List<CapturedRequest> requests = new ArrayList<>();
			FutureTask<Void> task = startServer(server, requests, 302, "");

			Connection connection = new Connection();
			connection.openConnection(
					"http://127.0.0.1:" + server.getLocalPort() + "/redirect",
					Connector.READ,
					false);
			assertEquals(302, connection.getResponseCode());
			connection.close();

			task.get(3, TimeUnit.SECONDS);
			assertEquals(1, requests.size());
		}
	}

	@Test
	public void errorResponseBodyRemainsReadable() throws Exception {
		try (ServerSocket server = loopbackServer()) {
			List<CapturedRequest> requests = new ArrayList<>();
			FutureTask<Void> task = startServer(server, requests, 404, "missing");

			Connection connection = new Connection();
			connection.openConnection(
					"http://127.0.0.1:" + server.getLocalPort() + "/missing",
					Connector.READ,
					false);
			assertEquals("missing", readAll(connection.openInputStream()));
			connection.close();

			task.get(3, TimeUnit.SECONDS);
		}
	}

	private static FutureTask<Void> startServer(
			ServerSocket server, List<CapturedRequest> requests, int status, String body) {
		FutureTask<Void> task = new FutureTask<>(() -> {
			handleRequest(server.accept(), requests, status, body);
			server.setSoTimeout(300);
			try (Socket extra = server.accept()) {
				handleRequest(extra, requests, status, body);
			} catch (SocketTimeoutException expected) {
				// One HttpConnection should not create a second transaction.
			}
			return null;
		});
		Thread thread = new Thread(task, "j2me-http-test-server");
		thread.setDaemon(true);
		thread.start();
		return task;
	}

	private static FutureTask<Void> startLengthServer(ServerSocket server, long declaredLength) {
		FutureTask<Void> task = new FutureTask<>(() -> {
			try (Socket connection = server.accept()) {
				connection.setSoTimeout(2000);
				BufferedReader reader = new BufferedReader(
						new InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII));
				String line;
				while ((line = reader.readLine()) != null && line.length() > 0) {
					// Consume request headers before sending response.
				}

				String headers = "HTTP/1.1 200 OK\r\n"
						+ "Content-Length: " + declaredLength + "\r\n"
						+ "Connection: close\r\n\r\n";
				OutputStream output = connection.getOutputStream();
				output.write(headers.getBytes(StandardCharsets.US_ASCII));
				output.flush();
			}
			return null;
		});
		Thread thread = new Thread(task, "j2me-http-length-test-server");
		thread.setDaemon(true);
		thread.start();
		return task;
	}

	private static void handleRequest(
			Socket socket, List<CapturedRequest> requests, int status, String body) throws IOException {
		try (Socket connection = socket) {
			connection.setSoTimeout(2000);
			BufferedReader reader = new BufferedReader(
					new InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII));
			String requestLine = reader.readLine();

			int contentLength = 0;
			String line;
			while ((line = reader.readLine()) != null && line.length() > 0) {
				if (line.regionMatches(true, 0, "Content-Length:", 0, "Content-Length:".length())) {
					contentLength = Integer.parseInt(line.substring("Content-Length:".length()).trim());
				}
			}
			StringBuilder requestBody = new StringBuilder(contentLength);
			for (int i = 0; i < contentLength; i++) {
				int value = reader.read();
				if (value == -1) {
					break;
				}
				requestBody.append((char) value);
			}
			requests.add(new CapturedRequest(requestLine, requestBody.toString()));

			byte[] payload = body.getBytes(StandardCharsets.US_ASCII);
			String reason = status == 200 ? "OK" : status == 302 ? "Found" : "Not Found";
			String headers = "HTTP/1.1 " + status + " " + reason + "\r\n"
					+ (status == 302 ? "Location: /target\r\n" : "")
					+ "Content-Length: " + payload.length + "\r\n"
					+ "Connection: close\r\n\r\n";
			OutputStream output = connection.getOutputStream();
			output.write(headers.getBytes(StandardCharsets.US_ASCII));
			output.write(payload);
			output.flush();
		}
	}

	private static ServerSocket loopbackServer() throws IOException {
		ServerSocket server = new ServerSocket(0, 2, InetAddress.getLoopbackAddress());
		server.setSoTimeout(2000);
		return server;
	}

	private static String readAll(InputStream input) throws IOException {
		try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			byte[] buffer = new byte[64];
			int read;
			while ((read = stream.read(buffer)) != -1) {
				output.write(buffer, 0, read);
			}
			return output.toString(StandardCharsets.US_ASCII.name());
		}
	}

	private static final class CapturedRequest {
		final String requestLine;
		final String body;

		CapturedRequest(String requestLine, String body) {
			this.requestLine = requestLine;
			this.body = body;
		}
	}
}
