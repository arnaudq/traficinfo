import contextlib
import csv
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('scan', Path(__file__).resolve().parents[1] / 'scan.py')
scan = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(scan)
ARTIFACTS = Path(__file__).resolve().parents[3] / 'target/mmm-archive-analysis/tests'


class ArchiveScanTest(unittest.TestCase):
    def setUp(self):
        ARTIFACTS.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(dir=ARTIFACTS)
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def archive(self, day, size, speed=True, extra_year=False):
        name = f'MMM_MMM_Comptage_202601{day:02}.csv'
        path = self.root / name
        with path.open('w', newline='') as file:
            writer = csv.DictWriter(file, fieldnames=['annee', 'code_centrale', 'code_canal', *scan.FIELDS])
            writer.writeheader()
            for key in range(size):
                writer.writerow({'annee': 2022, 'code_centrale': str(key), 'code_canal': 'Z',
                    'mja_jo': '100', 'v85ma_tcj': '40' if speed else '', 'vtma_tcj': '30',
                    'hp_johvs_matin': '12 (07h00 - 08h00)', 'hp_johvs_soir': '0'})
            if extra_year:
                writer.writerow({'annee': 2021, 'code_centrale': 'old', 'code_canal': 'Z', 'mja_jo': '100'})
        return {'name': name, 'date': f'202601{day:02}', 'path': str(path),
                'companion': name.replace('.csv', '.json')}

    def test_coverage_excludes_old_years_and_empty_or_zero_values(self):
        archive = self.archive(1, 3, extra_year=True)
        summary = scan.summarize(Path(archive['path']), 2022, 2026)
        self.assertEqual(set(summary['years']), {'2022'})
        self.assertEqual(scan.coverage(summary, 2022), {
            'records': 3, 'mja_jo': 3, 'v85ma_tcj': 3, 'vtma_tcj': 3,
            'hp_johvs_matin': 3, 'hp_johvs_soir': 0})

    def test_bisection_finds_adjacent_loss_and_reuses_cache(self):
        archives = [self.archive(day, 100, speed=day < 6) for day in range(1, 11)]
        scanner = scan.Scanner(archives, self.root / 'output', 2022, 2026, .1, 50)
        with contextlib.redirect_stdout(io.StringIO()):
            report = scanner.run()
        events = [e for e in report['collapses'] if e['metric'] == 'v85ma_tcj']
        self.assertEqual(len(events), 1)
        self.assertEqual(events[0]['before'], archives[4]['name'])
        self.assertEqual(events[0]['after'], archives[5]['name'])
        self.assertEqual(len(events[0]['lostKeys']), 100)
        self.assertLess(report['archivesInspected'], 10)
        self.assertEqual(report['recommendations'][0]['selected']['csv'], archives[4]['name'])
        retry = scan.Scanner(archives, self.root / 'output', 2022, 2026, .1, 50)
        with contextlib.redirect_stdout(io.StringIO()):
            repeated = retry.run()
        self.assertEqual(repeated['summaryCacheHits'], repeated['archivesInspected'])

    def test_sparse_sampling_limit_and_exhaustive_recovery(self):
        archives = [self.archive(day, 100, speed=day != 5) for day in range(1, 11)]
        with contextlib.redirect_stdout(io.StringIO()):
            sparse = scan.Scanner(archives, self.root / 'sparse', 2022, 2022, .1, 50).run()
            full = scan.Scanner(archives, self.root / 'full', 2022, 2022, .1, 50).run(True)
        self.assertFalse(sparse['collapses'])
        self.assertEqual(len(full['collapses']), 1)
        self.assertEqual(full['archivesInspected'], 10)

    def test_missing_companion_is_ineligible(self):
        archives = [self.archive(1, 100), self.archive(2, 200)]
        archives[1]['companion'] = None
        with contextlib.redirect_stdout(io.StringIO()):
            report = scan.Scanner(archives, self.root / 'output', 2022, 2022, .1, 50).run()
        self.assertEqual(report['yearSets'][0]['csv'], archives[0]['name'])
        self.assertEqual(report['recommendations'][0]['missingFromSelectedVsInspectedUnion']['records'], 100)

    def test_gzip_and_duplicate_keys(self):
        archive = self.archive(1, 2)
        path = Path(archive['path'])
        lines = path.read_text().splitlines()
        raw = ('\n'.join(lines + [lines[1]]) + '\n').encode()
        path.write_bytes(scan.gzip.compress(raw))
        summary = scan.summarize(path, 2022, 2022)
        self.assertEqual(summary['duplicates'], {'2022': 1})
        self.assertEqual(scan.coverage(summary, 2022)['records'], 2)

    def test_malformed_rows_only_ignored_when_year_is_known_outside_scope(self):
        archive = self.archive(1, 2)
        path = Path(archive['path'])
        original = path.read_text()
        path.write_text(original + '2010,old,Z\n')
        summary = scan.summarize(path, 2022, 2026)
        self.assertEqual(summary['malformedRowsOutsideYearScope'], 1)
        self.assertEqual(scan.coverage(summary, 2022)['records'], 2)
        path.write_text(original + '2022,broken,Z\n')
        with self.assertRaisesRegex(ValueError, 'malformed CSV row'):
            scan.summarize(path, 2022, 2026)

    def test_invalid_month_endpoint_falls_back_to_valid_neighbour(self):
        archives = [self.archive(1, 100), self.archive(2, 100), self.archive(3, 100)]
        Path(archives[-1]['path']).write_text('invalid csv\n')
        with contextlib.redirect_stdout(io.StringIO()):
            report = scan.Scanner(archives, self.root / 'output', 2022, 2022, .1, 50).run()
        self.assertEqual(report['yearSets'][0]['csv'], archives[1]['name'])
        self.assertEqual(report['unreadableArchives'][0]['csv'], archives[-1]['name'])
        self.assertFalse(report['collapses'])

    def test_conflicting_duplicates_are_excluded_but_identical_ones_are_not(self):
        archives = [self.archive(1, 100), self.archive(2, 110)]
        path = Path(archives[1]['path'])
        lines = path.read_text().splitlines()
        path.write_text('\n'.join(lines + [lines[1]]) + '\n')
        with contextlib.redirect_stdout(io.StringIO()):
            report = scan.Scanner(archives, self.root / 'same', 2022, 2022, .1, 50).run()
        self.assertEqual(report['yearSets'][0]['csv'], archives[1]['name'])
        path.write_text('\n'.join(lines + [lines[1].replace(',40,', ',99,')]) + '\n')
        with contextlib.redirect_stdout(io.StringIO()):
            report = scan.Scanner(archives, self.root / 'conflict', 2022, 2022, .1, 50).run()
        self.assertEqual(report['yearSets'][0]['csv'], archives[0]['name'])


if __name__ == '__main__':
    unittest.main()
