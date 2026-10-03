// SPDX-License-Identifier: Apache-2.0
#include "pcm_decoder.h"
extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/channel_layout.h>
#include <libavutil/dict.h>
#include <libavutil/error.h>
#include <libavutil/mathematics.h>
#include <libswresample/swresample.h>
}
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <stdexcept>
#include <thread>
#include <sys/stat.h>

namespace mmapi::pcm {
namespace {
static_assert(std::atomic<int64_t>::is_always_lock_free, "PCM realtime clock requires lock-free 64-bit atomics");
constexpr int CHUNK = 1024, SLOTS = 16, MAX_OUTPUT_FRAMES = 262144;
constexpr AVRational US{1, 1000000};
void check(int result, const char *operation) {
    if (result >= 0) return;
    if (result == AVERROR(ENOMEM)) throw std::bad_alloc();
    char message[AV_ERROR_MAX_STRING_SIZE];
    av_strerror(result, message, sizeof(message));
    throw std::runtime_error(std::string(operation) + ": " + message);
}
bool retained(AVCodecID id) {
    switch (id) {
        case AV_CODEC_ID_PCM_U8: case AV_CODEC_ID_PCM_S16LE:
        case AV_CODEC_ID_PCM_S24LE: case AV_CODEC_ID_PCM_S32LE:
        case AV_CODEC_ID_PCM_F32LE:
        case AV_CODEC_ID_PCM_ALAW: case AV_CODEC_ID_PCM_MULAW:
        case AV_CODEC_ID_GSM_MS: case AV_CODEC_ID_ADPCM_IMA_WAV:
        case AV_CODEC_ID_MP3: case AV_CODEC_ID_AAC:
        case AV_CODEC_ID_AMR_NB: case AV_CODEC_ID_AMR_WB: return true;
        default: return false;
    }
}
}
struct Decoder::State {
    struct Chunk { std::array<float, CHUNK * 2> samples{}; int count = 0; int64_t pts = 0; };
    std::array<Chunk, SLOTS> ring;
    std::atomic<uint32_t> head{0}, tail{0};
    int cursor = 0; // consumer only; reset only after detaching/joining
    std::atomic<int64_t> position{0}, consumed{0}, underflow{0}, audible{0};
    std::atomic<int64_t> length{-1};
    int64_t streamStart = 0, seekTarget = 0;
    bool trimDeclaredWave = false;
    bool durationEstimated = false;
    std::vector<std::string> tags;
    FILE *file = nullptr;
    int64_t fileSize = 0;
    AVIOContext *io = nullptr;
    AVFormatContext *format = nullptr;
    AVCodecContext *codec = nullptr;
    SwrContext *resampler = nullptr;
    AVFrame *frame = nullptr;
    AVPacket *packet = nullptr;
    int streamIndex = -1;
    int inputRate = 0, inputChannels = 0, inputFormat = -1;
    std::atomic<bool> stop{false}, cancelIO{false}, finished{true}, eof{false};
    std::atomic<int> failure{0}, workers{0};
    std::thread worker;
    bool demuxEnd = false, decoderEnd = false, sentEnd = false;
    bool havePacket = false;
    std::vector<float> staging;
    int stagingFrames = 0, stagingCursor = 0;
    int64_t stagingPts = 0, nextPts = 0, anchorPts = 0, producedFrames = 0;
    bool havePts = false;
    ~State() {
        cancelIO.store(true); stop.store(true);
        if (worker.joinable()) worker.join();
        av_packet_free(&packet); av_frame_free(&frame);
        swr_free(&resampler); avcodec_free_context(&codec);
        avformat_close_input(&format);
        if (io) { av_freep(&io->buffer); avio_context_free(&io); }
        if (file) fclose(file);
    }
    static int readIO(void *opaque, uint8_t *buffer, int size) {
        auto &s = *static_cast<State *>(opaque);
        if (s.cancelIO.load(std::memory_order_acquire)) return AVERROR_EXIT;
        size_t count = fread(buffer, 1, static_cast<size_t>(size), s.file);
        if (count) return static_cast<int>(count);
        return ferror(s.file) ? AVERROR(EIO) : AVERROR_EOF;
    }
    static int64_t seekIO(void *opaque, int64_t offset, int whence) {
        auto &s = *static_cast<State *>(opaque);
        if (s.cancelIO.load(std::memory_order_acquire)) return AVERROR_EXIT;
        if (whence == AVSEEK_SIZE) return s.fileSize;
        whence &= ~AVSEEK_FORCE;
        if (whence != SEEK_SET && whence != SEEK_CUR && whence != SEEK_END) return AVERROR(EINVAL);
        int64_t base = whence == SEEK_CUR ? ftello(s.file) : whence == SEEK_END ? s.fileSize : 0;
        if (base < 0 || base > s.fileSize || offset < -base || offset > s.fileSize - base) return AVERROR(EINVAL);
        // The owned file is bounded to 64 MiB, including on 32-bit ABIs.
        if (fseeko(s.file, base + offset, SEEK_SET)) return AVERROR(errno);
        return ftello(s.file);
    }
    static int interrupted(void *opaque) {
        return static_cast<State *>(opaque)->cancelIO.load(std::memory_order_acquire);
    }
    void open(const std::string &path) {
        file = fopen(path.c_str(), "rb");
        if (!file) throw std::runtime_error("Cannot open sampled cache");
        struct stat info{};
        if (fstat(fileno(file), &info) || !S_ISREG(info.st_mode) || info.st_size < 0
                || static_cast<uint64_t>(info.st_size) > MEDIA_LIMIT)
            throw std::runtime_error("Sampled cache must be a regular file within 64 MiB");
        fileSize = info.st_size;
        // Bind recognized containers to their parser. Corrupt WAV/AMR/MP4 or
        // framed MPEG audio must not be rescued by a different probe result.
        unsigned char header[16]{};
        const size_t headerSize = fread(header, 1, sizeof(header), file);
        rewind(file);
        const char *recognized = nullptr;
        if (headerSize >= 4 && !memcmp(header, "RIFF", 4)) {
            if (headerSize < 12 || memcmp(header + 8, "WAVE", 4))
                throw std::runtime_error("Unsupported or corrupt sampled RIFF container");
            recognized = "wav";
        } else if (headerSize >= 5 && !memcmp(header, "#!AMR", 5)) recognized = "amr";
        else if (headerSize >= 8 && !memcmp(header + 4, "ftyp", 4)) recognized = "mov";
        else if (headerSize >= 2 && header[0] == 255) {
            if ((header[1] & 246) == 240) recognized = "aac";
            else if ((header[1] & 224) == 224 && (header[1] & 6) != 0) recognized = "mp3";
        }
        auto *buffer = static_cast<unsigned char *>(av_malloc(32768));
        if (!buffer) throw std::bad_alloc();
        io = avio_alloc_context(buffer, 32768, 0, this, readIO, nullptr, seekIO);
        if (!io) { av_free(buffer); throw std::bad_alloc(); }
        format = avformat_alloc_context();
        if (!format) throw std::bad_alloc();
        format->pb = io; format->flags |= AVFMT_FLAG_CUSTOM_IO;
        format->interrupt_callback = {interrupted, this};
        // Probe only the configured retained demuxers, never protocols/network IO.
        format->probesize = 1024 * 1024; format->max_analyze_duration = 5000000;
        format->max_streams = 16;
        format->max_index_size = 1024 * 1024;
        check(avformat_open_input(&format, nullptr, recognized ? av_find_input_format(recognized) : nullptr, nullptr),
            "Recognize sampled audio");
        check(avformat_find_stream_info(format, nullptr), "Read sampled stream information");
        streamIndex = av_find_best_stream(format, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
        check(streamIndex, "Find audio stream");
        auto *stream = format->streams[streamIndex];
        if (!retained(stream->codecpar->codec_id)) throw std::runtime_error("Sampled codec is outside retained audio support");
        if (stream->codecpar->extradata_size > 65536) throw std::runtime_error("Sampled codec configuration exceeds 64 KiB");
        const AVCodec *implementation = avcodec_find_decoder(stream->codecpar->codec_id);
        if (!implementation) throw std::runtime_error("Retained sampled decoder is not built");
        codec = avcodec_alloc_context3(implementation);
        if (!codec) throw std::bad_alloc();
        check(avcodec_parameters_to_context(codec, stream->codecpar), "Configure sampled decoder");
        codec->pkt_timebase = stream->time_base;
        codec->thread_count = 1;
        check(avcodec_open2(codec, implementation, nullptr), "Open sampled decoder");
        if (codec->sample_rate <= 0 || codec->sample_rate > 384000
                || codec->ch_layout.nb_channels < 1 || codec->ch_layout.nb_channels > 8)
            throw std::runtime_error("Sampled stream exceeds rate/channel limits");
        inputRate = codec->sample_rate; inputChannels = codec->ch_layout.nb_channels;
        inputFormat = codec->sample_fmt;
        AVChannelLayout stereo{};
        av_channel_layout_default(&stereo, CHANNELS);
        check(swr_alloc_set_opts2(&resampler, &stereo, AV_SAMPLE_FMT_FLT, RATE,
            &codec->ch_layout, codec->sample_fmt, codec->sample_rate, 0, nullptr), "Configure sampled resampler");
        if (codec->ch_layout.nb_channels == 1) {
            const double mono[2] = {1, 1};
            check(swr_set_matrix(resampler, mono, 1), "Preserve mono channel gain");
        }
        check(swr_init(resampler), "Initialize sampled resampler");
        streamStart = stream->start_time == AV_NOPTS_VALUE ? 0 : av_rescale_q(stream->start_time, stream->time_base, US);
        if (stream->duration != AV_NOPTS_VALUE) length = av_rescale_q(stream->duration, stream->time_base, US);
        else if (format->duration != AV_NOPTS_VALUE) length = format->duration;
        durationEstimated = format->duration_estimation_method == AVFMT_DURATION_FROM_BITRATE;
        if (durationEstimated) length.store(-1);
        // WAV's sample count excludes the padding in its final ADPCM block.
        // A bitrate estimate (e.g. raw AAC) cannot safely truncate decoded PCM.
        trimDeclaredWave = length >= 0 && !strcmp(format->iformat->name, "wav");
        auto copyTags = [&](AVDictionary *dictionary) {
            AVDictionaryEntry *tag = nullptr;
            while ((tag = av_dict_get(dictionary, "", tag, AV_DICT_IGNORE_SUFFIX))) {
                if (tags.size() >= 128) break;
                tags.emplace_back(tag->key, std::min<size_t>(strlen(tag->key), 256));
                tags.emplace_back(tag->value, std::min<size_t>(strlen(tag->value), 4096));
            }
        };
        copyTags(format->metadata); copyTags(stream->metadata);
        packet = av_packet_alloc(); frame = av_frame_alloc();
        if (!packet || !frame) throw std::bad_alloc();
    }
    void join() {
        stop.store(true, std::memory_order_release);
        if (worker.joinable()) worker.join();
    }
    bool queueStaging() {
        auto write = head.load(std::memory_order_relaxed);
        if (write - tail.load(std::memory_order_acquire) == SLOTS) return false;
        Chunk &chunk = ring[write % SLOTS];
        chunk.count = std::min(CHUNK, stagingFrames - stagingCursor);
        chunk.pts = stagingPts + static_cast<int64_t>(stagingCursor) * 1000000 / RATE;
        std::copy_n(staging.data() + stagingCursor * 2, chunk.count * 2, chunk.samples.data());
        stagingCursor += chunk.count;
        head.store(write + 1, std::memory_order_release);
        return true;
    }
    void convert(bool drain) {
        if (!drain && ((frame->sample_rate > 0 && frame->sample_rate != inputRate)
                || frame->ch_layout.nb_channels != inputChannels || frame->format != inputFormat))
            throw std::runtime_error("Sampled stream changed its PCM configuration");
        int capacity = drain ? swr_get_out_samples(resampler, 0)
            : swr_get_out_samples(resampler, frame->nb_samples);
        check(capacity, "Calculate sampled PCM capacity");
        if (capacity > MAX_OUTPUT_FRAMES) throw std::runtime_error("Decoded sampled frame exceeds PCM limit");
        staging.resize(static_cast<size_t>(capacity) * 2);
        uint8_t *output = reinterpret_cast<uint8_t *>(staging.data());
        int64_t pts = nextPts;
        if (!drain && frame->best_effort_timestamp != AV_NOPTS_VALUE) {
            pts = av_rescale_q(frame->best_effort_timestamp, format->streams[streamIndex]->time_base, US) - streamStart
                - swr_get_delay(resampler, codec->sample_rate) * 1000000 / codec->sample_rate;
        }
        stagingFrames = swr_convert(resampler, &output, capacity,
            drain ? nullptr : const_cast<const uint8_t **>(frame->extended_data), drain ? 0 : frame->nb_samples);
        check(stagingFrames, "Resample sampled audio");
        for (int i = 0; i < stagingFrames * CHANNELS; ++i)
            if (!std::isfinite(staging[i])) staging[i] = 0;
        stagingCursor = 0;
        // Codec timestamps anchor consumed PCM. Decode-ahead never updates position.
        if (!havePts) { anchorPts = pts; producedFrames = 0; havePts = true; }
        stagingPts = anchorPts + producedFrames * 1000000 / RATE;
        producedFrames += stagingFrames;
        nextPts = anchorPts + producedFrames * 1000000 / RATE;
        if (trimDeclaredWave && nextPts > length) stagingFrames = static_cast<int>(std::max<int64_t>(0,
            std::min<int64_t>(stagingFrames, av_rescale_rnd(length.load() - stagingPts, RATE, 1000000, AV_ROUND_DOWN))));
        if (stagingPts < seekTarget) stagingCursor = static_cast<int>(std::min<int64_t>(stagingFrames,
            av_rescale_rnd(seekTarget - stagingPts, RATE, 1000000, AV_ROUND_UP)));
    }
    bool decode() {
        if (decoderEnd) {
            convert(true);
            if (!stagingFrames) {
                // Compressed container durations can include encoder priming and
                // padding. After drain, decoded timestamps are authoritative.
                if (!trimDeclaredWave || length.load() < 0)
                    length.store(havePts ? std::max<int64_t>(0, nextPts) : 0, std::memory_order_release);
                eof.store(true, std::memory_order_release); return false;
            }
            return true;
        }
        for (;;) {
            if (stop.load(std::memory_order_acquire)) return false;
            int result = avcodec_receive_frame(codec, frame);
            if (result >= 0) {
                if (frame->flags & AV_FRAME_FLAG_CORRUPT) throw std::runtime_error("Corrupt sampled frame");
                convert(false); av_frame_unref(frame); return true;
            }
            if (result == AVERROR_EOF) { decoderEnd = true; return decode(); }
            if (result != AVERROR(EAGAIN)) check(result, "Decode sampled frame");
            if (havePacket) {
                result = avcodec_send_packet(codec, packet);
                if (result == AVERROR(EAGAIN)) continue;
                check(result, "Submit sampled packet");
                av_packet_unref(packet); havePacket = false;
            } else if (demuxEnd) {
                if (sentEnd) throw std::runtime_error("Sampled decoder failed to drain");
                check(avcodec_send_packet(codec, nullptr), "Drain sampled decoder"); sentEnd = true;
            } else {
                do {
                    av_packet_unref(packet);
                    result = av_read_frame(format, packet);
                } while (result >= 0 && packet->stream_index != streamIndex && !stop.load());
                if (result >= 0 && packet->stream_index != streamIndex) {
                    av_packet_unref(packet); return false;
                }
                if (result == AVERROR_EOF) demuxEnd = true;
                else {
                    check(result, "Read sampled packet");
                    if (packet->flags & AV_PKT_FLAG_CORRUPT) throw std::runtime_error("Corrupt sampled packet");
                    havePacket = true;
                }
            }
        }
    }
    void run(bool continuous) {
        if (worker.joinable()) {
            if (!finished.load(std::memory_order_acquire)) return;
            worker.join();
        }
        if (eof.load() || failure.load()) return;
        stop.store(false); finished.store(false);
        worker = std::thread([this, continuous] {
            workers.fetch_add(1);
            try {
                while (!stop.load(std::memory_order_acquire)) {
                    if (stagingCursor < stagingFrames) {
                        if (!queueStaging()) {
                            if (!continuous) break; // no idle prefetch worker
                            std::this_thread::sleep_for(std::chrono::milliseconds(2));
                        }
                    } else if (!decode()) break;
                }
            } catch (const std::bad_alloc &) { failure.store(AVERROR(ENOMEM), std::memory_order_release); }
              catch (...) { failure.store(AVERROR_INVALIDDATA, std::memory_order_release); }
            workers.fetch_sub(1); finished.store(true, std::memory_order_release);
        });
    }
};
Decoder::Decoder(const std::string &path) : state(std::make_unique<State>()) { state->open(path); }
Decoder::~Decoder() = default;
void Decoder::prefetch() {
    state->run(false);
    auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(2);
    // The bounded prefetch worker exits at a full ring or EOF. One available
    // chunk is insufficient startup headroom when the decoder is rescheduled.
    while (!state->finished.load(std::memory_order_acquire)) {
        if (std::chrono::steady_clock::now() >= deadline) { state->join(); throw std::runtime_error("Sampled prefetch timed out"); }
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    check(state->failure.load(), "Prefetch sampled audio");
}
void Decoder::resume() { state->join(); state->run(true); }
void Decoder::pause() { state->join(); }
void Decoder::deallocate() { state->join(); seek(time()); }
int64_t Decoder::seek(int64_t microseconds) {
    state->join();
    microseconds = std::max<int64_t>(0, microseconds);
    if (state->length >= 0) microseconds = std::min(microseconds, state->length.load());
    auto *stream = state->format->streams[state->streamIndex];
    int64_t target = av_rescale_q(microseconds + state->streamStart, US, stream->time_base);
    check(avformat_seek_file(state->format, state->streamIndex, INT64_MIN, target, target, AVSEEK_FLAG_BACKWARD), "Seek sampled audio");
    avcodec_flush_buffers(state->codec); av_packet_unref(state->packet); av_frame_unref(state->frame);
    swr_close(state->resampler); check(swr_init(state->resampler), "Flush sampled resampler");
    state->head.store(0); state->tail.store(0); state->cursor = 0;
    state->stagingFrames = state->stagingCursor = 0; state->havePts = false;
    state->demuxEnd = state->decoderEnd = state->sentEnd = state->havePacket = false;
    state->eof.store(false); state->failure.store(0); state->seekTarget = microseconds;
    state->position.store(microseconds); state->nextPts = microseconds;
    return microseconds;
}
int Decoder::read(float *bus, int frames, float left, float right) {
    int copied = 0;
    int64_t audible = 0;
    while (copied < frames) {
        auto read = state->tail.load(std::memory_order_relaxed);
        if (read == state->head.load(std::memory_order_acquire)) break;
        auto &chunk = state->ring[read % SLOTS];
        int count = std::min(frames - copied, chunk.count - state->cursor);
        for (int i = 0; i < count; ++i) {
            const float l = chunk.samples[(state->cursor + i) * 2] * left;
            const float r = chunk.samples[(state->cursor + i) * 2 + 1] * right;
            bus[(copied + i) * 2] += l; bus[(copied + i) * 2 + 1] += r;
            audible += l != 0; audible += r != 0;
        }
        copied += count; state->cursor += count;
        state->position.store(std::max<int64_t>(0, chunk.pts + static_cast<int64_t>(state->cursor) * 1000000 / RATE));
        if (state->cursor == chunk.count) { state->cursor = 0; state->tail.store(read + 1, std::memory_order_release); }
    }
    state->audible.fetch_add(audible, std::memory_order_relaxed);
    state->consumed.fetch_add(copied, std::memory_order_relaxed);
    if (copied < frames && !state->eof.load(std::memory_order_acquire) && !state->failure.load())
        state->underflow.fetch_add(frames - copied, std::memory_order_relaxed);
    return copied;
}
bool Decoder::drained() const { return state->eof.load(std::memory_order_acquire) && state->tail.load() == state->head.load(); }
int Decoder::error() const { return state->failure.load(std::memory_order_acquire); }
int64_t Decoder::time() const { return state->position.load(); }
int64_t Decoder::duration() const { return state->length; }
int64_t Decoder::audibleSamples() const { return state->audible.load(); }
std::string Decoder::contentType() const {
    const char *format = state->format->iformat->name;
    if (!strcmp(format, "wav")) return "audio/wav";
    if (!strcmp(format, "mp3")) return "audio/mpeg";
    if (!strcmp(format, "aac")) return "audio/aac";
    if (!strcmp(format, "amr")) return state->codec->codec_id == AV_CODEC_ID_AMR_WB ? "audio/amr-wb" : "audio/amr";
    return "audio/mp4";
}
std::vector<std::string> Decoder::metadata() const {
    auto tags = state->tags;
    tags.insert(tags.end(), {"mimetype", contentType(), "samplerate", std::to_string(state->inputRate),
        "channels", std::to_string(state->inputChannels)});
    if (duration() >= 0) tags.insert(tags.end(), {"duration", std::to_string(duration() / 1000)});
    return tags;
}
std::array<int64_t, 4> Decoder::diagnostics() const {
    auto queued = std::min<uint32_t>(SLOTS, state->head.load() - state->tail.load());
    return {state->consumed.load(), state->underflow.load(), queued * CHUNK, state->workers.load()};
}
}
