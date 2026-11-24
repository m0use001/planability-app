# -*- coding: utf-8 -*-
"""googlesansflex.ttf 子集化（M3 Expressive 字体接入，2026-10-23 phone-c）。

原字体：google/fonts 仓库 `ofl/googlesansflex/GoogleSansFlex[ROND,opsz,slnt,wdth,wght].ttf`
（SIL OFL 1.1，可随 APK 分发；同为 `ofl/` 目录下的 TRADEMARKS.md 只保护字体名，不影响使用）。
完整可变字体 3.91MB / 686 字形，体积主要在 gvar（5 个轴的变体增量）。

子集策略：
- **只保留 UI 用得到的拉丁 + 符号字符集**（与 subset_notosanssc.py 的拉丁部分同源，
  另加 U+2500-257F 制表符「│」与 U+2700-27BF 的 ✓ ——评分胶囊和待办完成标记在用）；
  中文由 Android 逐字回退到 notosanssc / 系统 CJK 链，和现有拉丁字体（Poppins 等）行为一致。
- **只保留 wght / ROND / opsz 三个轴**：wght 喂 Type.kt 的字重偏移（fontWeight 300-800），
  ROND 是 M3 Expressive 的圆角终端轴（display/headline 要拉高它），opsz 让大字号自动收紧字怀；
  slnt（斜体）与 wdth（宽度）应用从不用，丢掉可大幅缩小 gvar。

用法（需 fonttools）：
    python tools/fonts/subset_googlesansflex.py <原字体.ttf> [--out <输出.ttf>]
"""
import argparse
import os
import sys


def charset_ranges():
    yield from range(0x20, 0x7F)          # ASCII
    yield from range(0xA0, 0x100)         # Latin-1 补充（° ± × ÷ 等）
    yield from range(0x2000, 0x2070)      # 通用标点（– — ‘ ’ “ ” …）
    yield from range(0x2190, 0x2200)      # 箭头 ← ↑ → ↓
    yield from range(0x2460, 0x2500)      # ①-⑳ 及带圈数字
    yield from range(0x2500, 0x2580)      # 制表符（评分胶囊的 │）
    yield from range(0x25A0, 0x2600)      # 几何形（◔ ● ○ ■）
    yield from range(0x2600, 0x2700)      # 杂项符号（★ ☆）
    yield from range(0x2700, 0x27C0)      # Dingbats（✓ ✕）
    yield from range(0x3000, 0x3040)      # CJK 标点（、。「」——原字体若无这些码位会自然跳过）
    yield from range(0xFF01, 0xFF65)      # 全角标点与英数字
    yield from range(0xFFE0, 0xFFE7)      # ￠ ￡ ￥ ￦


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('font', help='原 GoogleSansFlex 可变字体路径')
    ap.add_argument('--out', help='输出路径（缺省=原地替换）')
    args = ap.parse_args()

    from fontTools.subset import Subsetter, Options, load_font, save_font

    unicodes = sorted(set(charset_ranges()))
    print(f"charset: {len(unicodes)} codepoints")

    before = os.path.getsize(args.font)
    opts = Options()
    opts.layout_features = ['*']          # 保留 kern 等全部特性
    opts.name_IDs = [1, 2, 3, 4, 6]       # Android 用资源引用，基本命名够了
    opts.notdef_outline = True
    opts.glyph_names = False
    opts.prune_unicode_ranges = True
    opts.drop_tables += ['FFTM']
    # 注意：不要指望 Options.variations —— 4.5x 上实测不生效（5 个轴原样保留）。
    # 轴裁剪在下面用 varLib.instancer 钉死 slnt/wdth 完成，wght/ROND/opsz 三轴保留。

    font = load_font(args.font, opts)
    ss = Subsetter(options=opts)
    ss.populate(unicodes=unicodes)
    ss.subset(font)

    # 把 slnt/wdth 两个轴**钉死**（实例化为 0 / 100）——这是真正移除轴与增量的做法；
    # fontTools subset 的 Options.variations 在 4.5x 上不生效（实测 5 个轴原样保留），别再依赖它。
    from fontTools.varLib.instancer import instantiateVariableFont
    font = instantiateVariableFont(font, {"slnt": 0, "wdth": 100}, inplace=False, updateFontNames=False)

    out = args.out or args.font
    save_font(font, out, opts)
    after = os.path.getsize(out)
    print(f"{args.font}: {before:,} -> {after:,} bytes ({before/1048576:.2f}MB -> {after/1048576:.2f}MB, "
          f"-{(1-after/before)*100:.1f}%)")


if __name__ == '__main__':
    sys.exit(main())
