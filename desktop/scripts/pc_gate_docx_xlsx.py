# -*- coding: utf-8 -*-
"""PC 桥的 docx/xlsx 闸门：直接 curl 渲一份，再用 python-docx / openpyxl 验。

和 pptx 一样，先在 PC 侧把两端契约钉住，再上手机。
"""
import json, subprocess, sys, os
sys.stdout.reconfigure(encoding='utf-8')
import docx, openpyxl

tmp = os.environ.get('TEMP', r'C:\Users\test\AppData\Local\Temp')


def post(payload, out):
    f = os.path.join(tmp, 'req_bx.json')
    with open(f, 'w', encoding='utf-8') as fh:
        json.dump(payload, fh, ensure_ascii=False)
    subprocess.run(f'curl.exe -s --max-time 40 -X POST -H "Content-Type: application/json" '
                   f'--data-binary "@{f}" -o "{out}" http://127.0.0.1:4097/render',
                   shell=True, capture_output=True)
    return os.path.exists(out) and os.path.getsize(out) > 0


print('=== health ===')
h = json.loads(subprocess.run('curl.exe -s --max-time 5 http://127.0.0.1:4097/health',
                               shell=True, capture_output=True, text=True,
                               encoding='utf-8', errors='replace').stdout)
print('  ', h)

print('=== docx ===')
out = os.path.join(tmp, 'bridge.docx')
ok = post({"kind": "docx", "payload": {
    "title": "周报",
    "paragraphs": ["# 本周小结", "整体业务保持增长", "## 风险", "成本控制明显改善", ""],
}}, out)
print('  产物:', ok, os.path.getsize(out) if ok else 0)
if ok:
    d = docx.Document(out)
    print('  段落数:', len(d.paragraphs))
    for p in d.paragraphs:
        print(f'    [{p.style.name:10s}] {p.text[:40]}')
    styles = [p.style.name for p in d.paragraphs]
    print('  含标题样式:', any('Heading' in s for s in styles))

print('=== xlsx ===')
out2 = os.path.join(tmp, 'bridge.xlsx')
ok2 = post({"kind": "xlsx", "payload": {
    "sheetName": "渠道明细",
    "rows": [["渠道", "新增", "留存"], ["自然量", 12043, "45%"], ["投放", 3210, "31%"]],
}}, out2)
print('  产物:', ok2, os.path.getsize(out2) if ok2 else 0)
if ok2:
    wb = openpyxl.load_workbook(out2)
    ws = wb.active
    print('  表名:', ws.title, '尺寸:', ws.max_row, 'x', ws.max_column)
    for row in ws.iter_rows(values_only=True):
        print('   ', row)
    print('  首行加粗:', ws.cell(row=1, column=1).font.bold)

print('=== pptx 回归（确认没改坏） ===')
out3 = os.path.join(tmp, 'bridge.pptx')
ok3 = post({"kind": "pptx", "payload": {
    "title": "回归", "author": "Planability",
    "theme": {"name": "默认", "dark": False, "accent": "4F6BED"},
    "slides": [{"layout": "title", "title": "封面", "subtitle": "", "notes": "", "pageNo": 1, "blocks": []}],
}}, out3)
from pptx import Presentation
prs = Presentation(out3) if ok3 else None
print('  产物:', ok3, '页数:', len(list(prs.slides)) if prs else 0)

print('\n结论：', '三种格式全部通过' if (ok and ok2 and ok3) else '有格式没过')
