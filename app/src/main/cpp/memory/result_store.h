/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <span>
#include <vector>

namespace jlmem::v2 {

constexpr std::size_t kResultLogicalBlockSize = 4096U;
// Payload is split into fixed virtual slabs so immutable revisions can share untouched bitmap
// storage. A refine clones only the slab containing a mutated block instead of copying the full
// ResultStore payload. 4096 words = 32 KiB, while one full Auto block needs only 176 words.
constexpr std::size_t kResultPayloadSlabWords = 4096U;

enum class ResultPlane : std::uint8_t {
    Byte,
    Short,
    Char,
    Int,
    Float,
    Long,
    Double,
    Count,
};

constexpr std::size_t kResultPlaneCount =
        static_cast<std::size_t>(ResultPlane::Count);

[[nodiscard]] constexpr std::size_t planeIndex(ResultPlane plane) noexcept {
    return static_cast<std::size_t>(plane);
}

[[nodiscard]] constexpr std::size_t planeAlignment(ResultPlane plane) noexcept {
    switch (plane) {
    case ResultPlane::Byte:
        return 1U;
    case ResultPlane::Short:
    case ResultPlane::Char:
        return 2U;
    case ResultPlane::Int:
    case ResultPlane::Float:
        return 4U;
    case ResultPlane::Long:
    case ResultPlane::Double:
        return 8U;
    case ResultPlane::Count:
        return 0U;
    }
    return 0U;
}

[[nodiscard]] constexpr std::size_t planeSlotCount(ResultPlane plane) noexcept {
    const std::size_t alignment = planeAlignment(plane);
    return alignment == 0U ? 0U : kResultLogicalBlockSize / alignment;
}

[[nodiscard]] constexpr std::size_t planeWordCount(ResultPlane plane) noexcept {
    const std::size_t slots = planeSlotCount(plane);
    return (slots + 63U) / 64U;
}

[[nodiscard]] constexpr std::size_t scratchWordCount() noexcept {
    std::size_t total = 0U;
    for (std::size_t index = 0U; index < kResultPlaneCount; ++index) {
        total += planeWordCount(static_cast<ResultPlane>(index));
    }
    return total;
}

constexpr std::size_t kResultScratchWordCount = scratchWordCount();

struct ResultBlockHeader {
    std::uintptr_t baseAddress = 0U;
    // Virtual word offset. Blocks never cross a payload slab boundary.
    std::uint32_t payloadWordOffset = 0U;
    std::array<std::uint16_t, kResultPlaneCount> counts{};
    std::uint16_t uniqueAddressCount = 0U;
    // Allocated planes. A bit remains set after refine clears every match in that plane so the
    // payload layout stays stable for the lifetime of the ResultStore revision.
    std::uint8_t activeMask = 0U;
};

static_assert(sizeof(ResultBlockHeader) <= 32U,
              "ResultBlockHeader must stay compact at large block counts");

class ResultBlockScratch final {
  public:
    bool set(ResultPlane plane, std::size_t slot) noexcept;
    bool clear(ResultPlane plane, std::size_t slot) noexcept;

    [[nodiscard]] bool test(ResultPlane plane, std::size_t slot) const noexcept;
    [[nodiscard]] std::span<const std::uint64_t> planeWords(
            ResultPlane plane) const noexcept;
    [[nodiscard]] std::uint16_t count(ResultPlane plane) const noexcept;
    [[nodiscard]] std::uint16_t uniqueAddressCount() const noexcept;
    [[nodiscard]] std::uint8_t activeMask() const noexcept;
    [[nodiscard]] bool empty() const noexcept;

    void reset() noexcept;

  private:
    [[nodiscard]] static constexpr std::size_t planeOffset(
            ResultPlane plane) noexcept {
        std::size_t offset = 0U;
        for (std::size_t index = 0U; index < planeIndex(plane); ++index) {
            offset += planeWordCount(static_cast<ResultPlane>(index));
        }
        return offset;
    }

    std::array<std::uint64_t, kResultScratchWordCount> words_{};
};

class ResultStore final {
  public:
    // Blocks are append-only and strictly address ordered. Empty blocks are intentionally omitted
    // on first materialization; once a block exists, refine never compacts its payload layout.
    [[nodiscard]] bool appendNonEmptyBlock(
            std::uintptr_t baseAddress, const ResultBlockScratch &scratch);

    [[nodiscard]] bool clearSlot(std::size_t blockIndex, ResultPlane plane,
                                 std::size_t slot);
    // Recompute one header after a specialized refine kernel edits bitmap words directly.
    [[nodiscard]] bool recountBlock(std::size_t blockIndex) noexcept;

    [[nodiscard]] std::span<const std::uint64_t> planeWords(
            std::size_t blockIndex, ResultPlane plane) const noexcept;
    // May clone one shared 32 KiB payload slab on first mutation of this revision.
    [[nodiscard]] std::span<std::uint64_t> planeWords(
            std::size_t blockIndex, ResultPlane plane);
    [[nodiscard]] std::span<const ResultBlockHeader> headers() const noexcept {
        return headers_;
    }

    [[nodiscard]] std::size_t blockCount() const noexcept {
        return headers_.size();
    }
    [[nodiscard]] std::uint64_t typedCount() const noexcept {
        return typedCount_;
    }
    [[nodiscard]] std::uint64_t uniqueAddressCount() const noexcept {
        return uniqueAddressCount_;
    }
    [[nodiscard]] std::size_t retainedBytes() const noexcept;
    [[nodiscard]] std::size_t payloadSlabCount() const noexcept {
        return payloadSlabs_.size();
    }

    void clear() noexcept;

  private:
    struct PayloadSlab final {
        std::array<std::uint64_t, kResultPayloadSlabWords> words{};
    };

    [[nodiscard]] std::size_t payloadOffset(
            const ResultBlockHeader &header, ResultPlane plane) const noexcept;
    [[nodiscard]] bool locatePayload(std::size_t offset, std::size_t count,
                                     std::size_t &slabIndex,
                                     std::size_t &slabOffset) const noexcept;
    [[nodiscard]] bool anyAliasAtAddress(std::size_t blockIndex,
                                         std::size_t byteOffset) const noexcept;
    [[nodiscard]] std::uint16_t blockUniqueAddressCount(
            std::size_t blockIndex) const noexcept;

    std::vector<ResultBlockHeader> headers_;
    std::vector<std::shared_ptr<PayloadSlab>> payloadSlabs_;
    // Virtual word position following the last allocated block. Padding at slab tails is included.
    std::size_t payloadWordsUsed_ = 0U;
    std::uint64_t typedCount_ = 0U;
    std::uint64_t uniqueAddressCount_ = 0U;
};

static_assert(planeWordCount(ResultPlane::Byte) == 64U);
static_assert(planeWordCount(ResultPlane::Short) == 32U);
static_assert(planeWordCount(ResultPlane::Char) == 32U);
static_assert(planeWordCount(ResultPlane::Int) == 16U);
static_assert(planeWordCount(ResultPlane::Float) == 16U);
static_assert(planeWordCount(ResultPlane::Long) == 8U);
static_assert(planeWordCount(ResultPlane::Double) == 8U);
static_assert(kResultScratchWordCount == 176U);
static_assert(kResultScratchWordCount < kResultPayloadSlabWords);

} // namespace jlmem::v2
