# SPDX-License-Identifier: Apache-2.0
"""Bounded run identity used before test artifacts exist; mirrored by Android run-name."""
import hashlib
import re

RUN_TITLE = re.compile(
    r'PR (?P<pr_number>[1-9][0-9]*|-) \| (?P<mode>[a-z-]+) \| '
    r'(?P<source_sha>[0-9a-f]{40}) \| test (?P<selector_key>[0-9a-f]{16}|-) \| '
    r'request (?P<request_id>[A-Za-z0-9_-]{1,80})')


def selector_key(selector):
    return hashlib.sha256(selector.encode('utf-8')).hexdigest()[:16] if selector else '-'


def run_title(inputs):
    return (f"PR {inputs['pr_number']} | {inputs['mode']} | {inputs['source_sha']} | "
            f"test {inputs.get('selector_key') or '-'} | request {inputs['request_id']}")


def parse_run_title(title):
    match = RUN_TITLE.fullmatch(title)
    return match.groupdict() if match else None
