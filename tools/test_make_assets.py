#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""make_assets.py 的回归测试：清单字节数必须准，不然解压校验会误报。"""

import os
import subprocess
import sys
import tempfile
import unittest
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "make_assets.py")


def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(data)


def read_plan(path):
    plan = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            name, size = line.split("\t")
            plan[name] = int(size)
    return plan


class MakeAssetsTest(unittest.TestCase):
    def test_plan_matches_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            native = os.path.join(tmp, "native")
            app = os.path.join(tmp, "app")
            out = os.path.join(tmp, "assets")

            write(os.path.join(native, "bin", "node"), b"x" * 100)
            write(os.path.join(native, "bin", "proot"), b"p" * 10)
            write(os.path.join(native, "libicu.so"), b"y" * 50)
            write(os.path.join(app, "entry.js"), b"e" * 10)
            write(os.path.join(app, "web", "assets", "a.js"), b"a" * 20)
            write(os.path.join(app, "logs", "old.log"), b"L" * 999)

            r = subprocess.run(
                [sys.executable, SCRIPT, native, app, out],
                capture_output=True,
                text=True,
            )
            self.assertEqual(r.returncode, 0, r.stderr)

            plan = read_plan(os.path.join(out, "install-plan.txt"))
            self.assertEqual(plan["bin/node"], 100)
            self.assertEqual(plan["libicu.so"], 50)
            # 许可证不兼容的组件必须被过滤掉
            self.assertNotIn("bin/proot", plan)
            # payload.zip 记的是解压后的总字节数，logs/ 不进包
            self.assertEqual(plan["payload.zip"], 30)

            with zipfile.ZipFile(os.path.join(out, "payload.zip")) as zf:
                names = sorted(zf.namelist())
            self.assertEqual(names, ["entry.js", "web/assets/a.js"])

    def test_missing_dir_fails_loudly(self):
        with tempfile.TemporaryDirectory() as tmp:
            r = subprocess.run(
                [sys.executable, SCRIPT, os.path.join(tmp, "nope"), tmp, os.path.join(tmp, "out")],
                capture_output=True,
                text=True,
            )
            self.assertEqual(r.returncode, 2)


if __name__ == "__main__":
    unittest.main()
