"""Exercise OCI shell scripts against a local fake CLI; no cloud calls are made."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = ROOT / 'scripts'
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
elif command == ['fn', 'application', 'get']:
    emit(next(app for app in state['applications'] if app['id'] == opt('--application-id')))
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
    if '--timeout-in-seconds' in args: function['timeout-in-seconds'] = int(opt('--timeout-in-seconds'))
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
        for key in ['OCI_FN_APPLICATION_ID', 'BUCKETNAME_IN', 'BUCKETNAME_DPT34', 'BUCKETNAME_MRN',
                    'BUCKETNAME_NIM', 'BUCKETNAME_OUT', 'BUCKETNAME_OSM', 'OSM_CACHE_OBJECT', 'OSM_WAY_MAPPING_OBJECT', 'MAX_LOOP_DURATION_SECONDS', 'MAX_WAY_MAPPINGS_PER_RESOURCE']:
            self.env.pop(key, None)
        self.state = {
            'applications': [{'id': 'ocid1.fnapp.test', 'display-name': '3MOpenDataProcessor',
                              'lifecycle-state': 'ACTIVE'}],
            'functions': [{'id': 'ocid1.fnfunc.' + name, 'display-name': name,
                           'lifecycle-state': 'ACTIVE', 'defined-tags': {'Old': {'Tag': 'value'}}}
                          for name in ['3MOpenDataSource', 'DPT34OpenDataSource', 'DPT30OpenDataSource',
                                       '3MGeoJsonOut', 'DPT34GeoJsonOut', 'DPT30GeoJsonOut',
                                       'MRNOpenDataSource', 'MRNGeoJsonOut', 'NIMOpenDataSource', 'OSMInfoFunction']],
            'buckets': [{'name': name, 'compartment-id': 'ocid1.compartment.test',
                         'storage-tier': 'Standard', 'object-events-enabled': True,
                         'public-access-type': 'NoPublicAccess'}
                        for name in ['ds3mbucket', 'dsdpt34', 'dsdpt30', 'dsmrn', 'dsnim', 'traficinfo', 'osminfo']],
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
        return self.run_shell('set -euo pipefail; source "$TRAFICINFO_ROOT/scripts/oci-common.sh"; ' + code,
                              success=success)

    def script(self, name, *args, success=True):
        return self.run_shell('exec bash "$@"', SCRIPTS / name, *args, success=success)

    def deploy(self, success=True):
        library = self.directory / 'deploy-library.sh'
        library.write_text((SCRIPTS / 'deploy-functions.sh').read_text().rsplit('\nmain "$@"', 1)[0])
        return self.run_shell('set -euo pipefail; source "$1"; build_jar() { :; }; '
                              'validate_jar() { :; }; main', library, success=success)

    def deployment_common(self, code, *args, success=True):
        library = self.directory / 'deploy-library.sh'
        library.write_text((SCRIPTS / 'deploy-functions.sh').read_text().rsplit('\nmain "$@"', 1)[0])
        return self.run_shell('set -euo pipefail; source "$1"; shift; ' + code,
                              library, *args, success=success)

    def test_configuration_limit_counts_utf8_keys_and_values(self):
        for config, application, expected in [
                ({}, {}, 0),
                ({'KEY': 'a' * 4093}, {}, 4096),
                ({'KEY': 'é' * 2046 + 'a'}, {}, 4096),
                ({'KEY': '"' * 4093}, {}, 4096),
                ({'KEY': 'function'}, {'KEY': 'application', 'OTHER': 'é'}, 18)]:
            with self.subTest(expected=expected):
                result = self.deployment_common('configuration_size_bytes "$1" "$2"',
                                               json.dumps(config), json.dumps(application))
                self.assertEqual(int(result.stdout), expected)
                self.deployment_common('validate_configuration_size "$1" test "$2"',
                                       json.dumps(config), json.dumps(application))
        result = self.deployment_common('validate_configuration_size "$1" test',
                                       json.dumps({'KEY': 'é' * 2047}), success=False)
        self.assertIn('4097 UTF-8 bytes', result.stderr)
        self.assertNotIn('calls', self.state)

    def test_oversized_source_configuration_fails_before_cloud_calls(self):
        config = json.loads(self.config_file.read_text())
        config['MRN']['datasets']['mrn-2026']['inputs'][0]['url'] = 'https://example.invalid/' + 'x' * 4096
        self.config_file.write_text(json.dumps(config))
        result = self.deploy(success=False)
        self.assertIn('MRNOpenDataSource configuration uses', result.stderr)
        self.assertNotIn('calls', self.state)

    def test_application_settings_count_towards_limit_before_any_function_changes(self):
        self.state['applications'][0]['config'] = {'SHARED': 'x' * 3700}
        self.save()
        result = self.deploy(success=False)
        self.assertIn('3MOpenDataSource configuration uses', result.stderr)
        self.assertIn('OCI limit is 4096', result.stderr)
        self.assertNotIn('writes', self.state)
        self.assertFalse(any(call[:3] == ['fn', 'function', 'list'] for call in self.state['calls']))

    def test_application_config_read_failure_stops_deployment(self):
        self.state['fail_command'] = ['fn', 'application', 'get']
        self.save()
        self.deploy(success=False)
        self.assertNotIn('writes', self.state)

    def test_library_has_no_source_side_effects(self):
        for shell in ['bash', 'zsh']:
            with self.subTest(shell=shell):
                self.run_shell('before=$(set +o); before_traps=$(trap); before_dir=$PWD; '
                               'source "$TRAFICINFO_ROOT/scripts/oci-common.sh"; '
                               'test "$before" = "$(set +o)"; test "$before_traps" = "$(trap)"; '
                               'test "$before_dir" = "$PWD"', shell=shell)
        self.assertNotIn('calls', self.state)

    def test_source_defaults_and_overrides(self):
        sources = json.loads(self.common('load_data_sources').stdout)
        self.assertEqual(sources[0]['dataSourceFunction'], '3MOpenDataSource')
        self.assertEqual(sources[0]['inputBucket'], 'ds3mbucket')
        dpt34 = next(s for s in sources if s['id'] == 'DPT34')
        self.assertEqual(dpt34['geoJsonFunction'], 'DPT34GeoJsonOut')
        self.assertEqual(dpt34['csvObject'], 'DPT34_Comptage.csv')
        self.assertEqual(dpt34['inputBucket'], 'dsdpt34')
        mrn = next(s for s in sources if s['id'] == 'MRN')
        self.assertEqual(mrn['geoJsonFunction'], 'MRNGeoJsonOut')
        self.assertEqual(mrn['csvObject'], 'MRN_Comptage.csv')
        self.assertEqual(mrn['inputBucket'], 'dsmrn')
        self.common('get_data_source UNKNOWN', success=False)

    def test_retrieval_settings_derive_csv_names_and_preserve_companion_order(self):
        config = json.loads(self.config_file.read_text())
        config['DPT30']['csvObject'] = 'custom-counts.csv'
        config['DPT30']['datasets']['current']['inputs'][0]['url'] = 'https://example.invalid/new.csv'
        self.config_file.write_text(json.dumps(config))
        settings = json.loads(self.common('data_source_settings "$(get_data_source DPT30)"').stdout)
        self.assertEqual(settings['dataSourceId'], 'DPT30')
        self.assertEqual(settings['inputs'][0]['objectName'], 'custom-counts.csv')
        self.assertEqual(settings['inputs'][0]['url'], 'https://example.invalid/new.csv')
        self.assertNotIn('calls', self.state)

    def test_active_dataset_selection_controls_retrieval_preprocessing_and_timeouts(self):
        config = json.loads(self.config_file.read_text())
        nim = config['NIM']
        nim['datasets']['nim-2023'] = {
            'years': [2023],
            'datasetInfoUrl': 'https://example.invalid/2023',
            'timeoutSeconds': 120,
            'inputs': [{'url': 'https://example.invalid/2023.csv', 'contentType': 'text/csv'}],
        }
        nim['activeDatasetId'] = 'nim-2023'
        # Inactive settings must not be included in the function configuration budget.
        nim['datasets']['nim-2022']['inputs'][0]['url'] = 'https://example.invalid/' + 'x' * 4096
        self.config_file.write_text(json.dumps(config))
        settings = json.loads(self.common('preprocessing_settings "$(get_data_source NIM)"').stdout)
        self.assertEqual(settings['source']['datasetId'], 'nim-2023')
        self.assertEqual(settings['source']['inputs'], [{
            'url': 'https://example.invalid/2023.csv',
            'objectName': 'NIM_Comptage.csv', 'contentType': 'text/csv',
        }])
        self.assertEqual(settings['datasetUrl'], 'https://example.invalid/2023')
        self.assertEqual(settings['years'], [2023])
        self.deployment_common('build_jar() { :; }; validate_jar() { :; }; main ds NIM')
        function = next(f for f in self.state['functions'] if f['display-name'] == 'NIMOpenDataSource')
        self.assertEqual(function['timeout-in-seconds'], 120)
        self.assertEqual(json.loads(function['config']['DATA_SOURCE_SETTINGS']), settings['source'])
        self.script('invoke.sh', 'ds', 'NIM')
        call = self.state['calls'][-1]
        self.assertEqual(call[call.index('--read-timeout') + 1], '180')

    def test_missing_or_invalid_active_dataset_fails_before_cloud_calls(self):
        original = json.loads(self.config_file.read_text())
        for changes in [
                {'activeDatasetId': None}, {'activeDatasetId': ''}, {'activeDatasetId': []},
                {'activeDatasetId': 'unknown'}, {'datasets': None}, {'datasets': []},
                {'datasets': {'nim-2022': None}}, {'datasets': {'nim-2022': []}}]:
            with self.subTest(changes=changes):
                config = json.loads(json.dumps(original))
                config['NIM'].update(changes)
                self.config_file.write_text(json.dumps(config))
                self.deployment_common('main ds NIM', success=False)
                self.script('invoke.sh', 'ds', 'NIM', success=False)
                self.assertFalse(self.state.get('calls', []))

    def test_datasource_deployment_passes_settings_to_every_source(self):
        config = json.loads(self.config_file.read_text())
        config['MRN']['activeDatasetId'] = 'replacement-dataset'
        config['MRN']['datasets']['replacement-dataset'] = config['MRN']['datasets'].pop('mrn-2026')
        config['MRN']['datasets']['replacement-dataset']['inputs'][0]['url'] = 'https://example.invalid/replacement.csv'
        self.config_file.write_text(json.dumps(config))
        for existing in [True, False]:
            with self.subTest(existing=existing):
                if not existing:
                    self.state['functions'] = []
                    self.save()
                self.deploy()
                functions = {item['display-name']: item for item in self.state['functions']}
                for source_id, entry in config.items():
                    name = entry.get('functionPrefix', source_id) + 'OpenDataSource'
                    settings = json.loads(functions[name]['config']['DATA_SOURCE_SETTINGS'])
                    self.assertEqual(settings['dataSourceId'], source_id)
                    self.assertEqual(settings['datasetId'], entry['activeDatasetId'])
                    self.assertEqual([item['url'] for item in settings['inputs']],
                                     [item['url'] for item in entry['datasets'][entry['activeDatasetId']]['inputs']])
                self.assertEqual([item['objectName'] for item in json.loads(
                    functions['3MOpenDataSource']['config']['DATA_SOURCE_SETTINGS'])['inputs']],
                    ['MMM_MMM_Comptage.json', 'MMM_MMM_Comptage.csv'])

    def test_invalid_retrieval_settings_fail_before_deployment_calls(self):
        original = json.loads(self.config_file.read_text())
        for invalid in [None, {'inputs': []},
                        {'inputs': [{'url': 'file:///tmp/input', 'contentType': 'text/csv'}]},
                        {'inputs': [
                            {'url': 'https://example.invalid/a', 'contentType': 'text/csv'},
                            {'url': 'https://example.invalid/b', 'contentType': 'text/csv'}]}]:
            config = json.loads(json.dumps(original))
            config['DPT30']['datasets']['current'] = invalid
            self.config_file.write_text(json.dumps(config))
            self.deploy(success=False)
            self.assertNotIn('calls', self.state)

    def test_nim_defaults_and_download_only_routing(self):
        source = json.loads(self.common('get_data_source NIM').stdout)
        settings = json.loads(self.common('data_source_settings "$(get_data_source NIM)"').stdout)
        self.assertEqual(source['inputBucket'], 'dsnim')
        self.assertEqual(source['dataSourceFunction'], 'NIMOpenDataSource')
        self.assertEqual(source['csvObject'], 'NIM_Comptage.csv')
        self.assertFalse(source['geoJsonEnabled'])
        self.assertEqual(settings['datasetId'], 'nim-2022')
        self.assertIn('lagglomeration-2022/exports/csv?', settings['inputs'][0]['url'])
        self.assertEqual(settings['inputs'][0]['objectName'], 'NIM_Comptage.csv')
        result = self.script('invoke.sh', 'geojson', 'NIM', success=False)
        self.assertIn('GeoJSON is not enabled for NIM', result.stderr)
        self.assertNotIn('calls', self.state)

    def test_nim_deployment_has_long_timeout_and_stays_within_configuration_budget(self):
        for existing in [True, False]:
            with self.subTest(existing=existing):
                if not existing:
                    self.state['functions'] = [item for item in self.state['functions']
                                               if item['display-name'] != 'NIMOpenDataSource']
                    self.save()
                self.deployment_common('build_jar() { :; }; validate_jar() { :; }; main ds NIM')
                function = next(item for item in self.state['functions']
                                if item['display-name'] == 'NIMOpenDataSource')
                self.assertEqual(function['timeout-in-seconds'], 300)
                self.assertEqual(function['defined-tags'], {'TraficInfo': {'DataSourceSchedule': 'Monthly'}})
                self.assertEqual(function['config']['BUCKETNAME'], 'dsnim')
                settings = json.loads(function['config']['DATA_SOURCE_SETTINGS'])
                self.assertEqual(settings['datasetId'], 'nim-2022')
                size = sum(len(k.encode()) + len(v.encode()) for k, v in function['config'].items())
                self.assertLessEqual(size, 4096)
                handler = [call for call in self.state['calls'] if '--handler' in call][-1]
                self.assertEqual(handler[handler.index('--handler') + 1],
                                 'org.traficinfo.dsfunctions.nim.DataSourceFunction::handleRequest')

    def test_preprocessing_deployment_and_invocation_are_explicit_and_share_the_handler(self):
        for existing in [False, True]:
            with self.subTest(existing=existing):
                self.deployment_common('build_jar() { :; }; validate_jar() { :; }; main preprocess NIM')
                function = next(f for f in self.state['functions'] if f['display-name'] == 'NIMPreprocessing')
                self.assertEqual(function['timeout-in-seconds'], 300)
                config = function['config']
                self.assertEqual(config['BUCKETNAME'], 'dsnim')
                settings = json.loads(config['PREPROCESSING_SETTINGS'])
                self.assertEqual(settings['source']['dataSourceId'], 'NIM')
                self.assertEqual(settings['source']['datasetId'], 'nim-2022')
                self.assertEqual(settings['datasetUrl'],
                                 json.loads(self.config_file.read_text())['NIM']['datasets']['nim-2022']['datasetInfoUrl'])
                self.assertEqual(settings['years'], [2022])
                self.assertEqual(settings['source']['inputs'][0]['objectName'], 'NIM_Comptage.csv')
                self.assertLessEqual(sum(len(k.encode()) + len(v.encode()) for k, v in config.items()), 4096)
                self.assertNotIn('TraficInfo', function.get('defined-tags', {}))
        self.script('invoke.sh', 'preprocess', 'NIM')
        call = self.state['calls'][-1]
        self.assertEqual(call[call.index('--read-timeout') + 1], '360')
        self.assertTrue(any('org.traficinfo.dsfunctions.PreprocessingFunction::handleRequest' in call
                            for call in self.state['calls']))

    def test_preprocessing_requires_explicit_valid_years_before_any_oci_call(self):
        config = json.loads(self.config_file.read_text())
        for years in [None, [], [2022, 2022], [0], [3000], [2022.5], ['2022']]:
            with self.subTest(years=years):
                config['NIM']['datasets']['nim-2022']['years'] = years
                self.config_file.write_text(json.dumps(config))
                self.deployment_common('main preprocess NIM', success=False)
                self.assertFalse(self.state.get('calls', []))
        del config['MRN']['datasets']['mrn-2026']['years']
        self.config_file.write_text(json.dumps(config))
        self.deployment_common('main preprocess MRN', success=False)
        self.assertFalse(self.state.get('calls', []))

    def test_dpt30_preprocessing_uses_configured_years_and_shared_handler(self):
        self.deployment_common('build_jar() { :; }; validate_jar() { :; }; main preprocess DPT30')
        function = next(f for f in self.state['functions'] if f['display-name'] == 'DPT30Preprocessing')
        settings = json.loads(function['config']['PREPROCESSING_SETTINGS'])
        self.assertEqual(settings['source']['dataSourceId'], 'DPT30')
        self.assertEqual(settings['years'], [2021, 2022, 2023, 2024, 2025])
        self.assertEqual(function['config']['BUCKETNAME'], 'dsdpt30')
        self.assertEqual(function['timeout-in-seconds'], 300)
        self.assertTrue(any('org.traficinfo.dsfunctions.PreprocessingFunction::handleRequest' in call
                            for call in self.state['calls']))
        self.script('invoke.sh', 'preprocess', 'DPT30')
        call = self.state['calls'][-1]
        self.assertEqual(call[call.index('--function-id') + 1], function['id'])
        self.assertEqual(call[call.index('--read-timeout') + 1], '360')

    def test_nim_invocation_has_matching_client_timeout(self):
        self.script('invoke.sh', 'ds', 'NIM')
        call = self.state['calls'][-1]
        self.assertEqual(call[call.index('--function-id') + 1], 'ocid1.fnfunc.NIMOpenDataSource')
        self.assertEqual(call[call.index('--read-timeout') + 1], '360')

    def test_nim_has_no_geojson_deployment_or_event_rule(self):
        self.deployment_common('main geojson NIM', success=False)
        self.assertNotIn('calls', self.state)
        self.script('setup-events.sh')
        self.assertEqual(len(self.state['rules']), 4)
        self.assertFalse(any('NIM' in item['display-name'] for item in self.state['rules']))
        self.assertFalse(any('NIMGeoJsonOut' in ' '.join(call) for call in self.state['calls']))

    def test_download_only_sources_need_no_events_application_lookup(self):
        config = json.loads(self.config_file.read_text())
        self.config_file.write_text(json.dumps({'NIM': config['NIM']}))
        result = self.script('setup-events.sh')
        self.assertIn('No data sources have GeoJSON processing enabled', result.stderr)
        self.assertNotIn('calls', self.state)

    def test_invalid_geojson_flags_and_source_timeouts_are_rejected(self):
        original = json.loads(self.config_file.read_text())
        for field, values in [('geoJsonEnabled', ['false', 0]),
                              ('timeoutSeconds', [0, -1, 301, 1.5, '300'])]:
            for value in values:
                config = json.loads(json.dumps(original))
                if field == 'geoJsonEnabled': config['NIM'][field] = value
                else: config['NIM']['datasets']['nim-2022'][field] = value
                self.config_file.write_text(json.dumps(config))
                self.common('load_data_sources', success=False)
        self.assertNotIn('calls', self.state)

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
        self.assertNotIn('--read-timeout', self.state['calls'][-1])
        self.script('invoke.sh', 'ds', 'DPT34')
        self.assertNotIn('--read-timeout', self.state['calls'][-1])
        self.script('invoke.sh', 'ds', 'UNKNOWN', success=False)

    def test_invoke_osm_without_data_source_config(self):
        self.config_file.unlink()
        result = self.script('invoke.sh', 'osm')
        self.assertIn('ocid1.fnfunc.OSMInfoFunction', result.stdout)
        call = self.state['calls'][-1]
        self.assertEqual(call[:3], ['fn', 'function', 'invoke'])
        self.assertEqual(call[call.index('--fn-invoke-type') + 1], 'sync')
        self.assertEqual(call[call.index('--body') + 1], '')
        self.assertEqual(call[call.index('--file') + 1], '-')
        self.assertEqual(call[call.index('--read-timeout') + 1], '360')
        self.state['functions'] = [f for f in self.state['functions']
                                   if f['display-name'] != 'OSMInfoFunction']
        self.state['calls'] = []
        self.save()
        self.script('invoke.sh', 'osm', success=False)
        self.assertFalse(any(c[:3] == ['fn', 'function', 'invoke'] for c in self.state['calls']))

    def test_invoke_rejects_invalid_arguments_before_oci_calls(self):
        for args in [(), ('osm', 'DPT34'), ('osm', 'extra', 'extra'),
                     ('ds',), ('geojson',), ('ds', 'MMM', 'extra'), ('unknown',)]:
            with self.subTest(args=args):
                self.assertIn('Usage:', self.script('invoke.sh', *args, success=False).stderr)
        self.assertNotIn('calls', self.state)

    def test_deploy_creates_and_updates_correct_configs(self):
        for existing in [True, False]:
            with self.subTest(existing=existing):
                if not existing:
                    self.state['functions'] = []
                    self.state['writes'] = []
                    self.save()
                self.deploy()
                self.assertEqual(len(self.state['writes']), 10)
                functions = {f['display-name']: f for f in self.state['functions']}
                self.assertEqual(functions['OSMInfoFunction']['config'],
                                 {'NAMESPACE': 'testnamespace', 'BUCKETNAME_OSM': 'osminfo',
                                  'MAX_LOOP_DURATION_SECONDS': '120', 'MAX_WAY_MAPPINGS_PER_RESOURCE': '5'})
                self.assertEqual(functions['OSMInfoFunction']['timeout-in-seconds'], 300)
                self.assertEqual(functions['OSMInfoFunction']['defined-tags'],
                                 {'TraficInfo': {'DataSourceSchedule': 'Hourly'}})
                source_config = functions['3MOpenDataSource']['config']
                self.assertEqual(source_config['NAMESPACE'], 'testnamespace')
                self.assertEqual(source_config['BUCKETNAME'], 'ds3mbucket')
                retrieval = json.loads(source_config['DATA_SOURCE_SETTINGS'])
                self.assertEqual(retrieval['dataSourceId'], 'MMM')
                self.assertEqual([item['objectName'] for item in retrieval['inputs']],
                                 ['MMM_MMM_Comptage.json', 'MMM_MMM_Comptage.csv'])
                self.assertEqual(functions['DPT34OpenDataSource']['config']['BUCKETNAME'], 'dsdpt34')
                self.assertEqual(functions['MRNOpenDataSource']['config']['BUCKETNAME'], 'dsmrn')
                self.assertEqual(functions['MRNGeoJsonOut']['config'],
                                 {'NAMESPACE': 'testnamespace', 'BUCKETNAME_IN': 'dsmrn',
                                  'BUCKETNAME_OUT': 'traficinfo', 'BUCKETNAME_OSM': 'osminfo',
                                  'OSM_CACHE_OBJECT': 'MRN_OSMInfo.json',
                                  'OSM_WAY_MAPPING_OBJECT': 'MRN_OSMWayMapping.json'})
                self.assertEqual(functions['3MGeoJsonOut']['config']['BUCKETNAME_OSM'], 'osminfo')
                self.assertEqual(functions['DPT34GeoJsonOut']['config']['BUCKETNAME_OSM'], 'osminfo')
                self.assertEqual(functions['DPT34GeoJsonOut']['config']['OSM_CACHE_OBJECT'], 'DPT34_OSMInfo.json')
                self.assertEqual(functions['DPT34GeoJsonOut']['config']['OSM_WAY_MAPPING_OBJECT'], 'DPT34_OSMWayMapping.json')
                for name, cadence in [('3MOpenDataSource', 'Daily'), ('DPT34OpenDataSource', 'Monthly'),
                                      ('MRNOpenDataSource', 'Daily')]:
                    self.assertEqual(functions[name]['defined-tags'],
                                     {'TraficInfo': {'DataSourceSchedule': cadence}})
                for name in ['3MGeoJsonOut', 'DPT34GeoJsonOut', 'MRNGeoJsonOut']:
                    self.assertNotIn('TraficInfo', functions[name].get('defined-tags', {}))

    def test_osm_report_uses_bucket_override_and_handler(self):
        self.env['BUCKETNAME_OSM'] = 'custom-osminfo'
        self.env['MAX_LOOP_DURATION_SECONDS'] = '60'
        self.env['MAX_WAY_MAPPINGS_PER_RESOURCE'] = '2'
        self.deploy()
        function = next(f for f in self.state['functions'] if f['display-name'] == 'OSMInfoFunction')
        self.assertEqual(function['config'],
                         {'NAMESPACE': 'testnamespace', 'BUCKETNAME_OSM': 'custom-osminfo',
                          'MAX_LOOP_DURATION_SECONDS': '60', 'MAX_WAY_MAPPINGS_PER_RESOURCE': '2'})
        calls = [c for c in self.state['calls'] if '--handler' in c
                 and c[c.index('--handler') + 1] == 'org.traficinfo.dsfunctions.OSMInfoFunction::handleRequest']
        self.assertEqual(len(calls), 1)
        self.assertEqual(calls[0][calls[0].index('--function-id') + 1], function['id'])

    def test_invalid_osm_limits_fail_before_deployment(self):
        for key in ['MAX_LOOP_DURATION_SECONDS', 'MAX_WAY_MAPPINGS_PER_RESOURCE']:
            for value in ['', '0', '-1', 'abc', '1.5', '2147483648']:
                with self.subTest(key=key, value=value):
                    self.env[key] = value
                    self.deploy(success=False)
                    self.assertNotIn('writes', self.state)
            self.env.pop(key)

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
        self.assertEqual(conditions['MRNComptageCsvToGeoJson']['data']['resourceName'],
                         ['MRN_Comptage.csv'])
        self.assertEqual(conditions['MRNComptageCsvToGeoJson']['data']['additionalDetails']['bucketName'],
                         ['dsmrn'])
        self.script('setup-schedules.sh')
        self.assertEqual(len(self.state['schedules']), 4)
        schedules = {s['display-name']: s for s in self.state['schedules']}
        self.assertEqual(schedules['Hourly']['recurrence-details'], 'FREQ=HOURLY;BYMINUTE=0;BYSECOND=0')
        self.assertEqual(schedules['Hourly']['local-time-zone'], 'UTC')
        hourly_tags = next(f for f in schedules['Hourly']['resource-filters'] if f['attribute'] == 'DEFINED_TAGS')
        self.assertEqual(hourly_tags['value'],
                         [{'namespace': 'TraficInfo', 'tag-key': 'DataSourceSchedule', 'value': 'Hourly'}])
        self.assertEqual(schedules['Daily']['recurrence-details'],
                         'FREQ=DAILY;BYHOUR=15;BYMINUTE=30;BYSECOND=0')
        self.assertEqual(schedules['Weekly']['recurrence-details'],
                         'FREQ=WEEKLY;BYDAY=WE;BYHOUR=15;BYMINUTE=30;BYSECOND=0')
        self.assertEqual(schedules['Monthly']['recurrence-details'],
                         'FREQ=MONTHLY;BYMONTHDAY=5;BYHOUR=15;BYMINUTE=30;BYSECOND=0')
        writes = list(self.state['writes'])
        self.script('setup-events.sh')
        self.script('setup-schedules.sh')
        self.assertEqual(self.state['writes'], writes)
        self.state['rules'][0]['is-enabled'] = False
        hourly = next(s for s in self.state['schedules'] if s['display-name'] == 'Hourly')
        hourly['resources'] = [{'id': 'old-target'}]
        hourly['recurrence-details'] = 'FREQ=DAILY;BYHOUR=15;BYMINUTE=30;BYSECOND=0'
        self.save()
        self.script('setup-events.sh')
        self.script('setup-schedules.sh')
        self.assertEqual(self.state['writes'][-2][1], 'update')
        self.assertEqual(self.state['writes'][-1], ['schedule', 'update', 'Hourly'])
        hourly = next(s for s in self.state['schedules'] if s['display-name'] == 'Hourly')
        self.assertEqual(hourly['resources'], [])
        self.assertEqual(hourly['recurrence-details'], 'FREQ=HOURLY;BYMINUTE=0;BYSECOND=0')

    def test_setup_in_bash_and_zsh_exports_environment(self):
        scripts = self.directory / 'scripts'
        scripts.mkdir()
        setup = scripts / 'setup.sh'
        setup.write_text((SCRIPTS / 'setup.sh').read_text().replace('/usr/libexec/java_home -v 25',
                                                               "printf '/mock/jdk'"))
        (scripts / 'oci-common.sh').write_text((SCRIPTS / 'oci-common.sh').read_text())
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
