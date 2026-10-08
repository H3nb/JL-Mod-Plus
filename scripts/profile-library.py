#!/usr/bin/env python3
"""Diagnostic A/B workload for a disposable API 35 emulator, not an FPS benchmark."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

PACKAGE = "io.github.h3nb.jlmodplus.debug"
RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"
PROBE = "io.github.h3nb.jlmodplus.applist.LibraryPerformanceProbeTest"


def run(*args):
    try:
        return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT)
    except subprocess.CalledProcessError as error:
        raise RuntimeError(f"Command {args!r} failed: {error.output}") from error


def adb(*args):
    return run("adb", *args)


def hierarchy():
    adb("shell", "uiautomator", "dump", "/sdcard/library-probe.xml")
    return ET.fromstring(adb("shell", "cat", "/sdcard/library-probe.xml"))


def point(label):
    matches = []
    for node in hierarchy().iter("node"):
        if node.get("text") == label or node.get("content-desc") == label:
            bounds = [int(n) for n in re.findall(r"\d+", node.get("bounds", ""))]
            if len(bounds) == 4 and bounds[2] > bounds[0] and bounds[3] > bounds[1]:
                matches.append(((bounds[0] + bounds[2]) // 2, (bounds[1] + bounds[3]) // 2))
    if not matches:
        raise RuntimeError("Missing visible navigation/collection label: " + label)
    return max(matches, key=lambda p: p[1])


def tap(position):
    adb("shell", "input", "tap", *map(str, position))
    time.sleep(0.8)


def wait_populated():
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        if any("Probe Game" in n.get("text", "") for n in hierarchy().iter("node")):
            return
        time.sleep(0.5)
    raise RuntimeError("The real MainActivity did not display the seeded catalog")


def wait_destination(out, name, members=False):
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        tree = hierarchy()
        selected = any(node.get("selected") == "true" and
                       any(child.get("text") == name or child.get("content-desc") == name
                           for child in node.iter("node")) for node in tree.iter("node"))
        texts = [node.get("text", "") for node in tree.iter("node")]
        ready = (any("Probe Game" in text for text in texts) if name == "Apps" or members
                 else "Probe Collection" in texts if name == "Collections" else "Settings" in texts)
        if selected and ready:
            (out / f"navigation-{name.lower()}-{'members' if members else 'root'}.xml").write_bytes(
                ET.tostring(tree))
            return
        time.sleep(0.5)
    raise RuntimeError(f"Destination {name} (members={members}) did not become selected and ready")


def capture(out, name, action, seconds=20):
    config = out / (name + "-config.pftxt")
    config.write_text(re.sub(r"duration_ms: \d+", f"duration_ms: {seconds * 1000}",
                             (out / "trace-config.pftxt").read_text()))
    log = out / (name + "-recorder.log")
    with log.open("w") as stream:
        process = subprocess.Popen(
            ["python3", "perfetto-tools/record_android_trace", "--no-open", "-c", str(config),
             "-o", str(out / (name + ".perfetto-trace"))],
            stdout=stream, stderr=subprocess.STDOUT,
            env=dict(os.environ, PYTHONUNBUFFERED="1"),
        )
        try:
            deadline = time.monotonic() + 30
            while "Trace started" not in log.read_text():
                if process.poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError("Recorder did not start: " + log.read_text())
                time.sleep(0.1)
            action()
            if process.wait(timeout=40) != 0:
                raise RuntimeError("Recorder failed: " + log.read_text())
        finally:
            if process.poll() is None:
                process.terminate()
                process.wait(timeout=10)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--label", required=True)
    parser.add_argument("--apk", required=True)
    parser.add_argument("--test-apk", required=True)
    parser.add_argument("--commit", required=True)
    args = parser.parse_args()
    out = Path("performance-artifacts") / args.label
    out.mkdir(parents=True, exist_ok=True)
    # Each A/B installation begins with independent identical data, preferences and caches.
    subprocess.run(["adb", "uninstall", PACKAGE + ".test"], stdout=subprocess.DEVNULL)
    subprocess.run(["adb", "uninstall", PACKAGE], stdout=subprocess.DEVNULL)
    adb("install", args.apk)
    adb("install", args.test_apk)
    adb("shell", "appops", "set", PACKAGE, "MANAGE_EXTERNAL_STORAGE", "allow")
    adb("shell", "cmd", "package", "compile", "-m", "speed", "-f", PACKAGE)
    result = adb("shell", "am", "instrument", "-w", "-r", "-e", "class", PROBE,
                 "-e", "libraryPerformanceProbe", "true", RUNNER)
    (out / "instrumentation.txt").write_text(result)
    if "OK (2 tests)" not in result:
        raise RuntimeError("Search characterization/fixture setup failed: " + result)
    (out / "search-cpu.json").write_text(
        adb("shell", "run-as", PACKAGE, "cat", "files/library-search-cpu.json"))
    (out / "environment.json").write_text(json.dumps({
        "sourceCommit": args.commit, "label": args.label, "variant": "emulatorDebug",
        "nativeBuild": False, "rows": 1000, "collectionRows": 500,
        "buildFingerprint": adb("shell", "getprop", "ro.build.fingerprint").strip(),
        "compilation": "speed", "scope": "debug emulator diagnostic; not physical-device FPS",
    }, indent=2))
    (out / "trace-config.pftxt").write_text('''buffers { size_kb: 65536 fill_policy: RING_BUFFER }
duration_ms: 20000
data_sources { config { name: "linux.ftrace" ftrace_config {
  ftrace_events: "sched/sched_switch"
  ftrace_events: "sched/sched_waking"
  ftrace_events: "task/task_newtask"
  ftrace_events: "task/task_rename"
  atrace_categories: "gfx"
  atrace_categories: "view"
  atrace_categories: "wm"
  atrace_categories: "am"
  atrace_categories: "dalvik"
  atrace_apps: "io.github.h3nb.jlmodplus.debug"
} } }
data_sources { config { name: "android.surfaceflinger.frametimeline" } }
data_sources { config { name: "linux.process_stats" process_stats_config {
  scan_all_processes_on_start: true
  proc_stats_poll_ms: 1000
} } }
''')
    for iteration in range(3):
        adb("shell", "am", "force-stop", PACKAGE)

        def launch():
            launch_result = adb("shell", "am", "start", "-W", "-n",
                                PACKAGE + "/io.github.h3nb.jlmodplus.MainActivity")
            (out / f"cold-{iteration}-am-start.txt").write_text(launch_result)
        capture(out, f"cold-{iteration}", launch)
        # Accessibility dumps run after recording so they cannot inflate startup work.
        wait_populated()
    nav = {name: point(name) for name in ("Apps", "Collections", "More")}
    tap(nav["Collections"])
    collection = point("Probe Collection")
    tap(nav["Apps"])
    screen = re.findall(r"(\d+)x(\d+)", adb("shell", "wm", "size"))[-1]
    width, height = map(int, screen)

    def scroll():
        for start, end in [(0.7, 0.3)] * 4 + [(0.3, 0.7)] * 4:
            adb("shell", "input", "swipe", str(width // 2), str(int(height * start)),
                str(width // 2), str(int(height * end)), "300")
            time.sleep(0.8)

    capture(out, "scroll", scroll)
    # Read semantics outside the measured gesture window, and verify navigation has returned.
    nav = {name: point(name) for name in ("Apps", "Collections", "More")}

    # Separate transitions let us confirm each destination outside its measured window.
    capture(out, "navigation-0-collections", lambda: tap(nav["Collections"]), seconds=5)
    wait_destination(out, "Collections")
    capture(out, "collection-open", lambda: tap(collection), seconds=5)
    wait_destination(out, "Collections", members=True)
    for index, name in enumerate(("Apps", "Collections", "More", "Apps", "Collections", "Apps"), 1):
        capture(out, f"navigation-{index}-{name.lower()}", lambda: tap(nav[name]), seconds=5)
        wait_destination(out, name, members=name == "Collections")

    (out / "gfxinfo.txt").write_text(adb("shell", "dumpsys", "gfxinfo", PACKAGE))


if __name__ == "__main__":
    main()
