// SPDX-License-Identifier: Apache-2.0
#include "eas_player.h"
#include "eas_host.h"
#include "eas_data.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <future>
#include <functional>
#include <thread>
#include <stdexcept>
#include <vector>
extern "C" int JL_EAS_RealtimeAllocations(void);

namespace mmapi::eas {
// Only the device is fake. Player open/start/pause/recover/close and its actual
// callbacks run unchanged, allowing reproducible disconnect interleavings.
class TestStream final : public oboe::AudioStream {
public:
    oboe::StreamState state = oboe::StreamState::Open;
    int closes = 0;
    explicit TestStream(const oboe::AudioStreamBuilder &builder) : AudioStream(builder) {}
    oboe::Result close() override { ++closes; state = oboe::StreamState::Closed; return oboe::Result::OK; }
    oboe::Result start(int64_t) override { return requestStart(); }
    oboe::Result pause(int64_t) override { return requestPause(); }
    oboe::Result requestStart() override { state = oboe::StreamState::Started; return oboe::Result::OK; }
    oboe::Result requestPause() override { state = oboe::StreamState::Paused; return oboe::Result::OK; }
    oboe::Result requestFlush() override { return oboe::Result::OK; }
    oboe::Result requestStop() override { state = oboe::StreamState::Stopped; return oboe::Result::OK; }
    oboe::StreamState getState() override { return state; }
    oboe::Result waitForStateChange(oboe::StreamState, oboe::StreamState *next, int64_t) override {
        *next = state; return oboe::Result::OK;
    }
    bool isXRunCountSupported() const override { return false; }
    oboe::AudioApi getAudioApi() const override { return oboe::AudioApi::AAudio; }
    void updateFramesWritten() override {}
    void updateFramesRead() override {}
};
static bool rejectOpen = false;
static std::function<void()> beforeOpen;
oboe::Result testOpenOutput(const oboe::AudioStreamBuilder &builder, std::shared_ptr<oboe::AudioStream> &stream) {
    if (beforeOpen) beforeOpen();
    if (rejectOpen) return oboe::Result::ErrorInternal;
    stream = std::make_shared<TestStream>(builder);
    return oboe::Result::OK;
}
// Runs the actual production callback without opening any Android audio output.
// The friend seam permits controlled output facts, not alternate rendering code.
class PlayerTest {
    static void require(bool condition, const char *message) {
        if (!condition) throw std::runtime_error(message);
    }
    static void activate(Player &player) {
        player.prefetch(); player.start();
    }
    static std::vector<float> render(Player &player, const std::vector<int> &sizes) {
        std::vector<float> all;
        for (int count : sizes) {
            std::vector<float> guarded(count * 2 + 16, 123456.0f);
            player.engine->output->onAudioReady(nullptr, guarded.data() + 8, count);
            require(JL_EAS_RealtimeAllocations() == 0, "Core allocates while rendering");
            require(std::all_of(guarded.begin(), guarded.begin() + 8,
                [](float x) { return x == 123456.0f; }), "Callback writes before buffer");
            require(std::all_of(guarded.end() - 8, guarded.end(),
                [](float x) { return x == 123456.0f; }), "Callback writes beyond buffer");
            all.insert(all.end(), guarded.begin() + 8, guarded.end() - 8);
        }
        return all;
    }
public:
    static void outputRecovery(const std::string &bank) {
        auto player = std::make_shared<Player>("device://midi", bank);
        player->prefetch(); player->start();
        uint8_t note[] = {0x90, 60, 100};
        require(player->writeMidi(note, sizeof(note)) == sizeof(note), "Recovery note enqueue");
        render(*player, {511,511,511,511,511,511,511,511,511});
        const auto epoch = player->epoch();

        auto claimedOutput = player->engine->output;
        claimedOutput->callbacksActive.store(1);
        claimedOutput->onError(player->engine->stream.get(), oboe::Result::ErrorDisconnected);
        auto serializedRecovery = std::async(std::launch::async, [&] { player->recoverOutput(); });
        while (claimedOutput->enabled.load()) std::this_thread::yield();
        require(serializedRecovery.wait_for(std::chrono::milliseconds(20)) == std::future_status::timeout,
            "Replacement begins before old consumer acknowledges exit");
        claimedOutput->callbacksActive.store(0); serializedRecovery.get();
        render(*player, {511,511,511,511,511,511,511,511,511});
        std::puts("PASS: output replacement acknowledges the old PCM consumer before starting a new one");

        // Resolve the old callback's origin, then deliberately hold publication
        // until close/reopen/start has finished. No sleeps or shipping hooks.
        auto old = player->engine->output;
        auto oldStream = std::static_pointer_cast<TestStream>(player->engine->stream);
        std::promise<void> entered, publish;
        auto permission = publish.get_future();
        std::thread delayed([&] {
            require(old->enabled.load(), "Old callback did not resolve live output");
            entered.set_value(); permission.wait();
            old->onError(oldStream.get(), oboe::Result::ErrorInternal);
        });
        entered.get_future().wait();
        oldStream->state = oboe::StreamState::Disconnected;
        player->recoverOutput();
        auto replacement = player->engine->output;
        auto replacementStream = std::static_pointer_cast<TestStream>(player->engine->stream);
        publish.set_value(); delayed.join();
        require(old->error.load() != 0 && replacement->error.load() == 0 && replacement->enabled.load(),
            "Old callback error poisons replacement");
        Event event{};
        require(!player->poll(event) && replacementStream->closes == 0 && player->rendering.load(),
            "Old output closes or silences new output");
        auto heard = player->nonzero.load();
        render(*player, {511,511,511,511,511,511,511,511,511});
        require(player->nonzero.load() > heard, "Held note lost during recovery");

        // Six independent healthy episodes on the same request, including GAIN.
        for (int episode = 0; episode < 6; ++episode) {
            auto current = player->engine->output;
            auto device = std::static_pointer_cast<TestStream>(player->engine->stream);
            current->onError(device.get(), oboe::Result::ErrorDisconnected);
            device->state = oboe::StreamState::Disconnected;
            require(player->poll(event) && event.type == 4, "Disconnect not recoverable");
            require(!player->poll(event), "Disconnect repeats");
            if (episode == 3) {
                player->suspendOutput();
                player->recoverOutput();
                require(player->engine->output == current && !player->rendering.load(), "Suspension reopens or autoplays");
                player->resumeOutput();
            } else player->recoverOutput();
            require(player->engine->output != current && player->epoch() == epoch, "Recovery changes media request");
            render(*player, {511,511,511,511,511,511,511,511,511});
            require(player->engine->output->progress.load() >= 4410, "Replacement not healthy");
        }
        // A fresh storm gets three attempts, not three attempts per request.
        for (int attempt = 0; attempt < 3; ++attempt) {
            player->engine->output->onError(player->engine->stream.get(), oboe::Result::ErrorDisconnected);
            player->recoverOutput();
        }
        player->engine->output->onError(player->engine->stream.get(), oboe::Result::ErrorDisconnected);
        bool exhausted = false;
        try { player->recoverOutput(); } catch (const std::runtime_error &) { exhausted = true; }
        require(exhausted && !player->rendering.load(), "Disconnect storm unbounded");
        player->shutdown();
        auto last = replacement;
        last->onError(nullptr, oboe::Result::ErrorDisconnected);
        require(!last->enabled.load() && !player->engine->stream, "Close allows stale recovery");

        auto failing = std::make_shared<Player>("device://midi", bank);
        failing->prefetch(); failing->start();
        failing->engine->output->onError(failing->engine->stream.get(), oboe::Result::ErrorDisconnected);
        rejectOpen = true;
        bool rejected = false;
        try { failing->recoverOutput(); } catch (const std::runtime_error &) { rejected = true; }
        rejectOpen = false;
        require(rejected && !failing->engine->stream && !failing->rendering.load(), "Open failure leaves output running");
        failing->shutdown();
        auto stopped = std::make_shared<Player>("device://midi", bank);
        stopped->prefetch(); stopped->start(); stopped->pause();
        auto idle = stopped->engine->output;
        idle->onError(stopped->engine->stream.get(), oboe::Result::ErrorDisconnected);
        stopped->recoverOutput(); stopped->resumeOutput();
        require(stopped->engine->output == idle && !stopped->rendering.load(), "Guest stop recovers into autoplay");
        stopped->start();
        require(stopped->engine->output != idle && stopped->rendering.load(), "Fresh start cannot recover stopped output");
        stopped->shutdown();
        auto fatal = std::make_shared<Player>("device://midi", bank);
        fatal->prefetch(); fatal->start(); fatal->suspendOutput();
        fatal->engine->output->onError(fatal->engine->stream.get(), oboe::Result::ErrorInternal);
        bool failed = false;
        try { fatal->resumeOutput(); } catch (const std::runtime_error &) { failed = true; }
        require(failed && fatal->engine->opens == 1 && !fatal->rendering.load(), "GAIN hides a fatal output error");
        require(fatal->poll(event) && event.type == 5 && !fatal->poll(event), "Fatal error not delivered once");
        failed = false;
        try { fatal->recoverOutput(); } catch (const std::runtime_error &) { failed = true; }
        require(failed && fatal->engine->opens == 1, "Polling loses fatal cause");
        fatal->shutdown();
        auto closing = std::make_shared<Player>("device://midi", bank);
        closing->prefetch(); closing->start();
        closing->engine->output->onError(closing->engine->stream.get(), oboe::Result::ErrorDisconnected);
        std::promise<void> reopening, finishOpen, closeEntered;
        auto releaseOpen = finishOpen.get_future();
        beforeOpen = [&] { reopening.set_value(); releaseOpen.wait(); };
        auto recovery = std::async(std::launch::async, [&] { closing->recoverOutput(); });
        reopening.get_future().wait();
        auto closure = std::async(std::launch::async, [&] {
            closeEntered.set_value(); closing->shutdown();
        });
        closeEntered.get_future().wait();
        bool closeBlocked = closure.wait_for(std::chrono::milliseconds(0)) == std::future_status::timeout;
        finishOpen.set_value(); recovery.get(); closure.get(); beforeOpen = {};
        require(closeBlocked, "Close destroys context while reopen owns controls");
        require(closing->closed && !closing->engine->stream && !closing->engine->output && !closing->rendering.load(),
            "Recovery reopens output after concurrent close returns");
        std::puts("PASS: close requested during a controlled blocked reopen leaves no output/context");
        std::puts("PASS: delayed old publication, 7 healthy recoveries including GAIN, bounded storm and open failure");
    }
    static void sharedSources(const std::string &bank) {
        auto engine = std::make_shared<Engine>();
        Player a("device://midi", bank, engine), b("device://midi", bank, engine);
        Player reference("device://midi", bank);
        const std::vector<uint8_t> smf = {
            'M','T','h','d',0,0,0,6,0,0,0,1,0,96,
            'M','T','r','k',0,0,0,15,0,0xc0,0,0,0x90,60,100,
            96,0x80,60,0,0,0xff,0x2f,0
        };
        a.data(smf);
        uint8_t note[] = {0x90, 64, 100};
        b.prefetch(); reference.prefetch();
        b.writeMidi(note, sizeof(note)); reference.writeMidi(note, sizeof(note));
        b.start(); reference.start(); a.prefetch(); a.start();
        require(a.engine == b.engine && engine->opens == 1, "Sources open separate runtime outputs");
        render(b, {257,511}); render(reference, {257,511});
        a.pause();
        require(render(b, {257,511}) == render(reference, {257,511}), "Stop A changes B PCM");
        auto bEpoch = b.epoch(); auto device = engine->stream;
        a.seek(250000);
        require(render(b, {511}) == render(reference, {511}), "Seek A consumes B staging");
        a.deallocate();
        require(engine->stream == device && engine->opens == 1, "Deallocate A replaces shared output");
        require(render(b, {257}) == render(reference, {257}), "Deallocate A changes B PCM");
        a.shutdown();
        require(render(b, {511}) == render(reference, {511}) && b.epoch() == bEpoch,
            "Close A loses B voices or generation");
        require(engine->stream == device && device->getState() == oboe::StreamState::Started,
            "Close A pauses B output");
        reference.shutdown(); b.shutdown();

        Player old("device://midi", bank, engine), fresh("device://midi", bank, engine);
        old.prefetch(); old.start(0); fresh.prefetch();
        engine->policy(7, true); fresh.start(7);
        auto oldFrames = old.frames.load();
        render(fresh, {257});
        require(old.frames.load() == oldFrames && fresh.frames.load() == 257,
            "Permanent loss plays stale request beside fresh source");
        old.suspendOutput();
        require(engine->stream->getState() == oboe::StreamState::Started,
            "Late old source suspension pauses fresh output");
        engine->policy(7, false);
        auto freshFrames = fresh.frames.load();
        render(fresh, {511});
        require(fresh.frames.load() == freshFrames, "Policy suspension consumes PCM");
        fresh.suspendOutput(); engine->policy(7, true); fresh.resumeOutput();
        render(fresh, {257});
        require(fresh.frames.load() == freshFrames + 257, "Transient gain loses fresh intent");
        fresh.shutdown(); old.shutdown();

        engine->policy(0, true);
        Player one("device://midi", bank, engine), two("device://midi", bank, engine);
        activate(one); activate(two);
        one.cursor = two.cursor = 0;
        one.block.fill(32000); two.block.fill(32000);
        auto saturated = render(one, {256});
        require(std::all_of(saturated.begin(), saturated.end(), [](float x) { return x == 1; })
                && engine->clipped.load() >= 512, "Mixer overload policy is not measured hard clipping");
        one.cursor = two.cursor = 0; one.gain(.25f, .25f); two.gain(.5f, .5f);
        auto attenuated = render(one, {256});
        require(std::all_of(attenuated.begin(), attenuated.end(), [](float x) {
            return x == 32000 * .75f / 32768;
        }), "Mixer normalizes by source count or loses gain");
        auto claimed = one.slot;
        engine->slots[claimed].hazard.store(&one);
        std::promise<void> entered;
        auto closing = std::async(std::launch::async, [&] { entered.set_value(); one.shutdown(); });
        entered.get_future().wait();
        require(closing.wait_for(std::chrono::milliseconds(20)) == std::future_status::timeout,
            "Close frees a source claimed by callback");
        engine->slots[claimed].hazard.store(nullptr); closing.get();
        require(engine->stream && two.prepared.load(), "Acknowledged close frees peer output");
        engine->output->onError(engine->stream.get(), oboe::Result::ErrorInternal);
        Event terminal{};
        require(two.poll(terminal) && terminal.type == 5 && engine->failed(),
            "Fatal output is not runtime scoped");
        two.shutdown();
        Player looping("device://midi", bank, engine), shortEffect("device://midi", bank, engine);
        looping.data(smf); activate(looping); shortEffect.prefetch();
        // Pending EOM still owns a looping playback request, even in its management gap.
        looping.signal(2, 0);
        for (int attempt = 0; attempt < 3; ++attempt) {
            engine->output->onError(engine->stream.get(), oboe::Result::ErrorDisconnected);
            shortEffect.start(); shortEffect.pause();
        }
        engine->output->onError(engine->stream.get(), oboe::Result::ErrorDisconnected);
        bool exhausted = false;
        try { shortEffect.start(); } catch (const std::runtime_error &) { exhausted = true; }
        require(exhausted && engine->failed(), "Fresh short effects replenish peer recovery storm budget");
        shortEffect.shutdown(); looping.shutdown();
        std::puts("PASS: one output, two synths, stop/seek/deallocate/close isolation, policy fencing, clipping, lifetime acknowledgment");
    }
    static void mixedPcm(const std::string &bank, const std::string &path) {
        auto engine = std::make_shared<Engine>();
        Player synth("device://midi", bank, engine), reference("device://midi", bank);
        Player effect(path, "", engine, true);
        uint8_t note[] = {0x90, 60, 100};
        activate(synth); activate(reference); effect.prefetch();
        require(effect.time() == 0 && effect.length() == 1000000, "Prefetch advances sampled time");
        require(effect.decoderDiagnostics()[2] == 16384 && effect.decoderDiagnostics()[3] == 0,
            "Prefetch returns before filling the bounded ring and joining its worker");
        synth.writeMidi(note, sizeof(note)); reference.writeMidi(note, sizeof(note));
        effect.start();
        render(synth, {257,511}); render(reference, {257,511});
        require(effect.time() > 0 && effect.nonzero.load() > 0 && engine->opens == 1,
            "Sampled and synthesis do not share one output");
        effect.pause();
        require(render(synth, {257,511}) == render(reference, {257,511}), "Sampled stop changes synth PCM");
        auto sourceEpoch = effect.epoch(); auto synthEpoch = synth.epoch();
        require(effect.seek(500000) == 500000 && effect.epoch() != sourceEpoch, "Sampled seek retains old generation");
        require(render(synth, {511}) == render(reference, {511}) && synth.epoch() == synthEpoch,
            "Sampled seek disrupts synth clock/voices");
        effect.deallocate();
        require(effect.time() == 500000 && effect.length() == 1000000 && engine->stream,
            "Sampled deallocate loses known state or peer output");
        effect.prefetch(); effect.repeat(2); effect.start();
        Event end{}; int ends = 0;
        for (int i = 0; i < 400 && ends < 2; ++i) {
            render(synth, {511});
            if (effect.poll(end)) { require(end.type == (ends ? 2 : 1), "Sampled loop event mismatch"); ++ends; }
            std::this_thread::sleep_for(std::chrono::milliseconds(2));
        }
        require(ends == 2 && effect.time() >= 999000, "Sampled EOS before drain or finite loop missing");
        effect.seek(0); effect.start(); render(synth, {257});
        effect.signal(3, -1);
        require(effect.poll(end) && end.type == 3 && !engine->failed(), "Local decoder error poisons shared output");
        auto frames = synth.frames.load(); effect.shutdown(); render(synth, {511});
        require(synth.frames.load() == frames + 511 && engine->stream, "Sampled error/close stops synthesis peer");
        synth.shutdown(); reference.shutdown();
        std::puts("PASS: mixed PCM+synthesis, consumed clock, seek/deallocate, finite drain/loops and local failure isolation");
    }
    static void run(const std::string &bank, const std::string &pcm) {
        outputRecovery(bank);
        sharedSources(bank);
        if (!pcm.empty()) mixedPcm(bank, pcm);
        Player regular("device://midi", bank), varied("device://midi", bank);
        uint8_t note[] = {0xc0, 0, 0x90, 60, 100};
        require(EAS_WriteMIDIStream(regular.eas, regular.interactive, note, sizeof(note)) == EAS_SUCCESS, "MIDI note");
        require(EAS_WriteMIDIStream(varied.eas, varied.interactive, note, sizeof(note)) == EAS_SUCCESS, "MIDI note");
        activate(regular); activate(varied);
        auto reference = render(regular, {256, 256, 256, 256});
        auto fragmented = render(varied, {257, 511, 1, 255});
        require(reference == fragmented, "257/511-frame output differs from fixed blocks");
        require(std::any_of(reference.begin(), reference.end(), [](float x) { return x != 0; }), "Bank renders silence");
        auto before = render(varied, {17});
        auto regularBefore = render(regular, {17});
        require(before == regularBefore, "Partial staging mismatch");
        auto generation = varied.epoch();
        varied.suspendOutput();
        auto silent = render(varied, {511});
        require(std::all_of(silent.begin(), silent.end(), [](float x) { return x == 0; }), "Suspended output audible");
        require(varied.epoch() == generation, "Host suspension invalidates guest generation");
        require(varied.cursor == regular.cursor, "Suspension consumes staged PCM");
        activate(varied);
        require(render(regular, {257, 511}) == render(varied, {257, 511}), "Resume loses held voices or PCM");
        varied.signal(2, 0);
        varied.suspendOutput();
        require(varied.pending.load() == 2, "Host suspension discards pending EOM");
        varied.pause();
        require(varied.pending.load() == 0 && varied.epoch() != generation, "Guest stop fails to invalidate old event");
        bool unsupported = false;
        try { varied.seek(1000); }
        catch (const std::runtime_error &) { unsupported = true; }
        require(unsupported, "Interactive seek accepted");
        varied.prefetch();
        varied.midiHead.store(varied.midiTail.load());
        uint8_t large[16384]{};
        require(varied.writeMidi(large, sizeof(large)) == sizeof(large), "MIDI queue capacity");
        require(varied.writeMidi(large, 1) == -1, "MIDI queue overflow accepted");
        varied.engine->output->onError(nullptr, oboe::Result::ErrorDisconnected);
        Event outputEvent{};
        require(varied.poll(outputEvent) && outputEvent.type == 4, "Disconnect is not recoverable");
        require(!varied.poll(outputEvent), "Disconnect event repeated");
        varied.engine->output = std::make_shared<Engine::Output>(varied.engine, ++varied.engine->outputEpoch);
        varied.engine->output->onError(nullptr, oboe::Result::ErrorInternal);
        require(varied.poll(outputEvent) && outputEvent.type == 5, "Permanent output failure not fatal");
        varied.shutdown(); varied.shutdown();

        // A project-owned half-second SMF fixture exercises the production
        // file parser, frozen PREFETCHED MIDI, seek, and pending EOM loop facts.
        std::vector<uint8_t> smf = {
            'M','T','h','d',0,0,0,6,0,0,0,1,0,96,
            'M','T','r','k',0,0,0,15,0,0xc0,0,0,0x90,60,100,
            96,0x80,60,0,0,0xff,0x2f,0
        };
        for (bool wrapped : {false, true}) {
            auto bytes = smf;
            if (wrapped) {
                bytes = {'R','I','F','F',0,0,0,0,'R','M','I','D','d','a','t','a',0,0,0,0};
                const uint32_t size = smf.size();
                for (int i = 0; i < 4; ++i) {
                    bytes[4 + i] = (size + 12 + (size & 1)) >> (8 * i);
                    bytes[16 + i] = size >> (8 * i);
                }
                bytes.insert(bytes.end(), smf.begin(), smf.end());
                if (size & 1) bytes.push_back(0);
            }
            Player file("device://midi", bank);
            file.data(std::move(bytes));
            auto mediaDuration = file.length();
            std::printf("SMF duration: %lld us\n", static_cast<long long>(mediaDuration));
            require(mediaDuration >= 499000 && mediaDuration <= 501000, "SMF duration exceeds one millisecond quantization");
            activate(file); file.midiOnly = true;
            require(EAS_WriteMIDIStream(file.eas, file.interactive, note, sizeof(note)) == EAS_SUCCESS, "Prefetched file MIDI");
            auto preview = render(file, {257, 511});
            require(std::any_of(preview.begin(), preview.end(), [](float x) { return x != 0; }), "Prefetched MIDI silent");
            require(file.time() == 0 && file.media->pParserModule != nullptr, "Interactive MIDI advances or detaches parser");
            file.pause();
            activate(file); file.midiOnly = false;
            render(file, {257, 511});
            require(file.time() > 0, "SMF time does not advance");
            file.pause();
            auto frozenTime = file.time();
            render(file, {511});
            require(file.time() == frozenTime, "Guest pause advances media time");
            require(file.seek(250000) == 250000 && file.cursor == 256, "Seek retains stale PCM");
            activate(file);
            for (int i = 0; i < 150 && file.pending.load() == 0; ++i) render(file, {511});
            require(file.pending.load() == 2 && file.time() == mediaDuration, "SMF EOM missing");
            file.repeat(2);
            auto endEpoch = file.epoch();
            file.suspendOutput();
            require(file.pending.load() == 2 && file.epoch() == endEpoch, "Host pause invalidates completed SMF");
            Event end{};
            require(file.poll(end) && end.type == 1 && end.time == mediaDuration && end.generation == endEpoch,
                "Suspended loop loses EOM/restart facts");
            require(file.time() == 0 && !file.rendering.load(), "Suspended loop restarts output without focus");
            activate(file);
            for (int i = 0; i < 150 && file.pending.load() == 0; ++i) render(file, {511});
            file.suspendOutput();
            require(file.poll(end) && end.type == 2 && end.time == mediaDuration, "Loop count not honored");
            file.shutdown();
        }

        // Exercise the real memory locator and pooled duplicated cursor path.
        MemoryFile memory({0x12, 0x34, 0x56, 0x78});
        EAS_HW_DATA_HANDLE host = nullptr;
        require(EAS_HWInit(&host) == EAS_SUCCESS, "Host init");
        EAS_FILE_HANDLE one = nullptr, two = nullptr;
        require(EAS_HWOpenFile(host, &memory.locator, &one, EAS_FILE_READ) == EAS_SUCCESS, "readAt open");
        require(EAS_HWDupHandle(host, one, &two) == EAS_SUCCESS, "readAt duplicate");
        EAS_U32 value = 0;
        require(EAS_HWGetDWord(host, one, &value, EAS_TRUE) == EAS_SUCCESS && value == 0x12345678, "Big endian memory read");
        EAS_I32 position = -1;
        require(EAS_HWFilePos(host, two, &position) == EAS_SUCCESS && position == 0, "Duplicate shares cursor");
        require(EAS_HWFileSeek(host, two, 5) != EAS_SUCCESS, "Out of bounds seek accepted");
        require(EAS_HWFileSeekOfs(host, two, INT32_MAX) != EAS_SUCCESS, "Overflowing seek accepted");
        require(EAS_HWCloseFile(host, one) == EAS_SUCCESS && EAS_HWCloseFile(host, two) == EAS_SUCCESS, "Cursor close");
        EAS_HWShutdown(host);
    }
};
}
int main(int argc, char **argv) {
    try {
        mmapi::eas::PlayerTest::run(argc > 1 && std::string(argv[1]) != "-" ? argv[1] : "", argc > 2 ? argv[2] : "");
        std::puts("PASS: production callback 257/511 framing, guard buffers, frozen held voices/PCM, generations, readAt ownership");
        return 0;
    } catch (const std::exception &error) {
        std::fprintf(stderr, "FAIL: %s\n", error.what()); return 1;
    }
}
