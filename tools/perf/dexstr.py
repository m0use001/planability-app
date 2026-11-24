# -*- coding: utf-8 -*-
"""Search a .dex (or any binary) for plain strings — DEX string tables are UTF-8."""
import io
import sys

path = sys.argv[1]
needles = [s.encode("utf-8") for s in sys.argv[2:]]
data = io.open(path, "rb").read()
print("%s  %.1f MB" % (path, len(data) / 1048576.0))
for nd in needles:
    print("  %6d  %s" % (data.count(nd), nd.decode("utf-8")))
