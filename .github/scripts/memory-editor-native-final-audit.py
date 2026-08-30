from pathlib import Path

p = Path('app/src/main/cpp/memory/memory_engine.cpp')
text = p.read_text()

old = '''bool snapshotIdentity(std::span<const uint8_t> bytes, size_t offset,
                      size_t width, uint64_t &hash) noexcept {
    if (offset < kIdentityRadius || offset > bytes.size() ||
        width > bytes.size() - offset) {
'''
new = '''bool snapshotIdentity(std::span<const uint8_t> bytes, size_t offset,
                      size_t width, uint64_t &hash) noexcept {
    if (width == 0 || offset < kIdentityRadius || offset > bytes.size() ||
        width > bytes.size() - offset) {
'''
if text.count(old) != 1:
    raise SystemExit(f'snapshotIdentity marker expected once, found {text.count(old)}')
text = text.replace(old, new, 1)

old = '''    const size_t width = widthOf(type);
    uintptr_t valueEnd = 0;
    uintptr_t contextEnd = 0;
    if (address < kIdentityRadius ||
        !checkedAddressAdd(address, width, valueEnd) ||
'''
new = '''    const size_t width = widthOf(type);
    uintptr_t valueEnd = 0;
    uintptr_t contextEnd = 0;
    if (width == 0 || address < kIdentityRadius ||
        !checkedAddressAdd(address, width, valueEnd) ||
'''
if text.count(old) != 1:
    raise SystemExit(f'readIdentity marker expected once, found {text.count(old)}')
text = text.replace(old, new, 1)

p.write_text(text)
print('Applied final zero-width identity guards')
