/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.microemu.cldc.http;

import static org.junit.Assert.assertEquals;

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
			List<String> requests = new ArrayList<>();
			FutureTask<Void> task = startServer(server, requests, 200, "OK");

			Connection connection = new Connection();
			connection.openConnection(
					"http://127.0.0.1:" + server.getLocalPort() + "/login?world=1",
					Connector.READ_WRITE,
					false);
			assertEquals("world=1", connection.getQuery());
			assertEquals("identity", connection.getRequestProperty("Accept-Encoding"));

			OutputStream output = connection.openOutputStream();
			output.write("abc".getBytes(StandardCharsets.US_ASCII));
			output.close();
			assertEquals(HttpConnection.POST, connection.getRequestMethod());
			assertEquals(200, connection.getResponseCode());
			assertEquals("OK", readAll(connection.openInputStream()));
			connection.close();

			task.get(3, TimeUnit.SECONDS);
			assertEquals(1, requests.size());
			assertEquals("POST /login?world=1 HTTP/1.1", requests.get(0));
		}
	}

	@Test
	public void redirectIsExposedToMidletInsteadOfFollowed() throws Exception {
		try (ServerSocket server = loopbackServer()) {
			List<String> requests = new ArrayList<>();
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
			List<String> requests = new ArrayList<>();
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
			ServerSocket server, List<String> requests, int status, String body) {
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

	private static void handleRequest(
			Socket socket, List<String> requests, int status, String body) throws IOException {
		try (Socket connection = socket) {
			connection.setSoTimeout(2000);
			BufferedReader reader = new BufferedReader(
					new InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII));
			String requestLine = reader.readLine();
			requests.add(requestLine);

			int contentLength = 0;
			String line;
			while ((line = reader.readLine()) != null && line.length() > 0) {
				if (line.regionMatches(true, 0, "Content-Length:", 0, "Content-Length:".length())) {
					contentLength = Integer.parseInt(line.substring("Content-Length:".length()).trim());
				}
			}
			for (int i = 0; i < contentLength; i++) {
				reader.read();
			}

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
}
