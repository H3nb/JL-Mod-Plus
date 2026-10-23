# SPDX-License-Identifier: Apache-2.0
"""Check every arm64-v8a/x86_64 ELF LOAD segment in qualification APKs."""
import argparse
import json
import os
from pathlib import Path
import struct
import zipfile


MACHINES = {'arm64-v8a': 183, 'x86_64': 62}
PAGE_SIZE = 16384


def load_segments(data, abi):
    if len(data) < 64 or data[:7] != b'\x7fELF\x02\x01\x01':
        raise ValueError('Expected little-endian ELF64 version 1')
    header = struct.unpack_from('<HHIQQQIHHHHHH', data, 16)
    elf_type, machine, version, _, offset, _, _, header_size, entry_size, count, *_ = header
    if elf_type != 3 or machine != MACHINES[abi] or version != 1:
        raise ValueError(f'Expected {abi} shared-object ELF')
    if header_size != 64 or entry_size != 56 or not count or count == 65535:
        raise ValueError('Invalid or unsupported ELF program-header table')
    if offset < 64 or offset + count * entry_size > len(data):
        raise ValueError('Truncated ELF program-header table')
    segments = []
    for index in range(count):
        kind, _, file_offset, virtual_address, _, file_size, memory_size, alignment = struct.unpack_from(
            '<IIQQQQQQ', data, offset + index * entry_size)
        if kind != 1:  # PT_LOAD
            continue
        if file_offset + file_size > len(data) or file_size > memory_size:
            raise ValueError(f'Invalid LOAD segment {index} bounds')
        if alignment < PAGE_SIZE or alignment & (alignment - 1):
            raise ValueError(f'LOAD segment {index} alignment {alignment:#x} is below 16 KB or invalid')
        if (virtual_address - file_offset) % alignment:
            raise ValueError(f'LOAD segment {index} virtual address/file offset are not congruent')
        segments.append(dict(index=index, alignment=alignment, offset=file_offset, virtual_address=virtual_address))
    if not segments:
        raise ValueError('ELF has no LOAD segments')
    return segments


def inspect_apks(paths):
    libraries, problems, found_abis = [], [], set()
    for path in paths:
        try:
            with zipfile.ZipFile(path) as archive:
                seen = set()
                for info in archive.infolist():
                    parts = info.filename.split('/')
                    if len(parts) != 3 or parts[0] != 'lib' or parts[1] not in MACHINES or not parts[2].endswith('.so'):
                        continue
                    identity = f'{path}:{info.filename}'
                    try:
                        if info.filename in seen:
                            raise ValueError('Duplicate library ZIP entry')
                        seen.add(info.filename)
                        found_abis.add(parts[1])
                        segments = load_segments(archive.read(info), parts[1])
                        libraries.append(dict(apk=str(path), library=info.filename, segments=segments))
                    except (ValueError, OSError, RuntimeError, zipfile.BadZipFile) as error:
                        problems.append(f'{identity}: {error}')
        except (OSError, zipfile.BadZipFile) as error:
            problems.append(f'{path}: {error}')
    for abi in MACHINES.keys() - found_abis:
        problems.append(f'No {abi} libraries found in qualification APKs')
    return dict(schema_version=1, page_size=PAGE_SIZE, libraries=libraries, problems=problems,
                scope='ELF LOAD alignment only; device startup and ZIP alignment are separate checks.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apks', nargs='+', type=Path)
    parser.add_argument('--output', type=Path, default=Path('ci-artifacts/release-smoke/elf-alignment.json'))
    args = parser.parse_args()
    result = inspect_apks(args.apks)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    lines = [f"16 KB ELF qualification: {len(result['libraries'])} libraries checked."]
    lines.extend(f"PASS {item['apk']}:{item['library']} ({len(item['segments'])} LOAD segments)" for item in result['libraries'])
    lines.extend(f'FAIL {problem}' for problem in result['problems'])
    lines.append(result['scope'])
    summary = '\n'.join(lines) + '\n'
    print(summary, end='')
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a', encoding='utf-8') as output:
            output.write(summary)
    raise SystemExit(bool(result['problems']))


if __name__ == '__main__':
    main()
