"""kvote 的数据文件：一份很小的 JSON，记着 Claude 订阅每个额度窗口用了多少、什么时候重置。

    {"updated": 1791566636, "status": "allowed",
     "windows": {"five_hour": {"utilization": 0.18, "resetsAt": 1791579600},
                 "seven_day": {"utilization": 0.05, "resetsAt": 1792152000}}}

utilization 是 0~1，resetsAt / updated 是 Unix 秒。写的一方（capture/ 里的几个小工具）只管把 Claude Code
自己给出的数存进来；读的一方（server.py）只读。默认放在 ~/.claude/kvote-usage.json，用环境变量 KVOTE_FILE 改。
只用标准库。
"""
import json
import os
import time
from pathlib import Path

DEFAULT_FILE = Path(os.environ.get("KVOTE_FILE") or (Path.home() / ".claude" / "kvote-usage.json"))


def merge(prev, fresh, status=None, now=None):
    """上一份 + 这一次拿到的窗口 → 新的一份。这次没带的窗口（比如按模型分的那个）没过重置时间就留着。"""
    now = int(now if now is not None else time.time())
    windows = {}
    for key, w in ((prev or {}).get("windows") or {}).items():
        try:
            if float(w.get("resetsAt") or 0) > now:
                windows[key] = w
        except (AttributeError, TypeError, ValueError):
            pass
    got = 0
    for key, w in (fresh or {}).items():
        try:
            u, r = float(w["utilization"]), int(float(w["resetsAt"]))
        except (KeyError, TypeError, ValueError):
            continue
        windows[key] = {"utilization": u, "resetsAt": r}
        got += 1
    if not got:
        return None
    return {"updated": now, "status": status, "windows": windows}


def load(path=DEFAULT_FILE):
    try:
        return json.loads(Path(path).read_text("utf-8"))
    except (OSError, ValueError):
        return None


def record(fresh, status=None, path=DEFAULT_FILE):
    """存一次；坏数据不写，写失败返回 False，绝不抛（这些小工具挂在 Claude Code 旁边，不能把它带崩）。"""
    try:
        path = Path(path)
        nxt = merge(load(path), fresh, status)
        if not nxt:
            return False
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_name(f"{path.name}.{os.getpid()}.tmp")
        tmp.write_text(json.dumps(nxt), "utf-8")
        os.replace(tmp, path)
        return True
    except Exception:
        return False


def from_rate_limit_info(info):
    """stream-json 里 rate_limit_event.rate_limit_info → {窗口: {utilization, resetsAt}}"""
    if not isinstance(info, dict):
        return {}
    wins = info.get("unifiedWindows")
    if isinstance(wins, dict):
        return wins
    # 老一点的格式只有一个窗口
    if info.get("rateLimitType") and info.get("utilization") is not None:
        return {info["rateLimitType"]: {"utilization": info["utilization"], "resetsAt": info.get("resetsAt")}}
    return {}


def from_statusline(data):
    """状态栏脚本 stdin 里的 rate_limits（百分比 0~100）→ 同一个格式"""
    out = {}
    for key, w in ((data or {}).get("rate_limits") or {}).items():
        if key == "spend_limit" or not isinstance(w, dict):
            continue
        try:
            out[key] = {"utilization": float(w["used_percentage"]) / 100.0, "resetsAt": w["resets_at"]}
        except (KeyError, TypeError, ValueError):
            continue
    return out
