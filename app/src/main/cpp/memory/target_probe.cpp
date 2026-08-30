/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <jni.h>
#include <sys/mman.h>
#include <unistd.h>

#include <array>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <optional>
#include <string>
#include <utility>
#include <vector>

namespace {

constexpr jint kFastScope = 0;
constexpr jint kThoroughScope = 1;

enum class ScanScope : uint8_t {
    Fast = kFastScope,
    Thorough = kThoroughScope,
};

[[nodiscard]] constexpr std::optional<ScanScope> scanScopeFromJint(jint scope) noexcept {
    switch (scope) {
    case kFastScope: return ScanScope::Fast;
    case kThoroughScope: return ScanScope::Thorough;
    default: return std::nullopt;
    }
}

struct ResidentRun {
    uintptr_t start;
    uintptr_t end;
};

alignas(uint64_t) volatile uint64_t gReadProbe = UINT64_C(0x4a4c4d454d50524f);

bool isSelectedMap(const char *permissions, const std::string &name,
                   ScanScope scope) {
    if (std::strncmp(permissions, "rw-p", 4) != 0) {
        return false;
    }

    const bool dalvik = name.find("dalvik-") != std::string::npos;
    const bool zygote = name.find("zygote") != std::string::npos;
    if (scope == ScanScope::Fast) {
        return dalvik && !zygote;
    }
    if (scope != ScanScope::Thorough) {
        return false;
    }

    // ART names its managed-heap mappings on current releases. Also accept
    // unnamed private anonymous mappings so the thorough scope remains useful
    // on runtimes that omit those labels. File-backed and explicitly named
    // non-ART mappings stay out.
    return (dalvik && !zygote) || name.empty();
}

bool appendRun(std::vector<ResidentRun> &runs,
               uintptr_t start, uintptr_t end, size_t maxRuns,
               bool &truncated) {
    if (start >= end) {
        return true;
    }
    if (!runs.empty() && runs.back().end == start) {
        runs.back().end = end;
        return true;
    }
    if (runs.size() >= maxRuns) {
        truncated = true;
        return false;
    }
    runs.push_back({start, end});
    return true;
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryTarget_pageSize(JNIEnv *,
                                                                   jclass) {
    const long value = sysconf(_SC_PAGESIZE);
    if (value <= 0 || value > std::numeric_limits<jint>::max()) {
        return 0;
    }
    return static_cast<jint>(value);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryTarget_readProbe(JNIEnv *env,
                                                                    jclass) {
    const jlong values[] = {
            static_cast<jlong>(reinterpret_cast<uintptr_t>(&gReadProbe)),
            static_cast<jlong>(gReadProbe),
    };
    jlongArray result = env->NewLongArray(2);
    if (result != nullptr) {
        env->SetLongArrayRegion(result, 0, 2, values);
    }
    return result;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryTarget_collectResidentRuns(
        JNIEnv *env, jclass, jint scope, jint maxRuns) {
    const long pageValue = sysconf(_SC_PAGESIZE);
    const auto selectedScope = scanScopeFromJint(scope);
    if (!selectedScope.has_value() || maxRuns <= 0 || pageValue <= 0) {
        return nullptr;
    }
    const size_t pageSize = static_cast<size_t>(pageValue);

    FILE *maps = nullptr;
    char *line = nullptr;
    try {
        maps = std::fopen("/proc/self/maps", "re");
        if (maps == nullptr) {
            return nullptr;
        }

        std::vector<ResidentRun> runs;
        bool truncated = false;
        size_t lineCapacity = 0;
        while (!truncated && getline(&line, &lineCapacity, maps) >= 0) {
            unsigned long long rawStart = 0;
            unsigned long long rawEnd = 0;
            std::array<char, 5> permissions{};
            int nameOffset = 0;
            if (std::sscanf(line, "%llx-%llx %4s %*s %*s %*s %n", &rawStart,
                            &rawEnd, permissions.data(), &nameOffset) < 3 ||
                rawStart >= rawEnd ||
                rawEnd > std::numeric_limits<uintptr_t>::max()) {
                continue;
            }
            std::string name;
            if (nameOffset > 0) {
                const char *begin = line + nameOffset;
                while (*begin == ' ' || *begin == '\t') {
                    ++begin;
                }
                name.assign(begin);
                while (!name.empty() &&
                       (name.back() == '\n' || name.back() == '\r' ||
                        name.back() == ' ' || name.back() == '\t')) {
                    name.pop_back();
                }
            }
            if (!isSelectedMap(permissions.data(), name, *selectedScope)) {
                continue;
            }

            const uintptr_t start = static_cast<uintptr_t>(rawStart);
            const uintptr_t end = static_cast<uintptr_t>(rawEnd);
            if (start % pageSize != 0 || end % pageSize != 0) {
                continue;
            }
            const size_t pageCount =
                    static_cast<size_t>((end - start) / pageSize);
            if (pageCount == 0) {
                continue;
            }
            std::vector<unsigned char> residency;
            try {
                residency.resize(pageCount);
            } catch (...) {
                truncated = true;
                break;
            }
            if (mincore(reinterpret_cast<void *>(start), end - start,
                        residency.data()) != 0) {
                // A map may disappear between maps parsing and mincore. Skip
                // that stale map, as the proven PR #109 scanner did. The
                // maxRuns cap below still reports true payload truncation.
                continue;
            }

            size_t runStartPage = pageCount;
            for (size_t page = 0; page <= pageCount; ++page) {
                const bool resident =
                        page < pageCount && (residency[page] & 1U) != 0;
                if (resident && runStartPage == pageCount) {
                    runStartPage = page;
                } else if (!resident && runStartPage != pageCount) {
                    const uintptr_t runStart = start + runStartPage * pageSize;
                    const uintptr_t runEnd = start + page * pageSize;
                    if (!appendRun(runs, runStart, runEnd,
                                   static_cast<size_t>(maxRuns), truncated)) {
                        break;
                    }
                    runStartPage = pageCount;
                }
            }
        }
        std::free(line);
        line = nullptr;
        std::fclose(maps);
        maps = nullptr;

        if (runs.size() >
            (static_cast<size_t>(std::numeric_limits<jsize>::max()) - 2U) /
                    2U) {
            return nullptr;
        }
        const jsize outputSize = static_cast<jsize>(2U + runs.size() * 2U);
        std::vector<jlong> output(static_cast<size_t>(outputSize));
        output[0] = static_cast<jlong>(runs.size());
        output[1] = truncated ? 1 : 0;
        for (size_t index = 0; index < runs.size(); ++index) {
            output[2U + index * 2U] = static_cast<jlong>(runs[index].start);
            output[3U + index * 2U] = static_cast<jlong>(runs[index].end);
        }
        jlongArray result = env->NewLongArray(outputSize);
        if (result != nullptr) {
            env->SetLongArrayRegion(result, 0, outputSize, output.data());
        }
        return result;
    } catch (...) {
        std::free(line);
        if (maps != nullptr) {
            std::fclose(maps);
        }
        return nullptr;
    }
}
