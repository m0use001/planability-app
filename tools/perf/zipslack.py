# -*- coding: utf-8 -*-
"""Scan zip local file headers to find where the bytes actually go (finds padding/slack)."""
import io
import struct
import sys

path = sys.argv[1]
data = io.open(path, "rb").read()
size = len(data)
print("file size: %.2f MB" % (size / 1048576.0))

pos = 0
entries = []
while True:
    sig = data.find(b"PK\x03\x04", pos)
    if sig < 0:
        break
    (ver, flags, method, mtime, mdate, crc, csize, usize,
     nlen, elen) = struct.unpack_from("<HHHHHIIIHH", data, sig + 4)
    name = data[sig + 30: sig + 30 + nlen].decode("utf-8", "replace")
    start = sig + 30 + nlen + elen
    entries.append((name, method, usize, csize, start, start + csize))
    pos = start + csize

print("local entries found: %d" % len(entries))
print("--- 15 largest stored/compressed entries ---")
for name, method, usize, csize, start, end in sorted(entries, key=lambda e: -e[3])[:15]:
    m = "store" if method == 0 else "deflate"
    print("  %8.2f MB csize  %8.2f MB usize  %-8s %s  @%d" % (csize / 1048576.0, usize / 1048576.0, m, name[:52], start))

tot_c = sum(e[3] for e in entries)
print("sum csize: %.2f MB" % (tot_c / 1048576.0))
print("unaccounted (file - entries): %.2f MB" % ((size - tot_c) / 1048576.0))

# find the biggest gaps between consecutive entries
entries.sort(key=lambda e: e[4])
print("--- 8 largest gaps between entries ---")
for i in range(1, len(entries)):
    gap = entries[i][4] - entries[i - 1][5]
    if gap > 4096:
        print("  %8.2f MB gap  after %s" % (gap / 1048576.0, entries[i - 1][0][:46]))
print("--- tail ---")
print("  bytes after last entry: %.2f MB" % ((size - max(e[5] for e in entries)) / 1048576.0))
