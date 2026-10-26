#!/usr/bin/env python3
"""Vendor the Google Android CLI and the official Android Skills into the
`assets/plugins/android-skills` local plugin, then pack a .txplugin archive.

Usage:
    python tools/vendor-android-skills-plugin.py             # fetch only what is missing
    python tools/vendor-android-skills-plugin.py --force     # re-download everything
    python tools/vendor-android-skills-plugin.py --no-pack   # prepare payload only

Why a script: the payload is ~90 MiB of third-party binaries plus 33 upstream skill
directories. Manifests, scripts, checksums and download instructions stay versioned;
the binaries stay out of git (see .gitignore) and are reproduced from pinned URLs.

Pinned upstream inputs
----------------------
* android/skills          : git commit ANDROID_SKILLS_COMMIT (skill set = .claude-plugin/plugin.json "skills")
* Android CLI launcher    : dl.google.com/android/cli/<version>/linux_arm64/android      (4,160,720 B)
* Android CLI payload     : dl.google.com/android/cli/<version>/linux_arm64/android-cli  (89,790,864 B)

The "latest" alias is deliberately NOT used: it is a moving pointer, and the CLI
payload is what makes the plugin work fully offline.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import sys
import tarfile
import tempfile
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PLUGIN_DIR = ROOT / "assets" / "plugins" / "android-skills"
PAYLOAD_DIR = PLUGIN_DIR / "payload"
DIST_DIR = ROOT / "dist" / "plugins"

ANDROID_SKILLS_COMMIT = "95ea931a1ad8f9b2be132ae0cfb2aad964d71118"
ANDROID_SKILLS_TARBALL = (
    f"https://codeload.github.com/android/skills/tar.gz/{ANDROID_SKILLS_COMMIT}"
)

ANDROID_CLI_VERSION = "1.0.16625370"
ANDROID_CLI_BASE = f"https://dl.google.com/android/cli/{ANDROID_CLI_VERSION}/linux_arm64"
ANDROID_CLI_LAUNCHER = f"{ANDROID_CLI_BASE}/android"
ANDROID_CLI_PAYLOAD = f"{ANDROID_CLI_BASE}/android-cli"

ANDROID_CLI_LAUNCHER_SHA256 = "c56de0230dbbea470f13f1bf8f4d983584248c401eb5365bbbde6555949954cf"
ANDROID_CLI_PAYLOAD_SHA256 = "72d8ab31a3d7bab92793c37c7a72b5c854c09027c7975120e2c1413df983bcb4"

CHECKSUMS_NAME = "SHA256SUMS"
USER_AGENT = "tianyan-android-skills-vendor"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download(url: str, target: Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    print(f"  downloading {url}")
    with urllib.request.urlopen(request, timeout=300) as response, tempfile.NamedTemporaryFile(
        dir=str(target.parent), delete=False
    ) as tmp:
        shutil.copyfileobj(response, tmp, length=1024 * 1024)
        tmp_path = Path(tmp.name)
    tmp_path.replace(target)


def fetch_pinned(url: str, target: Path, expected_sha256: str, force: bool) -> None:
    if target.is_file() and not force and sha256_file(target) == expected_sha256:
        print(f"  ok (cached) {target.relative_to(ROOT)}")
        return
    download(url, target)
    actual = sha256_file(target)
    if actual != expected_sha256:
        target.unlink(missing_ok=True)
        raise SystemExit(
            f"SHA-256 mismatch for {url}\n  expected {expected_sha256}\n  actual   {actual}"
        )
    print(f"  ok {target.relative_to(ROOT)} sha256={actual}")


def vendor_skills(force: bool) -> tuple[str, int]:
    skills_root = PAYLOAD_DIR / "skills"
    licenses_dir = PAYLOAD_DIR / "licenses"
    staging = PAYLOAD_DIR / "skills.staging"

    if skills_root.is_dir() and not force:
        count = len(list(skills_root.glob("*/SKILL.md")))
        if count > 0:
            print(f"  ok (cached) payload/skills ({count} skills)")
            return read_upstream_version(), count

    with tempfile.TemporaryDirectory() as tmp:
        archive = Path(tmp) / "android-skills.tar.gz"
        download(ANDROID_SKILLS_TARBALL, archive)
        extract_root = Path(tmp) / "src"
        extract_root.mkdir()
        with tarfile.open(archive, "r:gz") as tar:
            # Python 3.12+ 默认警告未指定过滤策略；3.11 及更早不支持 filter 参数。
            try:
                tar.extractall(extract_root, filter="data")
            except TypeError:
                tar.extractall(extract_root)
        roots = [entry for entry in extract_root.iterdir() if entry.is_dir()]
        if len(roots) != 1:
            raise SystemExit(f"unexpected upstream layout: {roots}")
        source = roots[0]

        manifest_path = source / ".claude-plugin" / "plugin.json"
        upstream_plugin = json.loads(manifest_path.read_text(encoding="utf-8"))
        declared = upstream_plugin["skills"]

        if staging.exists():
            shutil.rmtree(staging)
        staging.mkdir(parents=True)

        for relative in declared:
            skill_dir = source / relative.lstrip("./")
            skill_file = skill_dir / "SKILL.md"
            if not skill_file.is_file():
                raise SystemExit(f"declared skill missing SKILL.md: {relative}")
            destination = staging / skill_dir.name
            if destination.exists():
                raise SystemExit(f"duplicate skill directory name: {skill_dir.name}")
            shutil.copytree(skill_dir, destination)

        if skills_root.exists():
            shutil.rmtree(skills_root)
        staging.replace(skills_root)

        license_source = source / "LICENSE.txt"
        if not license_source.is_file():
            raise SystemExit("upstream LICENSE.txt is missing")
        licenses_dir.mkdir(parents=True, exist_ok=True)
        shutil.copy2(license_source, licenses_dir / "android-skills-LICENSE.txt")

        count = len(list(skills_root.glob("*/SKILL.md")))
        print(f"  ok payload/skills ({count} skills, upstream plugin v{upstream_plugin.get('version')})")
        return str(upstream_plugin.get("version", "unknown")), count


def read_upstream_version() -> str:
    """Recover the upstream plugin version recorded by UPSTREAM.md (cache mode)."""
    upstream = PLUGIN_DIR / "UPSTREAM.md"
    if not upstream.is_file():
        return "unknown"
    match = re.search(r"插件版本\s*`([^`]+)`", upstream.read_text(encoding="utf-8"))
    return match.group(1).strip() if match else "unknown"


def write_checksums() -> Path:
    checksums = PAYLOAD_DIR / "checksums" / CHECKSUMS_NAME
    checksums.parent.mkdir(parents=True, exist_ok=True)
    lines = []
    for path in sorted(PAYLOAD_DIR.rglob("*")):
        if not path.is_file():
            continue
        relative = path.relative_to(PAYLOAD_DIR).as_posix()
        if relative == f"checksums/{CHECKSUMS_NAME}" or relative.startswith("skills.staging/"):
            continue
        lines.append(f"{sha256_file(path)}  {relative}")
    checksums.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    print(f"  ok payload/checksums/{CHECKSUMS_NAME} ({len(lines)} entries)")
    return checksums


def write_upstream_md(upstream_version: str, skill_count: int) -> None:
    content = f"""# 上游来源与固定版本（android-skills 插件）

本目录内的三方内容与固定输入，全部由 `tools/vendor-android-skills-plugin.py` 生成。

| 内容 | 来源 | 固定版本 | 许可证 |
| --- | --- | --- | --- |
| 官方 Android Skills（{skill_count} 个技能目录） | https://github.com/android/skills | 提交 `{ANDROID_SKILLS_COMMIT}`（上游 `android-skills` 插件版本 `{upstream_version}`） | Apache-2.0，原文见 `payload/licenses/android-skills-LICENSE.txt` |
| Android CLI 引导器 | `{ANDROID_CLI_LAUNCHER}` | 版本 `{ANDROID_CLI_VERSION}`，SHA-256 `{ANDROID_CLI_LAUNCHER_SHA256}` | Google 发布物（随 Android SDK 条款） |
| Android CLI 真身（`android-cli`） | `{ANDROID_CLI_PAYLOAD}` | 版本 `{ANDROID_CLI_VERSION}`，SHA-256 `{ANDROID_CLI_PAYLOAD_SHA256}` | 同上 |

## 为什么必须两件一起内置

实测（WSL Ubuntu + `unshare -rn` 真断网）：

1. `linux_arm64/android`（约 3.97 MiB）只是引导器；缺少 `$ANDROID_USER_HOME/bin/android-cli`
   时会去 `dl.google.com` 下载约 85.63 MiB 的真身。
2. 只要把 `android-cli` 预置到 `$ANDROID_USER_HOME/bin/android-cli`，**断网下
   `android --version` / `android info` 均正常**，引导器不再联网。
3. 首次运行会就地把内嵌安装解包到 `$ANDROID_USER_HOME/cli/bundles/<sha>/`
   （`main.jar` 约 61 MiB + 自带 JRE + `libos_trust.so`，合计约 70 MiB），**该解包不联网**。
4. ToS 只打印一次提示并写 `$ANDROID_USER_HOME/cli/tos_displayed`，不阻塞非交互调用；
   `android skills list` 离线时只输出告警并正常退出。

因此插件把引导器与 `android-cli` 都放进 `payload/bin/`，由安装脚本在装配期完成
解包与技能落位，做到「导入即离线可用」。

## 刷新方式

```powershell
# 只更新技能（技能目录可读可 diff，随仓库入库）
python tools/vendor-android-skills-plugin.py --force

# 只重打包（payload 已就绪时）
python tools/vendor-android-skills-plugin.py --no-pack   # 准备 payload
python tools/vendor-android-skills-plugin.py             # 打包 dist/plugins/*.txplugin
```

升级上游技能：改 `ANDROID_SKILLS_COMMIT`（用 `git ls-remote https://github.com/android/skills refs/heads/main` 取新值）。
升级 CLI：改 `ANDROID_CLI_VERSION`，并同步 `ANDROID_CLI_*_SHA256`（脚本在不匹配时会拒绝写入并打印实际值）。
每次刷新后必须同步 `manifest.json` 的 `version`，否则同版本包会被导入器拒绝。
"""
    (PLUGIN_DIR / "UPSTREAM.md").write_text(content, encoding="utf-8", newline="\n")
    print("  ok UPSTREAM.md")


def pack(version: str) -> Path:
    manifest_path = PLUGIN_DIR / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("version") != version:
        raise SystemExit(
            f"manifest.json version ({manifest.get('version')}) does not match requested {version}"
        )
    DIST_DIR.mkdir(parents=True, exist_ok=True)
    output = DIST_DIR / f"android-skills-{version}-arm64.txplugin"

    entries = [manifest_path] + sorted(p for p in PAYLOAD_DIR.rglob("*") if p.is_file())
    with zipfile.ZipFile(output, "w") as archive:
        for path in entries:
            relative = (
                "manifest.json"
                if path == manifest_path
                else "payload/" + path.relative_to(PAYLOAD_DIR).as_posix()
            )
            info = zipfile.ZipInfo(relative, date_time=(2025, 1, 1, 0, 0, 0))
            executable = relative.endswith(".sh") or "/bin/" in relative
            info.external_attr = (0o755 if executable else 0o644) << 16
            # 已压缩的上游载荷不再二次压缩
            info.compress_type = (
                zipfile.ZIP_STORED if relative.startswith("payload/bin/") else zipfile.ZIP_DEFLATED
            )
            archive.writestr(info, path.read_bytes())

    print(f"\n[+] {output} ({output.stat().st_size / (1024 * 1024):.2f} MiB)")
    return output


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--force", action="store_true", help="re-download pinned inputs")
    parser.add_argument("--no-pack", action="store_true", help="skip .txplugin packaging")
    args = parser.parse_args()

    if not PLUGIN_DIR.is_dir():
        raise SystemExit(f"plugin directory not found: {PLUGIN_DIR}")

    print("[*] Vendoring Android CLI (linux_arm64) ...")
    fetch_pinned(
        ANDROID_CLI_LAUNCHER, PAYLOAD_DIR / "bin" / "android-launcher",
        ANDROID_CLI_LAUNCHER_SHA256, args.force,
    )
    fetch_pinned(
        ANDROID_CLI_PAYLOAD, PAYLOAD_DIR / "bin" / "android-cli",
        ANDROID_CLI_PAYLOAD_SHA256, args.force,
    )

    print("[*] Vendoring android/skills ...")
    upstream_version, skill_count = vendor_skills(args.force)

    print("[*] Writing checksums and provenance ...")
    write_checksums()
    write_upstream_md(upstream_version, skill_count)

    manifest = json.loads((PLUGIN_DIR / "manifest.json").read_text(encoding="utf-8"))
    print(
        f"[*] payload ready: {skill_count} skills, manifest v{manifest['version']}, "
        f"id={manifest['id']}"
    )
    if args.no_pack:
        return 0

    print("[*] Packing .txplugin ...")
    pack(manifest["version"])
    return 0


if __name__ == "__main__":
    sys.exit(main())
