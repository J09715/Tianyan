#!/usr/bin/env python3
"""Opt-in, app-uid Android experiment. No model API, root, or production tuning."""
import argparse
import datetime
import json
from pathlib import Path
import re
import shlex
import statistics
import subprocess
import sys

VARIANTS = ('host', 'default-files', 'default-cache', 'disabled-files', 'disabled-cache')
TASKS = ('inventory', 'tmp')
IDENTIFIER = re.compile(r'[a-zA-Z0-9][a-zA-Z0-9_.-]*\Z')


def checked_identifier(value):
    if not IDENTIFIER.fullmatch(value) or '..' in value:
        raise argparse.ArgumentTypeError('Expected a simple package/distribution identifier')
    return value


def script(native, distro, files, rounds):
    values = dict(native=native, distro=distro, files=str(files), rounds=str(rounds))
    preamble = '\n'.join(f'{key}={shlex.quote(value)}' for key, value in values.items())
    return preamble + '\n' + Path(__file__).with_name('runtime-benchmark-device.sh').read_text(encoding='utf-8')


def parse_report(output, rounds):
    metadata, samples, seen = {}, [], set()
    for line in output.splitlines():
        fields = line.split('\t')
        if fields[0] == 'META' and len(fields) == 3:
            metadata[fields[1]] = fields[2]
        elif fields[0] == 'SAMPLE' and len(fields) == 6:
            _, variant, task, iteration, elapsed, status = fields
            iteration, elapsed, status = int(iteration), int(elapsed), int(status)
            key = variant, task, iteration
            if (variant not in VARIANTS or task not in TASKS or not 0 <= iteration <= rounds
                    or elapsed <= 0 or key in seen):
                raise ValueError('Invalid, duplicate, or unsupported clock sample')
            seen.add(key)
            samples.append(dict(variant=variant, task=task, round=iteration,
                                elapsed_ns=elapsed, exit_code=status, warmup=iteration == 0))
        elif line.strip():
            raise ValueError('Unexpected device protocol output')
    if len(samples) != len(VARIANTS) * len(TASKS) * (rounds + 1):
        raise ValueError('Incomplete campaign; no performance conclusion is valid')
    summary = []
    for variant in VARIANTS:
        for task in TASKS:
            group = [s for s in samples if s['variant'] == variant and s['task'] == task]
            valid = all(s['exit_code'] == 0 for s in group)
            durations = [s['elapsed_ns'] / 1e6 for s in group if not s['warmup']]
            summary.append(dict(variant=variant, task=task, valid=valid,
                                failures=sum(s['exit_code'] != 0 for s in group),
                                median_ms=statistics.median(durations) if valid else None,
                                min_ms=min(durations) if valid else None,
                                max_ms=max(durations) if valid else None))
    return dict(metadata=metadata, samples=samples, summary=summary)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial', required=True, help='Explicit connected adb device serial')
    parser.add_argument('--package', type=checked_identifier, default='top.tianyan.app.debug')
    parser.add_argument('--distro', type=checked_identifier, default='ubuntu')
    parser.add_argument('--native-lib-dir', help='Override extracted APK lib/arm64 directory')
    parser.add_argument('--files', type=int, default=1024)
    parser.add_argument('--rounds', type=int, default=7, help='Measured rounds, plus one warm-up')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args(argv)
    if not 64 <= args.files <= 8192 or not 3 <= args.rounds <= 30:
        parser.error('--files must be 64..8192; --rounds must be 3..30')
    if args.output.exists():
        parser.error('Output exists; choose a new report path')
    adb = [args.adb, '-s', args.serial]

    def call(arguments, **kwargs):
        return subprocess.run(adb + arguments, encoding='utf-8', capture_output=True,
                              check=True, **kwargs)

    # Verify debuggable application access before creating anything.
    call(['shell', 'run-as', args.package, 'pwd'], timeout=10)
    native = args.native_lib_dir
    if native is None:
        paths = call(['shell', 'pm', 'path', args.package], timeout=10).stdout.splitlines()
        apk = next((p.removeprefix('package:') for p in paths if p.endswith('/base.apk')), None)
        if apk is None:
            raise ValueError('Cannot locate base.apk; use --native-lib-dir')
        native = apk.rsplit('/', 1)[0] + '/lib/arm64'
    if not native.startswith('/data/app/') or '\n' in native or '\r' in native:
        raise ValueError('Native libraries must be under /data/app/')
    # No parent environment or credential is copied into either measured child.
    command = ' '.join(shlex.quote(s) for s in ['run-as', args.package, '/system/bin/env',
                                               '-i', 'PATH=/system/bin', '/system/bin/sh'])
    result = call(['shell', command], input=script(native, args.distro, args.files, args.rounds),
                  timeout=(args.rounds + 1) * 10 * 22 + 120)
    report = parse_report(result.stdout, args.rounds)
    report.update(schema_version=1, captured_at=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                  package=args.package, distribution=args.distro, files=args.files, rounds=args.rounds,
                  scope='Synthetic end-to-end microbenchmark; no automatic configuration selection')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open('x', encoding='utf-8') as destination:
        json.dump(report, destination, ensure_ascii=False, indent=2)
        destination.write('\n')
    for group in report['summary']:
        value = f"{group['median_ms']:.3f} ms" if group['valid'] else f"INVALID ({group['failures']} failures)"
        print(f"{group['variant']:16} {group['task']:10} {value}")
    print(f'Report: {args.output}')
    return 0 if all(s['valid'] for s in report['summary']) else 1


if __name__ == '__main__':
    try:
        sys.exit(main())
    except subprocess.CalledProcessError as error:
        print(f'Benchmark failed (adb exit {error.returncode}): {error.stderr.strip()[:2000]}', file=sys.stderr)
        sys.exit(2)
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        print(f'Benchmark failed: {error}', file=sys.stderr)
        sys.exit(2)
