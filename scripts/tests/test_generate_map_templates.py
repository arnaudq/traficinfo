"""Offline checks: python3 -m unittest discover -s scripts/tests."""

from copy import deepcopy
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "map_generator", ROOT / "scripts/generate-map-templates.py")
GENERATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GENERATOR)


class MapGenerationTest(unittest.TestCase):
    def setUp(self):
        self.config = GENERATOR.read_json(ROOT / "mapconfig.json")
        self.sources = GENERATOR.read_json(ROOT / "dsconfig.json")

    def test_maps_use_source_ids_and_keep_umap_placeholders(self):
        base = GENERATOR.read_json(ROOT / "map-templates/templates/map.json")
        layer = GENERATOR.read_json(ROOT / "map-templates/templates/layer.json")
        GENERATOR.validate(self.config, self.sources)
        for map_id, ids in (("MMM", ["MMM", "DPT34"]), ("NIM", ["NIM", "DPT30"])):
            entry = self.config["maps"][map_id]
            result = json.loads(GENERATOR.generate(entry, self.config, self.sources, base, layer))
            self.assertEqual(result["geometry"]["coordinates"], entry["center"])
            self.assertEqual(result["properties"]["center"], entry["center"])
            self.assertIn("{data_source}_Logo.png", result["properties"]["popupContentTemplate"])
            self.assertEqual(result.get("uri"), entry.get("uri"))
            self.assertEqual(len(result["layers"]), len(ids))
            for rank, source_id in enumerate(ids):
                generated = result["layers"][rank]
                self.assertEqual(generated["rank"], rank)
                self.assertEqual(generated["features"], [])
                self.assertTrue(generated["properties"]["remoteData"]["url"].endswith(
                    f"/{source_id}_TraficInfo.geojson"))
                source = self.sources[source_id]
                self.assertIn(source["datasets"][source["activeDatasetId"]]["datasetInfoUrl"],
                              generated["properties"]["description"])

    def test_invalid_configurations_are_rejected(self):
        mutations = [
            lambda e: e.update(dataSources=["UNKNOWN"]),
            lambda e: e.update(dataSources=["NIM", "NIM"]),
            lambda e: e.update(id="OTHER"),
            lambda e: e.update(center=[43, 181]),
            lambda e: e.update(layerProperties={"MMM": {}}),
        ]
        for mutate in mutations:
            config = deepcopy(self.config)
            mutate(config["maps"]["NIM"])
            with self.subTest(config=config), self.assertRaises(ValueError):
                GENERATOR.validate(config, self.sources)

    def test_cli_is_idempotent_and_validates_before_writing(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "scripts").mkdir()
            shutil.copy(ROOT / "scripts/generate-map-templates.py", root / "scripts")
            shutil.copytree(ROOT / "map-templates/templates", root / "map-templates/templates")
            shutil.copy(ROOT / "dsconfig.json", root / "dsconfig.json")
            config_path = root / "mapconfig.json"
            config_path.write_text(json.dumps(self.config))
            env = dict(os.environ, TRAFICINFO_ROOT=str(root),
                       TRAFICINFO_DSCONFIG=str(root / "dsconfig.json"))

            def run(*args):
                return subprocess.run([str(ROOT / "scripts/generate-map-templates.sh"), *args],
                                      env=env, capture_output=True, text=True)

            self.assertEqual(run("--check").returncode, 1)
            self.assertEqual(run("--dry-run").returncode, 0)
            self.assertFalse((root / "map-templates/NIM.umap").exists())
            self.assertEqual(run().returncode, 0)
            output = root / "map-templates/MMM.umap"
            original = output.read_bytes()
            timestamp = output.stat().st_mtime_ns
            self.assertEqual(run("--check").returncode, 0)
            self.assertEqual(run().returncode, 0)
            self.assertEqual(output.stat().st_mtime_ns, timestamp)
            self.config["maps"]["MMM"]["name"] = "Changed title"
            self.config["maps"]["NIM"]["dataSources"] = ["UNKNOWN"]
            config_path.write_text(json.dumps(self.config))
            self.assertEqual(run().returncode, 1)
            self.assertEqual(output.read_bytes(), original)


if __name__ == "__main__":
    unittest.main()
