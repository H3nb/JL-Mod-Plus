# SPDX-License-Identifier: Apache-2.0
"""Small shared CI profile contract. Inputs are data, never Gradle tasks or shell code."""
import re

PROFILES = ('validate', 'unit', 'instrumentation', 'screenshots', 'update-screenshots',
            'runtime-smoke', 'release-check', 'host-native')
CLASS = r'[^\W\d][\w$]*(?:\.[^\W\d][\w$]*)+'
UNIT = re.compile(r'[\w$*]+(?:\.[\w$*]+)+(?:[\w$* .\[\](),=\-]*)', re.UNICODE)
INSTRUMENTATION = re.compile(CLASS + r'(?:#[\w$][\w$ \[\]()=\-]*)?', re.UNICODE)


def validate_selector(profile, selector):
    if profile not in PROFILES:
        raise ValueError('Unknown CI profile')
    if not isinstance(selector, str) or len(selector) > 300 or any(ord(ch) < 32 for ch in selector):
        raise ValueError('Selector must be at most 300 printable characters')
    if profile not in ('unit', 'instrumentation'):
        if selector:
            raise ValueError('Only unit/instrumentation profiles accept a test selector')
        return selector
    pattern = UNIT if profile == 'unit' else INSTRUMENTATION
    if not selector or '$(' in selector or selector != selector.strip() or not pattern.fullmatch(selector):
        raise ValueError('Use one qualified test class or method selector; flags, paths and tasks are not accepted')
    return selector
