"""Read-only size ratchet. --base-ref must name a trusted target commit in CI."""
import argparse
import json
from pathlib import Path, PurePosixPath
import subprocess
import sys


def load_json(text):
    return json.loads(text.lstrip("\ufeff"))


def git_text(root, ref, path, required=True):
    result = subprocess.run(
        ["git", "show", f"{ref}:{path}"], cwd=root, capture_output=True,
        encoding="utf-8", errors="strict", check=False,
    )
    if result.returncode:
        if required:
            raise ValueError(f"Cannot read trusted base {ref}:{path}")
        return None
    return result.stdout


def line_count(text):
    # Match BufferedReader.readLine(), including CRLF and a final unterminated line.
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    return len(text.split("\n")) - int(text.endswith("\n")) if text else 0


def entries(document):
    files = document.get("files")
    if not isinstance(files, dict):
        raise ValueError("baseline.files must be an object")
    for path, size in files.items():
        parts = PurePosixPath(path)
        if (parts.is_absolute() or ".." in parts.parts or "\\" in path
                or ":" in path or not path.endswith(".kt")):
            raise ValueError(f"Invalid baseline path: {path}")
        if type(size) is not int or size <= 0:
            raise ValueError(f"Invalid baseline size: {path}")
    return files


def check(root, base_ref=None):
    policy = load_json((root / "architecture-policy.json").read_text(encoding="utf-8"))
    limit = policy["global"]["maxFileLines"]
    if type(limit) is not int or limit <= 0:
        raise ValueError("maxFileLines must be a positive integer")
    baseline_path = policy["global"]["baselineFile"]
    if baseline_path != ".architecture-baseline.json":
        raise ValueError("Ratchet expects the repository .architecture-baseline.json")
    baseline = entries(load_json((root / baseline_path).read_text(encoding="utf-8")))
    errors, hints = [], []
    base = None
    if base_ref:
        # Resolve to a commit first: neither options nor mutable refs reach subsequent git calls.
        result = subprocess.run(["git", "rev-parse", "--verify", "--end-of-options", f"{base_ref}^{{commit}}"],
                                cwd=root, capture_output=True, text=True, check=False)
        if result.returncode:
            raise ValueError("Trusted base is not an available Git commit")
        base_ref = result.stdout.strip()
        base_policy = load_json(git_text(root, base_ref, "architecture-policy.json"))
        if limit > base_policy["global"]["maxFileLines"]:
            errors.append("global.maxFileLines increased relative to trusted base")
        base_path = base_policy["global"]["baselineFile"]
        if base_path != baseline_path:
            raise ValueError("Trusted base uses a different baseline file")
        base = entries(load_json(git_text(root, base_ref, base_path)))
        for path, ceiling in baseline.items():
            if path not in base:
                errors.append(f"{path}: new baseline exemption is forbidden")
            elif ceiling > base[path]:
                errors.append(f"{path}: baseline increased {base[path]} -> {ceiling}")

    actual = {}
    for module in policy["modules"]:
        source = (root / module["path"] / "src/main").resolve()
        if not source.is_relative_to(root.resolve()):
            raise ValueError("Module source directory escapes repository")
        for file in source.rglob("*.kt"):
            if not file.resolve().is_relative_to(root.resolve()):
                raise ValueError("Source file escapes repository")
            path = file.relative_to(root).as_posix()
            size = line_count(file.read_text(encoding="utf-8"))
            actual[path] = size
            ceiling = baseline.get(path, limit)
            if base is not None and size > limit:
                # A stale baseline must not allow debt already removed in the base to return.
                original = git_text(root, base_ref, path, required=False)
                trusted = max(limit, line_count(original)) if original is not None else limit
                ceiling = min(ceiling, trusted, base.get(path, limit))
            if size > ceiling:
                errors.append(f"{path}: {size} lines exceeds ratchet ceiling {ceiling}")
    for path, ceiling in baseline.items():
        size = actual.get(path, 0)
        if size < ceiling:
            action = "remove" if size <= limit else f"lower to {size}"
            hints.append(f"{path}: {action} (baseline {ceiling})")
    return errors, hints


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--base-ref", help="Trusted target commit; CI must pass the PR base SHA")
    args = parser.parse_args()
    try:
        errors, hints = check(args.root.resolve(), args.base_ref)
    except (ValueError, KeyError, OSError, json.JSONDecodeError) as error:
        print(f"architectureBaselineCheck: {error}", file=sys.stderr)
        return 1
    for hint in hints:
        print(f"Baseline can tighten: {hint}")
    for error in errors:
        print(f"Ratchet violation: {error}", file=sys.stderr)
    print(f"architectureBaselineCheck: {len(errors)} violations, {len(hints)} tightening hints; no files written")
    return int(bool(errors))


if __name__ == "__main__":
    sys.exit(main())
