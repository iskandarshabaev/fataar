"""Regression: a referenced type is not proof that the APK defines that class."""
import struct
import unittest

from dex_inventory import dex_classes


class DexInventoryTest(unittest.TestCase):
    def test_distinguishes_definition_from_reference(self):
        # Two type_ids, but only the first has a class_def.
        data = bytearray(112 + 8 + 8 + 32)
        data[:8] = b'dex\n039\0'
        def uint(offset, value):
            struct.pack_into('<I', data, offset, value)
        uint(36, 112)
        uint(40, 0x12345678)
        uint(56, 2)
        uint(60, 112)
        uint(64, 2)
        uint(68, 120)
        uint(96, 1)
        uint(100, 128)
        for index, descriptor in enumerate((b'Lsdk/Present;', b'Lsdk/Missing;')):
            uint(112 + index * 4, len(data))
            uint(120 + index * 4, index)
            data.extend(bytes([len(descriptor)]) + descriptor + b'\0')
        uint(32, len(data))
        self.assertEqual({'sdk/Present.class'}, dex_classes(bytes(data)))

    def test_unknown_format_fails(self):
        with self.assertRaises(ValueError):
            dex_classes(b'dex\n041\0')


if __name__ == '__main__':
    unittest.main()
