import argparse
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('benchmark', Path(__file__).parents[1] / 'runtime-benchmark.py')
benchmark = importlib.util.module_from_spec(spec)
spec.loader.exec_module(benchmark)


def campaign(rounds=3):
    lines = ['META\tkernel\t6.1-fixture']
    for variant in benchmark.VARIANTS:
        for task in benchmark.TASKS:
            for iteration in range(rounds + 1):
                elapsed = 999_000_000 if iteration == 0 else iteration * 1_000_000
                lines.append(f'SAMPLE\t{variant}\t{task}\t{iteration}\t{elapsed}\t0')
    return '\n'.join(lines)


class RuntimeBenchmarkTest(unittest.TestCase):
    def test_summary_excludes_warmup_and_keeps_raw_samples(self):
        report = benchmark.parse_report(campaign(), 3)
        self.assertEqual(40, len(report['samples']))
        self.assertEqual('6.1-fixture', report['metadata']['kernel'])
        for group in report['summary']:
            self.assertTrue(group['valid'])
            self.assertEqual(2, group['median_ms'])
            self.assertEqual(1, group['min_ms'])
            self.assertEqual(3, group['max_ms'])

    def test_any_failure_invalidates_group_including_warmup(self):
        for iteration in (0, 2):
            with self.subTest(iteration=iteration):
                lines = campaign().splitlines()
                index = next(i for i, line in enumerate(lines)
                             if line.startswith(f'SAMPLE\tdisabled-cache\tinventory\t{iteration}\t'))
                lines[index] = lines[index][:-1] + '137'
                report = benchmark.parse_report('\n'.join(lines), 3)
                invalid = [s for s in report['summary'] if not s['valid']]
                self.assertEqual(1, len(invalid))
                self.assertIsNone(invalid[0]['median_ms'])

    def test_partial_duplicate_unknown_and_bad_clock_are_rejected(self):
        raw = campaign()
        cases = [raw.rsplit('\n', 1)[0], raw + '\n' + raw.splitlines()[-1],
                 raw.replace('host', 'unknown'), raw.replace('1000000', '0'),
                 raw + '\narbitrary shell output']
        for case in cases:
            with self.subTest(case=case[-60:]), self.assertRaises(ValueError):
                benchmark.parse_report(case, 3)

    def test_identifiers_cannot_escape_or_inject_shell(self):
        for value in ('../ubuntu', 'x/y', 'x;id', 'x\ny', '..', '-x'):
            with self.subTest(value=value), self.assertRaises(argparse.ArgumentTypeError):
                benchmark.checked_identifier(value)
        self.assertEqual('top.tianyan.app.debug', benchmark.checked_identifier('top.tianyan.app.debug'))

    def test_shell_values_are_quoted_and_no_global_tuning_is_written(self):
        script = benchmark.script("/data/app/a'b/lib/arm64", 'ubuntu', 64, 3)
        self.assertIn("native='/data/app/a'\"'\"'b/lib/arm64'", script)
        self.assertIn('PROOT_NO_SECCOMP=1', script)
        self.assertIn('env "$@" "$proot"', script)
        self.assertIn('trap cleanup EXIT', script)


if __name__ == '__main__':
    unittest.main()
