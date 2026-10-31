#!/usr/bin/env python3
"""Sample MMM archives, bisect coverage losses, and recommend yearly inputs."""
import argparse
import csv
import datetime as dt
import gzip
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys

FIELDS = ('mja_jo', 'v85ma_tcj', 'vtma_tcj', 'hp_johvs_matin', 'hp_johvs_soir')
METRICS = ('records',) + FIELDS
VERSION = 2


def save_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + '.tmp')
    temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n')
    temporary.replace(path)


def oci(*args):
    return subprocess.run(['oci', *args], check=True, capture_output=True, text=True).stdout


def populated(value):
    """A positive numeric value, including peak-hour values followed by an interval."""
    match = re.match(r'^\s*([+-]?\d+(?:[.,]\d+)?)', value)
    return bool(match and float(match[1].replace(',', '.')) > 0)


def summarize(path, min_year, max_year):
    raw = path.read_bytes()
    if raw[:2] == b'\x1f\x8b':
        raw = gzip.decompress(raw)
    reader = csv.DictReader(io.StringIO(raw.decode('utf-8-sig'), newline=''))
    required = {'annee', 'code_centrale', 'code_canal', *FIELDS}
    missing = required - set(reader.fieldnames or [])
    if missing:
        raise ValueError(f'{path.name}: missing columns {sorted(missing)}')
    years, duplicates, conflicts = {}, {}, {}
    malformed_outside_scope = 0
    for row in reader:
        malformed = None in row or any(value is None for value in row.values())
        try:
            year = int(row['annee'])
        except (TypeError, ValueError):
            raise ValueError(f'missing or invalid year at CSV line {reader.line_num}') from None
        if not min_year <= year <= max_year:
            malformed_outside_scope += int(malformed)
            continue
        if malformed:
            raise ValueError(f'incomplete or malformed CSV row at line {reader.line_num}')
        key = json.dumps([row['code_centrale'].strip(), row['code_canal'].strip()], ensure_ascii=False)
        if not row['code_centrale'].strip():
            raise ValueError(f'{path.name}: missing station identifier')
        records = years.setdefault(str(year), {})
        values = [row[field].strip() for field in FIELDS]
        if key in records:
            duplicates[str(year)] = duplicates.get(str(year), 0) + 1
            if records[key] != values:
                conflicts[str(year)] = conflicts.get(str(year), 0) + 1
            # Match ComptageDiffer: retain the first duplicate, report ambiguity.
            continue
        records[key] = values
    return {'years': years, 'duplicates': duplicates, 'conflictingDuplicateKeys': conflicts,
            'malformedRowsOutsideYearScope': malformed_outside_scope}


def presence(records):
    return {'records': set(records), **{
        field: {key for key, values in records.items() if populated(values[index])}
        for index, field in enumerate(FIELDS)}}


def coverage(summary, year):
    return {metric: len(keys) for metric, keys in presence(summary['years'].get(str(year), {})).items()}


def loss(before, after, relative, absolute):
    # Require both thresholds: avoid treating one missing value as a collapse at low coverage.
    return before > after and before - after >= absolute and (before - after) / before >= relative


def monthly_probes(archives):
    selected = {0, len(archives) - 1}
    months = {}
    for index, archive in enumerate(archives):
        months[archive['date'][:6]] = index
    return selected | set(months.values())


class Scanner:
    def __init__(self, archives, output, min_year, max_year, relative, absolute, namespace=None, bucket=None):
        self.archives, self.output = archives, output
        self.min_year, self.max_year = min_year, max_year
        self.relative, self.absolute = relative, absolute
        self.namespace, self.bucket = namespace, bucket
        self.summaries = {}
        self.downloads = self.cache_hits = 0
        self.events = []

    def read(self, index):
        if index in self.summaries:
            return self.summaries[index]
        archive = self.archives[index]
        identity = {'version': VERSION, 'minYear': self.min_year, 'maxYear': self.max_year,
                    'namespace': self.namespace, 'bucket': self.bucket, 'archive': archive}
        cache_key = hashlib.sha256(json.dumps(identity, sort_keys=True).encode()).hexdigest()
        cache = self.output / 'summaries' / (cache_key + '.json')
        if cache.exists():
            result = json.loads(cache.read_text())
            self.cache_hits += 1
        else:
            if 'path' in archive:
                path = Path(archive['path'])
            else:
                download_identity = {'namespace': self.namespace, 'bucket': self.bucket, 'archive': archive}
                download_key = hashlib.sha256(json.dumps(download_identity, sort_keys=True).encode()).hexdigest()
                path = self.output / 'downloads' / (download_key + '.csv')
                path.parent.mkdir(parents=True, exist_ok=True)
                if not path.exists():
                    temporary = path.with_suffix('.part')
                    oci('os', 'object', 'get', '--namespace-name', self.namespace, '--bucket-name', self.bucket,
                        '--name', archive['name'], '--file', str(temporary))
                    temporary.replace(path)
                    self.downloads += 1
            try:
                result = summarize(path, self.min_year, self.max_year)
            except (ValueError, UnicodeError, csv.Error, EOFError) as error:
                result = {'years': {}, 'duplicates': {}, 'invalid': str(error)}
            save_json(cache, result)
        self.summaries[index] = result
        print(f"[{len(self.summaries)}/{len(self.archives)} inspected] {archive['name']}", flush=True)
        return result

    def drops(self, left, right):
        before, after = self.read(left), self.read(right)
        if 'invalid' in before or 'invalid' in after:
            return []
        drops = []
        for year in range(self.min_year, self.max_year + 1):
            old, new = coverage(before, year), coverage(after, year)
            drops.extend((year, metric) for metric in METRICS
                         if loss(old[metric], new[metric], self.relative, self.absolute))
        return drops

    def refine(self, left, right):
        drops = self.drops(left, right)
        if not drops:
            return
        valid_middle = None
        if right - left > 1:
            middle = (left + right) // 2
            for index in sorted(range(left + 1, right), key=lambda i: abs(i - middle)):
                if 'invalid' not in self.read(index):
                    valid_middle = index
                    break
        if valid_middle is None:
            before, after = self.read(left), self.read(right)
            for year, metric in drops:
                old = presence(before['years'].get(str(year), {}))[metric]
                new = presence(after['years'].get(str(year), {}))[metric]
                self.events.append({'year': year, 'metric': metric,
                    'before': self.archives[left]['name'], 'after': self.archives[right]['name'],
                    'beforeCount': len(old), 'afterCount': len(new),
                    'unreadableArchivesBetween': right - left - 1,
                    'lostKeys': sorted(old - new), 'gainedKeys': sorted(new - old)})
            return
        middle = valid_middle
        self.refine(left, middle)
        self.refine(middle, right)
        # A gradual decline can cross the threshold over a long interval without a
        # single daily drop crossing it. Keep that interval visible in the report.
        if not any(e['before'] >= self.archives[left]['name'] and e['after'] <= self.archives[right]['name']
                   and (e['year'], e['metric']) in drops for e in self.events):
            self.gradual.add((left, right))

    def run(self, exhaustive=False):
        self.gradual = set()
        probes = sorted(monthly_probes(self.archives))
        for index in probes:
            self.read(index)
        valid_probes = set()
        for index in probes:
            if 'invalid' not in self.summaries[index]:
                valid_probes.add(index)
                continue
            # A bad monthly endpoint must not hide all valid files in that month.
            month = self.archives[index]['date'][:6]
            neighbours = [i for i, archive in enumerate(self.archives) if archive['date'][:6] == month]
            for neighbour in sorted(neighbours, key=lambda i: abs(i - index)):
                if 'invalid' not in self.read(neighbour):
                    valid_probes.add(neighbour)
                    break
        probes = sorted(valid_probes)
        if exhaustive:
            for index in range(len(self.archives)):
                self.read(index)
            valid = [i for i in range(len(self.archives)) if 'invalid' not in self.summaries[i]]
            for left, right in zip(valid, valid[1:]):
                self.refine(left, right)
        else:
            for left, right in zip(probes, probes[1:]):
                self.refine(left, right)
        return self.report(exhaustive)

    def report(self, exhaustive):
        rows, recommendations = [], []
        for year in range(self.min_year, self.max_year + 1):
            candidates = []
            for index, summary in sorted(self.summaries.items()):
                counts = coverage(summary, year)
                if not counts['records']:
                    continue
                archive = self.archives[index]
                candidate = {'year': year, 'csv': archive['name'], 'json': archive['companion'],
                    **counts, 'populatedValues': sum(counts[f] for f in FIELDS),
                    'duplicateKeys': summary['duplicates'].get(str(year), 0),
                    'conflictingDuplicateKeys': summary.get('conflictingDuplicateKeys', {}).get(str(year), 0),
                    'malformedRowsOutsideYearScope': summary.get('malformedRowsOutsideYearScope', 0)}
                rows.append(candidate)
                if archive['companion'] and not candidate['conflictingDuplicateKeys']:
                    candidates.append(candidate)
            if not candidates:
                recommendations.append({'year': year, 'status': 'no unambiguous CSV/JSON candidate'})
                continue
            best = max(candidates, key=lambda r: (r['populatedValues'], r['records'], r['csv']))
            # Keep alternatives whose metric totals are not dominated by another file.
            frontier = [c for c in candidates if not any(
                all(other[m] >= c[m] for m in METRICS) and any(other[m] > c[m] for m in METRICS)
                for other in candidates)]
            best_records = self.summaries[next(i for i in self.summaries if self.archives[i]['name'] == best['csv'])]['years'][str(year)]
            best_presence = presence(best_records)
            union = {metric: set() for metric in METRICS}
            for i, summary in self.summaries.items():
                for metric, keys in presence(summary['years'].get(str(year), {})).items():
                    union[metric].update(keys)
            recommendations.append({'year': year, 'status': 'best among inspected eligible pairs',
                'selected': best, 'metricMaxima': {m: max(c[m] for c in candidates) for m in METRICS},
                'missingFromSelectedVsInspectedUnion': {m: len(union[m] - best_presence[m]) for m in METRICS},
                'alternatives': [c for c in frontier if c['csv'] != best['csv']]})
        groups = []
        for recommendation in recommendations:
            if 'selected' not in recommendation:
                continue
            selected = recommendation['selected']
            if groups and groups[-1]['csv'] == selected['csv'] and groups[-1]['years'][-1] == selected['year'] - 1:
                groups[-1]['years'].append(selected['year'])
            else:
                groups.append({'csv': selected['csv'], 'json': selected['json'], 'years': [selected['year']]})
        report = {'measurementYears': [self.min_year, self.max_year], 'archivesAvailable': len(self.archives),
            'archivesInspected': len(self.summaries), 'downloadedThisRun': self.downloads, 'summaryCacheHits': self.cache_hits,
            'mode': 'exhaustive' if exhaustive else 'monthly sampling with adaptive loss bisection',
            'ranking': 'Most populated values across the five fields; ties: most records, then latest archive. No merging.',
            'thresholds': {'relativeDrop': self.relative, 'absoluteDrop': self.absolute, 'combination': 'both required'},
            'limitations': ['Sparse sampling can miss temporary collapses, recoveries and maxima between probes.',
                'Coverage measures availability, not correctness of populated values.',
                'Companion existence is checked; geometry identifier coverage is not validated.'],
            'collapses': self.events,
            'unreadableArchives': [{'csv': self.archives[i]['name'], 'error': summary['invalid']}
                                   for i, summary in sorted(self.summaries.items()) if 'invalid' in summary],
            'sourceWarnings': [{'csv': self.archives[i]['name'],
                                'malformedRowsOutsideYearScope': summary['malformedRowsOutsideYearScope']}
                               for i, summary in sorted(self.summaries.items())
                               if summary.get('malformedRowsOutsideYearScope')],
            'gradualDeclineIntervals': [{'before': self.archives[a]['name'], 'after': self.archives[b]['name']}
                                       for a, b in sorted(self.gradual)],
            'recommendations': recommendations, 'yearSets': groups}
        save_json(self.output / 'report.json', report)
        if rows:
            with (self.output / 'coverage.csv').open('w', newline='') as file:
                writer = csv.DictWriter(file, fieldnames=list(rows[0]))
                writer.writeheader()
                writer.writerows(rows)
        lines = ['# MMM archive selection', '',
            f"Inspected {len(self.summaries)} of {len(self.archives)} archives; measurement years {self.min_year}–{self.max_year}.", '',
            report['ranking'], '',
            '| Years | CSV | Companion JSON |', '| --- | --- | --- |']
        for group in groups:
            years = str(group['years'][0]) if len(group['years']) == 1 else f"{group['years'][0]}–{group['years'][-1]}"
            lines.append(f"| {years} | {group['csv']} | {group['json']} |")
        lines += ['', '## Selected coverage', '',
                  '| Year | Records | Traffic | V85 | Mean speed | Morning peak | Evening peak | Total populated values |',
                  '| --- | --- | --- | --- | --- | --- | --- | --- |']
        for recommendation in recommendations:
            if 'selected' not in recommendation:
                lines.append(f"| {recommendation['year']} | No eligible candidate | | | | | | |")
                continue
            row = recommendation['selected']
            lines.append('| ' + ' | '.join(str(row[k]) for k in ('year', *METRICS, 'populatedValues')) + ' |')
        lines += ['', '## Detected collapses', '', '| Year | Metric | Last archive before loss | First archive after loss | Coverage |',
                  '| --- | --- | --- | --- | --- |']
        for event in self.events:
            lines.append(f"| {event['year']} | {event['metric']} | {event['before']} | {event['after']} | {event['beforeCount']} → {event['afterCount']} |")
        lines += ['', '## Limits and trade-offs', '', *['- ' + item for item in report['limitations']],
                  '- Selection is preliminary unless every archive was inspected. See report.json for per-field alternatives, duplicate keys, missing union coverage and gradual-decline intervals.',
                  '- These reports do not modify dsconfig.json or publish datasets.', '']
        for bad in report['unreadableArchives']:
            lines.append(f"- Excluded unreadable archive: {bad['csv']}: {bad['error']}")
        for warning in report['sourceWarnings']:
            lines.append(f"- {warning['csv']}: {warning['malformedRowsOutsideYearScope']} malformed row(s) "
                         'outside the requested year scope were ignored; review before import.')
        for recommendation in recommendations:
            missing = recommendation.get('missingFromSelectedVsInspectedUnion', {})
            if any(missing.values()):
                lines.append(f"- {recommendation['year']}: selected file lacks values/records seen in other inspected files: "
                             + ', '.join(f'{key}={count}' for key, count in missing.items() if count))
        (self.output / 'report.md').write_text('\n'.join(lines).rstrip() + '\n')
        return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--local-dir', type=Path, help='Read local snapshots instead of OCI')
    parser.add_argument('--output-dir', type=Path)
    parser.add_argument('--min-year', type=int, default=2022)
    parser.add_argument('--max-year', type=int, default=dt.date.today().year)
    parser.add_argument('--relative-drop', type=float, default=.10)
    parser.add_argument('--absolute-drop', type=int, default=50)
    parser.add_argument('--exhaustive', action='store_true', help='Inspect every archive; retain the same cache')
    args = parser.parse_args()
    if args.min_year > args.max_year or not 0 < args.relative_drop <= 1 or args.absolute_drop < 1:
        parser.error('Invalid year range or drop thresholds')
    root = Path(os.environ.get('TRAFICINFO_ROOT', Path(__file__).resolve().parents[2]))
    output = (args.output_dir or root / 'target/mmm-archive-analysis').resolve()
    output.mkdir(parents=True, exist_ok=True)
    config = json.loads(Path(os.environ.get('TRAFICINFO_DSCONFIG', root / 'dsconfig.json')).read_text())['MMM']
    stem = config['csvObject'].removesuffix('.csv')
    pattern = re.compile(re.escape(stem) + r'_(\d{8})\.csv$')
    namespace = bucket = None
    if args.local_dir:
        files = list(args.local_dir.resolve().iterdir())
        names = {p.name for p in files}
        objects = [{'name': p.name, 'path': str(p), 'size': p.stat().st_size, 'modifiedNs': p.stat().st_mtime_ns}
                   for p in files if p.is_file()]
    else:
        namespace = os.environ['OCI_OBJECT_STORAGE_NAMESPACE']
        bucket = config['inputBucket']
        inventory = json.loads(oci('os', 'object', 'list', '--namespace-name', namespace,
            '--bucket-name', bucket, '--prefix', stem, '--all'))
        save_json(output / 'inventory.json', inventory)
        objects = [{k: obj.get(k) for k in ('name', 'size', 'md5', 'time-modified')} for obj in inventory['data']]
        names = {obj['name'] for obj in objects}
    archives = []
    for obj in objects:
        match = pattern.fullmatch(obj['name'])
        if not match:
            continue
        date = dt.datetime.strptime(match[1], '%Y%m%d').date()
        if date.year < args.min_year or date > dt.date.today():
            continue
        companion = obj['name'].removesuffix('.csv') + '.json'
        archives.append({**obj, 'date': match[1], 'companion': companion if companion in names else None})
    archives.sort(key=lambda obj: obj['date'])
    save_json(output / 'archives.json', archives)
    if not archives:
        parser.error('No dated MMM CSV archives found')
    scan = Scanner(archives, output, args.min_year, args.max_year, args.relative_drop, args.absolute_drop, namespace, bucket)
    report = scan.run(args.exhaustive)
    print(f"Report: {output / 'report.md'}")
    print(json.dumps(report['yearSets'], indent=2))


if __name__ == '__main__':
    main()
