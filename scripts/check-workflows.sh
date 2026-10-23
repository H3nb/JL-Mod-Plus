#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Linux x86_64 host checks, independent of the Android SDK and Gradle.
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ "$(uname -s)-$(uname -m)" != Linux-x86_64 ]]; then
  echo 'Pinned workflow tooling currently supports Linux x86_64; use that host or CI.' >&2
  exit 1
fi
tools_dir="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/jlmod-workflow-tools-1.7.12-0.11.0"
mkdir -p "$tools_dir"
if [[ ! -x "$tools_dir/actionlint" ]]; then
  curl --fail --silent --show-error --location \
    https://github.com/rhysd/actionlint/releases/download/v1.7.12/actionlint_1.7.12_linux_amd64.tar.gz \
    --output "$tools_dir/actionlint.tar.gz"
  printf '%s  %s\n' '8aca8db96f1b94770f1b0d72b6dddcb1ebb8123cb3712530b08cc387b349a3d8' "$tools_dir/actionlint.tar.gz" | sha256sum --check --status
  tar -xzf "$tools_dir/actionlint.tar.gz" -C "$tools_dir" actionlint
fi
if [[ ! -x "$tools_dir/shellcheck" ]]; then
  curl --fail --silent --show-error --location \
    https://github.com/koalaman/shellcheck/releases/download/v0.11.0/shellcheck-v0.11.0.linux.x86_64.tar.xz \
    --output "$tools_dir/shellcheck.tar.xz"
  printf '%s  %s\n' '8c3be12b05d5c177a04c29e3c78ce89ac86f1595681cab149b65b97c4e227198' "$tools_dir/shellcheck.tar.xz" | sha256sum --check --status
  tar -xJf "$tools_dir/shellcheck.tar.xz" -C "$tools_dir" --strip-components=1 shellcheck-v0.11.0/shellcheck
fi
export PATH="$tools_dir:$PATH"
actionlint -version
shellcheck --version
# actionlint 1.7.12 predates concurrency.queue. policy.test.mjs validates
# precisely this field until the pinned parser supports it.
actionlint -ignore 'unexpected key "queue" for "concurrency" section'
shellcheck scripts/*.sh
for script in scripts/*.sh; do bash -n "$script"; done
npm ci --prefix scripts/workflow-policy --ignore-scripts --no-audit --no-fund
npm test --prefix scripts/workflow-policy
python3 -B -m unittest discover -s scripts/tests -p 'test_*.py' -v
