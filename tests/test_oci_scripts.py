"""Exercise OCI shell scripts against a local fake CLI; no cloud calls are made."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
MOCK_OCI = r'''#!/usr/bin/env python3
import json, os, pathlib, sys
args = sys.argv[1:]
path = pathlib.Path(os.environ['MOCK_STATE'])
state = json.loads(path.read_text())
state.setdefault('calls', []).append(args)
path.write_text(json.dumps(state))
def opt(key): return args[args.index(key) + 1]
def emit(data): print(json.dumps({'data': data}))
def save(): path.write_text(json.dumps(state))
def write(service, operation, name):
    assert '--if-match' not in args
    state.setdefault('writes', []).append([service, operation, name])
    save()
def wire_actions(value):
    keys = {'actionType': 'action-type', 'isEnabled': 'is-enabled', 'functionId': 'function-id'}
    return {'actions': [{keys.get(k, k): v for k, v in a.items()} for a in value['actions']]}
def wire_filters(filters):
    for f in filters:
        if 'shouldIncludeChildCompartments' in f:
            f['should-include-child-compartments'] = f.pop('shouldIncludeChildCompartments')
        if f['attribute'] == 'DEFINED_TAGS':
            for v in f['value']: v['tag-key'] = v.pop('tagKey')
    return filters
command = args[:3]
if state.get('fail_command') == command: sys.exit(7)
if command == ['iam', 'compartment', 'list']:
    emit([{'id': 'ocid1.compartment.test'}])
elif command == ['os', 'ns', 'get']: print('testnamespace')
elif command == ['fn', 'application', 'list']:
    if state.get('malformed_applications'): emit({})
    else: emit(state['applications'])
elif command == ['fn', 'function', 'list']:
    if state.get('blank_functions'): sys.exit(0)
    if state.get('malformed_functions'): emit({})
    else: emit([f for f in state['functions'] if f['display-name'] == opt('--display-name')])
elif command == ['fn', 'function', 'invoke']: print('invoked ' + opt('--function-id'))
elif command in [['fn', 'function', 'create'], ['fn', 'function', 'update']]:
    operation = args[2]
    if operation == 'create':
        name = opt('--display-name')
        function = {'id': 'ocid1.fnfunc.' + name, 'display-name': name, 'lifecycle-state': 'ACTIVE'}
        state['functions'].append(function)
    else:
        function = next(f for f in state['functions'] if f['id'] == opt('--function-id'))
        name = function['display-name']
    function['config'] = json.loads(opt('--config'))
    if '--defined-tags' in args: function['defined-tags'] = json.loads(opt('--defined-tags'))
    write('function', operation, name)
    print('ocid1.functionsworkrequest.test')
elif command == ['fn', 'work-request', 'get']: print('SUCCEEDED')
elif command == ['os', 'bucket', 'list']: emit(state['buckets'])
elif command == ['os', 'bucket', 'get']:
    emit(next(b for b in state['buckets'] if b['name'] == opt('--name')))
elif command in [['os', 'bucket', 'create'], ['os', 'bucket', 'update']]:
    name = opt('--name')
    if args[2] == 'create':
        bucket = {'name': name, 'compartment-id': opt('--compartment-id'), 'storage-tier': 'Standard'}
        state['buckets'].append(bucket)
    else: bucket = next(b for b in state['buckets'] if b['name'] == name)
    if '--object-events-enabled' in args: bucket['object-events-enabled'] = True
    if '--public-access-type' in args: bucket['public-access-type'] = opt('--public-access-type')
    write('bucket', args[2], name); emit(bucket)
elif command == ['events', 'rule', 'list']:
    emit([r for r in state['rules'] if r['display-name'] == opt('--display-name')])
elif command == ['events', 'rule', 'get']:
    emit(next(r for r in state['rules'] if r['id'] == opt('--rule-id')))
elif command in [['events', 'rule', 'create'], ['events', 'rule', 'update']]:
    if args[2] == 'create':
        name = opt('--display-name')
        rule = {'id': 'rule-' + name, 'display-name': name, 'compartment-id': opt('--compartment-id')}
        state['rules'].append(rule)
    else: rule = next(r for r in state['rules'] if r['id'] == opt('--rule-id'))
    rule.update({'condition': opt('--condition'), 'actions': wire_actions(json.loads(opt('--actions'))),
                 'is-enabled': True, 'lifecycle-state': 'ACTIVE'})
    write('rule', args[2], rule['display-name']); emit(rule)
elif command == ['resource-scheduler', 'schedule', 'list']: emit({'items': state['schedules']})
elif command == ['resource-scheduler', 'schedule', 'get']:
    emit(next(s for s in state['schedules'] if s['id'] == opt('--schedule-id')))
elif command in [['resource-scheduler', 'schedule', 'create'], ['resource-scheduler', 'schedule', 'update']]:
    if args[2] == 'create':
        payload = json.loads(opt('--from-json'))
        keys = {'displayName': 'display-name', 'compartmentId': 'compartment-id',
                'recurrenceType': 'recurrence-type', 'recurrenceDetails': 'recurrence-details',
                'localTimeZone': 'local-time-zone', 'resourceFilters': 'resource-filters'}
        schedule = {keys.get(k, k): v for k, v in payload.items()}
        schedule['id'] = 'schedule-' + payload['displayName']
        state['schedules'].append(schedule)
    else:
        schedule = next(s for s in state['schedules'] if s['id'] == opt('--schedule-id'))
        for key in ['action', 'recurrence-type', 'recurrence-details', 'local-time-zone']:
            schedule[key] = opt('--' + key)
        schedule['resources'] = json.loads(opt('--resources'))
        schedule['resource-filters'] = json.loads(opt('--resource-filters'))
    schedule['resource-filters'] = wire_filters(schedule['resource-filters'])
    schedule['lifecycle-state'] = 'ACTIVE'
    write('schedule', args[2], schedule['display-name'])
    emit({'status': 'SUCCEEDED'})
else: raise AssertionError(args)
'''


class OciScriptsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='traficinfo-oci-test-')
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.state_file = self.directory / 'state.json'
        self.config_file = self.directory / 'dsconfig.json'
        self.config_file.write_text((ROOT / 'dsconfig.json').read_text())
        mock = self.directory / 'oci'
        mock.write_text(MOCK_OCI)
        mock.chmod(0o755)
        self.env = dict(os.environ, PATH=str(self.directory) + os.pathsep + os.environ['PATH'],
                        MOCK_STATE=str(self.state_file), TRAFICINFO_ROOT=str(ROOT),
                        TRAFICINFO_DSCONFIG=str(self.config_file),
                        OCI_COMPARTMENT_ID='ocid1.compartment.test',
                        OCI_OBJECT_STORAGE_NAMESPACE='testnamespace',
                        OCI_FN_APPLICATION_NAME='3MOpenDataProcessor')
        for key in ['OCI_FN_APPLICATION_ID', 'BUCKETNAME_IN', 'BUCKETNAME_DPT34',
                    'BUCKETNAME_OUT', 'BUCKETNAME_OSM', 'OSM_CACHE_OBJECT', 'OSM_WAY_MAPPING_OBJECT']:
            self.env.pop(key, None)
        self.state = {
            'applications': [{'id': 'ocid1.fnapp.test', 'display-name': '3MOpenDataProcessor',
                              'lifecycle-state': 'ACTIVE'}],
            'functions': [{'id': 'ocid1.fnfunc.' + name, 'display-name': name,
                           'lifecycle-state': 'ACTIVE', 'defined-tags': {'Old': {'Tag': 'value'}}}
                          for name in ['3MOpenDataSource', 'DPT34OpenDataSource',
                                       '3MGeoJsonOut', 'DPT34GeoJsonOut']],
            'buckets': [{'name': name, 'compartment-id': 'ocid1.compartment.test',
                         'storage-tier': 'Standard', 'object-events-enabled': True,
                         'public-access-type': 'NoPublicAccess'}
                        for name in ['ds3mbucket', 'dsdpt34', 'traficinfo', 'osminfo']],
            'rules': [], 'schedules': []}
        self.save()

    def save(self):
        self.state_file.write_text(json.dumps(self.state))

    def run_shell(self, code, *args, success=True, shell='bash', env=None):
        result = subprocess.run([shell, '-c', code, 'test', *map(str, args)],
                                cwd=self.directory, env=env or self.env,
                                capture_output=True, text=True)
        self.assertEqual(result.returncode == 0, success, result.stderr)
        self.state = json.loads(self.state_file.read_text())
        return result

    def common(self, code, success=True):
        return self.run_shell('set -euo pipefail; source "$TRAFICINFO_ROOT/oci-common.sh"; ' + code,
                              success=success)

    def script(self, name, *args, success=True):
        return self.run_shell('exec bash "$@"', ROOT / name, *args, success=success)

    def deploy(self, success=True):
        library = self.directory / 'deploy-library.sh'
        library.write_text((ROOT / 'deploy-functions.sh').read_text().rsplit('\nmain "$@"', 1)[0])
        return self.run_shell('set -euo pipefail; source "$1"; build_jar() { :; }; '
                              'validate_jar() { :; }; main', library, success=success)

    def test_library_has_no_source_side_effects(self):
        for shell in ['bash', 'zsh']:
            with self.subTest(shell=shell):
                self.run_shell('before=$(set +o); before_traps=$(trap); before_dir=$PWD; '
                               'source "$TRAFICINFO_ROOT/oci-common.sh"; '
                               'test "$before" = "$(set +o)"; test "$before_traps" = "$(trap)"; '
                               'test "$before_dir" = "$PWD"', shell=shell)
        self.assertNotIn('calls', self.state)

    def test_source_defaults_and_overrides(self):
        sources = json.loads(self.common('load_data_sources').stdout)
        self.assertEqual(sources[0]['dataSourceFunction'], '3MOpenDataSource')
        self.assertEqual(sources[0]['inputBucket'], 'ds3mbucket')
        self.assertEqual(sources[1]['geoJsonFunction'], 'DPT34GeoJsonOut')
        self.assertEqual(sources[1]['csvObject'], 'DPT34_Comptage.csv')
        self.assertEqual(sources[1]['inputBucket'], 'dsdpt34')
        self.common('get_data_source UNKNOWN', success=False)

    def test_invalid_source_config(self):
        for value in [{'MMM': {'id': 'WRONG'}}, {'MMM': {'id': 'MMM', 'inputBucket': ''}},
                      {'MMM': {'id': 'MMM', 'functionPrefix': 'X'},
                       'DPT34': {'id': 'DPT34', 'functionPrefix': 'X'}}]:
            self.config_file.write_text(json.dumps(value))
            self.common('load_data_sources', success=False)
        self.assertNotIn('writes', self.state)

    def test_dry_run_argument_validation(self):
        self.assertEqual(self.common('parse_dry_run_arguments').stdout.strip(), 'false')
        self.assertEqual(self.common('parse_dry_run_arguments --dry-run').stdout.strip(), 'true')
        self.common('parse_dry_run_arguments --wrong', success=False)
        for script in ['setup-buckets.sh', 'setup-events.sh', 'setup-schedules.sh']:
            self.script(script, '--dry-run', 'extra', success=False)

    def test_lookup_modes_and_errors(self):
        self.common('resolve_application_id')
        self.state['functions'] = []
        self.save()
        self.assertEqual(self.common('find_function_id ocid1.fnapp.test Missing').stdout.strip(), '')
        self.common('resolve_function_id ocid1.fnapp.test Missing', success=False)
        self.state['blank_functions'] = True
        self.save()
        self.common('find_function_id ocid1.fnapp.test Missing')
        self.state.pop('blank_functions')
        self.state['malformed_functions'] = True
        self.save()
        self.common('find_function_id ocid1.fnapp.test Missing', success=False)
        self.deploy(success=False)
        self.assertNotIn('writes', self.state)
        self.state.pop('malformed_functions')
        self.state['fail_command'] = ['fn', 'function', 'list']
        self.save()
        self.common('find_function_id ocid1.fnapp.test Missing', success=False)

    def test_duplicate_application_and_function_rejected(self):
        self.state['applications'] *= 2
        self.save()
        self.common('resolve_application_id', success=False)
        self.state['applications'] = self.state['applications'][:1]
        self.state['functions'].append(dict(self.state['functions'][0], id='ocid1.fnfunc.duplicate'))
        self.save()
        self.common('find_function_id ocid1.fnapp.test 3MOpenDataSource', success=False)
        self.deploy(success=False)
        self.assertNotIn('writes', self.state)

    def test_application_id_override(self):
        self.env['OCI_FN_APPLICATION_ID'] = 'ocid1.fnapp.override'
        self.assertEqual(self.common('resolve_application_id').stdout.strip(), 'ocid1.fnapp.override')
        self.assertNotIn('calls', self.state)

    def test_invoke_uses_config_prefix(self):
        config = json.loads(self.config_file.read_text())
        config['MMM']['functionPrefix'] = 'Custom'
        self.config_file.write_text(json.dumps(config))
        self.state['functions'].append({'id': 'ocid1.fnfunc.custom', 'display-name': 'CustomGeoJsonOut',
                                        'lifecycle-state': 'ACTIVE'})
        self.save()
        self.assertIn('ocid1.fnfunc.custom', self.script('invoke.sh', 'geojson', 'MMM').stdout)
        self.script('invoke.sh', 'ds', 'UNKNOWN', success=False)

    def test_deploy_creates_and_updates_correct_configs(self):
        for existing in [True, False]:
            with self.subTest(existing=existing):
                if not existing:
                    self.state['functions'] = []
                    self.state['writes'] = []
                    self.save()
                self.deploy()
                self.assertEqual(len(self.state['writes']), 4)
                functions = {f['display-name']: f for f in self.state['functions']}
                self.assertEqual(functions['3MOpenDataSource']['config'],
                                 {'NAMESPACE': 'testnamespace', 'BUCKETNAME': 'ds3mbucket'})
                self.assertEqual(functions['DPT34OpenDataSource']['config']['BUCKETNAME'], 'dsdpt34')
                self.assertEqual(functions['3MGeoJsonOut']['config']['BUCKETNAME_OSM'], 'osminfo')
                self.assertEqual(functions['DPT34GeoJsonOut']['config']['BUCKETNAME_OSM'], 'osminfo')
                self.assertEqual(functions['DPT34GeoJsonOut']['config']['OSM_CACHE_OBJECT'], 'DPT34_OSMInfo.json')
                self.assertEqual(functions['DPT34GeoJsonOut']['config']['OSM_WAY_MAPPING_OBJECT'], 'DPT34_OSMWayMapping.json')
                for name, cadence in [('3MOpenDataSource', 'Daily'), ('DPT34OpenDataSource', 'Monthly')]:
                    self.assertEqual(functions[name]['defined-tags'],
                                     {'TraficInfo': {'DataSourceSchedule': cadence}})
                for name in ['3MGeoJsonOut', 'DPT34GeoJsonOut']:
                    self.assertNotIn('TraficInfo', functions[name].get('defined-tags', {}))

    def test_bucket_configuration_follows_source_config(self):
        config = json.loads(self.config_file.read_text())
        config['DPT34']['inputBucket'] = 'custom-input'
        self.config_file.write_text(json.dumps(config))
        self.script('setup-buckets.sh', '--dry-run')
        self.assertNotIn('writes', self.state)
        self.script('setup-buckets.sh')
        buckets = {b['name']: b for b in self.state['buckets']}
        self.assertTrue(buckets['custom-input']['object-events-enabled'])
        self.assertEqual(buckets['traficinfo']['public-access-type'], 'ObjectReadWithoutList')
        writes = list(self.state['writes'])
        self.script('setup-buckets.sh')
        self.assertEqual(self.state['writes'], writes)

    def test_events_and_schedules_reconcile_and_skip(self):
        for script in ['setup-events.sh', 'setup-schedules.sh']:
            self.script(script, '--dry-run')
        self.assertNotIn('writes', self.state)
        self.script('setup-events.sh')
        conditions = {r['display-name']: json.loads(r['condition']) for r in self.state['rules']}
        self.assertEqual(conditions['3MComptageCsvToGeoJson']['data']['resourceName'],
                         ['MMM_MMM_Comptage.csv'])
        self.assertEqual(conditions['DPT34ComptageCsvToGeoJson']['data']['additionalDetails']['bucketName'],
                         ['dsdpt34'])
        self.script('setup-schedules.sh')
        self.assertEqual(len(self.state['schedules']), 3)
        writes = list(self.state['writes'])
        self.script('setup-events.sh')
        self.script('setup-schedules.sh')
        self.assertEqual(self.state['writes'], writes)
        self.state['rules'][0]['is-enabled'] = False
        self.state['schedules'][0]['resources'] = [{'id': 'old-target'}]
        self.save()
        self.script('setup-events.sh')
        self.script('setup-schedules.sh')
        self.assertEqual(self.state['writes'][-2][1], 'update')
        self.assertEqual(self.state['writes'][-1][1], 'update')

    def test_setup_in_bash_and_zsh_exports_environment(self):
        setup = self.directory / 'setup.sh'
        setup.write_text((ROOT / 'setup.sh').read_text().replace('/usr/libexec/java_home -v 25',
                                                               "printf '/mock/jdk'"))
        (self.directory / 'oci-common.sh').write_text((ROOT / 'oci-common.sh').read_text())
        credentials = self.directory / 'oci-config'
        credentials.write_text('[DEFAULT]\nregion=eu-frankfurt-1\n')
        fn = self.directory / 'fn'
        fn.write_text('#!/usr/bin/env bash\nexit 0\n')
        fn.chmod(0o755)
        for shell in ['bash', 'zsh']:
            env = dict(self.env, OCI_CLI_CONFIG_FILE=str(credentials))
            env.pop('OCI_COMPARTMENT_ID')
            for key in ['OCI_FN_COMPARTMENT_ID', 'OCI_BUCKET_COMPARTMENT_ID', 'OCI_SCHEDULE_COMPARTMENT_ID']:
                env.pop(key, None)
            self.run_shell('source "$1" >/dev/null || exit; '
                           'test "$TRAFICINFO_ROOT" = "$2"; '
                           'bash -c \'test "$OCI_COMPARTMENT_ID" = ocid1.compartment.test '
                           '&& test "$OCI_OBJECT_STORAGE_NAMESPACE" = testnamespace\'',
                           setup, self.directory, shell=shell, env=env)


if __name__ == '__main__':
    unittest.main()
