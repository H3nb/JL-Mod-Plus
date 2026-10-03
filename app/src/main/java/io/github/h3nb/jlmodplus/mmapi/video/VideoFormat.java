// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.mmapi.video;

import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.RandomAccessFile;
import java.nio.ByteBuffer;

import javax.microedition.media.MediaException;

/** Bounded ISO-BMFF routing; MIME/extension never decides whether video is present. */
public final class VideoFormat {
    final String path, decoder, contentType;
    final MediaFormat format;
    final int track, width, height;
    final boolean audio;
    final long duration, frameDuration;

    private VideoFormat(
            String path,
            String decoder,
            MediaFormat format,
            int track,
            boolean audio,
            long duration,
            String type) {
        this.path = path;
        this.decoder = decoder;
        this.format = format;
        this.track = track;
        this.audio = audio;
        this.duration = duration;
        contentType = type;
        width = format.getInteger(MediaFormat.KEY_WIDTH);
        height = format.getInteger(MediaFormat.KEY_HEIGHT);
        int fps =
                format.containsKey(MediaFormat.KEY_FRAME_RATE)
                        ? format.getInteger(MediaFormat.KEY_FRAME_RATE)
                        : 15;
        frameDuration = 1000000L / Math.max(1, Math.min(120, fps));
    }

    public static VideoFormat inspect(String path) throws MediaException {
        try (RandomAccessFile file = new RandomAccessFile(path, "r")) {
            if (file.length() < 12) return null;
            int brand = 0;
            long offset = 0;
            while (offset + 12 <= Math.min(file.length(), 65536)) {
                file.seek(offset);
                long size = Integer.toUnsignedLong(file.readInt());
                int box = file.readInt();
                if (box == 0x66747970) {
                    brand = file.readInt();
                    break;
                }
                if (size == 1) {
                    if (offset + 16 > file.length()) break;
                    size = file.readLong();
                }
                if (size < 8 || size > file.length() - offset) break;
                offset += size;
            }
            if (brand == 0) return null;
            if (file.length() > 64L * 1024 * 1024)
                throw new MediaException("Video cache exceeds 64 MiB");
            MediaExtractor extractor = new MediaExtractor();
            try {
                extractor.setDataSource(path);
                if (extractor.getTrackCount() > 16)
                    throw new MediaException("Video container exceeds 16 tracks");
                int selected = -1;
                MediaFormat video = null;
                boolean audio = false;
                long duration = 0;
                for (int i = 0; i < extractor.getTrackCount(); ++i) {
                    MediaFormat format = extractor.getTrackFormat(i);
                    String mime = format.getString(MediaFormat.KEY_MIME);
                    if (mime == null) throw new MediaException("Container track has no MIME");
                    audio |= mime.startsWith("audio/");
                    if (mime.startsWith("audio/") || mime.startsWith("video/"))
                        duration =
                                VideoTimeline.duration(
                                        duration,
                                        format.containsKey(MediaFormat.KEY_DURATION)
                                                ? format.getLong(MediaFormat.KEY_DURATION)
                                                : -1);
                    if (mime.startsWith("video/")) {
                        if (selected >= 0)
                            throw new MediaException("Multiple video tracks are unsupported");
                        selected = i;
                        video = format;
                    }
                }
                if (video == null) return null; // Audio-only MP4 remains on the PCM path.
                String mime = video.getString(MediaFormat.KEY_MIME);
                if (!"video/mp4v-es".equals(mime)
                        && !"video/3gpp".equals(mime)
                        && !"video/avc".equals(mime))
                    throw new MediaException(
                            "Video codec is outside file playback support: " + mime);
                int w = video.getInteger(MediaFormat.KEY_WIDTH),
                        h = video.getInteger(MediaFormat.KEY_HEIGHT);
                if (w <= 0 || h <= 0 || w > 1920 || h > 1080)
                    throw new MediaException("Video dimensions exceed 1920x1080");
                for (int i = 0; i < 3; ++i)
                    if (video.containsKey("csd-" + i)) {
                        ByteBuffer csd = video.getByteBuffer("csd-" + i);
                        if (csd != null && csd.remaining() > 65536)
                            throw new MediaException("Video configuration exceeds 64 KiB");
                    }
                MediaFormat capability = MediaFormat.createVideoFormat(mime, w, h);
                if (video.containsKey(MediaFormat.KEY_PROFILE))
                    capability.setInteger(
                            MediaFormat.KEY_PROFILE, video.getInteger(MediaFormat.KEY_PROFILE));
                if (video.containsKey(MediaFormat.KEY_BIT_RATE))
                    capability.setInteger(
                            MediaFormat.KEY_BIT_RATE, video.getInteger(MediaFormat.KEY_BIT_RATE));
                // Legacy MPEG-4 SP clips can declare QCIF level 0 despite larger
                // pictures. Query actual dimensions/throughput and supported
                // profile; configure the decoder with the original CSD below.
                // Android also applies a 12 fps lower capability bound to MPEG-4.
                if (!"video/mp4v-es".equals(mime) && video.containsKey(MediaFormat.KEY_LEVEL))
                    capability.setInteger(
                            MediaFormat.KEY_LEVEL, video.getInteger(MediaFormat.KEY_LEVEL));
                if (video.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    int fps = video.getInteger(MediaFormat.KEY_FRAME_RATE);
                    capability.setInteger(
                            MediaFormat.KEY_FRAME_RATE,
                            "video/mp4v-es".equals(mime) ? Math.max(12, fps) : fps);
                }
                String decoder =
                        new MediaCodecList(MediaCodecList.REGULAR_CODECS)
                                .findDecoderForFormat(capability);
                if (decoder == null)
                    throw new MediaException("Device has no compatible video decoder: " + mime);
                return new VideoFormat(
                        path,
                        decoder,
                        video,
                        selected,
                        audio,
                        duration,
                        (brand >>> 8) == 0x336770 ? "video/3gpp" : "video/mp4");
            } finally {
                extractor.release();
            }
        } catch (MediaException e) {
            throw e;
        } catch (Exception e) {
            throw new MediaException("Cannot inspect file video: " + e);
        }
    }
}
