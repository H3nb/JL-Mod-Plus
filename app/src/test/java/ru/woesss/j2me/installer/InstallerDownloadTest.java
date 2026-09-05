package ru.woesss.j2me.installer;

import java.net.ServerSocket;
import java.net.Socket;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class InstallerDownloadTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void resolvesRootRelativeAndQueryWithoutInheritingJadQuery() throws Exception {
        URI base = URI.create("https://example.com/catalog/app.jad?old=1");
        assertEquals("https://example.com/game.jar?key=2",
                InstallerDownload.resolve(base, "/game.jar?key=2").toString());
        assertEquals("https://example.com/game.jar",
                InstallerDownload.resolve(base, "../game.jar").toString());
        assertThrows(IOException.class, () -> InstallerDownload.resolve(base, "file:///tmp/game.jar"));
    }

    @Test public void followsRelativeRedirectAndReturnsFinalJadLocation() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 2, java.net.InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(5000);
            Thread responder = new Thread(() -> {
                try {
                    for (int i = 0; i < 2; i++) try (Socket socket = server.accept()) {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                        while (!reader.readLine().isEmpty()) { }
                        String response = i == 0
                                ? "HTTP/1.1 302 Found\r\nLocation: /moved/app.jad\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                : "HTTP/1.1 200 OK\r\nContent-Length: 18\r\nConnection: close\r\n\r\nMIDlet-Name: Test!";
                        socket.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
                    }
                } catch (IOException error) { throw new RuntimeException(error); }
            });
            responder.start();
            File target = new File(temporary.getRoot(), "source.jad");
            URI finalUri = InstallerDownload.download(
                    URI.create("http://localhost:" + server.getLocalPort() + "/start"),
                    target, () -> false, (bytes, total) -> {});
            assertEquals("/moved/app.jad", finalUri.getPath());
            assertEquals("MIDlet-Name: Test!", Files.readString(target.toPath()));
            assertFalse(new File(target + ".part").exists());
            responder.join(5000);
            assertFalse(responder.isAlive());
        }
    }

    @Test public void cancellationNeverPublishesSource() throws Exception {
        File target = new File(temporary.getRoot(), "source.jar");
        assertThrows(IOException.class, () -> InstallerDownload.download(
                URI.create("http://127.0.0.1/unused"), target, () -> true, (bytes, total) -> {}));
        assertFalse(target.exists());
        assertFalse(new File(target + ".part").exists());
    }

    @Test public void truncatedResponseAndRedirectLoopNeverPublishSources() throws Exception {
        assertRejectedResponse("HTTP/1.1 200 OK\r\nContent-Length: 200\r\nConnection: close\r\n\r\nshort");
        assertRejectedResponse("HTTP/1.1 302 Found\r\nLocation: /start\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
    }

    private void assertRejectedResponse(String response) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(5000);
            Thread responder = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    while (!reader.readLine().isEmpty()) { }
                    socket.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
                } catch (IOException error) { throw new RuntimeException(error); }
            });
            responder.setDaemon(true);
            responder.start();
            File target = new File(temporary.getRoot(), "rejected.jar");
            assertThrows(IOException.class, () -> InstallerDownload.download(
                    URI.create("http://localhost:" + server.getLocalPort() + "/start"),
                    target, () -> false, (bytes, total) -> {}));
            assertFalse(target.exists());
            assertFalse(new File(target + ".part").exists());
            responder.join(5000);
            assertFalse(responder.isAlive());
        }
    }
}
