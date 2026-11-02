"""Exercise the real invocation script with a fake OCI executable; no network calls."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
ARTIFACTS = ROOT / 'target/archive-preprocessing-tests'


class ArchiveInvocationTest(unittest.TestCase):
    def setUp(self):
        ARTIFACTS.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(dir=ARTIFACTS)
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.calls = self.directory / 'calls.jsonl'
        fake = self.directory / 'oci'
        fake.write_text('#!' + sys.executable + '\n' + '''
import json, os, sys
args = sys.argv[1:]
with open(os.environ['TEST_OCI_CALLS'], 'a') as file:
    file.write(json.dumps(args) + '\\n')
if args[:3] == ['fn', 'function', 'list']:
    name = args[args.index('--display-name') + 1]
    print(json.dumps({'data': [{'id': 'ocid1.fnfunc.test', 'display-name': name, 'lifecycle-state': 'ACTIVE'}]}))
elif args[:3] == ['fn', 'function', 'invoke']:
    print('OK')
else:
    sys.exit('Unexpected OCI call')
''')
        fake.chmod(0o755)
        self.config = self.directory / 'dsconfig.json'
        self.config.write_bytes((ROOT / 'dsconfig.json').read_bytes())
        self.environment = {**os.environ, 'PATH': str(self.directory) + os.pathsep + os.environ['PATH'],
                            'TRAFICINFO_ROOT': str(ROOT), 'TRAFICINFO_DSCONFIG': str(self.config),
                            'OCI_FN_APPLICATION_ID': 'ocid1.fnapp.test', 'TEST_OCI_CALLS': str(self.calls)}

    def invoke(self, *args):
        return subprocess.run(['bash', str(ROOT / 'scripts/invoke.sh'), *args],
                              env=self.environment, text=True, capture_output=True)

    def recorded(self):
        return [json.loads(line) for line in self.calls.read_text().splitlines()] if self.calls.exists() else []

    def test_archive_identifiers_are_sent_and_configuration_is_unchanged(self):
        before = self.config.read_bytes()
        for dataset in ['mmm-2011-2021', 'mmm-2022-2024']:
            result = self.invoke('preprocess', 'MMM', dataset)
            self.assertEqual(result.returncode, 0, result.stderr)
            calls = self.recorded()
            self.assertEqual(calls[-2][calls[-2].index('--display-name') + 1], 'PreprocessingFunction')
            invocation = calls[-1]
            request = json.loads(invocation[invocation.index('--body') + 1])
            self.assertEqual(request, {'datasourceId': 'MMM', 'datasetId': dataset})
            self.assertEqual(invocation[invocation.index('--read-timeout') + 1], '360')
        self.assertEqual(before, self.config.read_bytes())

    def test_normal_invocations_select_datasource_for_shared_stages(self):
        for kind in ['preprocess', 'ds', 'geojson']:
            result = self.invoke(kind, 'MMM')
            self.assertEqual(result.returncode, 0, result.stderr)
            args = self.recorded()[-1]
            body = args[args.index('--body') + 1]
            self.assertEqual(body, '' if kind == 'geojson' else '{"datasourceId":"MMM"}')

    def test_invalid_selection_fails_before_oci(self):
        for args in [('preprocess', 'MMM', 'missing'), ('preprocess', 'MMM', 'current'),
                     ('ds', 'MMM', 'mmm-2022-2024'), ('geojson', 'MMM', 'mmm-2022-2024')]:
            result = self.invoke(*args)
            self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.recorded(), [])

    def test_archive_inputs_must_be_explicit(self):
        config = json.loads(self.config.read_text())
        del config['MMM']['datasets']['mmm-2022-2024']['inputs'][1]['objectName']
        self.config.write_text(json.dumps(config))
        result = self.invoke('preprocess', 'MMM', 'mmm-2022-2024')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('explicit objectName', result.stderr)
        self.assertEqual(self.recorded(), [])

    def test_archive_cannot_reference_current_objects(self):
        config = json.loads(self.config.read_text())
        config['MMM']['datasets']['mmm-2022-2024']['inputs'][1]['objectName'] = 'MMM_MMM_Comptage.csv'
        self.config.write_text(json.dumps(config))
        result = self.invoke('preprocess', 'MMM', 'mmm-2022-2024')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('active input Objects', result.stderr)
        self.assertEqual(self.recorded(), [])


if __name__ == '__main__':
    unittest.main()
