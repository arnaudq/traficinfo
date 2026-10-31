"""Verify failure alarms without contacting OCI or sending email."""
import json
import unittest

import test_oci_scripts as existing


ALERT_COMMANDS = r'''
elif command == ['ons', 'topic', 'list']: emit(state['topics'])
elif command == ['ons', 'subscription', 'list']: emit(state['subscriptions'])
elif command == ['monitoring', 'alarm', 'list']: emit(state['alarms'])
elif command == ['monitoring', 'alarm', 'get']:
    emit(next(a for a in state['alarms'] if a['id'] == opt('--alarm-id')))
elif command in [['monitoring', 'alarm', 'create'], ['monitoring', 'alarm', 'update']]:
    payload = json.loads(opt('--from-json'))
    keys = {'compartmentId': 'compartment-id', 'displayName': 'display-name',
            'metricCompartmentId': 'metric-compartment-id',
            'metricCompartmentIdInSubtree': 'metric-compartment-id-in-subtree',
            'queryText': 'query', 'isEnabled': 'is-enabled', 'pendingDuration': 'pending-duration',
            'messageFormat': 'message-format',
            'notificationTitle': 'notification-title', 'alarmSummary': 'alarm-summary',
            'isNotificationsPerMetricDimensionEnabled': 'is-notifications-per-metric-dimension-enabled'}
    if args[2] == 'create':
        alarm = {'id': 'ocid1.alarm.' + payload['displayName']}
        state['alarms'].append(alarm)
    else: alarm = next(a for a in state['alarms'] if a['id'] == opt('--alarm-id'))
    alarm.update({keys.get(k, k): v for k, v in payload.items()})
    alarm['lifecycle-state'] = 'ACTIVE'
    write('alarm', args[2], alarm['display-name']); emit(alarm)
'''


class FunctionAlertsTest(unittest.TestCase):
    save = existing.OciScriptsTest.save
    run_shell = existing.OciScriptsTest.run_shell
    script = existing.OciScriptsTest.script

    def setUp(self):
        existing.OciScriptsTest.setUp(self)
        mock = self.directory / 'oci'
        mock.write_text(existing.MOCK_OCI.replace(
            '\nelse: raise AssertionError(args)', ALERT_COMMANDS + '\nelse: raise AssertionError(args)'))
        self.state.update(topics=[{'topic-id': 'ocid1.onstopic.test', 'name': 'TraficInfoMonitoring',
                                   'lifecycle-state': 'ACTIVE'}],
                          subscriptions=[{'topic-id': 'ocid1.onstopic.test', 'protocol': 'EMAIL',
                                          'endpoint': 'alerts@example.org', 'lifecycle-state': 'ACTIVE'}],
                          alarms=[])
        self.env.pop('OCI_FN_ALERT_TOPIC_NAME', None)
        self.save()

    def run_alerts(self, *args, success=True):
        return self.script('setup-function-alerts.sh', 'alerts@example.org', *args, success=success)

    def test_preview_then_create_and_repeat(self):
        result = self.run_alerts('--dry-run')
        self.assertEqual(['create', 'create'], [a['operation'] for a in json.loads(result.stdout)])
        self.assertNotIn('writes', self.state)
        self.run_alerts()
        availability, alarm = self.state['alarms']
        self.assertEqual(['ocid1.onstopic.test'], alarm['destinations'])
        self.assertEqual('FunctionResponseCount[1m]{applicationId = "ocid1.fnapp.test", '
                         'responseType = "Error", ErrorCode != "503"}'
                         '.groupBy(resourceId, resourceDisplayName, ErrorCode, ErrorMessage).sum() > 0', alarm['query'])
        self.assertEqual('CRITICAL', alarm['severity'])
        self.assertEqual('FunctionResponseCount[15m]{applicationId = "ocid1.fnapp.test", '
                         'responseType = "Error", ErrorCode = "503"}'
                         '.groupBy(resourceId, resourceDisplayName, ErrorCode, ErrorMessage).sum() >= 3', availability['query'])
        self.assertEqual('WARNING', availability['severity'])
        self.assertIn('does not mean retries are exhausted', availability['body'])
        self.assertTrue(alarm['is-notifications-per-metric-dimension-enabled'])
        self.assertEqual('PT1M', alarm['pending-duration'])
        self.assertIn('{{dimensions.resourceDisplayName}}', alarm['body'])
        self.assertIn('{{dimensions.resourceDisplayName}}', alarm['alarm-summary'])
        self.assertIn('{{status}}', alarm['notification-title'])
        self.assertIn('{{alarmUrl}}', alarm['body'])
        self.assertIn('{{dimensions.ErrorCode}}', alarm['body'])
        self.assertIn('{{dimensions.ErrorMessage}}', alarm['body'])
        self.run_alerts()
        self.assertEqual([['alarm', 'create', 'TraficInfo-FunctionAvailability'],
                          ['alarm', 'create', 'TraficInfo-FunctionErrors']], self.state['writes'])

    def test_update_disabled_alarm_and_wrong_destination(self):
        self.run_alerts()
        self.state['alarms'][0].update({'is-enabled': False, 'destinations': ['old-topic'],
                                        'overrides': [{'query': 'old rule'}]})
        self.save()
        self.run_alerts('--dry-run')
        self.assertEqual(2, len(self.state['writes']))
        self.run_alerts()
        self.assertEqual('update', self.state['writes'][-1][1])
        self.assertEqual([], self.state['alarms'][0]['overrides'])

    def test_old_email_format_is_updated_even_when_detection_is_unchanged(self):
        self.run_alerts()
        self.state['alarms'][0].pop('notification-title')
        self.state['alarms'][0].pop('alarm-summary')
        self.save()
        self.run_alerts()
        self.assertEqual('update', self.state['writes'][-1][1])
        self.assertIn('{{dimensions.resourceDisplayName}}', self.state['alarms'][0]['alarm-summary'])

    def test_missing_or_unconfirmed_subscription_and_missing_topic_stop_writes(self):
        for key, value in [('subscriptions', []), ('topics', []), ('subscriptions', [
                {'topic-id': 'ocid1.onstopic.test', 'protocol': 'EMAIL',
                 'endpoint': 'alerts@example.org', 'lifecycle-state': 'PENDING'}])]:
            with self.subTest(key=key, value=value):
                previous = self.state[key]
                self.state[key] = value
                self.save()
                self.run_alerts(success=False)
                self.assertNotIn('writes', self.state)
                self.state[key] = previous

    def test_failed_discovery_and_duplicate_alarms_stop_writes(self):
        for command in [['fn', 'application', 'list'], ['ons', 'topic', 'list'],
                        ['ons', 'subscription', 'list'], ['monitoring', 'alarm', 'list']]:
            self.state['fail_command'] = command
            self.save()
            self.run_alerts(success=False)
            self.assertNotIn('writes', self.state)
        self.state.pop('fail_command')
        self.state['alarms'] = [{'id': str(i), 'display-name': 'TraficInfo-FunctionErrors',
                                 'lifecycle-state': 'ACTIVE'} for i in range(2)]
        self.save()
        self.run_alerts(success=False)
        self.assertNotIn('writes', self.state)

    def test_legacy_alarm_is_renamed_after_availability_is_created(self):
        self.state['alarms'] = [{'id': 'legacy-id', 'display-name': '3MOpenDataProcessor-FunctionErrors',
                                 'compartment-id': 'ocid1.compartment.test', 'lifecycle-state': 'ACTIVE'}]
        self.save()
        preview = json.loads(self.run_alerts('--dry-run').stdout)
        self.assertEqual(['create', 'update'], [a['operation'] for a in preview])
        self.assertEqual('legacy-id', preview[1]['alarmId'])
        self.assertNotIn('writes', self.state)
        self.run_alerts()
        self.assertEqual([['alarm', 'create', 'TraficInfo-FunctionAvailability'],
                          ['alarm', 'update', 'TraficInfo-FunctionErrors']], self.state['writes'])
        migrated = next(a for a in self.state['alarms'] if a['id'] == 'legacy-id')
        self.assertEqual('TraficInfo-FunctionErrors', migrated['display-name'])
        self.assertIn('ErrorCode != "503"', migrated['query'])
        self.run_alerts()
        self.assertEqual(2, len(self.state['writes']))

    def test_suppression_on_second_alarm_prevents_all_writes(self):
        self.run_alerts()
        self.state.pop('writes')
        self.state['alarms'][0]['is-enabled'] = False
        self.state['alarms'][1]['suppression'] = {'description': 'maintenance'}
        self.save()
        self.run_alerts(success=False)
        self.assertNotIn('writes', self.state)

    def test_simultaneous_legacy_and_replacement_stop_before_writes(self):
        self.run_alerts()
        self.state.pop('writes')
        self.state['alarms'].append({'id': 'legacy-id', 'display-name': '3MOpenDataProcessor-FunctionErrors',
                                     'lifecycle-state': 'ACTIVE'})
        self.save()
        self.run_alerts(success=False)
        self.assertNotIn('writes', self.state)

    def test_invalid_arguments_stop_before_cloud_calls(self):
        self.run_alerts('--wrong', success=False)
        self.assertNotIn('calls', self.state)
        self.script('setup-function-alerts.sh', success=False)
        self.script('setup-function-alerts.sh', 'bad-email', success=False)
        self.assertNotIn('calls', self.state)


if __name__ == '__main__':
    unittest.main()
