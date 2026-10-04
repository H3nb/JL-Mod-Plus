// SPDX-License-Identifier: Apache-2.0
#include "legacy_smaf.h"
#include "pcm_decoder.h"
extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/error.h>
#include <libswresample/swresample.h>
}
#include <chrono>
#include <algorithm>
#include <cstdio>
#include <cstring>
#include <stdexcept>
#include <vector>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

namespace mmapi::pcm {
namespace {
void check(int result, const char *operation) {
    if (result >= 0) return;
    if (result == AVERROR(ENOMEM)) throw std::bad_alloc();
    char text[AV_ERROR_MAX_STRING_SIZE];
    av_strerror(result, text, sizeof(text));
    throw std::runtime_error(std::string(operation) + ": " + text);
}
struct Conversion {
    FILE *input = nullptr, *output = nullptr;
    AVIOContext *io = nullptr;
    AVFormatContext *format = nullptr;
    AVCodecContext *codec = nullptr;
    SwrContext *resampler = nullptr;
    AVPacket *packet = nullptr;
    AVFrame *frame = nullptr;
    int64_t size = 0;
    uint32_t written = 0;
    int channels = 0;
    std::chrono::steady_clock::time_point deadline =
        std::chrono::steady_clock::now() + std::chrono::seconds(10);
    ~Conversion() {
        av_frame_free(&frame); av_packet_free(&packet);
        swr_free(&resampler); avcodec_free_context(&codec);
        avformat_close_input(&format);
        if (io) { av_freep(&io->buffer); avio_context_free(&io); }
        if (input) fclose(input);
        if (output) fclose(output);
    }
    void budget() {
        if (interrupted(this)) throw std::runtime_error("Legacy SMAF conversion timed out");
    }
    static int interrupted(void *opaque) {
        return std::chrono::steady_clock::now() > static_cast<Conversion *>(opaque)->deadline;
    }
    static int read(void *opaque, uint8_t *buffer, int count) {
        auto &self = *static_cast<Conversion *>(opaque);
        if (interrupted(opaque)) return AVERROR_EXIT;
        size_t actual = fread(buffer, 1, static_cast<size_t>(count), self.input);
        if (actual) return static_cast<int>(actual);
        return ferror(self.input) ? AVERROR(EIO) : AVERROR_EOF;
    }
    static int64_t seek(void *opaque, int64_t offset, int whence) {
        auto &self = *static_cast<Conversion *>(opaque);
        if (interrupted(opaque)) return AVERROR_EXIT;
        if (whence == AVSEEK_SIZE) return self.size;
        whence &= ~AVSEEK_FORCE;
        if (whence != SEEK_SET && whence != SEEK_CUR && whence != SEEK_END) return AVERROR(EINVAL);
        int64_t base = whence == SEEK_CUR ? ftello(self.input) : whence == SEEK_END ? self.size : 0;
        if (base < 0 || base > self.size || offset < -base || offset > self.size - base) return AVERROR(EINVAL);
        return fseeko(self.input, base + offset, SEEK_SET) ? AVERROR(errno) : ftello(self.input);
    }
    void header() {
        uint8_t bytes[44]{};
        auto u16 = [&](int at, uint16_t value) { bytes[at] = value; bytes[at + 1] = value >> 8; };
        auto u32 = [&](int at, uint32_t value) {
            for (int i = 0; i < 4; ++i) bytes[at + i] = value >> (8 * i);
        };
        memcpy(bytes, "RIFF", 4); u32(4, 36 + written + (written & 1));
        memcpy(bytes + 8, "WAVEfmt ", 8); u32(16, 16); u16(20, 1);
        u16(22, channels); u32(24, 16000); u32(28, 16000 * channels);
        u16(32, channels); u16(34, 8); memcpy(bytes + 36, "data", 4); u32(40, written);
        if (fseek(output, 0, SEEK_SET) || fwrite(bytes, 1, sizeof(bytes), output) != sizeof(bytes))
            throw std::runtime_error("Cannot write legacy SMAF WAV header");
    }
    void convert(bool drain) {
        budget();
        if (!drain && (frame->sample_rate != codec->sample_rate
            || frame->format != codec->sample_fmt || frame->nb_samples < 0 || frame->nb_samples > 262144
            || av_channel_layout_compare(&frame->ch_layout, &codec->ch_layout)))
            throw std::runtime_error("Legacy SMAF changed audio configuration");
        int capacity = swr_get_out_samples(resampler, drain ? 0 : frame->nb_samples);
        if (capacity < 0 || capacity > 262144) throw std::runtime_error("Legacy SMAF frame exceeds limit");
        std::vector<uint8_t> pcm(static_cast<size_t>(std::max(capacity, 1)) * channels);
        uint8_t *destination = pcm.data();
        int samples = swr_convert(resampler, &destination, capacity,
            drain ? nullptr : const_cast<const uint8_t **>(frame->extended_data), drain ? 0 : frame->nb_samples);
        check(samples, "Resample legacy SMAF");
        size_t count = static_cast<size_t>(samples) * channels;
        if (count > Decoder::MEDIA_LIMIT - 45 - written) throw std::runtime_error("Legacy SMAF WAV exceeds 64 MiB");
        if (fwrite(pcm.data(), 1, count, output) != count) throw std::runtime_error("Cannot write legacy SMAF PCM");
        written += static_cast<uint32_t>(count);
    }
    void receive() {
        for (;;) {
            budget();
            int result = avcodec_receive_frame(codec, frame);
            if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) return;
            check(result, "Decode legacy SMAF");
            convert(false); av_frame_unref(frame);
        }
    }
    bool run(const std::string &source, const std::string &destination) {
        input = fopen(source.c_str(), "rb");
        if (!input) throw std::runtime_error("Cannot open legacy SMAF cache");
        struct stat info{};
        if (fstat(fileno(input), &info) || !S_ISREG(info.st_mode) || info.st_size < 0
            || static_cast<uint64_t>(info.st_size) > Decoder::MEDIA_LIMIT)
            throw std::runtime_error("Legacy SMAF input exceeds regular-file/64 MiB limits");
        size = info.st_size;
        char magic[4];
        if (fread(magic, 1, 4, input) != 4 || memcmp(magic, "MMMD", 4)) return false;
        rewind(input);
        uint8_t *buffer = static_cast<uint8_t *>(av_malloc(32768));
        if (!buffer) throw std::bad_alloc();
        io = avio_alloc_context(buffer, 32768, 0, this, read, nullptr, seek);
        if (!io) { av_free(buffer); throw std::bad_alloc(); }
        format = avformat_alloc_context();
        if (!format) throw std::bad_alloc();
        format->pb = io; format->flags |= AVFMT_FLAG_CUSTOM_IO;
        format->interrupt_callback = {interrupted, this};
        format->probesize = 1024 * 1024; format->max_analyze_duration = 5000000;
        format->max_streams = 16; format->max_index_size = 1024 * 1024;
        check(avformat_open_input(&format, nullptr, av_find_input_format("mmf"), nullptr), "Read legacy SMAF");
        check(avformat_find_stream_info(format, nullptr), "Inspect legacy SMAF");
        int index = av_find_best_stream(format, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
        if (index == AVERROR_STREAM_NOT_FOUND) return false;
        check(index, "Find legacy SMAF waveform");
        auto *parameters = format->streams[index]->codecpar;
        // This is the ADPCM waveform supported by the MMF demuxer, not SMAF sequence synthesis.
        if (parameters->codec_id != AV_CODEC_ID_ADPCM_YAMAHA) return false;
        if (parameters->extradata_size > 65536) throw std::runtime_error("Legacy SMAF configuration exceeds 64 KiB");
        const AVCodec *implementation = avcodec_find_decoder(parameters->codec_id);
        if (!implementation) throw std::runtime_error("Legacy SMAF decoder unavailable");
        codec = avcodec_alloc_context3(implementation);
        if (!codec) throw std::bad_alloc();
        check(avcodec_parameters_to_context(codec, parameters), "Configure legacy SMAF");
        codec->thread_count = 1;
        check(avcodec_open2(codec, implementation, nullptr), "Open legacy SMAF decoder");
        channels = codec->ch_layout.nb_channels;
        if (codec->sample_rate <= 0 || codec->sample_rate > 384000 || channels < 1 || channels > 2)
            throw std::runtime_error("Legacy SMAF exceeds rate/channel limits");
        check(swr_alloc_set_opts2(&resampler, &codec->ch_layout, AV_SAMPLE_FMT_U8, 16000,
            &codec->ch_layout, codec->sample_fmt, codec->sample_rate, 0, nullptr), "Configure legacy SMAF resampler");
        check(swr_init(resampler), "Initialize legacy SMAF resampler");
        int fd = open(destination.c_str(), O_WRONLY | O_NOFOLLOW);
        if (fd < 0) throw std::runtime_error("Cannot open legacy SMAF temporary output");
        struct stat target{};
        if (fstat(fd, &target) || !S_ISREG(target.st_mode) || target.st_size != 0
            || (target.st_dev == info.st_dev && target.st_ino == info.st_ino)) {
            close(fd); throw std::runtime_error("Legacy SMAF output must be an empty owned regular file");
        }
        output = fdopen(fd, "wb");
        if (!output) { close(fd); throw std::runtime_error("Cannot write legacy SMAF output"); }
        header();
        packet = av_packet_alloc(); frame = av_frame_alloc();
        if (!packet || !frame) throw std::bad_alloc();
        for (;;) {
            budget();
            int result = av_read_frame(format, packet);
            if (result == AVERROR_EOF) break;
            check(result, "Read legacy SMAF packet");
            if (packet->stream_index == index) {
                check(avcodec_send_packet(codec, packet), "Submit legacy SMAF packet"); receive();
            }
            av_packet_unref(packet);
        }
        check(avcodec_send_packet(codec, nullptr), "Drain legacy SMAF decoder"); receive();
        convert(true);
        if (!written) throw std::runtime_error("Legacy SMAF waveform is empty");
        if (written & 1) { const uint8_t zero = 0; if (fwrite(&zero, 1, 1, output) != 1) throw std::runtime_error("Cannot pad legacy SMAF WAV"); }
        header();
        if (fflush(output)) throw std::runtime_error("Cannot flush legacy SMAF WAV");
        FILE *completed = output; output = nullptr;
        if (fclose(completed)) throw std::runtime_error("Cannot close legacy SMAF WAV");
        return true;
    }
};
}
bool convertLegacySmaf(const std::string &input, const std::string &output) {
    Conversion conversion;
    return conversion.run(input, output);
}
}
