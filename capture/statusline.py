#!/usr/bin/env python3
"""给交互式 claude（终端里直接开的那种）用：挂成状态栏脚本。

Claude Code 刷新状态栏时，会把一段 JSON 从 stdin 交给状态栏脚本，订阅账号里面带 rate_limits
（five_hour / seven_day 的 used_percentage 和 resets_at）。这个脚本把它存进 kvote 的数据文件，
然后照常输出一行状态栏。不碰登录凭证，不多发任何请求。

~/.claude/settings.json：
  "statusLine": {"type": "command", "command": "python3 /路径/kvote-kit/capture/statusline.py"}

已经有自己的状态栏脚本？设 KVOTE_CHAIN 把原来那条命令接在后面，显示的还是你原来的：
  "command": "KVOTE_CHAIN='bash ~/.claude/my-statusline.sh' python3 /路径/kvote-kit/capture/statusline.py"
"""
import json
import os
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import kvote_store  # noqa: E402


def main():
    raw = sys.stdin.read()
    try:
        data = json.loads(raw)
    except ValueError:
        data = {}
    fresh = kvote_store.from_statusline(data)
    if fresh:
        kvote_store.record(fresh)

    chain = os.environ.get("KVOTE_CHAIN")
    if chain:
        try:
            r = subprocess.run(chain, shell=True, input=raw, capture_output=True, text=True, timeout=10)
            sys.stdout.write(r.stdout)
        except Exception:
            pass
        return
    # 没有接别的：显示一行最简单的
    parts = []
    names = {"five_hour": "5h", "seven_day": "7d"}
    for key, w in fresh.items():
        parts.append(f"{names.get(key, key)} {round(w['utilization'] * 100)}%")
    model = ((data.get("model") or {}).get("display_name")) or ""
    print(" · ".join([p for p in [model] + parts if p]))


if __name__ == "__main__":
    main()
