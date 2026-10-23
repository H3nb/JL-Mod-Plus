# SPDX-License-Identifier: Apache-2.0
import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile


spec = importlib.util.spec_from_file_location('elf_alignment', Path(__file__).resolve().parents[1] / 'check-elf-alignment.py')
alignment = importlib.util.module_from_spec(spec)
spec.loader.exec_module(alignment)


def elf(machine=183, page=16384, virtual_address=0, file_offset=0):
    data = bytearray(120)
    data[:16] = b'\x7fELF\x02\x01\x01' + b'\0' * 9
    struct.pack_into('<HHIQQQIHHHHHH', data, 16, 3, machine, 1, 0, 64, 0, 0, 64, 56, 1, 0, 0, 0)
    struct.pack_into('<IIQQQQQQ', data, 64, 1, 5, file_offset, virtual_address, 0, 0, 4096, page)
    return data


class ElfAlignmentTest(unittest.TestCase):
    def test_all_load_segments_require_16kb_alignment_and_matching_architecture(self):
        self.assertEqual(alignment.load_segments(elf(), 'arm64-v8a')[0]['alignment'], 16384)
        for data in [elf(page=4096), elf(page=32767), elf(virtual_address=4096), elf(machine=62), elf()[:90], b'not-elf']:
            with self.subTest(data=data), self.assertRaises(ValueError):
                alignment.load_segments(data, 'arm64-v8a')

    def test_one_bad_library_in_universal_apk_fails_even_when_others_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / 'universal.apk'
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('lib/arm64-v8a/libgood.so', elf())
                archive.writestr('lib/arm64-v8a/libbad.so', elf(page=4096))
                archive.writestr('lib/x86_64/libgood.so', elf(machine=62))
            result = alignment.inspect_apks([apk])
            self.assertEqual(len(result['libraries']), 2)
            self.assertEqual(len(result['problems']), 1)
            self.assertIn('libbad.so', result['problems'][0])

    def test_later_load_segment_is_also_checked(self):
        data = elf()
        struct.pack_into('<H', data, 56, 2)
        data.extend(b'\0' * 56)
        struct.pack_into('<IIQQQQQQ', data, 120, 1, 5, 0, 0, 0, 0, 4096, 4096)
        with self.assertRaisesRegex(ValueError, 'segment 1 alignment'):
            alignment.load_segments(data, 'arm64-v8a')

    def test_missing_apk_or_architecture_cannot_pass(self):
        self.assertTrue(alignment.inspect_apks([Path('/no-such-qualification.apk')])['problems'])
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / 'arm64.apk'
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('lib/arm64-v8a/libgood.so', elf())
            self.assertIn('No x86_64 libraries', '\n'.join(alignment.inspect_apks([apk])['problems']))


if __name__ == '__main__':
    unittest.main()
