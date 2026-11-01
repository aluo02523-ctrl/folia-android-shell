#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
APK 对齐 + 塞入 classes.dex（替代 zipalign）

为什么要自己写：
  Google build-tools 里的 zipalign 是 x86_64 的 ELF，Termux（arm64）跑不了。
  但"对齐"这件事本身很简单：ZIP 里每个条目的数据起始偏移，只要补到 4 字节
  对齐（.so 要 4096 对齐）就行，用 Python 重写 ZIP 结构完全可以做到。

做三件事：
  1. 把 classes.dex 加进 APK（不压缩或压缩都行，这里 DEFLATE 压缩）
  2. resources.arsc 保持「不压缩」且 4 字节对齐（Android 11+ 硬性要求）
  3. 其余所有条目的数据偏移补 4 字节对齐

用法：
  python3 align.py 输入.apk classes.dex 输出.apk
"""
import sys
import zlib
import struct

ALIGN_4 = 4
ALIGN_PAGE = 4096


def read_entries(path):
    """把源 zip 的条目读出来（保持原顺序）"""
    import zipfile

    out = []
    with zipfile.ZipFile(path, "r") as z:
        for info in z.infolist():
            data = z.read(info.filename)
            out.append({
                "name": info.filename,
                "data": data,
                "compress": info.compress_type,
                "date_time": info.date_time,
                "external_attr": info.external_attr,
            })
    return out


def build_zip(path, entries):
    """自己写一遍 ZIP：这样能精确控制每个条目的数据偏移"""
    central = []
    offset = 0
    chunks = []

    for e in entries:
        name = e["name"].encode("utf-8")
        raw = e["data"]

        # 规则 1：resources.arsc 必须 store（不压缩）
        # 规则 2：.so 库必须 store
        if name.endswith(b"resources.arsc") or name.endswith(b".so") or e["compress"] == 0:
            method = 0
            payload = raw
        else:
            method = 8
            # ZIP 里的 deflate 是「裸 deflate」，不带 zlib 头和 adler32 校验
            co = zlib.compressobj(9, zlib.DEFLATED, -15)
            payload = co.compress(raw) + co.flush()

        crc = zlib.crc32(raw) & 0xFFFFFFFF
        need_align = ALIGN_PAGE if name.endswith(b".so") else ALIGN_4

        # 本地文件头：30 字节 + 文件名 + 可选 extra
        # 我们先假设 0 长度 extra，算出数据偏移，再补 extra 让它对齐
        header_len = 30 + len(name)
        data_offset = offset + header_len
        pad = (need_align - (data_offset % need_align)) % need_align

        extra = b""
        if pad:
            # extra field 必须 >= 4 字节且符合 (id, size, data) 结构
            pad = pad if pad >= 4 else pad + need_align
            if pad - 4 >= 4:
                extra = struct.pack("<HH", 0xD935, pad - 4) + b"\x00" * (pad - 4)
            else:
                extra = struct.pack("<HH", 0xD935, pad - 4) + b"\x00" * (pad - 4)

        dt = e["date_time"]
        dos_time = (dt[3] << 11) | (dt[4] << 5) | (dt[5] // 2)
        dos_date = ((dt[0] - 1980) << 9) | (dt[1] << 5) | dt[2]

        local = struct.pack(
            "<IHHHHHIIIHH",
            0x04034B50, 20, 0, method, dos_time, dos_date,
            crc, len(payload), len(raw), len(name), len(extra),
        ) + name + extra

        central.append(struct.pack(
            "<IHHHHHHIIIHHHHHII",
            0x02014B50, 20, 20, 0, method, dos_time, dos_date,
            crc, len(payload), len(raw), len(name),
            len(extra), 0, 0, 0,
            e["external_attr"] or 0, offset,
        ) + name + extra)

        chunks.append(local)
        chunks.append(payload)
        offset += len(local) + len(payload)

    cd_start = offset
    for c in central:
        chunks.append(c)
        offset += len(c)
    cd_size = offset - cd_start

    eocd = struct.pack(
        "<IHHHHIIH",
        0x06054B50, 0, 0, len(central), len(central), cd_size, cd_start, 0,
    )
    chunks.append(eocd)

    with open(path, "wb") as f:
        f.write(b"".join(chunks))


def main():
    if len(sys.argv) != 4:
        print("用法: align.py 输入.apk classes.dex 输出.apk")
        return 2
    src, dex, dst = sys.argv[1], sys.argv[2], sys.argv[3]

    entries = read_entries(src)

    # 去重：如果 APK 里已经有 classes.dex，先删掉
    entries = [e for e in entries if e["name"] != "classes.dex"]

    with open(dex, "rb") as f:
        dex_data = f.read()

    # classes.dex 放在最前面，和标准 APK 的布局一致
    entries.insert(0, {
        "name": "classes.dex",
        "data": dex_data,
        "compress": 8,
        "date_time": (2026, 1, 1, 0, 0, 0),
        "external_attr": 0,
    })

    build_zip(dst, entries)
    print("   写出 %s（%d 个条目，%.1f KB）" % (dst, len(entries), len(open(dst, "rb").read()) / 1024.0))
    return 0


if __name__ == "__main__":
    sys.exit(main())
