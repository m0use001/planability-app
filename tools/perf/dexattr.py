# -*- coding: utf-8 -*-
"""Attribute kept/removed classes to packages using R8 mapping.txt / usage.txt.

mapping.txt lines:   com.foo.Bar -> a.b.c:
usage.txt lines:     com.foo.Bar            (removed)
Both are one class per line, so grouping by the first 2-3 package segments
gives a cheap per-library estimate of how much code survives R8.
"""
import io
import re
import sys
from collections import Counter

mode = sys.argv[1]          # "map" or "usage"
path = sys.argv[2]
depth = int(sys.argv[3]) if len(sys.argv) > 3 else 2
out = sys.argv[4]

rx_map = re.compile(r"^([\w.$]+?)\.\w[\w$]* -> ")
rx_use = re.compile(r"^([\w.$]+?)\.\w[\w$]*$")

cnt = Counter()
total = 0
with io.open(path, encoding="utf-8", errors="replace") as f:
    for line in f:
        line = line.rstrip("\n")
        if not line or line[0] in ("#", " ", "\t"):
            continue
        m = (rx_map if mode == "map" else rx_use).match(line)
        if not m:
            continue
        total += 1
        parts = m.group(1).split(".")
        cnt[".".join(parts[:depth])] += 1

lines = ["total classes: %d" % total, ""]
for name, n in cnt.most_common(45):
    lines.append("%7d  %s" % (n, name))
io.open(out, "w", encoding="utf-8", newline="").write("\n".join(lines))
print("ok total=%d groups=%d" % (total, len(cnt)))
