# -*- coding: utf-8 -*-
"""
设备数据守护（Planability）

解决：多个 agent 共用一台真机时，`uninstall` / `pm clear` / 装错变体（release/benchmark）
会**静默清空 App 数据**（课表、会话、记忆、项目）。

用法：
  python device_guard.py status                  # 当前装的是哪版、可不可调试、库里多少数据
  python device_guard.py backup                  # 立刻做一次完整快照（db+wal+shm → 单文件）
  python device_guard.py install <apk> [--allow-non-debug]
                                                 # 先备份，再 install -r；非 debug 包默认拒绝
  python device_guard.py restore [latest|<目录>]  # 恢复快照（会 force-stop，写回后重启）
  python device_guard.py watch --interval 600    # 常驻：每 N 秒快照一次（配合保留份数）

快照位置：%LOCALAPPDATA%\\Planability\\device-backups\\<时间戳>\\planability.db
          （**不放在仓库里**，避免被 git clean / 切分支波及）

设计要点：
1. 备份必须能扛住"整库被删"：快照是**单文件**（WAL 已 checkpoint 合并），带 integrity_check 与行数校验。
2. `install` 永远先备份：任何 agent 只要走这个入口，就不会出现"装完才发现数据没了"。
3. 非 debug 包（benchmark/release）默认**拒绝安装**：这次事故就是它导致的
   （isDebuggable=false → run-as 失效，连事后取证都做不到）。
4. 保留策略：默认留最近 48 份；`watch` 每 10 分钟一份 = 覆盖 8 小时。
"""
import argparse
import json
import os
import re
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import time
from datetime import datetime

sys.stdout.reconfigure(encoding='utf-8')

PKG_DEFAULT = 'io.github.m0use001.planability'
BACKUP_ROOT = os.path.join(os.environ.get('LOCALAPPDATA', tempfile.gettempdir()),
                           'Planability', 'device-backups')
TABLES = ('schedules', 'todos', 'projects', 'chat_sessions', 'chat_messages', 'memories',
          'plan_steps')


def adb(device, *args, **kw):
    cmd = ['adb'] + (['-s', device] if device else []) + list(args)
    return subprocess.run(cmd, capture_output=True, **kw)


def txt(device, *args):
    return adb(device, *args).stdout.decode('utf-8', 'ignore')


def pkg_info(device, pkg):
    info = txt(device, 'shell', 'dumpsys', 'package', pkg)
    debug = 'DEBUGGABLE' in info
    m = re.search(r'lastUpdateTime=([^\s]+)', info)
    fi = re.search(r'firstInstallTime=([^\s]+)', info)
    return {
        'debuggable': debug,
        'lastUpdate': m.group(1) if m else '',
        'firstInstall': fi.group(1) if fi else '',
    }


def read_db(device, pkg):
    """把 db+wal+shm 拉到临时目录并合并成单文件；返回 (path, error)。"""
    tmp = tempfile.mkdtemp(prefix='planability-guard-')
    local = os.path.join(tmp, 'planability.db')
    for suf in ('', '-wal', '-shm'):
        p = adb(device, 'exec-out', 'run-as', pkg, 'cat', 'databases/planability.db' + suf)
        data = p.stdout or b''
        if suf == '' and (not data or data.startswith(b'run-as:') or b'not debuggable' in data):
            return None, (data[:120].decode('utf-8', 'ignore') or '拿不到数据库（App 不可调试或未安装）')
        open(local + suf, 'wb').write(data)
    try:
        con = sqlite3.connect(local)
        con.execute('PRAGMA wal_checkpoint(TRUNCATE)')
        ok = con.execute('PRAGMA integrity_check').fetchone()[0]
        if ok != 'ok':
            return None, 'integrity_check: ' + str(ok)
        con.close()
    except Exception as e:
        return None, '打不开快照：%r' % e
    return local, None


def db_summary(path):
    con = sqlite3.connect(path)
    out = {'version': con.execute('PRAGMA user_version').fetchone()[0]}
    for t in TABLES:
        try:
            out[t] = con.execute('SELECT COUNT(*) FROM ' + t).fetchone()[0]
        except Exception:
            pass
    con.close()
    return out


def prune(keep):
    if not os.path.isdir(BACKUP_ROOT):
        return
    dirs = sorted([d for d in os.listdir(BACKUP_ROOT)
                   if os.path.isdir(os.path.join(BACKUP_ROOT, d))])
    for d in dirs[:-keep] if len(dirs) > keep else []:
        shutil.rmtree(os.path.join(BACKUP_ROOT, d), ignore_errors=True)


def stop_app(device, pkg):
    """拉库前必须停应用：活库里 `cat` 三个文件会拿到**撕裂的 WAL**。
    2026-11-05 实测：安装前备份直接报 database disk image is malformed，
    同一份库静置（应用已停）后再拉完全正常。停应用后等 0.5s 让 WAL 落盘。"""
    adb(device, 'shell', 'am', 'force-stop', pkg)
    time.sleep(0.5)


def do_backup(device, pkg, keep=48, quiet=False):
    os.makedirs(BACKUP_ROOT, exist_ok=True)
    stop_app(device, pkg)
    path, err = read_db(device, pkg)
    if err:
        print('✗ 备份失败：' + err)
        return None
    summary = db_summary(path)
    stamp = datetime.now().strftime('%Y%m%d-%H%M%S')
    outdir = os.path.join(BACKUP_ROOT, stamp)
    os.makedirs(outdir, exist_ok=True)
    shutil.copy(path, os.path.join(outdir, 'planability.db'))
    info = pkg_info(device, pkg)
    meta = {'at': stamp, 'device': device, 'package': pkg, **info, 'summary': summary}
    with open(os.path.join(outdir, 'meta.json'), 'w', encoding='utf-8') as f:
        json.dump(meta, f, ensure_ascii=False, indent=2)
    prune(keep)
    if not quiet:
        print('✓ 快照 %s  version=%s  %s' % (
            outdir, summary.get('version'),
            ' '.join('%s=%s' % (k, v) for k, v in summary.items() if k != 'version')))
    return outdir


def latest_backup():
    if not os.path.isdir(BACKUP_ROOT):
        return None
    dirs = sorted(d for d in os.listdir(BACKUP_ROOT)
                  if os.path.isfile(os.path.join(BACKUP_ROOT, d, 'planability.db')))
    return os.path.join(BACKUP_ROOT, dirs[-1]) if dirs else None


def cmd_status(args):
    info = pkg_info(args.device, args.package)
    print('包：%s' % args.package)
    print('  可调试 = %s   lastUpdate = %s   firstInstall = %s' % (
        info['debuggable'], info['lastUpdate'], info['firstInstall']))
    if not info['debuggable']:
        print('  ⚠ 当前是不可调试包（release/benchmark）：run-as 不可用，**任何 agent 都无法备份/取证**')
    # 读库前停应用（活库 cat 会拿到撕裂 WAL）；status 本来只是诊断，停一下无损
    stop_app(args.device, args.package)
    path, err = read_db(args.device, args.package)
    if err:
        print('  数据库：' + err)
    else:
        s = db_summary(path)
        print('  数据库：user_version=%s  %s' % (
            s.get('version'), ' '.join('%s=%s' % (k, v) for k, v in s.items() if k != 'version')))
    lb = latest_backup()
    print('  最近快照：%s' % (lb or '（无）'))


def cmd_backup(args):
    do_backup(args.device, args.package, args.keep)


def cmd_watch(args):
    print('守护中：每 %d 秒快照一次，保留 %d 份，目录 %s' % (args.interval, args.keep, BACKUP_ROOT))
    print('Ctrl+C 停止')
    while True:
        do_backup(args.device, args.package, args.keep)
        time.sleep(max(60, args.interval))


def cmd_restore(args):
    target = args.snapshot
    if not target or target == 'latest':
        target = latest_backup()
    # 支持三种来源：快照目录（guard 自己存的）、单个 .db 文件（App 自动导出到 Download 的）、latest
    if target and os.path.isfile(target):
        src = target
    elif target:
        src = os.path.join(target, 'planability.db')
    else:
        src = None
    if not src or not os.path.isfile(src):
        print('✗ 没有可用的快照'); return
    print('恢复 %s → %s' % (src, args.package))
    do_backup(args.device, args.package, args.keep, quiet=True)   # 恢复前也留一份当前状态
    adb(args.device, 'shell', 'am', 'force-stop', args.package)
    time.sleep(2)
    adb(args.device, 'push', src, '/data/local/tmp/restore.db')
    adb(args.device, 'shell', 'run-as', args.package, 'cp', '/data/local/tmp/restore.db',
        'databases/planability.db')
    for suf in ('-wal', '-shm', '-journal'):
        adb(args.device, 'shell', 'run-as', args.package, 'rm', '-f', 'databases/planability.db' + suf)
    adb(args.device, 'shell', 'rm', '-f', '/data/local/tmp/restore.db')
    adb(args.device, 'shell', 'am', 'start', '-n', args.package + '/.MainActivity')
    print('✓ 已恢复并重启；App 会按 Room 迁移链自动升级版本')


def apk_is_debuggable(apk):
    """用 aapt2 读 application-debuggable；找不到工具就返回 None（未知）。"""
    sdk = None
    lp = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
                      'local.properties')
    if os.path.isfile(lp):
        for line in open(lp, encoding='utf-8', errors='ignore'):
            if line.startswith('sdk.dir='):
                # Gradle properties 会转义：D\:\\code\\ANDROID_SDK → D:\code\ANDROID_SDK
                sdk = line.split('=', 1)[1].strip().strip('"')
                sdk = sdk.replace('\\\\', '\\').replace('\\:', ':')
    if not sdk:
        return None
    bt = os.path.join(sdk, 'build-tools')
    if not os.path.isdir(bt):
        return None
    for v in sorted(os.listdir(bt), reverse=True):
        exe = os.path.join(bt, v, 'aapt2.exe')
        if os.path.isfile(exe):
            p = subprocess.run([exe, 'dump', 'badging', apk], capture_output=True)
            out = (p.stdout or b'').decode('utf-8', 'ignore')
            if not out:
                return None
            return 'application-debuggable' in out
    return None


def cmd_install(args):
    debug = apk_is_debuggable(args.apk)
    if debug is False and not args.allow_non_debug:
        print('✗ 拒绝安装：这是非 debug 包（release/benchmark）。')
        print('  它会替换日常包、让 run-as 失效，并且任何 agent 都无法再备份/取证。')
        print('  确实要装（例如测性能）请加 --allow-non-debug，并在装完后立刻装回 debug 包。')
        return 1
    if debug is None:
        print('⚠ 无法判断 APK 是否可调试（没找到 aapt2），继续安装但会先备份')
    print('安装前先备份当前数据…')
    if not do_backup(args.device, args.package, args.keep):
        if not args.force:
            print('✗ 备份失败，已中止（--force 可跳过）'); return 1
    r = adb(args.device, 'install', '-r', args.apk)
    out = (r.stdout or b'').decode('utf-8', 'ignore') + (r.stderr or b'').decode('utf-8', 'ignore')
    print(out.strip()[-300:])
    if 'Success' not in out:
        return 1
    info = pkg_info(args.device, args.package)
    print('装后：可调试=%s lastUpdate=%s' % (info['debuggable'], info['lastUpdate']))
    if not info['debuggable']:
        print('⚠ 现在装的是非调试包，记得测完装回 debug 包')
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--device', default=os.environ.get('ANDROID_SERIAL', '◆'))
    ap.add_argument('--package', default=PKG_DEFAULT)
    ap.add_argument('--keep', type=int, default=48)
    sub = ap.add_subparsers(dest='cmd', required=True)

    sp = sub.add_parser('status'); sp.set_defaults(fn=cmd_status)
    sp = sub.add_parser('backup'); sp.set_defaults(fn=cmd_backup)
    sp = sub.add_parser('watch')
    sp.add_argument('--interval', type=int, default=600)
    sp.set_defaults(fn=cmd_watch)
    sp = sub.add_parser('restore')
    sp.add_argument('snapshot', nargs='?', default='latest')
    sp.set_defaults(fn=cmd_restore)
    sp = sub.add_parser('install')
    sp.add_argument('apk')
    sp.add_argument('--allow-non-debug', action='store_true')
    sp.add_argument('--force', action='store_true')
    sp.set_defaults(fn=cmd_install)

    args = ap.parse_args()
    sys.exit(args.fn(args) or 0)


if __name__ == '__main__':
    main()
