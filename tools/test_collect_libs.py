#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""collect-libs.py 的回归测试：依赖集合要和 ldd 对得上，且每个库只有一份。"""

import os
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "collect-libs.py")


@unittest.skipUnless(sys.platform.startswith("linux") and shutil.which("ldd"), "要 Linux + ldd")
class CollectLibsTest(unittest.TestCase):
    def test_matches_ldd(self):
        binary = shutil.which("ls") or "/bin/ls"
        ldd = subprocess.run(["ldd", binary], capture_output=True, text=True).stdout
        paths = [ln.split()[2] for ln in ldd.splitlines() if "=>" in ln and ln.split()[2].startswith("/")]
        self.assertTrue(paths, "ldd 没输出")
        libdir = os.path.dirname(paths[0])

        with tempfile.TemporaryDirectory() as out:
            r = subprocess.run(
                [sys.executable, SCRIPT, binary, libdir, out],
                capture_output=True,
                text=True,
            )
            self.assertEqual(r.returncode, 0, r.stderr)
            got = sorted(os.listdir(out))
            self.assertTrue(got, "一个库都没拷")
            # ldd 的可解析目标都要收进来（vDSO 例外，它不在 DT_NEEDED 里）
            want = {os.path.basename(p) for p in paths}
            self.assertTrue(want <= set(got), "缺：" + repr(want - set(got)))
            # 同一个 soname 只会有一份
            self.assertEqual(len(got), len(set(got)))

    def test_bad_input_fails(self):
        with tempfile.TemporaryDirectory() as out:
            r = subprocess.run(
                [sys.executable, SCRIPT, "/nonexistent/elf", out, out],
                capture_output=True,
                text=True,
            )
            self.assertNotEqual(r.returncode, 0)


if __name__ == "__main__":
    unittest.main()
