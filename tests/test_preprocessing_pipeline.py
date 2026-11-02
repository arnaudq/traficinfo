"""Exercise the shared preprocessing deployment and both event transitions without OCI access."""
import json
import unittest

import test_oci_scripts as existing


class PreprocessingPipelineTest(unittest.TestCase):
    save = existing.OciScriptsTest.save
    run_shell = existing.OciScriptsTest.run_shell
    script = existing.OciScriptsTest.script
    deployment_common = existing.OciScriptsTest.deployment_common

    def setUp(self):
        existing.OciScriptsTest.setUp(self)

    def test_last_input_and_manifest_rules_exclude_archives(self):
        preview = self.script('setup-events.sh', '--dry-run')
        self.assertNotIn('writes', self.state)
        self.assertIn('RetrievalToPreprocessing', preview.stdout)
        self.script('setup-events.sh')
        config = json.loads(self.config_file.read_text())
        self.assertEqual(2 * len(config), len(self.state['rules']))
        for source_id, source in config.items():
            last = source['datasets'][source['activeDatasetId']]['inputs'][-1]
            raw = last.get('objectName', source.get('csvObject', source_id + '_Comptage.csv'))
            rules = [rule for rule in self.state['rules']
                     if json.loads(rule['condition'])['data']['resourceName'] in
                     [[raw], [source_id + '_Manifest.json']]]
            self.assertEqual(2, len(rules))
            for rule in rules:
                name = json.loads(rule['condition'])['data']['resourceName'][0]
                action, = rule['actions']['actions']
                if name == raw:
                    self.assertEqual('ocid1.fnfunc.PreprocessingFunction', action['function-id'])
                else:
                    prefix = config[source_id].get('functionPrefix', source_id)
                    self.assertEqual('ocid1.fnfunc.' + prefix + 'GeoJsonOut', action['function-id'])
        writes = list(self.state['writes'])
        self.script('setup-events.sh')
        self.assertEqual(writes, self.state['writes'])

    def test_shared_deployment_and_minimal_manual_and_archive_requests(self):
        self.state['functions'] = [f for f in self.state['functions'] if f['display-name'] != 'PreprocessingFunction']
        self.save()
        self.deployment_common('build_jar() { :; }; validate_jar() { :; }; main preprocess')
        shared = next(f for f in self.state['functions'] if f['display-name'] == 'PreprocessingFunction')
        self.assertEqual({'NAMESPACE': 'testnamespace'}, shared['config'])
        self.assertEqual(300, shared['timeout-in-seconds'])
        self.assertEqual({}, shared['defined-tags'])
        for args, expected in [(('NIM',), {'datasourceId': 'NIM'}),
                               (('MMM', 'mmm-2022-2024'), {'datasourceId': 'MMM', 'datasetId': 'mmm-2022-2024'})]:
            self.script('invoke.sh', 'preprocess', *args)
            call = self.state['calls'][-1]
            self.assertEqual(shared['id'], call[call.index('--function-id') + 1])
            self.assertEqual(expected, json.loads(call[call.index('--body') + 1]))
        before = len(self.state['calls'])
        self.script('invoke.sh', 'preprocess', 'MMM', 'current', success=False)
        self.assertEqual(before, len(self.state['calls']))

    def test_raw_event_override_is_rejected_before_cloud_calls(self):
        config = json.loads(self.config_file.read_text())
        config['NIM']['eventObject'] = 'NIM_Comptage.csv'
        self.config_file.write_text(json.dumps(config))
        result = self.script('setup-events.sh', success=False)
        self.assertIn('preprocessing manifest', result.stderr)
        self.assertNotIn('calls', self.state)


if __name__ == '__main__':
    unittest.main()
