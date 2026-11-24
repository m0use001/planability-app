#!/usr/bin/env python3
"""启动器图标「配色 × 明暗」矩阵生成器。

配色是有限枚举（拼色四套 + 其他颜色八个预设 = 12 档），每档要浅/深两份，
所以启动器图标是 24 份预编译资源 + 24 个 activity-alias，运行期只启用其中一个
（见 PlanabilityApp.kt 的 LauncherNightIcon）。

本脚本从 Theme.kt 解析调色板（单一真源），生成：
  res/drawable/ic_launcher_fg_pal{i}_{day,night}.xml     前景矢量
  res/mipmap-anydpi-v26/ic_launcher_pal{i}_{day,night}.xml  自适应图标
  并重写 AndroidManifest.xml 里 LAUNCHER_ICON_ALIASES_BEGIN/END 之间的 alias 块。

改配色后重跑：python tools/launcher-icons/gen_launcher_icons.py

颜色规则：
  白天  主色 = 浅色方案 primary；浅调 = 同色相压饱和提亮
  夜晚  主色 = 深色方案 primary；浅调 = 同色相提饱和压暗
  s≈0 的方案（黑白）不做提饱和，否则会凭空长出红/蓝色相。
"""
import re
import sys
from pathlib import Path

# 本脚本是本机开发工具，根目录写死；换机器改这一行。
ROOT = Path(__file__).resolve().parents[2]
THEME = ROOT / "app/src/main/java/io/github/m0use001/planability/ui/theme/Theme.kt"
DRAWABLE = ROOT / "app/src/main/res/drawable"
MIPMAP = ROOT / "app/src/main/res/mipmap-anydpi-v26"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"

# 列宽 9 / 列距 4 / 外轮廓 30..78；True = 该段用主色。
#
# 切分位置（缝隙 4，四列缝隙起点 46/44/60/58，单位 y）：
#   列1 浅 30..46 / 深 50..78   列2 深 30..44 / 浅 48..78
#   列3 浅 30..60 / 深 64..78   列4 深 30..58 / 浅 62..78
# 三条约束同时成立（2026-10-25 用户定）：
#   · 左两列、右两列各自**视觉向上**（y 减小）且斜率相同（-2）
#   · 整体「先升后降再升」：46→44（升）、44→60（降）、60→58（升）
#   · 左右关于图标中心点对称——绕 (54,54) 转 180°，列1↔列4、列2↔列3
#     （缝隙 46..50 ↔ 58..62，44..48 ↔ 60..64）
COLS = [(34.5, 34.5, 41.5, 54.5, 73.5, False),   # 列1 浅 30..46 / 深 50..78
        (47.5, 34.5, 39.5, 52.5, 73.5, True),    # 列2 深 30..44 / 浅 48..78
        (60.5, 34.5, 55.5, 68.5, 73.5, False),   # 列3 浅 30..60 / 深 64..78
        (73.5, 34.5, 53.5, 66.5, 73.5, True)]    # 列4 深 30..58 / 浅 62..78


def hsv(h, s, v):
    """Android Color.HSVToColor 同口径（h 度，s/v 0..1）→ 0xAARRGGBB。"""
    h, s, v = h % 360.0, min(max(s, 0.0), 1.0), min(max(v, 0.0), 1.0)
    c = v * s
    m = v - c
    x = c * (1 - abs((h / 60.0) % 2 - 1))
    r, g, b = [(c, x, 0), (x, c, 0), (0, c, x), (0, x, c), (x, 0, c), (c, 0, x)][min(int(h // 60), 5)]
    return (0xFF << 24) | (int(round((r + m) * 255)) << 16) \
        | (int(round((g + m) * 255)) << 8) | int(round((b + m) * 255))


def hsv_of(argb):
    r, g, b = (((argb >> 16) & 0xFF) / 255.0, ((argb >> 8) & 0xFF) / 255.0, (argb & 0xFF) / 255.0)
    mx, mn = max(r, g, b), min(r, g, b)
    d = mx - mn
    if d == 0:
        return 0.0, 0.0, mx
    if mx == r:
        h = (60 * ((g - b) / d)) % 360
    elif mx == g:
        h = 60 * ((b - r) / d) + 120
    else:
        h = 60 * ((r - g) / d) + 240
    return h, d / mx, mx


def weak_day(strong):
    h, s, _ = hsv_of(strong)
    return hsv(h, 0.0 if s < 0.05 else min(s * 0.55, 0.42), 0.80 + 0.15 * min(s, 1.0))


def weak_night(strong):
    h, s, _ = hsv_of(strong)
    return hsv(h, 0.0 if s < 0.05 else min(max(s * 1.25, 0.18), 0.55), 0.44)


def hexc(argb):
    return "#%08X" % argb


def palettes():
    src = THEME.read_text(encoding="utf-8")
    block = re.search(r"FIXED_STYLES: List<FixedStyle> = listOf\((.*?)\n\)\n", src, re.S)
    seeds = re.search(r"PRESET_LIGHTS: List<PresetLight> = listOf\((.*?)\n\)", src, re.S)
    if not block or not seeds:
        sys.exit("解析失败：Theme.kt 里找不到 FIXED_STYLES / PRESET_LIGHTS")
    names = ("经典绿", "黑白", "中性", "亮蓝")
    rows = []
    for m in re.finditer(r"FixedStyle\(\s*light = listOf\((.*?)\),\s*dark = listOf\((.*?)\)\s*\)",
                         block.group(1), re.S):
        light = [int(x, 16) for x in re.findall(r"0x([0-9A-Fa-f]{8})", m.group(1))]
        dark = [int(x, 16) for x in re.findall(r"0x([0-9A-Fa-f]{8})", m.group(2))]
        if not light or not dark:
            sys.exit("解析失败：FIXED_STYLES 某套为空")
        rows.append((names[len(rows)], light[0], dark[0]))
    # 预设表每项 7 个角色，第一个 hex 就是该套的 primary（=种子）。
    # 白天直接用 primary 原值（与 App 内浅色色板逐值一致，别再按 HSV 重推，
    # #6750A4 会被推成 #463770）；夜晚按 M3 音阶取 tone-80 近似。
    for i, m in enumerate(re.finditer(r"PresetLight\((.*?)\)", seeds.group(1), re.S)):
        seed = int(re.findall(r"0x([0-9A-Fa-f]{8})", m.group(1))[0], 16)
        h, s, _ = hsv_of(seed)
        dark = hsv(h, min(max(s * 0.9, 0.18), 0.55), 0.78)
        rows.append(("自选 %02d" % (i + 1), seed, dark))
    return rows


def fg_xml(rows, idx, night):
    strong = rows[idx][2] if night else rows[idx][1]
    weak = weak_night(strong) if night else weak_day(strong)
    body = []
    for i, (xc, a1, a2, b1, b2, strong_top) in enumerate(COLS, 1):
        top, bottom = (strong, weak) if strong_top else (weak, strong)
        body.append('    <!-- 列 %d -->\n'
                    '    <path\n        android:pathData="M%s,%s L%s,%s"\n'
                    '        android:strokeColor="%s"\n        android:strokeWidth="9"\n'
                    '        android:strokeLineCap="round" />\n'
                    '    <path\n        android:pathData="M%s,%s L%s,%s"\n'
                    '        android:strokeColor="%s"\n        android:strokeWidth="9"\n'
                    '        android:strokeLineCap="round" />' % (
                        i, xc, a1, xc, a2, hexc(top), xc, b1, xc, b2, hexc(bottom)))
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<!--\n  启动器图标前景：配色「%s」%s版（palette %d）。\n\n'
            '  **配色矩阵生成物，别手改**：改 Theme.kt 的 FIXED_STYLES / COLOR_SEEDS 后重跑\n'
            '  tools/launcher-icons/gen_launcher_icons.py。\n\n'
            '  主色 %s / 浅调 %s；几何与所有其它 palette 完全一致\n'
            '  （列宽 9、列距 4、缝 4、外轮廓 y30..78、round cap 竖线），只有颜色不同。\n-->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="108dp"\n    android:height="108dp"\n'
            '    android:viewportWidth="108"\n    android:viewportHeight="108">\n\n'
            '%s\n</vector>\n') % (rows[idx][0], "夜间" if night else "日间", idx,
                                  hexc(strong), hexc(weak), "\n".join(body))


def mipmap_xml(idx, night):
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<!-- 配色 palette %d / %s（配色矩阵生成物，别手改） -->\n'
            '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <background android:drawable="@drawable/%s" />\n'
            '    <foreground android:drawable="@drawable/ic_launcher_fg_pal%d_%s" />\n'
            '    <monochrome android:drawable="@drawable/ic_launcher_mono" />\n'
            '</adaptive-icon>\n') % (idx, "夜间" if night else "日间",
                                     "ic_launcher_bg_night" if night else "ic_launcher_bg_day",
                                     idx, "night" if night else "day")


def alias_block(n):
    out = ["        <!-- LAUNCHER_ICON_ALIASES_BEGIN",
           "             配色矩阵：%d 档 × 明暗。运行期只启用一个，由 LauncherNightIcon" % n,
           "             按「当前配色 + 系统明暗」切换；默认 pal0_day，保证刚装好也有图标。 -->"]
    for idx in range(n):
        for night in (False, True):
            icon = "ic_launcher_pal%d_%s" % (idx, "night" if night else "day")
            out.append('        <activity-alias\n'
                       '            android:name=".LauncherPal%d%s"\n'
                       '            android:enabled="%s"\n'
                       '            android:exported="true"\n'
                       '            android:icon="@mipmap/%s"\n'
                       '            android:label="@string/app_name"\n'
                       '            android:roundIcon="@mipmap/%s"\n'
                       '            android:targetActivity=".MainActivity">\n'
                       '            <intent-filter>\n'
                       '                <action android:name="android.intent.action.MAIN" />\n\n'
                       '                <category android:name="android.intent.category.LAUNCHER" />\n'
                       '            </intent-filter>\n\n'
                       '            <meta-data\n'
                       '                android:name="android.app.shortcuts"\n'
                       '                android:resource="@xml/shortcuts" />\n'
                       '        </activity-alias>' % (
                           idx, "Night" if night else "Day",
                           "true" if (idx == 0 and not night) else "false", icon, icon))
    out.append("        <!-- LAUNCHER_ICON_ALIASES_END -->")
    return "\n".join(out)


def main():
    rows = palettes()
    for idx, (name, light, dark) in enumerate(rows):
        (DRAWABLE / ("ic_launcher_fg_pal%d_day.xml" % idx)).write_text(fg_xml(rows, idx, False), encoding="utf-8")
        (DRAWABLE / ("ic_launcher_fg_pal%d_night.xml" % idx)).write_text(fg_xml(rows, idx, True), encoding="utf-8")
        (MIPMAP / ("ic_launcher_pal%d_day.xml" % idx)).write_text(mipmap_xml(idx, False), encoding="utf-8")
        (MIPMAP / ("ic_launcher_pal%d_night.xml" % idx)).write_text(mipmap_xml(idx, True), encoding="utf-8")
        print("pal%d %s: 主色 日 %s 夜 %s | 浅调 日 %s 夜 %s" % (
            idx, name, hexc(light), hexc(dark), hexc(weak_day(light)), hexc(weak_night(dark))))

    src = MANIFEST.read_text(encoding="utf-8")
    pat = re.compile(r" *<!-- LAUNCHER_ICON_ALIASES_BEGIN.*?<!-- LAUNCHER_ICON_ALIASES_END -->", re.S)
    if not pat.search(src):
        sys.exit("清单里找不到 LAUNCHER_ICON_ALIASES_BEGIN/END 标记")
    MANIFEST.write_text(pat.sub(lambda _m: alias_block(len(rows)), src, count=1), encoding="utf-8")
    print("已写入 %d 档 × 2 = %d 份资源 + alias" % (len(rows), len(rows) * 2))


if __name__ == "__main__":
    main()
