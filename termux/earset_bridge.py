"""Muse Earset -> Muse: a tiny HTTP endpoint for the Muse Earset app on this phone.

    POST http://127.0.0.1:47823/ask  {"text": "..."}  ->  {"ok": true} | {"ok": false, "why": "..."}

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
PORT = int(os.environ.get("EARSET_BRIDGE_PORT", "47823"))
# Loopback by default (app and host on the same phone). Set EARSET_BRIDGE_BIND=0.0.0.0
# only for a split test setup where the earset app lives on another device on a trusted
# LAN: anyone who can reach the port can then put words in front of your Muse.
BIND = os.environ.get("EARSET_BRIDGE_BIND", "127.0.0.1")
# Every message carries its own lead-in - the same pattern the owner's S24 agent uses:
# it says who is speaking, through what, and how to answer (by voice, via earset-say).
PREFIX = (
    "[Earset] The owner is speaking through the Muse Earset app "
    "(voice input - the words may be mis-recognized). "
    "Answer by running the gadget command `earset-say \"<your reply>\"` so the reply is "
    "spoken into the earset: keep it short and conversational, two or three sentences. "
    "Also leave the same reply as chat text. Message: "
)
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


def deliver(text: str, lead: str = "") -> dict:
    # The app may carry the owner's own lead-in (editable in its setup, like the agent's);
    # an empty one falls back to the built-in default below.
    request = {"message": (lead or PREFIX) + text, "session_id": session_id()}
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
            lead = str(body.get("lead", "")).strip()[:1000]
        except (ValueError, AttributeError):
            return self._reply(400, {"ok": False, "why": "bad json"})
        if not text:
            return self._reply(400, {"ok": False, "why": "empty"})
        # Remember who asked: earset-say sends the spoken reply back to this address, so
        # the reply always lands on the device that asked (a tablet on the LAN, or this
        # same phone at 127.0.0.1 in a one-phone setup).
        try:
            (STATE / "earset_app_host").write_text(self.client_address[0] + "\n")
        except OSError:
            pass
        result = deliver(text, lead)
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
    server = ThreadingHTTPServer((BIND, PORT), Handler)
    log.info("listening on %s:%d", BIND, PORT)
    server.serve_forever()


if __name__ == "__main__":
    main()
