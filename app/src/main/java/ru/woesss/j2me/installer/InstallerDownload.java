/* Licensed under the Apache License, Version 2.0.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0. */
package ru.woesss.j2me.installer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;

/** Request-owned downloads. A partial response never becomes an installer source. */
final class InstallerDownload {
    interface Progress { void update(long bytes, long total); }
    interface Cancellation { boolean isCancelled(); }

    static URI resolve(URI base, String reference) throws IOException {
        try {
            URI resolved = base == null ? new URI(reference) : base.resolve(new URI(reference));
            String scheme = resolved.getScheme();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) ||
                    resolved.getHost() == null) throw new IOException("Unsupported download URL");
            return resolved;
        } catch (URISyntaxException | IllegalArgumentException error) {
            throw new IOException("Invalid download URL", error);
        }
    }

    static URI download(URI source, File destination, Cancellation cancelled, Progress progress)
            throws IOException {
        File partial = new File(destination.getPath() + ".part");
        Set<URI> visited = new HashSet<>();
        URI current = resolve(null, source.toString());
        try {
            for (int redirects = 0; redirects <= 10; redirects++) {
                checkCancelled(cancelled);
                if (!visited.add(current)) throw new IOException("Download redirect loop");
                HttpURLConnection connection = (HttpURLConnection) new URL(current.toString()).openConnection();
                try {
                    connection.setInstanceFollowRedirects(false);
                    connection.setConnectTimeout(15_000);
                    // A stalled read must not leave cancellation waiting for minutes.
                    connection.setReadTimeout(15_000);
                    connection.setRequestProperty("Accept-Encoding", "identity");
                    int code = connection.getResponseCode();
                    if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                        String location = connection.getHeaderField("Location");
                        if (location == null || location.isEmpty()) throw new IOException("Redirect has no location");
                        current = resolve(current, location);
                        continue;
                    }
                    if (code != HttpURLConnection.HTTP_OK) throw new IOException("Download failed (HTTP " + code + ")");
                    long total = -1;
                    try { total = Long.parseLong(connection.getHeaderField("Content-Length")); }
                    catch (NumberFormatException ignored) { }
                    long bytes = 0;
                    try (InputStream input = connection.getInputStream();
                            FileOutputStream output = new FileOutputStream(partial, false)) {
                        byte[] buffer = new byte[16 * 1024];
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            checkCancelled(cancelled);
                            if (count == 0) continue;
                            output.write(buffer, 0, count);
                            bytes += count;
                            progress.update(bytes, total);
                        }
                    }
                    checkCancelled(cancelled);
                    if (bytes == 0 || (total >= 0 && total != bytes)) throw new IOException("Incomplete download");
                    if (destination.exists() && !destination.delete()) throw new IOException("Unable to replace download scratch");
                    if (!partial.renameTo(destination)) throw new IOException("Unable to finish download scratch");
                    return current;
                } finally {
                    connection.disconnect();
                }
            }
            throw new IOException("Too many download redirects");
        } finally {
            // Scratch's owner also removes its directory on completion and after stale requests.
            if (partial.exists()) partial.delete();
        }
    }

    private static void checkCancelled(Cancellation cancelled) throws InterruptedIOException {
        if (cancelled.isCancelled() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Download cancelled");
        }
    }

    private InstallerDownload() { }
}
