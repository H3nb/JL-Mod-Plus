from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one marker, found {count}")
    return text.replace(old, new, 1)


p = Path("app/src/main/cpp/memory/memory_engine.cpp")
text = p.read_text()

# A default-constructed materialized value must be invalid, not silently Int32.
text = replace_once(
    text,
    "enum class ValueType : uint8_t {\n    Byte = kTypeByte,",
    "enum class ValueType : uint8_t {\n    Invalid = kTypeAuto,\n    Byte = kTypeByte,",
    "invalid ValueType sentinel",
)
text = replace_once(
    text,
    "static_assert(sizeof(ValueType) == sizeof(uint8_t));\nstatic_assert(toJint(ValueType::Double) == kTypeDouble);",
    """static_assert(sizeof(ValueType) == sizeof(uint8_t));
static_assert(toJint(ValueType::Invalid) == kTypeAuto);
static_assert(toJint(ValueType::Byte) == kTypeByte);
static_assert(toJint(ValueType::Short) == kTypeShort);
static_assert(toJint(ValueType::Char) == kTypeChar);
static_assert(toJint(ValueType::Int) == kTypeInt);
static_assert(toJint(ValueType::Long) == kTypeLong);
static_assert(toJint(ValueType::Float) == kTypeFloat);
static_assert(toJint(ValueType::Double) == kTypeDouble);""",
    "stable JNI type mapping assertions",
)
text = text.replace("ValueType type = ValueType::Int;", "ValueType type = ValueType::Invalid;")
if text.count("ValueType type = ValueType::Invalid;") != 2:
    raise SystemExit("expected Candidate and Query to use invalid type defaults")

text = replace_once(
    text,
    """[[nodiscard]] constexpr size_t widthOf(ValueType type) noexcept {
    switch (type) {
    case ValueType::Byte:
        return 1;""",
    """[[nodiscard]] constexpr size_t widthOf(ValueType type) noexcept {
    switch (type) {
    case ValueType::Invalid:
        return 0;
    case ValueType::Byte:
        return 1;""",
    "invalid type width",
)
width_end = """    }
    return 0;
}

[[nodiscard]] size_t widthOf(jint rawType) noexcept {"""
width_assertions = """    }
    return 0;
}

static_assert(widthOf(ValueType::Invalid) == 0);
static_assert(widthOf(ValueType::Byte) == 1);
static_assert(widthOf(ValueType::Short) == 2);
static_assert(widthOf(ValueType::Char) == 2);
static_assert(widthOf(ValueType::Int) == 4);
static_assert(widthOf(ValueType::Long) == 8);
static_assert(widthOf(ValueType::Float) == 4);
static_assert(widthOf(ValueType::Double) == 8);

[[nodiscard]] size_t widthOf(jint rawType) noexcept {"""
text = replace_once(text, width_end, width_assertions, "value-width assertions")

# Make the identity-window proof staged and overflow-obvious.
text = replace_once(
    text,
    """bool snapshotIdentity(std::span<const uint8_t> bytes, size_t offset,
                      size_t width, uint64_t &hash) noexcept {
    if (offset < kIdentityRadius || width > bytes.size() - std::min(offset, bytes.size()) ||
        offset + width > bytes.size() || bytes.size() - offset - width < kIdentityRadius) {
        return false;
    }
    hash = identityHash(bytes.subspan(offset - kIdentityRadius, kIdentityRadius),
                        bytes.subspan(offset + width, kIdentityRadius));
    return true;
}""",
    """bool snapshotIdentity(std::span<const uint8_t> bytes, size_t offset,
                      size_t width, uint64_t &hash) noexcept {
    if (offset < kIdentityRadius || offset > bytes.size() ||
        width > bytes.size() - offset) {
        return false;
    }
    const size_t valueEnd = offset + width;
    if (bytes.size() - valueEnd < kIdentityRadius) {
        return false;
    }
    hash = identityHash(bytes.subspan(offset - kIdentityRadius, kIdentityRadius),
                        bytes.subspan(valueEnd, kIdentityRadius));
    return true;
}""",
    "staged identity span bounds",
)

# Prove the complete remote range before the first syscall. This is especially important
# for writes: an overflow discovered after a partial write would violate fail-before-side-effect.
checked_add_end = """    result = base + static_cast<uintptr_t>(offset);
    return true;
}

bool readExact(pid_t pid, uintptr_t address, void *destination, size_t size) {"""
checked_span = """    result = base + static_cast<uintptr_t>(offset);
    return true;
}

[[nodiscard]] constexpr bool isAddressSpanValid(uintptr_t address,
                                                size_t size) noexcept {
    if (size == 0U) {
        return true;
    }
    uintptr_t lastByte = 0;
    return checkedAddressAdd(address, size - 1U, lastByte);
}

bool readExact(pid_t pid, uintptr_t address, void *destination, size_t size) {"""
text = replace_once(text, checked_add_end, checked_span, "whole address span helper")
text = replace_once(
    text,
    """bool readExact(pid_t pid, uintptr_t address, void *destination, size_t size) {
    if (destination == nullptr && size != 0U) {
        return false;
    }""",
    """bool readExact(pid_t pid, uintptr_t address, void *destination, size_t size) {
    if ((destination == nullptr && size != 0U) ||
        !isAddressSpanValid(address, size)) {
        return false;
    }""",
    "read span preflight",
)
text = replace_once(
    text,
    """bool writeExact(pid_t pid, uintptr_t address, const void *source, size_t size) {
    if (source == nullptr && size != 0U) {
        return false;
    }""",
    """bool writeExact(pid_t pid, uintptr_t address, const void *source, size_t size) {
    if ((source == nullptr && size != 0U) ||
        !isAddressSpanValid(address, size)) {
        return false;
    }""",
    "write span preflight",
)

# Invalid defaults must remain fail-closed at bounded-read helpers.
text = replace_once(
    text,
    """    const size_t width = widthOf(candidate.type);
    uintptr_t valueEnd = 0;
    if (radius == 0 || !checkedAddressAdd(candidate.address, width, valueEnd)) {
        return false;
    }""",
    """    const size_t width = widthOf(candidate.type);
    uintptr_t valueEnd = 0;
    if (width == 0 || radius == 0 ||
        !checkedAddressAdd(candidate.address, width, valueEnd)) {
        return false;
    }""",
    "candidate window invalid type guard",
)
text = replace_once(
    text,
    """        const size_t width = widthOf(candidate.type);
        uintptr_t valueEnd = 0;
        if (!checkedAddressAdd(candidate.address, width, valueEnd)) {
            return false;
        }""",
    """        const size_t width = widthOf(candidate.type);
        uintptr_t valueEnd = 0;
        if (width == 0 || !checkedAddressAdd(candidate.address, width, valueEnd)) {
            return false;
        }""",
    "candidate reader invalid type guard",
)

# Recovery indices are internal today, but span indexing should still fail closed if a future
# refactor breaks that invariant.
recover_start = """    unsafeCount = 0;
    if (recovery.empty()) {
        return kOk;
    }
    std::array<std::unordered_map<uint64_t, std::vector<size_t>>, kTypeSlotCount> wanted{};"""
recover_checked = """    unsafeCount = 0;
    if (recovery.empty()) {
        return kOk;
    }
    if (std::any_of(recovery.begin(), recovery.end(),
                    [&](size_t index) { return index >= candidates.size(); })) {
        setMessage("Invalid relocation recovery index");
        return kInvalidRequest;
    }
    std::array<std::unordered_map<uint64_t, std::vector<size_t>>, kTypeSlotCount> wanted{};"""
text = replace_once(text, recover_start, recover_checked, "recovery index preflight")

# Guard against accidental serialization of the Invalid sentinel even if a future caller manages
# to construct one. Candidate rows must always remain TYPE_BYTE..TYPE_DOUBLE.
serialize = """        output[base + 2U] = static_cast<jlong>(candidate.previousAddress);
        output[base + 3U] = toJint(candidate.type);
        output[base + 4U] = candidate.state;"""
serialize_checked = """        output[base + 2U] = static_cast<jlong>(candidate.previousAddress);
        const jint serializedType = toJint(candidate.type);
        if (serializedType < kTypeByte || serializedType > kTypeDouble) {
            return nullptr;
        }
        output[base + 3U] = serializedType;
        output[base + 4U] = candidate.state;"""
text = replace_once(text, serialize, serialize_checked, "candidate serialization guard")

p.write_text(text)
print("Applied Memory Editor native regression-audit fixes")
