"""Inspect NBT array lengths without allocating their payloads."""
import gzip
import json
import struct
import sys
import time

start = time.monotonic()
arrays = []
offset = 0
retained = 0
with gzip.open(sys.argv[1], 'rb') as stream:
    def read(length):
        global offset
        data = stream.read(length)
        if len(data) != length:
            raise EOFError((offset, length, len(data)))
        offset += length
        return data

    def skip(length):
        while length:
            size = min(length, 4 * 1024 * 1024)
            read(size)
            length -= size

    def string():
        return read(struct.unpack('>H', read(2))[0]).decode('utf-8', errors='replace')

    def value(kind, path):
        if kind in (1, 2, 3, 4, 5, 6):
            skip({1: 1, 2: 2, 3: 4, 4: 8, 5: 4, 6: 8}[kind])
        elif kind in (7, 11, 12):
            global retained
            length = struct.unpack('>i', read(4))[0]
            size = length * {7: 1, 11: 4, 12: 8}[kind]
            arrays.append({'path': path, 'length': length, 'bytes': size})
            if kind == 12 and path.endswith('/BlockStates'):
                remaining = size
                while remaining:
                    data = read(min(remaining, 32768))
                    if data.strip(b'\x00'):
                        retained += len(data)
                    remaining -= len(data)
            else:
                skip(size)
        elif kind == 8:
            string()
        elif kind == 9:
            element = read(1)[0]
            length = struct.unpack('>i', read(4))[0]
            for index in range(length):
                value(element, f'{path}[{index}]')
        elif kind == 10:
            while (child := read(1)[0]) != 0:
                name = string()
                value(child, path + '/' + name)
        else:
            raise ValueError((kind, path, offset))

    kind = read(1)[0]
    value(kind, string())

print(json.dumps({'decodedBytes': offset, 'arrayBytes': sum(a['bytes'] for a in arrays),
                  'nonzeroPageBytes': retained,
                  'arrays': sorted(arrays, key=lambda a: -a['bytes'])[:30],
                  'seconds': time.monotonic() - start}, ensure_ascii=False, indent=2))
