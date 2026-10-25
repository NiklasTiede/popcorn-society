import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from datetime import date, timedelta

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/container-scan-targets.py"


class ContainerScanTargetsTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location("scan_targets", SCRIPT)
        cls.targets = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.targets)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.apps = Path(self.temp.name)
        for service in ("backend", "frontend", "agent"):
            shutil.copyfile(ROOT / f"infrastructure/clusters/home/apps/{service}.yaml", self.apps / f"{service}.yaml")

    def test_reads_all_three_exact_gitops_references_without_registry_access(self):
        images = self.targets.production_images(self.apps)
        self.assertEqual([item["service"] for item in images], ["backend", "frontend", "agent"])
        for item in images:
            reference = item["image"].removeprefix("registry:")
            self.assertIn(f"image: {reference}\n", (self.apps / f'{item["service"]}.yaml').read_text())
            self.assertRegex(reference, r"@sha256:[a-f0-9]{64}$")

    def test_follows_new_digest_without_changing_service_categories(self):
        path = self.apps / "agent.yaml"
        before = self.targets.production_images(self.apps)
        old_image = before[2]["image"].removeprefix("registry:")
        new_image = "niklastiede/popcorn-society-agent:v2.0.0@sha256:" + "a" * 64
        path.write_text(path.read_text().replace(old_image, new_image))
        after = self.targets.production_images(self.apps)
        self.assertEqual(after[:2], before[:2])
        self.assertEqual(after[2], {"service": "agent", "image": "registry:" + new_image})

    def test_rejects_missing_duplicate_mutable_and_wrong_service_images(self):
        path = self.apps / "backend.yaml"
        original = path.read_text()
        reference = self.targets.production_images(self.apps)[0]["image"].removeprefix("registry:")
        invalid = [
            original.replace(f"image: {reference}", "imagePullPolicy: Always"),
            original + f"\n          image: {reference}\n",
            original.replace(reference, reference.split("@")[0]),
            original.replace(reference, "niklastiede/popcorn-society-backend:latest"),
            original.replace("popcorn-society-backend:v", "popcorn-society-agent:v"),
            original.replace("niklastiede/", "untrusted/"),
            original.replace(reference, reference[:-1] + "z"),
        ]
        for content in invalid:
            with self.subTest(content=content[-100:]):
                path.write_text(content)
                with self.assertRaises(ValueError):
                    self.targets.production_images(self.apps)

    def test_three_day_cadence_survives_month_year_and_leap_boundaries(self):
        for start in (date(2026, 10, 20), date(2026, 12, 20), date(2028, 2, 20)):
            due = [start + timedelta(days=i) for i in range(50) if self.targets.scan_due(start + timedelta(days=i))]
            self.assertGreater(len(due), 10)
            self.assertTrue(all((right - left).days == 3 for left, right in zip(due, due[1:])))

    def test_schedule_skips_off_days_but_manual_runs_always_scan(self):
        day = date(2026, 10, 25)
        while self.targets.scan_due(day):
            day += timedelta(days=1)
        self.assertEqual(self.targets.scan_matrix(self.apps, scheduled=True, today=day), {"include": []})
        self.assertEqual(len(self.targets.scan_matrix(self.apps, scheduled=False, today=day)["include"]), 3)
        self.assertEqual(len(self.targets.scan_matrix(self.apps, scheduled=True, today=day + timedelta(days=2))["include"]), 3)

    def test_cli_outputs_parseable_github_job_outputs(self):
        result = subprocess.run([sys.executable, str(SCRIPT)], cwd=ROOT, capture_output=True, text=True, check=True)
        outputs = dict(line.split("=", 1) for line in result.stdout.splitlines())
        self.assertEqual(outputs["scan"], "true")
        self.assertEqual(len(json.loads(outputs["matrix"])["include"]), 3)


if __name__ == "__main__":
    unittest.main()
