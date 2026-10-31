#!/usr/bin/env bash
set -euo pipefail
chmod +x gradlew
cat > /tmp/update-metadata-service.py <<'PY'
import json
import threading
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
gate = threading.Event()
class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/unlock":
            gate.set()
            self.send_response(200); self.end_headers(); self.wfile.write(b"ok"); return
        if self.path == "/health":
            self.send_response(200); self.end_headers(); self.wfile.write(b"ok"); return
        if self.path != "/updates.json":
            self.send_error(404); return
        if not gate.wait(80):
            self.send_error(503); return
        data = {"schema": 1, "applicationId": "io.github.h3nb.jlmodplus", "releases": [
            {"versionName": "99.0.0-beta", "versionCode": 2099999999, "prerelease": True,
             "minSdk": 23, "assets": [{
                 "abi": "x86_64",
                 "url": "https://github.com/H3nb/JL-Mod-Plus/releases/download/v99.0.0-beta/JL-Mod-Plus-v99.0.0-beta-x86_64.apk"
             }]}
        ]}
        body = json.dumps(data).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
ThreadingHTTPServer(("0.0.0.0", 8765), Handler).serve_forever()
PY
python3 /tmp/update-metadata-service.py >/tmp/update-http.log 2>&1 &
server_pid=$!
trap 'kill "$server_pid" 2>/dev/null || true; cat /tmp/update-http.log' EXIT
curl --fail --silent --show-error --retry 10 --retry-connrefused --retry-delay 1 http://127.0.0.1:8765/health
adb shell appops set io.github.h3nb.jlmodplus.debug MANAGE_EXTERNAL_STORAGE allow || true
./gradlew --daemon --stacktrace -PjlmodNativeBuild=false -PjlmodRuntimeTestAbi=x86_64 \
  -Pjlmod.versionName=0.2.5-beta -Pjlmod.versionCode=25 \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.h3nb.jlmodplus.updates.TemporaryUpdateAcceptanceTest \
  :app:connectedEmulatorDebugAndroidTest
