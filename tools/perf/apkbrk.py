# -*- coding: utf-8 -*-
"""Per-entry APK breakdown using CompressedLength (what the .apk file actually costs)."""
import io
import sys
import zipfile
from collections import Counter

path = sys.argv[1]
z = zipfile.ZipFile(path)
tot = onion = 0
groups = Counter()
rows = []
for i in z.infolist():
    if i.filename.endswith("/"):
        continue
    tot += i.compress_size
    top = i.filename.split("/")[0]
    groups[top] += i.compress_size
    rows.append((i.compress_size, i.file_size, i.filename))

print("%s  disk=%.1f MB  sum(compressed)=%.1f MB" % (path, io.open(path, "rb").seek(0, 2) / 1048576.0, tot / 1048576.0))
print("--- by top dir (compressed) ---")
for k, v in groups.most_common(12):
    print("  %7.2f MB  %s" % (v / 1048576.0, k))
print("--- top 18 entries by compressed size ---")
for c, u, n in sorted(rows, reverse=True)[:18]:
    print("  %7.2f MB (raw %7.2f)  %s" % (c / 1048576.0, u / 1048576.0, n))
