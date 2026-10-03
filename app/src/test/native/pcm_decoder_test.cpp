// SPDX-License-Identifier: Apache-2.0
#include "pcm_decoder.h"
#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dirent.h>
#include <stdexcept>
#include <thread>

using mmapi::pcm::Decoder;
namespace {
void require(bool condition, const char *message) { if (!condition) throw std::runtime_error(message); }
int descriptors() {
    DIR *directory = opendir("/proc/self/fd");
    if (!directory) throw std::runtime_error("Cannot count owned descriptors");
    int count = 0;
    while (readdir(directory)) ++count;
    closedir(directory);
    return count;
}
int consume(Decoder &decoder, int frames, double &energy) {
    std::array<float, 1024 * 2 + 2> audio{};
    audio.front() = audio.back() = 12345;
    audio[1 + frames * 2] = 12345;
    int read = decoder.read(audio.data() + 1, frames, 1, 0.5f);
    require(audio.front() == 12345 && audio[1 + frames * 2] == 12345 && audio.back() == 12345,
        "PCM consumer wrote outside bus");
    for (int i = 0; i < frames; ++i) {
        float left = audio[1 + i * 2], right = audio[2 + i * 2];
        require(std::isfinite(left) && std::isfinite(right), "Nonfinite decoded PCM");
        require(std::abs(right - left * 0.5f) < 0.00001, "Mono conversion/source pan differs");
        energy += static_cast<double>(left) * left;
    }
    return read;
}
void fixture(const char *path) {
    int before = descriptors();
    int64_t finalFrames = 0, finalDuration = 0;
    double totalEnergy = 0;
    {
        Decoder decoder(path);
        require(decoder.time() == 0, "Decoded lookahead changed initial cursor");
        require(consume(decoder, 257, totalEnergy) == 0 && !decoder.drained() && decoder.time() == 0,
            "PCM underflow advanced clock or reported EOS");
        require(decoder.duration() == -1 || (decoder.duration() >= 900000 && decoder.duration() <= 1300000),
            "Fixture duration out of bounds");
        decoder.prefetch();
        decoder.pause();
        require(decoder.diagnostics()[3] == 0, "Prefetched source retained idle worker");
        require(decoder.time() == 0, "Prefetch advanced consumed timeline");
        decoder.resume();
        auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(4);
        int iteration = 0;
        while (!decoder.drained()) {
            require(!decoder.error(), "Fixture decoder failed");
            int count = (++iteration % 2) ? 257 : 511;
            finalFrames += consume(decoder, count, totalEnergy);
            require(std::chrono::steady_clock::now() < deadline, "EOS drain timed out");
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
        decoder.pause();
        int64_t expectedFrames = strstr(path, "effect.aac") ? 45159 : 44100;
        require(finalFrames == expectedFrames, "Decoder/resampler/staging tail lost or duplicated");
        require(totalEnergy > 1, "Fixture produced silent output");
        require(decoder.time() >= 950000, "Consumed PCM cursor did not reach media end");
        finalDuration = decoder.duration();
        decoder.seek(500000);
        decoder.prefetch(); decoder.pause();
        require(decoder.time() == 500000, "Seek timeline changed by decode-ahead");
        consume(decoder, 257, totalEnergy);
        require(decoder.time() >= 500000 && decoder.time() <= 508000, "Seek/flush leaked old PCM timestamps");
        int64_t frozen = decoder.time();
        decoder.deallocate();
        require(decoder.time() == frozen && decoder.duration() == finalDuration, "Deallocate lost known time/duration");
        decoder.prefetch(); decoder.resume();
        consume(decoder, 511, totalEnergy);
        decoder.pause();
        require(decoder.time() >= frozen, "Re-prefetch rewound source");
    }
    require(descriptors() == before, "Decoder leaked cached-file descriptors");
    for (int i = 0; i < 8; ++i) { Decoder decoder(path); decoder.prefetch(); decoder.resume(); }
    require(descriptors() == before, "Close during producer IO leaked resources");
    std::printf("PASS %s frames=%lld duration=%lld energy=%.3f repeatedClose=8\n", path,
        static_cast<long long>(finalFrames), static_cast<long long>(finalDuration), totalEnergy);
}
void sidFixture(const char *path, int64_t expectedFrames) {
    Decoder decoder(path);
    decoder.prefetch(); decoder.resume();
    double energy = 0;
    int64_t frames = 0;
    auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(5);
    while (!decoder.drained()) {
        require(!decoder.error(), "AMR SID/NO_DATA decoder failed");
        frames += consume(decoder, 511, energy);
        require(std::chrono::steady_clock::now() < deadline, "AMR SID/NO_DATA drain timed out");
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    decoder.pause();
    std::printf("SID/NO_DATA observed %s frames=%lld expected=%lld time=%lld duration=%lld\n", path,
        static_cast<long long>(frames), static_cast<long long>(expectedFrames),
        static_cast<long long>(decoder.time()), static_cast<long long>(decoder.duration()));
    require(frames == expectedFrames, "AMR SID/NO_DATA dropped timeline frames");
    require(std::abs(decoder.time() - expectedFrames * 1000000 / Decoder::RATE) <= 1000,
        "AMR SID/NO_DATA consumed clock differs");
    require(std::abs(decoder.duration() - decoder.time()) <= 1000, "AMR bitrate estimate replaced actual duration");
    std::printf("PASS SID/NO_DATA %s frames=%lld time=%lld duration=%lld\n", path,
        static_cast<long long>(frames), static_cast<long long>(decoder.time()), static_cast<long long>(decoder.duration()));
}
void corruptFixture(const char *path) {
    int before = descriptors();
    bool rejected = false;
    try { Decoder decoder(path); decoder.prefetch(); }
    catch (const std::exception &) { rejected = true; }
    require(rejected && descriptors() == before, "Corrupt sampled header accepted or leaked resources");
    std::printf("PASS corrupt sampled header rejects and closes %s\n", path);
}
void videoOffsetFixture(const char *path) {
    bool rejected = false;
    try { Decoder audioOnly(path); }
    catch (const std::exception &) { rejected = true; }
    require(rejected, "Video silently routed to audio-only decoder");
    Decoder decoder(path, true);
    decoder.timeline(0); decoder.prefetch(); decoder.resume();
    std::array<float, 1022> samples{};
    int64_t frames = 0, firstAudible = -1;
    auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(5);
    while (!decoder.drained()) {
        samples.fill(0);
        const int copied = decoder.read(samples.data(), 511, 1, 1);
        for (int i = 0; i < copied; ++i)
            if (firstAudible < 0 && std::abs(samples[2 * i]) > 0.01f) firstAudible = frames + i;
        frames += copied;
        require(!decoder.error() && std::chrono::steady_clock::now() < deadline, "Video soundtrack drain failed");
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    decoder.pause();
    require(firstAudible >= 21000 && firstAudible <= 23000, "Track offset or AAC priming lost");
    require(decoder.time() > 2450000 && decoder.time() < 2550000, "Audio track normalized independently");
    decoder.seek(1250000); decoder.prefetch(); decoder.pause();
    double energy = 0; consume(decoder, 511, energy);
    require(decoder.time() >= 1250000 && decoder.time() < 1270000, "Container seek leaked old track offset");
    std::printf("PASS video soundtrack offset firstAudible=%lld frames=%lld seekTime=%lld\n",
        static_cast<long long>(firstAudible), static_cast<long long>(frames), static_cast<long long>(decoder.time()));
}
void videoGapFixture(const char *path) {
    {
        Decoder scan(path, true);
        scan.timeline(0);
        require(scan.scanDuration([] { return false; }) > 2980000, "Finite scan compacted PTS gap");
        require(scan.time() == 0 && scan.diagnostics()[3] == 0, "Finite scan advanced playback or started worker");
    }
    {
        Decoder scan(path, true);
        scan.timeline(0);
        bool cancelled = false;
        try { scan.scanDuration([] { return true; }); }
        catch (const std::runtime_error &) { cancelled = true; }
        require(cancelled, "Finite scan ignored cancellation");
    }
    Decoder decoder(path, true);
    decoder.timeline(0);
    for (int64_t target : {0LL, 750000LL, 1500000LL, 2300000LL}) {
        require(decoder.seek(target) == target, "Gap seek incorrectly clamped to compacted audio end");
        decoder.prefetch(); decoder.resume();
        std::array<float, 1022> samples{};
        int64_t frames = 0, secondTone = -1;
        double gapEnergy = 0, tailEnergy = 0;
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(5);
        while (!decoder.drained()) {
            samples.fill(0);
            const int count = decoder.read(samples.data(), 511, 1, 1);
            for (int i = 0; i < count; ++i) {
                const int64_t time = target + (frames + i) * 1000000 / Decoder::RATE;
                const double energy = samples[2 * i] * samples[2 * i];
                if (time >= 1150000 && time < 1850000) gapEnergy += energy;
                if (time >= 2200000 && time < 2800000) tailEnergy += energy;
                if (time >= 1500000 && secondTone < 0 && std::abs(samples[2 * i]) > 0.01f)
                    secondTone = time;
            }
            frames += count;
            require(!decoder.error() && std::chrono::steady_clock::now() < deadline, "Gap soundtrack drain failed");
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
        decoder.pause();
        require(decoder.duration() > 2980000 && decoder.duration() < 3040000,
            "Internal PTS gap was removed from final duration");
        require(std::abs(decoder.time() - decoder.duration()) < 1000, "Gap soundtrack final cursor differs");
        require(std::abs(target + frames * 1000000 / Decoder::RATE - decoder.duration()) < 3000,
            "Source silence or decoded tail lost timeline frames");
        require(gapEnergy < 0.001 && tailEnergy > 10, "Second tone moved into the timestamp gap");
        if (target <= 1500000)
            require(secondTone > 2000000 && secondTone < 2050000, "Post-gap tone started at wrong media time");
        require(decoder.diagnostics()[2] == 0 && decoder.diagnostics()[3] == 0,
            "Gap drain retained queued PCM or worker");
        std::printf("PASS internal gap seek=%lld frames=%lld secondTone=%lld duration=%lld gapEnergy=%.6f tailEnergy=%.3f\n",
            static_cast<long long>(target), static_cast<long long>(frames), static_cast<long long>(secondTone),
            static_cast<long long>(decoder.duration()), gapEnergy, tailEnergy);
    }
}
}
int main(int argc, char **argv) {
    try {
        require(argc >= 2, "Supply generated sampled fixture paths");
        for (int i = 1; i < argc; ++i) {
            if (!strcmp(argv[i], "--gap")) {
                require(i + 1 < argc, "Supply gapped video path");
                videoGapFixture(argv[++i]);
            } else if (!strcmp(argv[i], "--video")) {
                require(i + 1 < argc, "Supply offset video path");
                videoOffsetFixture(argv[++i]);
            } else if (!strcmp(argv[i], "--corrupt")) {
                require(i + 1 < argc, "Supply corrupt sampled path");
                corruptFixture(argv[++i]);
            } else if (!strcmp(argv[i], "--sid-nb") || !strcmp(argv[i], "--sid-wb")) {
                bool narrow = !strcmp(argv[i], "--sid-nb");
                require(i + 1 < argc, "Supply generated SID path");
                sidFixture(argv[++i], narrow ? 176400 : 3528);
            } else fixture(argv[i]);
        }
        std::puts("PASS PCM decoder: format/rate/channel, consumed clock, 257/511 framing, seek/flush, drain, deallocate, cancellation/close");
        return 0;
    } catch (const std::exception &error) { std::fprintf(stderr, "FAIL %s\n", error.what()); return 1; }
}
