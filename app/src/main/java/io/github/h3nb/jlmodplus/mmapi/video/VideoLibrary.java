// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.mmapi.video;

import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import io.github.h3nb.jlmodplus.mmapi.synth.Library;
import io.github.h3nb.jlmodplus.mmapi.synth.eas.LibEAS;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.microedition.media.MediaException;

/** File-video subresources for AudioPlayer; no nested guest Player or second audio sink. */
public final class VideoLibrary implements Library, VideoDisplay.SurfaceOwner {
    private static final AtomicInteger live = new AtomicInteger();
    private final VideoFormat source;
    private final VideoDisplay display;
    private final LibEAS audio;
    private final long audioHandle;
    private final ScheduledExecutorService worker =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread t = new Thread(r, "FileVideoWorker");
                        t.setDaemon(true);
                        return t;
                    });
    private final VideoTimeline clock = new VideoTimeline();
    private final ArrayDeque<long[]> events = new ArrayDeque<>();
    private MediaExtractor extractor;
    private MediaCodec codec;
    private Surface surface;
    private long surfaceRevision;
    private ScheduledFuture<?> tick;
    private volatile boolean closed;
    private boolean prepared, playing, suspended, inputEnd, videoEnd, audioEnd, ended;
    private long generation = 1,
            nativeGeneration,
            position,
            length,
            videoEndTime,
            lastVideoPts = -1,
            lastScheduled,
            requestEpoch;
    private int loops = 1, remaining = 1, pending = -1;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private long rendered,
            dropped,
            timestampSamples,
            fallbackSamples,
            worstSkew,
            sumSkew,
            maxUncertainty;
    private final long[] skewHistogram = new long[8];
    private long unmappedFrames,
            measuredGeneration = -1,
            firstSkew,
            lastSkew,
            firstPts,
            lastPts,
            maxDrift;

    public VideoLibrary(VideoFormat source) throws MediaException {
        this.source = source;
        length = source.duration;
        if (live.incrementAndGet() > 16) {
            live.decrementAndGet();
            worker.shutdown();
            throw new IllegalStateException("Video source limit reached (16)");
        }
        LibEAS backend = null;
        long handle = 0;
        try {
            if (source.audio) {
                backend = LibEAS.videoAudio();
                handle = backend.createPlayer(source.path);
                backend.setTimelineOrigin(handle, 0);
            }
            audio = backend;
            audioHandle = handle;
            audioEnd = audio == null;
            display = new VideoDisplay(source.width, source.height, this);
            clock.seek(0, System.nanoTime(), audio != null);
        } catch (Throwable e) {
            try {
                if (handle != 0) backend.close(handle);
            } finally {
                live.decrementAndGet();
                worker.shutdown();
            }
            if (e instanceof Error) throw (Error) e;
            if (e instanceof RuntimeException) throw (RuntimeException) e;
            if (e instanceof MediaException) throw (MediaException) e;
            throw new MediaException("Cannot create file video: " + e);
        }
    }

    private <T> T call(Callable<T> action) {
        if (closed) throw new IllegalStateException("Video source is closed");
        Future<T> operation = worker.submit(action);
        boolean interrupted = false;
        try {
            for (; ; ) {
                try {
                    return operation.get();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Error) throw (Error) cause;
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new IllegalStateException("Video operation failed", cause);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isSynthesis() {
        return false;
    }

    @Override
    public boolean requiresAudioFocus() {
        return audio != null;
    }

    @Override
    public VideoDisplay videoControl() {
        return display;
    }

    @Override
    public void setVideoSizeListener(Runnable listener) {
        display.setSizeListener(listener);
    }

    @Override
    public long createPlayer(String locator) {
        return 1;
    }

    @Override
    public void realize(long handle) {}

    @Override
    public String contentType(long handle) {
        return source.contentType;
    }

    @Override
    public String[] metadata(long handle) {
        return audio == null
                ? new String[] {"mimetype", source.contentType}
                : call(() -> audio.metadata(audioHandle));
    }

    @Override
    public long getOutputIdentity(long handle) {
        return audio == null ? 0 : audio.getOutputIdentity(audioHandle);
    }

    @Override
    public boolean outputFailed(long handle) {
        return audio != null && audio.outputFailed(audioHandle);
    }

    private void openVideo(long target) throws Exception {
        releaseCodec();
        if (extractor == null) {
            extractor = new MediaExtractor();
            extractor.setDataSource(source.path);
            extractor.selectTrack(source.track);
        }
        extractor.seekTo(target, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        MediaCodec next = MediaCodec.createByCodecName(source.decoder);
        try {
            next.configure(source.format, surface, null, 0);
            MediaCodec origin = next;
            long media = generation;
            next.setOnFrameRenderedListener(
                    (c, pts, nanos) -> {
                        try {
                            worker.execute(() -> recordRendered(origin, media, pts, nanos));
                        } catch (RejectedExecutionException ignored) {
                            /* Closed source. */
                        }
                    },
                    new Handler(Looper.getMainLooper()));
            next.start();
        } catch (Exception error) {
            try {
                next.release();
            } catch (Exception cleanup) {
                error.addSuppressed(cleanup);
            }
            throw new IllegalStateException("Cannot open video decoder " + source.decoder, error);
        }
        codec = next;
        inputEnd = false;
        videoEnd = false;
        pending = -1;
        videoEndTime = target;
        lastVideoPts = -1;
    }

    private void releaseCodec() {
        pending = -1;
        if (codec != null) {
            MediaCodec old = codec;
            codec = null;
            // release is valid even after an asynchronous codec error. stop is not.
            old.release();
        }
    }

    private void freeze() {
        long now = System.nanoTime();
        position = mediaTime(now);
        clock.pause(now);
        if (tick != null) {
            tick.cancel(false);
            tick = null;
        }
        if (codec != null) {
            codec.flush();
            pending = -1;
            inputEnd = false;
            extractor.seekTo(position, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        }
    }

    @Override
    public void prefetch(long handle) {
        call(
                () -> {
                    if (!prepared) {
                        if (audio != null) {
                            audio.prefetch(audioHandle);
                            nativeGeneration = audio.getGeneration(audioHandle);
                        }
                        openVideo(position);
                        prepared = true;
                    }
                    return null;
                });
    }

    @Override
    public void start(long handle) {
        start(handle, 0);
    }

    @Override
    public void start(long handle, long epoch) {
        call(
                () -> {
                    if (!prepared)
                        throw new IllegalStateException("Video source is not prefetched");
                    if (ended) {
                        seek(0);
                        remaining = loops;
                    }
                    ++generation;
                    events.clear();
                    ended = false;
                    playing = true;
                    requestEpoch = epoch;
                    if (audio != null) {
                        if (!audioEnd) audio.start(audioHandle, epoch);
                        nativeGeneration = audio.getGeneration(audioHandle);
                        suspended = !audioEnd && audio.isOutputSuspended(audioHandle);
                    } else suspended = false;
                    openVideo(position);
                    clock.start(System.nanoTime());
                    if (!suspended) schedule();
                    return null;
                });
    }

    private void schedule() {
        if (tick == null)
            tick = worker.scheduleWithFixedDelay(this::advance, 0, 4, TimeUnit.MILLISECONDS);
    }

    @Override
    public void pause(long handle) {
        call(
                () -> {
                    freeze();
                    playing = false;
                    suspended = false;
                    ++generation;
                    events.clear();
                    if (audio != null) {
                        audio.pause(audioHandle);
                        audio.setMediaTime(audioHandle, position);
                        nativeGeneration = audio.getGeneration(audioHandle);
                    }
                    return null;
                });
    }

    @Override
    public void suspendOutput(long handle) {
        call(
                () -> {
                    if (!suspended) {
                        freeze();
                        suspended = true;
                        if (!ended) ++generation;
                        if (audio != null) {
                            audio.suspendOutput(audioHandle);
                            if (!ended && !audioEnd) audio.setMediaTime(audioHandle, position);
                            nativeGeneration = audio.getGeneration(audioHandle);
                        }
                    }
                    return null;
                });
    }

    @Override
    public void resumeOutput(long handle) {
        call(
                () -> {
                    if (playing && suspended) {
                        if (audio != null) {
                            if (!audioEnd) audio.resumeOutput(audioHandle);
                            nativeGeneration = audio.getGeneration(audioHandle);
                            suspended = !audioEnd && audio.isOutputSuspended(audioHandle);
                        } else suspended = false;
                        if (!suspended) {
                            openVideo(position);
                            clock.start(System.nanoTime());
                            schedule();
                        }
                    }
                    return null;
                });
    }

    @Override
    public boolean isOutputSuspended(long handle) {
        return call(() -> suspended);
    }

    @Override
    public void recoverOutput(long handle) {
        call(
                () -> {
                    if (audio != null) audio.recoverOutput(audioHandle);
                    return null;
                });
    }

    @Override
    public void deallocate(long handle) {
        call(
                () -> {
                    freeze();
                    playing = false;
                    prepared = false;
                    ++generation;
                    events.clear();
                    releaseCodec();
                    if (extractor != null) {
                        extractor.release();
                        extractor = null;
                    }
                    if (audio != null) audio.deallocate(audioHandle);
                    return null;
                });
    }

    @Override
    public synchronized void close(long handle) {
        if (closed) return;
        try {
            call(
                    () -> {
                        if (tick != null) tick.cancel(false);
                        playing = false;
                        ++generation;
                        closed = true;
                        try {
                            releaseCodec();
                        } finally {
                            try {
                                if (extractor != null) extractor.release();
                            } finally {
                                try {
                                    if (surface != null) {
                                        surface.release();
                                        surface = null;
                                    }
                                } finally {
                                    if (audio != null) audio.close(audioHandle);
                                }
                            }
                        }
                        return null;
                    });
        } finally {
            display.close();
            worker.shutdown();
            live.decrementAndGet();
        }
    }

    private long seek(long target) throws Exception {
        boolean wasPlaying = playing && !suspended;
        if (tick != null) {
            tick.cancel(false);
            tick = null;
        }
        target = Math.max(0, target);
        if (length > 0) target = Math.min(target, length);
        boolean restartAudio = audioEnd;
        ++generation;
        events.clear();
        ended = false;
        audioEnd = audio == null;
        if (audio != null) {
            long audioLength = audio.getDuration(audioHandle);
            audio.setMediaTime(audioHandle, target);
            audioEnd = audioLength >= 0 && target >= audioLength;
            if (audioEnd) audio.pause(audioHandle);
            if (wasPlaying && restartAudio && !audioEnd) audio.start(audioHandle, requestEpoch);
            nativeGeneration = audio.getGeneration(audioHandle);
        }
        position = target;
        lastScheduled = target;
        clock.seek(target, System.nanoTime(), audio != null && !audioEnd);
        if (prepared) openVideo(target);
        if (wasPlaying) {
            clock.start(System.nanoTime());
            schedule();
        }
        return target;
    }

    @Override
    public long setMediaTime(long handle, long time) {
        return call(() -> seek(time));
    }

    private long mediaTime(long now) {
        if (audio != null && !audioEnd && playing && !suspended) {
            long[] stamp = audio.presentation(audioHandle);
            if (stamp[2] == nativeGeneration) clock.observeAudio(stamp[0], now);
        }
        return Math.max(0, clock.time(now));
    }

    @Override
    public long getMediaTime(long handle) {
        return call(() -> playing && !suspended ? mediaTime(System.nanoTime()) : position);
    }

    @Override
    public long getDuration(long handle) {
        return call(() -> length);
    }

    @Override
    public void setRepeat(long handle, int count) {
        call(
                () -> {
                    loops = remaining = count;
                    return null;
                });
    }

    @Override
    public void setVolume(long handle, float left, float right) {
        call(
                () -> {
                    if (audio != null) audio.setVolume(audioHandle, left, right);
                    return null;
                });
    }

    @Override
    public void setDataSource(long handle, byte[] data) {
        throw new IllegalStateException("File video has no ToneControl source");
    }

    @Override
    public int writeMIDI(long handle, byte[] data, int offset, int length) {
        return -1;
    }

    @Override
    public long getGeneration(long handle) {
        return call(() -> generation);
    }

    @Override
    public long[] pollEvent(long handle) {
        return call(
                () -> {
                    long[] event = events.poll();
                    if (event != null && event[0] == 1) {
                        boolean held = suspended;
                        seek(0);
                        ended = false;
                        playing = true;
                        if (audio != null) {
                            audio.start(audioHandle, requestEpoch);
                            nativeGeneration = audio.getGeneration(audioHandle);
                            suspended = audio.isOutputSuspended(audioHandle);
                        } else suspended = held;
                        if (!suspended) {
                            clock.start(System.nanoTime());
                            schedule();
                        }
                    }
                    return event;
                });
    }

    private void advance() {
        if (!playing || suspended || closed) return;
        try {
            long now = System.nanoTime();
            if (audio != null && !audioEnd) {
                long[] event;
                while ((event = audio.pollEvent(audioHandle)) != null) {
                    if (event[2] != nativeGeneration) continue;
                    int type = (int) event[0];
                    if (type == 2) {
                        audioEnd = true;
                        length = Math.max(length, event[1]);
                        clock.finishAudio(event[1], now);
                    } else if (type >= 3) {
                        events.add(
                                new long[] {
                                    type, event[1], generation, event.length > 3 ? event[3] : 0
                                });
                        if (type != 4) {
                            freeze();
                            playing = false;
                            return;
                        }
                    }
                }
                if (audio.isOutputSuspended(audioHandle) && !suspended) {
                    freeze();
                    suspended = true;
                    return;
                }
            }
            long time = mediaTime(now);
            if (!videoEnd && codec != null) decode(time, now);
            if (audioEnd && videoEnd && time >= videoEndTime && time >= lastScheduled) finish(now);
        } catch (Exception error) {
            Log.w("FileVideo", "Source decoder failed", error);
            events.add(new long[] {3, position, generation, 0});
            try {
                freeze();
            } catch (Exception ignored) {
            }
            playing = false;
        }
    }

    private void decode(long time, long now) throws Exception {
        if (!inputEnd) {
            int input = codec.dequeueInputBuffer(0);
            if (input >= 0) {
                ByteBuffer bytes = codec.getInputBuffer(input);
                int size = extractor.readSampleData(bytes, 0);
                if (size < 0) {
                    codec.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    inputEnd = true;
                } else {
                    if ((extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0)
                        throw new IllegalStateException("Encrypted video is unsupported");
                    codec.queueInputBuffer(input, 0, size, extractor.getSampleTime(), 0);
                    extractor.advance();
                }
            }
        }
        if (pending < 0) pending = codec.dequeueOutputBuffer(info, 0);
        if (pending < 0) return;
        boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
        if (info.size > 0) {
            long pts = info.presentationTimeUs;
            lastVideoPts = Math.max(lastVideoPts, pts);
            // Discard keyframe preroll and late output. One held buffer bounds lookahead.
            if (pts < position || VideoTimeline.late(pts, time, source.frameDuration)) {
                codec.releaseOutputBuffer(pending, false);
                dropped++;
            } else {
                long delay = VideoTimeline.delayNanos(pts, time);
                if (delay > 2000000L) return;
                if (surface != null) codec.releaseOutputBuffer(pending, now + delay);
                else codec.releaseOutputBuffer(pending, false);
                lastScheduled = pts;
            }
        } else codec.releaseOutputBuffer(pending, false);
        pending = -1;
        if (eos) {
            videoEnd = true;
            videoEndTime =
                    VideoTimeline.endTime(
                            lastVideoPts,
                            source.frameDuration,
                            source.format.containsKey(android.media.MediaFormat.KEY_DURATION)
                                    ? source.format.getLong(android.media.MediaFormat.KEY_DURATION)
                                    : -1,
                            videoEndTime);
        }
    }

    private void finish(long now) throws Exception {
        long end = Math.max(videoEndTime, audio == null ? 0 : audio.getDuration(audioHandle));
        if (audio != null) audio.pause(audioHandle);
        length = end;
        position = end;
        if (loops == -1 || --remaining > 0) {
            clock.pause(now);
            playing = false;
            ended = true;
            if (tick != null) {
                tick.cancel(false);
                tick = null;
            }
            events.add(new long[] {1, end, generation});
        } else {
            clock.pause(now);
            playing = false;
            ended = true;
            if (tick != null) {
                tick.cancel(false);
                tick = null;
            }
            events.add(new long[] {2, end, generation});
        }
    }

    @Override
    public void changeSurface(Surface next, SurfaceTexture obsolete, long revision) {
        try {
            worker.execute(
                    () -> {
                        try {
                            if (closed || revision < surfaceRevision) {
                                if (next != null) next.release();
                                return;
                            }
                            surfaceRevision = revision;
                            long time =
                                    playing && !suspended ? mediaTime(System.nanoTime()) : position;
                            releaseCodec();
                            if (surface != null) surface.release();
                            surface = next;
                            if (prepared) openVideo(time);
                        } catch (Exception error) {
                            events.add(new long[] {3, position, generation, 0});
                            Log.w("FileVideo", "Surface transition failed", error);
                        } finally {
                            if (obsolete != null) obsolete.release();
                        }
                    });
        } catch (RejectedExecutionException ignored) {
            if (next != null) next.release();
            if (obsolete != null) obsolete.release();
        }
    }

    private void recordRendered(MediaCodec origin, long media, long pts, long nanos) {
        if (closed || codec != origin || generation != media || suspended || !playing) return;
        long[] stamp = audio != null && !audioEnd ? audio.presentationAt(audioHandle, nanos) : null;
        if (stamp != null && (stamp[2] != nativeGeneration || stamp[7] == 0)) {
            unmappedFrames++;
            return;
        }
        long current = stamp != null ? stamp[0] : clock.time(nanos);
        long skew = current - pts;
        ++rendered;
        sumSkew += skew;
        worstSkew = Math.max(worstSkew, Math.abs(skew));
        int bucket = (int) Math.min(7, Math.abs(skew) / 10000);
        skewHistogram[bucket]++;
        if (measuredGeneration != media) {
            measuredGeneration = media;
            firstSkew = skew;
            firstPts = pts;
        }
        lastSkew = skew;
        lastPts = pts;
        maxDrift = Math.max(maxDrift, Math.abs(lastSkew - firstSkew));
        if (stamp != null) {
            if (stamp[4] != 0) timestampSamples++;
            else fallbackSamples++;
            maxUncertainty = Math.max(maxUncertainty, stamp[5]);
        }
    }

    /**
     * Qualification only: rendered/drop counts, skew and timestamp capability; no guest controls.
     */
    public long[] diagnostics() {
        return call(
                () ->
                        new long[] {
                            rendered,
                            dropped,
                            worstSkew,
                            rendered == 0 ? 0 : sumSkew / rendered,
                            timestampSamples,
                            fallbackSamples,
                            maxUncertainty,
                            generation,
                            skewHistogram[0],
                            skewHistogram[1],
                            skewHistogram[2],
                            skewHistogram[3],
                            skewHistogram[4],
                            skewHistogram[5],
                            skewHistogram[6],
                            skewHistogram[7],
                            unmappedFrames,
                            maxDrift,
                            lastSkew - firstSkew,
                            lastPts - firstPts
                        });
    }

    public long[] runtimeDiagnostics() {
        return audio == null ? new long[12] : call(() -> audio.runtimeDiagnostics(audioHandle));
    }
}
