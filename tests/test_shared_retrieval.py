"""Shared retrieval deployment, invocation and per-datasource schedules against the fake OCI CLI."""
import copy
import json
import unittest

import test_oci_scripts as existing


class SharedRetrievalTest(unittest.TestCase):
    save = existing.OciScriptsTest.save
    run_shell = existing.OciScriptsTest.run_shell
    script = existing.OciScriptsTest.script
    deploy = existing.OciScriptsTest.deploy

    def setUp(self):
        existing.OciScriptsTest.setUp(self)
        shared = next(f for f in self.state['functions'] if f['display-name'] == 'DataSourceFunction')
        shared['id'] = 'ocid1.fnfunc.shared'
        self.save()

    def deploy_retrieval(self, *args, success=True):
        library = self.directory / 'deploy-library.sh'
        library.write_text((existing.SCRIPTS / 'deploy-functions.sh').read_text().rsplit('\nmain "$@"', 1)[0])
        return self.run_shell('set -euo pipefail; source "$1"; shift; build_jar() { :; }; '
                              'validate_jar() { :; }; main "$@"', library, 'ds', *args, success=success)

    def test_single_deployment_and_minimal_invocation(self):
        self.deploy_retrieval()
        self.assertEqual([['function', 'update', 'DataSourceFunction']], self.state['writes'])
        shared = next(f for f in self.state['functions'] if f['display-name'] == 'DataSourceFunction')
        self.assertEqual({'NAMESPACE': 'testnamespace'},
                         shared['config'])
        self.assertEqual({}, shared['defined-tags'])
        self.assertEqual(300, shared['timeout-in-seconds'])
        update = next(c for c in self.state['calls'] if c[:3] == ['fn', 'function', 'update'])
        self.assertEqual('org.traficinfo.dsfunctions.DataSourceFunction::handleRequest', update[update.index('--handler') + 1])
        self.script('invoke.sh', 'ds', 'DPT34')
        invoke = self.state['calls'][-1]
        self.assertEqual('ocid1.fnfunc.shared', invoke[invoke.index('--function-id') + 1])
        self.assertEqual({'datasourceId': 'DPT34'}, json.loads(invoke[invoke.index('--body') + 1]))

    def test_legacy_deploy_selector_still_deploys_shared_function_and_creation_works(self):
        self.state['functions'] = [f for f in self.state['functions'] if f['display-name'] != 'DataSourceFunction']
        self.save()
        self.deploy_retrieval('MMM')
        self.assertEqual([['function', 'create', 'DataSourceFunction']], self.state['writes'])
        shared = next(f for f in self.state['functions'] if f['display-name'] == 'DataSourceFunction')
        self.assertEqual({'NAMESPACE': 'testnamespace'}, shared['config'])

    def test_generated_schedules_payloads_clock_override_and_repeat(self):
        config = json.loads(self.config_file.read_text())
        config['MRN']['scheduleClock'] = 'BYHOUR=16;BYMINUTE=10;BYSECOND=0'
        config['DPT34'].pop('scheduleClock', None)  # Exercise the fallback clock.
        config['DPT30']['schedule'] = 'Hourly'
        config['DPT30']['scheduleClock'] = 'BYMINUTE=25;BYSECOND=0'
        self.config_file.write_text(json.dumps(config))
        self.script('setup-schedules.sh', '--dry-run')
        self.assertNotIn('writes', self.state)
        self.script('setup-schedules.sh')
        schedules = {s['display-name']: s for s in self.state['schedules']}
        self.assertEqual({'Hourly'} | {id + 'Retrieval' for id in config}, set(schedules))
        self.assertEqual('FREQ=DAILY;BYHOUR=16;BYMINUTE=10;BYSECOND=0', schedules['MRNRetrieval']['recurrence-details'])
        self.assertEqual('FREQ=HOURLY;BYMINUTE=25;BYSECOND=0', schedules['DPT30Retrieval']['recurrence-details'])
        self.assertEqual('FREQ=MONTHLY;BYMONTHDAY=5;BYHOUR=15;BYMINUTE=30;BYSECOND=0',
                         schedules['DPT34Retrieval']['recurrence-details'])
        for id in config:
            resource, = schedules[id + 'Retrieval']['resources']
            self.assertEqual('ocid1.fnfunc.shared', resource['id'])
            parameter, = resource['parameters']
            self.assertEqual('BODY', parameter['parameter-type'])
            self.assertEqual({'datasourceId': id}, json.loads(parameter['value']))
            self.assertEqual([], schedules[id + 'Retrieval']['resource-filters'])
        self.assertEqual([], schedules['Hourly']['resources'])
        self.assertEqual(3, len(schedules['Hourly']['resource-filters']))
        writes = copy.deepcopy(self.state['writes'])
        self.script('setup-schedules.sh')
        self.assertEqual(writes, self.state['writes'])
        # Correct a drifted invocation payload and recurrence without replacing the schedule.
        current = next(s for s in self.state['schedules'] if s['display-name'] == 'MRNRetrieval')
        current['resources'][0]['parameters'][0]['value'] = {'datasourceId': 'MMM'}
        self.save()
        self.script('setup-schedules.sh')
        self.assertEqual(['schedule', 'update', 'MRNRetrieval'], self.state['writes'][-1])

    def test_invalid_clocks_and_ambiguous_schedules_prevent_all_writes(self):
        original = json.loads(self.config_file.read_text())
        for clock in ['BYHOUR=24;BYMINUTE=0;BYSECOND=0', 'BYHOUR=1;BYMINUTE=60;BYSECOND=0', 'FREQ=MINUTELY', 12, False]:
            with self.subTest(clock=clock):
                config = copy.deepcopy(original)
                config['MRN']['scheduleClock'] = clock
                self.config_file.write_text(json.dumps(config))
                self.script('setup-schedules.sh', success=False)
                self.assertNotIn('calls', self.state)
        self.config_file.write_text(json.dumps(original))
        self.script('setup-schedules.sh')
        duplicate = copy.deepcopy(self.state['schedules'][1])
        self.state['schedules'].append(duplicate)
        writes = copy.deepcopy(self.state['writes'])
        self.save()
        self.script('setup-schedules.sh', success=False)
        self.assertEqual(writes, self.state['writes'])

    def test_legacy_cadence_schedules_are_left_untouched(self):
        old = [{'id': 'legacy-' + name, 'display-name': name, 'lifecycle-state': 'ACTIVE',
                'resources': [{'id': 'old-target'}]} for name in ['Daily', 'Weekly', 'Monthly']]
        self.state['schedules'] = copy.deepcopy(old)
        self.save()
        self.script('setup-schedules.sh')
        self.assertEqual(old, self.state['schedules'][:3])

    def test_payload_comparison_accepts_json_strings_and_objects_but_rejects_drift(self):
        library = self.directory / 'schedule-library.sh'
        library.write_text((existing.SCRIPTS / 'setup-schedules.sh').read_text().rsplit('\nmain "$@"', 1)[0])
        desired = {'compartmentId': 'ocid1.compartment.test', 'action': 'START_RESOURCE',
                   'recurrenceType': 'ICAL', 'recurrenceDetails': 'FREQ=DAILY;BYHOUR=15;BYMINUTE=30;BYSECOND=0',
                   'localTimeZone': 'UTC', 'resourceFilters': [],
                   'resources': [{'id': 'ocid1.fnfunc.shared', 'parameters': [
                       {'parameterType': 'BODY', 'value': {'datasourceId': 'MMM'}}]}]}
        actual = {'data': {'compartment-id': desired['compartmentId'], 'lifecycle-state': 'ACTIVE',
                          'action': desired['action'], 'recurrence-type': desired['recurrenceType'],
                          'recurrence-details': desired['recurrenceDetails'], 'local-time-zone': 'UTC',
                          'resource-filters': [], 'resources': copy.deepcopy(desired['resources'])}}
        for value, matches in [({'datasourceId': 'MMM'}, True),
                               ('{ "datasourceId" : "MMM" }', True),
                               ('{"datasourceId":"MRN"}', False),
                               ('invalid JSON', False),
                               ('{"datasourceId":"MMM","extra":true}', False)]:
            with self.subTest(value=value):
                actual['data']['resources'][0]['parameters'] = [{'parameter-type': 'BODY', 'value': value}]
                self.run_shell('set -euo pipefail; source "$1"; schedule_matches "$2" "$3"',
                               library, json.dumps(actual), json.dumps(desired), success=matches)
        self.assertNotIn('calls', self.state)



if __name__ == '__main__':
    unittest.main()
