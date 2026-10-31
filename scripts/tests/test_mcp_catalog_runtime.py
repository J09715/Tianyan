"""Offline shell regression: real Node, fake npm; never contacts a registry."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


def shell_path(path):
    value = path.as_posix()
    if os.name == "nt":
        return "/" + value[0].lower() + value[2:]
    return value


class CatalogRuntimeTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.bash = shutil.which("bash")
        if os.name == "nt":
            self.bash = r"C:\Program Files\Git\bin\bash.exe"
        if not self.bash or not shutil.which("node"):
            self.skipTest("bash and node required")
        self.modules = self.root / "modules"
        self.package = self.modules / "fixture-mcp"
        self.package.mkdir(parents=True)
        self.manifest = {"name": "fixture-mcp", "version": "1.2.3", "bin": {"fixture-mcp": "entry.cjs"}}
        self.write_manifest()
        (self.package / "entry.cjs").write_text("console.log(JSON.stringify(process.argv.slice(2)));", encoding="utf-8")
        self.markers = self.root / "markers"
        self.markers.mkdir()
        (self.markers / "fixture.ok").write_text("SPEC=fixture-mcp@1.2.3\nVERSION=1.2.3\n", encoding="utf-8")
        # Everything the stub npm is allowed to answer with; absent files mean "unreachable".
        self.remote = self.root / "remote"
        self.remote.mkdir()
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.write_npm_stub()
        source = Path(__file__).resolve().parents[2] / "app/src/main/assets/scripts/setup_mcp_catalog.sh"
        self.script = self.root / "catalog.sh"
        self.script.write_text(source.read_text(encoding="utf-8").replace(
            'MARKER_DIR="/opt/tianyan/mcp"', 'MARKER_DIR="' + shell_path(self.markers) + '"'), encoding="utf-8")

    def write_manifest(self):
        (self.package / "package.json").write_text(json.dumps(self.manifest), encoding="utf-8")

    def write_npm_stub(self):
        """Fake npm: `root -g` and offline-materialised `install -g` work; every query is a file lookup."""
        npm = self.bin / "npm"
        npm.write_text(
            "#!/bin/sh\n"
            'if [ "$1" = "root" ] && [ "$2" = "-g" ]; then printf \'%s\\n\' "' + self.modules.as_posix() + '"; exit 0; fi\n'
            'if [ "$1" = "install" ]; then\n'
            '  spec="$3"; name="${spec%@*}"; ver="${spec##*@}"\n'
            '  mkdir -p "' + shell_path(self.modules) + '/$name" || exit 1\n'
            '  printf \'{"name":"%s","version":"%s","bin":{"%s":"entry.cjs"}}\' "$name" "$ver" "$name"'
            ' > "' + shell_path(self.modules) + '/$name/package.json" || exit 1\n'
            '  printf \'console.log(JSON.stringify(process.argv.slice(2)));\''
            ' > "' + shell_path(self.modules) + '/$name/entry.cjs" || exit 1\n'
            "  exit 0\n"
            "fi\n"
            'if [ "$1" = "view" ]; then\n'
            '  field="$3"\n'
            '  if [ "$field" = "version" ]; then\n'
            '    [ -f "' + shell_path(self.remote) + '/version" ] || { echo "NETWORK FORBIDDEN" >&2; exit 99; }\n'
            '    cat "' + shell_path(self.remote) + '/version"; exit 0\n'
            "  fi\n"
            '  if [ "$field" = "dist.integrity" ]; then\n'
            '    key="$(printf \'%s\' "$2" | tr "/@" "__")"\n'
            '    f="' + shell_path(self.remote) + '/integrity_$key"\n'
            '    [ -f "$f" ] || { echo "NETWORK FORBIDDEN" >&2; exit 99; }\n'
            '    cat "$f"; exit 0\n'
            "  fi\n"
            "fi\n"
            'echo "NETWORK FORBIDDEN" >&2; exit 99\n',
            encoding="utf-8")
        npm.chmod(0o755)

    def set_remote(self, version=None, integrity=None, integrity_spec=None):
        """Publish (or withdraw) what the fake registry can answer."""
        (self.remote / "version").unlink(missing_ok=True)
        for stale in self.remote.glob("integrity_*"):
            stale.unlink()
        if version is not None:
            (self.remote / "version").write_text(version, encoding="utf-8")
        if integrity is not None:
            spec = integrity_spec or "fixture-mcp@" + (version or "1.2.3")
            key = spec.replace("/", "_").replace("@", "_")
            (self.remote / ("integrity_" + key)).write_text(integrity, encoding="utf-8")

    def marker_text(self):
        return (self.markers / "fixture.ok").read_text(encoding="utf-8")

    def _run(self, *argv):
        return subprocess.run([self.bash, "-c", 'export PATH="$1:$PATH"; shift; exec /bin/sh "$@"',
            "test", shell_path(self.bin), shell_path(self.script), *argv],
            capture_output=True, text=True, encoding="utf-8", timeout=10)

    def run_catalog(self, command, *args):
        """check / upgrade / probe / run share the `<id> <pkgName> [serverArgs...]` shape."""
        return self._run(command, "fixture", "fixture-mcp", *args)

    def run_install(self, spec, integrity):
        """`install` takes `<id> <pkgSpec> <expectedIntegrity>` — pkgName is not a separate slot."""
        return self._run("install", "fixture", spec, integrity)

    def test_valid_installation_probes_and_runs_without_network(self):
        self.assertEqual(0, self.run_catalog("probe").returncode)
        result = self.run_catalog("run", "argument with spaces", "--mcp")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["argument with spaces", "--mcp"], json.loads(result.stdout))

    def test_deleted_package_does_not_pass_with_stale_marker(self):
        (self.package / "package.json").unlink()
        self.assertNotEqual(0, self.run_catalog("probe").returncode)
        self.assertNotEqual(0, self.run_catalog("run").returncode)

    def test_wrong_version_and_missing_entry_are_rejected(self):
        self.manifest["version"] = "1.2.4"
        self.write_manifest()
        self.assertNotEqual(0, self.run_catalog("probe").returncode)
        self.manifest["version"] = "1.2.3"
        self.write_manifest()
        (self.package / "entry.cjs").unlink()
        self.assertNotEqual(0, self.run_catalog("probe").returncode)

    def test_package_substitution_and_path_escape_are_rejected(self):
        (self.markers / "fixture.ok").write_text("SPEC=other-package@1.2.3\n", encoding="utf-8")
        self.assertNotEqual(0, self.run_catalog("probe").returncode)
        (self.markers / "fixture.ok").write_text("SPEC=fixture-mcp@1.2.3\n", encoding="utf-8")
        self.manifest["bin"] = "../outside.cjs"
        self.write_manifest()
        self.assertNotEqual(0, self.run_catalog("probe").returncode)

    # ---- install: pinned version guard + checksum gate + marker as the source of truth ----

    def test_install_refuses_unpinned_specs(self):
        self.set_remote(integrity="sha512-AAA")
        for spec in ("fixture-mcp@latest", "fixture-mcp", "fixture-mcp@*", "fixture-mcp@next"):
            result = self.run_install(spec, "sha512-AAA")
            self.assertNotEqual(0, result.returncode, spec)
            self.assertIn("固定版本号", result.stderr)

    def test_integrity_mismatch_blocks_install_and_leaves_marker_untouched(self):
        self.set_remote(integrity="sha512-TAMPERED")
        result = self.run_install("fixture-mcp@1.2.3", "sha512-AAA")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("校验和不匹配", result.stderr)
        self.assertIn("SPEC=fixture-mcp@1.2.3", self.marker_text())

    def test_install_records_spec_and_integrity_in_the_marker(self):
        self.set_remote(integrity="sha512-AAA")
        result = self.run_install("fixture-mcp@1.2.3", "sha512-AAA")
        self.assertEqual(0, result.returncode, result.stderr)
        marker = self.marker_text()
        self.assertIn("SPEC=fixture-mcp@1.2.3", marker)
        self.assertIn("VERSION=1.2.3", marker)
        self.assertIn("INTEGRITY=sha512-AAA", marker)

    # ---- check: installed x upstream ----

    def test_check_reports_installed_and_latest(self):
        self.set_remote(version="1.3.0")
        result = self.run_catalog("check")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("INSTALLED=1.2.3", result.stdout)
        self.assertIn("LATEST=1.3.0", result.stdout)

    def test_check_still_reports_installed_when_registry_is_unreachable(self):
        result = self.run_catalog("check")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("INSTALLED=1.2.3", result.stdout)
        self.assertIn("ERROR=", result.stdout)

    # ---- upgrade: rewrite the marker, then launch from it ----

    def test_upgrade_rewrites_marker_and_launch_picks_up_the_new_version(self):
        self.set_remote(version="1.3.0", integrity="sha512-BBB")
        result = self.run_catalog("upgrade")
        self.assertEqual(0, result.returncode, result.stderr)
        marker = self.marker_text()
        self.assertIn("SPEC=fixture-mcp@1.3.0", marker)
        self.assertIn("VERSION=1.3.0", marker)
        self.assertIn("INTEGRITY=sha512-BBB", marker)
        # The whole point: run reads the version from the marker, so the upgrade takes effect at once.
        run = self.run_catalog("run", "--mcp")
        self.assertEqual(0, run.returncode, run.stderr)
        self.assertEqual(["--mcp"], json.loads(run.stdout))

    def test_upgrade_is_a_noop_when_already_latest(self):
        self.set_remote(version="1.2.3")
        result = self.run_catalog("upgrade")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("已是最新版本", result.stdout)
        self.assertIn("VERSION=1.2.3", self.marker_text())

    def test_upgrade_fails_loudly_when_registry_is_unreachable(self):
        result = self.run_catalog("upgrade")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("无法从镜像读取", result.stderr)
        self.assertIn("VERSION=1.2.3", self.marker_text())


if __name__ == "__main__":
    unittest.main()
