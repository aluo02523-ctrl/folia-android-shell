#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""按 ELF 的 DT_NEEDED 递归收集二进制真正依赖的 .so。

为什么不用 ldd：Termux 默认没装它。退回「手写清单 + 通配符」会把
libicu* 的 .so / .so.78 / .so.78.3 三份全拷进去（内容相同），
native 从 95MB 胖到 172MB。这里直接读 ELF，按 soname 各拷一份。

用法：collect-libs.py <可执行文件> <库目录> <目标目录>
"""

import os
import shutil
import struct
import sys

DT_NEEDED = 1
DT_STRTAB = 5
DT_STRSZ = 10
PT_LOAD = 1
PT_DYNAMIC = 2


def parse_needed(data):
    """返回这个 ELF 的 DT_NEEDED 列表（soname）"""
    if data[:4] != b"\x7fELF":
        raise ValueError("不是 ELF")
    if data[4] != 2:
        raise ValueError("只支持 64 位 ELF")
    endian = "<" if data[5] == 1 else ">"
    e_phoff = struct.unpack_from(endian + "Q", data, 0x20)[0]
    e_phentsize = struct.unpack_from(endian + "H", data, 0x36)[0]
    e_phnum = struct.unpack_from(endian + "H", data, 0x38)[0]

    loads = []
    dyn = None
    for i in range(e_phnum):
        base = e_phoff + i * e_phentsize
        p_type = struct.unpack_from(endian + "I", data, base)[0]
        p_offset = struct.unpack_from(endian + "Q", data, base + 8)[0]
        p_vaddr = struct.unpack_from(endian + "Q", data, base + 16)[0]
        p_filesz = struct.unpack_from(endian + "Q", data, base + 32)[0]
        if p_type == PT_LOAD:
            loads.append((p_vaddr, p_vaddr + p_filesz, p_offset))
        elif p_type == PT_DYNAMIC:
            dyn = (p_offset, p_filesz)
    if dyn is None:
        return []

    def vaddr_to_off(addr):
        for start, end, off in loads:
            if start <= addr < end:
                return off + (addr - start)
        raise ValueError("0x%x 不在任何 PT_LOAD 里" % addr)

    needed, strtab, strsz = [], None, 0
    off, size = dyn
    for pos in range(off, off + size, 16):
        tag, val = struct.unpack_from(endian + "QQ", data, pos)
        if tag == 0:
            break
        if tag == DT_NEEDED:
            needed.append(val)
        elif tag == DT_STRTAB:
            strtab = val
        elif tag == DT_STRSZ:
            strsz = val
    if not needed or strtab is None:
        return []

    base = vaddr_to_off(strtab)
    out = []
    for n in needed:
        end = data.index(b"\0", base + n, base + n + strsz)
        out.append(data[base + n:end].decode("utf-8", "replace"))
    return out


def main():
    if len(sys.argv) != 4:
        print(__doc__)
        return 2
    binary, libdir, dest = sys.argv[1], sys.argv[2], sys.argv[3]
    if not os.path.isfile(binary):
        print("找不到 %s" % binary)
        return 2
    os.makedirs(dest, exist_ok=True)

    queue = [binary]
    seen = set()
    copied = []
    while queue:
        cur = queue.pop(0)
        try:
            with open(cur, "rb") as f:
                names = parse_needed(f.read())
        except Exception as e:
            print("  跳过 %s：%s" % (cur, e))
            continue
        for name in names:
            if name in seen:
                continue
            seen.add(name)
            src = os.path.join(libdir, name)
            if not os.path.exists(src):
                continue  # 系统库（libc/libm/libdl…），不用随包带
            dst = os.path.join(dest, name)
            # realpath 跟掉软链，名字按 soname 留，只存一份
            shutil.copyfile(os.path.realpath(src), dst)
            os.chmod(dst, 0o755)
            copied.append(name)
            queue.append(dst)

    total = sum(os.path.getsize(os.path.join(dest, n)) for n in copied)
    print("  收集 %d 个库，共 %.1f MB" % (len(copied), total / 1048576.0))
    for name in copied:
        print("    %-28s %5.1f MB" % (name, os.path.getsize(os.path.join(dest, name)) / 1048576.0))
    return 0


if __name__ == "__main__":
    sys.exit(main())
