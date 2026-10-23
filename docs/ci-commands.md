# PR CI commands and recovery

Android CI supports automatic PR validation, manual GitHub Actions dispatch, and
local commands. A GitHub connector can request the same profiles with a deliberate
PR Conversation comment once the controller is available on the default branch.
Running tests, generating candidates, accepting reviewed references, merging,
and publishing are separate operations with separate authority.

## Profiles and selectors

Copy one command line into its own top-level PR Conversation comment. Do not
include explanatory text, a Markdown quote, a code fence, or additional lines in
that comment. Edited/deleted comments do not trigger the `created` listener.

| Comment | Coverage |
| --- | --- |
| `/jlmod-ci validate` | Existing parallel APK assembly, app/dexlib JVM tests, instrumentation compilation, screenshot comparison and lint; compilation does not execute instrumentation tests |
| `/jlmod-ci unit --test 'package.NewRegressionTest'` | One app JVM class or Gradle test filter, with only its test task forced to execute |
| `/jlmod-ci unit --test 'package.NewRegressionTest.regressionCase'` | One app JVM method filter |
| `/jlmod-ci instrumentation --test 'package.NewRegressionTest#regressionCase'` | One AndroidJUnitRunner class/method selector on API 35 x86_64 |
| `/jlmod-ci screenshots` | Screenshot comparison; does not accept or update references |
| `/jlmod-ci update-screenshots` | Generate and package changed screenshot candidates for review; does not accept them |
| `/jlmod-ci runtime-smoke` | Existing selected runtime contracts on API 35 x86_64; does not run the whole instrumentation suite |
| `/jlmod-ci release-check` | Unsigned release packaging and startup qualification on a 16 KB emulator; does not publish |

`unit` and `instrumentation` require exactly one `--test` selector. A newly added
class/method needs no CI class allowlist. Unit filters use Gradle `--tests`
class/method patterns; instrumentation uses `package.Class` or
`package.Class#method`. Selectors are limited to 300 printable characters and the
repo's supported test-name syntax. Arbitrary tasks, flags, paths, environment
assignments and additional selectors are rejected. Single/double quotes preserve
supported spaces in names. The shared contract is `scripts/ci_profiles.py`.

The controller reads the current PR HEAD automatically. An optional
`--sha <40-lowercase-hex-SHA>` requests that exact HEAD; it fails as stale if HEAD
has changed. Routine requests do not require copying a SHA manually. Bodies are
limited to 700 characters, and no natural-language command interpretation occurs.
A comment beginning with the command prefix but containing invalid arguments can
start the small controller and be rejected; it never dispatches a test build.

## Who may request tests

The repository owner is authorized by the event's numeric owner ID. Additional
users/apps require an explicit numeric ID token in the repository Actions variable
`JLMOD_CI_ACTOR_IDS`, for example `|42|99|`. Empty/unset values authorize nobody
extra. Complete `|ID|` tokens are required; logins, wildcard roles and generic bot
status grant no access. Configure only identities deliberately trusted to execute
same-repository PR code. Valid authorized requests need no per-run approval.

For connector onboarding, inspect the authenticated profile and the actual comment
sender identity once, then configure its observed numeric ID if necessary. A
connector may comment as a separate app/bot; verify its sender ID rather than
guessing it or assuming it matches the authenticated user. The ordinary test allowlist grants no screenshot
acceptance, merge, or publication authority.

Both comment workflows check the PR object and authorization in `jobs.<id>.if`
before runner allocation. Every issue comment, including an owner comment with a
valid command, executes zero handler jobs and allocates zero runners. Outsider PR
comments, ordinary comments and quoted/code-block examples also execute zero
controller jobs. GitHub may still record a workflow whose jobs are skipped; this
is different from a guarantee that no event/run record exists. No negative event
receives an automatic reply from this mechanism.

## Source and token boundaries

The controller loads trusted tooling from the repository default branch. It
requires an open same-repository PR, resolves HEAD/base through GitHub, and
dispatches `android.yml` on the default branch with explicit `source_sha`,
PR number and request ID. The test workflow checks out the requested
source and verifies its revision and current PR HEAD before Android setup/build.
A moving HEAD produces a stale failure; it does not silently test another revision.
Automatic PR validation retains its synthetic merge source and records that
separately from PR HEAD. Forks retain the existing ordinary PR CI policy and cannot
be submitted as same-repository comment requests.

The controller's write token is used only for metadata, dispatch and the result
reply. It is not passed to PR code. Test jobs have read permissions and do not
receive release signing credentials. Explicit PR-source dispatches make
`setup-gradle` read-only so their cache outputs are not saved into the workflow
default-branch scope. This is an authorized same-repository code-execution path;
the actor allowlist is not an isolation boundary for arbitrary untrusted code.
Dispatching a workflow directly on a PR
branch runs that branch's workflow definition, so a SHA check alone cannot make
such a definition trusted. Prefer the default-branch definition plus explicit PR
source inputs; maintainers testing workflow changes must review that definition
before using the branch's manual dispatch route.

## GitHub CLI, UI and local equivalents

The Actions **Run workflow** UI and `gh workflow run` remain supported. To use the
trusted default-branch definition, select `alpha` (recheck the actual default
branch), provide the PR number, set `source_sha` to current PR HEAD, and choose a unique `request_id`. `unit`/`instrumentation` also take
`test_selector`; `selector_key` is optional (the controller supplies its SHA-256
fingerprint for deduplication). A manual branch-only run remains possible for profiles that do
not require a PR; its checked-out revision is recorded and it does not claim to be
a PR-head request.

For example, after replacing the PR number and test class:

```bash
PR_NUMBER=123
SOURCE_SHA=$(gh pr view "$PR_NUMBER" --repo H3nb/JL-Mod-Plus --json headRefOid --jq .headRefOid)
REQUEST_ID="manual-$(date -u +%Y%m%dT%H%M%SZ)"
gh workflow run android.yml --repo H3nb/JL-Mod-Plus --ref alpha \
  -f mode=unit -f pr_number="$PR_NUMBER" -f source_sha="$SOURCE_SHA" \
  -f request_id="$REQUEST_ID" \
  -f test_selector='package.NewRegressionTest'
```

The controller pins REST API version `2026-03-10`: workflow dispatch returns HTTP
200 with `workflow_run_id`, `run_url` and `html_url`. It does not use the removed
`return_run_details` parameter. For a direct API request, write the dispatch
`ref`/`inputs` object to a JSON file and use
`gh api --method POST -H 'X-GitHub-Api-Version: 2026-03-10' repos/H3nb/JL-Mod-Plus/actions/workflows/android.yml/dispatches --input request.json`.
Use the returned `html_url`; never assume the newest run belongs to your request.

Focused local execution uses the same argument builder and result helper as CI:

```bash
python3 scripts/ci-profile.py unit --test 'package.NewRegressionTest'
python3 scripts/ci-profile.py instrumentation --test 'package.NewRegressionTest#regressionCase'
```

Use `--dry-run` to inspect the argument array. Instrumentation requires a connected
x86_64 Android emulator/device and the configured SDK/NDK. CI provisions API 35;
a local device's API is your responsibility to record. The helper removes only
the selected profile's old result directory and forces only its test task with
Gradle `--rerun`; dependencies may reuse cache. A failed Gradle command, unmatched
selector, missing/empty/stale reports or all-skipped suite cannot become PASS.
Handled SIGINT/SIGTERM records cancellation in the same result JSON. Hard runner
termination may prevent finalization; use the GitHub job status/logs when no report
can be written. JUnit 4 and AndroidJUnitRunner remain in place.

Workflow/helper changes can be checked before Android setup with
`bash scripts/check-workflows.sh` on Linux x86_64 with Python, Node/npm and network
access for the pinned tools. This runs actionlint, ShellCheck, shell syntax checks,
actual-YAML expression policy fixtures and Python helper regressions.

Other profiles retain their existing local tasks/scripts. Use the build and test
commands in [Build and validation](development.md#select-the-relevant-check) for full
validation and dexlib tests. Screenshot comparison and candidate generation use
respectively `:app:testEmulatorDebugScreenshotTestDefaultTestSuite` and
`:app:updateEmulatorDebugScreenshotTestDefaultTestSuite` (screenshots may use
`-PjlmodNativeBuild=false`). Run `bash scripts/runtime-smoke.sh` with a suitable
connected emulator for runtime smoke. Release qualification uses the workflow's
release assembly/staging steps followed by `bash scripts/release-smoke.sh`; the
latter expects staged APKs and a 16 KB emulator, not an arbitrary debug build.
Do not disable existing isolation for runtime tests that mutate global state.

## Find results and recover a request

A valid comment gets one status reply containing the run URL, profile, source and
request ID. Comment IDs become `comment-<ID>` request IDs. Dispatch titles include
PR, profile, full expected SHA, a 16-hex selector fingerprint (or `-`) and request
ID. The
original selector is preserved in the request reply, job environment and result
metadata; a bounded fingerprint keeps long selectors from truncating run identity.
gh/UI requests should use an explicit ID; otherwise the run itself identifies the request. Inspect that
identity and attempt when reading a run, rather than matching only branch/time.

Request rejection/API diagnosis appears in controller stderr; the run URL and
request status are also printed if its reply cannot be posted. Test names/messages
and lint rule/severity/path/line/message appear in stdout and step summaries.
`ci-results.py` writes schema-v2 JSON and matching Markdown with
built/expected/tree/PR/base revisions, profile/selectors/tasks, request/run/attempt/
job identity, counts and scope. Outcomes distinguish `passed`, `failed`,
`cancelled`, `not-run`, `skipped-by-policy` and `unavailable`. Unknown requested
counts, cache outcomes, timings and artifact metadata remain unknown. Non-XML
operations preserve step outcomes. Runtime counts come from verified completed
instrumentation batches; startup/packaging do not manufacture JUnit test totals. XML-derived
counts alone do not prove fresh task execution; focused runs explicitly force
the selected task. Summaries are bounded; detailed XML/logs remain in diagnostic
artifacts. Artifact metadata may become available only after upload: inspect the
run's artifact endpoint for IDs, digests, URLs and expiry. Artifacts normally
expire after seven days; an expired artifact needs fresh evidence, not a guessed
ID or reused acceptance request. An early APK is labeled unverified and is not a
passing validation verdict.

Controller decisions for one PR are serialized. They recover the exact request before reading current PR HEAD, so its original
revision remains stable after HEAD moves. For new requests they reuse an identical
active profile/selector/SHA run. Exact request matches take precedence over other
active runs across the bounded history search. Manual requests with different profiles/selectors do not cancel each
other. Automatic validation may cancel superseded PR revisions. This is bounded
coordination with GitHub's eventually consistent API, not exactly-once execution.
If dispatch or its reply is lost, inspect controller logs and search the full run
title/request ID first. Retrying a controller attempt cannot blindly repeat a
POST; an uncertain missing run requires inspection before a new deliberate
request. A failed status reply does not justify another dispatch.

For infrastructure failure on the same SHA, collect the failed job's logs,
step outcomes, emulator fingerprint, crash buffer and exit-info before rerunning
only failed jobs. Preserve run ID/attempt and the original diagnosis. Changed code
needs a new revision request. Do not auto-retry an unexplained startup exit to
obtain green. A stale request needs a new current-HEAD request after reviewing the
change; rerunning the old SHA does not validate the new HEAD.

Generating screenshot candidates, reviewing original renderer images, and owner
acceptance remain separate. Follow
[reviewed screenshot promotion](development.md#github-only-reviewed-screenshot-promotion)
for exact source/artifact/digest checks and owner-only acceptance. If promotion
pushes references but follow-up dispatch fails, its result records **partial
success**, the promoted SHA and request ID. Inspect runs for that identity first;
if validation is absent, validate the new promoted SHA with `/jlmod-ci validate
--sha <promoted-SHA>` on one line or gh/UI exact-source inputs. Do not accept the
old candidate again from its replaced HEAD.

## Default-branch bootstrap and canary

`issue_comment` uses the default branch's workflow. A handler changed in a PR
cannot be claimed tested end to end through ordinary comments until that handler
is integrated into the default branch. Parser/helper fixtures, YAML-expression
policy tests, actionlint and branch-dispatched workflow tests are separate evidence
from the postmerge comment canary. Integration is a maintainer action; this work
does not authorize merging to enable a test.

Check screenshot promotion's workflow path, registration state, default branch,
validation errors and run history separately. A file's presence does not prove
registration or a working trigger. If it reports deleted or remains unregistered,
capture the API/configuration evidence and investigate with repository admin
access; neither a rename nor enable action is presumed to fix it. A connector
without dispatch/enable capability must report that limitation.

After integration, use a harmless current same-repository PR for these canaries:

1. Recheck default branch, workflow IDs/paths/state and the committed controller
   version. Confirm allowlisted identities before the positive test.
2. Use an existing issue only if available and appropriate; do not create a public
   issue merely for a negative test. Owner and other-author issue comments,
   including valid commands and quoted examples, must leave every handler job
   skipped with no runner/steps and no automatic reply. Fixture evidence covers
   cases that cannot be exercised with the available identities/access.
3. On the PR, verify ordinary owner/allowed comments, quoted/code-fenced examples
   and an outsider valid command skip every controller job. Edited/deleted
   comments must not trigger this `created` handler.
4. Post one valid authorized focused request. Check its reply URL, exact request
   ID, expected/built/current PR SHA, profile/selector, executed count and absence
   of controller token/signing secrets in test-job configuration.
5. Check wrong/zero-test selectors fail, moved HEAD is stale before Android setup,
   identical active requests reuse the existing run and different profiles remain
   independent. Inspect diagnostics before any rerun.
6. Diagnose promotion registration through workflow state and actual event/job
   evidence. Do not fabricate a baseline change or accept an unreviewed candidate
   to exercise its write path. Promotion's partial-success recovery is covered by
   fixtures until a real reviewed acceptance warrants the live path.

Record each canary's source SHA, event/comment ID, run URL, attempt, actual job/
step evidence and counts. Pending live checks remain pending; fixture success is
not live runner-allocation or emulator evidence.

## Measuring CI efficiency

The historical full-CI sample is context, not a benchmark. Retain parallel jobs
and `setup-gradle` ownership of Gradle User Home; do not add a second output cache
or a warming build on every alpha push without measured benefit. Configuration
Cache, Build Cache, parallel execution and KSP already exist.

Measure queue, setup, download, execution, upload and cache-post time separately,
plus time to APK, first test feedback, final verdict and total runner time. Compare
identical source/task/ABI/toolchain/runner/signing configurations with recorded
cold/warm state, multiple observations and individual values plus median/range.
Three runs do not establish p95. Focused result JSON measures only its invocation duration; unknown phases remain
unknown. Verify cross-run Configuration Cache **reuse**, not
merely local storage. An encryption key is necessary but not sufficient: the
pinned Enhanced provider also gates saving project-cache entries on separate
opt-in and Develocity-trial eligibility; the Basic provider caches Gradle User Home only.
The repo configures neither project-cache opt-in nor a trial. Do not infer
cross-run reuse from an encryption input or a local "entry stored" message.
See the [pinned implementation and limits](development.md#ci-performance).
Treat blank support output as unknown, preserve the current licensing/configuration
boundary, and never print encryption secrets. Investigate native-task inputs,
cache scope/availability and eviction before adding caches.
Require repeated comparable observations before claiming efficiency gains or
changing job/cache topology. Keep the simpler configuration when experiments show
no benefit. Never delete shared caches for experiments.

These commands add no required checks, reviewers, merge queue or protection rules.
Alpha retains exceptional merge discretion. Test failures stay failures, and the
release publishing workflow gains no CI-evidence gate.
