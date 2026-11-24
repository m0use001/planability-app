# -*- coding: utf-8 -*-
"""notosanssc.ttf 子集化（体积阶段 1 第 1 项，ref-perf-study.md §3 阶段 1.1）。

原字体：Google Fonts 的 Noto Sans SC **变量字体**（wght 100-900，9 个命名实例），
30,890 个 cmap 字符（20,976 个 CJK），原始 17.8MB / APK 压缩后 ~10.75MB——APK 最大单项。

子集字符集（可复现，零网络依赖，纯 stdlib 生成）：
- ASCII + Latin-1 补充区（度数符号、货币等）
- U+2000-206F 通用标点（– — ‘ ’ “ ” … 等）、U+2190-2193 箭头、
  U+2460-24FF 圈号（①-⑳）、U+25A0-25FF 几何形（■ □ ● ○ 等）、U+2605-2606 星号
- U+3000-303F 中日韩标点、U+FF00-FFEF 全角形式
- **GB2312 全表 6763 汉字**（含一级 3755 + 二级 3008；直接从 gb2312 编解码表生成）

不在子集里的生僻字/罕见字由 Android **逐字回退到系统字体链**渲染（ColorOS 自带全量
CJK），豆腐块风险与「默认（系统字体）」体验一致。变量轴 wght 必须保留——Type.kt 的
字重偏移（fontWeight 设置 300-800）依赖它。

用法（需 fonttools：python -m pip install fonttools）：
    python tools/fonts/subset_notosanssc.py app/src/main/res/font/notosanssc.ttf
默认原地替换并打印前后字节数；加 --out <path> 写到别处先看尺寸。
"""
import argparse
import os
import sys

def charset_bytes():
    yield from range(0x20, 0x7F)          # ASCII
    yield from range(0xA0, 0x100)         # Latin-1 补充
    yield from range(0x2000, 0x2070)      # 通用标点
    yield from range(0x2190, 0x2194)      # ← ↑ → ↓
    yield from range(0x2460, 0x2500)      # ①-⑳ 及带圈数字
    yield from range(0x25A0, 0x2600)      # 几何形
    yield from range(0x2605, 0x2607)      # ★ ☆
    yield from range(0x3000, 0x3040)      # CJK 标点（、。「」【】…）
    yield from range(0xFF01, 0xFF65)      # 全角标点与英数字
    yield from range(0xFFE0, 0xFFE7)      # ￠ ￡ ￥ ￦

def gb2312_chars():
    for hi in range(0xA1, 0xF8):
        for lo in range(0xA1, 0xFF):
            try:
                yield bytes([hi, lo]).decode('gb2312')
            except UnicodeDecodeError:
                pass  # 码位空洞，跳过

def build_text():
    parts = []
    parts.append(''.join(chr(c) for c in charset_bytes()))
    parts.append(''.join(gb2312_chars()))
    return ''.join(parts)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('font', help='原 notosanssc.ttf 路径')
    ap.add_argument('--out', help='输出路径（缺省=原地替换）')
    args = ap.parse_args()

    from fontTools.subset import Subsetter, Options, load_font, save_font

    text = build_text()
    unicodes = sorted({ord(c) for c in text})
    print(f"charset: {len(unicodes)} codepoints (GB2312 汉字含标点全表)")

    before = os.path.getsize(args.font)
    opts = Options()
    opts.layout_features = ['*']      # 保留 kern/vert 等全部特性
    opts.name_IDs = [1, 2, 3, 4, 6]   # 只留基本命名（Android 用资源引用，不吃 name 表）
    opts.notdef_outline = True
    opts.glyph_names = False
    opts.prune_unicode_ranges = True
    opts.drop_tables += ['FFTM']

    font = load_font(args.font, opts)
    ss = Subsetter(options=opts)
    ss.populate(unicodes=unicodes)
    ss.subset(font)

    out = args.out or args.font
    save_font(font, out, opts)
    after = os.path.getsize(out)
    print(f"{args.font}: {before:,} -> {after:,} bytes ({before/1048576:.2f}MB -> {after/1048576:.2f}MB, "
          f"-{(1-after/before)*100:.1f}%)")

if __name__ == '__main__':
    sys.exit(main())
