"""Read class definitions, not mere type references, from standard Android DEX files."""
import io
import re
import struct
import zipfile


def dex_classes(data):
    if data[:8] not in [b'dex\n' + v + b'\0' for v in (b'035', b'037', b'038', b'039', b'040')]:
        raise ValueError('Unsupported DEX format')

    def uint(offset):
        return struct.unpack_from('<I', data, offset)[0]

    if uint(32) != len(data) or uint(36) != 112 or uint(40) != 0x12345678:
        raise ValueError('Invalid DEX header')
    string_count, strings = uint(56), uint(60)
    type_count, types = uint(64), uint(68)
    class_count, classes = uint(96), uint(100)
    result = set()
    for index in range(class_count):
        type_index = uint(classes + index * 32)
        if type_index >= type_count:
            raise ValueError('Invalid DEX class type')
        string_index = uint(types + type_index * 4)
        if string_index >= string_count:
            raise ValueError('Invalid DEX descriptor')
        offset = uint(strings + string_index * 4)
        for _ in range(5):  # Skip ULEB128 UTF-16 string length.
            byte = data[offset]
            offset += 1
            if byte < 128:
                break
        else:
            raise ValueError('Invalid DEX string length')
        descriptor = data[offset:data.index(b'\0', offset)].decode('utf-8')
        if not descriptor.startswith('L') or not descriptor.endswith(';'):
            raise ValueError('Invalid class descriptor')
        result.add(descriptor[1:-1] + '.class')
    return result


def verify_apk_classes(apk, aar):
    with zipfile.ZipFile(aar) as archive:
        with zipfile.ZipFile(io.BytesIO(archive.read('classes.jar'))) as jar:
            expected = {n for n in jar.namelist() if n.endswith('.class') and n != 'module-info.class'}
    with zipfile.ZipFile(apk) as archive:
        actual = set()
        for name in archive.namelist():
            if re.fullmatch(r'classes(?:[2-9]|[1-9][0-9]+)?\.dex', name):
                actual.update(dex_classes(archive.read(name)))
    if not expected or not expected <= actual:
        raise AssertionError(f'{apk}: missing SDK class definitions: {sorted(expected - actual)}')
    return len(expected)
