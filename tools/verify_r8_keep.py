#!/usr/bin/env python3
"""校验 release（minify 开启）产物的 R8 行为是否仍然正确。

背景：Rhino 用 Class.forName("org.mozilla.javascript.Interpreter") 按字符串名取
解释器类（Context.interpreterClass），再用 Kit.newInstanceOrNull 对该 Class 直接调
getDeclaredConstructor()——Kit 的 catch 列表不含 NPE。一旦 R8 把这个类改名或剥离，
Class.forName 返回 null，脚本引擎在编译阶段就抛裸 NPE，codemode 在正式版上完全不可用。
（历史事故：Tianyan v1.3.0 release 包，见 harness/consumer-rules.pro 的 keep 说明。）

同时，"混淆确实开着"必须一起校验：如果某次构建恰好把 minify 关掉，keep 类自然还在，
校验会假绿。因此默认还要断言一个未 keep 的 Rhino 类（Context）在被混淆后已不存在。

用法：
    python3 tools/verify_r8_keep.py <apk> [--require CLASS]... [--absent CLASS]...

退出码：0 通过；1 校验失败；2 用法/产物错误。
"""

import argparse
import sys
import zipfile

DEFAULT_REQUIRE = ["Lorg/mozilla/javascript/Interpreter;"]
# 哨兵：Context 未被 keep，release 混淆后应完全消失；它出现即说明 minify 没生效。
DEFAULT_ABSENT = ["Lorg/mozilla/javascript/Context;"]


def dex_payload(apk_path):
    """把所有 classes*.dex 的字节拼起来，返回 (payload, dex 文件名列表)。"""
    try:
        with zipfile.ZipFile(apk_path) as apk:
            names = sorted(n for n in apk.namelist() if n.endswith(".dex"))
            if not names:
                raise ValueError(f"{apk_path} 内没有 .dex（不像 APK？）")
            return b"".join(apk.read(n) for n in names), names
    except (OSError, zipfile.BadZipFile) as exc:
        raise ValueError(f"无法读取 {apk_path}: {exc}") from exc


def main(argv=None):
    parser = argparse.ArgumentParser(description="校验 release 产物的 R8 keep/混淆状态")
    parser.add_argument("apk", help="minify 后的 APK 路径")
    parser.add_argument(
        "--require",
        action="append",
        default=None,
        metavar="CLASS",
        help=f"必须保留类名（dex 描述符形式），可重复；默认 {DEFAULT_REQUIRE[0]}",
    )
    parser.add_argument(
        "--absent",
        action="append",
        default=None,
        metavar="CLASS",
        help="必须已消失类名（用于证明混淆生效），可重复；默认 Context",
    )
    args = parser.parse_args(argv)

    require = args.require if args.require else DEFAULT_REQUIRE
    absent = args.absent if args.absent else DEFAULT_ABSENT

    try:
        payload, dex_names = dex_payload(args.apk)
    except ValueError as exc:
        print(f"[FAIL] {exc}", file=sys.stderr)
        return 2

    print(f"APK        : {args.apk}")
    print(f"dex 文件   : {len(dex_names)} 个")
    print(f"字节总量   : {len(payload)}")

    failures = []
    print("\n必须保留（R8 keep 生效）：")
    for name in require:
        count = payload.count(name.encode())
        status = "OK  " if count else "FAIL"
        print(f"  [{status}] {name}  出现 {count} 次")
        if not count:
            failures.append(f"{name} 缺失：keep 规则没生效，codemode 会在编译阶段抛 NPE")

    print("\n必须消失（minify/混淆确实开着）：")
    for name in absent:
        count = payload.count(name.encode())
        status = "OK  " if not count else "FAIL"
        print(f"  [{status}] {name}  出现 {count} 次")
        if count:
            failures.append(
                f"{name} 仍存在：本次构建可能没开 minify，上面的 keep 校验属于假绿"
            )

    if failures:
        print("\n[FAIL] 校验未通过：")
        for item in failures:
            print(f"  - {item}")
        return 1

    print("\n[OK] release 产物符合预期：keep 类按原名保留，混淆仍在生效。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
