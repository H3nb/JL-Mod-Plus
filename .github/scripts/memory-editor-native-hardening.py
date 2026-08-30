from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one marker, found {count}")
    return text.replace(old, new, 1)


def transform_region(text: str, start: str, end: str, transform, label: str) -> str:
    start_pos = text.find(start)
    if start_pos < 0:
        raise SystemExit(f"{label}: start marker missing")
    end_pos = text.find(end, start_pos)
    if end_pos < 0:
        raise SystemExit(f"{label}: end marker missing")
    region = text[start_pos:end_pos]
    changed = transform(region)
    if changed == region:
        raise SystemExit(f"{label}: transform made no change")
    return text[:start_pos] + changed + text[end_pos:]


# 1) Modern language baseline. Keep warnings strict but avoid noisy conversion flags.
p = Path("app/src/main/cpp/memory/Android.mk")
text = p.read_text()
text = text.replace(
    "LOCAL_CPPFLAGS := -std=c++17 -Wall -Wextra -Werror",
    "LOCAL_CPPFLAGS := -std=c++23 -Wall -Wextra -Werror -Wpedantic -Wformat=2 -Wimplicit-fallthrough",
)
if text.count("-std=c++23") != 2 or "-std=c++17" in text:
    raise SystemExit("Android.mk: expected both Memory Editor modules to use C++23")
p.write_text(text)


# 2) Native engine: strong value types, bounded byte views, checked address arithmetic,
# bit_cast, RAII JNI chars, and index-based recovery instead of Candidate* lifetimes.
p = Path("app/src/main/cpp/memory/memory_engine.cpp")
text = p.read_text()
text = replace_once(
    text,
    "#include <algorithm>\n#include <atomic>\n",
    "#include <algorithm>\n#include <array>\n#include <atomic>\n#include <bit>\n",
    "engine modern includes 1",
)
text = replace_once(
    text,
    "#include <new>\n#include <string>\n",
    "#include <new>\n#include <optional>\n#include <span>\n#include <string>\n",
    "engine modern includes 2",
)

raw_types = """constexpr jint kTypeAuto = 0;
constexpr jint kTypeByte = 1;
constexpr jint kTypeShort = 2;
constexpr jint kTypeChar = 3;
constexpr jint kTypeInt = 4;
constexpr jint kTypeLong = 5;
constexpr jint kTypeFloat = 6;
constexpr jint kTypeDouble = 7;
"""
strong_types = raw_types + """
// JNI uses stable integer constants, but materialized candidates and parsed queries use a
// scoped type so width-sensitive operations cannot accidentally accept predicates or other ints.
enum class ValueType : uint8_t {
    Byte = kTypeByte,
    Short = kTypeShort,
    Char = kTypeChar,
    Int = kTypeInt,
    Long = kTypeLong,
    Float = kTypeFloat,
    Double = kTypeDouble,
};

[[nodiscard]] constexpr jint toJint(ValueType type) noexcept {
    return static_cast<jint>(std::to_underlying(type));
}

[[nodiscard]] constexpr size_t typeIndex(ValueType type) noexcept {
    return static_cast<size_t>(std::to_underlying(type));
}

constexpr size_t kTypeSlotCount = typeIndex(ValueType::Double) + 1U;

[[nodiscard]] constexpr std::optional<ValueType> valueTypeFromJint(jint type) noexcept {
    switch (type) {
    case kTypeByte: return ValueType::Byte;
    case kTypeShort: return ValueType::Short;
    case kTypeChar: return ValueType::Char;
    case kTypeInt: return ValueType::Int;
    case kTypeLong: return ValueType::Long;
    case kTypeFloat: return ValueType::Float;
    case kTypeDouble: return ValueType::Double;
    default: return std::nullopt;
    }
}

[[nodiscard]] constexpr bool isFloating(ValueType type) noexcept {
    return type == ValueType::Float || type == ValueType::Double;
}

static_assert(sizeof(ValueType) == sizeof(uint8_t));
static_assert(toJint(ValueType::Double) == kTypeDouble);
"""
text = replace_once(text, raw_types, strong_types, "strong value types")

old_candidate = """struct Candidate {
    uint64_t id;
    uintptr_t address;
    uintptr_t previousAddress;
    uint64_t initialBits;
    uint64_t previousBits;
    uint64_t currentBits;
    uint64_t identityHash;
    uint32_t relocationCount;
    uint8_t type;
    uint8_t state;
    bool identityValid;
};
"""
new_candidate = """struct Candidate {
    uint64_t id = 0;
    uintptr_t address = 0;
    uintptr_t previousAddress = 0;
    uint64_t initialBits = 0;
    uint64_t previousBits = 0;
    uint64_t currentBits = 0;
    uint64_t identityHash = 0;
    uint32_t relocationCount = 0;
    ValueType type = ValueType::Int;
    uint8_t state = kStable;
    bool identityValid = false;
};
"""
text = replace_once(text, old_candidate, new_candidate, "candidate strong type")
text = replace_once(text, "struct Query {\n    jint type = 0;\n", "struct Query {\n    ValueType type = ValueType::Int;\n", "query strong type")

old_width = """size_t widthOf(jint type) {
    switch (type) {
    case kTypeByte:
        return 1;
    case kTypeShort:
    case kTypeChar:
        return 2;
    case kTypeInt:
    case kTypeFloat:
        return 4;
    case kTypeLong:
    case kTypeDouble:
        return 8;
    default:
        return 0;
    }
}

std::vector<jint> expandedTypes(jint requestedType) {
    if (requestedType == kTypeAuto) {
        // Prefer the representations most useful for J2ME gameplay before
        // noisy narrow aliases fill the first result pages.
        return {kTypeInt,  kTypeFloat, kTypeLong, kTypeDouble,
                kTypeShort, kTypeChar, kTypeByte};
    }
    if (widthOf(requestedType) == 0) {
        return {};
    }
    return {requestedType};
}
"""
new_width = """[[nodiscard]] constexpr size_t widthOf(ValueType type) noexcept {
    switch (type) {
    case ValueType::Byte:
        return 1;
    case ValueType::Short:
    case ValueType::Char:
        return 2;
    case ValueType::Int:
    case ValueType::Float:
        return 4;
    case ValueType::Long:
    case ValueType::Double:
        return 8;
    }
    return 0;
}

[[nodiscard]] size_t widthOf(jint rawType) noexcept {
    const auto type = valueTypeFromJint(rawType);
    return type.has_value() ? widthOf(*type) : 0U;
}

std::vector<ValueType> expandedTypes(jint requestedType) {
    if (requestedType == kTypeAuto) {
        // Prefer the representations most useful for J2ME gameplay before
        // noisy narrow aliases fill the first result pages.
        return {ValueType::Int, ValueType::Float, ValueType::Long,
                ValueType::Double, ValueType::Short, ValueType::Char,
                ValueType::Byte};
    }
    const auto type = valueTypeFromJint(requestedType);
    return type.has_value() ? std::vector<ValueType>{*type}
                            : std::vector<ValueType>{};
}
"""
text = replace_once(text, old_width, new_width, "typed width and expansion")


def typed_integer(region: str) -> str:
    region = region.replace("jint type", "ValueType type", 1)
    for raw, typed in (
        ("kTypeByte", "ValueType::Byte"),
        ("kTypeShort", "ValueType::Short"),
        ("kTypeChar", "ValueType::Char"),
        ("kTypeInt", "ValueType::Int"),
        ("kTypeLong", "ValueType::Long"),
    ):
        region = region.replace(f"case {raw}:", f"case {typed}:")
    return region

text = transform_region(text, "bool parseInteger(", "bool parseFloating(", typed_integer, "parseInteger typed")
text = transform_region(text, "bool parseDelta(", "bool predicateNeedsFirst(", typed_integer, "parseDelta typed")

# parseQuery has a typed core plus a narrow JNI/raw overload.
def typed_query(region: str) -> str:
    region = region.replace("bool parseQuery(jint type,", "bool parseQuery(ValueType type,", 1)
    region = region.replace("query.floating = type == kTypeFloat || type == kTypeDouble;", "query.floating = isFloating(type);")
    region = region.replace("type == kTypeFloat", "type == ValueType::Float")
    return region

text = transform_region(text, "bool parseQuery(", "uint64_t loadBits(", typed_query, "parseQuery typed")
query_end = """    return true;
}

uint64_t loadBits("""
query_overload = """    return true;
}

bool parseQuery(jint rawType, jint predicate, const std::string &first,
                const std::string &second, Query &query) {
    const auto type = valueTypeFromJint(rawType);
    return type.has_value() && parseQuery(*type, predicate, first, second, query);
}

uint64_t loadBits("""
text = replace_once(text, query_end, query_overload, "raw parseQuery boundary")

old_bits = """uint64_t loadBits(const uint8_t *data, size_t width) {
    uint64_t result = 0;
    std::memcpy(&result, data, width);
    return result;
}

Candidate makeCandidate(uint64_t id, uintptr_t address, jint type,
                        uint64_t initialBits, uint64_t currentBits) {
    Candidate candidate{};
    candidate.id = id;
    candidate.address = address;
    candidate.previousAddress = address;
    candidate.initialBits = initialBits;
    candidate.previousBits = initialBits;
    candidate.currentBits = currentBits;
    candidate.type = static_cast<uint8_t>(type);
    candidate.state = kStable;
    return candidate;
}

uint64_t identityHash(const uint8_t *before, const uint8_t *after) {
    uint64_t hash = UINT64_C(1469598103934665603);
    for (size_t index = 0; index < kIdentityRadius; ++index) {
        hash ^= before[index];
        hash *= UINT64_C(1099511628211);
    }
    for (size_t index = 0; index < kIdentityRadius; ++index) {
        hash ^= after[index];
        hash *= UINT64_C(1099511628211);
    }
    return hash;
}

bool snapshotIdentity(const uint8_t *bytes, size_t size, size_t offset,
                      size_t width, uint64_t &hash) {
    if (offset < kIdentityRadius || offset + width > size ||
        size - offset - width < kIdentityRadius) {
        return false;
    }
    hash = identityHash(bytes + offset - kIdentityRadius,
                        bytes + offset + width);
    return true;
}
"""
new_bits = """[[nodiscard]] uint64_t loadBits(std::span<const uint8_t> bytes) noexcept {
    if (bytes.size() > sizeof(uint64_t)) {
        return 0;
    }
    uint64_t result = 0;
    std::memcpy(&result, bytes.data(), bytes.size());
    return result;
}

[[nodiscard]] uint64_t loadBits(const uint8_t *data, size_t width) noexcept {
    if (data == nullptr && width != 0U) {
        return 0;
    }
    return loadBits(std::span<const uint8_t>(data, width));
}

Candidate makeCandidate(uint64_t id, uintptr_t address, ValueType type,
                        uint64_t initialBits, uint64_t currentBits) {
    Candidate candidate{};
    candidate.id = id;
    candidate.address = address;
    candidate.previousAddress = address;
    candidate.initialBits = initialBits;
    candidate.previousBits = initialBits;
    candidate.currentBits = currentBits;
    candidate.type = type;
    return candidate;
}

[[nodiscard]] uint64_t identityHash(std::span<const uint8_t> before,
                                    std::span<const uint8_t> after) noexcept {
    if (before.size() != kIdentityRadius || after.size() != kIdentityRadius) {
        return 0;
    }
    uint64_t hash = UINT64_C(1469598103934665603);
    for (const uint8_t value : before) {
        hash ^= value;
        hash *= UINT64_C(1099511628211);
    }
    for (const uint8_t value : after) {
        hash ^= value;
        hash *= UINT64_C(1099511628211);
    }
    return hash;
}

bool snapshotIdentity(std::span<const uint8_t> bytes, size_t offset,
                      size_t width, uint64_t &hash) noexcept {
    if (offset < kIdentityRadius || width > bytes.size() - std::min(offset, bytes.size()) ||
        offset + width > bytes.size() || bytes.size() - offset - width < kIdentityRadius) {
        return false;
    }
    hash = identityHash(bytes.subspan(offset - kIdentityRadius, kIdentityRadius),
                        bytes.subspan(offset + width, kIdentityRadius));
    return true;
}

bool snapshotIdentity(const uint8_t *bytes, size_t size, size_t offset,
                      size_t width, uint64_t &hash) noexcept {
    if (bytes == nullptr && size != 0U) {
        return false;
    }
    return snapshotIdentity(std::span<const uint8_t>(bytes, size), offset, width,
                            hash);
}
"""
text = replace_once(text, old_bits, new_bits, "span byte helpers")


def typed_integer_value(region: str) -> str:
    region = region.replace("jint type", "ValueType type", 1)
    for raw, typed in (
        ("kTypeByte", "ValueType::Byte"),
        ("kTypeShort", "ValueType::Short"),
        ("kTypeChar", "ValueType::Char"),
        ("kTypeInt", "ValueType::Int"),
        ("kTypeLong", "ValueType::Long"),
    ):
        region = region.replace(f"case {raw}:", f"case {typed}:")
    return region

text = transform_region(text, "int64_t integerValue(", "double floatingValue(", typed_integer_value, "integerValue typed")
old_float = """double floatingValue(jint type, uint64_t bits) {
    if (type == kTypeFloat) {
        uint32_t raw = static_cast<uint32_t>(bits);
        float value = 0;
        std::memcpy(&value, &raw, sizeof(value));
        return value;
    }
    double value = 0;
    std::memcpy(&value, &bits, sizeof(value));
    return value;
}
"""
new_float = """double floatingValue(ValueType type, uint64_t bits) noexcept {
    if (type == ValueType::Float) {
        return std::bit_cast<float>(static_cast<uint32_t>(bits));
    }
    return std::bit_cast<double>(bits);
}
"""
text = replace_once(text, old_float, new_float, "bit_cast floating decode")

# Checked remote address arithmetic protects the syscall boundary even if a future caller
# forgets to prove address + size separately.
old_read = """bool readExact(pid_t pid, uintptr_t address, void *destination, size_t size) {
    auto *output = static_cast<uint8_t *>(destination);
    size_t completed = 0;
    while (completed < size) {
        iovec local{output + completed, size - completed};
        iovec remote{reinterpret_cast<void *>(address + completed),
                     size - completed};
        const ssize_t read = process_vm_readv(pid, &local, 1, &remote, 1, 0);
        if (read < 0 && errno == EINTR) {
            continue;
        }
        if (read <= 0) {
            return false;
        }
        completed += static_cast<size_t>(read);
    }
    return true;
}

bool writeExact(pid_t pid, uintptr_t address, const void *source, size_t size) {
    const auto *input = static_cast<const uint8_t *>(source);
    size_t completed = 0;
    while (completed < size) {
        iovec local{const_cast<uint8_t *>(input + completed), size - completed};
        iovec remote{reinterpret_cast<void *>(address + completed),
                     size - completed};
        const ssize_t written =
                process_vm_writev(pid, &local, 1, &remote, 1, 0);
        if (written < 0 && errno == EINTR) {
            continue;
        }
        if (written <= 0) {
            return false;
        }
        completed += static_cast<size_t>(written);
    }
    return true;
}
"""
new_read = """[[nodiscard]] constexpr bool checkedAddressAdd(uintptr_t base, size_t offset,
                                               uintptr_t &result) noexcept {
    if (offset > std::numeric_limits<uintptr_t>::max() - base) {
        return false;
    }
    result = base + static_cast<uintptr_t>(offset);
    return true;
}

bool readExact(pid_t pid, uintptr_t address, void *destination, size_t size) {
    if (destination == nullptr && size != 0U) {
        return false;
    }
    auto *output = static_cast<uint8_t *>(destination);
    size_t completed = 0;
    while (completed < size) {
        uintptr_t remoteAddress = 0;
        if (!checkedAddressAdd(address, completed, remoteAddress)) {
            return false;
        }
        iovec local{output + completed, size - completed};
        iovec remote{reinterpret_cast<void *>(remoteAddress), size - completed};
        const ssize_t read = process_vm_readv(pid, &local, 1, &remote, 1, 0);
        if (read < 0 && errno == EINTR) {
            continue;
        }
        if (read <= 0) {
            return false;
        }
        completed += static_cast<size_t>(read);
    }
    return true;
}

bool writeExact(pid_t pid, uintptr_t address, const void *source, size_t size) {
    if (source == nullptr && size != 0U) {
        return false;
    }
    const auto *input = static_cast<const uint8_t *>(source);
    size_t completed = 0;
    while (completed < size) {
        uintptr_t remoteAddress = 0;
        if (!checkedAddressAdd(address, completed, remoteAddress)) {
            return false;
        }
        iovec local{const_cast<uint8_t *>(input + completed), size - completed};
        iovec remote{reinterpret_cast<void *>(remoteAddress), size - completed};
        const ssize_t written =
                process_vm_writev(pid, &local, 1, &remote, 1, 0);
        if (written < 0 && errno == EINTR) {
            continue;
        }
        if (written <= 0) {
            return false;
        }
        completed += static_cast<size_t>(written);
    }
    return true;
}
"""
text = replace_once(text, old_read, new_read, "checked syscall address arithmetic")

old_identity_sig = "bool readIdentity(const Target &target, uintptr_t address, jint type,\n                  uint64_t &hash) {"
new_identity_sig = "bool readIdentity(const Target &target, uintptr_t address, ValueType type,\n                  uint64_t &hash) {"
text = replace_once(text, old_identity_sig, new_identity_sig, "typed readIdentity")
old_identity_body = """    const size_t width = widthOf(type);
    if (width == 0 || address < kIdentityRadius ||
        address > std::numeric_limits<uintptr_t>::max() - width -
                          kIdentityRadius) {
        return false;
    }
    const uintptr_t contextStart = address - kIdentityRadius;
    const uintptr_t contextEnd = address + width + kIdentityRadius;
"""
new_identity_body = """    const size_t width = widthOf(type);
    uintptr_t valueEnd = 0;
    uintptr_t contextEnd = 0;
    if (address < kIdentityRadius ||
        !checkedAddressAdd(address, width, valueEnd) ||
        !checkedAddressAdd(valueEnd, kIdentityRadius, contextEnd)) {
        return false;
    }
    const uintptr_t contextStart = address - kIdentityRadius;
"""
text = replace_once(text, old_identity_body, new_identity_body, "checked identity window")
old_context = """    uint8_t context[kIdentityRadius * 2] = {};
    iovec local[2] = {
            {context, kIdentityRadius},
            {context + kIdentityRadius, kIdentityRadius},
    };
    iovec remote[2] = {
            {reinterpret_cast<void *>(contextStart), kIdentityRadius},
            {reinterpret_cast<void *>(address + width), kIdentityRadius},
    };
    ssize_t result;
    do {
        result = process_vm_readv(target.pid, local, 2, remote, 2, 0);
    } while (result < 0 && errno == EINTR);
    if (result != static_cast<ssize_t>(sizeof(context))) {
        return false;
    }
    hash = identityHash(context, context + kIdentityRadius);
"""
new_context = """    std::array<uint8_t, kIdentityRadius * 2U> context{};
    std::array<iovec, 2> local{{
            {context.data(), kIdentityRadius},
            {context.data() + kIdentityRadius, kIdentityRadius},
    }};
    std::array<iovec, 2> remote{{
            {reinterpret_cast<void *>(contextStart), kIdentityRadius},
            {reinterpret_cast<void *>(valueEnd), kIdentityRadius},
    }};
    ssize_t result;
    do {
        result = process_vm_readv(target.pid, local.data(), local.size(),
                                  remote.data(), remote.size(), 0);
    } while (result < 0 && errno == EINTR);
    if (result != static_cast<ssize_t>(context.size())) {
        return false;
    }
    hash = identityHash(std::span<const uint8_t>(context).first(kIdentityRadius),
                        std::span<const uint8_t>(context).last(kIdentityRadius));
"""
text = replace_once(text, old_context, new_context, "array identity context")
identity_end = """    return true;
}

void captureIdentities("""
identity_overload = """    return true;
}

bool readIdentity(const Target &target, uintptr_t address, jint rawType,
                  uint64_t &hash) {
    const auto type = valueTypeFromJint(rawType);
    return type.has_value() && readIdentity(target, address, *type, hash);
}

void captureIdentities("""
text = replace_once(text, identity_end, identity_overload, "raw readIdentity boundary")

# Display priority is now type-safe.
def typed_display(region: str) -> str:
    region = region.replace("jint type", "ValueType type", 1)
    for raw, typed in (
        ("kTypeInt", "ValueType::Int"),
        ("kTypeFloat", "ValueType::Float"),
        ("kTypeLong", "ValueType::Long"),
        ("kTypeDouble", "ValueType::Double"),
        ("kTypeShort", "ValueType::Short"),
        ("kTypeChar", "ValueType::Char"),
        ("kTypeByte", "ValueType::Byte"),
    ):
        region = region.replace(f"case {raw}:", f"case {typed}:")
    region = region.replace("    default:\n        return 7;\n", "    return 7;\n")
    return region

text = transform_region(text, "int displayTypePriority(", "bool candidateDisplayOrder(", typed_display, "display priority typed")
text = replace_once(text, "for (jint type : expandedTypes(requestedType)) {", "for (ValueType type : expandedTypes(requestedType)) {", "typed query expansion loop")
text = replace_once(text, "struct GroupMatch {\n    uintptr_t address;\n    jint type;\n", "struct GroupMatch {\n    uintptr_t address;\n    ValueType type;\n", "typed group match")

# Candidate refine dispatch uses a bounded std::array keyed only by ValueType.
old_dispatch = """        const Query *queriesByType[kTypeDouble + 1] = {};
        for (const Query &query : queries) {
            queriesByType[query.type] = &query;
        }
"""
new_dispatch = """        std::array<const Query *, kTypeSlotCount> queriesByType{};
        for (const Query &query : queries) {
            queriesByType[typeIndex(query.type)] = &query;
        }
"""
text = replace_once(text, old_dispatch, new_dispatch, "typed query dispatch array")
old_lookup = """            const Query *query = candidate.type <= kTypeDouble
                                         ? queriesByType[candidate.type]
                                         : nullptr;
"""
new_lookup = """            const Query *query = queriesByType[typeIndex(candidate.type)];
"""
text = replace_once(text, old_lookup, new_lookup, "typed query lookup")

# Recovery identity maps are fixed-size arrays indexed by the scoped ValueType.
text = replace_once(
    text,
    "    std::unordered_set<uint64_t> wantedIdentity[kTypeDouble + 1];\n    std::unordered_map<uint64_t, std::vector<size_t>>\n            freshByIdentity[kTypeDouble + 1];\n",
    "    std::array<std::unordered_set<uint64_t>, kTypeSlotCount> wantedIdentity{};\n    std::array<std::unordered_map<uint64_t, std::vector<size_t>>, kTypeSlotCount>\n            freshByIdentity{};\n",
    "typed recovery identity arrays",
)
text = text.replace("if (old.type <= kTypeDouble && old.identityValid) {", "if (old.identityValid) {")
text = text.replace("wantedIdentity[old.type]", "wantedIdentity[typeIndex(old.type)]")
text = text.replace("if (candidate.type <= kTypeDouble && candidate.identityValid &&\n                wantedIdentity[candidate.type]", "if (candidate.identityValid &&\n                wantedIdentity[typeIndex(candidate.type)]")
text = text.replace("wantedIdentity[candidate.type].end()", "wantedIdentity[typeIndex(candidate.type)].end()")
text = text.replace("freshByIdentity[candidate.type][candidate.identityHash]", "freshByIdentity[typeIndex(candidate.type)][candidate.identityHash]")
text = text.replace("if (!old.identityValid || old.type > kTypeDouble) {", "if (!old.identityValid) {")
text = text.replace("freshByIdentity[old.type].find", "freshByIdentity[typeIndex(old.type)].find")
text = text.replace("freshByIdentity[old.type].end()", "freshByIdentity[typeIndex(old.type)].end()")

# Remove raw Candidate* recovery lifetimes: selected owns candidates; recovery carries indices.
old_recover_sig = """jint recoverCandidatesBatch(const Target &target,
                            const std::vector<Candidate *> &recovery,
                            size_t &unsafeCount) {
"""
new_recover_sig = """jint recoverCandidatesBatch(const Target &target,
                            std::span<Candidate> candidates,
                            std::span<const size_t> recovery,
                            size_t &unsafeCount) {
"""
text = replace_once(text, old_recover_sig, new_recover_sig, "index recovery signature")
text = replace_once(
    text,
    "    std::unordered_map<uint64_t, std::vector<size_t>> wanted[kTypeDouble + 1];\n",
    "    std::array<std::unordered_map<uint64_t, std::vector<size_t>>, kTypeSlotCount> wanted{};\n",
    "typed recovery value array",
)
text = text.replace("Candidate &candidate = *recovery[index];", "Candidate &candidate = candidates[recovery[index]];")
text = text.replace("if (!candidate.identityValid || candidate.type > kTypeDouble ||\n            widthOf(candidate.type) == 0) {", "if (!candidate.identityValid || widthOf(candidate.type) == 0) {")
text = text.replace("wanted[candidate.type]", "wanted[typeIndex(candidate.type)]")
text = text.replace("Candidate &candidate = *recovery[recoveryIndex];", "Candidate &candidate = candidates[recovery[recoveryIndex]];")

old_refresh_recovery = """    std::vector<Candidate *> recovery;
    const auto refreshOne = [&](Candidate &candidate) {
"""
new_refresh_recovery = """    std::vector<size_t> recovery;
    const auto refreshOne = [&](Candidate &candidate, size_t candidateIndex) {
"""
text = replace_once(text, old_refresh_recovery, new_refresh_recovery, "index recovery collection")
text = replace_once(text, "            recovery.push_back(&candidate);", "            recovery.push_back(candidateIndex);", "index recovery push")
text = replace_once(
    text,
    "    for (Candidate &candidate : selected) {\n        refreshOne(candidate);\n    }\n",
    "    for (size_t index = 0; index < selected.size(); ++index) {\n        refreshOne(selected[index], index);\n    }\n",
    "indexed refresh loop",
)
text = replace_once(
    text,
    "            recoverCandidatesBatch(context.target, recovery, recoveryUnsafe);",
    "            recoverCandidatesBatch(context.target, selected, recovery, recoveryUnsafe);",
    "index recovery call",
)

# C++20 bit_cast makes representation intent explicit in the write path.
old_replace_bits = """        if (candidate.type == kTypeFloat) {
            const float value = static_cast<float>(query.floatingFirst);
            uint32_t raw = 0;
            std::memcpy(&raw, &value, sizeof(raw));
            bits = raw;
        } else {
            std::memcpy(&bits, &query.floatingFirst, sizeof(bits));
        }
"""
new_replace_bits = """        if (candidate.type == ValueType::Float) {
            const float value = static_cast<float>(query.floatingFirst);
            bits = std::bit_cast<uint32_t>(value);
        } else {
            bits = std::bit_cast<uint64_t>(query.floatingFirst);
        }
"""
text = replace_once(text, old_replace_bits, new_replace_bits, "bit_cast write encode")

# JNI serialization is the only place where ValueType intentionally becomes its stable raw int.
text = replace_once(text, "        output[base + 3U] = candidate.type;", "        output[base + 3U] = toJint(candidate.type);", "typed candidate serialization")

# Checked candidate window arithmetic.
old_window = """    const size_t width = widthOf(candidate.type);
    if (width == 0 || radius == 0 ||
        candidate.address > std::numeric_limits<uintptr_t>::max() - width) {
        return false;
    }
    const uintptr_t valueEnd = candidate.address + width;
"""
new_window = """    const size_t width = widthOf(candidate.type);
    uintptr_t valueEnd = 0;
    if (radius == 0 || !checkedAddressAdd(candidate.address, width, valueEnd)) {
        return false;
    }
"""
text = replace_once(text, old_window, new_window, "checked candidate window")

# CandidateValueReader reuses the already-proven value end instead of repeating additions.
old_reader = """        const size_t width = widthOf(candidate.type);
        if (width == 0 || candidate.address >
                                  std::numeric_limits<uintptr_t>::max() - width) {
            return false;
        }
        if (candidate.address >= cachedStart_ &&
            candidate.address + width <= cachedEnd_) {
"""
new_reader = """        const size_t width = widthOf(candidate.type);
        uintptr_t valueEnd = 0;
        if (!checkedAddressAdd(candidate.address, width, valueEnd)) {
            return false;
        }
        if (candidate.address >= cachedStart_ && valueEnd <= cachedEnd_) {
"""
text = replace_once(text, old_reader, new_reader, "checked candidate reader")
text = replace_once(text, "            candidate.address + width > containing.end) {", "            valueEnd > containing.end) {", "reader containing bound")

# Small RAII wrapper guarantees GetStringUTFChars is always paired with ReleaseStringUTFChars.
old_jstring = """std::string fromJString(JNIEnv *env, jstring value) {
    if (value == nullptr) {
        return {};
    }
    const char *characters = env->GetStringUTFChars(value, nullptr);
    if (characters == nullptr) {
        return {};
    }
    std::string result(characters);
    env->ReleaseStringUTFChars(value, characters);
    return result;
}
"""
new_jstring = """class ScopedUtfChars final {
  public:
    ScopedUtfChars(JNIEnv *env, jstring value) noexcept
        : env_(env), value_(value), chars_(value == nullptr ? nullptr
                                                            : env->GetStringUTFChars(value, nullptr)) {}
    ~ScopedUtfChars() {
        if (chars_ != nullptr) {
            env_->ReleaseStringUTFChars(value_, chars_);
        }
    }
    ScopedUtfChars(const ScopedUtfChars &) = delete;
    ScopedUtfChars &operator=(const ScopedUtfChars &) = delete;

    [[nodiscard]] const char *get() const noexcept { return chars_; }

  private:
    JNIEnv *env_;
    jstring value_;
    const char *chars_;
};

std::string fromJString(JNIEnv *env, jstring value) {
    if (value == nullptr) {
        return {};
    }
    const ScopedUtfChars characters(env, value);
    return characters.get() == nullptr ? std::string{} : std::string(characters.get());
}
"""
text = replace_once(text, old_jstring, new_jstring, "RAII JNI UTF chars")

# Known enum/raw friction points.
text = text.replace("candidate.type == kTypeFloat", "candidate.type == ValueType::Float")
text = text.replace("query.type == kTypeFloat", "query.type == ValueType::Float")

# Strong type must not leak through implicit indexing or serialization.
if "queriesByType[query.type]" in text or "freshByIdentity[old.type]" in text:
    raise SystemExit("engine: unconverted ValueType array index remains")

p.write_text(text)


# 3) Target-local range collector: type the scope and stop using pair.first/pair.second.
p = Path("app/src/main/cpp/memory/target_probe.cpp")
text = p.read_text()
text = replace_once(text, "#include <cerrno>\n#include <cstdint>\n", "#include <array>\n#include <cerrno>\n#include <cstdint>\n", "target array include")
text = replace_once(text, "#include <limits>\n#include <string>\n", "#include <limits>\n#include <optional>\n#include <string>\n", "target optional include")
old_scope = """constexpr jint kFastScope = 0;
constexpr jint kThoroughScope = 1;
alignas(uint64_t) volatile uint64_t gReadProbe = UINT64_C(0x4a4c4d454d50524f);

bool isSelectedMap(const char *permissions, const std::string &name,
                   jint scope) {
"""
new_scope = """constexpr jint kFastScope = 0;
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
"""
text = replace_once(text, old_scope, new_scope, "typed target scope")
text = text.replace("scope == kFastScope", "scope == ScanScope::Fast")
text = text.replace("scope != kThoroughScope", "scope != ScanScope::Thorough")
text = replace_once(text, "bool appendRun(std::vector<std::pair<uintptr_t, uintptr_t>> &runs,", "bool appendRun(std::vector<ResidentRun> &runs,", "typed resident runs")
text = text.replace("runs.back().second", "runs.back().end")
text = text.replace("runs.emplace_back(start, end);", "runs.push_back({start, end});")
old_validate_scope = """    const long pageValue = sysconf(_SC_PAGESIZE);
    if ((scope != kFastScope && scope != kThoroughScope) || maxRuns <= 0 ||
        pageValue <= 0) {
        return nullptr;
    }
    const size_t pageSize = static_cast<size_t>(pageValue);
"""
new_validate_scope = """    const long pageValue = sysconf(_SC_PAGESIZE);
    const auto selectedScope = scanScopeFromJint(scope);
    if (!selectedScope.has_value() || maxRuns <= 0 || pageValue <= 0) {
        return nullptr;
    }
    const size_t pageSize = static_cast<size_t>(pageValue);
"""
text = replace_once(text, old_validate_scope, new_validate_scope, "scope boundary validation")
text = replace_once(text, "        std::vector<std::pair<uintptr_t, uintptr_t>> runs;", "        std::vector<ResidentRun> runs;", "resident run vector")
text = replace_once(text, "            char permissions[5] = {};", "            std::array<char, 5> permissions{};", "permissions array")
text = replace_once(text, "&rawEnd, permissions, &nameOffset)", "&rawEnd, permissions.data(), &nameOffset)", "permissions data")
text = replace_once(text, "            if (!isSelectedMap(permissions, name, scope)) {", "            if (!isSelectedMap(permissions.data(), name, *selectedScope)) {", "typed scope use")
text = text.replace("runs[index].first", "runs[index].start")
text = text.replace("runs[index].second", "runs[index].end")
p.write_text(text)

print("Applied guarded native C++ hardening")
