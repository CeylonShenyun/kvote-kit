#!/usr/bin/env python3
"""kvote —— 把 Claude 订阅额度做成一张卡片：一个文件，只用标准库，`python3 server.py` 就能跑。

  GET /            卡片页面（web/index.html）
  GET /api/usage   kvote 数据文件的内容，过了重置时间的窗口去掉

数据文件由 capture/ 里的小工具写（stream-json / 状态栏 / 定时刷新三选一），这里只读，
不碰登录凭证，也不去问 Anthropic 的任何接口。
"""
import json
import os
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import kvote_store

ROOT = Path(__file__).resolve().parent
WEB = ROOT / "web"
HOST = os.environ.get("KVOTE_HOST", "127.0.0.1")
PORT = int(os.environ.get("KVOTE_PORT", "18020"))
TYPES = {".html": "text/html", ".js": "text/javascript", ".css": "text/css", ".png": "image/png"}


def usage() -> dict:
    d = kvote_store.load() or {}
    now = time.time()
    wins = {k: v for k, v in (d.get("windows") or {}).items()
            if isinstance(v, dict) and float(v.get("resetsAt") or 0) > now}
    return {"updated": d.get("updated"), "status": d.get("status"), "windows": wins}


class Handler(BaseHTTPRequestHandler):
    def _send(self, code: int, body: bytes, ctype: str):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path == "/api/usage":
            return self._send(200, json.dumps(usage()).encode(), "application/json; charset=utf-8")
        name = "index.html" if path in ("/", "") else path.lstrip("/")
        f = (WEB / name).resolve()
        if WEB not in f.parents or not f.is_file():
            return self._send(404, b"not found", "text/plain")
        ctype = TYPES.get(f.suffix, "application/octet-stream")
        if ctype.startswith("text/"):
            ctype += "; charset=utf-8"
        self._send(200, f.read_bytes(), ctype)

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    print(f"kvote：http://{HOST}:{PORT}/   数据文件：{kvote_store.DEFAULT_FILE}", flush=True)
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
