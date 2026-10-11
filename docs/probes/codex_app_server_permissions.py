"""Credential-free Windows 0.154.0 permission probe; never starts a model Turn.

Run with an absolute path to the native codex.exe, not a shell/npm wrapper.
The isolated CODEX_HOME and report remain in this checkout's ignored target/.
Exit 2 means thread/start rejected the requested restricted read boundary.
This is investigation evidence, not a supported Rei execution backend.
"""
import json
import os
from pathlib import Path
import queue
import subprocess
import sys
import tempfile
import threading
import time


def main():
    if os.name != "nt" or len(sys.argv) != 2:
        raise SystemExit("Usage on Windows: python docs/probes/codex_app_server_permissions.py C:/absolute/path/codex.exe")
    binary = Path(sys.argv[1])
    if not binary.is_absolute() or not binary.is_file() or binary.suffix.lower() != ".exe":
        raise SystemExit("An absolute native executable path is required")
    target = Path(__file__).resolve().parents[2] / "target"
    target.mkdir(exist_ok=True)
    probe_home = Path(tempfile.mkdtemp(prefix="app-server-permission-probe-", dir=target))
    workspace = probe_home / "workspace"
    workspace.mkdir()
    (probe_home / "config.toml").write_text('''default_permissions = "rei-probe"
web_search = "disabled"
[windows]
sandbox = "unelevated"
[permissions.rei-probe.filesystem]
":root" = "deny"
":minimal" = "read"
":workspace_roots" = "read"
[permissions.rei-probe.network]
enabled = false
[shell_environment_policy]
inherit = "none"
''', encoding="utf8")
    # No OAuth/API keys, user home, proxy or normal Codex login environment.
    child_env = {key: os.environ[key] for key in ("SystemRoot", "PATH", "TEMP", "TMP") if key in os.environ}
    child_env["CODEX_HOME"] = str(probe_home)
    version = subprocess.run([str(binary), "--version"], cwd=workspace, env=child_env,
                             capture_output=True, text=True, timeout=10, creationflags=subprocess.CREATE_NO_WINDOW)
    if version.returncode != 0 or version.stdout.strip() != "codex-cli 0.154.0":
        raise SystemExit("This reproduction is pinned to codex-cli 0.154.0; inspect a different version's schema first")
    process = subprocess.Popen([str(binary), "app-server", "--listen", "stdio://"],
                               cwd=workspace, env=child_env, stdin=subprocess.PIPE,
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                               text=True, encoding="utf8", creationflags=subprocess.CREATE_NO_WINDOW)
    incoming = queue.Queue(maxsize=100)
    records = []

    def read_protocol():
        while True:
            line = process.stdout.readline(65537)
            if not line:
                incoming.put(None)
                return
            if len(line) > 65536:
                incoming.put(None)
                return
            incoming.put(line)

    def discard_diagnostics():
        # Drain without persisting/printing native diagnostics.
        while process.stderr.read(4096):
            pass

    threading.Thread(target=read_protocol, daemon=True).start()
    threading.Thread(target=discard_diagnostics, daemon=True).start()

    def send(value):
        process.stdin.write(json.dumps(value) + "\n")
        process.stdin.flush()

    def request(request_id, method, params):
        send({"id": request_id, "method": method, "params": params})
        deadline = time.monotonic() + 15
        # A finite frame count prevents notifications from extending the probe.
        for _ in range(100):
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise RuntimeError("Protocol request deadline exceeded")
            line = incoming.get(timeout=remaining)
            if line is None:
                raise RuntimeError("Protocol process ended or exceeded the frame limit")
            value = json.loads(line)
            if value.get("id") == request_id:
                records.append({"method": method, "response": value})
                return value
        raise RuntimeError("Protocol notification limit exceeded")

    try:
        initialized = request(1, "initialize", {"clientInfo": {"name": "rei_protocol_probe", "version": "0.1.0"},
                                                "capabilities": {"experimentalApi": True}})
        if "error" in initialized:
            raise RuntimeError("Protocol initialization rejected")
        send({"method": "initialized", "params": {}})
        profiles = request(2, "permissionProfile/list", {})
        if not any(p.get("id") == "rei-probe" and p.get("allowed") for p in profiles.get("result", {}).get("data", [])):
            raise RuntimeError("Configured restricted profile is unavailable")
        thread = request(3, "thread/start", {"cwd": str(workspace), "ephemeral": True,
                                             "permissions": "rei-probe", "approvalPolicy": "untrusted",
                                             "approvalsReviewer": "user", "model": "gpt-5.3-codex"})
        # No turn/start, account login, inference or sandbox setup is sent.
    finally:
        process.stdin.close()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)
    report = {"version": "0.154.0", "requests": records, "processExit": process.returncode,
              "modelTurnStarted": False, "threadCreated": "result" in thread}
    (probe_home / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["threadCreated"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
