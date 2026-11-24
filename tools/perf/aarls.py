# -*- coding: utf-8 -*-
"""Inspect androidx AARs in the gradle cache: read the nested classes.jar and list matching classes."""
import glob
import io
import os
import sys
import zipfile

root = r"C:\Users\test\.gradle\caches\modules-2\files-2.1"
patterns = sys.argv[1:] or ["androidx.benchmark"]
keys = ("CompilationMode", "StartupTiming", "MacrobenchmarkRule", "LargeTest", "AndroidJUnit4", "uiAutomator", "UiAutomator", "Filters")

for p in patterns:
    for aar in sorted(glob.glob(os.path.join(root, p, "**", "*.aar"), recursive=True)):
        rel = aar.replace(root + os.sep, "")
        try:
            with zipfile.ZipFile(aar) as z:
                if "classes.jar" not in z.namelist():
                    print("==", rel, "(no classes.jar)")
                    continue
                data = z.read("classes.jar")
        except Exception as e:
            print("!!", rel, e)
            continue
        tmp = os.path.join(os.environ["TEMP"], "cjar.jar")
        io.open(tmp, "wb").write(data)
        try:
            with zipfile.ZipFile(tmp) as z2:
                names = [n for n in z2.namelist() if n.endswith(".class")]
        except Exception as e:
            print("!!", rel, e)
            continue
        hits = [n for n in names if any(k in n for k in keys)]
        print("==", rel, "(%d classes)" % len(names))
        for n in sorted(hits)[:16]:
            print("     ", n)
