"""Loopback-only OpenAI fixture held until the first Terminal pane has exited."""
import json
import pathlib
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

root = pathlib.Path(sys.argv[1])


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_GET(self):
        if self.path != "/v1/models":
            self.send_error(404)
            return
        body = json.dumps({"object": "list", "data": [{"id": "fixture", "object": "model"}]}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        if self.path != "/v1/chat/completions":
            self.send_error(404)
            return
        (root / "provider-blocked").write_text("request received", encoding="utf-8")
        deadline = time.monotonic() + 90
        while not (root / "release-provider").exists():
            if time.monotonic() > deadline:
                self.send_error(504)
                return
            time.sleep(0.1)
        common = {"id": "pane-fixture", "created": 1, "model": "fixture"}
        if request.get("stream"):
            chunks = [
                {**common, "object": "chat.completion.chunk", "choices": [{"index": 0, "delta": {"role": "assistant", "content": "Pane run completed."}, "finish_reason": None}]},
                {**common, "object": "chat.completion.chunk", "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}], "usage": {"prompt_tokens": 1, "completion_tokens": 4, "total_tokens": 5}},
            ]
            body = ("".join("data: " + json.dumps(chunk) + "\n\n" for chunk in chunks) + "data: [DONE]\n\n").encode()
            content_type = "text/event-stream"
        else:
            body = json.dumps({**common, "object": "chat.completion", "choices": [{"index": 0, "message": {"role": "assistant", "content": "Pane run completed."}, "finish_reason": "stop"}], "usage": {"prompt_tokens": 1, "completion_tokens": 4, "total_tokens": 5}}).encode()
            content_type = "application/json"
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


with ThreadingHTTPServer(("127.0.0.1", 0), Handler) as server:
    (root / "provider-url").write_text(f"http://127.0.0.1:{server.server_port}", encoding="utf-8")
    server.serve_forever()
