"""Exercise the alarm reconciler with a fake OCI CLI; no network or email delivery."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
FAKE_OCI = '''#!/usr/bin/env python3
import json, os, pathlib, sys
args = sys.argv[1:]
path = pathlib.Path(os.environ['FAKE_OCI_STATE'])
state = json.loads(path.read_text())
state.setdefault('calls', []).append(args)
def value(flag): return args[args.index(flag) + 1]
if args[:3] == ['ons', 'topic', 'list']:
    data = [{'name': 'TraficInfoMonitoring', 'topic-id': 'topic', 'lifecycle-state': 'ACTIVE'}]
elif args[:3] == ['ons', 'subscription', 'list']:
    data = [{'topic-id': 'topic', 'protocol': 'EMAIL', 'endpoint': 'review@example.org',
             'lifecycle-state': state.get('subscription', 'ACTIVE')}]
elif args[:3] == ['monitoring', 'alarm', 'list']:
    data = state.get('alarms', [])
elif args[:3] == ['monitoring', 'alarm', 'get']:
    data = next(alarm for alarm in state['alarms'] if alarm['id'] == value('--alarm-id'))
elif args[:2] == ['monitoring', 'alarm'] and args[2] in ('create', 'update'):
    payload = json.loads(value('--from-json'))
    def kebab(key):
        return ''.join('-' + char.lower() if char.isupper() else char for char in key)
    data = {('query' if key == 'queryText' else kebab(key)): item for key, item in payload.items()}
    alarm_id = 'alarm-not-found' if data['display-name'].endswith('NotFound') else 'alarm'
    data.update({'id': alarm_id, 'lifecycle-state': 'ACTIVE', 'suppression': None})
    state['alarms'] = [item for item in state.get('alarms', []) if item['id'] != alarm_id] + [data]
else:
    raise AssertionError(args)
path.write_text(json.dumps(state))
print(json.dumps({'data': data}))
'''


class OsmReviewAlarmTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        directory = Path(self.temp.name)
        executable = directory / 'oci'
        executable.write_text(FAKE_OCI)
        executable.chmod(0o755)
        self.state = directory / 'state.json'
        self.state.write_text('{}')
        self.env = dict(os.environ, PATH=str(directory) + os.pathsep + os.environ['PATH'],
                        TRAFICINFO_ROOT=str(ROOT), OCI_COMPARTMENT_ID='ocid1.compartment.oc1..test',
                        FAKE_OCI_STATE=str(self.state))
        self.env.pop('OCI_FN_ALERT_TOPIC_NAME', None)

    def run_script(self, *args):
        return subprocess.run(['bash', str(ROOT / 'scripts/setup-osm-review-alert.sh'),
                               'review@example.org', *args], env=self.env, text=True,
                              capture_output=True)

    def read_state(self):
        return json.loads(self.state.read_text())

    def writes(self):
        return [call for call in self.read_state()['calls']
                if call[:2] == ['monitoring', 'alarm'] and call[2] in ('create', 'update')]

    def test_dry_run_preserves_dimensions_and_does_not_write(self):
        result = self.run_script('--dry-run')
        self.assertEqual(0, result.returncode, result.stderr)
        payload = json.loads(result.stdout)
        self.assertEqual('create', payload['operation'])
        self.assertEqual('traficinfo', payload['namespace'])
        self.assertEqual('OSMWayMappings[1h]{status = "NEEDS_REVIEW"}.groupBy(datasourceId).max() > 0',
                         payload['queryText'])
        self.assertFalse(payload['isNotificationsPerMetricDimensionEnabled'])
        self.assertEqual('P1D', payload['repeatNotificationDuration'])
        self.assertEqual('PT1M', payload['pendingDuration'])
        self.assertEqual(['topic'], payload['destinations'])
        self.assertEqual([], self.writes())

    def test_create_skip_and_reconcile_drift(self):
        result = self.run_script()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('create', self.writes()[0][2])
        self.assertEqual(0, self.run_script().returncode)
        self.assertEqual(1, len(self.writes()))
        state = self.read_state()
        state['alarms'][0]['repeat-notification-duration'] = 'PT1H'
        state['alarms'][0]['is-notifications-per-metric-dimension-enabled'] = True
        self.state.write_text(json.dumps(state))
        result = self.run_script()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('update', self.writes()[1][2])
        alarm = self.read_state()['alarms'][0]
        self.assertEqual('P1D', alarm['repeat-notification-duration'])
        self.assertFalse(alarm['is-notifications-per-metric-dimension-enabled'])

    def test_not_found_dry_run_and_creation_preserve_review_alarm(self):
        self.assertEqual(0, self.run_script().returncode)
        review_alarm = self.read_state()['alarms'][0]
        result = self.run_script('--status', 'NOT_FOUND', '--dry-run')
        self.assertEqual(0, result.returncode, result.stderr)
        payload = json.loads(result.stdout)
        self.assertEqual('TraficInfo-OSMWayMappingsNotFound', payload['displayName'])
        self.assertEqual('OSMWayMappings[1h]{status = "NOT_FOUND"}.groupBy(datasourceId).max() > 0',
                         payload['queryText'])
        self.assertFalse(payload['isNotificationsPerMetricDimensionEnabled'])
        self.assertEqual('P1D', payload['repeatNotificationDuration'])
        self.assertIn('<datasourceId>_OSMWayMapping.json', payload['body'])
        self.assertNotIn('OSMWayMapping_NEEDS_REVIEW.json', payload['body'])
        self.assertEqual(1, len(self.writes()))
        result = self.run_script('--status', 'NOT_FOUND')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(2, len(self.read_state()['alarms']))
        self.assertEqual(review_alarm, self.read_state()['alarms'][0])
        self.assertEqual(2, len(self.writes()))
        result = self.run_script('--status', 'NOT_FOUND')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(2, len(self.writes()))

    def test_invalid_status_rejected_before_oci_calls(self):
        for args in [('--status', 'MATCHED'), ('--status',)]:
            self.assertNotEqual(0, self.run_script(*args).returncode)
            self.assertEqual({}, self.read_state())

    def test_unconfirmed_email_rejected_without_writes(self):
        self.state.write_text(json.dumps({'subscription': 'PENDING'}))
        self.assertNotEqual(0, self.run_script().returncode)
        self.assertEqual([], self.writes())

    def test_duplicate_and_suppressed_alarms_rejected(self):
        self.assertEqual(0, self.run_script().returncode)
        original = self.read_state()
        for duplicate in (True, False):
            state = json.loads(json.dumps(original))
            state['calls'] = []
            if duplicate:
                state['alarms'].append(dict(state['alarms'][0], id='duplicate'))
            else:
                state['alarms'][0]['suppression'] = {'time-suppress-until': 'future'}
            self.state.write_text(json.dumps(state))
            self.assertNotEqual(0, self.run_script().returncode)
            self.assertEqual([], self.writes())


if __name__ == '__main__':
    unittest.main()
