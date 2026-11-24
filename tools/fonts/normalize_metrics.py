# -*- coding: utf-8 -*-
"""把自带中文字体的垂直度量统一到同一套（hhea asc/desc + OS/2 winAscent/winDescent）。

为什么需要：选「中文圆体」时，一行里同时有 Google Sans Flex 的拉丁和回退字体的汉字，
Android（Minikin）会把该行**用到过的每个字体的** ascent/descent 一起算进行度量。
975 圆体原始 hhea 是 928/-256（em=1000），比思源系（1160/-288）矮一截，混排时整行文字
会被顶高约 20px（顶栏标题/日期肉眼可见），与另外两个中文字体不一致。

统一值取思源系（也是文渊圆体/资源圆体原生的值），保证三个候选项彼此一致：
    hhea.ascent = 1160, hhea.descent = -288, hhea.lineGap = 0
    OS/2.usWinAscent = 1160, OS/2.usWinDescent = 288
    OS/2.sTypoAscender = 880, OS/2.sTypoDescender = -120, OS/2.sTypoLineGap = 0
（最后三项：有的字号会按 sTypo 算行高——Google Sans Flex 打了 USE_TYPO_METRICS，
中文字体没打；两边 sTypo 保持一致可避免混排行高各算各的。）

用法：
    python tools/fonts/normalize_metrics.py app/src/main/res/font/yuan975_400.ttf [...]
只改垂直度量字段，不动字形与 cmap；可重复执行（幂等）。
"""
import argparse
import sys

ASC, DESC = 1160, -288
TYPO_ASC, TYPO_DESC = 880, -120


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("fonts", nargs="+", help="要归一化的字体文件（原地修改）")
    args = ap.parse_args()

    from fontTools.ttLib import TTFont

    for path in args.fonts:
        f = TTFont(path)
        upm = f["head"].unitsPerEm
        if upm != 1000:
            print(f"{path}: unitsPerEm={upm}，本脚本只处理 1000/em，跳过", file=sys.stderr)
            continue
        hhea, os2 = f["hhea"], f["OS/2"]
        before = (hhea.ascent, hhea.descent, os2.usWinAscent, os2.usWinDescent,
                  os2.sTypoAscender, os2.sTypoDescender, os2.sTypoLineGap)
        hhea.ascent, hhea.descent, hhea.lineGap = ASC, DESC, 0
        os2.usWinAscent, os2.usWinDescent = ASC, -DESC
        os2.sTypoAscender, os2.sTypoDescender, os2.sTypoLineGap = TYPO_ASC, TYPO_DESC, 0
        f.save(path)
        print(f"{path}: {before} -> {(ASC, DESC, ASC, -DESC, TYPO_ASC, TYPO_DESC, 0)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
