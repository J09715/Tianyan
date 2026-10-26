"""Build an offline .txplugin with only the Python 3 standard library."""
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parent.parent
MAX_BYTES = 8 * 1024 * 1024 * 1024


def build(root=ROOT):
    root = Path(root)
    manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
    tool_id, version = manifest["id"], manifest["version"]
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{1,63}", tool_id):
        raise ValueError("Invalid tool ID")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+-]{0,127}", version):
        raise ValueError("Invalid package version")
    if manifest.get("schemaVersion") != 2 or manifest["plugin"]["execution"] != "PROCESS":
        raise ValueError("This packager requires a v2 PROCESS manifest")
    payload = root / "payload"
    if not payload.is_dir() or payload.is_symlink():
        raise ValueError("payload must be a real directory")
    files = []
    for path in payload.rglob("*"):
        relative = path.relative_to(payload).as_posix()
        if path.is_symlink() or "\\" in relative or ":" in relative or len(relative) > 512:
            raise ValueError("Unsafe payload path: " + relative)
        if path.is_file():
            files.append((relative, path))
    if len(files) > 20_000 or sum(path.stat().st_size for _, path in files) > MAX_BYTES:
        raise ValueError("Payload exceeds package limits")
    output = root / "dist" / (tool_id + "-" + version + ".txplugin")
    output.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix="txplugin-", suffix=".tmp", dir=output.parent)
    os.close(descriptor)
    try:
        # Hash exactly the bytes written into the ZIP, rather than rereading changed files.
        hashes = {}
        with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            total = 0
            for relative, path in sorted(files):
                digest = hashlib.sha256()
                with path.open("rb") as source, archive.open("payload/" + relative, "w", force_zip64=True) as target:
                    while True:
                        block = source.read(1024 * 1024)
                        if not block:
                            break
                        total += len(block)
                        if total > MAX_BYTES:
                            raise ValueError("Payload exceeds package limits")
                        target.write(block)
                        digest.update(block)
                hashes[relative] = digest.hexdigest()
            manifest["plugin"]["integrity"] = hashes
            data = json.dumps(manifest, ensure_ascii=False, indent=2).encode("utf-8")
            if len(data) > 1024 * 1024 or total + len(data) > MAX_BYTES:
                raise ValueError("Manifest or package exceeds package limits")
            archive.writestr("manifest.json", data)
            if not files:
                archive.writestr("payload/", b"")
        os.replace(temporary, output)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    return output


if __name__ == "__main__":
    print(build())
