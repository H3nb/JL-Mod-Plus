// SPDX-License-Identifier: Apache-2.0
// Evaluate the actual YAML job conditions with GitHub's expression engine.
import assert from 'node:assert/strict';
import {readFileSync, readdirSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import {parse} from 'yaml';
import {Evaluator, Lexer, Parser, data} from '@actions/expressions';

const root = fileURLToPath(new URL('../../', import.meta.url));
const allWorkflows = readdirSync(`${root}.github/workflows`).filter(name => /\.ya?ml$/.test(name))
  .map(name => [name, parse(readFileSync(`${root}.github/workflows/${name}`, 'utf8'))]);
// Narrow schema supplementation for actionlint 1.7.12. Primary contract:
// https://docs.github.com/en/actions/reference/workflow-syntax-for-github-actions#concurrency
for (const [name, workflow] of allWorkflows) {
  for (const group of [workflow.concurrency, ...Object.values(workflow.jobs).map(job => job.concurrency)]) {
    if (group && typeof group === 'object' && 'queue' in group) {
      assert(['single', 'max'].includes(group.queue), `${name}: invalid concurrency.queue`);
      assert(!(group.queue === 'max' && group['cancel-in-progress'] === true), `${name}: max queue cannot cancel active runs`);
    }
  }
}
const workflows = allWorkflows
  .filter(([, workflow]) => workflow.on?.issue_comment);
assert(workflows.length >= 2, 'Both comment handlers must be covered');

function evaluate(expression, context) {
  assert.equal(typeof expression, 'string', 'Every comment job needs a pre-run condition');
  const code = expression.replace(/^\s*\$\{\{/, '').replace(/\}\}\s*$/, '');
  const {tokens} = new Lexer(code).lex();
  const extensionFunctions = ['always', 'success', 'cancelled', 'failure'].map(name => ({name, minArgs: 0, maxArgs: 0}));
  const tree = new Parser(tokens, ['github', 'vars', 'needs', 'inputs'], extensionFunctions).parse();
  const functions = new Map(extensionFunctions.map(({name, ...info}) => [name, {
    name, ...info, call: () => new data.BooleanData(name === 'always' || name === 'success')
  }]));
  const parsed = JSON.parse(JSON.stringify(context), data.reviver);
  return new Evaluator(tree, parsed, functions).evaluate();
}

function context({pr = false, actor = 10, body = '/jlmod-ci validate', ids = '', action = 'created'} = {}) {
  return {
    github: {event_name: 'issue_comment', event: {
      action, repository: {owner: {id: 10}}, sender: {id: actor},
      comment: {body, user: {id: actor}}, issue: {number: 1, ...(pr ? {pull_request: {url: 'https://api.github.com/repos/test/repo/pulls/1'}} : {})}
    }},
    vars: {JLMOD_CI_ACTOR_IDS: ids},
    needs: {}, inputs: {}
  };
}

function selected(job, fixture) {
  const value = evaluate(job.if, context(fixture));
  // Job selection uses expression truthiness, not just a literal boolean.
  return value.kind !== data.Kind.Null && !(value.kind === data.Kind.Boolean && !value.value)
    && !(value.kind === data.Kind.String && value.value === '')
    && !(value.kind === data.Kind.Number && (value.value === 0 || Number.isNaN(value.value)));
}

let checks = 0;
for (const [name, workflow] of workflows) {
  assert.deepEqual(workflow.on.issue_comment.types, ['created'], `${name}: edited/deleted must not trigger`);
  for (const [id, job] of Object.entries(workflow.jobs)) {
    assert(!job.needs, `${name}/${id}: comment controller must not grow downstream jobs`);
    const isPromotion = name === 'screenshot-promotion.yml';
    const command = isPromotion ? '/jlmod-promote-screenshots\n{}' : '/jlmod-ci validate';
    for (const actor of [10, 42, 999]) {
      for (const body of [command, 'ordinary comment', `> ${command}`, `\`\`\`\n${command}\n\`\`\``, `Example: ${command}`, 'Long report\n'.repeat(100) + command]) {
        for (const ids of ['', '[]', '[42]', '[not-json]', '[true]', '"42"', '|42|']) {
          assert(!selected(job, {actor, body, ids}), `${name}/${id}: issue comment allocated a job`);
          checks++;
        }
      }
    }
    for (const body of ['ordinary comment', `> ${command}`, `\`\`\`\n${command}\n\`\`\``, `Example: ${command}`]) {
      assert(!selected(job, {pr: true, body}), `${name}/${id}: prose/quote allocated a job`);
      checks++;
    }
    for (const ids of ['', '[]', '[42]', '[not-json]', '[true]', '"999"', '|42|']) {
      assert(!selected(job, {pr: true, actor: 999, body: command, ids}), `${name}/${id}: outsider allocated a job`);
      checks++;
    }
    assert(selected(job, {pr: true, body: command}), `${name}/${id}: owner command must select controller`);
    if (!isPromotion) {
      assert(!selected(job, {pr: true, actor: 1, body: command, ids: '[true]'}), `${name}/${id}: malformed allowlist widened authorization`);
      assert(!selected(job, {pr: true, actor: 99, body: command, ids: '[[99]]'}), `${name}/${id}: nested allowlist widened authorization`);
      assert(selected(job, {pr: true, actor: 42, body: command, ids: '|42|'}), `${name}/${id}: explicit connector identity must work`);
      assert(!selected(job, {pr: true, actor: 4, body: command, ids: '|42|'}), `${name}/${id}: partial actor ID must not match`);
      assert(!selected(job, {pr: true, body: `${command}\nExample`}), `${name}/${id}: multiline prose must not match`);
      checks += 5;
    } else {
      for (const body of ['/jlmod-promote-screenshots-example', '/jlmod-promote-screenshots discussed here', '/jlmod-promote-screenshots']) {
        assert(!selected(job, {pr: true, body}), `${name}/${id}: promotion command needs an exact first line and request body`);
        checks++;
      }
    }
    checks++;
  }
}
console.log(`Comment job policy: ${checks} assertions passed against ${workflows.length} actual workflow files.`);

// Exercise the actual trusted inline source guard, rather than a parser replica.
const android = allWorkflows.find(([name]) => name === 'android.yml')[1];
const sourceJob = android.jobs.source;
assert(sourceJob && !sourceJob.steps.some(step => step.uses), 'Source verification must run without checkout or PR-owned actions');
assert.deepEqual([android.jobs.helpers.needs].flat(), ['source']);
for (const [id, job] of Object.entries(android.jobs)) {
  const expensive = job.steps?.some(step => /setup-java|setup-gradle|setup-android|android-emulator-runner/.test(step.uses || '') || /gradlew/.test(step.run || ''));
  if (expensive || id === 'host-native') {
    const dependencies = [job.needs].flat();
    assert(dependencies.includes('source') && dependencies.includes('helpers'), `${id}: verification/helpers must finish before Android or native work`);
    assert(!/\b(always|failure|cancelled)\s*\(/i.test(job.if || ''), `${id}: status conditions cannot bypass failed prerequisites`);
    const checkout = job.steps.find(step => step.uses?.startsWith('actions/checkout@'));
    assert.equal(checkout.with.ref, '${{ needs.source.outputs.sha }}', `${id}: checkout must use verified exact source`);
  }
}

const {spawnSync} = await import('node:child_process');
const {mkdtempSync, writeFileSync, chmodSync, existsSync, rmSync} = await import('node:fs');
const {tmpdir} = await import('node:os');
const {join} = await import('node:path');
const {createHash} = await import('node:crypto');
const knownProfiles = spawnSync('python3', ['-c', 'import sys,json;sys.path.insert(0,"scripts");from ci_profiles import PROFILES;print(json.dumps(PROFILES))'], {cwd: root, encoding: 'utf8'});
assert.equal(knownProfiles.status, 0, knownProfiles.stderr);
assert.deepEqual([...android.on.workflow_dispatch.inputs.mode.options].sort(), JSON.parse(knownProfiles.stdout).sort(), 'Dispatch choices must match the single profile contract');

const sourceStep = sourceJob.steps.find(step => step.id === 'source');
assert(sourceStep?.run, 'The source guard must remain testable before build setup');
const sha = char => char.repeat(40);
let sourceCases = 0;
function verifySource({event = 'workflow_dispatch', source = sha('a'), expected = source, head = source,
  repo = 'test/repo', headRepo = repo, number = '8', state = 'open', request = 'comment-27',
  workflowSha = sha('f'), selector = '', selectorKey, inputs = {}, succeeds = true, diagnostic = ''} = {}) {
  const directory = mkdtempSync(join(tmpdir(), 'jlmod-source-policy-'));
  try {
    const output = join(directory, 'output');
    const callLog = join(directory, 'gh-called');
    writeFileSync(join(directory, 'gh'), '#!/usr/bin/env python3\nimport json,os,sys\nassert "X-GitHub-Api-Version: 2026-03-10" in sys.argv\nopen(os.environ["JLMOD_FIXTURE_CALL"],"w").write("called")\nprint(os.environ["JLMOD_FIXTURE_PR"])\n');
    chmodSync(join(directory, 'gh'), 0o700);
    const fixture = {
      inputs: {source_sha: source, expected_sha: expected, pr_number: number, request_id: request,
        mode: 'unit', test_selector: selector,
        selector_key: selectorKey ?? (selector ? createHash('sha256').update(selector).digest('hex').slice(0, 16) : ''), ...inputs},
      vars: {}, needs: {}, github: {event_name: event, sha: workflowSha, run_id: '20', token: 'fixture-token', repository: repo,
        event: event === 'pull_request' ? {pull_request: {number: Number(number), head: {sha: head}, base: {sha: sha('b')}}} : {}}
    };
    if (event === 'pull_request') fixture.inputs = {};
    const guardEnv = Object.fromEntries(Object.entries(sourceStep.env).map(([key, value]) => [key,
      typeof value === 'string' && value.includes('${{') ? evaluate(value, fixture).coerceString() : String(value)]));
    const result = spawnSync('bash', ['-e', '-o', 'pipefail', '-c', sourceStep.run], {
      cwd: root, encoding: 'utf8', env: {...process.env, ...guardEnv,
        PATH: `${directory}:${process.env.PATH}`, GITHUB_OUTPUT: output,
        GITHUB_EVENT_NAME: event, GITHUB_SHA: workflowSha, GITHUB_REPOSITORY: repo,
        JLMOD_FIXTURE_CALL: callLog,
        JLMOD_FIXTURE_PR: JSON.stringify({state, head: {sha: head, repo: {full_name: headRepo}}, base: {sha: sha('b')}})}
    });
    assert.equal(result.status === 0, succeeds, `Source fixture ${sourceCases}: ${result.stdout}\n${result.stderr}`);
    const written = existsSync(output) ? readFileSync(output, 'utf8') : '';
    if (succeeds) {
      const actualSource = event === 'pull_request' ? workflowSha : source;
      assert(written.includes(`sha=${actualSource}\n`), 'Only the verified source can reach checkout');
      if (number) assert(written.includes(`pr_head=${head}\n`), 'PR head must stay distinct from the built merge revision');
    } else {
      assert.equal(written, '', 'Rejected source must produce no build outputs');
      if (diagnostic) assert(`${result.stdout}\n${result.stderr}`.includes(diagnostic), `Expected clear rejection: ${diagnostic}`);
    }
    if (event === 'pull_request') assert(!existsSync(callLog), 'Automatic PR source uses event merge/head evidence without manual API policy');
    sourceCases++;
  } finally {
    rmSync(directory, {recursive: true, force: true});
  }
}
verifySource();
verifySource({expected: sha('b'), succeeds: false, diagnostic: 'Stale request'});
verifySource({head: sha('b'), succeeds: false, diagnostic: 'Stale request'});
verifySource({headRepo: 'outsider/fork', succeeds: false, diagnostic: 'same-repository'});
verifySource({state: 'closed', succeeds: false, diagnostic: 'open same-repository'});
verifySource({number: '', succeeds: false, diagnostic: 'requires a PR number'});
verifySource({number: '8;echo attack', succeeds: false, diagnostic: 'Invalid PR number'});
verifySource({request: 'comment\n27', succeeds: false, diagnostic: 'Invalid request ID'});
verifySource({source: 'main', expected: '', succeeds: false, diagnostic: 'Stale request'});
verifySource({source: sha('f'), number: ''}); // Ordinary gh/UI dispatch on the selected workflow branch.
verifySource({source: sha('f'), headRepo: 'outsider/fork', succeeds: false, diagnostic: 'same-repository'});
verifySource({event: 'pull_request', head: sha('a'), workflowSha: sha('c')}); // Synthetic merge is intentional.
verifySource({selector: 'package.NewRegressionTest#regressionCase'});
verifySource({selector: 'package.NewRegressionTest#regressionCase', selectorKey: '0000000000000000', succeeds: false, diagnostic: 'Selector key'});
verifySource({selectorKey: '0000000000000000', succeeds: false, diagnostic: 'Selector key'});
console.log(`Source guard: ${sourceCases} actual-inline-code fixtures passed; setup dependency and profile contracts passed.`);

// Explicit-source dispatch runs a trusted default-branch workflow with PR inputs.
// Its outputs must never populate the default branch's Gradle cache scope.
let cacheCases = 0;
for (const [id, job] of Object.entries(android.jobs)) {
  const setup = job.steps?.find(step => step.uses?.startsWith('gradle/actions/setup-gradle@'));
  if (!setup) continue;
  const expression = setup.with['cache-read-only'];
  for (const [event, source, headRepo, readOnly] of [
    ['workflow_dispatch', sha('a'), 'test/repo', true],
    ['workflow_dispatch', '', 'test/repo', false],
    ['pull_request', '', 'test/repo', false],
    ['pull_request', '', 'outsider/fork', true]
  ]) {
    const fixture = {github: {event_name: event, repository: 'test/repo',
      event: {pull_request: {head: {repo: {full_name: headRepo}}}}},
      inputs: {source_sha: source}, vars: {}, needs: {}};
    const actual = evaluate(expression, fixture);
    assert.equal(actual.kind, data.Kind.Boolean, `${id}: cache policy must yield a boolean`);
    assert.equal(actual.value, readOnly, `${id}: wrong cache write policy for ${event}, source=${source}, repo=${headRepo}`);
    cacheCases++;
  }
}
assert.equal(cacheCases, 16, 'All four Gradle-owning jobs must enforce cache trust boundaries');
console.log(`Cache policy: ${cacheCases} fixtures passed against actual setup-gradle expressions.`);

// Even callers reusing a request ID cannot cancel a different manual selector.
const render = (template, fixture) => template.replace(/\$\{\{([\s\S]*?)\}\}/g, (_, expression) => evaluate(expression, fixture).coerceString());
const concurrencyContext = (event, run, profile, selector) => ({
  github: {event_name: event, run_id: run, event: event === 'pull_request' ? {pull_request: {number: 8}} : {}},
  inputs: {mode: profile, request_id: 'reused-manual-id', test_selector: selector}, vars: {}, needs: {}
});
const manualFirst = concurrencyContext('workflow_dispatch', '20', 'unit', 'p.FirstTest');
const manualSecond = concurrencyContext('workflow_dispatch', '21', 'unit', 'p.SecondTest');
assert.notEqual(render(android.concurrency.group, manualFirst), render(android.concurrency.group, manualSecond));
assert.equal(evaluate(android.concurrency['cancel-in-progress'], manualFirst).value, false);
const automaticFirst = concurrencyContext('pull_request', '20', 'validate', '');
const automaticSecond = concurrencyContext('pull_request', '21', 'validate', '');
assert.equal(render(android.concurrency.group, automaticFirst), render(android.concurrency.group, automaticSecond));
assert.equal(evaluate(android.concurrency['cancel-in-progress'], automaticFirst).value, true);
console.log('Concurrency: manual investigations remain independent; automatic revisions share their PR group.');
