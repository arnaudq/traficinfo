"""Verify application JSON logging setup without contacting OCI."""
import copy
import json
import unittest

import test_oci_scripts as existing


APPLICATION_COMMANDS = r'''
elif command == ['fn', 'application', 'update']:
    assert '--config' not in args
    assert '--display-name' not in args
    assert '--defined-tags' not in args
    assert '--if-match' not in args
    assert '--force' in args
    assert '--wait-for-state' not in args
    app = next(a for a in state['applications'] if a['id'] == opt('--application-id'))
    logging = json.loads(opt('--logging'))
    assert logging == {'lineFormat': 'JSON'}
    if not state.get('ignore_update'):
        app['logging'] = {'line-format': logging['lineFormat']}
    write('application', 'update', app['display-name'])
    if state.get('update_state'):
        app['lifecycle-state'] = state['update_state']
        save()
    if state.get('delayed_update'):
        app['lifecycle-state'] = 'UPDATING'
        state['pending_gets'] = 1
        save()
    emit(app)
'''

APPLICATION_GET = r'''
    app = next(app for app in state['applications'] if app['id'] == opt('--application-id'))
    if 'pending_gets' in state:
        if state['pending_gets'] == 0:
            app['lifecycle-state'] = 'ACTIVE'
        else:
            state['pending_gets'] -= 1
        save()
    emit(app)
'''


class ApplicationSetupTest(unittest.TestCase):
    save = existing.OciScriptsTest.save
    run_shell = existing.OciScriptsTest.run_shell
    script = existing.OciScriptsTest.script

    def setUp(self):
        existing.OciScriptsTest.setUp(self)
        (self.directory / 'oci').write_text(existing.MOCK_OCI.replace(
            '\nelse: raise AssertionError(args)', APPLICATION_COMMANDS + '\nelse: raise AssertionError(args)').replace(
                "    emit(next(app for app in state['applications'] if app['id'] == opt('--application-id')))",
                APPLICATION_GET))
        self.state['applications'][0].update({
            'compartment-id': 'ocid1.compartment.test',
            'logging': {'line-format': 'TEXT'},
            'config': {'EXISTING': 'retained'},
            'defined-tags': {'Existing': {'Tag': 'retained'}},
            'subnet-ids': ['ocid1.subnet.existing'],
        })
        self.save()

    def test_preview_update_and_idempotence_preserve_other_settings(self):
        before = copy.deepcopy(self.state['applications'][0])
        preview = self.script('setup-application.sh', '--dry-run')
        self.assertEqual({'operation': 'update', 'applicationId': before['id'],
                          'logging': {'lineFormat': 'JSON'}}, json.loads(preview.stdout))
        self.assertNotIn('writes', self.state)
        self.script('setup-application.sh')
        before['logging'] = {'line-format': 'JSON'}
        self.assertEqual(before, self.state['applications'][0])
        self.script('setup-application.sh')
        self.assertEqual([['application', 'update', '3MOpenDataProcessor']], self.state['writes'])

    def test_absent_logging_and_explicit_application_id(self):
        self.state['applications'][0].pop('logging')
        self.env['OCI_FN_APPLICATION_ID'] = 'ocid1.fnapp.test'
        self.save()
        self.script('setup-application.sh')
        self.assertEqual('JSON', self.state['applications'][0]['logging']['line-format'])
        self.assertFalse(any(call[:3] == ['fn', 'application', 'list'] for call in self.state['calls']))

    def test_invalid_target_or_failed_discovery_prevents_updates(self):
        original = copy.deepcopy(self.state)
        cases = [
            {'applications': []},
            {'applications': [original['applications'][0], original['applications'][0]]},
            {'fail_command': ['fn', 'application', 'list']},
            {'fail_command': ['fn', 'application', 'get']},
            {'applications': [dict(original['applications'][0], **{'compartment-id': 'other'})]},
            {'applications': [dict(original['applications'][0], **{'lifecycle-state': 'UPDATING'})]},
        ]
        for changes in cases:
            with self.subTest(changes=changes):
                self.state = dict(copy.deepcopy(original), **changes)
                self.save()
                self.script('setup-application.sh', success=False)
                self.assertNotIn('writes', self.state)

    def test_failed_update_and_verification_are_reported(self):
        original = copy.deepcopy(self.state)
        for changes in [{'fail_command': ['fn', 'application', 'update']},
                        {'update_state': 'FAILED'}]:
            with self.subTest(changes=changes):
                self.state = dict(copy.deepcopy(original), **changes)
                self.save()
                self.script('setup-application.sh', success=False)

    def test_unchanged_application_times_out_without_cli_waiter(self):
        self.state['ignore_update'] = True
        self.save()
        # Advance the shell clock instead of spending ten minutes on the timeout path.
        result = self.run_shell(
            'sleep() { SECONDS=$((SECONDS + 600)); }; export -f sleep; exec bash "$@"',
            existing.SCRIPTS / 'setup-application.sh', success=False)
        self.assertIn('Timed out waiting', result.stderr)
        self.assertEqual(3, sum(call[:3] == ['fn', 'application', 'get']
                                for call in self.state['calls']))

    def test_updating_application_is_polled_until_active(self):
        self.state['delayed_update'] = True
        self.save()
        result = self.run_shell('sleep() { :; }; export -f sleep; exec bash "$@"',
                                existing.SCRIPTS / 'setup-application.sh')
        self.assertIn('Verified application JSON log line format.', result.stderr)
        self.assertEqual('ACTIVE', self.state['applications'][0]['lifecycle-state'])
        self.assertEqual(3, sum(call[:3] == ['fn', 'application', 'get']
                                for call in self.state['calls']))

    def test_invalid_arguments_prevent_oci_calls(self):
        self.script('setup-application.sh', '--unknown', success=False)
        self.assertNotIn('calls', self.state)


if __name__ == '__main__':
    unittest.main()
