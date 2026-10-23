# SPDX-License-Identifier: Apache-2.0
"""Promote exact screenshot candidates that were already reviewed from CI."""

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import subprocess
import sys
import time
import zipfile

from ci_run import parse_run_title

COMMAND = "/jlmod-promote-screenshots"
ARTIFACT_NAME = "JL-Mod-Plus-screenshot-candidates"
REFERENCE_PREFIX = "app/src/screenshotTestDefaultEmulatorDebug/reference/"
PNG = b"\x89PNG\r\n\x1a\n"
SHA = re.compile(r"^[0-9a-f]{40}$")
DIGEST = re.compile(r"^sha256:[0-9a-f]{64}$")
MAX_FILES = 500
MAX_ENTRY = 16 * 1024 * 1024
MAX_TOTAL = 256 * 1024 * 1024


class Reject(RuntimeError):
    pass


def call(*args, cwd=None, input_text=None, binary_stdout=None):
    result = subprocess.run(
        args,
        cwd=cwd,
        input=input_text,
        text=input_text is not None,
        stdout=binary_stdout if binary_stdout is not None else subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if result.returncode:
        detail = result.stderr.decode(errors="replace") if isinstance(result.stderr, bytes) else result.stderr
        raise Reject(f"Command failed: {' '.join(args)}: {detail.strip()[:1000]}")
    if binary_stdout is not None:
        return ""
    output = result.stdout.decode() if isinstance(result.stdout, bytes) else result.stdout
    return output.strip()


def gh(path, method="GET", payload=None):
    args = ["gh", "api", "-H", "X-GitHub-Api-Version: 2026-03-10", path]
    if method != "GET":
        args += ["--method", method, "--input", "-"]
    text = call(*args, input_text=json.dumps(payload) if payload is not None else None)
    return json.loads(text) if text else None


def parse_request(body):
    lines = body.strip().splitlines()
    if not lines or lines[0].strip() != COMMAND:
        raise Reject(f"Expected exact command {COMMAND}.")
    try:
        data = json.loads("\n".join(lines[1:]).strip())
    except json.JSONDecodeError as error:
        raise Reject(f"Invalid promotion JSON: {error.msg}.") from error
    required = {"head_sha", "base_sha", "build_sha", "run_id", "artifact_id", "artifact_digest"}
    if not isinstance(data, dict) or set(data) != required:
        raise Reject("Promotion JSON has unexpected or missing keys.")
    for key in ("head_sha", "base_sha", "build_sha"):
        if not isinstance(data[key], str) or not SHA.fullmatch(data[key]):
            raise Reject(f"Invalid {key}.")
    if not isinstance(data["artifact_digest"], str) or not DIGEST.fullmatch(data["artifact_digest"]):
        raise Reject("Invalid artifact_digest.")
    for key in ("run_id", "artifact_id"):
        if not isinstance(data[key], int) or isinstance(data[key], bool) or data[key] <= 0:
            raise Reject(f"Invalid {key}.")
    return data


def safe_bundle(archive_path, request):
    with zipfile.ZipFile(archive_path) as archive:
        infos = archive.infolist()
        if sum(item.file_size for item in infos) > MAX_TOTAL:
            raise Reject("Screenshot artifact is too large.")
        names = {}
        for info in infos:
            path = PurePosixPath(info.filename)
            mode = info.external_attr >> 16
            if (not info.filename or "\\" in info.filename or info.filename.startswith("/") or
                    any(part in ("", ".", "..") for part in path.parts) or
                    (mode and stat.S_ISLNK(mode)) or info.file_size > MAX_ENTRY):
                raise Reject(f"Unsafe screenshot artifact entry: {info.filename!r}.")
            name = path.as_posix()
            if name in names:
                raise Reject(f"Duplicate screenshot artifact entry: {name}.")
            names[name] = info
        if "source-context.json" not in names:
            raise Reject("Screenshot artifact has no source-context.json.")
        context = json.loads(archive.read(names["source-context.json"]).decode("utf-8"))
        if not isinstance(context, dict):
            raise Reject("Invalid screenshot context.")
        checks = {
            "build_commit": request["build_sha"],
            "pr_head_commit": request["head_sha"],
            "pr_base_commit": request["base_sha"],
            "run_id": str(request["run_id"]),
        }
        if any(context.get(key) != value for key, value in checks.items()):
            raise Reject("Screenshot artifact provenance does not match the reviewed request.")
        if context.get("schema_version") != 2 or context.get("generation") not in ("comparison", "manual"):
            raise Reject("Unsupported screenshot manifest version or generation mode.")
        if context.get("excluded_failures") != []:
            raise Reject("Artifact includes non-comparison screenshot failures.")
        paths = context.get("candidate_paths")
        if (not isinstance(paths, list) or not paths or len(paths) > MAX_FILES or
                any(not isinstance(path, str) for path in paths) or len(paths) != len(set(paths))):
            raise Reject("Invalid screenshot candidate path list.")
        paths = sorted(paths)
        changes = context.get("changes")
        if not isinstance(changes, list) or len(changes) != len(paths):
            raise Reject("Invalid screenshot change manifest.")
        changes_by_path = {}
        for change in changes:
            if (not isinstance(change, dict) or set(change) != {"path", "operation", "before_sha256", "after_sha256"}
                    or not isinstance(change["path"], str) or change["path"] in changes_by_path):
                raise Reject("Invalid or duplicate screenshot change.")
            changes_by_path[change["path"]] = change
        if sorted(changes_by_path) != paths:
            raise Reject("Manifest paths do not match candidate paths.")
        files = []
        for target in paths:
            if (not isinstance(target, str) or not target.startswith(REFERENCE_PREFIX) or
                    not target.endswith(".png") or any(ord(ch) < 32 for ch in target)):
                raise Reject(f"Invalid screenshot candidate path: {target!r}.")
            relative = target[len(REFERENCE_PREFIX):]
            rel_path = PurePosixPath(relative)
            if not relative or rel_path.is_absolute() or "\\" in relative or rel_path.as_posix() != relative or ".." in rel_path.parts:
                raise Reject(f"Invalid screenshot candidate relative path: {relative!r}.")
            change = changes_by_path[target]
            operation = change["operation"]
            if operation not in ("add", "replace", "delete") or (context["generation"] == "comparison" and operation != "replace"):
                raise Reject("Comparison artifacts may only replace existing references.")
            images = []
            for side, absent in (("before", operation == "add"), ("after", operation == "delete")):
                name = f"{side}/{relative}"
                digest = change[f"{side}_sha256"]
                if absent:
                    if name in names or digest is not None:
                        raise Reject(f"Unexpected {side} image for {operation}: {target}.")
                    images.append(None)
                else:
                    if name not in names:
                        raise Reject(f"Artifact is missing reviewed {side} image: {target}.")
                    data = archive.read(names[name])
                    if not data.startswith(PNG) or hashlib.sha256(data).hexdigest() != digest:
                        raise Reject(f"Invalid reviewed {side} PNG/hash: {target}.")
                    images.append(data)
            before, after = images
            if before == after:
                raise Reject(f"Unchanged screenshot candidate: {target}.")
            files.append((target, before, after))
        return paths, files


def event_request(event_path):
    event = json.loads(Path(event_path).read_text(encoding="utf-8"))
    if not (event.get("issue") or {}).get("pull_request"):
        raise Reject("Promotion is supported only on pull requests.")
    if not (event.get("sender") or {}).get("id") or (event.get("sender") or {}).get("id") != ((event.get("repository") or {}).get("owner") or {}).get("id"):
        raise Reject("Only the repository owner may promote screenshots.")
    return event, parse_request((event.get("comment") or {}).get("body") or "")


def dispatch_generation(run, request, pr_number, default_branch):
    """Bind a trusted workflow definition separately from its checked-out PR source."""
    identity = parse_run_title(run.get("display_title", ""))
    if ((run.get("head_branch") == default_branch or run.get("head_sha") == request["head_sha"]) and identity
            and identity["pr_number"] == str(pr_number) and identity["source_sha"] == request["head_sha"]
            and identity["selector_key"] == "-"):
        if identity["mode"] == "update-screenshots":
            return "manual"
        if identity["mode"] in ("validate", "screenshots"):
            return "comparison"
    # Existing direct gh/UI requests on the PR branch retain their original contract.
    if run.get("head_sha") == request["head_sha"]:
        return "manual"
    raise Reject("Dispatched screenshot run does not identify the reviewed PR source.")


def inspect(args):
    event, request = event_request(args.event)
    repo = event["repository"]["full_name"]
    if repo != os.environ.get("GITHUB_REPOSITORY"):
        raise Reject("Event repository mismatch.")
    pr_number = event["issue"]["number"]
    pr = gh(f"repos/{repo}/pulls/{pr_number}")
    head, base = pr["head"], pr["base"]
    if pr["state"] != "open" or head["repo"]["full_name"] != repo:
        raise Reject("Promotion requires an open same-repository pull request.")
    if head["sha"] != request["head_sha"] or base["sha"] != request["base_sha"]:
        raise Reject("Pull-request head/base moved after review.")
    run = gh(f"repos/{repo}/actions/runs/{request['run_id']}")
    if (run.get("name") != "Android CI" or run.get("path") != ".github/workflows/android.yml" or
            run.get("status") != "completed" or run.get("event") not in ("pull_request", "workflow_dispatch") or
            (run.get("head_repository") or {}).get("full_name") != repo):
        raise Reject("Reviewed workflow run provenance is invalid.")
    artifact = gh(f"repos/{repo}/actions/artifacts/{request['artifact_id']}")
    workflow_run = artifact.get("workflow_run") or {}
    if (artifact.get("name") != ARTIFACT_NAME or artifact.get("expired") or
            artifact.get("digest") != request["artifact_digest"] or
            workflow_run.get("id") != request["run_id"] or
            workflow_run.get("head_sha") != run.get("head_sha")):
        raise Reject("Reviewed artifact provenance is invalid.")
    if run["event"] == "pull_request":
        if run.get("head_sha") != request["head_sha"]:
            raise Reject("Reviewed PR workflow has a different HEAD.")
        build = gh(f"repos/{repo}/commits/{request['build_sha']}")
        if [item["sha"] for item in build.get("parents", [])] != [request["base_sha"], request["head_sha"]]:
            raise Reject("Screenshot build is not the expected base/head merge commit.")
        expected_mode = "comparison"
    else:
        expected_mode = dispatch_generation(run, request, pr_number, event["repository"]["default_branch"])
        if request["build_sha"] != request["head_sha"]:
            raise Reject("Dispatched screenshot build must be the reviewed PR HEAD.")
        if expected_mode == "manual" and run.get("conclusion") != "success":
            raise Reject("Manual screenshot generation did not succeed.")

    artifact_path = Path(args.artifact)
    artifact_path.parent.mkdir(parents=True, exist_ok=True)
    with artifact_path.open("wb") as output:
        call("gh", "api", f"repos/{repo}/actions/artifacts/{request['artifact_id']}/zip", binary_stdout=output)
    if "sha256:" + hashlib.sha256(artifact_path.read_bytes()).hexdigest() != request["artifact_digest"]:
        raise Reject("Downloaded artifact digest does not match the reviewed archive.")
    paths, _ = safe_bundle(artifact_path, request)
    with zipfile.ZipFile(artifact_path) as archive:
        context = json.loads(archive.read("source-context.json"))
    if (context["generation"] != expected_mode or context.get("pr_number") != str(pr_number)
            or context.get("run_attempt") != str(run.get("run_attempt"))):
        raise Reject("Screenshot generation mode, PR or run attempt does not match.")
    state = {"version": 1, "repo": repo, "pr": pr_number, "branch": head["ref"], "request": request, "paths": paths,
             "default_branch": event["repository"]["default_branch"]}
    Path(args.state).write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
        output.write(f"head_sha={request['head_sha']}\n")


def promote(args):
    state = json.loads(Path(args.state).read_text(encoding="utf-8"))
    request, repo, pr_number = state["request"], state["repo"], state["pr"]
    pr = gh(f"repos/{repo}/pulls/{pr_number}")
    if (pr["state"] != "open" or pr["head"]["sha"] != request["head_sha"] or
            pr["base"]["sha"] != request["base_sha"] or pr["head"]["ref"] != state["branch"]):
        raise Reject("Pull-request state changed before promotion.")
    paths, files = safe_bundle(Path(args.artifact), request)
    if paths != state["paths"]:
        raise Reject("Candidate set changed between inspection and promotion.")
    target = Path(args.target).resolve()
    if call("git", "rev-parse", "HEAD", cwd=target) != request["head_sha"] or call("git", "status", "--porcelain", cwd=target):
        raise Reject("Target checkout is not the clean reviewed HEAD.")
    for path, before, _ in files:
        destination = target / path
        if destination.is_symlink() or not destination.resolve().is_relative_to(target):
            raise Reject(f"Reference path escapes checkout: {path}.")
        current = destination.read_bytes() if destination.exists() else None
        if current != before:
            raise Reject(f"Current golden does not match reviewed before image: {path}.")
    for path, _, after in files:
        destination = target / path
        if after is None:
            destination.unlink()
        else:
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(after)
        call("git", "add", "--", path, cwd=target)
    staged = call("git", "-c", "core.quotepath=false", "diff", "--cached", "--no-renames", "--name-only", cwd=target).splitlines()
    if staged != paths:
        raise Reject("Promotion staged files outside the reviewed candidate set.")
    for path, _, after in files:
        if ((target / path).read_bytes() if (target / path).exists() else None) != after:
            raise Reject(f"Promoted golden does not equal the reviewed after image: {path}.")
    status = call("git", "-c", "core.quotepath=false", "status", "--porcelain", cwd=target).splitlines()
    if any(REFERENCE_PREFIX not in line for line in status):
        raise Reject("Promotion touched files outside screenshot references.")

    message = (
        "test(screenshot): accept reviewed references\n\n"
        f"Screenshot-Source-Head: {request['head_sha']}\n"
        f"Screenshot-Source-Base: {request['base_sha']}\n"
        f"Screenshot-Source-Build: {request['build_sha']}\n"
        f"Screenshot-Source-Run: {request['run_id']}\n"
        f"Screenshot-Artifact: {request['artifact_id']}\n"
        f"Screenshot-Artifact-Digest: {request['artifact_digest']}"
    )
    call("git", "-c", "user.name=github-actions[bot]", "-c",
         "user.email=41898282+github-actions[bot]@users.noreply.github.com",
         "commit", "-m", message, cwd=target)
    promoted = call("git", "rev-parse", "HEAD", cwd=target)
    if call("git", "rev-parse", "HEAD^", cwd=target) != request["head_sha"]:
        raise Reject("Promotion commit has the wrong parent.")
    committed_paths = call("git", "-c", "core.quotepath=false", "diff-tree", "--no-renames", "--no-commit-id", "--name-only", "-r", promoted, cwd=target).splitlines()
    if sorted(committed_paths) != paths:
        raise Reject("Promotion commit contains unexpected files.")
    branch = state["branch"]
    lease = f"refs/heads/{branch}:{request['head_sha']}"
    call("git", "push", f"--force-with-lease={lease}", "origin", f"HEAD:refs/heads/{branch}", cwd=target)
    finish_promotion(args.state, state, promoted)


def finish_promotion(state_path, state, promoted):
    """A successful push is irreversible here, even when follow-up validation fails."""
    request, repo, pr_number = state["request"], state["repo"], state["pr"]
    request_id = f"promotion-{request['artifact_id']}-{promoted[:12]}"
    result = dict(schema_version=1, status="partial-success", source_sha=request["head_sha"],
                  promoted_sha=promoted, request_id=request_id, run_url=None)
    error = None
    try:
        for _ in range(6):
            if gh(f"repos/{repo}/pulls/{pr_number}")["head"]["sha"] == promoted:
                break
            time.sleep(1)
        else:
            raise Reject("Promoted commit did not become pull-request HEAD.")
        response = gh(f"repos/{repo}/actions/workflows/android.yml/dispatches", "POST", {
            "ref": state["default_branch"], "inputs": {
                "mode": "validate", "pr_number": str(pr_number), "source_sha": promoted,
                "request_id": request_id,
            },
        })
        if not isinstance(response, dict) or not response.get("workflow_run_id") or not response.get("html_url"):
            raise Reject("Dispatch response did not identify a run; inspect the request ID before retrying.")
        result.update(status="validation-dispatched", run_url=response["html_url"], run_id=response["workflow_run_id"])
    except (Reject, KeyError, OSError, json.JSONDecodeError) as failure:
        error = str(failure)
        result["validation_error"] = error
    state["promotion_result"] = result
    Path(state_path).write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))
    lines = [
        "<!-- jlmod-screenshot-promotion-result:v1 -->",
        "Reviewed screenshot references were promoted without re-rendering.", "",
        f"- Source HEAD: `{request['head_sha']}`", f"- Promoted commit: `{promoted}`",
        f"- Candidates: {len(state['paths'])}", f"- Artifact: `{request['artifact_id']}`",
        f"- Validation request: `{request_id}`",
    ]
    if error:
        lines.extend([
            "- **Partial success:** baseline push succeeded; validation dispatch is unconfirmed.",
            f"- Diagnosis: {error}",
            f"- Inspect runs for request `{request_id}` first. If no run exists, validate the new HEAD with "
            f"`/jlmod-ci validate --sha {promoted}` or gh/UI using this exact SHA.",
            "- Do not accept the old candidate again; its source HEAD has already been replaced.",
        ])
    else:
        lines.append(f"- Final Android CI validation: {result['run_url']}")
    summary = "\n".join(lines) + "\n"
    print(summary)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as output:
            output.write(summary)
    try:
        gh(f"repos/{repo}/issues/{pr_number}/comments", "POST", {"body": summary})
    except Reject as failure:
        print(f"Screenshot promotion warning: result comment failed: {failure}", file=sys.stderr)
    if error:
        raise Reject(f"Partial success: baseline pushed at {promoted}; validation unconfirmed: {error}")


def main():
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest="command", required=True)
    inspect_parser = commands.add_parser("inspect")
    inspect_parser.add_argument("--event", required=True)
    inspect_parser.add_argument("--artifact", required=True)
    inspect_parser.add_argument("--state", required=True)
    promote_parser = commands.add_parser("promote")
    promote_parser.add_argument("--artifact", required=True)
    promote_parser.add_argument("--state", required=True)
    promote_parser.add_argument("--target", required=True)
    args = parser.parse_args()
    try:
        inspect(args) if args.command == "inspect" else promote(args)
    except (Reject, KeyError, OSError, zipfile.BadZipFile, json.JSONDecodeError) as error:
        print(f"Screenshot promotion rejected: {error}", file=sys.stderr)
        raise SystemExit(1) from error


if __name__ == "__main__":
    main()
