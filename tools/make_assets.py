#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把「运行时 + 应用」装进 assets/。

产出三样东西：
  assets/native/<相对路径>    ← proot、node、各种 .so（原样，安装时拷到 runtime/）
  assets/payload.zip          ← 网页 + API + 脚本（解压到 app/）
  assets/install-plan.txt     ← 清单：每行「路径<TAB>字节数」，App 用它算进度

用法：
  python3 tools/make_assets.py <native目录> <app目录> <输出assets目录>

install-plan.txt 里的字节数含义要特别说明：
  native 文件 = 磁盘上的真实大小
  payload.zip = 解压后的总字节数（不是 zip 的大小）
因为 App 是一边解压一边按「已经吐出来多少字节」算进度的。
"""

import os
import sys
import zipfile


def walk_files(root, skip_prefixes=()):
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames.sort()
        dirnames[:] = [d for d in dirnames if not any(
            (os.path.relpath(os.path.join(dirpath, d), root).replace(os.sep, "/") + "/").startswith(p)
            for p in skip_prefixes)]
        for name in sorted(filenames):
            full = os.path.join(dirpath, name)
            rel = os.path.relpath(full, root).replace(os.sep, "/")
            if any(rel.startswith(p) for p in skip_prefixes):
                continue
            yield rel, full


def main():
    if len(sys.argv) != 4:
        print(__doc__)
        return 2
    native_dir, app_dir, out_assets = sys.argv[1], sys.argv[2], sys.argv[3]

    if not os.path.isdir(native_dir):
        print("[make_assets] 没有 native 目录: %s" % native_dir)
        return 2
    if not os.path.isdir(app_dir):
        print("[make_assets] 没有 app 目录: %s" % app_dir)
        return 2

    native_out = os.path.join(out_assets, "native")
    os.makedirs(native_out, exist_ok=True)

    plan = []          # [(名字, 字节数)]
    native_total = 0

    for rel, full in walk_files(native_dir):
        dst = os.path.join(native_out, rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        size = os.path.getsize(full)
        # 直接复制（保持内容不变；可执行位由 App 自己 chmod）
        with open(full, "rb") as src, open(dst, "wb") as out:
            while True:
                chunk = src.read(1024 * 1024)
                if not chunk:
                    break
                out.write(chunk)
        plan.append((rel, size))
        native_total += size

    payload = os.path.join(out_assets, "payload.zip")
    payload_uncompressed = 0
    count = 0
    with zipfile.ZipFile(payload, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as zf:
        for rel, full in walk_files(app_dir, skip_prefixes=("logs/",)):
            zf.write(full, rel)
            payload_uncompressed += os.path.getsize(full)
            count += 1

    plan.append(("payload.zip", payload_uncompressed))

    with open(os.path.join(out_assets, "install-plan.txt"), "w", encoding="utf-8") as f:
        f.write("# name\tsize  (payload.zip 的 size 是解压后的总字节数)\n")
        for name, size in plan:
            f.write("%s\t%d\n" % (name, size))

    print("[make_assets] native 文件 %d 个，共 %.1f MB" % (len(plan) - 1, native_total / 1048576.0))
    print("[make_assets] payload 文件 %d 个，解压后 %.1f MB，zip %.1f MB"
          % (count, payload_uncompressed / 1048576.0, os.path.getsize(payload) / 1048576.0))
    print("[make_assets] 清单：%s" % os.path.join(out_assets, "install-plan.txt"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
