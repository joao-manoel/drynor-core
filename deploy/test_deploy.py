"""Verifica o fluxo de deploy sem tocar em Docker ou serviços reais."""
import os
from pathlib import Path
import subprocess
import re
import shlex
import tarfile
import tempfile
import unittest


class DeployFlowTest(unittest.TestCase):
    def test_world_archive_includes_managed_data_and_nested_ge_categories(self):
        dockerfile = Path(__file__).with_name("Dockerfile.world").read_text().replace("\\\n", " ")
        command = re.search(r"tar (--exclude=.*?)\n", dockerfile).group(1)
        arguments = shlex.split(command)
        payloads = arguments[arguments.index("-cf") + 2:]
        required = {"areas", "ge", "items", "map", "music", "npcs"}
        self.assertTrue({f"data/{name}" for name in required}.issubset(payloads))
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for payload in payloads:
                (root / payload).mkdir(parents=True)
                (root / payload / "sample.json").write_text("{}")
            (root / "data/ge/instant").mkdir()
            (root / "data/ge/settings.json").write_text('{"enabled":true}')
            (root / "data/ge/instant/new-category.json").write_text('{"items":[]}')
            (root / "data/worldConfig.json").write_text("private configuration")
            (root / "data/npcs/addedSpawns.json").write_text("local spawns")
            arguments[arguments.index("-cf") + 1] = str(root / "payload.tar")
            subprocess.run(["tar", *arguments], cwd=root, check=True, capture_output=True)
            with tarfile.open(root / "payload.tar") as archive:
                names = set(archive.getnames())
            self.assertIn("data/ge/settings.json", names)
            self.assertIn("data/ge/instant/new-category.json", names)
            self.assertNotIn("data/worldConfig.json", names)
            self.assertNotIn("data/npcs/addedSpawns.json", names)

    def run_flow(self, fail_build=False):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        root = Path(temp.name)
        for name in (".deploy", "world/data", "cache-unpacked/gamevals",
                     "repositories/drynor-core/.git", "repositories/drynor-lobby-server/.git"):
            (root / name).mkdir(parents=True, exist_ok=True)
        (root / "cache-unpacked/gamevals/obj.json").write_text("{}")
        (root / "world/data/worldConfig.json").write_text("{}")
        (root / "compose.yml").write_text("services: {}\n")
        bin_dir = root / "bin"
        bin_dir.mkdir()
        scripts = {
            "git": '''#!/usr/bin/env bash
case "$*" in
  *branch*) echo main;;
  *status*) :;;
  *rev-parse*) echo 0123456789012345678901234567890123456789;;
esac
''',
            "docker": '''#!/usr/bin/env bash
printf '%s\n' "$*" >> "$TEST_LOG"
case "$1" in
  inspect) if [[ "$*" == *State.Running* ]]; then echo 1; else echo sha256:previous; fi;;
  exec) if [[ "$*" == *test-mongo* ]]; then echo 1; else printf 'HTTP/1.0 200 OK\n'; fi;;
  run) echo test-container;;
  compose) if [[ "$*" == *'build --builder'* && "$FAIL_BUILD" == 1 ]]; then exit 17; fi;;
esac
''',
            "curl": '#!/usr/bin/env bash\nprintf \'{"ready":true,"worlds":1}\\n\'\n',
            "sleep": '#!/usr/bin/env bash\nexit 0\n',
        }
        for name, content in scripts.items():
            file = bin_dir / name
            file.write_text(content, encoding="utf-8", newline="\n")
            file.chmod(0o755)
        env = os.environ.copy()
        env.update(DRYNOR_ROOT=root.as_posix(), TEST_BIN=bin_dir.as_posix(),
                   TEST_LOG=(root / "calls.txt").as_posix(), FAIL_BUILD=str(int(fail_build)),
                   TEST_SCRIPT=Path(__file__).with_name("deploy.sh").resolve().as_posix())
        command = 'if command -v cygpath >/dev/null; then TEST_BIN=$(cygpath -u "$TEST_BIN"); fi; export PATH="$TEST_BIN:$PATH"; bash "$TEST_SCRIPT" lobby'
        result = subprocess.run([os.environ.get("BASH_BINARY", "bash"), "-c", command],
                                env=env, text=True, capture_output=True, timeout=30)
        return root, result, (root / "calls.txt").read_text()

    def test_build_failure_recovers_old_service(self):
        root, result, calls = self.run_flow(fail_build=True)
        self.assertEqual(result.returncode, 17, result.stdout + result.stderr)
        self.assertIn("stop world lobby", calls)
        self.assertIn("up -d --no-deps lobby", calls)
        self.assertIn("start drynor-rsps-world-1", calls)
        self.assertFalse((root / ".deploy/lobby.yml").exists())
        self.assertFalse((root / ".deploy/last-lobby").exists())
        self.assertNotIn("down", calls)

    def test_success_installs_image_and_saves_rollback(self):
        root, result, calls = self.run_flow()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertTrue((root / ".deploy/last-lobby").exists())
        overlay = (root / ".deploy/lobby.yml").read_text()
        self.assertIn("image: drynor-lobby:", overlay)
        self.assertIn("/opt/drynor/lobby/bin/drynor-lobby-server", overlay)
        self.assertIn("rm -fv drynor-deploy-test-mongo", calls)
        self.assertNotIn("down", calls)


if __name__ == "__main__":
    unittest.main()
