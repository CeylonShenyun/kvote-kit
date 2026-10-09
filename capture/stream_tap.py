#!/usr/bin/env python3
"""给用 `claude -p --output-format stream-json` 跑 Claude Code 的前端（各种 bridge）用。

Claude Code 每回一轮，stream-json 里都会带一条 rate_limit_event，里面就是各个额度窗口的用量和重置时间。
这个小工具原样转发每一行，顺手把那条存进 kvote 的数据文件。不碰登录凭证，不多发任何请求。

两种用法：
  1) 管道：  claude -p ... --output-format stream-json --verbose | python3 capture/stream_tap.py | 你原来的处理
  2) 代码里：from capture.stream_tap import handle_line，每收到一行 stdout 调一次 handle_line(line)
     （Node 的 bridge 用 capture/usage-snapshot.js，一样的事）
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import kvote_store  # noqa: E402


def handle_line(line, path=None):
    """是 rate_limit_event 就存；返回 True 表示存了。别的行什么都不做。"""
    s = line.strip() if isinstance(line, str) else line.decode("utf-8", "replace").strip()
    if not s.startswith("{") or "rate_limit_event" not in s:
        return False
    try:
        ev = json.loads(s)
    except ValueError:
        return False
    if ev.get("type") != "rate_limit_event":
        return False
    info = ev.get("rate_limit_info") or ev.get("rateLimitInfo") or {}
    kw = {"path": path} if path else {}
    return kvote_store.record(kvote_store.from_rate_limit_info(info), info.get("status"), **kw)


def main():
    out = sys.stdout
    for line in sys.stdin:
        out.write(line)
        out.flush()
        handle_line(line)


if __name__ == "__main__":
    main()
