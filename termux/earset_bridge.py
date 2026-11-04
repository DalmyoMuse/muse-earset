"""Muse Earset -> Muse: a tiny HTTP endpoint for the Muse Earset app on this phone.

    POST http://127.0.0.1:47821/ask  {"text": "..."}  ->  {"ok": true} | {"ok": false, "why": "..."}

It forwards the text, the same way `musegadget send-user-msg --session-id` does, to one
dedicated chat (its id is kept in ~/.musegadget/earset_session), so voice requests don't
mix with your main chat. Muse answers by running the `earset-say` gadget command, which
hands the reply back to the app to speak in the voice you chose.
Loopback only: Android apps can't reach Termux's Unix socket, but they can reach 127.0.0.1.
"""

from __future__ import annotations

import json
import logging
import os
import socket
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

STATE = Path(os.environ.get("MUSEGADGET_STATE_DIR") or Path.home() / ".musegadget")
SOCK = Path(os.environ.get("MUSEGADGET_SOCKET") or STATE / "musegadget.sock")
SESSION_FILE = STATE / "earset_session"
PORT = int(os.environ.get("EARSET_BRIDGE_PORT", "47821"))
PREFIX = "[Earset] "
MAX_TEXT = 2000

log = logging.getLogger("earset-bridge")


def session_id() -> str:
    try:
        sid = SESSION_FILE.read_text().strip()
        if sid:
            return sid
    except OSError:
        pass
    sid = str(uuid.uuid4())
    SESSION_FILE.write_text(sid + "\n")
    log.info("new earset chat %s", sid)
    return sid


def deliver(text: str) -> dict:
    request = {"message": PREFIX + text, "session_id": session_id()}
    try:
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as s:
            s.settimeout(15)
            s.connect(str(SOCK))
            s.sendall(json.dumps(request).encode() + b"\n")
            reply = json.loads(s.makefile("rb").readline() or b"{}")
    except (OSError, ValueError) as exc:
        return {"ok": False, "why": f"gadget: {exc}"}
    if reply.get("ok"):
        return {"ok": True}
    return {"ok": False, "why": str(reply.get("error") or reply.get("status") or reply)[:200]}


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path != "/ask":
            return self._reply(404, {"ok": False, "why": "not found"})
        try:
            n = int(self.headers.get("Content-Length") or 0)
            body = json.loads(self.rfile.read(min(n, 64 * 1024)) or b"{}")
            text = str(body.get("text", "")).strip()[:MAX_TEXT]
        except (ValueError, AttributeError):
            return self._reply(400, {"ok": False, "why": "bad json"})
        if not text:
            return self._reply(400, {"ok": False, "why": "empty"})
        result = deliver(text)
        log.info("ask %d chars -> %s", len(text), "ok" if result["ok"] else result["why"])
        self._reply(200, result)

    def _reply(self, status: int, obj: dict) -> None:
        data = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, fmt, *args):
        pass


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    session_id()
    server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    log.info("listening on 127.0.0.1:%d", PORT)
    server.serve_forever()


if __name__ == "__main__":
    main()
