# SPDX-License-Identifier: Apache-2.0
"""Require paired AndroidJUnitRunner start/success records for every selector."""
import argparse
from pathlib import Path
import re


def verify(text, selectors):
    if re.search(r'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed|No tests found', text):
        raise ValueError('Instrumentation reported a failure')
    fields, active, finished = {}, set(), {}
    completion = []
    declared_counts = set()
    for line in text.splitlines():
        if line.startswith('INSTRUMENTATION_STATUS: '):
            key, separator, value = line.removeprefix('INSTRUMENTATION_STATUS: ').partition('=')
            if separator:
                fields[key] = value
        elif line.startswith('INSTRUMENTATION_STATUS_CODE: '):
            code = int(line.split(': ', 1)[1])
            if 'numtests' in fields:
                declared_counts.add(int(fields['numtests']))
            identity = (fields.get('class'), fields.get('test'))
            fields = {}
            if not all(identity):
                raise ValueError('Status record has no complete class/method identity')
            if completion:
                raise ValueError('Test status after instrumentation completion')
            if code == 1:
                if identity in active or identity in finished:
                    raise ValueError(f'Duplicate test start: {identity}')
                active.add(identity)
            else:
                if code != 0:
                    raise ValueError(f'Test did not pass (status {code}): {identity}')
                if identity not in active:
                    raise ValueError(f'Test completed without matching start: {identity}')
                active.remove(identity)
                finished[identity] = code
        elif line.startswith('INSTRUMENTATION_CODE: '):
            completion.append(int(line.split(': ', 1)[1]))
    if completion != [-1] or active or fields:
        raise ValueError('Incomplete or invalid instrumentation completion')
    if declared_counts and declared_counts != {len(finished)}:
        raise ValueError('Reported test count does not match completed results')
    if not selectors or len(set(selectors)) != len(selectors):
        raise ValueError('Selectors must be nonempty and unique')
    for selector in selectors:
        classname, separator, method = selector.partition('#')
        matches = [key for key in finished if key[0] == classname and (not separator or key[1] == method)]
        if not matches:
            raise ValueError(f'Selected test did not pass: {selector}')
    return sorted(f'{cls}#{method}' for cls, method in finished)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--log', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('selectors', nargs='+')
    args = parser.parse_args()
    try:
        tests = verify(args.log.read_text(encoding='utf-8'), args.selectors)
    except (ValueError, OSError) as error:
        parser.exit(1, f'Runtime-smoke verification failed: {error}\n')
    # Only append selector PASS evidence after the entire batch has been verified.
    with args.output.open('a', encoding='utf-8') as output:
        output.writelines(f'PASS\t{selector}\n' for selector in args.selectors)
    args.log.with_suffix('.verified.txt').write_text('\n'.join(tests) + '\n', encoding='utf-8')
    print(f'Verified {len(tests)} test results for {len(args.selectors)} selectors.')


if __name__ == '__main__':
    main()
